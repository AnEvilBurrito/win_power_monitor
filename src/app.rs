use crate::{
    cli::{Mode, Theme},
    config::Settings,
    export::Log,
    model::{History, Metrics, Sample, number, stamp_completion},
    provider::Provider,
    tray::{Latest, Reading, Tray},
    ui,
};
use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use std::{
    io::{self, Write},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};

/// Accept plain/shifted theme shortcuts, never release events or Ctrl/Alt chords.
fn theme_for_key(theme: Theme, key: KeyEvent) -> Option<Theme> {
    if key.kind == KeyEventKind::Release || !(key.modifiers & !KeyModifiers::SHIFT).is_empty() {
        return None;
    }
    match key.code {
        KeyCode::Char('t') => Some(theme.cycle(key.modifiers.contains(KeyModifiers::SHIFT))),
        KeyCode::Char('T') => Some(theme.cycle(true)),
        _ => None,
    }
}

/// Redraw on changes, with a one-second idle tick for clocks and notice expiry.
#[derive(Default)]
struct DashboardRefresh {
    last_draw: Option<Instant>,
    dirty: bool,
    prepared: Option<ui::PreparedHistory>,
}
impl DashboardRefresh {
    fn due(&self, now: Instant) -> bool {
        self.dirty
            || self
                .last_draw
                .is_none_or(|last| now.saturating_duration_since(last) >= Duration::from_secs(1))
    }
    fn request(&mut self) {
        self.dirty = true;
    }
    fn invalidate(&mut self) {
        self.prepared = None;
        self.request();
    }
    fn sample_received(&mut self, paused: bool) {
        if !paused {
            self.invalidate();
        }
    }
    fn drawn(&mut self, now: Instant) {
        self.last_draw = Some(now);
        self.dirty = false;
    }
}

struct Sampler {
    stop: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl Drop for Sampler {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(t) = self.thread.take() {
            t.thread().unpark();
            let _ = t.join();
        }
    }
}
struct TerminalGuard;
impl Drop for TerminalGuard {
    fn drop(&mut self) {
        ratatui::restore();
    }
}
/// Minimum spacing between exports: each one creates two files, so a held key
/// would otherwise fill the launch directory.
const EXPORT_COOLDOWN: Duration = Duration::from_secs(2);
/// Last action or state message, plus the instant transient feedback should clear.
/// Export and log toggles write files silently, so their paths must be reported.
#[derive(Default)]
struct Notice {
    text: String,
    until: Option<Instant>,
}
impl Notice {
    const TTL: Duration = Duration::from_secs(10);
    /// Feedback for a completed action; reverts to the plain separator when stale.
    fn temporary(&mut self, text: String, now: Instant) {
        self.text = text;
        self.until = Some(now + Self::TTL);
    }
    /// Failures and state changes stay visible until the next message.
    fn sticky(&mut self, text: String) {
        self.text = text;
        self.until = None;
    }
    fn tick(&mut self, now: Instant) {
        if self.until.is_some_and(|until| now >= until) {
            self.clear();
        }
    }
    /// Sticky failures have no expiry, so Esc must be able to dismiss them.
    fn clear(&mut self) {
        self.text.clear();
        self.until = None;
    }
    /// Help carries durable state, so it shows failures and skips action feedback
    /// that the status row is already presenting.
    fn persistent(&self) -> &str {
        if self.until.is_none() { &self.text } else { "" }
    }
    fn text(&self) -> &str {
        &self.text
    }
}
/// Absolute form of a user-facing path so dashboard feedback names a real location.
fn locate(path: &std::path::Path) -> String {
    std::path::absolute(path)
        .unwrap_or_else(|_| path.to_path_buf())
        .display()
        .to_string()
}
/// One export is two files, so a held key would litter the directory: gate repeats
/// and report how long the user must wait. Err carries the remaining cooldown.
fn export_gate(last: Option<Instant>, now: Instant, cooldown: Duration) -> Result<(), Duration> {
    last.and_then(|last| {
        let elapsed = now.saturating_duration_since(last);
        (elapsed < cooldown).then(|| cooldown - elapsed)
    })
    .map_or(Ok(()), Err)
}
/// Dashboard-mode summary printed after the alternate screen is restored, so the
/// file locations survive in the shell scrollback once the TUI is gone.
fn exit_summary(log_path: Option<&std::path::Path>, export_dir: Option<&str>) -> Option<String> {
    let mut parts: Vec<String> = Vec::new();
    if let Some(p) = log_path {
        parts.push(format!("log {}", locate(p)));
    }
    if let Some(d) = export_dir {
        parts.push(format!("exports in {d}"));
    }
    (!parts.is_empty()).then(|| parts.join(" · "))
}
fn write_log(
    log: &mut Option<Log>,
    s: &Sample,
    log_path: Option<&std::path::Path>,
    notice: &mut Notice,
) {
    if let Some(writer) = log
        && let Err(e) = writer.write(s)
    {
        let at = log_path.map_or_else(String::new, |p| format!(" at {}", locate(p)));
        notice.sticky(format!("log error: {e}{at}; disabled"));
        eprintln!("wpm: {}", notice.text());
        *log = None;
    }
}
pub fn run(settings: Settings) -> Result<u8, String> {
    let start = Instant::now();
    // The log target is tracked even while logging is off so the UI can name the
    // file that `l` will create instead of leaving the user to guess the directory.
    let mut log_path = settings.log.clone();
    // Export and log errors have no path of their own: io::Error prints only the
    // reason, so report the directory the files land in alongside them.
    let cwd = locate(std::path::Path::new("."));
    let mut export_dir: Option<String> = None;
    let mut log = match &log_path {
        Some(p) => match Log::open(p) {
            Ok(log) => Some(log),
            Err(e) => {
                eprintln!("wpm: log: {e}");
                return Ok(4);
            }
        },
        None => None,
    };
    let stop = Arc::new(AtomicBool::new(false));
    let handler_stop = stop.clone();
    ctrlc::set_handler(move || handler_stop.store(true, Ordering::Release))
        .map_err(|e| e.to_string())?;
    let latest: Latest = Arc::new(Mutex::new(None));
    let tray = settings
        .tray
        .then(|| Tray::start(latest.clone(), stop.clone(), settings.rate));
    let tray_hwnd = tray.as_ref().map(Tray::notifier);
    let (sender, receiver) = mpsc::sync_channel::<Sample>(64);
    let thread_stop = stop.clone();
    let rate = settings.rate;
    let wall_start = chrono::Utc::now();
    let single = matches!(settings.mode, Mode::Json | Mode::Once | Mode::SelfTest);
    let system_meter = settings.system_meter.clone();
    let thread = std::thread::spawn(move || {
        // COM and the WMI connection must be created and dropped on this thread.
        let mut provider = Provider::with_system_meter(system_meter);
        let mut sequence = 0;
        let mut metrics = Metrics::new(rate);
        let mut dropped = 0;
        let mut deadline = Instant::now();
        while !thread_stop.load(Ordering::Acquire) {
            let query_start = Instant::now();
            let mut s = provider.sample(0.0);
            let completed = Instant::now();
            stamp_completion(
                &mut s,
                completed.duration_since(start).as_secs_f64(),
                chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
                completed.duration_since(query_start).as_secs_f64() * 1000.0,
            );
            metrics.apply(&mut s);
            sequence += 1;
            if dropped > 0 {
                s.diagnostics
                    .push(format!("{dropped} samples dropped: UI/output backlog"));
            }
            if let Ok(mut value) = latest.lock() {
                *value = Some(Reading {
                    sequence,
                    received: Instant::now(),
                    sample: s.clone(),
                });
            }
            if let Some(hwnd) = &tray_hwnd {
                crate::tray::notify(hwnd);
            }
            match sender.try_send(s) {
                Ok(()) => dropped = 0,
                Err(mpsc::TrySendError::Full(_)) => dropped += 1,
                Err(_) => break,
            }
            if single {
                break;
            }
            deadline = next_deadline(deadline, Instant::now(), rate);
            while !thread_stop.load(Ordering::Acquire) && Instant::now() < deadline {
                std::thread::park_timeout(
                    deadline
                        .saturating_duration_since(Instant::now())
                        .min(Duration::from_millis(100)),
                );
            }
        }
    });
    let sampler = Sampler {
        stop: stop.clone(),
        thread: Some(thread),
    };
    let mut notice = Notice::default();
    let mut last_export: Option<Instant> = None;
    if settings.mode != Mode::Dashboard {
        let mut stdout = io::BufWriter::new(io::stdout().lock());
        let mut previous: Option<f64> = None;
        let mut code = 0;
        while !stop.load(Ordering::Acquire) {
            let mut s = match receiver.recv_timeout(Duration::from_millis(100)) {
                Ok(s) => s,
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(_) => break,
            };
            s.gap |= previous.is_some_and(|p| s.elapsed_s - p > rate.as_secs_f64() * 2.0);
            previous = Some(s.elapsed_s);
            write_log(&mut log, &s, log_path.as_deref(), &mut notice);
            if settings.require_battery && s.battery_present != Some(true) {
                code = 3;
            }
            let result = match settings.mode {
                Mode::Once => writeln!(stdout, "{:?}: {} W measured power; {} V; {}%; {}{}", s.state, number(s.watts.map(|w| if settings.absolute { w.abs() } else { w })), number(s.volts), number(s.pct), s.source, s.reason.as_ref().map_or(String::new(), |r| format!(" ({r})"))),
                Mode::SelfTest => {
                    #[cfg(target_os = "macos")]
                    let limitations = ["Experimental IORegistry battery flow; real MacBook validation pending", "Remaining/full/design energy capacity unavailable; percentage uses charge-capacity ratio", "No menu-bar integration or system power measurement", "battery flow is not system/wall consumption"];
                    #[cfg(not(target_os = "macos"))]
                    let limitations = ["Desktop requires supported PDH meter and user-confirmed system input scope; no aggregation or estimates", "Experimental multi-battery aggregation; user-confirmed working on a Surface Book with v0.1.1. v0.1.2 capacity display has synthetic coverage, not new hardware validation.", "WMI percentage fallback not implemented", "battery flow is not system/wall consumption"];
                    let report = serde_json::json!({"version": env!("CARGO_PKG_VERSION"), "os": std::env::consts::OS, "arch": std::env::consts::ARCH,
                        "sample": s, "limitations": limitations});
                    serde_json::to_writer_pretty(&mut stdout, &report).map_err(io::Error::other).and_then(|_| writeln!(stdout))
                },
                _ => serde_json::to_writer(&mut stdout, &s).map_err(io::Error::other).and_then(|_| writeln!(stdout)),
            }.and_then(|_| stdout.flush());
            if let Err(e) = result {
                if e.kind() == io::ErrorKind::BrokenPipe {
                    break;
                }
                return Err(e.to_string());
            }
            if single || code != 0 {
                break;
            }
        }
        if let Some(log) = &mut log {
            log.flush().map_err(|e| e.to_string())?;
        }
        drop(sampler);
        drop(tray);
        return Ok(code);
    }
    let old_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        ratatui::restore();
        old_hook(info);
    }));
    let mut terminal = ratatui::try_init().map_err(|e| e.to_string())?;
    let guard = TerminalGuard;
    let mut history = History::new(
        settings.x_length,
        settings.sample_count,
        rate,
        settings.all_states,
    );
    let mut ui_state = ui::UiState::new(wall_start, 0.0, settings.x_length.as_secs_f64());
    let mut current = None;
    let mut paused = false;
    let mut help = false;
    let mut absolute = settings.absolute;
    let mut step = settings.step;
    let mut theme = settings.theme;
    let mut last_received = Instant::now();
    let mut frozen = 0.0;
    let mut frozen_view: Option<(History, Option<Sample>)> = None;
    let mut refresh = DashboardRefresh::default();
    let mut code = 0;
    while !stop.load(Ordering::Acquire) {
        for s in receiver.try_iter() {
            last_received = Instant::now();
            if settings.require_battery && s.battery_present != Some(true) {
                code = 3;
                stop.store(true, Ordering::Release);
            }
            write_log(&mut log, &s, log_path.as_deref(), &mut notice);
            current = Some(history.push(s));
            refresh.sample_received(paused);
            if let Some(sample) = &current {
                ui_state.analytics.observe(sample);
            }
        }
        if refresh.due(Instant::now()) {
            let now = Instant::now();
            notice.tick(now);
            let tray_status = tray
                .as_ref()
                .and_then(|t| t.status.lock().ok().map(|s| s.clone()))
                .unwrap_or_else(|| "off".into());
            let log_part = match (log.is_some(), &log_path) {
                (true, Some(p)) => format!("log on {}", locate(p)),
                (true, None) => "log on".to_string(),
                (false, Some(p)) => format!("log off (l opens {})", locate(p)),
                (false, None) => "log off (l opens wpm.csv)".to_string(),
            };
            let stats_part = if settings.all_states {
                "all-state stats"
            } else {
                "discharge stats"
            };
            // Action feedback wins; otherwise a running log keeps its target visible so
            // the file can be found long after the "logging on" notice expires.
            let status_row = if notice.text().is_empty() {
                match (log.is_some(), &log_path) {
                    (true, Some(p)) => format!("logging on: appending to {}", locate(p)),
                    _ => String::new(),
                }
            } else {
                notice.text().to_string()
            };
            let banner = [
                notice.persistent().to_string(),
                log_part,
                export_dir
                    .as_ref()
                    .map(|d| format!("last export in {d}"))
                    .unwrap_or_default(),
                format!("tray {tray_status}"),
                format!("{:.2}s", rate.as_secs_f64()),
                format!("{} pts max", settings.sample_count),
                stats_part.to_string(),
            ]
            .into_iter()
            .filter(|part| !part.is_empty())
            .collect::<Vec<_>>()
            .join(" | ");
            if !paused {
                ui_state
                    .analytics
                    .refresh(&history, start.elapsed().as_secs_f64(), help);
            } else if help
                && ui_state.frozen_analytics.is_none()
                && let Some((frozen_history, _)) = &frozen_view
                && ui_state
                    .analytics
                    .refresh(frozen_history, start.elapsed().as_secs_f64(), true)
            {
                ui_state.frozen_analytics = ui_state.analytics.summary.clone();
            }
            if refresh.prepared.is_none() {
                if !paused {
                    ui_state.advance(
                        start.elapsed().as_secs_f64(),
                        settings.x_length.as_secs_f64(),
                        &history,
                        absolute,
                    );
                }
                refresh.prepared = Some(ui::PreparedHistory::new(
                    frozen_view.as_ref().map_or(&history, |v| &v.0),
                    absolute,
                    step,
                ));
            }
            if !paused {
                ui_state.advance_clock(
                    start.elapsed().as_secs_f64(),
                    settings.x_length.as_secs_f64(),
                    absolute,
                );
            } else {
                ui_state.select(absolute);
            }
            terminal
                .draw(|f| {
                    ui::draw_prepared(
                        f,
                        &ui::View {
                            history: frozen_view.as_ref().map_or(&history, |v| &v.0),
                            latest: frozen_view
                                .as_ref()
                                .map_or(current.as_ref(), |v| v.1.as_ref()),
                            paused,
                            help,
                            absolute,
                            step,
                            theme,
                            status: &banner,
                            notice: &status_row,
                            span: settings.x_length.as_secs_f64(),
                            now: if paused {
                                frozen
                            } else {
                                start.elapsed().as_secs_f64()
                            },
                            program_elapsed_s: start.elapsed().as_secs_f64(),
                            stale: !paused && last_received.elapsed() > rate * 2,
                            rate: rate.as_secs_f64(),
                            state: &ui_state,
                        },
                        refresh
                            .prepared
                            .as_ref()
                            .expect("prepared displayed history"),
                    )
                })
                .map_err(|e| e.to_string())?;
            if help {
                ui_state.help_scroll = ui_state.help_scroll.min(ui_state.help_max.get());
            }
            refresh.drawn(Instant::now());
        }
        if event::poll(Duration::from_millis(100)).map_err(|e| e.to_string())? {
            let input = event::read().map_err(|e| e.to_string())?;
            if matches!(input, Event::Resize(_, _)) {
                refresh.request();
            }
            let Event::Key(key) = input else {
                continue;
            };
            if key.kind == event::KeyEventKind::Release {
                continue;
            }
            refresh.request();
            if let Some(next) = theme_for_key(theme, key) {
                theme = next;
                notice.temporary(format!("theme: {theme} (session only)"), Instant::now());
                continue;
            }
            match key.code {
                KeyCode::Char('q') => stop.store(true, Ordering::Release),
                KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                    stop.store(true, Ordering::Release)
                }
                KeyCode::Char(' ') => {
                    refresh.invalidate();
                    paused = !paused;
                    frozen = start.elapsed().as_secs_f64();
                    frozen_view = if paused {
                        ui_state.frozen_analytics = ui_state.analytics.summary.clone();
                        Some((history.clone(), current.clone()))
                    } else {
                        None
                    };
                    if let Some(l) = &mut log
                        && let Err(e) = l.flush()
                    {
                        notice.sticky(format!("log flush: {e}"));
                    }
                }
                KeyCode::Char('r') => {
                    refresh.invalidate();
                    history.reset();
                    ui_state.reset(
                        start.elapsed().as_secs_f64(),
                        settings.x_length.as_secs_f64(),
                    );
                    current = None;
                    frozen_view = None;
                    paused = false;
                }
                KeyCode::Char('?') => help = !help,
                KeyCode::Up if help => {
                    ui_state.help_scroll = ui_state.help_scroll.saturating_sub(1)
                }
                KeyCode::Down if help => {
                    ui_state.help_scroll = ui_state
                        .help_scroll
                        .saturating_add(1)
                        .min(ui_state.help_max.get())
                }
                KeyCode::PageUp if help => {
                    ui_state.help_scroll = ui_state.help_scroll.saturating_sub(10)
                }
                KeyCode::PageDown if help => {
                    ui_state.help_scroll = ui_state
                        .help_scroll
                        .saturating_add(10)
                        .min(ui_state.help_max.get())
                }
                KeyCode::Home if help => ui_state.help_scroll = 0,
                KeyCode::End if help => ui_state.help_scroll = ui_state.help_max.get(),
                KeyCode::Char('a') => {
                    absolute = !absolute;
                    refresh.invalidate();
                }
                KeyCode::Char('s') => {
                    step = !step;
                    refresh.invalidate();
                }
                KeyCode::Esc => {
                    if help {
                        help = false;
                    } else {
                        // Sticky failures never expire on their own, so this is the
                        // only neutral way to get the row back to its separator.
                        notice.clear();
                    }
                }
                KeyCode::Char('e') => {
                    let now = Instant::now();
                    if let Err(wait) = export_gate(last_export, now, EXPORT_COOLDOWN) {
                        let last = export_dir
                            .as_ref()
                            .map_or_else(String::new, |d| format!("; last in {d}"));
                        notice.temporary(
                            format!("export throttled: next in {}s{last}", wait.as_secs().max(1)),
                            now,
                        );
                    } else {
                        last_export = Some(now);
                        if let Some(l) = &mut log {
                            let _ = l.flush();
                        }
                        let samples = &frozen_view.as_ref().map_or(&history, |v| &v.0).samples;
                        match crate::export::export(samples) {
                            Ok(base) => {
                                // Lead with the folder: the generated name is long enough
                                // to push the location off a narrow terminal, and the
                                // folder is what lets the user find the files again.
                                let dir = std::path::absolute(format!("{base}.csv"))
                                    .ok()
                                    .and_then(|p| p.parent().map(|d| d.display().to_string()))
                                    .unwrap_or_else(|| "the launch directory".into());
                                export_dir = Some(dir.clone());
                                notice.temporary(format!("exported 2 files to {dir}"), now);
                            }
                            Err(e) => notice.sticky(format!("export error: {e} in {cwd}")),
                        }
                    }
                }
                KeyCode::Char('l') => {
                    if let Some(mut l) = log.take() {
                        let path = log_path.as_deref().map(locate);
                        match l.flush() {
                            Ok(()) => {
                                let text = path.map_or_else(
                                    || "logging off".to_string(),
                                    |p| format!("logging off: {p}"),
                                );
                                notice.temporary(text, Instant::now())
                            }
                            // io::Error names no path: without one the user cannot
                            // tell which file failed or where it lived.
                            Err(e) => {
                                let text = path.map_or_else(
                                    || format!("log flush error: {e}"),
                                    |p| format!("log flush error: {e} at {p}"),
                                );
                                notice.sticky(text)
                            }
                        }
                    } else {
                        let path = log_path
                            .clone()
                            .unwrap_or_else(|| std::path::PathBuf::from("wpm.csv"));
                        match Log::open(&path) {
                            Ok(l) => {
                                log = Some(l);
                                log_path = Some(path.clone());
                                notice.temporary(
                                    format!("logging on: appending to {}", locate(&path)),
                                    Instant::now(),
                                );
                            }
                            Err(e) => {
                                notice.sticky(format!("log error: {e} opening {}", locate(&path)))
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }
    if let Some(log) = &mut log {
        log.flush().map_err(|e| e.to_string())?;
    }
    drop(sampler);
    drop(tray);
    // Restore the terminal first, then print: the alt screen is discarded on exit,
    // so anything written before restore would never reach the user's scrollback.
    drop(guard);
    if let Some(summary) = exit_summary(log_path.as_deref(), export_dir.as_deref()) {
        eprintln!("wpm: {summary}");
    }
    Ok(code)
}

/// Keep cadence when a query fits; skip overruns without a catch-up query burst.
pub fn next_deadline(previous: Instant, completed: Instant, rate: Duration) -> Instant {
    let next = previous + rate;
    if next < completed {
        completed + rate
    } else {
        next
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dashboard_idle_tick_and_changes_drive_redraws() {
        let start = Instant::now();
        let mut refresh = DashboardRefresh::default();
        let mut frames = 0;
        for tick in 0..=100 {
            let now = start + Duration::from_millis(tick * 100);
            if refresh.due(now) {
                frames += 1;
                refresh.drawn(now);
            }
        }
        assert_eq!(frames, 11, "idle rendering is 1 Hz, not 10 Hz");
        let now = start + Duration::from_millis(10_100);
        assert!(!refresh.due(now));
        refresh.request(); // Key or resize: do not wait for the next clock tick.
        assert!(refresh.due(now));
        refresh.drawn(now);
        assert!(!refresh.due(now));
        refresh.sample_received(false);
        assert!(refresh.due(now));
    }

    #[test]
    fn paused_samples_and_theme_or_resize_requests_keep_prepared_history() {
        let history = History::new(Duration::from_secs(300), 60, Duration::from_secs(5), false);
        let mut refresh = DashboardRefresh {
            prepared: Some(ui::PreparedHistory::new(&history, true, false)),
            ..DashboardRefresh::default()
        };
        refresh.drawn(Instant::now());
        refresh.sample_received(true);
        assert!(refresh.prepared.is_some());
        assert!(!refresh.dirty);
        refresh.request();
        assert!(refresh.prepared.is_some());
        refresh.sample_received(false);
        assert!(refresh.prepared.is_none());
        refresh.prepared = Some(ui::PreparedHistory::new(&history, true, false));
        refresh.invalidate(); // Reset, pause/resume, signed/stepped mode changes.
        assert!(refresh.prepared.is_none());
        assert!(refresh.dirty);
    }

    #[test]
    fn notice_expiry_keeps_failures_and_clears_action_feedback() {
        let start = Instant::now();
        let mut notice = Notice::default();
        assert_eq!(notice.text(), "", "no notice before the first keypress");

        notice.temporary("exported x.csv + .json in /tmp".into(), start);
        assert_eq!(
            notice.persistent(),
            "",
            "help must not repeat what the status row already shows"
        );
        notice.tick(start + Notice::TTL - Duration::from_millis(1));
        assert_eq!(notice.text(), "exported x.csv + .json in /tmp");
        notice.tick(start + Notice::TTL);
        assert_eq!(notice.text(), "", "action feedback must not linger forever");

        notice.sticky("export error: disk full".into());
        assert_eq!(notice.persistent(), "export error: disk full");
        notice.tick(start + Notice::TTL * 100);
        assert_eq!(
            notice.text(),
            "export error: disk full",
            "failures stay until replaced"
        );
    }

    #[test]
    fn esc_clears_a_sticky_failure_that_never_expires() {
        let start = Instant::now();
        let mut notice = Notice::default();
        notice.sticky("log error: Permission denied opening C:\\wpm.csv".into());
        notice.tick(start + Notice::TTL * 100);
        assert!(!notice.text().is_empty(), "sticky failures have no timeout");
        notice.clear();
        assert_eq!(notice.text(), "");
        assert_eq!(notice.persistent(), "");
        notice.clear(); // Dismissing an already empty notice is harmless.
        assert_eq!(notice.text(), "");
    }

    #[test]
    fn exports_are_throttled_so_a_held_key_cannot_fill_the_directory() {
        let start = Instant::now();
        assert_eq!(export_gate(None, start, EXPORT_COOLDOWN), Ok(()));
        assert_eq!(
            export_gate(Some(start), start, EXPORT_COOLDOWN),
            Err(EXPORT_COOLDOWN),
            "a repeat in the same instant is refused"
        );
        assert_eq!(
            export_gate(
                Some(start),
                start + Duration::from_millis(500),
                EXPORT_COOLDOWN
            ),
            Err(Duration::from_millis(1500))
        );
        assert_eq!(
            export_gate(Some(start), start + EXPORT_COOLDOWN, EXPORT_COOLDOWN),
            Ok(()),
            "the cooldown elapses"
        );
        // 40 key-repeat presses inside one cooldown window produce one export.
        let mut last = None;
        let mut exports = 0;
        for press in 0..40 {
            let now = start + Duration::from_millis(press * 30);
            if export_gate(last, now, EXPORT_COOLDOWN).is_ok() {
                last = Some(now);
                exports += 1;
            }
        }
        assert_eq!(exports, 1);
    }

    #[test]
    fn exit_summary_names_the_log_and_export_locations() {
        assert_eq!(exit_summary(None, None), None, "quiet run stays quiet");

        let log = exit_summary(Some(std::path::Path::new("wpm.csv")), None).unwrap();
        assert!(log.starts_with("log "), "{log}");
        assert!(log.ends_with("wpm.csv"), "{log}");
        assert!(
            std::path::Path::new(log.trim_start_matches("log ")).is_absolute(),
            "{log} must be findable after the TUI is gone"
        );

        assert_eq!(
            exit_summary(None, Some("/tmp/logs")).unwrap(),
            "exports in /tmp/logs"
        );
        let both = exit_summary(Some(std::path::Path::new("wpm.csv")), Some("/tmp/logs")).unwrap();
        assert!(both.contains("exports in /tmp/logs"), "{both}");
    }

    #[test]
    fn locate_names_an_absolute_path_for_file_feedback() {
        let shown = locate(std::path::Path::new("wpm.csv"));
        assert!(
            std::path::Path::new(&shown).is_absolute(),
            "{shown} must not be relative to the launch directory"
        );
        assert!(shown.ends_with("wpm.csv"));
    }

    #[test]
    fn theme_shortcuts_cycle_both_directions_and_wrap() {
        let next = KeyEvent::new(KeyCode::Char('t'), KeyModifiers::NONE);
        let previous = KeyEvent::new(KeyCode::Char('T'), KeyModifiers::SHIFT);
        assert_eq!(
            theme_for_key(Theme::Default, next),
            Some(Theme::HighContrast)
        );
        assert_eq!(theme_for_key(Theme::Nord, next), Some(Theme::Default));
        assert_eq!(theme_for_key(Theme::Default, previous), Some(Theme::Nord));
        assert_eq!(
            theme_for_key(Theme::Mono, previous),
            Some(Theme::HighContrast)
        );
        // Some terminals encode Shift+t as lowercase plus a modifier.
        assert_eq!(
            theme_for_key(
                Theme::Default,
                KeyEvent::new(KeyCode::Char('t'), KeyModifiers::SHIFT)
            ),
            Some(Theme::Nord)
        );
        assert_eq!(
            theme_for_key(
                Theme::Default,
                KeyEvent::new(KeyCode::Char('T'), KeyModifiers::NONE)
            ),
            Some(Theme::Nord)
        );
    }

    #[test]
    fn theme_shortcuts_ignore_release_events_and_modified_chords() {
        for modifiers in [
            KeyModifiers::CONTROL,
            KeyModifiers::ALT,
            KeyModifiers::CONTROL | KeyModifiers::SHIFT,
        ] {
            assert_eq!(
                theme_for_key(Theme::Default, KeyEvent::new(KeyCode::Char('t'), modifiers)),
                None
            );
        }
        let mut key = KeyEvent::new(KeyCode::Char('t'), KeyModifiers::NONE);
        key.kind = KeyEventKind::Release;
        assert_eq!(theme_for_key(Theme::Default, key), None);
        key.kind = KeyEventKind::Repeat;
        assert_eq!(
            theme_for_key(Theme::Default, key),
            Some(Theme::HighContrast)
        );
        assert_eq!(
            theme_for_key(
                Theme::Default,
                KeyEvent::new(KeyCode::Char('r'), KeyModifiers::NONE)
            ),
            None
        );
    }
}
