//! Experimental Apple Silicon battery-specific SMC reads.
//! Protocol reference: ChargeKit, MIT licensed; see docs/chargekit-license.txt.

/// Decode the battery gauge's little-endian millivolts and signed milliamps.
pub fn decode_battery(voltage: &[u8], current: &[u8]) -> Result<(u32, i32), &'static str> {
    let voltage: [u8; 2] = voltage.try_into().map_err(|_| "smc_voltage_size")?;
    let current: [u8; 2] = current.try_into().map_err(|_| "smc_current_size")?;
    let mv = u32::from(u16::from_le_bytes(voltage));
    let ma = i32::from(i16::from_le_bytes(current));
    if !(3000..=30000).contains(&mv) {
        return Err("smc_voltage_out_of_range");
    }
    Ok((mv, ma))
}

/// Use SMC only when the existing ASB state agrees with the current direction.
pub fn apply_battery(
    sample: &mut crate::model::Sample,
    mv: u32,
    ma: i32,
) -> Result<(), &'static str> {
    use crate::model::State;
    if !sample.available || sample.transition || sample.battery_count != 1 {
        return Err("smc_asb_state_unavailable");
    }
    if !(3000..=30000).contains(&mv) || !(-32768..=32767).contains(&ma) {
        return Err("smc_reading_out_of_range");
    }
    let consistent = matches!(
        (sample.state, ma),
        (State::Charging, 1..) | (State::Discharging, ..=-1) | (State::Idle, 0)
    );
    if !consistent {
        return Err("smc_direction_conflict");
    }
    let volts = f64::from(mv) / 1000.0;
    let amps = f64::from(ma) / 1000.0;
    let watts = volts * amps;
    sample.diagnostics.push(format!(
        "macos_asb_reference: watts={:?} volts={:?} amps={:?}",
        sample.watts, sample.volts, sample.amps
    ));
    sample.watts = Some(watts);
    sample.volts = Some(volts);
    sample.amps = Some(amps);
    if let Some(battery) = sample.batteries.first_mut() {
        battery.watts = Some(watts);
        battery.volts = Some(volts);
        battery.amps = Some(amps);
    }
    sample.source = "native:macos_smc_battery".into();
    Ok(())
}

#[cfg(target_os = "macos")]
mod native {
    use std::ffi::{c_char, c_void};

    #[link(name = "IOKit", kind = "framework")]
    unsafe extern "C" {
        fn IOServiceMatching(name: *const c_char) -> *mut c_void;
        fn IOServiceGetMatchingService(port: u32, matching: *mut c_void) -> u32;
        fn IOServiceOpen(service: u32, task: u32, kind: u32, connection: *mut u32) -> i32;
        fn IOObjectRelease(object: u32) -> i32;
        fn IOServiceClose(connection: u32) -> i32;
        fn IOConnectCallStructMethod(
            connection: u32,
            selector: u32,
            input: *const c_void,
            input_size: usize,
            output: *mut c_void,
            output_size: *mut usize,
        ) -> i32;
        static mach_task_self_: u32;
    }

    struct Connection(u32);
    impl Drop for Connection {
        fn drop(&mut self) {
            // SAFETY: Connection is an open IOKit connection owned by this object.
            unsafe {
                IOServiceClose(self.0);
            }
        }
    }
    impl Connection {
        fn open() -> Result<Self, String> {
            // SAFETY: IOKit consumes the matching dictionary; service and connection
            // handles are released on every successful acquisition path.
            unsafe {
                let matching = IOServiceMatching(c"AppleSMC".as_ptr());
                if matching.is_null() {
                    return Err("smc_matching_failed".into());
                }
                let service = IOServiceGetMatchingService(0, matching);
                if service == 0 {
                    return Err("smc_service_unavailable".into());
                }
                let mut connection = 0;
                let status = IOServiceOpen(service, mach_task_self_, 0, &mut connection);
                IOObjectRelease(service);
                if status != 0 || connection == 0 {
                    return Err(format!("smc_open:{status:#x}"));
                }
                Ok(Self(connection))
            }
        }
        fn call(&self, input: &[u8; 80]) -> Result<[u8; 80], String> {
            let mut output = [0u8; 80];
            let mut size = output.len();
            // SAFETY: input/output are valid buffers with the declared lengths.
            let status = unsafe {
                IOConnectCallStructMethod(
                    self.0,
                    2,
                    input.as_ptr().cast(),
                    input.len(),
                    output.as_mut_ptr().cast(),
                    &mut size,
                )
            };
            if status != 0 {
                return Err(format!("smc_call:{status:#x}"));
            }
            if size != output.len() {
                return Err(format!("smc_output_size:{size}"));
            }
            if output[40] != 0 {
                return Err(format!("smc_result:{}", output[40]));
            }
            Ok(output)
        }
        fn read_key(&self, name: &[u8; 4], expected_type: &[u8; 4]) -> Result<[u8; 2], String> {
            let key = u32::from_be_bytes(*name);
            let mut input = [0u8; 80];
            input[0..4].copy_from_slice(&key.to_le_bytes());
            input[42] = 9;
            let info = self.call(&input)?;
            let size = u32::from_le_bytes(info[28..32].try_into().unwrap());
            let kind = u32::from_le_bytes(info[32..36].try_into().unwrap());
            if size != 2 || kind != u32::from_be_bytes(*expected_type) {
                return Err(format!(
                    "smc_unexpected_key_type:{}:{kind:#x}:{size}",
                    String::from_utf8_lossy(name)
                ));
            }
            input[28..32].copy_from_slice(&size.to_le_bytes());
            input[32..36].copy_from_slice(&kind.to_le_bytes());
            input[42] = 5;
            let data = self.call(&input)?;
            Ok([data[48], data[49]])
        }
    }
    pub fn read_battery() -> Result<(u32, i32), String> {
        let connection = Connection::open()?;
        let voltage = connection.read_key(b"B0AV", b"ui16")?;
        let current = connection.read_key(b"B0AC", b"si16")?;
        super::decode_battery(&voltage, &current).map_err(str::to_owned)
    }
}

#[cfg(target_os = "macos")]
pub use native::read_battery;
