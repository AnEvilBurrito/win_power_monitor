//! Experimental, private battery refresh request. A successful return is not
//! proof that the driver accepted the request or that sensors refreshed.
use std::ffi::{c_char, c_int, c_void};
use std::time::{Duration, Instant};

unsafe extern "C" {
    fn dlopen(path: *const c_char, mode: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlclose(handle: *mut c_void) -> c_int;
    fn notify_register_check(name: *const c_char, token: *mut c_int) -> u32;
    fn notify_check(token: c_int, check: *mut c_int) -> u32;
    fn notify_cancel(token: c_int) -> u32;
}

#[derive(serde::Serialize)]
pub struct RefreshResult {
    pub mode: &'static str,
    pub request_return: Option<i32>,
    pub notification: bool,
    pub notification_error: Option<u32>,
    pub timed_out: bool,
    pub elapsed_ms: u128,
    pub error: Option<&'static str>,
}

pub fn collect(refresh: bool) -> RefreshResult {
    let start = Instant::now();
    let mut result = RefreshResult {
        mode: if refresh { "refresh" } else { "baseline" },
        request_return: None,
        notification: false,
        notification_error: None,
        timed_out: false,
        elapsed_ms: 0,
        error: None,
    };
    if !refresh {
        return result;
    }
    // SAFETY: All pointers are static NUL-terminated strings. The loaded
    // function is called using its published C signature and before dlclose.
    unsafe {
        let handle = dlopen(
            c"/System/Library/Frameworks/IOKit.framework/IOKit".as_ptr(),
            2,
        );
        if handle.is_null() {
            result.error = Some("iokit_unavailable");
            return result;
        }
        let symbol = dlsym(handle, c"IOPSRequestBatteryUpdate".as_ptr());
        if symbol.is_null() {
            result.error = Some("symbol_unavailable");
            dlclose(handle);
            return result;
        }
        let mut token = 0;
        let status = notify_register_check(c"com.apple.system.powersources".as_ptr(), &mut token);
        if status != 0 {
            result.notification_error = Some(status);
        } else {
            let mut initial = 0;
            let status = notify_check(token, &mut initial);
            if status != 0 {
                result.notification_error = Some(status);
            }
        }
        let request: unsafe extern "C" fn(c_int) -> i32 = std::mem::transmute(symbol);
        let code = request(2);
        result.request_return = Some(code);
        if code == 0 && status == 0 && result.notification_error.is_none() {
            while start.elapsed() < Duration::from_millis(800) {
                let mut changed = 0;
                let check = notify_check(token, &mut changed);
                if check != 0 {
                    result.notification_error = Some(check);
                    break;
                }
                if changed != 0 {
                    result.notification = true;
                    break;
                }
                std::thread::sleep(Duration::from_millis(25));
            }
            result.timed_out = !result.notification && result.notification_error.is_none();
        }
        if status == 0 {
            notify_cancel(token);
        }
        dlclose(handle);
    }
    result.elapsed_ms = start.elapsed().as_millis();
    result
}
