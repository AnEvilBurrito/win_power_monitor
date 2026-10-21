use crate::{
    cli::Theme,
    model::{History, Sample, State, Stats, number},
};
use chrono::{DateTime, Local, Utc};
use ratatui::{
    Frame,
    layout::{Constraint, Layout, Rect},
    style::{Color, Modifier, Style},
    symbols,
    text::{Line, Span},
    widgets::{Axis, Block, Borders, Chart, Clear, Dataset, GraphType, Paragraph, Wrap},
};

/// Clock anchor never follows later wall-clock adjustments. Viewports use monotonic seconds.
pub struct UiState {
    pub x: [f64; 2],
    pub y: [f64; 2],
    signed_y: [f64; 2],
    absolute_y: [f64; 2],
    origin: f64,
    wall: DateTime<Utc>,
}
impl UiState {
    pub fn new(wall: DateTime<Utc>, origin: f64, span: f64) -> Self {
        Self {
            x: [origin, origin + span],
            y: [0.0, 10.0],
            signed_y: [0.0, 0.0],
            absolute_y: [0.0, 10.0],
            origin,
            wall,
        }
    }
    pub fn reset(&mut self, origin: f64, span: f64) {
        self.origin = origin;
        self.x = [origin, origin + span];
        self.signed_y = [0.0, 0.0];
        self.absolute_y = [0.0, 10.0];
        self.y = self.absolute_y;
    }
    pub fn advance(&mut self, now: f64, span: f64, history: &History, absolute: bool) {
        let end = now.max(self.origin + span);
        self.x = [end - span, end];
        for (mode, bounds) in [(false, &mut self.signed_y), (true, &mut self.absolute_y)] {
            for w in history
                .samples
                .iter()
                .filter_map(|s| s.watts)
                .filter(|w| w.is_finite())
            {
                let w = if mode { w.abs() } else { w };
                if w < bounds[0] || w > bounds[1] {
                    let expanded = nice_y_bounds(std::iter::once(w), mode);
                    bounds[0] = bounds[0].min(expanded[0]);
                    bounds[1] = bounds[1].max(expanded[1]);
                }
            }
        }
        self.select(absolute);
    }
    pub fn select(&mut self, absolute: bool) {
        self.y = if absolute {
            self.absolute_y
        } else {
            if self.signed_y == [0.0, 0.0] {
                [0.0, 10.0]
            } else {
                self.signed_y
            }
        };
    }
    pub fn clock_label(&self, elapsed: f64) -> String {
        (self.wall + chrono::Duration::milliseconds((elapsed * 1000.0).round() as i64))
            .with_timezone(&Local)
            .format("%H:%M:%S")
            .to_string()
    }
}
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
    pub rate: f64,
    pub state: &'a UiState,
}
fn stat_line(label: &str, s: &Stats, absolute: bool) -> Line<'static> {
    let (avg, min, max) = if absolute {
        (
            (s.count > 0).then(|| s.magnitude_sum / s.count as f64),
            s.magnitude_min,
            s.magnitude_max,
        )
    } else {
        (s.average(), s.min, s.max)
    };
    Line::from(vec![
        Span::styled(
            format!(" {label:<7} "),
            Style::default().fg(Color::DarkGray),
        ),
        Span::raw(format!(
            "avg {}  min {}  max {} W",
            number(avg),
            number(min),
            number(max)
        )),
    ])
}
pub fn draw(frame: &mut Frame, v: &View<'_>) {
    let area = frame.area();
    let mono = matches!(v.theme, Theme::Mono);
    let axes = Style::default().fg(if mono { Color::White } else { Color::Cyan });
    let dim = Style::default().fg(if matches!(v.theme, Theme::HighContrast) {
        Color::Gray
    } else {
        Color::DarkGray
    });
    let color = if mono {
        Color::White
    } else if v.latest.is_some_and(|s| s.state == State::Charging) {
        Color::Green
    } else {
        Color::Yellow
    };
    let rows = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Min(0),
        Constraint::Length(1),
        Constraint::Length(if area.height >= 24 { 3 } else { 2 }),
        Constraint::Length(1),
        Constraint::Length(1),
    ])
    .split(area);
    for i in [1, 3, 5, 7] {
        frame.render_widget(
            Paragraph::new("─".repeat(area.width as usize)).style(dim),
            rows[i],
        );
    }
    let live = if v.paused {
        "PAUSED"
    } else if v.stale {
        "NO RECENT POLL"
    } else {
        "LIVE"
    };
    let source = v.latest.map_or("waiting", |s| match s.source.as_str() {
        "wmi:root\\wmi:BatteryStatus" => "root\\wmi",
        "k32:GetSystemPowerStatus" => "Win32",
        other => other,
    });
    let state = v.latest.map_or(State::Unknown, |s| s.state);
    let left = if area.width >= 100 {
        format!(
            " wpm · {state:?} · {source} · {:.1}s · {:.0}s · {} pts · {live}",
            v.rate,
            v.span,
            v.history.samples.len()
        )
    } else {
        format!(
            " wpm · {source} · {:.1}s · {:.0}s · {} pts · {live}",
            v.rate,
            v.span,
            v.history.samples.len()
        )
    };
    let top = Layout::horizontal([Constraint::Min(0), Constraint::Length(8)]).split(rows[0]);
    frame.render_widget(Paragraph::new(left), top[0]);
    frame.render_widget(Paragraph::new("? help").style(dim).right_aligned(), top[1]);
    let kpi = if let Some(s) = v.latest {
        let direction = match s.state {
            State::Discharging => "↓",
            State::Charging => "↑",
            State::Idle => "Idle",
            State::Unknown => "Unknown",
        };
        let value = |n: Option<f64>| {
            number(
                n.filter(|n| n.is_finite())
                    .map(|n| if v.absolute { n.abs() } else { n }),
            )
        };
        let watts = if v.stale {
            "—".into()
        } else {
            value(s.watts)
        };
        let electrical = if area.width >= 80 {
            format!("   {} V   {} A", number(s.volts), value(s.amps))
        } else {
            String::new()
        };
        let cells = if area.width >= 100 { 16 } else { 6 };
        let gauge = s.pct.filter(|p| p.is_finite()).map_or_else(
            || "unavailable".into(),
            |p| {
                let filled = (p.clamp(0.0, 100.0) / 100.0 * cells as f64).round() as usize;
                format!("{}{}", "█".repeat(filled), "░".repeat(cells - filled))
            },
        );
        let pct = s.pct.map_or("—".into(), |p| format!("{p:.0}"));
        let eta = if v.stale {
            "—".into()
        } else {
            compact_eta(s)
        };
        format!(" {watts} W {direction}{electrical}   {gauge} {pct}%  ETA {eta}")
    } else {
        " Waiting for battery flow — not system/wall draw".into()
    };
    frame.render_widget(
        Paragraph::new(kpi).style(Style::default().fg(color).add_modifier(Modifier::BOLD)),
        rows[2],
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
    let zero = [(v.state.x[0], 0.0), (v.state.x[1], 0.0)];
    let mut datasets = vec![
        Dataset::default()
            .data(&zero)
            .graph_type(GraphType::Line)
            .style(dim),
    ];
    datasets.extend(segments.iter().filter(|s| !s.is_empty()).map(|data| {
        Dataset::default()
            .data(data)
            .marker(symbols::Marker::Braille)
            .graph_type(GraphType::Line)
            .style(Style::default().fg(color))
    }));
    let bounds = v.state.y;
    let ticks = [bounds[0], (bounds[0] + bounds[1]) / 2.0, bounds[1]];
    frame.render_widget(
        Chart::new(datasets)
            .x_axis(
                Axis::default().style(axes).bounds(v.state.x).labels(
                    v.state
                        .x
                        .map(|t| Line::styled(v.state.clock_label(t), axes)),
                ),
            )
            .y_axis(
                Axis::default()
                    .title("W")
                    .style(axes)
                    .bounds(bounds)
                    .labels(ticks.map(|t| Line::styled(format!("{t:.0}"), axes))),
            ),
        rows[4],
    );
    let mut stats = vec![
        stat_line("Session", &v.history.session, v.absolute),
        stat_line("Window", &v.history.window(), v.absolute),
    ];
    if rows[6].height >= 3 {
        stats.push(Line::from(vec![
            Span::styled(" Energy  ", dim),
            Span::raw(format!(
                "out {:.3} Wh · in {:.3} Wh",
                v.history.session.discharge_wh, v.history.session.charge_wh
            )),
            Span::styled("   Health — · Cycles —", dim),
        ]));
    }
    frame.render_widget(Paragraph::new(stats), rows[6]);
    frame.render_widget(
        Paragraph::new(if area.width >= 100 {
            " q quit · space pause · r reset · e export · l log · a signed/abs · s step · ? help"
        } else {
            " q quit · space pause · r reset · e export · ? help"
        })
        .style(dim),
        rows[8],
    );
    if v.help {
        let popup = Rect::new(
            area.x + area.width / 20,
            area.y + area.height / 10,
            area.width - area.width / 10,
            area.height - area.height / 5,
        );
        let diagnostic = v.latest.map_or_else(
            || "Waiting for sampler".into(),
            |s| {
                format!(
                    "poll #{} · age {:.1}s · query {:.0}ms · unchanged {}\n{}\n{}",
                    s.poll_count,
                    (v.now - s.elapsed_s).max(0.0),
                    s.query_ms,
                    s.unchanged_polls,
                    s.reason
                        .as_deref()
                        .unwrap_or("battery flow, not system/wall draw"),
                    s.diagnostics.join("\n")
                )
            },
        );
        frame.render_widget(Clear, popup);
        frame.render_widget(Paragraph::new(format!("q quit · space freeze display (sampling/log/tray continue)\nr reset · e signed export · l log · a signed/magnitude · s step\n? close help · {}\nETA: {}\nDrain: {} %/hr estimate · Health/cycles unavailable\n{}\n{}", if v.absolute {"magnitude"} else {"signed"}, v.latest.map_or("—".into(), remaining_label),number(v.latest.filter(|_| !v.stale).and_then(|s| s.drain_pct_hr)),v.status,diagnostic)).wrap(Wrap {trim:true}).block(Block::default().title(" Help / diagnostics ").borders(Borders::ALL)),popup);
    }
}
fn compact_eta(s: &Sample) -> String {
    s.eta_s.map_or("—".into(), |t| {
        format!(
            "{}h{:02}m{}",
            t / 3600,
            t % 3600 / 60,
            if s.eta_source
                .as_deref()
                .is_some_and(|s| s.contains("estimate"))
            {
                " est"
            } else {
                ""
            }
        )
    })
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
