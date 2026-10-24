use crate::{
    cli::Theme,
    model::{History, Sample, State, Stats, number},
    theme::Palette,
};
use chrono::{DateTime, Local, Utc};
use ratatui::{
    Frame,
    layout::{Constraint, Layout, Rect},
    style::{Modifier, Style},
    symbols,
    text::{Line, Span},
    widgets::{Axis, Block, Borders, Chart, Clear, Dataset, GraphType, Paragraph, Wrap},
};

/// Clock anchor never follows later wall-clock adjustments. Viewports use monotonic seconds.
pub struct UiState {
    pub analytics: crate::analytics::Analytics,
    pub frozen_analytics: Option<crate::analytics::Summary>,
    pub help_scroll: u16,
    pub help_max: std::cell::Cell<u16>,
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
            analytics: crate::analytics::Analytics::default(),
            frozen_analytics: None,
            help_scroll: 0,
            help_max: std::cell::Cell::new(0),
            x: [origin, origin + span],
            y: [0.0, 10.0],
            signed_y: [0.0, 0.0],
            absolute_y: [0.0, 10.0],
            origin,
            wall,
        }
    }
    pub fn reset(&mut self, origin: f64, span: f64) {
        self.analytics.reset();
        self.frozen_analytics = None;
        self.help_scroll = 0;
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
    pub program_elapsed_s: f64,
    pub stale: bool,
    pub rate: f64,
    pub state: &'a UiState,
}
fn stat_line(label: &str, s: &Stats, absolute: bool, palette: Palette) -> Line<'static> {
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
            Style::default().fg(palette.secondary),
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
    let palette = v.theme.palette();
    frame.render_widget(Block::default().style(palette.style()), area);
    if area.width < 30 || area.height < 8 {
        frame.render_widget(
            Paragraph::new("wpm\nTerminal too small\n? diagnostics / q quit")
                .style(palette.style()),
            area,
        );
        return;
    }
    let axes = Style::default().fg(palette.secondary);
    let dim = Style::default().fg(palette.secondary);
    let roomy = area.width >= 100 && area.height >= 24;
    let unavailable = v.stale || v.latest.is_some_and(|sample| sample.transition);
    let rows = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(if roomy { 2 } else { 1 }),
        Constraint::Length(u16::from(area.height >= 24)),
        Constraint::Min(0),
        Constraint::Length(u16::from(area.height >= 24)),
        Constraint::Length(if area.height >= 24 { 5 } else { 4 }),
        Constraint::Length(1),
        Constraint::Length(1),
    ])
    .split(area);
    for i in [1, 3, 5, 7] {
        frame.render_widget(
            Paragraph::new("─".repeat(area.width as usize))
                .style(Style::default().fg(palette.border)),
            rows[i],
        );
    }
    let live = if v.paused {
        "PAUSED"
    } else if v.latest.is_some_and(|s| s.transition) {
        "TRANSITION"
    } else if v.stale {
        "NO RECENT POLL"
    } else {
        "LIVE"
    };
    let source = v.latest.map_or("waiting", |s| match s.source.as_str() {
        "native:IOCTL_BATTERY_QUERY_STATUS" => "Battery IOCTL",
        "wmi:root\\wmi:BatteryStatus:fallback_unverified" => "WMI fallback (unverified)",
        "wmi:root\\wmi:BatteryStatus" => "WMI",
        "k32:GetSystemPowerStatus" => "Win32",
        "native:sysfs_power_supply" => "sysfs",
        "native:macos_ioreg" => "IORegistry",
        other => other,
    });
    let left = Line::from(vec![
        Span::styled(" wpm   ", dim),
        Span::styled(
            live,
            Style::default()
                .fg(if v.paused || unavailable {
                    palette.warning
                } else {
                    palette.foreground
                })
                .add_modifier(Modifier::BOLD),
        ),
        Span::styled(format!("   {source}"), dim),
    ]);
    let top = Layout::horizontal([Constraint::Min(0), Constraint::Length(8)]).split(rows[0]);
    frame.render_widget(Paragraph::new(left), top[0]);
    frame.render_widget(Paragraph::new("? help").style(dim).right_aligned(), top[1]);
    let kpi = if let Some(s) = v.latest {
        let direction = if s.is_system_power() {
            "system input"
        } else {
            match s.state {
                State::Discharging => "↓ discharge",
                State::Charging => "↑ charging",
                State::Idle => "Idle",
                State::Mixed => "Mixed",
                State::Unknown => "Unknown",
            }
        };
        let value = |n: Option<f64>| {
            number(
                n.filter(|n| !unavailable && n.is_finite())
                    .map(|n| if v.absolute { n.abs() } else { n }),
            )
        };
        let watts = if unavailable {
            "—".into()
        } else {
            value(s.watts)
        };
        let pct = s
            .pct
            .filter(|pct| !unavailable && pct.is_finite())
            .map_or("—".into(), |p| format!("{p:.0}"));
        let eta = if unavailable {
            "—".into()
        } else {
            compact_eta(s)
        };
        let gauge = s
            .pct
            .filter(|pct| !unavailable && (0.0..=100.0).contains(pct) && area.width >= 80)
            .map_or_else(String::new, |pct| {
                let cells = if roomy { 16 } else { 6 };
                let filled = (pct / 100.0 * cells as f64).round() as usize;
                format!(" {}{}", "█".repeat(filled), "░".repeat(cells - filled))
            });
        let details = if s.is_system_power() {
            "   user-confirmed meter scope".into()
        } else {
            format!(
                "   Battery{gauge} {pct}%{}",
                if s.pct
                    .is_some_and(|pct| !unavailable && (0.0..=20.0).contains(&pct))
                {
                    " low"
                } else {
                    ""
                }
            )
        };
        let low_battery = !s.is_system_power()
            && !unavailable
            && s.pct.is_some_and(|pct| (0.0..=20.0).contains(&pct));
        let mut lines = vec![Line::from(vec![
            Span::styled(
                format!(" {watts} W"),
                Style::default().add_modifier(Modifier::BOLD),
            ),
            Span::styled(
                format!(" {}{direction}", if s.is_system_power() { "" } else { " " }),
                Style::default().fg(if unavailable {
                    palette.neutral
                } else {
                    palette.state(s.state)
                }),
            ),
            Span::styled(
                details,
                Style::default().fg(if low_battery {
                    palette.low_battery
                } else {
                    palette.foreground
                }),
            ),
            Span::raw(if s.is_system_power() {
                String::new()
            } else {
                format!(
                    "   ETA {eta}{}",
                    if roomy {
                        String::new()
                    } else {
                        format!(" · {} batteries", s.battery_count)
                    }
                )
            }),
        ])];
        if roomy {
            lines.push(Line::styled(
                if s.is_system_power() {
                    " Measured system input power".into()
                } else {
                    format!(
                        " {} batteries                         {} V   {} A",
                        s.battery_count,
                        number(s.volts.filter(|_| !unavailable)),
                        value(s.amps)
                    )
                },
                dim,
            ));
        }
        lines
    } else {
        vec![Line::from(" Waiting for measured power")]
    };
    frame.render_widget(Paragraph::new(kpi), rows[2]);
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
            .style(Style::default().fg(palette.axes))
    }));
    let bounds = v.state.y;
    let ticks = [bounds[0], (bounds[0] + bounds[1]) / 2.0, bounds[1]];
    let chart_rows = Layout::vertical([
        Constraint::Length(u16::from(area.height >= 24)),
        Constraint::Min(0),
    ])
    .split(rows[4]);
    frame.render_widget(
        Paragraph::new(format!(
            " Power / {} W{}   window {:.0}s",
            if v.absolute { "magnitude" } else { "signed" },
            if unavailable { " / historical" } else { "" },
            v.span
        ))
        .style(dim),
        chart_rows[0],
    );
    frame.render_widget(
        Chart::new(datasets)
            .style(palette.style())
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
        chart_rows[1],
    );
    let mut stats = vec![
        stat_line("Window", &v.history.window(), v.absolute, palette),
        stat_line("Session", &v.history.session, v.absolute, palette),
    ];
    if rows[6].height >= 5 {
        stats.push(Line::from(vec![
            Span::styled(" Energy  ", dim),
            Span::raw(format!(
                "net battery out {:.3} Wh · in {:.3} Wh · system {:.3} Wh",
                v.history.session.discharge_wh,
                v.history.session.charge_wh,
                v.history.session.system_wh
            )),
        ]));
    }
    let capacity = capacity_label(v.latest, unavailable);
    let width = area.width.saturating_sub(1) as usize;
    let capacity = if capacity.chars().count() > width {
        format!(
            "{}…",
            capacity
                .chars()
                .take(width.saturating_sub(1))
                .collect::<String>()
        )
    } else {
        capacity
    };
    stats.push(Line::from(format!(" {capacity}")));
    stats.push(Line::from(format!(
        " Program elapsed {} · stats {}",
        elapsed_label(v.program_elapsed_s),
        elapsed_label(v.now - v.state.origin)
    )));
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
                    s.reason.as_deref().unwrap_or(&s.measurement_kind),
                    format_args!(
                        "{}\n{}",
                        s.diagnostics.join("\n"),
                        s.batteries
                            .iter()
                            .map(|b| format!(
                                "Battery {}: {:?}, {} W, {} V, {} A, {}",
                                b.index,
                                b.state,
                                number(b.watts),
                                number(b.volts),
                                number(b.amps),
                                b.reason.as_deref().unwrap_or("available"),
                            ))
                            .collect::<Vec<_>>()
                            .join("\n")
                    )
                )
            },
        );
        frame.render_widget(Clear, popup);
        frame.render_widget(Block::default().style(palette.style()), popup);
        let health = v
            .latest
            .filter(|sample| !sample.is_system_power())
            .filter(|sample| !sample.batteries.is_empty())
            .map_or_else(
                || "Battery health: unavailable".into(),
                |sample| {
                    sample
                        .batteries
                        .iter()
                        .map(|battery| {
                            format!(
                                "Battery {}\n{}\nFull: {} Wh · Remaining: {} Wh",
                                battery.index,
                                battery_health_label(battery, v.stale || sample.transition),
                                number(
                                    battery
                                        .full_charged_wh
                                        .filter(|_| !v.stale && !sample.transition)
                                ),
                                number(
                                    battery
                                        .remaining_wh
                                        .filter(|_| !v.stale && !sample.transition)
                                ),
                            )
                        })
                        .collect::<Vec<_>>()
                        .join("\n")
                },
            );
        let content = format!(
            "Battery health / capacity\n{health}\n\nStatistics / estimates\n{analytics}\nETA: {}\nDrain: {} %/hr estimate\n\nTechnical details\n{diagnostic}\n\nDisplay: {} · window {:.0}s\nSession: since launch/reset; gaps separate epochs.\n{status}\nControls\nspace freeze display (sampling/log/tray continue)\nr reset · e export · l log · a signed/magnitude · s step\n? close · arrows/PgUp/PgDn scroll · Home/End · q quit",
            v.latest.map_or("—".into(), remaining_label),
            number(v.latest.filter(|_| !v.stale).and_then(|s| s.drain_pct_hr)),
            if v.absolute { "magnitude" } else { "signed" },
            v.span,
            analytics = analytics_label(v),
            status = v.status,
        );
        let paragraph = Paragraph::new(content).wrap(Wrap { trim: true });
        let inner_width = popup.width.saturating_sub(2);
        let inner_height = popup.height.saturating_sub(2);
        let maximum = paragraph
            .line_count(inner_width)
            .saturating_sub(inner_height as usize)
            .min(u16::MAX as usize) as u16;
        let scroll = v.state.help_scroll.min(maximum);
        v.state.help_max.set(maximum);
        frame.render_widget(
            paragraph.scroll((scroll, 0)).block(
                Block::default()
                    .title(format!(
                        " Help / diagnostics {}/{} ",
                        scroll + 1,
                        maximum + 1
                    ))
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(palette.border)),
            ),
            popup,
        );
    }
}

fn analytics_label(view: &View<'_>) -> String {
    let summary = if view.paused {
        view.state.frozen_analytics.as_ref()
    } else {
        view.state.analytics.summary.as_ref()
    };
    let Some(summary) = summary.filter(|summary| {
        !view.stale
            && view
                .history
                .samples
                .front()
                .is_some_and(|first| summary.observed_s >= first.elapsed_s)
    }) else {
        return "Estimates unavailable / pending refresh".into();
    };
    format!(
        "Idle power proxy   {} W\nBelow-mean readings {}%\nAverage ETA        {}\n{} readings / {:.0}s · age {:.0}s{}",
        number(summary.idle_w),
        number(summary.below_average_pct),
        summary.eta_s.map_or_else(
            || "unavailable (capacity/history required)".into(),
            elapsed_label
        ),
        summary.count,
        summary.covered_s,
        (view.now - summary.observed_s).max(0.0),
        if view.paused { " (frozen)" } else { "" },
    )
}

pub fn battery_health_label(
    battery: &crate::native_battery::BatteryDiagnostic,
    stale: bool,
) -> String {
    let valid =
        !stale && (battery.available || battery.reason.as_deref() == Some("native_relative_units"));
    let design = battery.designed_capacity;
    let full = battery.full_charged_capacity;
    let design_known = valid && design > 0 && design != u32::MAX;
    let full_known = valid && full > 0 && full != u32::MAX;
    let relative = battery.capabilities & 0x40000000 != 0;
    let capacity = if design_known && !relative {
        format!("{:.1} Wh", design as f64 / 1000.0)
    } else if design_known {
        "unavailable (relative units)".into()
    } else {
        "unavailable".into()
    };
    let health = if design_known && full_known {
        format!(
            "{:.1}% retained capacity{}",
            full as f64 / design as f64 * 100.0,
            if full > design {
                " (reported above design)"
            } else {
                ""
            }
        )
    } else {
        "unavailable".into()
    };
    format!("Design: {capacity} · Health: {health}")
}
pub fn elapsed_label(seconds: f64) -> String {
    let seconds = if seconds.is_finite() {
        seconds.max(0.0) as u64
    } else {
        0
    };
    let clock = format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600 % 24,
        seconds / 60 % 60,
        seconds % 60
    );
    if seconds >= 86400 {
        format!("{}d {clock}", seconds / 86400)
    } else {
        clock
    }
}
pub fn capacity_label(sample: Option<&Sample>, stale: bool) -> String {
    let Some(s) = sample.filter(|s| !s.is_system_power()) else {
        return "Capacity: —".into();
    };
    if s.batteries.is_empty() {
        return "Capacity: —".into();
    }
    s.batteries
        .iter()
        .map(|b| {
            let live = !stale && !s.transition && s.available && b.available;
            let value = |n: Option<f64>| {
                n.filter(|n| live && n.is_finite() && *n >= 0.0)
                    .map_or_else(|| "—".into(), |n| format!("{n:.1}"))
            };
            format!(
                "bat {}: {} / {} Wh",
                b.index,
                value(b.remaining_wh),
                value(b.full_charged_wh)
            )
        })
        .collect::<Vec<_>>()
        .join("; ")
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
