//! Immediate battery-class status requests; no WMI refresh side effects are required.
use crate::model::{Sample, State};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Reading {
    pub capabilities: u32,
    pub power_state: u32,
    pub capacity: u32,
    pub voltage: u32,
    pub rate: i32,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct BatteryDiagnostic {
    pub id: String,
    pub tag: u32,
    pub state: State,
    pub available: bool,
    pub reason: Option<String>,
    pub watts: Option<f64>,
    pub volts: Option<f64>,
    pub amps: Option<f64>,
    pub remaining_wh: Option<f64>,
    pub full_charged_capacity: u32,
    pub designed_capacity: u32,
    pub default_alert1: u32,
    pub default_alert2: u32,
    pub critical_bias: u32,
    pub cycle_count: u32,
    pub technology: u8,
    pub chemistry: [u8; 4],
    pub capabilities: u32,
    pub power_state: u32,
    pub capacity: u32,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Member {
    pub id: String,
    pub tag: u32,
    pub full_charged_capacity: u32,
    pub designed_capacity: u32,
    pub default_alert1: u32,
    pub default_alert2: u32,
    pub critical_bias: u32,
    pub cycle_count: u32,
    pub technology: u8,
    pub chemistry: [u8; 4],
    pub reading: Reading,
}
/// Each snapshot opens/queries/closes all devices. No cached rates or handles.
/// Repeating full discovery also validates attachment during sequential reads.
pub trait Backend {
    fn snapshot(&mut self, diagnostics: &mut Vec<String>) -> Result<Vec<Member>, String>;
    fn now_s(&self) -> f64;
    fn take_failures(&mut self) -> Vec<BatteryDiagnostic> {
        Vec::new()
    }
}
#[derive(Default)]
pub struct Collector {
    membership: Option<Vec<(String, u32)>>,
    observations: Option<Vec<(String, u32)>>,
    epoch: u64,
    invalidated: bool,
}
fn canonical(rows: Vec<Member>) -> Result<Vec<Member>, String> {
    let mut members = std::collections::BTreeMap::new();
    for mut m in rows.into_iter().filter(|m| {
        m.reading.capabilities & 0x8000_0000 != 0 && m.reading.capabilities & 0x2000_0000 == 0
    }) {
        m.id = m.id.to_lowercase();
        if m.id.is_empty() || m.tag == 0 {
            return Err("native_invalid_identity".into());
        }
        if let Some(old) = members.insert(m.id.clone(), m.clone())
            && (old != m)
        {
            return Err("native_duplicate_conflict".into());
        }
    }
    Ok(members.into_values().collect())
}
fn keys(rows: &[Member]) -> Vec<(String, u32)> {
    rows.iter().map(|m| (m.id.clone(), m.tag)).collect()
}
fn observations(rows: &[Member]) -> Vec<(String, u32)> {
    rows.iter()
        .map(|m| (m.id.clone(), m.reading.power_state & 7))
        .collect()
}
impl Collector {
    /// One collection per tick, at most three snapshots and two tag attempts per device.
    /// Failure is published, never hidden by retrying to a numeric total this tick.
    pub fn collect(&mut self, backend: &mut impl Backend, s: &mut Sample) {
        s.watts = None;
        s.volts = None;
        s.amps = None;
        s.remaining_wh = None;
        s.available = false;
        s.batteries.clear();
        s.battery_count = 0;
        s.collection_start_s = Some(backend.now_s());
        let result = (|| {
            let before = canonical(backend.snapshot(&mut s.diagnostics)?)?;
            s.battery_count = before.len();
            if !before.is_empty() {
                s.battery_present = Some(true);
            }
            s.batteries = before.iter().map(diagnostic).collect();
            let reads = canonical(backend.snapshot(&mut s.diagnostics)?)?;
            s.batteries = reads.iter().map(diagnostic).collect();
            let after = canonical(backend.snapshot(&mut s.diagnostics)?)?;
            if before
                .iter()
                .chain(&reads)
                .chain(&after)
                .any(|m| !diagnostic(m).available)
            {
                return Err(before
                    .iter()
                    .chain(&reads)
                    .chain(&after)
                    .find_map(|m| diagnostic(m).reason)
                    .unwrap_or_else(|| "native_invalid_member".into()));
            }
            if keys(&before) != keys(&reads) || keys(&reads) != keys(&after) {
                return Err("native_topology_transition".into());
            }
            if observations(&before) != observations(&reads)
                || observations(&reads) != observations(&after)
            {
                return Err("native_charger_transition".into());
            }
            let ac: std::collections::BTreeSet<_> =
                reads.iter().map(|m| m.reading.power_state & 1).collect();
            if ac.len() > 1 {
                return Err("native_conflicting_ac".into());
            }
            let k = keys(&reads);
            let obs = observations(&reads);
            let changed = self.membership.as_ref().is_some_and(|old| *old != k);
            let direction_changed = self.observations.as_ref().is_some_and(|old| *old != obs);
            if changed {
                self.epoch += 1;
            }
            s.transition = changed || direction_changed || self.invalidated;
            s.gap |= s.transition;
            self.membership = Some(k);
            self.observations = Some(obs);
            self.invalidated = false;
            apply_members(s, &reads);
            if !reads.is_empty() && !s.available {
                self.invalidated = true;
                let reason = s
                    .reason
                    .clone()
                    .unwrap_or_else(|| "native_invalid_member".into());
                invalidate(s, &reason);
            } else if s.transition {
                invalidate(s, "native_transition");
            }
            Ok::<_, String>(())
        })();
        if let Err(reason) = result {
            s.batteries.extend(backend.take_failures());
            self.invalidated = true;
            invalidate(s, &reason);
        }
        s.topology_epoch = self.epoch;
        s.collection_end_s = Some(backend.now_s());
    }
}
fn invalidate(s: &mut Sample, reason: &str) {
    s.watts = None;
    s.volts = None;
    s.amps = None;
    s.pct = None;
    s.remaining_wh = None;
    s.eta_s = None;
    s.eta_source = None;
    s.available = false;
    s.state = State::Unknown;
    s.transition = true;
    s.gap = true;
    s.reason = Some(reason.into());
    s.source = "native:IOCTL_BATTERY_QUERY_STATUS".into();
}
fn diagnostic(m: &Member) -> BatteryDiagnostic {
    let b = m.reading;
    let state = match (b.power_state & 4 != 0, b.power_state & 2 != 0) {
        (true, false) => State::Charging,
        (false, true) => State::Discharging,
        (false, false) => State::Idle,
        _ => State::Unknown,
    };
    let reason = if b.capabilities & 0x4000_0000 != 0 {
        Some("native_relative_units")
    } else if b.rate == i32::MIN
        || !match state {
            State::Charging => b.rate >= 0,
            State::Discharging => b.rate <= 0,
            State::Idle => b.rate == 0,
            _ => false,
        }
    {
        Some("native_unknown_or_conflicting_rate")
    } else {
        None
    };
    let watts = reason.is_none().then(|| f64::from(b.rate) / 1000.0);
    let absolute = b.capabilities & 0x4000_0000 == 0;
    let volts = (absolute && b.voltage != 0 && b.voltage != u32::MAX)
        .then(|| f64::from(b.voltage) / 1000.0);
    BatteryDiagnostic {
        id: m.id.clone(),
        tag: m.tag,
        state,
        available: reason.is_none(),
        reason: reason.map(str::to_owned),
        watts,
        volts,
        amps: watts.zip(volts).map(|(w, v)| w / v),
        remaining_wh: (absolute && b.capacity != u32::MAX).then(|| f64::from(b.capacity) / 1000.0),
        full_charged_capacity: m.full_charged_capacity,
        designed_capacity: m.designed_capacity,
        default_alert1: m.default_alert1,
        default_alert2: m.default_alert2,
        critical_bias: m.critical_bias,
        cycle_count: m.cycle_count,
        technology: m.technology,
        chemistry: m.chemistry,
        capabilities: b.capabilities,
        power_state: b.power_state,
        capacity: b.capacity,
    }
}
pub fn apply_members(s: &mut Sample, members: &[Member]) {
    s.battery_count = members.len();
    s.batteries = members.iter().map(diagnostic).collect();
    if members.is_empty() {
        return;
    }
    s.battery_present = Some(true);
    s.source = "native:IOCTL_BATTERY_QUERY_STATUS".into();
    s.measurement_kind = "battery_flow".into();
    s.pct = None;
    s.eta_s = None;
    s.eta_source = None;
    s.watts = None;
    s.volts = None;
    s.amps = None;
    s.remaining_wh = None;
    let charge = s.batteries.iter().any(|b| b.state == State::Charging);
    let discharge = s.batteries.iter().any(|b| b.state == State::Discharging);
    s.state = if s.batteries.iter().any(|b| b.state == State::Unknown) {
        State::Unknown
    } else if charge && discharge {
        State::Mixed
    } else if charge {
        State::Charging
    } else if discharge {
        State::Discharging
    } else {
        State::Idle
    };
    s.ac_online = Some(members[0].reading.power_state & 1 != 0);
    s.remaining_wh = s.batteries.iter().map(|b| b.remaining_wh).sum();
    let full: Option<f64> = members
        .iter()
        .map(|m| {
            (m.full_charged_capacity > 0
                && m.full_charged_capacity != u32::MAX
                && m.reading.capacity != u32::MAX
                && m.reading.capacity <= m.full_charged_capacity)
                .then(|| f64::from(m.full_charged_capacity) / 1000.0)
        })
        .sum();
    s.pct = s
        .remaining_wh
        .zip(full)
        .map(|(remaining, full)| 100.0 * remaining / full);
    if members.len() == 1 {
        s.volts = s.batteries[0].volts;
        s.amps = s.batteries[0].amps;
    }
    if let Some(reason) = s.batteries.iter().find_map(|b| b.reason.clone()) {
        s.available = false;
        s.reason = Some(reason);
        return;
    }
    s.watts = s.batteries.iter().map(|b| b.watts).sum();
    s.available = s.watts.is_some();
    s.reason = None;
}
/// Legacy conversion entry point; production uses identity-aware Collector.
pub fn apply(s: &mut Sample, readings: &[Reading]) -> bool {
    let members: Vec<_> = readings
        .iter()
        .filter(|b| b.capabilities & 0x8000_0000 != 0 && b.capabilities & 0x2000_0000 == 0)
        .enumerate()
        .map(|(i, b)| Member {
            id: format!("legacy-{i}"),
            tag: 1,
            full_charged_capacity: u32::MAX,
            designed_capacity: u32::MAX,
            default_alert1: u32::MAX,
            default_alert2: u32::MAX,
            critical_bias: u32::MAX,
            cycle_count: u32::MAX,
            technology: 0,
            chemistry: [0; 4],
            reading: *b,
        })
        .collect();
    apply_members(s, &members);
    members.is_empty()
}

#[cfg(windows)]
mod device {
    use super::{BatteryDiagnostic, Member, Reading, diagnostic};
    use std::{io, mem::size_of, ptr, time::Instant};
    use windows_sys::Win32::{
        Devices::DeviceAndDriverInstallation::*,
        Foundation::{
            CloseHandle, ERROR_INSUFFICIENT_BUFFER, ERROR_NO_MORE_ITEMS, GENERIC_READ,
            GENERIC_WRITE, HANDLE, INVALID_HANDLE_VALUE,
        },
        Storage::FileSystem::{
            CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
        },
        System::{IO::DeviceIoControl, Power::*},
    };
    use windows_sys::core::GUID;

    const BATTERY_INTERFACE: GUID = GUID::from_u128(0x72631e54_78a4_11d0_bcf7_00aa00b7b32a);
    struct Device(HANDLE);
    impl Drop for Device {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
    struct DeviceSet(HDEVINFO);
    impl Drop for DeviceSet {
        fn drop(&mut self) {
            unsafe {
                SetupDiDestroyDeviceInfoList(self.0);
            }
        }
    }

    // Private callers use only fixed-size, zero-valid Windows battery POD structures.
    fn query<I, O>(
        device: &Device,
        code: u32,
        name: &str,
        input: &I,
        diagnostics: &mut Vec<String>,
    ) -> io::Result<O> {
        let start = Instant::now();
        let mut output: O = unsafe { std::mem::zeroed() };
        let mut returned = 0;
        let ok = unsafe {
            DeviceIoControl(
                device.0,
                code,
                (input as *const I).cast(),
                size_of::<I>() as u32,
                (&mut output as *mut O).cast(),
                size_of::<O>() as u32,
                &mut returned,
                ptr::null_mut(),
            )
        };
        let result = if ok == 0 {
            Err(io::Error::last_os_error())
        } else if returned as usize != size_of::<O>() {
            Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("short response: {returned}/{} bytes", size_of::<O>()),
            ))
        } else {
            Ok(output)
        };
        diagnostics.push(format!(
            "native: {name} {:.3}ms {}",
            start.elapsed().as_secs_f64() * 1000.0,
            result
                .as_ref()
                .map_or_else(|e| format!("failed: {e}"), |_| "OK".into())
        ));
        result
    }

    fn read(device: &Device, diagnostics: &mut Vec<String>) -> io::Result<Option<Member>> {
        for attempt in 0..2 {
            let result: io::Result<Option<Member>> = (|| {
                let tag: u32 = query(
                    device,
                    IOCTL_BATTERY_QUERY_TAG,
                    "IOCTL_BATTERY_QUERY_TAG wait=0",
                    &0u32,
                    diagnostics,
                )?;
                if tag == 0 {
                    return Ok(None);
                }
                let info: BATTERY_INFORMATION = query(
                    device,
                    IOCTL_BATTERY_QUERY_INFORMATION,
                    "IOCTL_BATTERY_QUERY_INFORMATION BatteryInformation",
                    &BATTERY_QUERY_INFORMATION {
                        BatteryTag: tag,
                        InformationLevel: BatteryInformation,
                        AtRate: 0,
                    },
                    diagnostics,
                )?;
                diagnostics.push(format!(
                    "native: tag={tag} capabilities=0x{:08x}",
                    info.Capabilities
                ));
                if info.Capabilities & BATTERY_SYSTEM_BATTERY == 0
                    || info.Capabilities & BATTERY_IS_SHORT_TERM != 0
                {
                    diagnostics.push("native: ignored non-system or short-term battery".into());
                    return Ok(None);
                }
                let status: BATTERY_STATUS = query(
                    device,
                    IOCTL_BATTERY_QUERY_STATUS,
                    "IOCTL_BATTERY_QUERY_STATUS Timeout=0",
                    &BATTERY_WAIT_STATUS {
                        BatteryTag: tag,
                        Timeout: 0,
                        PowerState: 0,
                        LowCapacity: 0,
                        HighCapacity: 0,
                    },
                    diagnostics,
                )?;
                diagnostics.push(format!(
                    "native: PowerState=0x{:08x} Rate={} Capacity={} Voltage={}",
                    status.PowerState, status.Rate, status.Capacity, status.Voltage
                ));
                let final_tag: u32 = query(
                    device,
                    IOCTL_BATTERY_QUERY_TAG,
                    "IOCTL_BATTERY_QUERY_TAG final wait=0",
                    &0u32,
                    diagnostics,
                )?;
                if final_tag != tag {
                    return Err(io::Error::other("battery tag changed during status read"));
                }
                Ok(Some(Member {
                    id: String::new(),
                    tag,
                    full_charged_capacity: info.FullChargedCapacity,
                    designed_capacity: info.DesignedCapacity,
                    default_alert1: info.DefaultAlert1,
                    default_alert2: info.DefaultAlert2,
                    critical_bias: info.CriticalBias,
                    cycle_count: info.CycleCount,
                    technology: info.Technology,
                    chemistry: info.Chemistry,
                    reading: Reading {
                        capabilities: info.Capabilities,
                        power_state: status.PowerState,
                        capacity: status.Capacity,
                        voltage: status.Voltage,
                        rate: status.Rate,
                    },
                }))
            })();
            match result {
                Err(ref e) if attempt == 0 && matches!(e.raw_os_error(), Some(2 | 433)) => {
                    diagnostics.push(
                        "native: tag/device changed; reacquiring tag and information once".into(),
                    );
                }
                _ => return result,
            }
        }
        unreachable!()
    }

    pub fn sample(
        diagnostics: &mut Vec<String>,
        failures: &mut Vec<BatteryDiagnostic>,
    ) -> io::Result<Vec<Member>> {
        let start = Instant::now();
        let result = enumerate(diagnostics, failures);
        diagnostics.push(format!(
            "native: SetupDi enumeration/read total {:.3}ms {}",
            start.elapsed().as_secs_f64() * 1000.0,
            result.as_ref().map_or_else(
                |e| format!("failed: {e}"),
                |r| format!("OK, {} system battery status(es)", r.len())
            )
        ));
        result
    }

    fn enumerate(
        diagnostics: &mut Vec<String>,
        failures: &mut Vec<BatteryDiagnostic>,
    ) -> io::Result<Vec<Member>> {
        let raw = unsafe {
            SetupDiGetClassDevsW(
                &BATTERY_INTERFACE,
                ptr::null(),
                ptr::null_mut(),
                DIGCF_PRESENT | DIGCF_DEVICEINTERFACE,
            )
        };
        if raw == INVALID_HANDLE_VALUE as HDEVINFO {
            return Err(io::Error::last_os_error());
        }
        let set = DeviceSet(raw);
        let mut readings = Vec::new();
        let mut complete = true;
        for index in 0.. {
            let mut interface: SP_DEVICE_INTERFACE_DATA = unsafe { std::mem::zeroed() };
            interface.cbSize = size_of::<SP_DEVICE_INTERFACE_DATA>() as u32;
            if unsafe {
                SetupDiEnumDeviceInterfaces(
                    set.0,
                    ptr::null(),
                    &BATTERY_INTERFACE,
                    index,
                    &mut interface,
                )
            } == 0
            {
                let error = io::Error::last_os_error();
                if error.raw_os_error() == Some(ERROR_NO_MORE_ITEMS as i32) {
                    break;
                }
                return Err(error);
            }
            let mut required = 0;
            unsafe {
                SetupDiGetDeviceInterfaceDetailW(
                    set.0,
                    &interface,
                    ptr::null_mut(),
                    0,
                    &mut required,
                    ptr::null_mut(),
                );
            }
            let error = io::Error::last_os_error();
            if error.raw_os_error() != Some(ERROR_INSUFFICIENT_BUFFER as i32)
                || required < size_of::<SP_DEVICE_INTERFACE_DETAIL_DATA_W>() as u32
            {
                return Err(error);
            }
            // usize storage provides the alignment required by the detail structure on x86/x64.
            let mut buffer = vec![0usize; (required as usize).div_ceil(size_of::<usize>())];
            let detail = buffer
                .as_mut_ptr()
                .cast::<SP_DEVICE_INTERFACE_DETAIL_DATA_W>();
            unsafe {
                (*detail).cbSize = size_of::<SP_DEVICE_INTERFACE_DETAIL_DATA_W>() as u32;
            }
            let mut devinfo: SP_DEVINFO_DATA = unsafe { std::mem::zeroed() };
            devinfo.cbSize = size_of::<SP_DEVINFO_DATA>() as u32;
            if unsafe {
                SetupDiGetDeviceInterfaceDetailW(
                    set.0,
                    &interface,
                    detail,
                    required,
                    ptr::null_mut(),
                    &mut devinfo,
                )
            } == 0
            {
                return Err(io::Error::last_os_error());
            }
            let mut identity = vec![0u16; 4096];
            if unsafe {
                SetupDiGetDeviceInstanceIdW(
                    set.0,
                    &devinfo,
                    identity.as_mut_ptr(),
                    identity.len() as u32,
                    ptr::null_mut(),
                )
            } == 0
            {
                return Err(io::Error::last_os_error());
            }
            let end = identity
                .iter()
                .position(|c| *c == 0)
                .unwrap_or(identity.len());
            let id = String::from_utf16_lossy(&identity[..end]).to_lowercase();
            let path = unsafe { ptr::addr_of!((*detail).DevicePath).cast::<u16>() };
            let raw = unsafe {
                CreateFileW(
                    path,
                    GENERIC_READ | GENERIC_WRITE,
                    FILE_SHARE_READ | FILE_SHARE_WRITE,
                    ptr::null(),
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL,
                    ptr::null_mut(),
                )
            };
            if raw == INVALID_HANDLE_VALUE {
                return Err(io::Error::other(format!(
                    "native: device {id} CreateFileW failed: {}",
                    io::Error::last_os_error()
                )));
            }
            let device = Device(raw);
            diagnostics.push(format!("native: interface #{index} opened synchronously"));
            match read(&device, diagnostics) {
                Ok(Some(mut reading)) => {
                    reading.id = id;
                    readings.push(reading);
                }
                Ok(None) => {}
                Err(e) => {
                    diagnostics.push(format!("native: device {id} unavailable: {e}"));
                    let mut outcome = diagnostic(&Member {
                        id,
                        tag: 0,
                        full_charged_capacity: u32::MAX,
                        designed_capacity: u32::MAX,
                        default_alert1: u32::MAX,
                        default_alert2: u32::MAX,
                        critical_bias: u32::MAX,
                        cycle_count: u32::MAX,
                        technology: 0,
                        chemistry: [0; 4],
                        reading: Reading {
                            capabilities: 0x80000000,
                            power_state: 6,
                            capacity: u32::MAX,
                            voltage: u32::MAX,
                            rate: i32::MIN,
                        },
                    });
                    outcome.reason = Some(format!("native_member_read_failed: {e}"));
                    failures.push(outcome);
                    complete = false;
                }
            }
        }
        if !complete {
            return Err(io::Error::other(
                "incomplete battery set; partial readings discarded; interfaces reopened next tick",
            ));
        }
        Ok(readings)
    }
}
#[cfg(windows)]
#[derive(Default)]
pub struct WindowsBackend {
    failures: Vec<BatteryDiagnostic>,
}
#[cfg(windows)]
impl Backend for WindowsBackend {
    fn snapshot(&mut self, diagnostics: &mut Vec<String>) -> Result<Vec<Member>, String> {
        self.failures.clear();
        device::sample(diagnostics, &mut self.failures).map_err(|e| e.to_string())
    }
    fn take_failures(&mut self) -> Vec<BatteryDiagnostic> {
        std::mem::take(&mut self.failures)
    }
    fn now_s(&self) -> f64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs_f64()
    }
}
