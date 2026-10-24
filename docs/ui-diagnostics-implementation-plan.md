# Next-release UI and diagnostics implementation plan

Status: proposed implementation plan. No runtime features are implemented by
this document. No release version is assigned. Numerical defaults below are
starting proposals to validate with synthetic traces, not settled requirements.

## Implementation checkpoint

Implemented: named palettes and shared theme parsing; semantic UI colours;
scrollable structured diagnostics and a tiny-terminal fallback; cached derived
analytics with a 30-second minimum interval; per-battery design/health formatting.
The experimental idle proxy uses rounded whole-watt sample mode and averages
original readings within +/-1 W, requiring a result below the overall mean.
Existing ETA and serialized exports are unchanged.

Review artifact: `target/ui-review/themes.html`, generated from production
TestBackend buffers by `cargo test --test dashboard theme_review_artifact`.
It includes all themes, dashboard and scrolled diagnostics, and a narrow view.
The selected A layout now groups neutral current watts with a small flow accent,
uses a stable trace colour, shows electrical detail separately on roomy terminals,
and puts telemetry first in diagnostics. Current battery levels at or below 20%
use red plus a `low` label (text-only emphasis in mono). Stale percentages do not
trigger warnings. The review sheet includes low-battery and other state scenarios.
The developer approved the visual direction and rendered appearance on 2026-10-24.

Local Windows verification: full Rust tests, warning-free all-target Clippy, and
release build passed. An optimized 500,000-reading analytics calculation took
about 47 ms on this development machine; this is not a battery-life measurement.
The developer confirmed Linux hardware capacity reporting works well on
2026-10-24; this does not establish all-platform analytics validation. macOS
hardware capacity remains unconfirmed. Native release checks and real-workload
estimator tuning remain pending.
Specific analytics unavailable reasons still need implementation; the current
summary uses generic pending/history/capacity messages.
Ratatui's feature-gated rendered-line-info API is enabled
to clamp scrolling to actual wrapped content.

## Scope and compatibility

- Improve dashboard layout, text readability, and semantic colour usage.
- Add Tokyo Night, Tokyo Night Storm, Catppuccin Latte / Frappe / Macchiato /
  Mocha, and Nord alongside default, high-contrast, and mono.
- Show estimated idle power, average-consumption time remaining, and idle
  percentage in the `?` help / diagnostics overlay.
- Show per-battery design capacity and capacity-based battery health in diagnostics.
- Preserve signed acquisition and exports, current ETA selection, sampling,
  logging, tray behavior, and existing session/window statistics.
- Do not add system-idle detection, cycle-count features, new platform
  providers, or export schema changes in this scope.

## 1. Semantic colours and themes

Implementation surfaces: `src/cli.rs`, `src/config.rs`, `src/ui.rs`, and a new
`src/theme.rs` palette module registered in `src/lib.rs`.

- Define palette roles for background, foreground, secondary text, borders,
  axes, charge, discharge, mixed/unknown, warning, error, and selection.
- Replace hard-coded UI colours, including statistics labels and popup text,
  with those roles. Always retain textual state labels; colour alone must not
  communicate state. Unavailable values must remain readable, not disappear.
- Preserve the default theme's terminal-background behavior. Give named themes
  explicit backgrounds and style the entire frame and cleared popup consistently.
- Keep high-contrast and mono useful on limited-colour terminals. Document
  true-colour requirements for faithful named palettes and check fallback rendering.
- Extend `Theme` with stable kebab-case names. Unify accepted values across
  CLI, `WPM_THEME`, and TOML while preserving CLI > environment > file > default
  precedence and existing names. Environment parsing currently duplicates names.
- Use startup selection first; an interactive theme picker and saving preferences
  are deferred unless separately agreed. Do not change the default theme silently.

## 2. Dashboard and diagnostics layout

Preliminary alternatives and the proposed colour communication contract are in
[dashboard redesign mockups](dashboard-redesign-mockups.md), with a
[coloured review sheet](dashboard-redesign-mockups.html). Review these sketches
before changing the runtime layout; they are not production render artifacts.

Implementation surfaces: `src/ui.rs`, `src/app.rs`, `tests/dashboard.rs`.

- Organize the dashboard into source/live status, current power and battery
  summary, chart, compact statistics/capacity rows, and command/status footer.
- Keep the chart dominant; hide optional rows at short heights, truncate long
  battery/source text safely, and give tiny terminals a deliberate minimal view.
- Split the current large help paragraph into structured controls, derived
  metrics, sampling/source diagnostics, and per-battery diagnostics.
- Add scroll state and Up/Down, PageUp/PageDown, Home/End handling while help is
  open, with a visible position indicator. Preserve `?` close and `q` quit.
- Bound popup dimensions and scroll offsets after resize or content changes.
  All batteries and new metrics must remain reachable on short terminals.
- Keep program elapsed live during pause; telemetry and derived metrics use the
  frozen history snapshot. Opening help must not stop sampling or logging.

## 3. Shared diagnostic analytics

Implementation surfaces: a new `src/analytics.rs`, registered in `src/lib.rs`,
consuming `History.samples` from `src/model.rs`; render results in `src/ui.rs`.

- Return a typed summary with values, unavailable reasons, eligible sample
  count, covered duration, and effective window. Avoid embedding UI strings in
  the estimator and avoid extending serialized `Sample` for dashboard-only data.
- Use the latest contiguous eligible segment of the configured `--x-length`
  window, rather than historical session totals. Stop at gaps, transitions,
  source/kind, AC/state, or topology changes, invalid data, or non-increasing time.
- Battery analytics accept available, finite, strictly negative discharging
  flow and convert it to positive consumption. Charging, idle, mixed, unknown,
  and unavailable battery states are excluded, regardless of `--all-states`.
- Confirmed system-input power may support idle-power and below-average
  statistics using finite non-negative values, but never battery runtime.
  Do not combine system-input and battery-flow observations.
- Propose at least 10 eligible readings and 60 seconds of contiguous coverage
  before publishing estimates. A short configured history may remain unavailable;
  expose the reason instead of silently changing the user's history span.
- Stale live data suppresses estimates; paused data remains a clearly frozen
  snapshot. Reset clears analytics via `History::reset`; no sampler reset command
  is needed. Signed/absolute display toggles must not change calculations.
- Treat analytics overhead as part of the power-measurement budget. Cache the
  summary; never scan history, sort, or rebuild histograms on terminal redraws
  or on every incoming sample. Keep rendering read-only and inexpensive.
- Propose a minimum 30-second interval between analytics attempts, measured
  with a monotonic clock, and require changed history before recalculation.
  Compute lazily only while diagnostics is visible; reuse the existing event
  loop without a new timer thread, extra hardware polls, or background wakeups.
  Opening diagnostics may calculate immediately only if the throttle allows it;
  otherwise show cached results or a pending state. No catch-up bursts.
- Show the cached summary's observation timestamp / age. Cheap freshness and
  source/state/topology checks must immediately suppress incompatible results
  even between refreshes, without forcing a full calculation.
- Freeze the cached summary together with history on pause. If no matching
  summary exists, allow at most one lazy calculation of the frozen snapshot
  when permitted by the throttle; do not periodically recompute paused data.
  Reset clears the cache immediately, but does not bypass the refresh throttle.
- Bound storage and work by existing history limits. Benchmark typical and
  maximum supported histories before finalizing cadence; use bounded incremental
  aggregation if full scans exceed the agreed CPU budget. Apply the cadence to
  the new diagnostics only; leave existing ETA and acquisition timing unchanged.

### Estimated idle power draw

- Round each eligible positive consumption reading to the nearest whole watt
  (half watts round upward) and select the unique most frequent rounded value.
- Average the original, unrounded readings within an inclusive +/-1 W of that
  mode. Do not temporally average readings before mode selection; short bursts
  should not contaminate every temporal bucket.
- Reject tied modes and any non-finite estimate or estimate at or above the
  arithmetic mean of all eligible readings. Constant traces are unavailable.
- Retain the shared minimum of 10 readings and 60 seconds of coverage, along
  with the existing refresh throttle. No six-bucket requirement remains.
- Display as an idle-power proxy, not measured idle. Below-average consumption
  alone does not establish inactivity; real-workload validation remains needed.

### Estimated time remaining by average consumption

- Use time-weighted average discharge power over eligible contiguous intervals:
  integrated discharge Wh / covered hours. Reuse the existing trapezoidal
  integration convention without changing existing sample-average statistics.
- Compute seconds = current remaining Wh / average discharge W * 3600.
  Require finite non-negative current capacity, positive average power, adequate
  coverage, and a valid current discharging sample; reject overflow/non-finite results.
- Label separately as average-consumption ETA with its window. Do not replace
  `Sample.eta_s`, reported ETA, or the percentage-trend fallback.
- Missing capacity means unavailable, including current macOS acquisition.
  Do not infer Wh from percentage or silently substitute percentage-trend ETA.

### Idle percentage

- Follow the requested definition literally: 100 * eligible readings strictly
  below their arithmetic mean / eligible reading count, in the same segment.
- Compare positive consumption, not signed battery watts. Equality is not below;
  a constant trace therefore produces 0%, not 100% idle.
- Label as "Idle proxy: below-average readings" and display the eligible count.
  This is sample-count based, not a claim about time spent idle. Polling jitter
  and sustained high workloads can make this proxy misleading.
- Keep this arithmetic mean distinct from the duration-weighted ETA mean.
  Time weighting or a baseline threshold would be a different metric and needs
  an explicit definition change before implementation.

## 4. Battery design capacity and health

Implementation surfaces: `src/native_battery.rs`, existing provider adapters
(`src/sysfs.rs`, `src/macos.rs`), diagnostic formatting in `src/ui.rs`, and
existing native-battery, platform, multi-battery, and dashboard tests.

- Display design capacity in Wh alongside full-charge capacity and health for
  each battery, using its existing per-session index. Do not combine battery
  health into an unweighted system percentage.
- Define capacity-based health (%) = full-charge capacity / design capacity *
  100. This estimates retained capacity, not state of charge or a safety diagnosis.
- Validate existing raw `designed_capacity` and `full_charged_capacity` metadata
  before use. Reject unknown sentinels, zero denominators, and invalid readings.
  Absolute Windows capacities use mWh and require conversion to Wh; relative
  capacities must never be labeled Wh. A health ratio is usable only when both
  capacities have a documented matching unit and basis, including relative units.
- Keep validated UI derivation separate from serialized fields, preserving the
  current export schema. Do not depend solely on `full_charged_wh` for health,
  since relative-unit metadata may support a ratio without absolute energy.
- Preserve reported health above 100% with a neutral indication rather than
  silently clamping it. Factory tolerances and recalibration can produce this;
  avoid unsupported good/bad thresholds or alarm colours in the initial version.
- Reuse current acquisition paths and audit platform metadata mappings. Display
  unavailable with a reason when design/full capacity or compatible units are
  missing; do not invent macOS Wh from charge ratios or add acquisition APIs in
  this scope. Document actual platform support after that audit.
- Treat health/design metadata independently of discharge-only analytics:
  charging batteries can still have valid health. Suppress invalid, stale, or
  transition collections and never carry metadata across device identities;
  paused diagnostics retain their frozen snapshot.
- Replace the current help text saying health is out of scope when implemented;
  cycle counts remain out of scope. Test known ratios, above-100% health, unknown
  and zero values, relative units, partial metadata, charging, stale/transition
  states, detach/reattach, and multiple batteries with different health.

## 5. Implementation sequence and verification

1. Add semantic palettes and theme parsing; extend `tests/cli_config.rs` for
   every name, precedence, invalid inputs, and backward compatibility.
2. Apply palettes and dashboard layout; extend existing TestBackend coverage
   for wide, 80x24, narrow, short, and tiny terminals, every theme, light/dark
   backgrounds, stale/paused/transition states, and long multi-battery content.
3. Implement pure diagnostic analytics with focused tests for constant and
   known-distribution traces, irregular timing, warm-up, gaps, reset, topology,
   source/state changes, non-finite values, zero power, capacity absence, and
  numeric bounds. Use fake-clock/call-count tests to verify the refresh limit,
  no work while hidden, no redraw-triggered scans, pause/reset behavior, and
  immediate cache invalidation without recomputation. Measure computation cost
  on typical and maximum histories; do not infer battery-life savings from
  timing tests alone. Prototype and approve the idle-mode method in this step.
4. Validate and derive per-battery design capacity and health using existing
  metadata; extend provider/unit and dashboard tests before exposing the values.
5. Integrate metrics and scrollable diagnostics; verify overflow reachability,
   resize clamping, frozen snapshots, reset, and unchanged chart/ETA behavior.
   Add a focused event-state test if existing rendering tests cannot cover keys.
6. Update README theme/config examples and metric definitions; move release
   notes from planned to implemented only for completed features. Run formatting,
   clippy, full tests, and native platform CI before treating this as release-ready.

Acceptance: all planned themes resolve consistently; text remains readable and
layout content does not overlap; diagnostics remain reachable; fixture estimates
match specified calculations or explicit unavailable states; existing output,
sampling, pause/reset, and platform contracts remain intact. Manually inspect
true-colour and limited-colour terminals; TestBackend cannot prove appearance.

## Decisions before implementation

- Confirm initial theme list and startup-only selection scope.
- Approve analytics window/warm-up proposals and the distinct averaging rules.
- Confirm the proposed 30-second minimum analytics refresh interval and an
  acceptable measured CPU budget; avoid adding user-facing cadence settings
  unless there is a demonstrated need.
- Select the idle-mode method after the synthetic prototype, including behavior
  for broad or ambiguous distributions; do not present it as measured idle power.
- Confirm the explicit below-average proxy label and missing-capacity ETA policy.
- Confirm capacity-based health labeling and the policy of displaying, rather
  than clamping, valid reported ratios above 100%.