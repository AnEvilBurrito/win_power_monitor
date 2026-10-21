use std::time::{Duration, Instant};
use wpm_cli::{
    model::{History, Sample, State},
    power_meter::{MeterValue, apply, select},
    tray::{Reading, icon_text, tooltip},
};
fn meter(name: &str, mw: f64) -> MeterValue {
    MeterValue {
        instance: name.into(),
        status: 0,
        milliwatts: mw,
    }
}
#[test]
fn mock_counter_collections_selection_and_conversion() {
    let values = [meter("PSU input", 125500.0), meter("CPU", 25000.0)];
    assert!(select(&values, Some("PSU input"), 0).is_err());
    assert!(select(&values, Some("PSU input"), 1).is_err());
    assert_eq!(select(&values, Some("PSU input"), 2), Ok(125.5));
    assert!(select(&values, None, 2).is_err());
    assert!(select(&[meter("_Total", 1000.0)], None, 2).is_err());
    assert!(select(&values, Some("missing"), 2).is_err());
    assert!(select(&[meter("x", 1.0), meter("x", 2.0)], Some("x"), 2).is_err());
    assert_eq!(select(&[meter("x", 0.0)], Some("x"), 2), Ok(0.0));
    for mw in [f64::NAN, f64::INFINITY, -1.0] {
        assert!(select(&[meter("x", mw)], Some("x"), 2).is_err());
    }
    let mut invalid = meter("x", 10.0);
    invalid.status = 0x800007d5;
    assert!(select(&[invalid], Some("x"), 2).is_err());
}
fn system(t: f64, watts: f64) -> Sample {
    let mut s = Sample::empty(t);
    s.battery_present = Some(false);
    apply(&mut s, watts);
    s
}
#[test]
fn desktop_without_battery_exports_tray_and_energy_have_no_charge_semantics() {
    let rate = Duration::from_secs(5);
    let mut h = History::new(Duration::from_secs(300), 60, rate, false);
    h.push(system(0.0, 100.0));
    let s = h.push(system(5.0, 200.0));
    assert_eq!(s.state, State::Unknown);
    assert_eq!(s.eta_s, None);
    assert_eq!(s.pct, None);
    assert!(s.available);
    assert_eq!(h.session.count, 2);
    assert_eq!(h.session.charge_wh, 0.0);
    assert_eq!(h.session.discharge_wh, 0.0);
    assert!((h.session.system_wh - 750.0 / 3600.0).abs() < 1e-10);
    let json = serde_json::to_value(&s).unwrap();
    assert_eq!(json["battery_present"], false);
    assert_eq!(json["watts"], 200.0);
    assert!(json["eta_s"].is_null());
    let now = Instant::now();
    let r = Reading {
        sequence: 1,
        received: now,
        sample: s,
    };
    assert_eq!(icon_text(Some(&r), now, rate), "200");
    assert!(tooltip(Some(&r), now, rate).contains("system_input_power_user_confirmed"));
    h.push(Sample::empty(10.0));
    h.push(system(15.0, 100.0));
    h.push(system(40.0, 100.0));
    assert_eq!(h.session.covered_s, 5.0);
}
#[test]
fn measurement_scope_transition_never_bridges_energy() {
    let rate = Duration::from_secs(5);
    let mut h = History::new(Duration::from_secs(300), 60, rate, true);
    let mut battery = Sample::empty(0.0);
    battery.state = State::Unknown;
    battery.watts = Some(100.0);
    h.push(battery);
    h.push(system(5.0, 100.0));
    assert_eq!(h.session.covered_s, 0.0);
}

#[test]
fn desktop_dashboard_renders_system_scope_without_battery_kpi() {
    use ratatui::{Terminal, backend::TestBackend};
    use wpm_cli::{
        cli::Theme,
        ui::{self, UiState, View},
    };
    let rate = Duration::from_secs(5);
    let mut h = History::new(Duration::from_secs(300), 60, rate, false);
    let s = h.push(system(0.0, 125.5));
    let state = UiState::new(chrono::Utc::now(), 0.0, 300.0);
    let mut terminal = Terminal::new(TestBackend::new(150, 32)).unwrap();
    terminal
        .draw(|frame| {
            ui::draw(
                frame,
                &View {
                    history: &h,
                    latest: Some(&s),
                    paused: false,
                    help: false,
                    absolute: true,
                    step: false,
                    theme: Theme::Default,
                    status: "test",
                    span: 300.0,
                    now: 0.0,
                    stale: false,
                    rate: 5.0,
                    state: &state,
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
    assert!(text.contains("125.50 W system input"));
    assert!(!text.contains("ETA"));
    assert!(!text.contains("Health"));
}
