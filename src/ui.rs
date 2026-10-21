use crate::{
    cli::Theme,
    model::{History, Sample, number},
};
use ratatui::{
    Frame,
    layout::{Constraint, Layout},
    style::{Color, Style},
    symbols,
    text::Line,
    widgets::{Axis, Block, Chart, Dataset, GraphType, Paragraph},
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
        Theme::Default => Color::Cyan,
        Theme::HighContrast => Color::Yellow,
        Theme::Mono => Color::White,
    };
    let rows = Layout::vertical([
        Constraint::Length(2),
        Constraint::Min(2),
        Constraint::Length(3),
        Constraint::Length(2),
    ])
    .split(area);
    let current = v.latest.map_or_else(
        || "Waiting for sampler".into(),
        |s| {
            format!(
                "{:?} | {} W  {} V  {} A  {}% | {} | {}{}",
                s.state,
                number(if v.stale { None } else { s.watts }),
                number(s.volts),
                number(s.amps),
                number(s.pct),
                s.source,
                s.measurement_kind,
                if v.stale { " STALE" } else { "" }
            )
        },
    );
    frame.render_widget(
        Paragraph::new(format!(
            "wpm {} | {current}\n{}",
            if v.paused {
                "PAUSED view (sampling/log/tray LIVE)"
            } else {
                "LIVE"
            },
            v.status
        )),
        rows[0],
    );
    let mut segments: Vec<Vec<(f64, f64)>> = vec![vec![]];
    for s in &v.history.samples {
        if s.gap || s.watts.is_none() {
            segments.push(vec![]);
        }
        if let Some(w) = s.watts {
            let w = if v.absolute { w.abs() } else { w };
            if v.step
                && let Some((_, previous)) = segments.last().and_then(|p| p.last()).copied()
                && let Some(p) = segments.last_mut()
            {
                p.push((s.elapsed_s, previous));
            }
            if let Some(p) = segments.last_mut() {
                p.push((s.elapsed_s, w));
            }
        }
    }
    let bound = segments
        .iter()
        .flatten()
        .map(|(_, w)| w.abs())
        .fold(1.0, f64::max)
        * 1.1;
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
                .style(Style::default().fg(color))
        })
        .collect();
    datasets.push(
        Dataset::default()
            .data(&zero)
            .graph_type(GraphType::Line)
            .style(Style::default().fg(Color::DarkGray)),
    );
    let chart = Chart::new(datasets)
        .block(Block::bordered().title("Battery flow W (NOT wall/system draw); gaps = missing"))
        .x_axis(Axis::default().bounds([start, end]).labels([
            Line::from(format!("{start:.0}s")),
            Line::from(format!("{end:.0}s")),
        ]))
        .y_axis(
            Axis::default()
                .bounds([if v.absolute { 0.0 } else { -bound }, bound])
                .labels([
                    Line::from(if v.absolute {
                        "0".into()
                    } else {
                        format!("-{bound:.0}")
                    }),
                    Line::from("0"),
                    Line::from(format!("{bound:.0}")),
                ]),
        );
    frame.render_widget(chart, rows[1]);
    frame.render_widget(
        Paragraph::new(format!(
            "session {}\nwindow  {}\n{}",
            v.history.session.label(),
            v.history.window().label(),
            v.latest
                .and_then(|s| s.reason.as_deref())
                .unwrap_or("measured battery flow")
        )),
        rows[2],
    );
    frame.render_widget(Paragraph::new(if v.help { "q/Ctrl+C exit | space freeze view only | r reset stats/history | e export window CSV+JSON\nl toggle append log (default wpm.csv) | a abs chart | s step chart | ? help; discharge-only stats unless --all-states" } else { "q quit · space pause view · r reset · e export · l log · a abs · s step · ? help" }), rows[3]);
}
