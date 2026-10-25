//! Manual synthetic timings, not a terminal I/O or process-power benchmark.
use chrono::Utc;
use ratatui::{Terminal, backend::TestBackend};
use std::{
    hint::black_box,
    time::{Duration, Instant},
};
use wpm_cli::{
    cli::Theme,
    model::{History, Sample, State},
    ui::{self, PreparedHistory, UiState, View},
};

#[test]
#[ignore = "manual dashboard cost measurement; run in release mode"]
fn dashboard_render_cost() {
    for count in [61, 10_000, 500_000] {
        let rate = Duration::from_secs(5);
        let span = Duration::from_secs(count as u64 * 5);
        let mut history = History::new(span, count, rate, false);
        for index in 0..count {
            let mut sample = Sample::empty(index as f64 * 5.0);
            sample.available = true;
            sample.state = State::Discharging;
            sample.watts = Some(-10.0 - (index % 20) as f64);
            history.push(sample);
        }
        let mut state = UiState::new(Utc::now(), 0.0, span.as_secs_f64());
        let start = Instant::now();
        state.advance(span.as_secs_f64(), span.as_secs_f64(), &history, true);
        let prepared = PreparedHistory::new(&history, true, false);
        eprintln!(
            "{count} samples: {:.3} ms to prepare history + axes",
            start.elapsed().as_secs_f64() * 1000.0
        );
        for cached in [false, true] {
            let mut terminal = Terminal::new(TestBackend::new(120, 32)).unwrap();
            let frames = 20;
            let start = Instant::now();
            for frame in 0..frames {
                let now = span.as_secs_f64() + frame as f64 * 0.1;
                if cached {
                    state.advance_clock(now, span.as_secs_f64(), true);
                } else {
                    state.advance(now, span.as_secs_f64(), &history, true);
                }
                terminal
                    .draw(|frame| {
                        let view = View {
                            history: &history,
                            latest: history.samples.back(),
                            paused: false,
                            help: false,
                            absolute: true,
                            step: false,
                            theme: Theme::Default,
                            status: "benchmark",
                            span: span.as_secs_f64(),
                            now,
                            program_elapsed_s: now,
                            stale: false,
                            rate: rate.as_secs_f64(),
                            state: &state,
                        };
                        if cached {
                            ui::draw_prepared(frame, &view, &prepared);
                        } else {
                            ui::draw(frame, &view);
                        }
                    })
                    .unwrap();
                black_box(terminal.backend().buffer());
            }
            eprintln!(
                "{count} samples: {:.3} ms/frame ({})",
                start.elapsed().as_secs_f64() * 1000.0 / frames as f64,
                if cached { "cached" } else { "uncached" }
            );
        }
    }
}
