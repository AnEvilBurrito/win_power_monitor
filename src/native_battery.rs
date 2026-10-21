//! Immediate battery-class status requests; no WMI refresh side effects are required.
use crate::model::{Sample, State};

#[derive(Clone, Copy, Debug)]
pub struct Reading {
    pub capabilities: u32,
    pub power_state: u32,
    pub capacity: u32,
    pub voltage: u32,
    pub rate: i32,
}

/// Returns whether a WMI fallback is safe to attempt (not relative or multi-battery).
pub fn apply(s: &mut Sample, readings: &[Reading]) -> bool {
    let batteries: Vec<_> = readings
        .iter()
        .filter(|b| b.capabilities & 0x8000_0000 != 0 && b.capabilities & 0x2000_0000 == 0)
        .collect();
    if batteries.is_empty() {
        return true;
    }
    s.battery_present = Some(true);
    s.source = "native:IOCTL_BATTERY_QUERY_STATUS".into();
    if batteries.len() != 1 {
        s.reason = Some("multiple_native_batteries".into());
        s.diagnostics.push(format!(
            "native: {} system batteries; aggregation not implemented; WMI fallback suppressed",
            batteries.len()
        ));
        return false;
    }
    let b = batteries[0];
    s.ac_online = Some(b.power_state & 1 != 0);
    s.state = match (b.power_state & 4 != 0, b.power_state & 2 != 0) {
        (true, false) => State::Charging,
        (false, true) => State::Discharging,
        (false, false) => State::Idle,
        _ => State::Unknown,
    };
    if b.capabilities & 0x4000_0000 != 0 {
        s.reason = Some("native_relative_units".into());
        s.diagnostics.push("native: BATTERY_CAPACITY_RELATIVE; no W/Wh/V/A units inferred; WMI fallback suppressed".into());
        return false;
    }
    s.volts = (b.voltage != u32::MAX && b.voltage != 0).then(|| f64::from(b.voltage) / 1000.0);
    s.remaining_wh = (b.capacity != u32::MAX).then(|| f64::from(b.capacity) / 1000.0);
    let consistent = match s.state {
        State::Charging => b.rate >= 0,
        State::Discharging => b.rate <= 0,
        State::Idle => b.rate == 0,
        State::Unknown => false,
    };
    if b.rate == i32::MIN || !consistent {
        s.reason = Some("native_unknown_or_conflicting_rate".into());
        return true;
    }
    s.watts = Some(f64::from(b.rate) / 1000.0);
    s.amps = s.watts.zip(s.volts).map(|(w, v)| w / v);
    s.measurement_kind = "battery_flow".into();
    s.available = true;
    s.reason = None;
    false
}

#[cfg(windows)]
mod device {
    use super::Reading;
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

    fn read(device: &Device, diagnostics: &mut Vec<String>) -> io::Result<Option<Reading>> {
        for attempt in 0..2 {
            let result: io::Result<Option<Reading>> = (|| {
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
                Ok(Some(Reading {
                    capabilities: info.Capabilities,
                    power_state: status.PowerState,
                    capacity: status.Capacity,
                    voltage: status.Voltage,
                    rate: status.Rate,
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

    pub fn sample(diagnostics: &mut Vec<String>) -> io::Result<Vec<Reading>> {
        let start = Instant::now();
        let result = enumerate(diagnostics);
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

    fn enumerate(diagnostics: &mut Vec<String>) -> io::Result<Vec<Reading>> {
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
            if unsafe {
                SetupDiGetDeviceInterfaceDetailW(
                    set.0,
                    &interface,
                    detail,
                    required,
                    ptr::null_mut(),
                    ptr::null_mut(),
                )
            } == 0
            {
                return Err(io::Error::last_os_error());
            }
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
                diagnostics.push(format!(
                    "native: interface #{index} CreateFileW failed: {}",
                    io::Error::last_os_error()
                ));
                complete = false;
                continue;
            }
            let device = Device(raw);
            diagnostics.push(format!("native: interface #{index} opened synchronously"));
            match read(&device, diagnostics) {
                Ok(Some(reading)) => readings.push(reading),
                Ok(None) => {}
                Err(e) => {
                    diagnostics.push(format!("native: interface #{index} unavailable: {e}"));
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
pub use device::sample;
