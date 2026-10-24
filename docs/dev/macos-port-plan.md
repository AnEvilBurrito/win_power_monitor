# macOS battery-flow provider - implementation plan

Status: initial implementation complete; native CI and hardware validation pending.
Experimental until validated on real Macs.
Targets: aarch64-apple-darwin and x86_64-apple-darwin MacBooks.

## 1. Scope

Battery charge/discharge watts, voltage, current, percentage, history, estimates,
and existing CSV/JSON/NDJSON modes. No menu-bar integration, whole-system power
measurement, wall power, SMC probing, powermetrics, or elevated privileges.

## 2. Data acquisition

Use the bundled `/usr/sbin/ioreg -a -r -c AppleSmartBattery` command and parse its
plist output with the `plist` crate. This is an intentional first-version tradeoff:
one subprocess per snapshot instead of unsafe IOKit/CoreFoundation bindings.
The existing Collector makes three snapshots per tick. Measure overhead on a Mac;
the normal five-second sampling interval is the initial target. Repeated reads do
not imply refreshed hardware telemetry.

Only the AppleSmartBattery class is queried; peripheral batteries and UPS power
sources are not included. Use IORegistryEntryID for per-session identity. Native
execution is macOS-only, while plist conversion and fixture tests run on every OS.
WPM_IOREG_FIXTURE can select a saved plist for deterministic CLI tests on macOS.

## 3. Conversion contract

| Property | Meaning / treatment |
|---|---|
| BatteryInstalled | Explicit false excludes the battery |
| ExternalConnected | AC connection; missing means unknown, not disconnected |
| IsCharging | Charging state, checked against signed current |
| Voltage | Positive mV |
| InstantAmperage, else Amperage | Signed mA; decode signed and wrapped 32/64-bit integers |
| AppleRawCurrentCapacity / AppleRawMaxCapacity | Charge-capacity ratio for percentage only |
| CurrentCapacity / MaxCapacity | Fallback ratio for percentage only, never assumed mAh |
| CycleCount | Optional diagnostic |

Power in mW = signed mA * mV / 1000. Reject out-of-range values and contradictory
charging/current direction. Do not take the absolute value to conceal conflicts.
An explicit zero current with known state can report idle zero watts; missing
current cannot. Negative current while connected to AC is valid discharge.
No capacity property is published as Wh: normalized and raw charge properties vary
by platform, and multiplying instantaneous voltage by charge is not measured energy.
Remaining/full/design Wh stay unavailable in this first version. Percentage is a
single-battery hint, never an average across multiple batteries.

## 4. Integration and safety

Add a macOS module and cfg-gated Provider, reusing Backend, Collector and Member.
Do not modify Windows/Linux provider bodies, shared collection rules, or Windows
packaging. Keep existing XDG/HOME config resolution for compatibility; tray stays
disabled by default. --system-meter reports unsupported on macOS.

Add macOS CI for formatting, Clippy, tests and native release build, plus a macOS
tarball packager. Separate arm64/x64 artifacts; no universal binary, signing or
notarization claim. Preserve Windows/Linux CI gates unchanged.

## 5. Verification and acceptance

Cross-platform fixtures: charge, discharge, idle, unsigned-wrapped negative current,
missing fields, contradictory direction, malformed plist, capacity units, identity,
and absent battery. macOS CLI fixtures exercise --once/--json and --require-battery.

Run cargo fmt --check, cargo clippy --locked --all-targets -- -D warnings,
cargo test --locked, and the existing Windows release build on this Windows host.
macOS CI must build and test natively; Windows cannot validate framework/runtime
behavior. Validate real Intel and Apple Silicon MacBooks separately before claiming
hardware support: unplugged, charging, charge-limited idle, sleep/wake and reconnect.
Compare ioreg properties and WPM output; redact serial numbers before sharing dumps.

## 6. Known limits

IORegistry battery properties are compatibility-sensitive, not a guaranteed stable
cross-generation telemetry schema. Initial fixtures are synthetic, not hardware
validation. Unsupported/missing readings stay unavailable. No native macOS builds
or hardware measurements are possible on the development Windows host.

## 7. Local verification

Windows: 71 tests pass, formatting and all-target Clippy with warnings denied pass,
and cargo build --release --locked succeeds. Both aarch64-apple-darwin and
x86_64-apple-darwin pass cargo clippy --locked --all-targets --target TARGET --
-D warnings on the Windows host. These checks compile macOS provider and test
paths without linking or executing them. Native packaging/CLI fixture execution
belongs to the added macOS CI jobs; hardware measurements remain unverified.