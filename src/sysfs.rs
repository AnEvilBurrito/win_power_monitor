//! Linux `/sys/class/power_supply` reader.
//!
//! Parsing is pure and compiled on every platform so fixtures run on Windows CI
//! too. Values are converted into the Windows battery-class shape
//! (`native_battery::{Member, Reading}`) so `Collector`, its three-snapshot
//! validation, and `apply_members` stay untouched and identical for both
//! providers. Only the directory walk is `target_os = "linux"`.
//!
//! sysfs reports magnitudes with direction in `status`, in micro-units, and has
//! no battery-tag concept; this module supplies the sign, the milli-unit
//! conversion, and a constant tag.

use crate::model::State;
use crate::native_battery::{Member, Reading};
use std::collections::BTreeMap;

/// `Sample::source` value for readings from this module.
pub const SOURCE: &str = "native:sysfs_power_supply";

/// Default power-supply class directory.
pub const DEFAULT_ROOT: &str = "/sys/class/power_supply";

/// Attribute names to trimmed values for one supply directory.
pub type Attrs = BTreeMap<String, String>;

/// Windows `BATTERY_SYSTEM_BATTERY`; `canonical()` requires it and requires
/// `0x2000_0000` (short-term) and `0x4000_0000` (relative units) to be clear.
const SYSTEM_BATTERY: u32 = 0x8000_0000;
/// Sentinel used by the shared model for "not reported".
const UNKNOWN: u32 = u32::MAX;
/// sysfs has no battery tag; a constant is stable across snapshots, which is
/// all `Collector` requires to detect topology churn.
const TAG: u32 = 1;

const MICRO_WH_TO_MWH: f64 = 1e-3;
const MICRO_W_TO_MW: f64 = 1e-3;
const MICRO_V_TO_MV: f64 = 1e-3;
/// µA·µV = 1e-12 W = 1e-9 mW.
const MICRO_A_MICRO_V_TO_MW: f64 = 1e-9;
/// µAh·µV = 1e-12 Wh = 1e-9 mWh.
const MICRO_AH_MICRO_V_TO_MWH: f64 = 1e-9;

fn attr<'a>(attrs: &'a Attrs, key: &str) -> Option<&'a str> {
    attrs.get(key).map(|v| v.trim()).filter(|v| !v.is_empty())
}

/// Integer attribute in its native micro/milli unit.
pub fn micro(attrs: &Attrs, key: &str) -> Option<i64> {
    attr(attrs, key)?.parse::<i64>().ok()
}

/// A usable system battery. Rejects peripherals (`scope=Device`, or a pack that
/// exposes no energy/rate/voltage attributes at all — e.g. a HID mouse battery
/// reporting only `capacity`), and detached packs (`present=0`). Absent
/// `scope`/`present` means "accept", which is the common laptop case.
pub fn is_battery(attrs: &Attrs) -> bool {
    attr(attrs, "type") == Some("Battery")
        && attr(attrs, "scope") != Some("Device")
        && attr(attrs, "present") != Some("0")
        && ["energy_now", "charge_now", "power_now", "voltage_now"]
            .iter()
            .any(|key| micro(attrs, key).is_some())
}

/// AC adapter state from a `type=Mains` supply.
pub fn ac_online(attrs: &Attrs) -> Option<bool> {
    if attr(attrs, "type") != Some("Mains") {
        return None;
    }
    match attr(attrs, "online")? {
        "0" => Some(false),
        "1" => Some(true),
        _ => None,
    }
}

/// Raw `capacity` percentage. Deliberately kept out of `Sample` so individual
/// percentages are never averaged for multiple batteries.
pub fn percent(attrs: &Attrs) -> Option<f64> {
    let value = micro(attrs, "capacity")?;
    (0..=100).contains(&value).then_some(value as f64)
}

fn status(attrs: &Attrs) -> &str {
    attr(attrs, "status").unwrap_or("Unknown")
}

/// `status` mapped onto the shared state vocabulary.
pub fn state(attrs: &Attrs) -> State {
    match status(attrs) {
        "Charging" => State::Charging,
        "Discharging" => State::Discharging,
        "Full" | "Not charging" => State::Idle,
        _ => State::Unknown,
    }
}

/// Remaining energy in mWh, from `energy_now` or `charge_now` × `voltage_now`.
pub fn remaining_mwh(attrs: &Attrs) -> Option<f64> {
    if let Some(energy) = micro(attrs, "energy_now") {
        return Some(energy as f64 * MICRO_WH_TO_MWH);
    }
    charge_mwh(attrs, "charge_now")
}

/// Full-charge energy in mWh, from `energy_full` or `charge_full` × `voltage_now`.
pub fn full_mwh(attrs: &Attrs) -> Option<f64> {
    if let Some(energy) = micro(attrs, "energy_full") {
        return Some(energy as f64 * MICRO_WH_TO_MWH);
    }
    charge_mwh(attrs, "charge_full")
}

/// Design energy in mWh, from `energy_full_design` or `charge_full_design`.
pub fn design_mwh(attrs: &Attrs) -> Option<f64> {
    if let Some(energy) = micro(attrs, "energy_full_design") {
        return Some(energy as f64 * MICRO_WH_TO_MWH);
    }
    charge_mwh(attrs, "charge_full_design")
}

fn charge_mwh(attrs: &Attrs, key: &str) -> Option<f64> {
    Some(micro(attrs, key)? as f64 * micro(attrs, "voltage_now")? as f64 * MICRO_AH_MICRO_V_TO_MWH)
}

/// Signed power in mW from `power_now`, else `current_now` × `voltage_now`.
pub fn power_mw(attrs: &Attrs) -> Option<f64> {
    if let Some(power) = micro(attrs, "power_now") {
        return Some(power as f64 * MICRO_W_TO_MW);
    }
    Some(
        micro(attrs, "current_now")? as f64
            * micro(attrs, "voltage_now")? as f64
            * MICRO_A_MICRO_V_TO_MW,
    )
}

fn milli(value: Option<f64>) -> u32 {
    value
        .filter(|v| v.is_finite() && *v >= 0.0 && *v < f64::from(UNKNOWN))
        .map_or(UNKNOWN, |v| v.round() as u32)
}

fn power_state(ac: Option<bool>, state: State) -> u32 {
    let online = match (ac, state) {
        (Some(online), _) => Some(online),
        (None, State::Charging | State::Idle) => Some(true),
        (None, State::Discharging) => Some(false),
        (None, _) => None,
    };
    let mut bits = match state {
        State::Charging => 4,
        State::Discharging => 2,
        _ => 0,
    };
    if online == Some(true) {
        bits |= 1;
    }
    bits
}

/// One supply directory as a `Member`.
///
/// Never fails: unusable readings become the shared model's sentinels
/// (`i32::MIN` rate, `u32::MAX` capacity), which `diagnostic()` already turns
/// into published reason codes. `diagnostics` records anything discarded.
pub fn parse_member(
    id: &str,
    attrs: &Attrs,
    ac: Option<bool>,
    diagnostics: &mut Vec<String>,
) -> Member {
    let state = state(attrs);
    let magnitude = power_mw(attrs).filter(|v| v.is_finite()).map(f64::abs);
    let rate = match state {
        State::Charging => magnitude.map(|v| v.round()),
        State::Discharging => magnitude.map(|v| -v.round()),
        // Windows treats idle as exactly zero flow; keep that contract so a
        // plugged-in, fully-charged pack stays available rather than rejected.
        State::Idle => {
            if let Some(reported) = magnitude.filter(|v| *v >= 1.0) {
                diagnostics.push(format!(
                    "{id}: idle status reports {reported:.0} mW; recorded as 0 to match the idle contract"
                ));
            }
            Some(0.0)
        }
        _ => None,
    };
    let reading = Reading {
        capabilities: SYSTEM_BATTERY,
        power_state: power_state(ac, state),
        capacity: milli(remaining_mwh(attrs)),
        voltage: milli(micro(attrs, "voltage_now").map(|v| v as f64 * MICRO_V_TO_MV)),
        rate: rate
            .filter(|v| v.is_finite() && *v > f64::from(i32::MIN) && *v <= f64::from(i32::MAX))
            .map_or(i32::MIN, |v| v as i32),
    };
    Member {
        id: id.to_owned(),
        tag: TAG,
        full_charged_capacity: milli(full_mwh(attrs)),
        designed_capacity: milli(design_mwh(attrs)),
        default_alert1: UNKNOWN,
        default_alert2: UNKNOWN,
        critical_bias: UNKNOWN,
        cycle_count: micro(attrs, "cycle_count")
            .and_then(|v| u32::try_from(v).ok())
            .filter(|v| *v != UNKNOWN)
            .unwrap_or(UNKNOWN),
        technology: 0,
        chemistry: [0; 4],
        reading,
    }
}

#[cfg(target_os = "linux")]
pub use linux::SysfsBackend;

#[cfg(target_os = "linux")]
mod linux {
    use super::{Attrs, DEFAULT_ROOT, Member, ac_online, is_battery, parse_member, percent};
    use crate::native_battery::Backend;
    use std::{
        fs,
        path::{Path, PathBuf},
        time::{SystemTime, UNIX_EPOCH},
    };

    /// Only attributes the conversion reads. Three snapshots per tick make a
    /// blanket directory read wasteful, and `device`/`subsystem` symlinks add
    /// errors with no information.
    const KEYS: &[&str] = &[
        "type",
        "scope",
        "present",
        "status",
        "online",
        "capacity",
        "power_now",
        "voltage_now",
        "current_now",
        "energy_now",
        "energy_full",
        "energy_full_design",
        "charge_now",
        "charge_full",
        "charge_full_design",
        "cycle_count",
    ];

    /// Reads `/sys/class/power_supply` on every snapshot; nothing is cached, so
    /// attachment changes are detected the same way the Windows backend detects
    /// them. `with_root` exists so a fixture tree can be walked in tests.
    pub struct SysfsBackend {
        root: PathBuf,
        ac: Option<bool>,
        pct: Option<f64>,
    }

    impl SysfsBackend {
        pub fn with_root(root: impl Into<PathBuf>) -> Self {
            Self {
                root: root.into(),
                ac: None,
                pct: None,
            }
        }

        /// AC state observed during the last snapshot.
        pub fn ac_online(&self) -> Option<bool> {
            self.ac
        }

        /// `capacity` percentage, only when exactly one battery is present.
        pub fn percent_hint(&self) -> Option<f64> {
            self.pct
        }
    }

    impl Default for SysfsBackend {
        fn default() -> Self {
            Self::with_root(DEFAULT_ROOT)
        }
    }

    fn read_attrs(dir: &Path) -> Attrs {
        let mut attrs = Attrs::new();
        for key in KEYS {
            if let Ok(value) = fs::read_to_string(dir.join(key)) {
                attrs.insert((*key).to_owned(), value.trim().to_owned());
            }
        }
        attrs
    }

    impl Backend for SysfsBackend {
        fn snapshot(&mut self, diagnostics: &mut Vec<String>) -> Result<Vec<Member>, String> {
            let entries = fs::read_dir(&self.root)
                .map_err(|e| format!("sysfs_unavailable: {}: {e}", self.root.display()))?;
            let mut supplies: Vec<(String, Attrs)> = Vec::new();
            for entry in entries.flatten() {
                let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
                    continue;
                };
                let attrs = read_attrs(&entry.path());
                if !attrs.is_empty() {
                    supplies.push((name, attrs));
                }
            }
            let adapters: std::collections::BTreeSet<bool> = supplies
                .iter()
                .filter_map(|(_, attrs)| ac_online(attrs))
                .collect();
            if adapters.len() > 1 {
                diagnostics.push("AC adapters disagree; AC state unknown".into());
            }
            self.ac = if adapters.len() == 1 {
                adapters.into_iter().next()
            } else {
                None
            };
            let mut members = Vec::new();
            let mut battery_count = 0usize;
            let mut pct = None;
            for (name, attrs) in &supplies {
                if !is_battery(attrs) {
                    continue;
                }
                battery_count += 1;
                members.push(parse_member(name, attrs, self.ac, diagnostics));
                pct = percent(attrs);
            }
            if battery_count == 0 {
                diagnostics.push(format!(
                    "sysfs: no battery supply under {}",
                    self.root.display()
                ));
            }
            self.pct = (battery_count == 1).then_some(pct).flatten();
            Ok(members)
        }

        fn now_s(&self) -> f64 {
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs_f64()
        }
    }
}
