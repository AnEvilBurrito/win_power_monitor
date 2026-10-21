use crate::model::{Sample, number};
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicIsize, Ordering},
    },
    thread::JoinHandle,
    time::{Duration, Instant},
};
#[derive(Clone)]
pub struct Reading {
    pub sequence: u64,
    pub received: Instant,
    pub sample: Sample,
}
pub type Latest = Arc<Mutex<Option<Reading>>>;
pub fn icon_text(reading: Option<&Reading>, now: Instant, rate: Duration) -> String {
    match reading
        .filter(|r| now.saturating_duration_since(r.received) <= rate * 2)
        .filter(|r| !r.sample.transition)
        .and_then(|r| r.sample.watts)
    {
        Some(w) if w.is_finite() && w.abs().round() <= 999.0 => format!("{:.0}", w.abs()),
        Some(w) if w.is_finite() => "HI".into(),
        Some(_) => "—".into(),
        None => "—".into(),
    }
}
pub fn tooltip(reading: Option<&Reading>, now: Instant, rate: Duration) -> String {
    match reading {
        Some(r) => format!(
            "wpm {} W | {:?} | {} | {}{}",
            number(
                if now.saturating_duration_since(r.received) > rate * 2 || r.sample.transition {
                    None
                } else {
                    r.sample.watts
                }
            ),
            r.sample.state,
            r.sample.source,
            r.sample.measurement_kind,
            if now.saturating_duration_since(r.received) > rate * 2 {
                " | STALE"
            } else if r.sample.transition {
                " | TRANSITION"
            } else {
                ""
            }
        ),
        None => "wpm — | waiting for measured power".into(),
    }
}
pub struct Tray {
    hwnd: Arc<AtomicIsize>,
    thread: Option<JoinHandle<()>>,
    pub status: Arc<Mutex<String>>,
}
impl Tray {
    pub fn start(latest: Latest, stop: Arc<AtomicBool>, rate: Duration) -> Self {
        let hwnd = Arc::new(AtomicIsize::new(0));
        let status = Arc::new(Mutex::new("starting".into()));
        let h = hwnd.clone();
        let s = status.clone();
        let thread = std::thread::spawn(move || platform::run(latest, stop, rate, h, s));
        Self {
            hwnd,
            thread: Some(thread),
            status,
        }
    }
    pub fn notify(&self) {
        platform::post(self.hwnd.load(Ordering::Acquire), false);
    }
    pub fn notifier(&self) -> Arc<AtomicIsize> {
        self.hwnd.clone()
    }
}
impl Drop for Tray {
    fn drop(&mut self) {
        platform::post(self.hwnd.load(Ordering::Acquire), true);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
pub fn notify(hwnd: &AtomicIsize) {
    platform::post(hwnd.load(Ordering::Acquire), false);
}

#[cfg(windows)]
mod platform {
    use super::*;
    use windows_sys::Win32::{
        Foundation::*,
        Graphics::Gdi::*,
        System::{Console::GetConsoleWindow, LibraryLoader::GetModuleHandleW},
        UI::{HiDpi::*, Shell::*, WindowsAndMessaging::*},
    };
    const UPDATE: u32 = WM_APP + 2;
    static UPDATE_PENDING: AtomicBool = AtomicBool::new(false);
    const CALLBACK: u32 = WM_APP + 1;
    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(Some(0)).collect()
    }
    struct Context {
        latest: Latest,
        stop: Arc<AtomicBool>,
        rate: Duration,
        status: Arc<Mutex<String>>,
        icon: HICON,
        restart: u32,
        key: String,
        added: bool,
    }
    impl Context {
        fn data(&self, hwnd: HWND) -> NOTIFYICONDATAW {
            let mut data: NOTIFYICONDATAW = unsafe { std::mem::zeroed() };
            data.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
            data.hWnd = hwnd;
            data.uID = 1;
            data.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE;
            data.hIcon = self.icon;
            data.uCallbackMessage = CALLBACK;
            data
        }
        fn refresh(&mut self, hwnd: HWND, force: bool) {
            let reading = self.latest.lock().ok().and_then(|r| r.clone());
            let now = Instant::now();
            let text = icon_text(reading.as_ref(), now, self.rate);
            let tip = tooltip(reading.as_ref(), now, self.rate);
            let key = format!("{text}{tip}");
            if !force && self.added && self.key == key {
                return;
            }
            let icon = unsafe { numeric_icon(hwnd, &text) };
            if icon.is_null() {
                if let Ok(mut s) = self.status.lock() {
                    *s = "error: icon creation failed".into();
                }
                return;
            }
            let old = self.icon;
            self.icon = icon;
            let mut data = self.data(hwnd);
            let tip = wide(&tip);
            let len = tip.len().saturating_sub(1).min(data.szTip.len() - 1);
            data.szTip[..len].copy_from_slice(&tip[..len]);
            let ok = unsafe {
                Shell_NotifyIconW(
                    if self.added && !force {
                        NIM_MODIFY
                    } else {
                        NIM_ADD
                    },
                    &data,
                )
            } != 0;
            self.added = ok;
            self.key = key;
            if !old.is_null() {
                unsafe {
                    DestroyIcon(old);
                }
            }
            if let Ok(mut s) = self.status.lock() {
                *s = if ok {
                    "active".into()
                } else {
                    format!(
                        "error: Shell_NotifyIconW {} (retrying)",
                        std::io::Error::last_os_error()
                    )
                };
            }
        }
    }
    // Opaque high-contrast DIB with a monochrome mask, all GDI ownership stays on this thread.
    unsafe fn numeric_icon(hwnd: HWND, text: &str) -> HICON {
        unsafe {
            let dpi = GetDpiForWindow(hwnd).max(GetDpiForSystem()).max(96);
            let size = GetSystemMetricsForDpi(SM_CXSMICON, dpi).max(16);
            let dc = CreateCompatibleDC(std::ptr::null_mut());
            if dc.is_null() {
                return std::ptr::null_mut();
            }
            let mut info: BITMAPINFO = std::mem::zeroed();
            info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
            info.bmiHeader.biWidth = size;
            info.bmiHeader.biHeight = -size;
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = BI_RGB;
            let mut bits = std::ptr::null_mut();
            let bitmap = CreateDIBSection(
                dc,
                &info,
                DIB_RGB_COLORS,
                &mut bits,
                std::ptr::null_mut(),
                0,
            );
            if bitmap.is_null() {
                DeleteDC(dc);
                return std::ptr::null_mut();
            }
            std::ptr::write_bytes(bits, 0, (size * size * 4) as usize);
            let old = SelectObject(dc, bitmap);
            let font = CreateFontW(
                -(size * 9 / 10),
                if text.chars().count() == 3 {
                    size / 3
                } else {
                    0
                },
                0,
                0,
                FW_BOLD as i32,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                0,
                0,
                NONANTIALIASED_QUALITY as u32,
                0,
                wide("Consolas").as_ptr(),
            );
            let old_font = SelectObject(dc, font);
            SetTextColor(dc, 0x00ff_ffff);
            SetBkColor(dc, 0);
            let mut rect = RECT {
                left: 0,
                top: 0,
                right: size,
                bottom: size,
            };
            let text = wide(text);
            DrawTextW(
                dc,
                text.as_ptr(),
                (text.len() - 1) as i32,
                &mut rect,
                DT_CENTER | DT_VCENTER | DT_SINGLELINE,
            );
            for pixel in std::slice::from_raw_parts_mut(bits as *mut u32, (size * size) as usize) {
                *pixel |= 0xff00_0000;
            }
            SelectObject(dc, old_font);
            if !font.is_null() {
                DeleteObject(font);
            }
            SelectObject(dc, old);
            let mask_bits = vec![0u8; (((size + 15) / 16) * 2 * size) as usize];
            let mask = CreateBitmap(size, size, 1, 1, mask_bits.as_ptr().cast());
            let info = ICONINFO {
                fIcon: 1,
                xHotspot: 0,
                yHotspot: 0,
                hbmMask: mask,
                hbmColor: bitmap,
            };
            let icon = CreateIconIndirect(&info);
            if !mask.is_null() {
                DeleteObject(mask);
            }
            DeleteObject(bitmap);
            DeleteDC(dc);
            icon
        }
    }
    unsafe extern "system" fn procedure(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
        unsafe {
            let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Context;
            if ptr.is_null() {
                return DefWindowProcW(hwnd, msg, w, l);
            }
            let ctx = &mut *ptr;
            if msg == ctx.restart {
                ctx.added = false;
                ctx.refresh(hwnd, true);
                return 0;
            }
            match msg {
                UPDATE | WM_TIMER => {
                    if msg == UPDATE {
                        UPDATE_PENDING.store(false, Ordering::Release);
                    }
                    if ctx.stop.load(Ordering::Acquire) {
                        DestroyWindow(hwnd);
                        return 0;
                    }
                    ctx.refresh(hwnd, false);
                    0
                }
                CALLBACK if l as u32 == WM_RBUTTONUP || l as u32 == WM_CONTEXTMENU => {
                    let menu = CreatePopupMenu();
                    if !menu.is_null() {
                        AppendMenuW(menu, MF_STRING, 1, wide("Show dashboard").as_ptr());
                        AppendMenuW(menu, MF_STRING, 2, wide("Exit").as_ptr());
                        let mut point = POINT { x: 0, y: 0 };
                        GetCursorPos(&mut point);
                        SetForegroundWindow(hwnd);
                        let choice = TrackPopupMenu(
                            menu,
                            TPM_RETURNCMD | TPM_RIGHTBUTTON,
                            point.x,
                            point.y,
                            0,
                            hwnd,
                            std::ptr::null(),
                        );
                        DestroyMenu(menu);
                        if choice == 1 {
                            let console = GetConsoleWindow();
                            if !console.is_null() {
                                ShowWindow(console, SW_RESTORE);
                                SetForegroundWindow(console);
                            }
                        }
                        if choice == 2 {
                            ctx.stop.store(true, Ordering::Release);
                        }
                        PostMessageW(hwnd, WM_NULL, 0, 0);
                    }
                    0
                }
                WM_CLOSE => {
                    DestroyWindow(hwnd);
                    0
                }
                WM_DESTROY => {
                    KillTimer(hwnd, 2);
                    Shell_NotifyIconW(NIM_DELETE, &ctx.data(hwnd));
                    if !ctx.icon.is_null() {
                        DestroyIcon(ctx.icon);
                        ctx.icon = std::ptr::null_mut();
                    }
                    PostQuitMessage(0);
                    0
                }
                _ => DefWindowProcW(hwnd, msg, w, l),
            }
        }
    }
    pub fn post(hwnd: isize, close: bool) {
        if hwnd != 0 && (close || !UPDATE_PENDING.swap(true, Ordering::AcqRel)) {
            let posted =
                unsafe { PostMessageW(hwnd as HWND, if close { WM_CLOSE } else { UPDATE }, 0, 0) };
            if posted == 0 && !close {
                UPDATE_PENDING.store(false, Ordering::Release);
            }
        }
    }
    pub fn run(
        latest: Latest,
        stop: Arc<AtomicBool>,
        rate: Duration,
        published: Arc<AtomicIsize>,
        status: Arc<Mutex<String>>,
    ) {
        unsafe {
            SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
            let name = wide("wpm.TrayWindow");
            let instance = GetModuleHandleW(std::ptr::null());
            let class = WNDCLASSW {
                lpfnWndProc: Some(procedure),
                hInstance: instance,
                lpszClassName: name.as_ptr(),
                ..std::mem::zeroed()
            };
            if RegisterClassW(&class) == 0 {
                if let Ok(mut s) = status.lock() {
                    *s = "error: RegisterClassW".into();
                }
                return;
            }
            let hwnd = CreateWindowExW(
                0,
                name.as_ptr(),
                name.as_ptr(),
                0,
                0,
                0,
                0,
                0,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                instance,
                std::ptr::null(),
            );
            if hwnd.is_null() {
                if let Ok(mut s) = status.lock() {
                    *s = "error: CreateWindowExW".into();
                }
                UnregisterClassW(name.as_ptr(), instance);
                return;
            }
            let mut ctx = Box::new(Context {
                latest,
                stop,
                rate,
                status,
                icon: std::ptr::null_mut(),
                restart: RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()),
                key: String::new(),
                added: false,
            });
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, (&mut *ctx as *mut Context) as isize);
            published.store(hwnd as isize, Ordering::Release);
            if ctx.stop.load(Ordering::Acquire) {
                DestroyWindow(hwnd);
            } else {
                ctx.refresh(hwnd, true);
            }

            if SetTimer(hwnd, 2, (rate.as_millis() as u32).clamp(100, 1000), None) == 0 {
                if let Ok(mut s) = ctx.status.lock() {
                    *s = "error: stale timer unavailable; tray disabled".into();
                }
                DestroyWindow(hwnd);
            }
            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            published.store(0, Ordering::Release);
            UnregisterClassW(name.as_ptr(), instance);
        }
    }
}
#[cfg(not(windows))]
mod platform {
    use super::*;
    pub fn post(_: isize, _: bool) {}
    pub fn run(
        _: Latest,
        _: Arc<AtomicBool>,
        _: Duration,
        _: Arc<AtomicIsize>,
        status: Arc<Mutex<String>>,
    ) {
        if let Ok(mut s) = status.lock() {
            *s = "Windows required".into();
        }
    }
}
