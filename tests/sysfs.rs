//! sysfs fixtures. Parsing is platform-neutral, so every test except the
//! directory walk runs on Windows CI as well; the walk is gated because the
//! backend type only exists on Linux.

use wpm_cli::{
    model::{Sample, State},
    native_battery::{Member, apply_members},
    sysfs::{self, Attrs},
};

fn attrs(pairs: &[(&str, &str)]) -> Attrs {
    pairs
        .iter()
        .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
        .collect()
}

fn member(id: &str, pairs: &[(&str, &str)], ac: Option<bool>) -> Member {
    let mut diagnostics = Vec::new();
    sysfs::parse_member(id, &attrs(pairs), ac, &mut diagnostics)
}

fn sample(members: &[Member]) -> Sample {
    let mut s = Sample::empty(0.0);
    apply_members(&mut s, members);
    s
}

const DISCHARGING: &[(&str, &str)] = &[
    ("type", "Battery"),
    ("status", "Discharging"),
    ("power_now", "12000000"),
    ("voltage_now", "12000000"),
    ("energy_now", "42000000"),
    ("energy_full", "48000000"),
];

#[test]
fn power_now_discharge_becomes_signed_milli_units() {
    let s = sample(&[member("BAT0", DISCHARGING, Some(false))]);
    assert_eq!(s.state, State::Discharging);
    assert_eq!(s.watts, Some(-12.0));
    assert_eq!(s.volts, Some(12.0));
    assert_eq!(s.amps, Some(-1.0));
    assert_eq!(s.remaining_wh, Some(42.0));
    assert_eq!(s.pct, Some(87.5));
    assert!(s.available);
    assert_eq!(s.battery_count, 1);
}

#[test]
fn current_times_voltage_replaces_a_missing_power_now() {
    let s = sample(&[member(
        "BAT0",
        &[
            ("type", "Battery"),
            ("status", "Charging"),
            ("current_now", "2000000"),
            ("voltage_now", "12000000"),
        ],
        Some(true),
    )]);
    assert_eq!(s.state, State::Charging);
    assert_eq!(s.watts, Some(24.0));
    assert!(s.available);
}

#[test]
fn charge_only_drivers_convert_through_voltage() {
    let s = sample(&[member(
        "BAT0",
        &[
            ("type", "Battery"),
            ("status", "Discharging"),
            ("current_now", "1000000"),
            ("voltage_now", "12000000"),
            ("charge_now", "4000000"),
            ("charge_full", "5000000"),
        ],
        Some(false),
    )]);
    assert_eq!(s.watts, Some(-12.0));
    assert_eq!(s.remaining_wh, Some(48.0));
    assert_eq!(s.pct, Some(80.0));
}

#[test]
fn idle_status_clamps_reported_flow_to_zero() {
    let mut diagnostics = Vec::new();
    let m = sysfs::parse_member(
        "BAT1",
        &attrs(&[
            ("type", "Battery"),
            ("status", "Full"),
            ("power_now", "250000"),
        ]),
        Some(true),
        &mut diagnostics,
    );
    assert_eq!(m.reading.rate, 0);
    assert!(
        diagnostics
            .iter()
            .any(|line| line.contains("idle status reports"))
    );
    let s = sample(&[m]);
    assert_eq!(s.state, State::Idle);
    assert_eq!(s.watts, Some(0.0));
    assert!(s.available);
}

#[test]
fn unusable_rate_and_capacity_use_the_shared_sentinels() {
    let s = sample(&[member(
        "BAT0",
        &[
            ("type", "Battery"),
            ("status", "Unknown"),
            ("power_now", "5000000"),
        ],
        None,
    )]);
    assert!(!s.available);
    assert_eq!(
        s.reason.as_deref(),
        Some("native_unknown_or_conflicting_rate")
    );
    let s = sample(&[member(
        "BAT0",
        &[("type", "Battery"), ("status", "Discharging")],
        Some(false),
    )]);
    assert!(!s.available);
    assert_eq!(
        s.reason.as_deref(),
        Some("native_unknown_or_conflicting_rate")
    );
    let m = member(
        "BAT0",
        &[("type", "Battery"), ("status", "Discharging")],
        None,
    );
    assert_eq!(m.reading.rate, i32::MIN);
    assert_eq!(m.reading.capacity, u32::MAX);
    assert_eq!(m.reading.voltage, u32::MAX);
}

#[test]
fn peripherals_and_detached_packs_are_not_batteries() {
    assert!(!sysfs::is_battery(&attrs(&[
        ("type", "Mains"),
        ("online", "1")
    ])));
    assert!(!sysfs::is_battery(&attrs(&[
        ("type", "Battery"),
        ("present", "0"),
        ("energy_now", "1000000")
    ])));
    assert!(!sysfs::is_battery(&attrs(&[
        ("type", "Battery"),
        ("scope", "Device"),
        ("energy_now", "1000000")
    ])));
    // A HID peripheral reporting only a percentage has no usable energy data.
    assert!(!sysfs::is_battery(&attrs(&[
        ("type", "Battery"),
        ("capacity", "55")
    ])));
    assert!(sysfs::is_battery(&attrs(&[
        ("type", "Battery"),
        ("status", "Discharging"),
        ("energy_now", "1000000")
    ])));
    assert!(sysfs::is_battery(&attrs(&[
        ("type", "Battery"),
        ("scope", "System"),
        ("power_now", "1000000")
    ])));
}

#[test]
fn ac_percent_and_unit_helpers() {
    assert_eq!(
        sysfs::ac_online(&attrs(&[("type", "Mains"), ("online", "1")])),
        Some(true)
    );
    assert_eq!(
        sysfs::ac_online(&attrs(&[("type", "Mains"), ("online", "0")])),
        Some(false)
    );
    assert_eq!(sysfs::ac_online(&attrs(&[("type", "Mains")])), None);
    assert_eq!(sysfs::ac_online(&attrs(&[("type", "Battery")])), None);
    assert_eq!(sysfs::percent(&attrs(&[("capacity", "87")])), Some(87.0));
    assert_eq!(sysfs::percent(&attrs(&[("capacity", "150")])), None);
    assert_eq!(sysfs::percent(&attrs(&[])), None);
    assert_eq!(
        sysfs::micro(&attrs(&[("energy_now", " 42 ")]), "energy_now"),
        Some(42)
    );
    assert_eq!(
        sysfs::micro(&attrs(&[("energy_now", "n/a")]), "energy_now"),
        None
    );
    assert_eq!(
        sysfs::state(&attrs(&[("status", "Not charging")])),
        State::Idle
    );
    assert_eq!(sysfs::state(&attrs(&[("status", "weird")])), State::Unknown);
}

#[test]
fn ac_state_is_encoded_in_power_state_bits() {
    let mut diagnostics = Vec::new();
    let charging = sysfs::parse_member(
        "BAT0",
        &attrs(&[
            ("type", "Battery"),
            ("status", "Charging"),
            ("power_now", "1000000"),
        ]),
        Some(true),
        &mut diagnostics,
    );
    assert_eq!(charging.reading.power_state, 5);
    let discharging = sysfs::parse_member(
        "BAT0",
        &attrs(&[
            ("type", "Battery"),
            ("status", "Discharging"),
            ("power_now", "1000000"),
        ]),
        Some(false),
        &mut diagnostics,
    );
    assert_eq!(discharging.reading.power_state, 2);
    assert_eq!(charging.reading.capabilities, 0x8000_0000);
}

#[test]
fn two_batteries_aggregate_by_the_shared_rules() {
    let pack = |energy: &'static str, power: &'static str| {
        let mut diagnostics = Vec::new();
        sysfs::parse_member(
            "BAT0",
            &attrs(&[
                ("type", "Battery"),
                ("status", "Discharging"),
                ("power_now", power),
                ("voltage_now", "12000000"),
                ("energy_now", energy),
                ("energy_full", "40000000"),
            ]),
            Some(false),
            &mut diagnostics,
        )
    };
    let s = sample(&[pack("30000000", "10000000"), pack("20000000", "5000000")]);
    assert_eq!(s.battery_count, 2);
    assert_eq!(s.watts, Some(-15.0));
    assert_eq!(s.remaining_wh, Some(50.0));
    assert_eq!(s.pct, Some(62.5));
    // Voltage is never summed or averaged across batteries.
    assert_eq!(s.volts, None);
}

#[cfg(target_os = "linux")]
fn fixture_root(label: &str) -> std::path::PathBuf {
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let root = std::env::temp_dir().join(format!(
        "wpm-sysfs-{}-{label}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).expect("fixture root");
    root
}

#[cfg(target_os = "linux")]
fn write_supply(dir: &std::path::Path, pairs: &[(&str, &str)]) {
    std::fs::create_dir_all(dir).expect("supply directory");
    for (key, value) in pairs {
        std::fs::write(dir.join(key), format!("{value}\n")).expect("supply attribute");
    }
}

#[cfg(target_os = "linux")]
#[test]
fn backend_walks_a_fixture_tree_through_the_collector() {
    use wpm_cli::native_battery::{Backend, Collector};
    use wpm_cli::sysfs::SysfsBackend;

    let root = fixture_root("walk");
    write_supply(&root.join("AC"), &[("type", "Mains"), ("online", "1")]);
    let mut pack = DISCHARGING.to_vec();
    pack.push(("capacity", "87"));
    write_supply(&root.join("BAT0"), &pack);
    // A peripheral pack must not join the collection.
    write_supply(
        &root.join("hidpp_battery_0"),
        &[("type", "Battery"), ("scope", "Device"), ("capacity", "55")],
    );

    let mut backend = SysfsBackend::with_root(&root);
    let mut diagnostics = Vec::new();
    let members = backend.snapshot(&mut diagnostics).expect("snapshot");
    assert_eq!(members.len(), 1);
    assert_eq!(backend.ac_online(), Some(true));
    assert_eq!(backend.percent_hint(), Some(87.0));

    let mut collector = Collector::default();
    let mut first = Sample::empty(0.0);
    collector.collect(&mut backend, &mut first);
    assert_eq!(first.state, State::Discharging);
    assert_eq!(first.watts, Some(-12.0));
    assert_eq!(first.ac_online, Some(true));
    assert!(!first.transition);
    let epoch = first.topology_epoch;

    let mut second = Sample::empty(0.0);
    collector.collect(&mut backend, &mut second);
    assert_eq!(second.topology_epoch, epoch);
    assert!(!second.transition);

    std::fs::remove_dir_all(&root).expect("clean fixture tree");
}

#[cfg(target_os = "linux")]
#[test]
fn binary_reads_a_fixture_root_and_reports_absent_batteries() {
    let run = |root: &std::path::Path, args: &[&str]| {
        std::process::Command::new(env!("CARGO_BIN_EXE_wpm"))
            .args(args)
            .env("WPM_SYSFS_ROOT", root)
            .env_remove("WPM_CONFIG")
            .output()
            .expect("run binary")
    };

    let root = fixture_root("battery");
    write_supply(&root.join("AC"), &[("type", "Mains"), ("online", "0")]);
    write_supply(&root.join("BAT0"), DISCHARGING);
    let output = run(&root, &["--once"]);
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(output.status.success(), "{stdout}");
    assert!(
        stdout.contains("Discharging: 12.00 W measured power; 12.00 V; 87.50%"),
        "{stdout}"
    );
    assert!(stdout.contains("native:sysfs_power_supply"), "{stdout}");

    let empty = fixture_root("desktop");
    write_supply(&empty.join("AC"), &[("type", "Mains"), ("online", "1")]);
    let output = run(&empty, &["--json", "--require-battery"]);
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert_eq!(output.status.code(), Some(3), "{stdout}");
    assert!(stdout.contains("\"battery_present\":false"), "{stdout}");
    assert!(stdout.contains("no_battery_supply"), "{stdout}");

    std::fs::remove_dir_all(&root).expect("clean battery fixture");
    std::fs::remove_dir_all(&empty).expect("clean desktop fixture");
}
