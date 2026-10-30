use plist::{Dictionary, Value};
use wpm_cli::{macos, macos_smc, model::Sample, native_battery::apply_members};

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
fn experimental_design_capacity_uses_nominal_or_live_voltage() {
    let mut properties = battery(-1000, false);
    properties.insert("DesignCapacity".into(), Value::Integer(5000.into()));
    assert_eq!(macos::design_capacity_mwh(&properties), Some(60000));
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
    assert_eq!(macos::design_capacity_mwh(&properties), Some(60000));
    properties.remove("Voltage");
    assert_eq!(macos::design_capacity_mwh(&properties), None);
}

#[test]
fn raw_charge_capacity_estimates_and_nested_design() {
    let mut properties = battery(-1000, false);
    properties.insert(
        "AppleRawCurrentCapacity".into(),
        Value::Integer(3000.into()),
    );
    properties.insert("AppleRawMaxCapacity".into(), Value::Integer(5000.into()));
    let mut nested = Dictionary::new();
    nested.insert("DesignCapacity".into(), Value::Integer(5500.into()));
    properties.insert("BatteryData".into(), Value::Dictionary(nested));
    let reading = sample(&properties);
    assert_eq!(reading.remaining_wh, Some(36.0));
    assert_eq!(reading.batteries[0].full_charged_wh, Some(60.0));
    assert_eq!(reading.batteries[0].designed_capacity, 66000);
    assert_eq!(macos::percent(&properties), Some(60.0));
    properties.insert(
        "AppleRawCurrentCapacity".into(),
        Value::Integer(6000.into()),
    );
    assert_eq!(macos::charge_energy_mwh(&properties), (None, Some(60000)));
    properties.insert("AppleRawCurrentCapacity".into(), Value::Integer(0.into()));
    assert_eq!(
        macos::charge_energy_mwh(&properties),
        (Some(0), Some(60000))
    );
    properties.remove("Voltage");
    assert_eq!(macos::charge_energy_mwh(&properties), (None, None));
    assert_eq!(macos::design_capacity_mwh(&properties), None);
}

#[test]
fn smc_battery_decode_and_sign_checked_fallback() {
    assert_eq!(
        macos_smc::decode_battery(&12000u16.to_le_bytes(), &(-1000i16).to_le_bytes()),
        Ok((12000, -1000))
    );
    assert!(macos_smc::decode_battery(&[1], &[0, 0]).is_err());
    assert!(macos_smc::decode_battery(&0u16.to_le_bytes(), &[0, 0]).is_err());
    let mut discharge = sample(&battery(-900, false));
    assert_eq!(
        macos_smc::apply_battery(&mut discharge, 12000, 1000),
        Err("smc_direction_conflict")
    );
    assert_eq!(discharge.watts, Some(-10.8));
    assert!(macos_smc::apply_battery(&mut discharge, 12000, -1000).is_ok());
    assert_eq!(discharge.watts, Some(-12.0));
    assert_eq!(discharge.batteries[0].watts, Some(-12.0));
    assert_eq!(discharge.source, "native:macos_smc_battery");
    let mut charging = sample(&battery(1000, true));
    assert!(macos_smc::apply_battery(&mut charging, 12000, 1000).is_ok());
    assert_eq!(charging.watts, Some(12.0));
    charging.transition = true;
    assert_eq!(
        macos_smc::apply_battery(&mut charging, 12000, 1000),
        Err("smc_asb_state_unavailable")
    );
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
    let mut properties = battery(-1000, false);
    properties.insert(
        "AppleRawCurrentCapacity".into(),
        Value::Integer(3000.into()),
    );
    properties.insert("AppleRawMaxCapacity".into(), Value::Integer(5000.into()));
    Value::Array(vec![Value::Dictionary(properties)])
        .to_file_xml(&fixture)
        .unwrap();
    let output = run(&["--json"]);
    assert!(output.status.success());
    let value: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(value["watts"], -12.0);
    assert_eq!(value["pct"], 60.0);
    assert_eq!(value["source"], macos::SOURCE);
    assert_eq!(value["remaining_wh"], 36.0);
    assert_eq!(value["batteries"][0]["full_charged_wh"], 60.0);
    Value::Array(vec![]).to_file_xml(&fixture).unwrap();
    let output = run(&["--json", "--require-battery"]);
    assert_eq!(output.status.code(), Some(3));
    let value: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(value["battery_present"], false);
    std::fs::remove_dir_all(root).unwrap();
}
