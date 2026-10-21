use std::time::{Duration, Instant};
use wpm_cli::{
    export::{HEADER, csv_row},
    model::{History, Sample, State},
    provider::{Battery, apply_batteries},
    tray::{Reading, icon_text, tooltip},
};
fn sample(t: f64, w: Option<f64>) -> Sample {
    let mut s = Sample::empty(t);
    s.ts = "2026-10-21T00:00:00.000Z".into();
    s.watts = w;
    s.state = State::Discharging;
    s
}
#[test]
fn provider_conversion_and_unknowns() {
    let mut s = sample(0.0, None);
    apply_batteries(
        &mut s,
        &[Battery {
            active: Some(true),
            charging: Some(false),
            discharging: Some(true),
            discharge_rate: Some(12400),
            voltage: Some(15500),
            ..Default::default()
        }],
    );
    assert_eq!(s.watts, Some(-12.4));
    assert_eq!(s.volts, Some(15.5));
    assert_eq!(s.amps, Some(-0.8));
    assert_eq!(s.measurement_kind, "battery_flow");
    assert!(s.available);
    let mut unknown = sample(0.0, None);
    apply_batteries(
        &mut unknown,
        &[Battery {
            charging: Some(false),
            discharging: Some(true),
            discharge_rate: Some(u32::MAX),
            voltage: Some(0),
            ..Default::default()
        }],
    );
    assert_eq!(unknown.watts, None);
    assert_eq!(unknown.volts, None);
    assert_eq!(unknown.amps, None);
    let mut idle = sample(0.0, None);
    apply_batteries(
        &mut idle,
        &[Battery {
            power_online: Some(true),
            charging: Some(false),
            discharging: Some(false),
            ..Default::default()
        }],
    );
    assert_eq!(idle.watts, None);
    apply_batteries(
        &mut idle,
        &[Battery {
            charging: Some(false),
            discharging: Some(false),
            charge_rate: Some(0),
            discharge_rate: Some(0),
            ..Default::default()
        }],
    );
    assert_eq!(idle.watts, Some(0.0));
}
#[test]
fn conflicting_and_multiple_batteries_are_not_guessed() {
    for batteries in [
        vec![Battery {
            charging: Some(true),
            discharging: Some(true),
            charge_rate: Some(10000),
            ..Default::default()
        }],
        vec![Battery::default(), Battery::default()],
    ] {
        let mut s = sample(0.0, None);
        apply_batteries(&mut s, &batteries);
        assert_eq!(s.watts, None);
        assert!(!s.available);
    }
}
#[test]
fn age_eviction_gaps_missing_and_scoped_energy() {
    let rate = Duration::from_secs(2);
    let mut h = History::new(Duration::from_secs(10), 6, rate, false);
    h.push(sample(0.0, Some(-10.0)));
    h.push(sample(2.0, Some(-20.0)));
    assert!((h.session.discharge_wh - 30.0 / 3600.0).abs() < 1e-10);
    h.push(sample(4.0, None));
    h.push(sample(6.0, Some(-10.0)));
    let gap = h.push(sample(20.0, Some(-100.0)));
    assert!(gap.gap);
    assert_eq!(h.samples.len(), 1);
    assert_eq!(h.window().covered_s, 0.0);
    assert_eq!(h.session.covered_s, 2.0);
    let mut charging = sample(22.0, Some(20.0));
    charging.state = State::Charging;
    h.push(charging);
    assert_eq!(h.session.count, 4);
    assert_eq!(h.session.charge_wh, 0.0);
    h.reset();
    assert_eq!(h.session.count, 0);
    assert!(h.samples.is_empty());
}
#[test]
fn all_states_integrates_charge_separately_without_cross_state_bridge() {
    let mut h = History::new(Duration::from_secs(30), 15, Duration::from_secs(2), true);
    for t in [0.0, 2.0] {
        let mut s = sample(t, Some(18.0));
        s.state = State::Charging;
        h.push(s);
    }
    h.push(sample(4.0, Some(-18.0)));
    assert!((h.session.charge_wh - 0.01).abs() < 1e-10);
    assert_eq!(h.session.discharge_wh, 0.0);
}
#[test]
fn json_and_csv_preserve_null_and_sign_metadata() {
    let s = sample(0.0, None);
    let json = serde_json::to_value(&s).expect("serialize");
    assert!(json["watts"].is_null());
    assert_eq!(json["measurement_kind"], "unavailable");
    assert_eq!(HEADER, "Time,State,Watts,Volts,Amps,BatteryPercent\n");
    assert_eq!(csv_row(&s), "2026-10-21T00:00:00.000Z,Discharging,,,,\n");
    assert!(csv_row(&sample(0.0, Some(-12.4))).contains(",-12.4000,"));
}
#[test]
fn tray_formatting_is_pure_and_stale_never_shows_live_number() {
    let now = Instant::now();
    let r = Reading {
        sequence: 1,
        received: now,
        sample: sample(0.0, Some(-12.4)),
    };
    let rate = Duration::from_secs(2);
    assert_eq!(icon_text(Some(&r), now, rate), "12");
    assert_eq!(icon_text(Some(&r), now + Duration::from_secs(5), rate), "—");
    assert!(tooltip(Some(&r), now + Duration::from_secs(5), rate).contains("STALE"));
    assert_eq!(icon_text(None, now, rate), "—");
}
#[test]
fn synthetic_dashboard_renders_small_and_large_without_io() {
    use ratatui::{Terminal, backend::TestBackend};
    use wpm_cli::{
        cli::Theme,
        ui::{View, draw},
    };
    let mut h = History::new(Duration::from_secs(300), 150, Duration::from_secs(2), false);
    let s = h.push(sample(0.0, Some(-12.4)));
    for (w, height) in [(120, 32), (100, 24), (80, 14), (60, 14), (20, 5), (1, 1)] {
        let mut terminal = Terminal::new(TestBackend::new(w, height)).expect("backend");
        terminal
            .draw(|frame| {
                draw(
                    frame,
                    &View {
                        history: &h,
                        latest: Some(&s),
                        paused: false,
                        help: true,
                        absolute: false,
                        step: true,
                        theme: Theme::Mono,
                        status: "synthetic",
                        span: 300.0,
                        now: 2.0,
                        stale: false,
                    },
                )
            })
            .expect("render");
    }
}

#[test]
fn nice_axis_ranges_and_ticks() {
    use wpm_cli::ui::nice_y_bounds;
    for (values, absolute, expected) in [
        (vec![], true, [0.0, 10.0]),
        (vec![0.0], true, [0.0, 10.0]),
        (vec![-27.1], true, [0.0, 50.0]),
        (vec![-12.4], false, [-20.0, 0.0]),
        (vec![-12.4, 27.1], false, [-20.0, 50.0]),
        (vec![f64::NAN, f64::INFINITY], true, [0.0, 10.0]),
    ] {
        assert_eq!(nice_y_bounds(values.into_iter(), absolute), expected);
    }
}
#[test]
fn estimates_regression_resets_and_completion_timing() {
    use wpm_cli::model::{Metrics, stamp_completion};
    let mut metrics = Metrics::new(Duration::from_secs(5));
    for (t, p) in [(0.0, 80.0), (5.0, 79.95), (10.0, 79.9)] {
        let mut s = sample(t, Some(-10.0));
        s.pct = Some(p);
        metrics.apply(&mut s);
        if t < 10.0 {
            assert_eq!(s.drain_pct_hr, None);
        } else {
            assert!((s.drain_pct_hr.unwrap() - 36.0).abs() < 1e-8);
            assert_eq!(s.eta_source.as_deref(), Some("percentage trend estimate"));
        }
    }
    let mut gap = sample(30.0, Some(-10.0));
    gap.pct = Some(79.0);
    metrics.apply(&mut gap);
    assert!(gap.gap);
    assert_eq!(gap.drain_pct_hr, None);
    assert_eq!(gap.eta_s, None);
    let mut capacity = sample(35.0, Some(-10.0));
    capacity.remaining_wh = Some(20.0);
    metrics.apply(&mut capacity);
    assert_eq!(capacity.eta_s, Some(7200));
    assert_eq!(
        capacity.eta_source.as_deref(),
        Some("capacity/rate estimate")
    );
    capacity.state = State::Charging;
    capacity.elapsed_s = 40.0;
    metrics.apply(&mut capacity);
    assert_eq!(capacity.eta_s, None);
    assert_eq!(capacity.drain_pct_hr, None);
    stamp_completion(&mut capacity, 42.75, "completion".into(), 2750.0);
    assert_eq!(capacity.elapsed_s, 42.75);
    assert_eq!(capacity.ts, "completion");
    assert_eq!(capacity.query_ms, 2750.0);
}
#[test]
fn unchanged_polls_are_counted_not_marked_stale_and_flat_percent_is_unknown() {
    use wpm_cli::model::Metrics;
    let mut metrics = Metrics::new(Duration::from_secs(2));
    for i in 0..8 {
        let mut s = sample(i as f64 * 2.0, Some(-10.0));
        s.pct = Some(80.0);
        metrics.apply(&mut s);
        assert_eq!(s.poll_count, i + 1);
        assert_eq!(s.unchanged_polls, i);
        assert!(!s.gap);
        assert_eq!(s.drain_pct_hr, None);
        assert_eq!(s.eta_s, None);
    }
}
#[test]
fn kernel_runtime_and_capacity_sentinels() {
    use wpm_cli::{model::Metrics, provider::apply_kernel_eta};
    let mut s = sample(0.0, None);
    apply_kernel_eta(&mut s, 3600);
    assert_eq!(s.eta_s, Some(3600));
    assert_eq!(s.eta_source.as_deref(), Some("Windows reported"));
    s.remaining_wh = Some(20.0);
    s.watts = Some(-10.0);
    Metrics::new(Duration::from_secs(2)).apply(&mut s);
    assert_eq!(s.eta_s, Some(3600));
    apply_kernel_eta(&mut s, u32::MAX);
    assert_eq!(s.eta_s, None);
    s.state = State::Charging;
    apply_kernel_eta(&mut s, 100);
    assert_eq!(s.eta_s, None);
    for (capacity, expected) in [(u32::MAX, None), (0, Some(0.0)), (25000, Some(25.0))] {
        let mut s = sample(0.0, None);
        apply_batteries(
            &mut s,
            &[Battery {
                remaining_capacity: Some(capacity),
                ..Default::default()
            }],
        );
        assert_eq!(s.remaining_wh, expected);
    }
}
#[test]
fn tray_integer_boundaries_tooltip_and_unknowns() {
    let now = Instant::now();
    let rate = Duration::from_secs(2);
    for (watts, text) in [
        (0.0, "0"),
        (-27.1, "27"),
        (99.9, "100"),
        (999.4, "999"),
        (999.5, "HI"),
        (f64::NAN, "—"),
    ] {
        let r = Reading {
            sequence: 1,
            received: now,
            sample: sample(0.0, Some(watts)),
        };
        assert_eq!(icon_text(Some(&r), now, rate), text);
        if watts == -27.1 {
            assert!(tooltip(Some(&r), now, rate).contains("-27.10 W"));
        }
    }
}
#[test]
fn deadline_cadence_and_overrun_are_deterministic() {
    use wpm_cli::app::next_deadline;
    let start = Instant::now();
    let rate = Duration::from_secs(2);
    assert_eq!(
        next_deadline(start, start + Duration::from_millis(250), rate),
        start + rate
    );
    assert_eq!(
        next_deadline(start, start + Duration::from_secs(3), rate),
        start + Duration::from_secs(5)
    );
}
#[test]
fn rendered_magnitude_axis_and_poll_labels_are_truthful() {
    use ratatui::{Terminal, backend::TestBackend};
    use wpm_cli::{
        cli::Theme,
        ui::{View, draw},
    };
    let mut h = History::new(Duration::from_secs(300), 150, Duration::from_secs(2), false);
    let mut s = sample(0.0, Some(-27.1));
    s.poll_count = 12;
    s.eta_s = Some(7200);
    s.eta_source = Some("capacity/rate estimate".into());
    let s = h.push(s);
    let mut terminal = Terminal::new(TestBackend::new(150, 32)).unwrap();
    terminal
        .draw(|f| {
            draw(
                f,
                &View {
                    history: &h,
                    latest: Some(&s),
                    paused: false,
                    help: false,
                    absolute: true,
                    step: false,
                    theme: Theme::Mono,
                    status: "2s",
                    span: 300.0,
                    now: 2.0,
                    stale: false,
                },
            )
        })
        .unwrap();
    let buffer = terminal.backend().buffer();
    let text = (0..32)
        .map(|y| {
            (0..150)
                .map(|x| buffer[(x, y)].symbol())
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n");
    assert!(text.contains("27.10 W"));
    assert!(!text.contains("-27.10"));
    for label in [
        "50",
        "25",
        "remaining 2h 00m (capacity/rate estimate)",
        "poll #12",
        "drain",
    ] {
        assert!(text.contains(label), "{label}");
    }
    assert!(!text.contains('┌'));
}
