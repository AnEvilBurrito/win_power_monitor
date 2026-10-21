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
    assert_eq!(icon_text(Some(&r), now, rate), "12.4");
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
