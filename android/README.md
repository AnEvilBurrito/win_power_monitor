# Android initial implementation

Experimental native Android app for **background-first** battery power monitoring.
Development builds and a separately signed release-candidate pipeline are available.
Real-device background endurance and energy overhead have not been validated.
Licensed under the repository's [GPL-3.0-only license](../LICENSE). The
[third-party notices](licenses/README.md) do not change the application's license.

## UI/UX design review

The [approved interactive mock-up](design/mobile-mockup.html) uses synthetic data
for design review only. The native app uses actual Android telemetry instead.
See the [implementation specification](specs/initial-implementation.md),
[design brief](design/README.md), and [device acceptance checklist](specs/device-acceptance.md).

## Included now

- Separate Gradle application; the desktop Cargo build is unchanged.
- Native Compose Monitor, Session, Diagnostics, settings, and four terminal palettes.
- Separate portrait and chart-first landscape layouts, absolute/signed display,
  retained freeze view, gaps, window/session statistics, and CSV export.
- Magnitude-aware units everywhere (W/mW/µW, Wh/mWh/µWh, A/mA/µA) with a chart axis
  that follows the data, because a phone idles in milliwatts: `1.26 mW`, not
  `0.00 W` on a fixed 0–1 W frame.
- **Interpreted instantaneous power/current are primary** in the readout, chart,
  new session totals, window statistics and notification. Power uses normalized
  CURRENT_NOW × reported voltage, not a rolling counter average. A saved/manual
  scale/sign interpretation enables live readings on the first poll, with no
  60-second wait. Hardware may repeat cached values despite a new poll.
- **Five startup polls about one second apart** provide a quick scale/sign
  interpretation, with `Checking reading N/5` feedback instead of a long blocked
  Calibrating step. Usable readings then show **Estimated** while charge-counter
  validation runs in the background. Ambiguous inference becomes actionable
  unavailable, never fabricated zero or an average fallback. Startup inference
  also works without a counter; only counter-confirmed calibration is auto-saved.
  Magnitude/status heuristics can be wrong and are not an accuracy calibration.
- The separate counter-derived ~60s average is used for runtime estimates and
  developer/CSV comparison only. It does not smooth or delay the main readout.
- **Settings → Developer mode** is off by default and persisted. It exposes raw
  properties, scale/sign overrides, calibration evidence and auxiliary averages.
  Progress, estimated/saved status and reset are also in normal Diagnostics.
  Background validation uses three consecutive consistent gauge-evidence windows;
  only its confirmed result survives restart as calibration. Reset affects NOW evidence, not observed capacity
  or retained sessions. Hiding dev mode preserves corrections. Design capacity
  remains a normal setting; signed raw/average evidence remains in CSV.
- Polling choices are **1 / 2 / 5 seconds, default 2s**. A new one-time migration
  changes prior 5/15/30s settings to 2s (old default vs deliberate 5s cannot be
  distinguished). Subsequent 1/2/5s choices persist. Startup/reset briefly polls
  at 1s until the five-poll check completes (even if ambiguous), saved/manual
  readings work, or NOW is unsupported. A 90s safety cap bounds failed acquisition;
  neither counter validation nor the auxiliary average keeps the burst running. No extra wake lock or
  persistent setting mutation. Normal events cannot sample faster than the selected
  interval. Polling does not force a hardware refresh or replace elapsed evidence.
- User-started foreground service, low-importance ongoing notification, and a
  notification stop action. Closing/swiping away the activity does not
  intentionally stop the service; Android/OEM policy can still terminate it.
- **Settings → Notification update rate:** Instant (each poll), 15s or 60s. Default
  stays 60s, including upgrades without a saved choice. Timed rates post on the
  first reading at/after the interval (e.g. 15s can be 16s with 2s polling).
  Changes apply immediately and persist; important status transitions bypass the
  rate. No extra polls, timers or wake locks; Android can defer/coalesce display.
- Android 13+ notification-permission handling.
- Private service using the `specialUse` foreground-service type, not `health`.
- Public BatteryManager/sticky-broadcast readings; unavailable properties stay
  unavailable. No root, network, privileged battery permission, boot receiver,
  exact-alarm polling, or automatic recording after process death.
- Low-impact mode has no wake lock. Explicit high-resolution mode uses a timed
  partial wake-lock lease; developer diagnostics reflect the actual lease flag.
- Framework SQLite storage (schema v4, history-preserving upgrade): at most 7 days
  **or 130,000 rows, whichever comes first** (~3 days at default 2s, ~1.5 at 1s).
  The bounded 8192-point live buffer supports the 2h chart at all polling choices.
  Batches of up to 4 samples / 60 seconds, first sample and Stop/export
  flushed promptly. Abrupt kill can lose the uncommitted tail. Cold reopen
  restores committed history as stopped, not a fabricated continued recording.
- Layout arithmetic that previously collapsed the landscape dashboard (a selected
  chip stretching to the full row and a squeezed toolbar label) is unit-tested in
  `ui/LandscapeRules.kt`. Canvas, axis/legend and 48dp controls have separate
  height budgets; larger fonts/short windows scroll instead of collapsing.
  Readouts use a bounded number+unit group and battery column. The suffix sits
  directly beside the digits, with actual font-measured width plus an ink guard
  so mW/µW are not clipped. Both use native baseline alignment. Calibrating/live/
  missing states reserve the same space. This addresses layout-driven jitter;
  actual frame stability remains a device check. Ordinary flow is not warning
  orange, and notification denial is no longer a dashboard error. Real recording
  and data-loss failures stay visible. The age ticker stops with the activity.
  In 0.5.1, freshness uses a synchronous frame clock rather than an older cached
  tick: new samples no longer briefly look future-dated/stale while an effect
  catches up. Runtime reserves two lines to avoid collapsing to a shorter status.
  See [the supplied-video analysis](specs/refresh-jitter.md); phone recheck pending.
- Raw samples and summaries are written atomically. Fatal persistence errors end
  recording and are surfaced. Pruning retained samples preserves session totals.

- Capacity is split into user-entered design capacity (optional, health reference
  only), an immediate single-point gauge estimate, and an observed estimate from
  completed monotonic charging **or discharging** windows spanning >=20 percentage
  points (e.g. 20 -> 40 or 80 -> 60). This is an accuracy policy, not a
  mathematical requirement, and it is an absolute percentage-point span, not 20% of
  the starting level. Neither a full charge nor a full discharge is needed.
  Estimated health compares observed capacity with the user-supplied design value.
- Cycle count is shown when Android reports it (API 34+). Runtime uses the recent
  counter average, not instantaneous current; charging time additionally needs an
  observed capacity. Missing inputs stay unavailable.
- Schema v4 preserves history while clearing only the old capacity aggregate:
  older estimates cannot honestly be relabelled as observed capacity.

**Deferred:** real-device acceptance/overhead profiling, background auto-resume,
Rust/JNI shared-core integration, archive/session picker, battery-wear modelling,
Play Store distribution and policy review.

The Kotlin app runs on ARM64 phones and x86-64 emulators. There is no first-party
NDK/Rust library, but Compose transitively packages a graphics native library.
The built APK's 64-bit ELF segments and APK alignment pass 16 KB checks.
Cloud backup and device-transfer extraction are explicitly excluded.

## Pinned toolchain

| Component | Version |
| --- | --- |
| Gradle | 8.13, distribution SHA-256 pinned |
| Android Gradle Plugin | 8.13.2 |
| Kotlin + Compose compiler plugin | 2.2.21 |
| Java running Gradle | JDK 17 or 21; JVM bytecode target 17 |
| Android SDK compile/target | API 36 |
| Android SDK Build Tools | 35.0.0 |
| Minimum device OS | Android 8.0 / API 26 |
| Compose BOM | 2025.12.00 |

These are intentionally pinned compatible versions, not a claim to use the newest
releases. Gradle 8.13 does **not** support running on JDK 25.
No NDK, CMake, Rust Android target, Room, or JNI dependency is needed at this stage.

## Android Studio development on Windows

**Recommended workflow:** let Android Studio manage the SDK and remember the
project's Gradle JDK. Normal IDE builds do not require `$env:` commands or changes
to your system-wide Java installation. Android Studio itself is optional; the
terminal alternative is described below.

### One-time setup

#### 1. Install Android Studio and SDK components

Install [Android Studio](https://developer.android.com/studio) and complete its
setup wizard, including the Android SDK. Use a release supporting AGP 8.13.2.

Open **SDK Manager** from the welcome screen's More Actions menu or Settings:

- SDK Platforms: Android API 36.
- SDK Tools: Android SDK Platform-Tools and Android SDK Command-line Tools.
- Enable **Show Package Details** and install Android SDK Build-Tools 35.0.0.
- Optional: Android Emulator plus an API 36 x86-64 image for this Windows host.
- Accept the SDK licenses when prompted.

Note the **Android SDK Location** displayed there. Android Studio normally finds
this SDK automatically. NDK and CMake are not needed until Rust integration.

#### 2. Open the Android project and select Java once

Open the repository's **android/** directory in Android Studio, not the repository
root. The standard Gradle wrapper scripts/JAR are now included, with the official
distribution SHA-256 pinned. Normally no bootstrap script is needed.

In **Settings > Build, Execution, Deployment > Build Tools > Gradle**:

- Select the **Gradle wrapper** as the distribution when available.
- Check **Gradle JDK**. Use the bundled runtime if it is JDK 17 or 21.
- Otherwise use **Download JDK** to select JDK 21, or select an existing JDK 17/21
  installation. Menu wording can vary by Studio version.

This selection is saved for the project. Android Studio's own runtime and the
Java running Gradle do not have to be the same version. Do not remove or globally
replace JDK 25 used by other projects.

Android Studio normally creates **android/local.properties** with the SDK path.
If it has not done so yet, create `local.properties` in the opened project's root
and copy the actual SDK Manager location using forward slashes, for example:

```properties
sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

This file is ignored by Git. Never commit it or another developer's SDK path.
With this file present, **ANDROID_HOME is unnecessary for this project's builds**.

#### 3. Wrapper recovery only (normally skip)

`gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are included. Skip this section
unless those generated files need recreating; it is not a daily setup step.

Open Android Studio's **Terminal**, choose PowerShell, and make sure the current
directory is **android/** (the opened project root). `Get-Location` shows it.
The IDE's Gradle JDK setting does **not** automatically configure this terminal.
For this one-time helper only, point Java at the actual JDK directory selected
in step 2 if the terminal's existing JAVA_HOME/Java is not already JDK 17/21:

```powershell
# Run inside android/. Replace with the actual Gradle JDK installation path.
$env:JAVA_HOME = 'C:\path\to\jdk-21'
powershell -File .\setup-gradle.ps1
```

No PATH or ANDROID_HOME assignment is needed: the helper uses JAVA_HOME directly
and the SDK path in `local.properties`. This temporary JAVA_HOME assignment
expires when the terminal closes; **you do not repeat it for daily IDE builds**.
It does not change Android Studio's saved Gradle JDK or Windows-wide Java settings.

The helper downloads the official Gradle distribution into your user Gradle cache,
verifies SHA-256, and generates `gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.jar`. Retain those files in version control after
successful generation. The existing wrapper properties pin the distribution and
checksum. Network access is required for Gradle, plugins, and dependencies.

If running the helper from a separate terminal at the **repository root**, the
script path instead is `powershell -File .\android\setup-gradle.ps1`.
If execution policy blocks it, review the script and use your organization's
approved way to run it; no system-wide policy change is needed.

#### 4. Sync and run

Return to Android Studio and **Sync Project with Gradle Files**. Decline suggested
toolchain upgrades for now; use the pinned versions above for the first build.

For a physical phone:

1. Enable Developer Options and USB debugging.
2. Connect it by USB and approve the computer's debugging key on the phone.
3. Install the Windows OEM USB driver if the device is not detected.
4. Select the **app** run configuration and your phone, then click **Run**.

An emulator is optional and cannot validate real battery-current telemetry.

### Everyday development (no environment commands)

1. Open **android/** in Android Studio.
2. Edit Kotlin/resources; sync when changing Gradle dependencies.
3. Select your phone and click **Run**. The IDE builds, installs, and launches it.
4. Inspect **Logcat** for service startup or lifecycle errors.

For an APK and lint checks, use the **Gradle tool window > Execute Gradle Task**
and enter `:app:assembleDebug :app:lintDebug` (UI wording may vary). These tasks
use the saved Gradle JDK; they are not commands to paste into PowerShell.

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk` relative to the
repository root. Debug signing is automatic, with application ID
`io.github.anevilburrito.wpm.debug` and version suffix `-debug`, separate from the
permanent release ID `io.github.anevilburrito.wpm`. Treat debug APKs as development
builds, not validated monitoring releases. Play distribution remains separate.

### Optional terminal workflow

This is separate from the IDE workflow. A new terminal does not inherit Android
Studio's Gradle JDK selection. Either set JAVA_HOME for that session, or configure
it once through Windows Environment Variables if you want that JDK as your normal
terminal default. The latter affects other Java projects, so it is not required
or recommended merely to build this app through Android Studio.

From **android/**, with JAVA_HOME pointing to JDK 17/21 and `local.properties`
already locating the SDK:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

From the repository root, you can instead run
`pwsh -NoProfile -File scripts/build-android.ps1` to test/lint/build the unsigned
release variant without changing the shell directory. Set JAVA_HOME to JDK 21 for release verification. ADB commands are
optional; Android Studio handles development installation and launch.

### Automated APK builds

The separate [Android debug APK workflow](../.github/workflows/android.yml) runs
on pushes/pull requests affecting `android/` or the workflow itself. To build
manually, open **Actions → Android debug APK → Run workflow** and select a ref.
Desktop workflows and release-candidate packaging are unchanged.

The job uses Ubuntu 24.04, JDK 21, the checksummed Gradle wrapper, API 36 and Build
Tools 35.0.0. It validates the wrapper, runs JVM tests/lint, assembles the debug
APK, checks its signature and 16 KiB ZIP alignment, and writes a SHA-256 checksum.
Only successful checks permit the APK upload. The run's **Artifacts** section has
`wpm-android-debug-<commit>-<attempt>` with `app-debug.apk`, its `.sha256` file and
`output-metadata.json`. Test/lint reports are a separate artifact, including after
build failures when available. Both artifacts expire after 14 days. After
extracting the APK artifact, `sha256sum -c app-debug.apk.sha256` verifies it; on
PowerShell compare `Get-FileHash .\app-debug.apk -Algorithm SHA256` with the file.

These are development artifacts, not published releases or release-signed APKs.
Fresh CI runners generate debug signing keys; they may differ between CI runs
and from your local key. Android will refuse an in-place update with a different
key. **Export important recordings before uninstalling an existing copy**, because
uninstalling removes its private history/settings. No release signing secrets
are used by this debug workflow. CI does not establish hardware accuracy,
background endurance or observer overhead.

### Signed Android release candidates

The manual [signed candidate workflow](../.github/workflows/android-candidate.yml)
builds, tests and lints the release APK on an immutable master/Android-tag SHA.
A separate protected `android-release` job aligns, signs and verifies the APK
against the approved certificate, then calculates its SHA-256 checksum. Secrets
are not passed to Gradle and the temporary keystore is always deleted.
There is no dependency-source export, native-source download or source rebuild.

The final `wpm-android-release-assets-<sha>-<attempt>` artifact contains only the
signed APK and its SHA-256 sidecar. It expires after 14 days. The
workflow does **not** tag or publish; the `android-release` skill separately
verifies the downloaded inventory and creates an approved GitHub pre-release.
Android tags use `android-v<VERSION>`, independently of desktop versions.
See [the pipeline contract](specs/release-pipeline.md) for signing and APK checks.

The permanent release ID is `io.github.anevilburrito.wpm`. The first transition
from an older unsuffixed debug-signed installation normally requires uninstalling
that copy: **export important recordings first**. Subsequent official releases
retain the signer/ID and increase versionCode for in-place updates. The maintainer
will handle phone testing after the first pre-release; this is not stable-device
acceptance. No installation, uninstall or data clearing is performed by CI.

### Verification status

Local verification for `0.7.0-rc.1` (versionCode 11), using JDK 21 and the SDK:

```text
:app:testReleaseUnitTest      481 tests, 0 failures/errors/skips
:app:lintRelease             0 errors, 17 warnings
:app:assembleRelease         app/build/outputs/apk/release/app-release-unsigned.apk
Python release tooling      225 tests passed
```

Hosted build/signing evidence is provided by the exact candidate run
linked in each release, not inferred from these local checks.
The universal APK is approximately 11 MiB. Native ELF LOAD alignment and
`zipalign -c -P 16 -v 4` both pass. Lint warnings include the intentionally pinned
older toolchain/target SDK, Compose modifier style and icon/KTX suggestions; they
are not hidden by a generated baseline.

These are compile/unit/lint/package checks, **not device execution or battery
measurements**. Follow [device acceptance](specs/device-acceptance.md) before
claiming screen-off reliability, accurate OEM polarity, or low observer overhead.
In particular, current calibration and AccuBattery parity, warning stability,
landscape layout, schema upgrade, and observed-capacity plausibility still need
on-device checks. No earlier screenshot alone establishes a 1000x OEM unit error.
The gauge's refresh cadence and counter resolution must be measured on the phone.
Start a fresh recording to compare the new calibrated-live totals. Stored historical
summaries keep their original metric; CSV marks restored summaries as metric unknown
rather than relabelling older counter-average totals as live-power totals.
See [the current measurement contract](specs/live-current.md).

## Background-monitoring contract for implementation

- The service owns sampling and storage; the activity only observes state.
- Default low-impact mode permits CPU sleep. Store monotonic timestamps, actual
  sample intervals, stale readings, and gaps; never fabricate sleep-time samples.
- A foreground service does not prevent suspend. An optional high-resolution
  mode may use a partial wake lock, with explicit user consent and measurement
  of the observer's own power impact. Do not add permanent wake locks by default.
- WorkManager is not the seconds-level sampling engine; do not use exact alarms
  to evade background restrictions.
- `START_NOT_STICKY` is deliberate. Persisted data is restored as stopped after
  process death; recording is never silently restarted.
- `specialUse` needs a truthful manifest justification and Google Play review if
  distributed there. A foreground service is not immunity from OEM restrictions,
  Android's user stop controls, force-stop, or reboot.

## Next milestones and dependencies

1. Complete the real-device checklist: first launch, rotation/font scale, actual
   telemetry, screen-off/Doze, Stop/start races, storage/export and cold recovery.
2. Profile foreground/background overhead at each cadence across manufacturers;
   keep low-impact defaults until the measured tradeoffs justify changes.
3. Optionally extract/share the Rust model/analytics/export via JNI. Keep desktop
   dependencies out, and retain the Kotlin conformance tests as a porting oracle.
   NDK/cargo-ndk/Android Rust targets are not needed for the current app.
4. Validate the first hosted Android workflow run and keep wrapper, unit/lint,
   signature/alignment checks and downloadable debug artifacts working.
5. Preserve the permanent ID/signing key and verify signed candidate identity,
   checksum and in-place updates; separately review Play/foreground-service policy.

## References

- [AGP 8.13 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes)
- [Gradle 8.13 Java compatibility](https://docs.gradle.org/8.13/userguide/compatibility.html)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Wake-lock impact](https://developer.android.com/topic/performance/vitals/wakelock)
- [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)
