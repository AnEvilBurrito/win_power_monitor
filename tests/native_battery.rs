use wpm_cli::{
    model::{Sample, State},
    native_battery::{Reading, apply},
};

fn reading() -> Reading {
    Reading {
        capabilities: 0x8000_0000,
        power_state: 2,
        capacity: 42000,
        voltage: 15000,
        rate: -12000,
    }
}
fn convert(b: Reading) -> Sample {
    let mut s = Sample::empty(0.0);
    apply(&mut s, &[b]);
    s
}
#[test]
fn signed_milliunits_and_source() {
    let s = convert(reading());
    assert_eq!(s.state, State::Discharging);
    assert_eq!(s.watts, Some(-12.0));
    assert_eq!(s.volts, Some(15.0));
    assert_eq!(s.amps, Some(-0.8));
    assert_eq!(s.remaining_wh, Some(42.0));
    assert_eq!(s.source, "native:IOCTL_BATTERY_QUERY_STATUS");
    assert_eq!(s.measurement_kind, "battery_flow");
    assert!(s.available);
    assert!(!s.estimated);
}
#[test]
fn charging_idle_and_ac_are_independent() {
    let s = convert(Reading {
        power_state: 5,
        rate: 6500,
        ..reading()
    });
    assert_eq!(s.state, State::Charging);
    assert_eq!(s.watts, Some(6.5));
    assert_eq!(s.ac_online, Some(true));
    let s = convert(Reading {
        power_state: 1,
        rate: 0,
        ..reading()
    });
    assert_eq!(s.state, State::Idle);
    assert_eq!(s.watts, Some(0.0));
    let s = convert(Reading {
        power_state: 3,
        ..reading()
    });
    assert_eq!(s.state, State::Discharging);
    assert_eq!(s.ac_online, Some(true));
}
#[test]
fn unknown_signed_rate_does_not_become_huge_discharge() {
    let s = convert(Reading {
        rate: i32::MIN,
        ..reading()
    });
    assert_eq!(s.watts, None);
    assert_eq!(s.amps, None);
    assert!(!s.available);
}
#[test]
fn unknown_capacity_voltage_and_zero_are_distinct() {
    for voltage in [0, u32::MAX] {
        let s = convert(Reading {
            voltage,
            capacity: u32::MAX,
            ..reading()
        });
        assert_eq!(s.volts, None);
        assert_eq!(s.amps, None);
        assert_eq!(s.remaining_wh, None);
        assert_eq!(s.watts, Some(-12.0));
    }
    assert_eq!(
        convert(Reading {
            capacity: 0,
            ..reading()
        })
        .remaining_wh,
        Some(0.0)
    );
}
#[test]
fn relative_units_block_native_and_wmi_watts() {
    let mut s = Sample::empty(0.0);
    assert!(!apply(
        &mut s,
        &[Reading {
            capabilities: 0xc000_0000,
            ..reading()
        }]
    ));
    assert_eq!(s.reason.as_deref(), Some("native_relative_units"));
    assert_eq!(s.watts, None);
    assert_eq!(s.remaining_wh, None);
    assert_eq!(s.volts, None);
    assert_eq!(s.amps, None);
}
#[test]
fn contradictory_state_and_sign_are_unavailable() {
    for (power_state, rate) in [(6, -12000), (4, -12000), (2, 12000), (1, -12000)] {
        let s = convert(Reading {
            power_state,
            rate,
            ..reading()
        });
        assert!(!s.available);
        assert_eq!(s.watts, None);
    }
}
#[test]
fn system_battery_filter_and_multiple_battery_safety() {
    let mut s = Sample::empty(0.0);
    assert!(!apply(
        &mut s,
        &[
            reading(),
            Reading {
                capabilities: 0xa000_0000,
                ..reading()
            },
            Reading {
                capabilities: 0,
                ..reading()
            }
        ]
    ));
    assert_eq!(s.watts, Some(-12.0));
    let mut s = Sample::empty(0.0);
    assert!(!apply(&mut s, &[reading(), reading()]));
    assert!(s.available);
    assert_eq!(s.watts, Some(-24.0));
}
#[test]
fn missing_battery_allows_diagnostic_fallback() {
    let mut s = Sample::empty(0.0);
    assert!(apply(&mut s, &[]));
    assert!(!s.available);
    assert!(apply(
        &mut s,
        &[Reading {
            capabilities: 0,
            ..reading()
        }]
    ));
}
