//! Hardware Power Meter counters only. No CPU utilization or estimated wattage.
use crate::model::{Sample, State};

#[derive(Debug, Clone)]
pub struct MeterValue {
    pub instance: String,
    pub status: u32,
    pub milliwatts: f64,
}

pub fn select(values: &[MeterValue], selected: Option<&str>, cycles: u32) -> Result<f64, String> {
    if cycles < 2 {
        return Err("power_meter_priming".into());
    }
    let name = selected.ok_or("power_meter_scope_unconfirmed: use --system-meter only after verifying whole-system input scope")?;
    let matches: Vec<_> = values.iter().filter(|v| v.instance == name).collect();
    if matches.len() != 1 {
        return Err("power_meter_selected_instance_missing_or_ambiguous".into());
    }
    let v = matches[0];
    if v.status > 1 || !v.milliwatts.is_finite() || v.milliwatts < 0.0 {
        return Err("power_meter_invalid_reading".into());
    }
    Ok(v.milliwatts / 1000.0)
}

pub fn apply(s: &mut Sample, watts: f64) {
    s.watts = Some(watts);
    s.volts = None;
    s.amps = None;
    s.pct = None;
    s.remaining_wh = None;
    s.eta_s = None;
    s.eta_source = None;
    s.state = State::Unknown;
    s.source = "pdh:Power Meter:Power".into();
    s.measurement_kind = "system_input_power_user_confirmed".into();
    s.available = true;
    s.reason = None;
}

#[cfg(windows)]
pub struct PowerMeter {
    query: isize,
    counter: isize,
    cycles: u32,
    selected: Option<String>,
    error: Option<String>,
}
#[cfg(windows)]
impl PowerMeter {
    pub fn new(selected: Option<String>) -> Self {
        use windows_sys::Win32::System::Performance::*;
        let mut meter = Self {
            query: 0,
            counter: 0,
            cycles: 0,
            selected,
            error: None,
        };
        let path: Vec<u16> = "\\Power Meter(*)\\Power"
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let status = unsafe { PdhOpenQueryW(std::ptr::null(), 0, &mut meter.query) };
        if status != 0 {
            meter.error = Some(format!("PdhOpenQueryW: 0x{status:08x}"));
            return meter;
        }
        let status =
            unsafe { PdhAddEnglishCounterW(meter.query, path.as_ptr(), 0, &mut meter.counter) };
        if status != 0 {
            meter.error = Some(format!("PdhAddEnglishCounterW: 0x{status:08x}"));
            return meter;
        }
        let status = unsafe { PdhCollectQueryData(meter.query) };
        if status == 0 {
            meter.cycles = 1;
            // Even single-shot modes need a separate second collection interval.
            std::thread::sleep(std::time::Duration::from_secs(1));
        }
        meter
    }
    pub fn sample(&mut self, s: &mut Sample) {
        use windows_sys::Win32::System::Performance::*;
        let result = (|| -> Result<Vec<MeterValue>, String> {
            if let Some(error) = &self.error {
                return Err(error.clone());
            }
            let status = unsafe { PdhCollectQueryData(self.query) };
            if status != 0 {
                self.cycles = 0;
                return Err(format!("PdhCollectQueryData: 0x{status:08x}"));
            }
            self.cycles = self.cycles.saturating_add(1);
            let mut bytes = 0;
            let mut count = 0;
            // PDH_FMT_NOSCALE (0x1000) preserves the documented raw mW units.
            let flags = PDH_FMT_DOUBLE | 0x1000;
            let status = unsafe {
                PdhGetFormattedCounterArrayW(
                    self.counter,
                    flags,
                    &mut bytes,
                    &mut count,
                    std::ptr::null_mut(),
                )
            };
            if status != PDH_MORE_DATA {
                return Err(format!(
                    "PdhGetFormattedCounterArrayW(size): 0x{status:08x}"
                ));
            }
            if bytes == 0 || bytes > 16 * 1024 * 1024 {
                return Err("invalid counter array size".into());
            }
            // u64 allocation keeps PDH structures correctly aligned on x64.
            let mut buffer = vec![0u64; (bytes as usize).div_ceil(8)];
            let items = buffer.as_mut_ptr().cast::<PDH_FMT_COUNTERVALUE_ITEM_W>();
            let status = unsafe {
                PdhGetFormattedCounterArrayW(self.counter, flags, &mut bytes, &mut count, items)
            };
            if status != 0 {
                return Err(format!("PdhGetFormattedCounterArrayW: 0x{status:08x}"));
            }
            if count as usize
                > buffer.len() * 8 / std::mem::size_of::<PDH_FMT_COUNTERVALUE_ITEM_W>()
            {
                return Err("invalid counter array count".into());
            }
            let mut values = Vec::new();
            for item in unsafe { std::slice::from_raw_parts(items, count as usize) } {
                if item.szName.is_null() {
                    continue;
                }
                let mut len = 0;
                // Names point into the PDH-owned layout in our buffer.
                let start = buffer.as_ptr() as usize;
                let end = start + buffer.len() * 8;
                let name = item.szName as usize;
                if name < start || name >= end || !name.is_multiple_of(2) {
                    return Err("invalid instance name pointer".into());
                }
                let max = (end - name) / 2;
                while len < max && unsafe { *item.szName.add(len) } != 0 {
                    len += 1;
                }
                if len == max {
                    return Err("unterminated instance name".into());
                }
                values.push(MeterValue {
                    instance: String::from_utf16_lossy(unsafe {
                        std::slice::from_raw_parts(item.szName, len)
                    }),
                    status: item.FmtValue.CStatus,
                    milliwatts: unsafe { item.FmtValue.Anonymous.doubleValue },
                });
            }
            Ok(values)
        })();
        match result {
            Ok(values) => {
                for v in &values {
                    s.diagnostics.push(format!("pdh: instance {:?}; scope unknown (not inferred from name); status 0x{:08x}", v.instance, v.status));
                }
                match select(&values, self.selected.as_deref(), self.cycles) {
                    Ok(watts) => {
                        apply(s, watts);
                        s.diagnostics.push(format!("pdh: selected {:?}; whole-system input scope asserted by user; mW / 1000; two collections completed", self.selected));
                    }
                    Err(error) => {
                        s.diagnostics.push(format!("pdh: {error}"));
                        s.reason = Some(error);
                    }
                }
            }
            Err(error) => {
                s.diagnostics.push(format!("pdh: unavailable: {error}"));
                s.reason = Some("no_supported_system_power_meter".into());
            }
        }
    }
}
#[cfg(windows)]
impl Drop for PowerMeter {
    fn drop(&mut self) {
        if self.query != 0 {
            unsafe {
                windows_sys::Win32::System::Performance::PdhCloseQuery(self.query);
            }
        }
    }
}
