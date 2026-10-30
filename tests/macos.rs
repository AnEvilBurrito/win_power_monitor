use plist::{Dictionary, Value};
use wpm_cli::{macos, model::Sample, native_battery::apply_members};

fn battery(current: i64, charging: bool) -> Dictionary {
    let mut properties = Dictionary::new();
    for (key, value) in [
        ("IORegistryEntryID", 123),
        ("Voltage", 12000),
        ("Amperage", current),
        ("CurrentCapacity", 75),
        ("MaxCapacity", 100),
    ] {
        properties.insert(key.into(), Value::Integer(value.into()));
    }
    properties.insert("IsCharging".into(), Value::Boolean(charging));
    properties.insert("ExternalConnected".into(), Value::Boolean(charging));
    properties
}

fn sample(properties: &Dictionary) -> Sample {
    let mut sample = Sample::empty(0.0);
    apply_members(
        &mut sample,
        &[macos::parse_member(properties).unwrap().unwrap()],
    );
    sample
}

#[test]
fn signed_flow_and_capacity_units() {
    for (current, charging, watts) in [(-1000, false, -12.0), (1000, true, 12.0), (0, false, 0.0)] {
        let properties = battery(current, charging);
        let sample = sample(&properties);
        assert_eq!(sample.watts, Some(watts));
        assert_eq!(sample.volts, Some(12.0));
        assert_eq!(sample.remaining_wh, None);
        assert_eq!(sample.batteries[0].full_charged_wh, None);
        assert_eq!(macos::percent(&properties), Some(75.0));
    }
}

#[test]
fn experimental_design_capacity_requires_plausible_charge_and_nominal_voltage() {
    let mut properties = battery(-1000, false);
    properties.insert("DesignCapacity".into(), Value::Integer(5000.into()));
    assert_eq!(macos::design_capacity_mwh(&properties), None);
    properties.insert("DesignVoltage".into(), Value::Integer(12000.into()));
    assert_eq!(macos::design_capacity_mwh(&properties), Some(60000));
    assert_eq!(
        macos::parse_member(&properties)
            .unwrap()
            .unwrap()
            .designed_capacity,
        60000
    );
    properties.insert("DesignCapacity".into(), Value::Integer(100.into()));
    assert_eq!(macos::design_capacity_mwh(&properties), None);
    properties.insert("DesignCapacity".into(), Value::Integer(5000.into()));
    properties.insert("DesignVoltage".into(), Value::Integer(0.into()));
    assert_eq!(macos::design_capacity_mwh(&properties), None);
}

#[test]
fn wrapped_current_and_instantaneous_precedence() {
    for wrapped in [u64::from(u32::MAX) - 999, u64::MAX - 999] {
        let mut properties = battery(1000, false);
        properties.insert("InstantAmperage".into(), Value::Integer(wrapped.into()));
        assert_eq!(sample(&properties).watts, Some(-12.0));
    }
}

#[test]
fn unknown_and_contradictory_readings_stay_unavailable() {
    for key in ["Amperage", "Voltage", "IsCharging", "ExternalConnected"] {
        let mut properties = battery(-1000, false);
        properties.remove(key);
        assert!(!sample(&properties).available, "{key}");
    }
    assert!(!sample(&battery(-1000, true)).available);
    assert!(!sample(&battery(1000, false)).available);
    assert!(!sample(&battery(i64::MAX, false)).available);
    let mut properties = battery(-1000, false);
    properties.insert("ExternalConnected".into(), Value::Boolean(true));
    assert_eq!(sample(&properties).watts, Some(-12.0));
}

#[test]
fn identity_absence_and_percentage_validation() {
    let mut properties = battery(-1000, false);
    properties.remove("IORegistryEntryID");
    assert!(macos::parse_member(&properties).is_err());
    properties.insert("BatteryInstalled".into(), Value::Boolean(false));
    assert!(macos::parse_member(&properties).unwrap().is_none());
    properties.insert("MaxCapacity".into(), Value::Integer(0.into()));
    assert_eq!(macos::percent(&properties), None);
}

#[test]
fn structured_plist_roundtrip_and_invalid_input() {
    let properties = battery(-1000, false);
    let mut bytes = Vec::new();
    Value::Array(vec![Value::Dictionary(properties.clone())])
        .to_writer_xml(&mut bytes)
        .unwrap();
    assert_eq!(macos::parse_snapshot(&bytes).unwrap(), vec![properties]);
    assert!(macos::parse_snapshot(b"not a plist").is_err());
}

#[cfg(target_os = "macos")]
#[test]
fn binary_fixture_flow_and_no_battery() {
    let root = std::env::temp_dir().join(format!("wpm-macos-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let fixture = root.join("battery.plist");
    let run = |args: &[&str]| {
        std::process::Command::new(env!("CARGO_BIN_EXE_wpm"))
            .args(args)
            .env("WPM_IOREG_FIXTURE", &fixture)
            .env("WPM_CONFIG", root.join("absent.toml"))
            .output()
            .unwrap()
    };
    Value::Array(vec![Value::Dictionary(battery(-1000, false))])
        .to_file_xml(&fixture)
        .unwrap();
    let output = run(&["--json"]);
    assert!(output.status.success());
    let value: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(value["watts"], -12.0);
    assert_eq!(value["pct"], 75.0);
    assert_eq!(value["source"], macos::SOURCE);
    assert!(value["remaining_wh"].is_null());
    Value::Array(vec![]).to_file_xml(&fixture).unwrap();
    let output = run(&["--json", "--require-battery"]);
    assert_eq!(output.status.code(), Some(3));
    let value: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(value["battery_present"], false);
    std::fs::remove_dir_all(root).unwrap();
}
