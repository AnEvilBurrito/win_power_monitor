use crate::{
    cli::Mode,
    config::Settings,
    export::Log,
    model::{History, Metrics, Sample, number, stamp_completion},
    provider::Provider,
    tray::{Latest, Reading, Tray},
    ui,
};
use crossterm::event::{self, Event, KeyCode, KeyModifiers};
use std::{
    io::{self, Write},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};

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
fn write_log(log: &mut Option<Log>, s: &Sample, status: &mut String) {
    if let Some(writer) = log
        && let Err(e) = writer.write(s)
    {
        *status = format!("log error: {e}; disabled");
        eprintln!("wpm: {status}");
        *log = None;
    }
}
pub fn run(settings: Settings) -> Result<u8, String> {
    let start = Instant::now();
    let mut log = match &settings.log {
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
    let mut status = String::new();
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
            write_log(&mut log, &s, &mut status);
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
    let _guard = TerminalGuard;
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
    let mut last_received = Instant::now();
    let mut frozen = 0.0;
    let mut frozen_view: Option<(History, Option<Sample>)> = None;
    let mut code = 0;
    while !stop.load(Ordering::Acquire) {
        for s in receiver.try_iter() {
            last_received = Instant::now();
            if settings.require_battery && s.battery_present != Some(true) {
                code = 3;
                stop.store(true, Ordering::Release);
            }
            write_log(&mut log, &s, &mut status);
            current = Some(history.push(s));
            if let Some(sample) = &current {
                ui_state.analytics.observe(sample);
            }
        }
        let tray_status = tray
            .as_ref()
            .and_then(|t| t.status.lock().ok().map(|s| s.clone()))
            .unwrap_or_else(|| "off".into());
        let banner = format!(
            "{status} | log {} | tray {tray_status} | {:.2}s | {} pts max | {}",
            if log.is_some() { "on" } else { "off" },
            rate.as_secs_f64(),
            settings.sample_count,
            if settings.all_states {
                "all-state stats"
            } else {
                "discharge stats"
            }
        );
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
        if !paused {
            ui_state.advance(
                start.elapsed().as_secs_f64(),
                settings.x_length.as_secs_f64(),
                &history,
                absolute,
            );
        } else {
            ui_state.select(absolute);
        }
        terminal
            .draw(|f| {
                ui::draw(
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
                        theme: settings.theme,
                        status: &banner,
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
                )
            })
            .map_err(|e| e.to_string())?;
        if help {
            ui_state.help_scroll = ui_state.help_scroll.min(ui_state.help_max.get());
        }
        if event::poll(Duration::from_millis(100)).map_err(|e| e.to_string())?
            && let Event::Key(key) = event::read().map_err(|e| e.to_string())?
        {
            if key.kind == event::KeyEventKind::Release {
                continue;
            }
            match key.code {
                KeyCode::Char('q') => stop.store(true, Ordering::Release),
                KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                    stop.store(true, Ordering::Release)
                }
                KeyCode::Char(' ') => {
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
                        status = format!("log flush: {e}");
                    }
                }
                KeyCode::Char('r') => {
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
                KeyCode::Char('a') => absolute = !absolute,
                KeyCode::Char('s') => step = !step,
                KeyCode::Char('e') => {
                    if let Some(l) = &mut log {
                        let _ = l.flush();
                    }
                    status = match crate::export::export(
                        &frozen_view.as_ref().map_or(&history, |v| &v.0).samples,
                    ) {
                        Ok(base) => format!("exported {base}.csv + .json"),
                        Err(e) => format!("export error: {e}"),
                    };
                }
                KeyCode::Char('l') => {
                    if let Some(mut l) = log.take() {
                        status = match l.flush() {
                            Ok(()) => "logging off".into(),
                            Err(e) => format!("log flush: {e}"),
                        };
                    } else {
                        match Log::open(
                            settings
                                .log
                                .as_deref()
                                .unwrap_or(std::path::Path::new("wpm.csv")),
                        ) {
                            Ok(l) => {
                                log = Some(l);
                                status = "logging on".into();
                            }
                            Err(e) => status = format!("log error: {e}"),
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
