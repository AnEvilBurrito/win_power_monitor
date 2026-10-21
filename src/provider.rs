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
        s.source = "wmi:root\\wmi:BatteryStatus".into();
        s.measurement_kind = "battery_flow".into();
        s.available = true;
        s.reason = None;
    }
}

#[cfg(windows)]
pub struct Provider {
    connection: Option<wmi::WMIConnection>,
    error: Option<String>,
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
        let result = wmi::COMLibrary::new()
            .and_then(|com| wmi::WMIConnection::with_namespace_path("ROOT\\WMI", com));
        match result {
            Ok(connection) => Self {
                connection: Some(connection),
                error: None,
            },
            Err(e) => Self {
                connection: None,
                error: Some(e.to_string()),
            },
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
        if let Some(connection) = &self.connection {
            match connection.raw_query::<Battery>("SELECT InstanceName, Active, Charging, Discharging, PowerOnline, ChargeRate, DischargeRate, Voltage, RemainingCapacity FROM BatteryStatus") {
                Ok(batteries) => {
                    s.diagnostics.push(format!("wmi: query OK, {} row(s)", batteries.len()));
                    apply_batteries(&mut s, &batteries);
                },
                Err(e) => s.diagnostics.push(format!("wmi: query failed: {e}")),
            }
        } else {
            s.diagnostics.push(format!(
                "wmi: initialization failed: {}",
                self.error.as_deref().unwrap_or("unknown")
            ));
        }
        let kernel_eta = s.eta_s.unwrap_or(u32::MAX);
        apply_kernel_eta(&mut s, kernel_eta);
        if s.battery_present == Some(false) {
            s.reason = Some("no_battery".into());
        }
        s.diagnostics
            .push("pdh: not implemented; capacity-delta fallback: not implemented".into());
        s
    }
}
#[cfg(not(windows))]
pub struct Provider;
#[cfg(not(windows))]
impl Default for Provider {
    fn default() -> Self {
        Self::new()
    }
}
#[cfg(not(windows))]
impl Provider {
    pub fn new() -> Self {
        Self
    }
    pub fn sample(&mut self, elapsed: f64) -> Sample {
        let mut s = Sample::empty(elapsed);
        s.diagnostics.push("Windows required".into());
        s
    }
}
