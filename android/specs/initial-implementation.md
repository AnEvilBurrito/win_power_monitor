# Android initial implementation specification

Status: approved UI translated into an initial implementation milestone.
Measurement semantics updated by [measurement-refactor.md](measurement-refactor.md),
then average-first UI by [average-first-ui.md](average-first-ui.md). The current
0.6.0 [live-current contract](live-current.md) supersedes both for main readings/cadence.
Visual reference: ../design/mobile-mockup.html and ../design/README.md. The latest
landscape design is authoritative: full-width chart, NOT the superseded side rail.
License: GPL-3.0-only, matching the repository. Desktop source/build remains intact.

## 1. Deliverable and scope

A native Kotlin/Compose debug APK, API 26+, targeting API 36, usable without root.
Provide actual public-API battery telemetry, user-started background recording,
bounded durable local history, session statistics, CSV export, portrait and
landscape dashboards, diagnostics, settings, themes, and display freeze.
No synthetic samples or developer/scaffold messaging in the running application.
Missing device telemetry must remain unavailable rather than simulated.

Use existing pinned Gradle/AGP/Kotlin/Compose versions. No WebView, chart SDK,
network/analytics SDK, Room/KSP, or JNI/NDK in this milestone. Framework SQLite
avoids new runtime/build dependencies. Rust core extraction is separate work;
test the Kotlin analytics contract explicitly rather than claiming shared Rust.

## 2. User interface

### Monitor

- Portrait follows the approved stacked dashboard. Calibrated live power/current +
  direction, percentage, voltage, chart, window statistics, session link and controls.
  Show Checking reading N/5 during the quick startup check, then live numbers
  labelled Estimated until counter-confirmed. Saved/manual calibration enables
  the first live sample without any counter-average wait. Missing NOW is
  unavailable, never substituted with a counter/platform average.
  Pack number and suffix together in a bounded group; reserve the suffix's actual
  measured font width, not a guessed character advance. Use native baselines.
  Keep the group's footprint, battery position and line heights stable through
  digit-count, unit, missingness and readiness changes.
  Larger fonts may rearrange once per configuration, not once per reading.
- Landscape is a distinct chart-first composition: compact readings/statistics
  strip ABOVE a nearly full-width plot; one chart toolbar; slim navigation,
  session/mode context, and Stop BELOW. No side rail or fixed portrait aspect
  ratio. Canvas uses its actual bounds. Target >=90% plotting width at 880x452dp,
  allowing axis margins/safe insets; adapt gracefully to smaller windows/font size.
- All interactive targets >=48dp. Observe safe drawing insets. Small screens and
  font scaling may scroll detail, never clip Stop/navigation or force tiny text.
- Windows 5m / 30m / 2h. Include zero on Y axis. Independent magnitude and signed
  bounds, gaps not connected. Stable cool trace regardless of current direction.
- Absolute scale defaults on. Off displays negative discharge / positive charge
  for watts/current, chart, window/session averages and ordered extrema. Out/in
  energy totals stay separate nonnegative values. Raw diagnostics/CSV stay signed.
- Freeze captures monitor readings/history/statistics, not the service. Timestamp
  the frozen view. Diagnostics, notifications, and recording remain live. A scale
  change transforms the frozen snapshot without unfreezing it. Rotation does not
  reset recording, current page, selected window, frozen state, or theme.
- Stopped/stale snapshots do not look live. Show dashes for current measurements
  when stopped or last successful poll is older than max(45s, 3*interval), or when
  persisted monotonic timestamps are from before a reboot. Missing current alone
  does not hide a valid percentage/voltage. Last poll is not hardware update age.

### Session / Diagnostics / settings

- Session: elapsed time, battery-out/in energy (Wh / mWh / µWh), observed coverage,
  average/min/max in the same auto-selected unit, gaps, recent events, export.
  Report bounded retained-history limitations honestly.
- Diagnostics: recording status/mode/cadence, battery level, live power/current,
  voltage, temperature/condition, age, capacity and current calibration/readiness. No per-sample
  warning banner. Normal discharge/charge is not warning orange. Ordinary permission
  advisories never occupy the recording-error channel; real failures remain visible.
- Developer mode is explicit, default-off and persisted. Raw properties, counter
  windows/averages, calibration evidence, manual scale/sign and wake-lock details
  are developer-only. Hiding dev mode preserves corrections. Calibration status and
  reset/retry and startup progress are also in normal Diagnostics.
- Capacity concepts stay distinct: optional user-entered design capacity; immediate
  single-point gauge estimate (counter / reported fraction); observed capacity from
  completed monotonic charging **or discharging** windows spanning >=20 percentage
  points (an absolute percentage-point span; accuracy policy, not a mathematical
  prerequisite). Estimated health requires observed and
  design capacity. Neither gauge nor design capacity is persisted as an observation.
  Platform cycle count (API34+) and runtime remain unavailable when inputs are absent.
- Units are magnitude-aware everywhere: power W/mW/µW, energy Wh/mWh/µWh, current
  A/mA/µA. A phone idling at 1.26 mW must read `1.26 mW`, never `0.00 W`, and the
  chart axis must follow the data (`0 / 2 / 4 mW`) instead of a fixed 1 W frame.
- Android condition "Good" is not retained-capacity health. Own power overhead is
  unmeasured and no low-drain claim is made. Battery flow is not wall/charger/per-app consumption.
- Settings: 1/2/5s interval (2s default). A new one-time migration changes old
  5/15/30s settings to 2s; later explicit 1/2/5s survives. The old migration flag
  cannot skip this upgrade. Low-impact / high-resolution, four palettes (Tokyo Night Storm
  default, Mocha, Latte, Mono), absolute toggle, optional editable/clearable design
  capacity (health reference only) and developer-mode toggle. Manual scale/sign and
  calibration evidence are developer-only. NOW calibration reset/restart (with
  confirmation) is normal Diagnostics. No deep discharge/full charge required.
- High-resolution selection requires a clear confirmation: holding a partial
  wake lock costs battery and can increase idle drain. Screen may remain off.
- End-user copy only: e.g. "Android may delay readings while your phone sleeps."
  No proposals/profiling/TODO/synthetic/developer explanation inside the app UI.
- Starting asks notification permission on API33+ when needed; denial does not
  prohibit FGS or add a persistent orange dashboard warning. In-app Stop confirms; the
  notification Stop acts directly without opening an activity.

## 3. Architecture and ownership

One foreground service owns sampling, wake lock and notification. The activity
observes StateFlows with lifecycle awareness; it never launches a sampler.
Keep display state in a UI ViewModel across configuration changes. A new process
loads the latest SAVED session as stopped; never silently resume recording.

Shared contracts are in model/MonitorModels.kt (parent-owned). UI implementation
lives under ui/** (UI agent). Service/storage/provider under monitoring/** (service
agent), plus service strings/manifest. Parent owns MainActivity, build/dependencies,
model analytics/tests, integration/docs/CI. Do not edit another owner's files.

### Repository/host API (service agent must implement exactly)

Package io.github.anevilburrito.wpm.monitoring:

- `class MonitoringRepository` with `companion object { fun get(context: Context):
  MonitoringRepository }` (application-context singleton).
- `val state: StateFlow<MonitorUiState>`; `val settings: StateFlow<MonitorSettings>`.
- `val wakeLockHeld: StateFlow<Boolean>` reflects the actual lease flag. Host
  merges it into `MonitorUiState.wakeLockHeld`; UI must not infer held from mode.
- `suspend fun load()` idempotent, safe when activity/service call concurrently.
- `fun saveSettings(value: MonitorSettings)` validates allowed intervals and saves
  preferences; immediately publishes settings. Running service observes changes.
- `suspend fun writeCsv(output: OutputStream)` exports retained samples for the
  latest session with timestamps, signed values, gap markers/reasons, header and
  blanks for missing properties; callers run on IO. Flush pending samples first.
- Other service/internal methods are up to the service agent.
- `MonitoringService.ACTION_START`, `.ACTION_STOP` constants. Start via explicit
  ContextCompat.startForegroundService from a visible activity. Normal Stop sends
  ACTION_STOP so the tail is flushed before stopSelf; abrupt platform destruction
  is only best-effort cleanup. Repeated Start must not create duplicate samplers.
- All published collections/snapshots immutable copies; shared repository state
  synchronized. Expose errors in MonitorUiState, never quietly lose recordings.

### UI API (UI agent must implement exactly)

Package io.github.anevilburrito.wpm.ui:

`@Composable fun WpmDashboard(state: MonitorUiState, settings: MonitorSettings,
 onStart: () -> Unit, onStop: () -> Unit,
 onSettingsChange: (MonitorSettings) -> Unit, onExport: () -> Unit,
 onResetCalibration: () -> Unit)`

State/settings from repository; host handles Android permission/start/stop/export.
UI ViewModel holds presentation/freeze state; use lifecycle-viewmodel-compose.
Pure formatting/decimation helpers and JVM tests may live in ui/** / test/**/ui.
Use model analytics helpers (below) rather than independent energy calculations.

## 4. Telemetry and measurement correctness

- BatteryManager CURRENT_NOW in signed µA, sticky ACTION_BATTERY_CHANGED mV,
  percentage level/scale, plugged/status, temperature (tenths °C), health enum.
- Primary readings use interpreted normalized CURRENT_NOW and stored decoder watts
  (normalized current × valid voltage). Do not recompute power suppressed by the
  decoder's voltage/polarity checks. Accept currentCalibrated + source INSTANT,
  or explicitly labelled INSTANT_ESTIMATED from the five-poll heuristic. Missing
  or ambiguous readings stay missing. Readout, chart, new summaries,
  window stats and notification agree on this metric. A saved interpretation works
  immediately even before a counter average or without a counter.
- Counter-derived current/power remain auxiliary runtime/developer/CSV values.
  Keep existing CSV columns and estimated-source provenance; new summaries use
  INSTANT_INTERPRETED and disclose possible startup-estimated segments. Stored summaries are not rewritten;
  restored metric remains unknown because the schema lacks a per-session marker.
- Current calibration compares integrated raw current with charge-counter change
  across >=3 consecutive consistent, non-overlapping windows (>=15s and >=3mAh
  each; <=5min). Establish scale and polarity together; interruptions/contradictory
  evidence restart the pending run. Once saved, calibration stays stable until reset.
  This checks gauge consistency, not independent absolute accuracy or equivalence
  with another app. No single instantaneous-to-average ratio can prove a unit bug.
- Five roughly one-second startup polls infer tentative scale/sign using magnitude
  bands and direction evidence; display them as Estimated, never auto-save them as
  confirmed. Background counter thresholds above remain unchanged. See live-current.md.
- Manual unit/polarity > saved counter calibration > ephemeral startup inference >
  documented µA/sign fallback (the last is not eligible for live display alone).
  Save raw CURRENT_AVERAGE and provenance in CSV; do not assume its scale equals NOW.
  Record interpretation changes as gaps, never integrate across corrections or
  retroactively rewrite historical energy. Explicit reset revisions prevent stale
  in-flight calibration evidence from undoing a user reset.
- Poll at 1s during startup/reset; end at five-poll completion (including ambiguity),
  confirmed/manual readiness, unsupported NOW, or the <=90s failure safety cap.
  Counter validation continues at selected cadence and never holds the display. No wake-lock change or persistent interval mutation. Otherwise
  use selected 1/2/5s; that interval is also the event spacing floor, so a 1s
  choice is honoured and events cannot silently increase the selected rate.
  Count poll processing time toward cadence. None of this forces a hardware refresh.
- A reactive gap (a poll step >5 min), a non-monotonic clock, a missing counter or
  an implausible counter step in either direction re-anchors the estimator instead of
  attributing unobserved charge to a window. A flat counter reports 0 A only after
  10 minutes without movement; shorter flat stretches stay unavailable.
- Auxiliary counter-average acquisition can answer at >=15s if the counter moved >=3mAh monotonically;
  this movement threshold is a heuristic, not knowledge of the device's resolution.
  Once answered early, continue a growing-window average until the usual ~60s
  trailing window, never alternate valid/null just because the early gate closed.
  Otherwise wait for ~60s (flat counter zero policy above still applies). Reject
  implausible window-average rates. A short fast-poll step can release cached
  counter movement: judge its rate over >=5s, not as a guaranteed one-second surge.
- Handle Integer.MIN_VALUE/unsupported, missing extras, nonpositive voltage,
  invalid percent/temperature, provider exceptions. Zero current is NOT missing.
- A = µA/1e6, V = mV/1000, W = A*V. Never use charger ratings as battery watts.
- Counter-confirmed sign uses integrated evidence, never plug status alone.
  Startup may use platform status as an explicitly tentative heuristic, never proof. If the
  corrected current is clearly inconsistent (positive while unplugged/discharging),
  suppress watts; keep its reason in exported provenance, not a transient banner.
  Negative current with a connected charger can be real net discharge.
- Flow follows trusted net current; missing current may use reported status for
  the WORD only, never to manufacture watts. Sampling timestamp is query time,
  not proof the fuel gauge changed. Keep repeated readings rather than inventing
  fluctuations. No sysfs/root fallback.
- Samples carry wall-clock and elapsedRealtime milliseconds. Calculate durations,
  integration, gaps using monotonic time, not wall-clock adjustments.
- Gap when elapsed time between samples >max(30s,2.5*interval), nonpositive time,
  an explicit interruption, or cadence/mode changes. Do not integrate/draw across
  gaps or unavailable watts. Show resumed data as a new trace segment.

- Runtime estimate = remaining charge counter / recent counter-average magnitude,
  never instantaneous current. Charging countdown additionally needs observed
  capacity; manually entered design capacity changes health only. Label at recent
  average rate, not a promised completion time. Withhold when stopped/stale,
  missing the average, or below the 0.5mA policy floor.

### Shared analytics API (parent implements)

`SessionAccumulator(id: Long, startedAtMillis: Long,
 startedElapsedRealtimeMillis: Long,
 samplePowerWatts: (BatterySample) -> Double? = { it.watts })`;
`fun add(sample: BatterySample, intervalSeconds: Int): SessionSummary`;
`fun snapshot(endedAtMillis: Long? = null): SessionSummary`.

`fun summarizeWindow(samples: List<BatterySample>, windowSeconds: Int,
 intervalSeconds: Int,
 samplePowerWatts: (BatterySample) -> Double? = { it.watts }): SessionSummary`
and `fun isSampleStale(sample:
 BatterySample?, nowElapsedRealtimeMillis: Long, intervalSeconds: Int): Boolean`.

Integrate trapezoidally over valid, contiguous intervals. Split linear zero
crossings into out/in triangles, so absolute energy is not abs(net energy).
Observed time includes only eligible intervals; averages are time weighted.
Extrema use actual valid points, correct signed/magnitude ordering. New recordings
select liveWatts; the default raw selector preserves historical APIs/tests.
Missing coverage (including initial acquisition) stays missing. Samples list for UI is bounded, not all-day reloaded on
recomposition. First point alone has no observed duration/average.

## 5. Background lifecycle and lightweight constraints

- Promote to FGS immediately with a low-importance ongoing notification, then
  initialize/load storage on IO. SpecialUse type/permission and truthful subtype.
- Low impact: no wake lock, no exact alarms/boot receiver/WorkManager polling.
  Accept sleep gaps. FGS is not a guarantee against OEM/user process termination.
- High resolution: partial wake lock only while recording and explicitly enabled;
  use a timed lease renewed while service runs; release on mode change, stop,
  startup failure, cancellation, onDestroy. No bright/display wake lock.
- START_NOT_STICKY. On a cold load an unfinished saved session is labeled stopped/
  interrupted, with saved history preserved. Starting again creates a new session.
- One coroutine sampler, no busy wait. IO off main thread. Catch/report provider/
  database errors. Cancel jobs and flush/close safely on stop; avoid lifecycle
  races that could mark a still-running/restarted session stopped.
- User notification cadence: Instant (each sampled reading), 15s or 60s, default
  60s for new/old installs with no key. Persist it separately from poll cadence.
  Timed choices post on the first poll at/after the threshold, not a separate timer.
  Changing only this option refreshes the notification now without sampling,
  resetting estimators/calibration or making gaps. State/mode/error transitions
  bypass the throttle; Stop clears the notification. Android may defer presentation.
- UI observes only while started; no per-second UI clock or redraw animation.
  Recompute chart/stats only on data/window/scale changes, bound/decimate points to
  canvas width preserving first/last/min/max and gap boundaries.

## 6. Local durability and bounds

Framework SQLiteOpenHelper, private app storage; no external-storage permission.
Persist raw samples (plus current-source provenance: raw property value, charge
counter, derived rate/window, unit-correction flag, cycle count) and the
full-session summary/metadata. A single-row `capacity_state` table holds the
session-spanning weighted usable-capacity aggregate; it is written outside the
sample batch transaction and a failed write is recoverable, never fatal to
recording. Only latest session shown initially; retain at most 7 days OR
130,000 stored samples globally, whichever ceiling is reached first (~3 days at
2s, ~1.5 days at 1s). <=8192 recent UI points cover the 2h chart even at 1s. Pruning detail must NOT reset
full-session energy/summary. Expose `historyTruncated` when exporting/viewing
retained detail. Schema upgrades add columns/tables so session/sample history is never dropped.
Version 4 invalidates only the old capacity aggregate and adds an estimator-method
marker: an estimate from a different method cannot be relabelled as observed capacity. Batch at most 4 samples or 60s, flush on
Stop/export; at most that unsaved tail can be lost to abrupt kill. Persist session
start and first sample promptly. Restore all committed history/summary on cold
load as STOPPED; no invented continuation. Settings persist across launches.
Retention/flush logic must be bounded and serialized; no re-reading all database
history each sample. CSV uses retained history only and the UI says so, without
implying a full archived backup.

## 7. Tests and acceptance

1. JVM tests: units/sentinels/missing data/polarity, signed/magnitude extrema,
   integration including zero crossings, stale/reboot times, sleep gaps, cadence
   boundaries, weighted averages, formatting and chart decimation as applicable.
2. Build `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with JDK21,
   actual configured SDK; generate and retain the standard checksummed Gradle wrapper.
3. No theme/rotation/session settings reset; no second sampler on Activity recreate.
4. Inspect Compose layout for portrait + horizontal chart width, empty/unsupported/
   stale/charging/low battery, and readable text/minimum touch targets. Layout
   arithmetic that must not regress (toolbar rows, strip/plot/deck heights, label
   minimum width, chip budget, landscape decision) lives in a pure unit-tested
   helper (`LandscapeRules`) rather than in Compose measurement alone. Selected
   chips draw their underline with `drawBehind`, never a child `fillMaxWidth()`,
   which previously stretched one chip to the full row and pushed its siblings
   off-screen.
5. Device acceptance still REQUIRED before claiming background reliability or low
   overhead: screen-off/Doze, force-stop, kill/cold reopen, mode switch wake lock
   release, notification denial/direct stop, export and data recovery, repeated
   rotation, at least two OEMs where possible. Emulator is not telemetry proof.
6. Keep remaining hardware/accessibility/overhead limitations in Android README,
   not in user-facing implementation/profiling notes inside the UI.

Do not commit, publish releases, or upload user data as part of implementation.
