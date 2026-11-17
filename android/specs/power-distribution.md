# Power distribution (histogram) view: 0.8.0 contract

Ports the desktop distribution added in `f696503` (`src/histogram.rs`, rendered by
`src/ui.rs`) to the Android Monitor tab. Nothing in the measurement contract
changes: [live-current.md](live-current.md) still governs which watts a sample
carries, this document only counts those samples into bins instead of drawing
them over time. Desktop behaviour, Kotlin measurement code, storage and CSV stay
as they are. The companion artifact is
[../design/histogram-mockup.html](../design/histogram-mockup.html), which uses
synthetic data for design review only. No installation, build or commit is
requested by this document.

## 1. Scope

- One new read-only presentation of samples the app already holds. No new
  telemetry property, permission, sampler, table, column or dependency.
- The line chart is not removed and remains the default. The distribution is a
  second view of the **same** plot area, chosen with one control (§5.2).
- New pure-Kotlin files only: `ui/PowerDistribution.kt` and its JVM test
  `ui/PowerDistributionTest.kt` (§8). Names follow the existing
  `ChartModel.kt` / `ChartModelTest.kt` convention rather than a `*Model`
  suffix.
- Compose touch points are limited to `ui/PowerChart.kt` (a sibling composable),
  `ui/MonitorScreen.kt` (portrait slot + toolbar control), `ui/WpmDashboard.kt`
  (landscape slot), `ui/WpmViewModel.kt` (the selected view) and
  `ui/LandscapeRules.kt` (the toolbar width budget, §5.2).
- The distribution is never computed for `DashboardTab.SESSION` or
  `DashboardTab.DIAGNOSTICS`, and never for a stopped-and-empty history.

## 2. Constants ported verbatim from `src/histogram.rs`

Every number below is a contract value. The Kotlin port must use these exact
values, and `PowerDistributionTest` must pin them.

| Constant | Value | Rust source | Meaning |
| --- | --- | --- | --- |
| `MIN_SAMPLES` | `8` | `src/histogram.rs:13` | Below this many counted readings there is no distribution; the line chart stays. |
| `MIN_BINS` | `6` | `src/histogram.rs:15` | Fewer bins hide structure. |
| `MAX_BINS` | `14` | `src/histogram.rs:16` | More bins turn single samples into full bars. |
| `LOG_SUPPORT_RATIO` | `10.0` | `src/histogram.rs:20` | A log axis needs a decade of support. |
| `QUANTUM_DECIMALS` | `3` | `src/histogram.rs:23` | Meter increment is inferred at 10^-3 W resolution. |
| `NICE_PERCENT` | `5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 100` | `src/histogram.rs:25-29` | Readable percent ceilings. |
| `STEP_LADDER` | `1, 1.5, 2, 2.5, 3, 4, 5, 7.5, 10` × 10^k | `src/histogram.rs:265` | Nice bin-width ladder; the 7.5 entry keeps a 0.68 W width at 0.75 W instead of flattening to 1 W. |
| log decade ladder | `0.05, 0.1, 0.2, 0.25, 0.5, 1.0` | `src/histogram.rs:243` | Log bin ratio in decades. |
| Freedman–Diaconis width | `2 · IQR · n^(-1/3)` | `src/histogram.rs:194` | Target width before ladder/quantum snapping. |
| FD fallback width | `(high - low) / 9` | `src/histogram.rs:195` | Used when the IQR is zero or non-finite (a run of repeats). |
| step-walk bound | `16` iterations | `src/histogram.rs:205` | Terminates the coarser/finer ladder walk. |
| percent of a bin | `100 · count / n` | `src/histogram.rs:93-95` | Denominator is **every counted sample**, never the window's total sample list. |
| ceiling rule | first `NICE_PERCENT ≥ peak`, else `100` | `src/histogram.rs:154-158` | Axis top. |
| log step label | `"log-spaced"` | `src/histogram.rs:112-120` | Header text for a log axis. |

## 3. Population: which samples are counted

### 3.1 Source list

- The population is built from the **live-display projection** already in
  `DashboardScope.liveSamples` (`ui/WpmDashboard.kt:137-146`), i.e. the calibrated
  instantaneous `watts`/`flow` of [live-current.md](live-current.md). The raw
  sample list stays untouched for export, diagnostics and the counter average.
- The distribution is scoped to the **selected chart window** (5m / 30m / 2h), not
  the whole session. Justification in §6.1.
- Because `liveSamples` derives from `monitorState`, a frozen view bins the frozen
  snapshot automatically (§6.3).

### 3.2 Eligibility (mirrors `src/analytics.rs:55-70` + `src/histogram.rs:69-79`)

A sample enters the population when **all** hold:

- `gapBefore == false` — a gap marker is not a measurement.
- `flow == FlowState.DISCHARGING` — the analogue of Rust's
  `state == Discharging && watts < 0`. Charging, `IDLE` and `UNKNOWN` never enter.
- `watts` is non-null and finite, and its magnitude is `> 0`.
- The sample's **interpretation identity** equals the newest sample's identity,
  where identity is the pair `(currentSource, currentCalibrated)`.

The binned value is the discharge **magnitude** `abs(watts)`, so a bin edge is a
positive wattage in both display scales.

### 3.3 Android adaptations of the identity/topology rules

- Rust excludes `sample.source`, `measurement_kind` and `topology_epoch` mismatches
  against the newest sample (`src/histogram.rs:69-79`). Android has no
  `topology_epoch`: one `BatteryManager` serves one battery, so a meter swap inside
  a session cannot happen and no epoch field is invented.
- The Android analogue of "a different meter identity" is a changed current
  interpretation. `currentSource` distinguishes confirmed/estimated/legacy-counter
  provenance, and `currentCalibrated` distinguishes established from tentative.
  Mixing them would put two different quantities on one axis, exactly what the
  Rust comment rejects.
- An interpretation change is already recorded as a `gapBefore` marker
  (live-current.md: "Changed effective unit/sign marks an integration/trace gap"),
  so the gap rule and the identity rule usually agree. Keeping both means a legacy
  row with the same timestamp ordering but no gap flag still cannot leak in.
- `MONITOR` is a net-battery-flow instrument: `BatterySample.liveWatts` is already
  battery flow, never charger input (`LiveMeasurement.kt:11-28`), so no
  system-power case needs porting.

### 3.4 Excluded count

- `excluded` counts every sample in the window that failed §3.2 — charging, idle,
  unknown, missing-watts, gap-marked and foreign-interpretation samples.
- `n` is the counted population; `n + excluded == liveSamples.size`, which the test
  pins.

## 4. Bin construction (port of `src/histogram.rs:131-259`)

1. If `population.size < MIN_SAMPLES` (8) → **no distribution** (§5.4). The same
   gate is re-applied after dropping non-finite/non-positive values
   (`src/histogram.rs:132-138`).
2. Sort ascending, compute `quantum` = greatest common divisor of adjacent
   differences at 10^-3 W resolution (`src/histogram.rs:331-348`); a single
   distinct value yields `0.001` W exactly as `src/histogram.rs:345-347`.
3. Log switch: `low > 0 && high / low >= LOG_SUPPORT_RATIO (10.0)` → log edges.
4. Linear path: `target = max(Freedman–Diaconis, quantum)`, `nice_step(target)`,
   then walk the step ladder up to 16 times — coarser when the bin count exceeds
   `MAX_BINS`, finer when below `MIN_BINS` (never finer than `quantum`) — then snap
   `step = round(step / quantum) · quantum` and clamp the bin count to `6..14`.
5. Log path: pick the first decade step whose `ceil(span / step) <= MAX_BINS`,
   start at `floor(log10(low) / step) · step`, clamp bins to `6..14`, edges
   `10^(start + step · i)` so every edge shares one ratio.
6. The first edge is rounded **down** (`floor(low / step) · step`) so the first bar
   is never half-empty; edges cover the data instead of starting at zero.
7. Counting clamps a value to `[0, bins-1]`, so a reading exactly on the last edge
   lands in the last bar (`src/histogram.rs:168-189`).
8. `percent(i) = 100 · count(i) / n`; `peak = max(percent)`; `top_pct` = first
   `NICE_PERCENT` entry `>= peak`, else `100`.
9. `step = edges[1] - edges[0]`; the step label is the trimmed decimal text of the
   step for a linear axis, or `"log-spaced"`.

## 5. Presentation

**Status: implemented.** The pure model is `ui/PowerDistribution.kt` with
`ui/PowerDistributionTest.kt` (21 cases, fixture copied from `src/histogram.rs`); the
view is `DistributionChart` in `ui/PowerChart.kt`; the switch is `ChartViewToggle` in
`ui/MonitorScreen.kt`; the selection is `WpmViewModel.chartView`. One deviation from
§5.3 is recorded there. `testDebugUnitTest`: 560 tests, 0 failures.

### 5.1 Placement: replace the line chart in place

- Recommended and specified: the distribution **replaces the line chart inside the
  existing plot area**, exactly like `src/ui.rs:562-566` ("it answers a different
  question about the same samples, so the layout stays put"). No new screen, no
  dialog, no navigation destination.
- Rationale: portrait already spends its vertical budget on a fixed 184dp plot
  (`LandscapeRules.PORTRAIT_PLOT_HEIGHT_DP`) and landscape on a chart-first
  composition with a 120dp minimum canvas
  (`LandscapeRules.PLOT_MIN_HEIGHT_DP`). A separate screen would need its own
  window controls, freeze affordance and statistics, and would make the same
  numbers reachable two ways. In-place keeps one instrument.
- The replacement keeps the same outer `modifier` and therefore the same height,
  the same 44dp axis gutter (`AxisWidth`, `ui/PowerChart.kt:39`) and the same
  axis+legend budget (`LandscapeRules.AXIS_AND_LEGEND_HEIGHT_DP`). Nothing below
  the plot moves.
- Portrait slot: the `ChartArea` call in `ui/MonitorScreen.kt:151-166`.
  Landscape slot: the `ChartArea` call in `ui/WpmDashboard.kt:370-380`.
- The view is **not** offered on Session or Diagnostics.

### 5.2 The switch: one labelled toggle, not a third chip

- A single `ChartViewToggle` control in the chart control row:
  - label `"Distribution"` while the line chart is shown, `"Line chart"` while the
    distribution is shown;
  - `heightIn(min = 48.dp)` (touch target), `widthIn(min = 88.dp)`, `10.sp` label,
    `maxLines = 1`, `softWrap = false`;
  - `Role.Switch`-style semantics via `toggleable(role = Role.Switch)` plus
    `contentDescription = "Distribution view"`, so the label change is announced
    as a state change and colour is never the only signal;
  - tint follows the existing convention: `colors.trace` when the distribution is
    active, `colors.muted` otherwise (same as `FreezeButton`,
    `ui/MonitorScreen.kt:597-640`).
- Landscape: placed in `ChartFullToolbar` between the range chips and the scale
  toggle, in both the one-row and two-row branches (`ui/MonitorScreen.kt:443-523`).
- Portrait: placed in `RangeToolbar` between the chips and the freeze button when
  the row fits; otherwise on its own 48dp row under the chips (`RangeToolbar`
  becomes a `Column` in that case). Portrait already scrolls
  (`ui/WpmDashboard.kt:283-300`), so the extra row is safe.
- Pure width budget, decided by `LandscapeRules` rather than by measurement:
  - new `VIEW_TOGGLE_MIN_WIDTH_DP = 88`;
  - `controlsWidthDp()` becomes `rangeChipsWidthDp() (148) + 88 + SCALE_TOGGLE_WIDTH_DP (126) + FREEZE_CONTROL_WIDTH_DP (82) + 4 · CONTROL_SPACING_DP (24) = 468` (was 374);
  - `oneLineToolbarMinWidthDp()` becomes `LABEL_MIN_WIDTH_DP (132) + 468 = 600` (was 506), still below `TWO_ROW_TOOLBAR_BELOW_WIDTH_DP (720)`, so the two-row fallback still covers every narrower landscape window and no label can be squeezed;
  - new `toolbarToggleOwnRow(availableWidthDp: Int): Boolean = availableWidthDp < rangeChipsWidthDp() + VIEW_TOGGLE_MIN_WIDTH_DP + FREEZE_CONTROL_WIDTH_DP`, i.e. `availableWidthDp < 318`. At a 320dp-wide portrait with the 18dp side padding (284dp) the toggle takes its own row; at 360dp (324dp) it shares the row.
  - `LandscapeRulesTest` gains assertions for both new numbers and for
    `oneLineToolbarMinWidthDp() < TWO_ROW_TOOLBAR_BELOW_WIDTH_DP`.

### 5.3 Header line

The header is one single-line text, mirroring `distribution_title`
(`src/ui.rs:199-217`). Bin width uses the magnitude-aware display unit (§5.6), so a
milliwatt-scale phone reads `250 mW wide`, not `0.25 W wide`.

**Implementation note (deviation).** The header is drawn inside the plot area, in a
13dp band at the top of the canvas, rather than in the caption slot above it. The
caption slot already carries the frozen/stopped/stale wording that §5.3 requires to
stay (`chartCaptionText`), and growing the landscape toolbar by a third line would
break the fixed chrome budget in `LandscapeRules`. A band inside the canvas costs no
layout height, keeps the caption's meaning intact, and the bars start below it so the
header never overlaps data.

- Tiers, longest first. Draw the first tier whose measured width fits the caption
  slot (measure with `rememberTextMeasurer`, the same technique the unit slot uses
  in `ui/MonitorScreen.kt:202-212`); `maxLines = 1`, `softWrap = false`,
  `TextOverflow.Ellipsis` only as the last resort:
  1. `Distribution · n=60 · 9 bins · 0.75 W wide · 6 excluded`
  2. `Distribution · n=60 · 9 bins · 0.75 W wide`
  3. `9 bins · 0.75 W wide`
  4. `Distribution · n=60`
- The exclusion count is the first thing dropped, then the `Distribution` prefix:
  `n` and the bin width are the numbers that make the shape honest, and they
  survive every tier. This is the Android form of the desktop's column-count rule
  (`src/ui.rs:213-215`), which drops the exclusion count under 80 columns.
- Log axis: `Distribution · n=60 · 10 bins · log-spaced` (the width is replaced, not
  abbreviated; a log axis has no single width to state).
- Below the gate, population non-empty:
  `Distribution · collecting 3/8 readings`
- Population empty with exclusions (e.g. a charging session):
  `Distribution · no discharge readings yet (6 excluded)`
- Population empty without exclusions: `Distribution · collecting 0/8 readings`

Frozen/stopped/stealing wording stays on the existing caption line above the plot
(`chartCaptionText`, `ui/MonitorScreen.kt:740-755`); the distribution header never
duplicates it.

### 5.4 Empty and short states

- `n < MIN_SAMPLES`: draw **no bars**. The plot area shows the existing
  placeholder style (`"No readings in this window"` at 11sp, `colors.muted`) with
  the text `Needs 8 discharge readings`, and the header states
  `collecting N/8 readings` where `N = population.size`. This is the desktop's
  `Power / … · collecting {}/{} for a distribution` wording
  (`src/ui.rs:580-587`) adapted to the Android caption.
- Population empty because everything was charging: placeholder
  `Charging readings are excluded` — waiting quietly is the honest answer, and the
  `(6 excluded)` count shows the toggle did something (`src/ui.rs:572-580`).
- No fabricated zero bars, no skeleton animation, no spinner.

### 5.5 Bars, axis and labels

- Y axis: percent of counted samples. Three labels in the 44dp gutter —
  top `top_pct`, middle `top_pct / 2`, bottom `0` — each `"%`-suffixed and
  rounded to a whole percent (`40%`, `20%`, `0%`; an odd ceiling such as 15 shows
  `15%` / `8%` / `0%`, which is exactly the terminal's `{:>3.0}%` formatting), 9sp,
  `colors.muted`, matching `distribution_lines` (`src/ui.rs:239-256`).
- Three faint rules at the same positions, plus a `colors.muted` rule at the zero
  baseline; same alpha values as the line chart (`ui/PowerChart.kt:172-180`).
- Bars are drawn on the Compose `Canvas` in `colors.trace` — same colour role as
  the trace, so the histogram reads as the same instrument. No per-bar colour
  variation: the data is one series.
- Bar geometry: a 1-2px gap between bars when
  `plotWidthPx >= binCount * 3`, dropped below that (the desktop drops the gap
  when it cannot spare the columns, `src/ui.rs:229-234`). Minimum bar width 2px so
  a 14-bin plot on a narrow phone still draws something.
- Empty bins draw **nothing** — the hole in the silhouette is data, and the Rust
  test explicitly pins an empty bin for the reference session
  (`src/histogram.rs:420-434`).
- X labels: the **lower edge** of each bin, thinned so labels never collide. One
  label per `DISTRIBUTION_LABEL_SLOT_DP = 44` of plot width:
  `shown = clamp(floor(plotWidthDp / 44), 1, binCount)`, then
  `index = round(slot * (binCount - 1) / (shown - 1))` (the desktop thins the same
  way by character cells, `src/ui.rs:275-288`). The first label aligns to the left
  plot edge and the last to the right edge. Labels are 9sp `colors.muted`,
  `maxLines = 1`, `softWrap = false`. At the 390dp portrait width (~298dp of plot)
  that is 6 labels for 9 bins; at 880dp landscape it is all 9.
- Edge label text is the trimmed decimal text of the edge at the display unit's
  required precision (§5.6). It is **not** `Units.axisTick`: that helper collapses
  to one decimal above 10, which would print 3.0 / 3.8 / 4.5 for 0.75 W bins and
  silently misplace the reader. A 0.75 W step needs two decimals, so `3`, `3.75`,
  `4.5`.
- Legend line under the plot (same 16dp row the clock labels use): left
  `Bars · % of counted discharge readings`, right `n=60 · 6 excluded` or
  `n=60 · all counted`. Explicit words, so the meaning never depends on colour.

### 5.6 Units and magnitude formatting

- One unit for the whole histogram, chosen once from the final edges:
  `Units.axisUnit(edges.first(), edges.last())` — the same rule the chart uses
  (`ui/ChartModel.kt:133-139`, `ui/Units.kt:125-127`). Mixed units across one axis
  would be unreadable.
- Decimals per label come from the ported `decimals(step)` + `trimZeros`
  (`src/histogram.rs:301-320`) applied to the **display** step
  `step · unit.perWatt`: a 0.75 W step → 2 decimals → `3`, `3.75`, `4.5`; a 250 mW
  step → 0 decimals → `0`, `250`, `500`; a 0.001 W step in µW → `0`…`1000` is
  prevented by the unit choice, not by extra decimals.
- **Log axes label by significant digits, not by the edge difference** (Android
  change, verified on a phone). A log axis has no constant step, so the desktop's
  difference-based rule prints `0.0631 / 0.1585 / 0.3981 / 1 / 2.5119 / 6.3096` —
  four decimals of noise under a 44dp axis. Log labels therefore keep two
  significant digits, trimmed: `0.063`, `0.16`, `0.4`, `1`, `2.5`, `6.3`. Linear
  axes keep the step rule above, where one width really does describe every label.
  The tooltip reuses the same labels, so it stays terse too; the header still says
  `log-spaced` because there is no single width to state.
- The header bin width uses the same pair: `0.75 W wide`, `250 mW wide`.
- The percent axis is unit-free and never scaled.

### 5.7 Touch behaviour

**Implemented.** Tapping the plot selects the bar under the finger: its span, count and
share appear as text (`4.5–5.25 W · 18 readings · 30%`), the bar is outlined and drawn
in the text colour, and tapping the same bar again clears the selection. The
selection is dropped when the population changes, so it can never describe a
different bin after a new poll or a window change.

- The **whole plot** is the touch target (it is always ≥48dp tall), so this does not
  introduce the sub-48dp hit area the earlier draft of this document rejected.
  Accuracy at the edges is irrelevant: [binIndexAt] clamps out-of-range taps to the
  first/last bar instead of doing nothing.
- Accessibility: the selected detail is also the plot node's `stateDescription`, and
  an accessibility *activation* cycles through the bins (`Show next bin`), so a screen
  reader user never has to aim at a ~20dp bar. Every number remains visible as text
  in the header, the percent axis, the tooltip and the legend.
- The hit test is a pure function in the model (`binIndexAt(xFraction, bins)`), unit
  tested for every bar index rather than inferred from pixel geometry.

## 6. Interaction with existing controls

### 6.1 Window selector

- The distribution uses the **selected window**, unlike the desktop
  implementation, which bins the whole in-memory history
  (`src/histogram.rs:48-66` called with the full `History`). Android-specific
  reason: the window chips (5m/30m/2h) are a visible, persistent scope control on
  this screen (`ui/MonitorScreen.kt:545-565`); a chip that silently means nothing
  in one of the two views is worse than a documented difference. The bounded
  window also bounds the sort (§9).
- The chips stay **enabled** while the distribution is shown and change the
  population immediately; the header's `n` and the bin width are re-derived.
- The population is scoped like `buildChartSeries` does
  (`ui/ChartModel.kt:63-70`): samples in
  `[latest.elapsedRealtimeMillis - windowSeconds·1000, latest]`.

### 6.2 Absolute vs signed scale

- The distribution is **unaffected**: it counts discharge magnitudes only, exactly
  like the desktop, where `consumption()` already returns positive watts
  (`src/analytics.rs:55-70`) regardless of the `a` toggle.
- The toggle therefore stays enabled and operative (it continues to change the
  readout, the line chart and the statistics). It is not disabled or hidden while
  the distribution is shown, because a control that looks broken is worse than one
  with no effect on this view; the legend's `counted discharge readings` wording
  states the scope.

### 6.3 Freeze

- A frozen view bins the frozen snapshot: `liveSamples` derives from
  `monitorState` (`ui/WpmDashboard.kt:137-146`), so no averaging, gap or decoding
  path is bypassed and no new frozen field is added.
- Freezing while the distribution is shown therefore leaves the bars unchanged and
  only changes the caption to `View frozen · recording continues`, exactly as the
  line chart behaves today.
- Resuming re-derives the population from live samples; no stale memo may survive
  because the `remember` key includes the sample list (§9).

### 6.4 Rotation, theme and settings changes

- Rotation keeps the selected view: it is ViewModel state (§7.1), like the tab,
  the window and the freeze snapshot (`ui/WpmViewModel.kt:33-48`).
- Theme changes re-colour the bars and labels only; the bins are
  theme-independent, mirroring `PreparedHistory` being "independent of theme,
  terminal size, and viewport" (`src/ui.rs:133-134`).
- A change of polling interval (1/2/5s) marks a gap but changes no bin, because the
  quantum is inferred from the values, not from the interval
  (`src/histogram.rs:331-348`). The population changes only through
  the sample list itself.
- A change of calibration/interpretation (§3.3) splits the population; the header's
  `excluded` count makes the split visible instead of silently mixing units.
- `Start`/`Stop`: stopping leaves the last snapshot's population on screen with the
  existing `Stopped · history retained` caption. A new session starts with a fresh
  sample list, so `collecting 0/8 readings` is the honest first state.

## 7. Persistence, export and privacy

### 7.1 Transient view state only

- The selected view is a new `WpmViewModel` field (`var chartView by
  mutableStateOf(ChartView.LINE)` with a `selectChartView(...)` mutator), so it
  survives configuration change and dies with the process. It is **not** added to
  `MonitorSettings`, `SettingsStore`, SQLite or the schema.
- Reason: it is a way of looking at data, not a measurement preference; the
  desktop also starts in the line view on each launch
  (`ViewKind::default() == Line`, `src/ui.rs:96-107`), and every stored setting in
  this app so far changes what is recorded or measured.

### 7.2 CSV export: no change

- `CsvExporter.COLUMNS` (`monitoring/CsvExporter.kt:42-74`) is per-sample provenance.
  A distribution is a derived, transient re-count of columns that are already
  exported (`watts`, `amps`, `flow`, `gap_before`, `current_source`,
  `current_calibrated`).
- Adding histogram rows or a sidecar summary would create a second, unreplayable
  summary format whose numbers can be recomputed from the file with the §2
  constants; and the UI cannot honestly export bins for a window the user picked
  without inventing an export scope the file does not have.
- The per-bin rule is fully specified in §2-§4, so a reader (or a future script)
  reproduces the exact bars from the CSV. No header, no column, no metadata line
  is added, and the existing metadata lines stay byte-identical.

## 8. Pure-Kotlin boundary and test matrix

New `ui/PowerDistribution.kt`, Android-free (no Compose, no `android.*`, no
`context`), mirroring `ui/ChartModel.kt`. As built:

```kotlin
const val DISTRIBUTION_MIN_SAMPLES = 8
const val DISTRIBUTION_MIN_BINS = 6
const val DISTRIBUTION_MAX_BINS = 14
const val DISTRIBUTION_LOG_SUPPORT_RATIO = 10.0
const val DISTRIBUTION_QUANTUM_DECIMALS = 3
const val DISTRIBUTION_LABEL_SLOT_DP = 44
val DISTRIBUTION_NICE_PERCENT = doubleArrayOf(5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 75.0, 100.0)
val DISTRIBUTION_STEP_LADDER = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0)

enum class DistributionScale { LINEAR, LOG }
data class DistributionPopulation(val values: List<Double>, val excluded: Int)
data class PowerDistribution(edges, counts, scale, n, topPercent)

fun distributionPopulation(samples: List<BatterySample>): DistributionPopulation
fun distributionWindow(samples: List<BatterySample>, windowSeconds: Int): List<BatterySample>
fun buildDistribution(population: DistributionPopulation): PowerDistribution?
fun PowerDistribution.bins(): Int
fun PowerDistribution.percent(index: Int): Double
fun PowerDistribution.peakPercent(): Double
fun PowerDistribution.step(): Double
fun PowerDistribution.axisUnit(): PowerUnit
fun PowerDistribution.binLabel(index: Int, unit: PowerUnit): String
fun PowerDistribution.widthLabel(unit: PowerUnit): String
fun PowerDistribution.accessibilityDescription(): String
fun distributionHeaderTiers(distribution: PowerDistribution?, population: DistributionPopulation): List<String>
fun distributionPlaceholder(distribution: PowerDistribution?, population: DistributionPopulation): String?
fun distributionLabelIndices(binCount: Int, plotWidthDp: Int): List<Int>
fun percentLabel(percent: Double): String
fun distributionFor(samples: List<BatterySample>, windowSeconds: Int): PowerDistribution?
```

`ui/PowerDistributionTest.kt` must contain at least these cases. Names in
parentheses are the Rust tests they mirror (`src/histogram.rs:359-580`).

- `percentagesDivideByEveryCountedSample` — `percentages_divide_by_every_counted_sample`: percentages sum to 100, `counts.sum() == n`, `n == 60` for the fixture.
- `theReferenceSessionLandsOnReadableBins` — `the_real_session_lands_on_readable_bins`: copy the 60 discharge readings of `src/histogram.rs:374-387` verbatim into the test as the porting oracle, then assert `scale == LINEAR`, `bins() == 9`, `step() == 0.75`, `edges[0] == 3.0`, `counts == [2, 21, 18, 8, 5, 3, 2, 0, 1]`, `binLabel(0) == "3"`, `binLabel(1) == "3.75"`, `binLabel(2) == "4.5"`, `peakPercent() == 35.0`, `topPercent == 40.0`. The empty 8th bin is data, not a rendering bug.
- `aTwoAndAHalfTimesSpreadStaysLinear` — `a_two_and_a_half_times_spread_stays_linear`: a 2.5× span stays linear and every edge is finite.
- `binsAreFewEnoughToReadAndEdgesAreEvenlySpaced` — same name: `bins in 6..14`, one constant linear step across all edges, `counts.sum() == n`.
- `aLogAxisNeedsADecadeOfSupport` — same name: 60 readings `2.0 · 1.08^i`; `scale == LOG`, `edges[1]/edges[0] == edges[2]/edges[1]`, `counts.sum() == n`. Pinned expected values from the ported algorithm: 10 bins, ratio `10^0.2 ≈ 1.5849`, first edge `≈ 1.585`, counts `[3, 6, 6, 6, 6, 6, 6, 6, 6, 9]`, `peakPercent == 15.0`, `topPercent == 15.0`.
- `chargingReadingsAreExcludedRatherThanBinned` — `charging_readings_are_excluded_rather_than_binned`: append charging samples; `population.size` stays 60, `excluded == 3`, and no value reaches a charging magnitude.
- `gapsAreExcludedAndAnInterpretationChangeStartsANewPopulation` — `gaps_are_excluded_and_a_meter_change_starts_a_new_population`: a `gapBefore` sample is excluded; a newest sample with a different `(currentSource, currentCalibrated)` leaves only that sample in the population and excludes the rest.
- `coarseMetersForceStepsThatAreMultiplesOfTheirIncrement` — same name: 60 readings `10.0 + (i % 7) / 10.0`; `step / 0.1` is an integer within `1e-6`.
- `identicalReadingsStillGetAReadableAxis` — same name: 20 readings of exactly 5 W; `counts.sum() == 20`; `edges.last() > 5.0 && edges.first() <= 5.0`.
- `aShortRunReportsNoDistributionYet` — same name: `MIN_SAMPLES - 1` readings → `buildDistribution(...) == null`.
- `zeroAndNonFiniteWattsAreDropped` — `src/histogram.rs:136`: `0.0`, `NaN`, `+Inf` never reach a bin and do not count as excluded-and-counted twice.
- `niceCeilingIsNeverBelowThePeak` — `NICE_PERCENT` walk: peak 35 → 40, peak 40 → 40, peak 41 → 50, peak 100 → 100; every drawn bar is `<= topPercent`.
- `widthLabelFollowsTheDisplayUnit` — Android-specific: a milliwatt fixture (e.g. 60 readings spread `1.2e-3 .. 3.4e-3` W) yields `axisUnit() == MILLIWATTS` and `widthLabel(...)` containing `mW`; a watt fixture yields `W`; `binLabel` never prints more decimals than the display step needs and drops trailing zeros.
- `windowScopeKeepsOnlyTheVisibleSpan` — Android-specific: `distributionWindow`
  keeps only `[newest - windowSeconds·1000, newest]`, exactly like
  `buildChartSeries` (`ui/ChartModel.kt:63-70`), so two readings older than a 5m
  window do not reach `n`; and `distributionPopulation` on that result reports
  `n + excluded == window size`.
- `distributionDoesNotDependOnTheAbsoluteToggle` — Android-specific: signed `-4.2` W discharge samples and their absolute copies produce identical edges and counts.

`WpmViewModelTest` gains two cases: the selected view survives
`@Configuration`-style state recreation (the ViewModel keeps it) and defaults to
`LINE`; selecting a tab does not reset it.

`LandscapeRulesTest` gains the §5.2 assertions.

## 9. Accessibility and performance

- Accessibility:
  - the plot is one node with `contentDescription = "Power distribution: 60 discharge readings, 9 bins, 0.75 watts wide, peak 35 percent"` (values from the model, never hard-coded copy);
  - the toggle has a 48dp target and announces its state via `Role.Switch`;
  - percent axis, bin edges, `n`, exclusions and the legend are all text, so no
    information depends on colour or on a hover/tap gesture;
  - font scaling: the plot keeps its fixed height; landscape already switches to a
    scrolling fallback above `LandscapeRules.MAX_LAYOUT_FONT_SCALE` and portrait
    already scrolls, so a larger font cannot clip the header into an ellipsis
    without the legend still carrying `n`.
- Performance:
  - computed inside `remember(…)` on the sample list (already window-scoped by
    `DashboardScope.liveSamples`), so it runs once per polled list change — never per
    frame, never on theme/rotation/freeze-tick recomposition. The distribution
    composable only exists while it is the selected view, so switching views disposes
    and recreates it instead of needing `chartView` as a `remember` key.
    This follows the existing rule "recompute chart/stats only on data/window/scale
    changes" (`initial-implementation.md:264`);
  - the population is bounded by the visible window and by
    `MonitoringRepository.MAX_UI_SAMPLES = 8192` (`MonitoringRepository.kt:820`),
    i.e. at most 7200 samples at a 1s cadence over 2h; sorting that is a few
    milliseconds, the same order as `buildChartSeries`;
  - `remember` is keyed on the sample list instance, which is already re-created
    once per poll by `DashboardScope.liveSamples`; the distribution adds no new
    flow, coroutine, timer or allocation per frame;
  - no decimation of the population: binning *is* the reduction, so the sort must
    see every eligible sample. If a device measurement ever shows the sort on the
    composition thread above ~4ms, the fallback is to move
    `buildDistribution` into a `produceState(Dispatchers.Default)` keyed the same
    way — a documented escape hatch, not a requirement.

## 10. What is not ported, and what is not changed

Not ported (deliberately):

- The desktop TUI layout, box drawing, `█` bar cells, terminal-width thinning of
  the caption (`src/ui.rs:199-217` hide-below-80-columns rule) and the `g`
  keybinding (`src/app.rs:545-548`). Android uses one labelled toggle instead of a
  keyboard shortcut.
- Terminal palettes and any theme-specific histogram colours: the palette roles
  from `ui/WpmTheme.kt` are reused unchanged.
- The desktop's whole-history scope (§6.1).
- `measurement_kind`/`topology_epoch` (§3.3); Android has no equivalent state and
  none is invented.

Not changed:

- Measurement, calibration, sampling, gap detection, storage/schema, retention,
  notification, CSV format and the network-free build.
- The line chart, its window chips, its decimation, the freeze snapshot, the
  absolute/signed toggle, and both existing compositions' heights.
- Any user-facing wording that already describes the line chart; the distribution
  introduces its own header and legend strings only.

## 11. Review questions

Answered and implemented:

1. **Window-scoped population (§6.1): accepted.** The chips are a persistent scope
   control on this screen, so they keep meaning in both views.
2. **One labelled toggle, not a two-segment `Line | Distribution` control: accepted.**
   It costs 88dp of the toolbar budget instead of a segment pair, and the label names
   the view the tap will switch to.
3. **Exclusion count: may drop first on narrow widths.** Tiers 2-4 keep `n`, bin count
   and bin width, which are the numbers that make the shape honest; the legend repeats
   the exclusion count on every width anyway.
