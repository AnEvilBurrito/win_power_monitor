use chrono::{TimeZone, Utc};
use ratatui::{
    Terminal,
    backend::TestBackend,
    buffer::Buffer,
    style::{Color, Modifier},
};
use std::time::Duration;
use wpm_cli::{
    cli::Theme,
    model::{History, Sample, State},
    ui::{self, UiState, View},
};

fn history() -> History {
    History::new(
        Duration::from_secs(300),
        1000,
        Duration::from_secs(2),
        false,
    )
}
fn sample(t: f64, watts: Option<f64>) -> Sample {
    let mut s = Sample::empty(t);
    s.state = State::Discharging;
    s.source = "wmi:root\\wmi:BatteryStatus".into();
    s.watts = watts;
    s.volts = Some(15.62);
    s.amps = Some(-0.794);
    s.pct = Some(81.0);
    s.eta_s = Some(8040);
    s.eta_source = Some("capacity/rate estimate".into());
    s
}
fn state() -> UiState {
    UiState::new(
        Utc.with_ymd_and_hms(2026, 10, 21, 23, 59, 0).unwrap(),
        0.0,
        300.0,
    )
}
fn render(
    w: u16,
    h: u16,
    history: &History,
    state: &UiState,
    signed: bool,
    paused: bool,
    help: bool,
) -> Buffer {
    let mut terminal = Terminal::new(TestBackend::new(w, h)).unwrap();
    terminal
        .draw(|f| {
            ui::draw(
                f,
                &View {
                    history,
                    latest: history.samples.back(),
                    paused,
                    help,
                    absolute: !signed,
                    step: false,
                    theme: Theme::Default,
                    status: "log off · tray off",
                    span: 300.0,
                    now: state.x[1],
                    stale: false,
                    rate: 2.0,
                    state,
                },
            )
        })
        .unwrap();
    terminal.backend().buffer().clone()
}
fn row(b: &Buffer, y: u16) -> String {
    (0..b.area.width).map(|x| b[(x, y)].symbol()).collect()
}
fn text(b: &Buffer) -> String {
    (0..b.area.height)
        .map(|y| row(b, y))
        .collect::<Vec<_>>()
        .join("\n")
}
#[test]
fn fixed_viewport_growth_rollover_reset_and_clock_anchor() {
    let mut h = history();
    let mut state = state();
    for t in [0.0, 2.0, 100.0, 299.0, 300.0] {
        h.push(sample(t, Some(-12.4)));
        state.advance(t, 300.0, &h, true);
        assert_eq!(state.x, [0.0, 300.0]);
        assert_eq!(state.y, [0.0, 20.0]);
    }
    state.advance(302.0, 300.0, &h, true);
    assert_eq!(state.x, [2.0, 302.0]);
    let expected = (Utc.with_ymd_and_hms(2026, 10, 21, 23, 59, 0).unwrap()
        + chrono::Duration::seconds(302))
    .with_timezone(&chrono::Local)
    .format("%H:%M:%S")
    .to_string();
    assert_eq!(state.clock_label(302.0), expected);
    state.reset(400.0, 300.0);
    assert_eq!(state.x, [400.0, 700.0]);
    assert_eq!(state.y, [0.0, 10.0]);
    assert_eq!(
        state.clock_label(302.0),
        expected,
        "reset must not move wall anchor"
    );
}
#[test]
fn scale_only_expands_and_modes_keep_their_own_bounds() {
    let mut h = history();
    let mut state = state();
    h.push(sample(0.0, Some(-27.1)));
    state.advance(0.0, 300.0, &h, true);
    assert_eq!(state.y, [0.0, 50.0]);
    h.reset();
    h.push(sample(302.0, Some(-4.0)));
    state.advance(302.0, 300.0, &h, true);
    assert_eq!(state.y, [0.0, 50.0]);
    h.push(sample(304.0, Some(-49.0)));
    state.advance(304.0, 300.0, &h, true);
    assert_eq!(state.y, [0.0, 50.0], "no headroom-triggered jitter");
    h.push(sample(306.0, Some(-51.0)));
    state.advance(306.0, 300.0, &h, true);
    assert_eq!(state.y, [0.0, 100.0]);
    state.select(false);
    assert!(state.y[0] <= -51.0 && state.y[1] >= 0.0);
    state.select(true);
    assert_eq!(state.y, [0.0, 100.0]);
}
#[test]
fn five_bands_axes_styles_and_freeze_at_supported_sizes() {
    let mut h = history();
    let mut state = state();
    for t in (0..=100).step_by(2) {
        h.push(sample(t as f64, Some(-12.4)));
    }
    state.advance(100.0, 300.0, &h, true);
    for (w, height) in [(120, 32), (100, 24), (80, 24), (80, 14), (60, 14)] {
        let b = render(w, height, &h, &state, false, false, false);
        assert!(row(&b, 0).contains("root\\wmi"));
        assert!(row(&b, 0).contains("? help"));
        assert!(row(&b, 0).contains("LIVE"));
        assert!(row(&b, 2).contains("12.40 W ↓"));
        assert!(row(&b, 2).contains("81%"));
        assert!(row(&b, 2).contains("ETA 2h14m est"));
        for y in [1, 3, height - if height >= 24 { 6 } else { 5 }, height - 2] {
            assert_eq!(row(&b, y), "─".repeat(w as usize));
        }
        assert!(row(&b, height - 1).contains("q quit"));
        let all = text(&b);
        assert!(all.contains("Session"));
        assert!(all.contains("Window"));
        assert!(!all.contains("poll #"));
        assert!(all.contains(&state.clock_label(0.0)));
        assert!(all.contains(&state.clock_label(300.0)));
        let top = (4..height - 6)
            .find(|y| row(&b, *y).contains("20"))
            .unwrap();
        let bottom = (top + 1..height - 3)
            .find(|y| row(&b, *y).trim_start().starts_with('0'))
            .unwrap();
        assert!(top < bottom);
        assert!(b[(1, 2)].modifier.contains(Modifier::BOLD));
        assert_eq!(b[(1, 2)].fg, Color::Yellow);
        if w >= 80 {
            assert!(row(&b, 2).contains("15.62 V"));
        }
        let frozen = render(w, height, &h, &state, false, true, false);
        for y in 4..height - if height >= 24 { 6 } else { 5 } {
            assert_eq!(row(&b, y), row(&frozen, y));
        }
    }
}
#[test]
fn trace_grows_left_to_right_and_diagnostics_are_help_only() {
    let mut h = history();
    let mut state = state();
    h.push(sample(0.0, Some(-12.4)));
    h.push(sample(2.0, Some(-12.4)));
    state.advance(2.0, 300.0, &h, true);
    let early = render(120, 32, &h, &state, false, false, false);
    for t in (4..=100).step_by(2) {
        h.push(sample(t as f64, Some(-12.4)));
    }
    state.advance(100.0, 300.0, &h, true);
    let later = render(120, 32, &h, &state, false, false, false);
    let rightmost = |b: &Buffer| {
        (4..26)
            .flat_map(|y| (0..120).map(move |x| (x, y)))
            .filter(|&(x, y)| b[(x, y)].fg == Color::Yellow && b[(x, y)].symbol() != " ")
            .map(|(x, _)| x)
            .max()
            .unwrap()
    };
    assert!(rightmost(&early) < 15);
    assert!(rightmost(&later) > 30 && rightmost(&later) < 50);
    assert!(text(&render(120, 32, &h, &state, false, false, true)).contains("poll #"));
    h.reset();
    h.push(sample(0.0, None));
    assert!(row(&render(120, 32, &h, &state, false, false, false), 2).contains("— W"));
}

/// Optional persistent HTML of the actual styled TestBackend cells, for browser inspection.
#[test]
fn representative_render_capture() {
    let Ok(path) = std::env::var("WPM_RENDER_HTML") else {
        return;
    };
    let mut h = history();
    let mut state = state();
    for t in (0..=180).step_by(2) {
        h.push(sample(
            t as f64,
            Some(-(12.4 + 4.0 * (t as f64 / 20.0).sin())),
        ));
    }
    state.advance(180.0, 300.0, &h, true);
    let mut html = String::from(
        "<!doctype html><meta charset='utf-8'><title>wpm synthetic TestBackend renders</title><style>body{background:#10151c;color:#e5e9ef}pre{font:16px/1.3 Consolas,monospace;white-space:pre}h2{font:18px sans-serif}</style>",
    );
    for (w, height) in [(120, 32), (100, 24), (80, 14), (60, 14)] {
        html.push_str(&format!(
            "<h2>Synthetic {w} × {height} — actual TestBackend cells</h2><pre>"
        ));
        let b = render(w, height, &h, &state, false, false, false);
        for y in 0..height {
            for x in 0..w {
                let c = &b[(x, y)];
                let fg = match c.fg {
                    Color::Yellow => "#ffd166",
                    Color::Green => "#75dc95",
                    Color::Cyan => "#6fdaee",
                    Color::DarkGray => "#9199a6",
                    _ => "#e5e9ef",
                };
                let weight = if c.modifier.contains(Modifier::BOLD) {
                    "bold"
                } else {
                    "normal"
                };
                html.push_str(&format!(
                    "<span style='color:{fg};font-weight:{weight}'>{}</span>",
                    c.symbol()
                        .replace('&', "&amp;")
                        .replace('<', "&lt;")
                        .replace('>', "&gt;")
                ));
            }
            html.push('\n');
        }
        html.push_str("</pre>");
    }
    std::fs::write(path, html).unwrap();
}
