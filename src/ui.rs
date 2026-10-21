use crate::{
    cli::Theme,
    model::{History, Sample, State, number},
};
use ratatui::{
    Frame,
    layout::{Constraint, Layout},
    style::{Color, Style},
    symbols,
    text::Line,
    widgets::{Axis, Chart, Dataset, GraphType, Paragraph},
};
pub struct View<'a> {
    pub history: &'a History,
    pub latest: Option<&'a Sample>,
    pub paused: bool,
    pub help: bool,
    pub absolute: bool,
    pub step: bool,
    pub theme: Theme,
    pub status: &'a str,
    pub span: f64,
    pub now: f64,
    pub stale: bool,
}
pub fn draw(frame: &mut Frame, v: &View<'_>) {
    let area = frame.area();
    let color = match v.theme {
        Theme::Mono => Color::White,
        Theme::HighContrast => Color::Yellow,
        _ => {
            if v.latest.is_some_and(|s| s.state == State::Charging) {
                Color::Green
            } else {
                Color::Cyan
            }
        }
    };
    let rows = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Min(2),
        Constraint::Length(if area.height >= 18 { 3 } else { 2 }),
        Constraint::Length(1),
    ])
    .split(area);
    let mode = if v.absolute { "magnitude" } else { "signed" };
    let status = v.latest.map_or_else(
        || "wpm · waiting for sampler".into(),
        |s| {
            format!(
                "wpm · {:?} · poll #{} {:.1}s ago · {} · {} · {}",
                s.state,
                s.poll_count,
                (v.now - s.elapsed_s).max(0.0),
                s.source,
                if v.paused {
                    "PAUSED (sampler/log/tray live)"
                } else if v.stale {
                    "NO RECENT POLL"
                } else {
                    "LIVE"
                },
                v.status
            )
        },
    );
    frame.render_widget(
        Paragraph::new(status).style(Style::default().fg(color)),
        rows[0],
    );
    let current = v.latest.map_or_else(
        || "Waiting for battery flow (not system draw)".into(),
        |s| {
            let arrow = match s.state {
                State::Discharging => "↓",
                State::Charging => "↑",
                _ => "·",
            };
            let watts = if v.stale {
                None
            } else {
                s.watts.map(|w| if v.absolute { w.abs() } else { w })
            };
            let electrical = if area.width >= 100 {
                format!(
                    "  {} V  {} A",
                    number(s.volts),
                    number(s.amps.map(|a| if v.absolute { a.abs() } else { a }))
                )
            } else {
                String::new()
            };
            format!(
                "{} W {arrow}{electrical}  {}%  remaining {}",
                number(watts),
                number(s.pct),
                if v.stale {
                    "—".into()
                } else {
                    remaining_label(s)
                }
            )
        },
    );
    frame.render_widget(
        Paragraph::new(current).style(Style::default().fg(color)),
        rows[1],
    );
    let mut segments: Vec<Vec<(f64, f64)>> = vec![vec![]];
    for s in &v.history.samples {
        let watts = s.watts.filter(|w| w.is_finite());
        if s.gap || watts.is_none() {
            segments.push(vec![]);
        }
        if let Some(w) = watts {
            let w = if v.absolute { w.abs() } else { w };
            let segment = segments.last_mut().expect("segment");
            if v.step
                && let Some((_, previous)) = segment.last().copied()
            {
                segment.push((s.elapsed_s, previous));
            }
            segment.push((s.elapsed_s, w));
        }
    }
    let bounds = nice_y_bounds(segments.iter().flatten().map(|(_, w)| *w), v.absolute);
    let end = v.now.max(1.0);
    let start = (end - v.span).max(0.0);
    let zero = [(start, 0.0), (end, 0.0)];
    let mut datasets: Vec<_> = segments
        .iter()
        .filter(|s| !s.is_empty())
        .map(|data| {
            Dataset::default()
                .data(data)
                .marker(symbols::Marker::Braille)
                .graph_type(GraphType::Line)
                .style(Style::default().fg(if v.paused { Color::DarkGray } else { color }))
        })
        .collect();
    datasets.push(
        Dataset::default()
            .data(&zero)
            .graph_type(GraphType::Line)
            .style(Style::default().fg(Color::DarkGray)),
    );
    let ticks = [bounds[0], (bounds[0] + bounds[1]) / 2.0, bounds[1]];
    let chart = Chart::new(datasets)
        .x_axis(Axis::default().bounds([start, end]).labels([
            Line::from(format!("{start:.0}s")),
            Line::from(format!("{end:.0}s")),
        ]))
        .y_axis(
            Axis::default()
                .bounds(bounds)
                .labels(ticks.map(|t| Line::from(format!("{t:.0}")))),
        );
    frame.render_widget(chart, rows[2]);
    let diagnostic = v.latest.map_or_else(String::new, |s| format!(
        "poll #{} · last {:.1}s ago · query {:.0}ms · unchanged {} polls (not proof of stale) · {}",
        s.poll_count, (v.now-s.elapsed_s).max(0.0), s.query_ms, s.unchanged_polls,
        s.reason.as_deref().unwrap_or("battery flow, not system/wall draw")));
    let drain = if v.stale {
        None
    } else {
        v.latest.and_then(|s| s.drain_pct_hr)
    };
    frame.render_widget(
        Paragraph::new(format!(
            "session {}\nwindow drain {} %/hr est · {}\n{diagnostic}",
            v.history.session.display_label(v.absolute),
            number(drain),
            v.history.window().display_label(v.absolute)
        )),
        rows[3],
    );
    frame.render_widget(Paragraph::new(if v.help {format!("q quit · space freeze · r reset · e signed export · l log · a {mode}/signed · s step · ? help")}
        else {format!("q quit · space pause · r reset · e export · l log · a {mode} · s step · ? help")}), rows[4]);
}
pub fn remaining_label(s: &Sample) -> String {
    s.eta_s.map_or_else(
        || "—".into(),
        |seconds| {
            format!(
                "{}h {:02}m ({})",
                seconds / 3600,
                seconds % 3600 / 60,
                s.eta_source.as_deref().unwrap_or("reported")
            )
        },
    )
}
/// Zero-anchored range, 10% headroom, outward 1/2/5×10^n endpoints, 10 W minimum.
pub fn nice_y_bounds(values: impl Iterator<Item = f64>, absolute: bool) -> [f64; 2] {
    let (mut low, mut high) = (0.0_f64, 0.0_f64);
    for value in values.filter(|v| v.is_finite()) {
        let value = if absolute { value.abs() } else { value };
        low = low.min(value);
        high = high.max(value);
    }
    fn nice(value: f64) -> f64 {
        let value = (value * 1.1).max(10.0);
        let scale = 10_f64.powf(value.log10().floor());
        [1.0, 2.0, 5.0, 10.0]
            .into_iter()
            .find(|n| n * scale >= value)
            .unwrap_or(10.0)
            * scale
    }
    if low == 0.0 {
        [0.0, nice(high)]
    } else if high == 0.0 {
        [-nice(-low), 0.0]
    } else {
        [-nice(-low), nice(high)]
    }
}
