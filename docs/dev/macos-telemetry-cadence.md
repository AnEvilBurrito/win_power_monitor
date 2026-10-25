# macOS telemetry cadence - deferred investigation

Recorded: 2026-10-24. Status: development note only; no implementation scheduled.
This does not expand the current macOS provider's scope or hardware-support claims.

## User observation and available evidence

A user supplied a WPM dashboard screenshot from an Apple Silicon machine. The
five-minute chart contains long flat sections between changes in reported battery
power, roughly spanning 25-42 W, with 27 W discharge displayed at capture time.
The user reports that AppleSmartBattery appears to refresh on its own schedule,
rather than at WPM's polling interval.

The screenshot is all we currently have. There is no raw telemetry trace, exact
Mac model, macOS version, workload record, or confirmed configured poll interval.
It is evidence of a real user run, not complete validation of the provider. It is
consistent with slower source updates but cannot establish a fixed refresh period
or prove that scheduling, rather than steady load or another cause, explains each
plateau.

## Reading properties is not requesting a measurement

The current provider reads `/usr/sbin/ioreg -a -r -c AppleSmartBattery`, preferring
`InstantAmperage` over `Amperage` and calculating battery power from current and
voltage. The property name `InstantAmperage` does not guarantee a new physical
measurement for each read.

The effective path is battery/controller -> macOS-managed updates -> IORegistry
-> WPM reads. The shared collector's three snapshots check consistency across
battery/charger transitions; they do not force hardware refreshes. Direct IOKit
property reads could reduce subprocess overhead but would not, by themselves,
change the underlying telemetry cadence.

Repeated identical values alone are not proof of stale data. Do not discard them
or infer measurement timestamps from value changes. Charts, statistics, and
integrated session Wh describe the reported signal and may miss short workload
spikes; integration is not an independent energy measurement.

## Private battery refresh request

Apple's published IOKitUser sources expose the private function:

```c
IOPSRequestBatteryUpdate(kIOPSReadAll);
```

Its header says it tells the battery driver to read battery state and is intended
for diagnostic tools requiring more frequent updates. It is asynchronous: request
an update, wait for a power-source notification, and then read properties. A
notification alone does not prove that every sensor supplied a new measurement.

Important limitations:

- This is an internal, publicly unsupported interface, not a stable public API.
- The published implementation opens `AppleSmartBatteryManager` and invokes its
  request-poll user-client method. The published manager user client checks for
  administrator privileges when opened; do not assume this is a sudo-less path.
- The header describes automatic user-visible updates every 60 seconds and other
  data every 10 minutes or less frequently. These descriptions are not verified
  per-property cadence guarantees for current Apple Silicon hardware/macOS.
- A driver refresh request does not guarantee a new physical sensor conversion.
- Availability, privileges, completion behavior, and actual improvements have not
  been tested on the user's machine or any Apple Silicon machine for this note.

There is therefore a concrete refresh-request mechanism to investigate, but no
verified supported public API that guarantees fresh watts on demand. Do not
introduce a privileged helper or promise faster readings based on source inspection
alone.

## Alternative sources worth investigating

| Source | Potential measurement | Constraints |
| --- | --- | --- |
| AppleSMC | System/rail power; existing tools use `PSTR` for system power and `PDTR` for adapter input | Undocumented keys, model/OS-dependent availability and semantics; sensors still have their own update cadence |
| IOReport | Available component energy counters, converted to interval-average watts using energy deltas | Private interface; CPU/GPU/ANE or other component totals are not necessarily whole-machine power |
| `powermetrics` | Apple's power diagnostics, including supported component power estimates | Normally requires root; output/coverage vary; not equivalent to battery or wall power |

Existing projects demonstrate possible access paths, not validated contracts for
WPM. Some use SMC/IOReport without sudo; permissions and compatibility must be
verified rather than generalized. Do not copy model-specific timing assumptions
or infer battery power from adapter/system differences without validating scope,
signs, synchronization, and losses.

Battery flow, system power, component power, and wall power are different
measurement kinds. Never silently substitute one for another. Any future SMC or
IOReport mode should have an explicit source and measurement label and remain
separate from the current battery-flow contract.

## Deferred validation ideas

When access to a Mac or further user traces becomes available:

1. Record model/chip, macOS and WPM versions, poll interval, and charger state.
2. Capture timestamped current/voltage and any available source-update metadata
   during controlled idle -> load -> idle transitions, including AC changes and
   sleep/wake. Verify what any source timestamps actually represent.
3. Compare ordinary reads with private refresh requests, checking permissions,
   errors, asynchronous completion, latency, and whether measurements change more
   frequently. Bound waits and preserve unavailable/error behavior.
4. Independently evaluate SMC and IOReport coverage, overhead, update cadence, and
   measurement meaning. Faster property access is not proof of fresher data.
5. Consider UI/docs wording such as: "macOS-reported battery flow; hardware update
   cadence may be slower than polling." Do not declare equal samples stale.

No code, default sampling rate, privilege requirements, or measurement semantics
are changed by this note.

## Sources inspected

These are moving upstream links, inspected on the recorded date, not proof of the
behavior of a particular shipping macOS release.

- [Apple IOPowerSourcesPrivate.h: API contract and cadence comments](https://github.com/apple-oss-distributions/IOKitUser/blob/main/ps.subproj/IOPowerSourcesPrivate.h)
- [Apple IOPowerSourcesPrivate.c: refresh-request implementation](https://github.com/apple-oss-distributions/IOKitUser/blob/main/ps.subproj/IOPowerSourcesPrivate.c)
- [AppleSmartBatteryManagerUserClient.cpp: privileges and request-poll handling](https://github.com/apple-oss-distributions/PowerManagement/blob/main/AppleSmartBatteryManager/AppleSmartBatteryManagerUserClient.cpp)
- [AppleSmartBattery.cpp: asynchronous driver polling](https://github.com/apple-oss-distributions/PowerManagement/blob/main/AppleSmartBatteryManager/AppleSmartBattery.cpp)
- [BatteryTimeRemaining.m: powerd battery polling](https://github.com/apple-oss-distributions/PowerManagement/blob/main/pmconfigd/BatteryTimeRemaining.m)
- [macmon: private-API Apple Silicon component telemetry](https://github.com/vladkens/macmon)
- [power-monitor-macos: SMC PSTR/PDTR telemetry example](https://github.com/mmaaaff/power-monitor-macos)
