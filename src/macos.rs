use crate::native_battery::{Member, Reading};
use plist::{Dictionary, Value};

pub const SOURCE: &str = "native:macos_ioreg";

fn integer(properties: &Dictionary, key: &str) -> Option<i64> {
    let value = properties.get(key)?;
    value
        .as_signed_integer()
        .or_else(|| value.as_unsigned_integer().map(|number| number as i64))
}

fn boolean(properties: &Dictionary, key: &str) -> Option<bool> {
    properties.get(key)?.as_boolean()
}

fn current(properties: &Dictionary) -> Option<i64> {
    let value =
        integer(properties, "InstantAmperage").or_else(|| integer(properties, "Amperage"))?;
    let signed = if (i64::from(i32::MAX)..=i64::from(u32::MAX)).contains(&value) {
        i64::from(value as u32 as i32)
    } else {
        value
    };
    (-100_000..=100_000).contains(&signed).then_some(signed)
}

pub fn percent(properties: &Dictionary) -> Option<f64> {
    let ratio = |remaining, full| {
        let remaining = integer(properties, remaining)?;
        let full = integer(properties, full)?;
        (full > 0 && remaining >= 0 && remaining <= full)
            .then_some(100.0 * remaining as f64 / full as f64)
    };
    ratio("AppleRawCurrentCapacity", "AppleRawMaxCapacity")
        .or_else(|| ratio("CurrentCapacity", "MaxCapacity"))
}

pub fn parse_member(properties: &Dictionary) -> Result<Option<Member>, String> {
    if boolean(properties, "BatteryInstalled") == Some(false) {
        return Ok(None);
    }
    let identity = properties
        .get("IORegistryEntryID")
        .and_then(Value::as_unsigned_integer)
        .filter(|identity| *identity != 0)
        .ok_or("macos_missing_battery_identity")?;
    let voltage = integer(properties, "Voltage")
        .filter(|value| (1..=100_000).contains(value))
        .map_or(u32::MAX, |value| value as u32);
    let amperage = current(properties);
    let ac = boolean(properties, "ExternalConnected");
    let charging = boolean(properties, "IsCharging");
    let direction = match (charging, amperage) {
        (Some(true), Some(value)) if value >= 0 => Some(4),
        (Some(false), Some(value)) if value < 0 => Some(2),
        (Some(false), Some(0)) => Some(0),
        _ => None,
    };
    let rate = amperage
        .filter(|_| direction.is_some() && ac.is_some() && voltage != u32::MAX)
        .and_then(|value| i32::try_from(value * i64::from(voltage) / 1000).ok())
        .filter(|value| *value != i32::MIN)
        .unwrap_or(i32::MIN);
    Ok(Some(Member {
        id: format!("ioreg:{identity}"),
        tag: 1,
        full_charged_capacity: u32::MAX,
        designed_capacity: u32::MAX,
        default_alert1: u32::MAX,
        default_alert2: u32::MAX,
        critical_bias: u32::MAX,
        cycle_count: integer(properties, "CycleCount")
            .and_then(|value| u32::try_from(value).ok())
            .unwrap_or(u32::MAX),
        technology: 0,
        chemistry: [0; 4],
        reading: Reading {
            capabilities: 0x8000_0000,
            power_state: direction.unwrap_or(0) | u32::from(ac == Some(true)),
            capacity: u32::MAX,
            voltage,
            rate,
        },
    }))
}

pub fn parse_snapshot(bytes: &[u8]) -> Result<Vec<Dictionary>, String> {
    let value = Value::from_reader(std::io::Cursor::new(bytes))
        .map_err(|error| format!("macos_invalid_plist: {error}"))?;
    value
        .as_array()
        .ok_or_else(|| "macos_invalid_plist_root".to_owned())?
        .iter()
        .map(|value| {
            value
                .as_dictionary()
                .cloned()
                .ok_or_else(|| "macos_invalid_battery_dictionary".to_owned())
        })
        .collect()
}

#[cfg(target_os = "macos")]
#[derive(Default)]
pub struct MacosBackend {
    pub percent_hint: Option<f64>,
}

#[cfg(target_os = "macos")]
impl crate::native_battery::Backend for MacosBackend {
    fn snapshot(&mut self, _: &mut Vec<String>) -> Result<Vec<Member>, String> {
        self.percent_hint = None;
        let bytes = if let Some(path) = std::env::var_os("WPM_IOREG_FIXTURE") {
            std::fs::read(path).map_err(|error| format!("macos_fixture: {error}"))?
        } else {
            let output = std::process::Command::new("/usr/sbin/ioreg")
                .args(["-a", "-r", "-c", "AppleSmartBattery"])
                .output()
                .map_err(|error| format!("macos_ioreg: {error}"))?;
            if !output.status.success() {
                return Err(format!("macos_ioreg_exit: {}", output.status));
            }
            output.stdout
        };
        let properties = parse_snapshot(&bytes)?;
        let mut members = Vec::new();
        let mut hint = None;
        for battery in properties {
            if let Some(member) = parse_member(&battery)? {
                hint = percent(&battery);
                members.push(member);
            }
        }
        self.percent_hint = (members.len() == 1).then_some(hint).flatten();
        Ok(members)
    }

    fn now_s(&self) -> f64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs_f64()
    }
}
