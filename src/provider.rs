use crate::model::{Sample, State};
use serde::Deserialize;

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct Battery {
    pub instance_name: Option<String>,
    pub active: Option<bool>,
    pub charging: Option<bool>,
    pub discharging: Option<bool>,
    pub power_online: Option<bool>,
    pub charge_rate: Option<u32>,
    pub discharge_rate: Option<u32>,
    pub voltage: Option<u32>,
    pub remaining_capacity: Option<u32>,
}
pub fn valid(value: Option<u32>) -> Option<f64> {
    value.filter(|v| *v != u32::MAX).map(f64::from)
}
/// GetSystemPowerStatus remaining runtime is meaningful only on discharge.
pub fn apply_kernel_eta(s: &mut Sample, seconds: u32) {
    if s.state == State::Discharging && s.battery_present != Some(false) && seconds != u32::MAX {
        s.eta_s = Some(seconds);
        s.eta_source = Some("Windows reported".into());
    } else {
        s.eta_s = None;
        s.eta_source = None;
    }
}
pub fn apply_batteries(s: &mut Sample, batteries: &[Battery]) {
    let active: Vec<_> = batteries
        .iter()
        .filter(|b| b.active != Some(false))
        .collect();
    if active.is_empty() {
        return;
    }
    s.battery_present = Some(true);
    if active.len() != 1 {
        s.diagnostics.push(format!(
            "{} active batteries: aggregation not implemented; watts unavailable",
            active.len()
        ));
        return;
    }
    let b = active[0];
    s.diagnostics.push(format!(
        "battery instance: {}",
        b.instance_name.as_deref().unwrap_or("unknown")
    ));
    s.state = match (b.charging, b.discharging) {
        (Some(true), Some(false)) => State::Charging,
        (Some(false), Some(true)) => State::Discharging,
        (Some(false), Some(false)) => State::Idle,
        _ => State::Unknown,
    };
    s.watts = match s.state {
        State::Charging => valid(b.charge_rate).map(|v| v / 1000.0),
        State::Discharging => valid(b.discharge_rate).map(|v| -v / 1000.0),
        State::Idle
            if valid(b.charge_rate) == Some(0.0) && valid(b.discharge_rate) == Some(0.0) =>
        {
            Some(0.0)
        }
        _ => None,
    };
    s.remaining_wh = valid(b.remaining_capacity).map(|v| v / 1000.0);
    s.volts = valid(b.voltage).filter(|v| *v > 0.0).map(|v| v / 1000.0);
    s.amps = s.watts.zip(s.volts).map(|(w, v)| w / v);
    if s.ac_online.is_none() {
        s.ac_online = b.power_online;
    }
    if s.watts.is_some() {
        s.source = "wmi:root\\wmi:BatteryStatus:fallback_unverified".into();
        s.measurement_kind = "battery_flow_fallback_unverified".into();
        s.available = true;
        s.reason = None;
    }
}

#[cfg(windows)]
pub struct Provider {
    meter: Option<crate::power_meter::PowerMeter>,
    system_meter: Option<String>,
    collector: crate::native_battery::Collector,
}
#[cfg(windows)]
impl Default for Provider {
    fn default() -> Self {
        Self::new()
    }
}
#[cfg(windows)]
impl Provider {
    pub fn new() -> Self {
        Self {
            meter: None,
            system_meter: None,
            collector: Default::default(),
        }
    }
    pub fn with_system_meter(selected: Option<String>) -> Self {
        Self {
            system_meter: selected,
            ..Self::new()
        }
    }
    pub fn sample(&mut self, elapsed: f64) -> Sample {
        use windows_sys::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
        let mut s = Sample::empty(elapsed);
        let mut status: SYSTEM_POWER_STATUS = unsafe { std::mem::zeroed() };
        if unsafe { GetSystemPowerStatus(&mut status) } != 0 {
            s.source = "k32:GetSystemPowerStatus".into();
            s.ac_online = match status.ACLineStatus {
                0 => Some(false),
                1 => Some(true),
                _ => None,
            };
            s.battery_present = if status.BatteryFlag == 255 {
                None
            } else {
                Some(status.BatteryFlag & 128 == 0)
            };
            if s.battery_present != Some(false) {
                s.state = if status.BatteryFlag == 255 {
                    State::Unknown
                } else if status.BatteryFlag & 8 != 0 {
                    State::Charging
                } else if s.ac_online == Some(false) {
                    State::Discharging
                } else {
                    State::Unknown
                };
                s.pct = (status.BatteryLifePercent <= 100)
                    .then_some(f64::from(status.BatteryLifePercent));
                s.eta_s = (status.BatteryLifeTime != u32::MAX).then_some(status.BatteryLifeTime);
            }
            s.diagnostics.push("k32: available".into());
        } else {
            s.diagnostics
                .push(format!("k32: {}", std::io::Error::last_os_error()));
        }
        let kernel_eta = s.eta_s.unwrap_or(u32::MAX);
        self.collector.collect(
            &mut crate::native_battery::WindowsBackend::default(),
            &mut s,
        );
        s.diagnostics
            .push("wmi: not queried; native collections never mixed or masked by fallback".into());
        if s.battery_count == 1 && s.available && !s.transition {
            apply_kernel_eta(&mut s, kernel_eta);
        }
        if s.battery_present == Some(false) {
            let meter = self.meter.get_or_insert_with(|| {
                crate::power_meter::PowerMeter::new(self.system_meter.clone())
            });
            meter.sample(&mut s);
        } else {
            s.diagnostics
                .push("pdh: not selected; battery presence not confirmed absent".into());
        }
        s
    }
}
#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
pub struct Provider;
#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
impl Default for Provider {
    fn default() -> Self {
        Self::new()
    }
}
#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
impl Provider {
    pub fn new() -> Self {
        Self
    }
    pub fn with_system_meter(_: Option<String>) -> Self {
        Self
    }
    pub fn sample(&mut self, elapsed: f64) -> Sample {
        let mut s = Sample::empty(elapsed);
        s.diagnostics.push("Unsupported platform".into());
        s
    }
}

#[cfg(target_os = "macos")]
#[derive(Default)]
pub struct Provider {
    backend: crate::macos::MacosBackend,
    collector: crate::native_battery::Collector,
    system_meter: Option<String>,
}

#[cfg(target_os = "macos")]
impl Provider {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_system_meter(system_meter: Option<String>) -> Self {
        Self {
            system_meter,
            ..Self::new()
        }
    }

    pub fn sample(&mut self, elapsed: f64) -> Sample {
        let mut sample = Sample::empty(elapsed);
        let fixture = std::env::var_os("WPM_IOREG_FIXTURE").is_some();
        self.collector.collect(&mut self.backend, &mut sample);
        sample.source = crate::macos::SOURCE.into();
        if sample.battery_count == 0 && !sample.transition {
            sample.battery_present = Some(false);
            sample.reason = Some("no_battery_supply".into());
        } else if sample.battery_count == 1 && !sample.transition {
            sample.pct = self.backend.percent_hint;
        }
        sample
            .diagnostics
            .push("macos: capacity Wh estimates use charge and nominal or live voltage".into());
        if let Some(name) = &self.system_meter {
            sample.diagnostics.push(format!(
                "--system-meter {name}: unsupported on macOS; battery flow only"
            ));
        }
        if !fixture {
            match crate::macos_smc::read_battery().and_then(|(mv, ma)| {
                sample
                    .diagnostics
                    .push(format!("macos_smc_reading: mv={mv} ma={ma}"));
                crate::macos_smc::apply_battery(&mut sample, mv, ma).map_err(str::to_owned)
            }) {
                Ok(()) => sample
                    .diagnostics
                    .push("macos: experimental SMC battery flow".into()),
                Err(error) => sample
                    .diagnostics
                    .push(format!("macos_smc_fallback: {error}")),
            }
        }
        sample
    }
}

/// Linux provider: `/sys/class/power_supply` battery flow.
///
/// Deliberately thinner than the Windows provider: Windows merges a kernel
/// status call, a native IOCTL collection, an optional WMI fallback and an
/// optional PDH meter. Linux has one authoritative source, so the shared
/// `Collector` and `apply_members` do all validation and aggregation and this
/// type only repairs what the shared code assumes about the platform.
#[cfg(target_os = "linux")]
pub struct Provider {
    backend: crate::sysfs::SysfsBackend,
    collector: crate::native_battery::Collector,
    system_meter: Option<String>,
}
#[cfg(target_os = "linux")]
impl Default for Provider {
    fn default() -> Self {
        Self::new()
    }
}
#[cfg(target_os = "linux")]
impl Provider {
    pub fn new() -> Self {
        let backend = std::env::var_os("WPM_SYSFS_ROOT").map_or_else(
            crate::sysfs::SysfsBackend::default,
            crate::sysfs::SysfsBackend::with_root,
        );
        Self {
            backend,
            collector: Default::default(),
            system_meter: None,
        }
    }
    pub fn with_system_meter(selected: Option<String>) -> Self {
        Self {
            system_meter: selected,
            ..Self::new()
        }
    }
    pub fn sample(&mut self, elapsed: f64) -> Sample {
        let mut s = Sample::empty(elapsed);
        self.collector.collect(&mut self.backend, &mut s);
        // `invalidate` and `apply_members` hardcode the Windows source label.
        s.source = crate::sysfs::SOURCE.into();
        // Three snapshots per tick repeat per-supply notes; keep one of each.
        s.diagnostics.dedup();
        if let Some(online) = self.backend.ac_online() {
            s.ac_online = Some(online);
        }
        if s.battery_count == 0 && !s.transition {
            s.battery_present = Some(false);
            s.reason = Some("no_battery_supply".into());
        } else if s.pct.is_none() && !s.transition && s.battery_count == 1 {
            // Energy-derived percentage wins; this covers packs that only
            // expose `capacity` (%). Never averaged across batteries.
            s.pct = self.backend.percent_hint();
        }
        if let Some(name) = &self.system_meter {
            s.diagnostics.push(format!(
                "--system-meter {name}: no PDH Power Meter analogue on Linux; battery flow only"
            ));
        }
        s
    }
}
