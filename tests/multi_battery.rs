use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};
use wpm_cli::{
    export::csv_row,
    model::{History, Metrics, Sample, State},
    native_battery::{Backend, Collector, Member, Reading, apply_members},
    tray::{self, Reading as TrayReading},
};
fn member(id: &str, rate: i32) -> Member {
    Member {
        id: id.into(),
        tag: 7,
        full_charged_capacity: 50000,
        designed_capacity: 60000,
        default_alert1: 0,
        default_alert2: 0,
        critical_bias: 0,
        cycle_count: 10,
        technology: 1,
        chemistry: *b"LION",
        reading: Reading {
            capabilities: 0x80000000,
            power_state: if rate > 0 {
                4
            } else if rate < 0 {
                2
            } else {
                0
            },
            capacity: 25000,
            voltage: 12500,
            rate,
        },
    }
}
struct Script {
    steps: VecDeque<Result<Vec<Member>, String>>,
    calls: usize,
}
impl Script {
    fn new(steps: Vec<Result<Vec<Member>, String>>) -> Self {
        Self {
            steps: steps.into(),
            calls: 0,
        }
    }
    fn stable(rows: &[Member]) -> Self {
        Self::new(vec![Ok(rows.to_vec()); 3])
    }
}
impl Backend for Script {
    fn snapshot(&mut self, _: &mut Vec<String>) -> Result<Vec<Member>, String> {
        self.calls += 1;
        self.steps.pop_front().expect("bounded scripted calls")
    }
    fn now_s(&self) -> f64 {
        self.calls as f64
    }
}
fn collect(c: &mut Collector, script: &mut impl Backend, t: f64) -> Sample {
    let mut s = Sample::empty(t);
    c.collect(script, &mut s);
    s
}
#[test]
fn aggregation_matrix() {
    for (a, b, w, state) in [
        (-10000, -5000, -15., State::Discharging),
        (10000, 5000, 15., State::Charging),
        (-10000, 0, -10., State::Discharging),
        (10000, -5000, 5., State::Mixed),
        (10000, -10000, 0., State::Mixed),
        (0, 0, 0., State::Idle),
    ] {
        let rows = vec![member("a", a), member("b", b)];
        let s = collect(&mut Collector::default(), &mut Script::stable(&rows), 0.);
        assert_eq!(s.watts, Some(w));
        assert_eq!(s.state, state);
        assert_eq!(s.pct, Some(50.));
        assert_eq!(s.remaining_wh, Some(50.));
        assert_eq!(s.volts, None);
        assert_eq!(s.amps, None);
        assert_eq!(s.battery_count, 2);
    }
}
#[test]
fn independent_capacity_and_invalid_rate_matrix() {
    for mode in 0..6 {
        let mut rows = vec![member("a", -10000), member("b", -5000)];
        match mode {
            0 => rows[1].reading.capacity = u32::MAX,
            1 => rows[1].full_charged_capacity = 0,
            2 => rows[1].reading.capacity = 60000,
            3 => rows[1].reading.rate = i32::MIN,
            4 => rows[1].reading.power_state = 6,
            _ => rows[1].reading.capabilities |= 0x40000000,
        }
        let s = collect(&mut Collector::default(), &mut Script::stable(&rows), 0.);
        if mode < 3 {
            assert_eq!(s.watts, Some(-15.));
            assert_eq!(s.pct, None);
        } else {
            assert_eq!(s.watts, None);
            assert!(s.transition);
        }
        if mode == 0 || mode == 5 {
            assert_eq!(s.remaining_wh, None);
        }
    }
}
#[test]
fn capacity_weighting_and_per_device_tags() {
    let mut a = member("a", -1000);
    a.reading.capacity = 10000;
    a.full_charged_capacity = 20000;
    let mut b = member("b", -2000);
    b.reading.capacity = 60000;
    b.full_charged_capacity = 80000;
    let mut s = Sample::empty(0.);
    apply_members(&mut s, &[a, b]);
    assert_eq!(s.pct, Some(70.));
    assert_eq!(s.watts, Some(-3.));
    assert_eq!(s.batteries[0].tag, s.batteries[1].tag);
}
#[test]
fn dedup_and_reorder_do_not_change_epoch() {
    let a = member("a", -10000);
    let b = member("b", -5000);
    let mut c = Collector::default();
    let mut script = Script::new(vec![
        Ok(vec![a.clone(), b.clone(), a.clone()]),
        Ok(vec![b.clone(), a.clone()]),
        Ok(vec![b.clone(), a.clone(), b.clone()]),
    ]);
    let s = collect(&mut c, &mut script, 0.);
    assert_eq!(s.watts, Some(-15.));
    assert_eq!(s.topology_epoch, 0);
    let s = collect(&mut c, &mut Script::stable(&[b, a]), 5.);
    assert!(!s.transition);
    assert_eq!(s.topology_epoch, 0);
}
#[test]
fn removal_attachment_tag_replacement_at_every_phase() {
    let old = vec![member("a", -10000), member("b", -5000)];
    for change in 0..4 {
        for phase in 0..3 {
            let mut changed = old.clone();
            match change {
                0 => {
                    changed.pop();
                }
                1 => changed.push(member("c", -3000)),
                2 => changed[1].tag += 1,
                _ => changed[1].id = "replacement".into(),
            }
            let mut c = Collector::default();
            collect(&mut c, &mut Script::stable(&old), 0.);
            let mut steps = vec![Ok(old.clone()); 3];
            steps[phase] = Ok(changed.clone());
            let mut script = Script::new(steps);
            let bad = collect(&mut c, &mut script, 5.);
            assert_eq!(script.calls, 3);
            assert_eq!(bad.watts, None);
            assert!(bad.transition);
            assert_eq!(bad.reason.as_deref(), Some("native_topology_transition"));
            let first = collect(&mut c, &mut Script::stable(&changed), 10.);
            assert!(first.transition);
            assert_eq!(first.topology_epoch, 1);
            assert_eq!(first.watts, None);
            let good = collect(&mut c, &mut Script::stable(&changed), 15.);
            assert!(good.available);
            assert!(!good.transition);
            assert_eq!(good.topology_epoch, 1);
        }
    }
}
#[test]
fn failure_at_every_phase_never_reuses_values_and_recovers() {
    let rows = vec![member("a", -10000), member("b", -5000)];
    for phase in 0..3 {
        let mut c = Collector::default();
        collect(&mut c, &mut Script::stable(&rows), 0.);
        let mut steps = vec![Ok(rows.clone()); 3];
        steps[phase] = Err("scripted member/final discovery failure".into());
        let mut script = Script::new(steps);
        let s = collect(&mut c, &mut script, 5.);
        assert_eq!(script.calls, phase + 1);
        assert_eq!(s.watts, None);
        assert!(s.transition);
        assert!(collect(&mut c, &mut Script::stable(&rows), 10.).transition);
        assert_eq!(
            collect(&mut c, &mut Script::stable(&rows), 15.).watts,
            Some(-15.)
        );
    }
}
#[test]
fn charger_changes_and_conflicting_ac_are_conservative_but_mixed_is_valid() {
    let rows = vec![member("a", 10000), member("b", -5000)];
    let mut c = Collector::default();
    assert_eq!(
        collect(&mut c, &mut Script::stable(&rows), 0.).state,
        State::Mixed
    );
    let mut ac = rows.clone();
    for m in &mut ac {
        m.reading.power_state |= 1;
    }
    let s = collect(&mut c, &mut Script::stable(&ac), 5.);
    assert!(s.transition);
    assert_eq!(s.watts, None);
    assert_eq!(
        collect(&mut c, &mut Script::stable(&ac), 10.).state,
        State::Mixed
    );
    for phase in 0..3 {
        let mut steps = vec![Ok(rows.clone()); 3];
        steps[phase] = Ok(ac.clone());
        let s = collect(&mut Collector::default(), &mut Script::new(steps), 0.);
        assert_eq!(s.reason.as_deref(), Some("native_charger_transition"));
    }
    ac[0].reading.power_state &= !1;
    assert_eq!(
        collect(&mut c, &mut Script::stable(&ac), 15.)
            .reason
            .as_deref(),
        Some("native_conflicting_ac")
    );
}
#[test]
fn topology_and_charger_simultaneous_and_rapid_repeated_changes() {
    let mut c = Collector::default();
    let mut rows = vec![member("a", -10000)];
    collect(&mut c, &mut Script::stable(&rows), 0.);
    for i in 1..6 {
        rows[0].tag += 1;
        rows[0].reading.power_state ^= 1;
        let s = collect(&mut c, &mut Script::stable(&rows), i as f64 * 5.);
        assert!(s.transition);
        assert_eq!(s.watts, None);
        assert_eq!(s.topology_epoch, i);
    }
    assert!(collect(&mut c, &mut Script::stable(&rows), 35.).available);
}
#[test]
fn metrics_history_breaks_estimator_and_energy_epochs_and_invalid_samples() {
    let rate = Duration::from_secs(5);
    let mut h = History::new(Duration::from_secs(300), 100, rate, true);
    let mut metrics = Metrics::new(rate);
    let mut c = Collector::default();
    let mut rows = vec![member("a", -10000)];
    for i in 0..3 {
        rows[0].reading.capacity -= 100;
        let mut s = collect(&mut c, &mut Script::stable(&rows), i as f64 * 5.);
        metrics.apply(&mut s);
        h.push(s);
    }
    assert!(h.samples.back().unwrap().drain_pct_hr.is_some());
    let wh = h.session.discharge_wh;
    rows.push(member("b", -5000));
    let mut s = collect(&mut c, &mut Script::stable(&rows), 15.);
    metrics.apply(&mut s);
    assert_eq!(s.drain_pct_hr, None);
    assert_eq!(s.eta_s, None);
    h.push(s);
    let mut s = collect(&mut c, &mut Script::stable(&rows), 20.);
    metrics.apply(&mut s);
    assert_eq!(s.drain_pct_hr, None);
    assert_eq!(s.eta_s, None);
    h.push(s);
    assert_eq!(h.session.discharge_wh, wh);
    let mut s = collect(&mut c, &mut Script::stable(&rows), 25.);
    metrics.apply(&mut s);
    h.push(s);
    assert!(h.session.discharge_wh > wh);
    let frozen = h.clone();
    let mut s = Sample::empty(30.);
    s.transition = true;
    h.push(s);
    assert_ne!(frozen.samples.len(), h.samples.len());
    assert_eq!(frozen.session.discharge_wh, h.session.discharge_wh);
}
#[test]
fn mixed_net_energy_scope_and_exports_tray() {
    let mut h = History::new(Duration::from_secs(60), 10, Duration::from_secs(5), true);
    for t in [0., 5.] {
        let s = collect(
            &mut Collector::default(),
            &mut Script::stable(&[member("a", 10000), member("b", -15000)]),
            t,
        );
        h.push(s);
    }
    assert!((h.session.discharge_wh - 25. / 3600.).abs() < 1e-10);
    assert_eq!(h.session.charge_wh, 0.);
    assert_eq!(h.session.system_wh, 0.);
    let s = h.samples.back().unwrap();
    assert!(csv_row(s).contains("Mixed,-5.0000,,,50.0000"));
    let json = serde_json::to_value(s).unwrap();
    assert_eq!(json["batteries"].as_array().unwrap().len(), 2);
    assert_eq!(json["collection_end_s"], 3.);
    let now = Instant::now();
    let mut r = TrayReading {
        sequence: 1,
        received: now,
        sample: s.clone(),
    };
    r.sample.transition = true;
    assert_eq!(tray::icon_text(Some(&r), now, Duration::from_secs(5)), "—");
    assert!(tray::tooltip(Some(&r), now, Duration::from_secs(5)).contains("TRANSITION"));
}
#[test]
fn synthetic_multi_dashboard_small_large_and_transition() {
    use ratatui::{Terminal, backend::TestBackend};
    use wpm_cli::{
        cli::Theme,
        ui::{self, UiState, View},
    };
    for transition in [false, true] {
        let mut s = collect(
            &mut Collector::default(),
            &mut Script::stable(&[member("a", 10000), member("b", -5000)]),
            0.,
        );
        s.transition = transition;
        if transition {
            s.watts = None;
        }
        let mut h = History::new(Duration::from_secs(300), 60, Duration::from_secs(5), true);
        let s = h.push(s);
        for width in [60, 120] {
            let mut term = Terminal::new(TestBackend::new(width, 24)).unwrap();
            term.draw(|f| {
                ui::draw(
                    f,
                    &View {
                        history: &h,
                        latest: Some(&s),
                        paused: false,
                        help: false,
                        absolute: false,
                        step: false,
                        theme: Theme::Mono,
                        status: "",
                        span: 300.,
                        now: 0.,
                        program_elapsed_s: 0.0,
                        stale: false,
                        rate: 5.,
                        state: &UiState::new(chrono::Utc::now(), 0., 300.),
                    },
                )
            })
            .unwrap();
            let text = term
                .backend()
                .buffer()
                .content
                .iter()
                .map(|c| c.symbol())
                .collect::<String>();
            assert!(text.contains("2 batteries"));
            assert!(text.contains(if transition { "TRANSITION" } else { "Mixed" }));
        }
    }
}

#[test]
fn duplicate_conflicts_invalid_identity_and_invalid_boundary_reads() {
    let a = member("a", -10000);
    for mode in 0..4 {
        let mut b = a.clone();
        match mode {
            0 => b.tag += 1,
            1 => b.reading.rate = -20000,
            2 => b.id.clear(),
            _ => b.tag = 0,
        }
        let bad = collect(
            &mut Collector::default(),
            &mut Script::stable(&[a.clone(), b]),
            0.,
        );
        assert!(bad.transition);
        assert_eq!(bad.watts, None);
    }
    for phase in [0, 2] {
        let mut bad = a.clone();
        bad.reading.rate = i32::MIN;
        let mut steps = vec![Ok(vec![a.clone()]); 3];
        steps[phase] = Ok(vec![bad]);
        assert_eq!(
            collect(&mut Collector::default(), &mut Script::new(steps), 0.).watts,
            None
        );
    }
}
#[test]
fn invalid_collection_breaks_integration_and_fresh_recovery_estimates() {
    let rate = Duration::from_secs(5);
    let mut metrics = Metrics::new(rate);
    let mut history = History::new(Duration::from_secs(300), 60, rate, true);
    let rows = vec![member("a", -10000)];
    let mut c = Collector::default();
    for t in [0., 5.] {
        let mut s = collect(&mut c, &mut Script::stable(&rows), t);
        metrics.apply(&mut s);
        history.push(s);
    }
    let wh = history.session.discharge_wh;
    let mut s = collect(
        &mut c,
        &mut Script::new(vec![Err("read failure".into())]),
        10.,
    );
    metrics.apply(&mut s);
    history.push(s);
    for t in [15., 20.] {
        let mut s = collect(&mut c, &mut Script::stable(&rows), t);
        metrics.apply(&mut s);
        assert_eq!(s.drain_pct_hr, None);
        assert_eq!(s.eta_s, None);
        history.push(s);
    }
    assert_eq!(history.session.discharge_wh, wh);
    let mut s = collect(&mut c, &mut Script::stable(&rows), 25.);
    metrics.apply(&mut s);
    assert!(s.eta_s.is_some());
    history.push(s);
    assert!(history.session.discharge_wh > wh);
}
#[test]
fn empty_set_and_net_mixed_excluded_by_default() {
    let mut c = Collector::default();
    let s = collect(&mut c, &mut Script::stable(&[]), 0.);
    assert!(!s.available);
    assert_eq!(s.battery_count, 0);
    let mut h = History::new(Duration::from_secs(60), 10, Duration::from_secs(5), false);
    for t in [0., 5.] {
        h.push(collect(
            &mut c,
            &mut Script::stable(&[member("a", 10000), member("b", -15000)]),
            t,
        ));
    }
    assert_eq!(h.session.covered_s, 0.);
    assert_eq!(h.session.count, 0);
}

#[test]
fn injected_resource_ownership_cleanup_on_success_failure_and_recovery() {
    use std::{cell::Cell, rc::Rc};
    struct Lease(Rc<Cell<usize>>);
    impl Drop for Lease {
        fn drop(&mut self) {
            self.0.set(self.0.get() - 1);
        }
    }
    struct OwnedBackend {
        script: Script,
        opened: Rc<Cell<usize>>,
    }
    impl Backend for OwnedBackend {
        fn snapshot(&mut self, d: &mut Vec<String>) -> Result<Vec<Member>, String> {
            self.opened.set(self.opened.get() + 1);
            let _enumeration = Lease(self.opened.clone());
            self.opened.set(self.opened.get() + 1);
            let _device = Lease(self.opened.clone());
            self.script.snapshot(d)
        }
        fn now_s(&self) -> f64 {
            self.script.now_s()
        }
    }
    let rows = vec![member("a", -10000)];
    let mut c = Collector::default();
    for phase in 0..4 {
        let count = Rc::new(Cell::new(0));
        let mut steps = vec![Ok(rows.clone()); 3];
        if phase < 3 {
            steps[phase] = Err("removal".into());
        }
        let mut backend = OwnedBackend {
            script: Script::new(steps),
            opened: count.clone(),
        };
        let s = collect(&mut c, &mut backend, phase as f64 * 5.);
        assert_eq!(count.get(), 0);
        assert!(backend.script.calls <= 3);
        if phase < 3 {
            assert_eq!(s.watts, None);
        }
    }
    assert!(collect(&mut c, &mut Script::stable(&rows), 25.).available);
}

#[test]
fn capacity_fields_json_units_and_stable_session_ordinals() {
    use wpm_cli::ui::capacity_label;
    let mut a = member("a", -1000);
    a.reading.capacity = 18000;
    a.full_charged_capacity = 23000;
    let mut b = member("b", -2000);
    b.reading.capacity = 30000;
    b.full_charged_capacity = 45000;
    let mut c = Collector::default();
    let s = collect(&mut c, &mut Script::stable(&[b.clone(), a.clone()]), 0.);
    assert_eq!(
        capacity_label(Some(&s), false),
        "bat 1: 18.0 / 23.0 Wh; bat 2: 30.0 / 45.0 Wh"
    );
    assert_eq!(s.pct, Some(4800. / 68.));
    let json = serde_json::to_value(&s).unwrap();
    assert_eq!(json["batteries"][0]["index"], 1);
    assert_eq!(json["batteries"][0]["full_charged_wh"], 23.);
    assert_eq!(json["batteries"][0]["remaining_wh"], 18.);
    assert!(capacity_label(Some(&s), true).contains("bat 1: — / — Wh"));
    let reordered = collect(&mut c, &mut Script::stable(&[a.clone(), b.clone()]), 1.);
    assert_eq!(
        capacity_label(Some(&s), false),
        capacity_label(Some(&reordered), false)
    );
    let transition = collect(&mut c, &mut Script::stable(&[b.clone()]), 2.);
    assert!(
        transition
            .batteries
            .iter()
            .all(|b| b.remaining_wh.is_none() && b.full_charged_wh.is_none())
    );
    let single = collect(&mut c, &mut Script::stable(&[b.clone()]), 3.);
    assert_eq!(
        capacity_label(Some(&single), false),
        "bat 2: 30.0 / 45.0 Wh"
    );
    a.tag += 1;
    collect(&mut c, &mut Script::stable(&[a.clone(), b.clone()]), 4.);
    let returned = collect(&mut c, &mut Script::stable(&[a, b]), 5.);
    assert_eq!(
        returned
            .batteries
            .iter()
            .map(|b| b.index)
            .collect::<Vec<_>>(),
        vec![1, 2]
    );
    let failed = collect(&mut c, &mut Script::new(vec![Err("failed".into())]), 6.);
    assert_eq!(capacity_label(Some(&failed), false), "Capacity: —");
}
#[test]
fn capacity_invalid_unknown_relative_and_zero() {
    for (capacity, full, relative, expected) in [
        (0, 23000, false, "bat 1: 0.0 / 23.0 Wh"),
        (u32::MAX, 23000, false, "bat 1: — / 23.0 Wh"),
        (18000, 0, false, "bat 1: 18.0 / — Wh"),
        (18000, u32::MAX, false, "bat 1: 18.0 / — Wh"),
        (24000, 23000, false, "bat 1: — / — Wh"),
        (18000, 23000, true, "bat 1: — / — Wh"),
    ] {
        let mut m = member("a", -1000);
        m.reading.capacity = capacity;
        m.full_charged_capacity = full;
        if relative {
            m.reading.capabilities |= 0x40000000;
        }
        let s = collect(&mut Collector::default(), &mut Script::stable(&[m]), 0.);
        assert_eq!(wpm_cli::ui::capacity_label(Some(&s), false), expected);
        if !relative {
            assert_eq!(s.watts, Some(-1.));
        }
    }
}
