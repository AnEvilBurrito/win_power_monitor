# Session power split by display state

Status: implemented (schema v6). Review artifact:
[design/screen-state-mockup.html](../design/screen-state-mockup.html).

The Session screen reports **discharge** power separately for screen-off and
screen-on time, so an idle-drain figure can be read without watching the chart and
without a charger inflating it. Nothing is inferred: a poll either recorded the
display state or it did not.

## Data path

| Step | Where | Contract |
| --- | --- | --- |
| Capture | `MonitoringService.readSample` | `PowerManager.isInteractive` as maintained from `ACTION_SCREEN_ON`/`OFF` (one query at start, not one per poll) is passed to `BatteryTelemetryDecoder.decode`. |
| Sample field | `BatterySample.displayOn: Boolean?` | `true` on, `false` off, `null` not recorded. Null is never read as "off". |
| Integration | `SessionAccumulator.add` | Each interval is booked to the display state of the sample that closes it: wall seconds always, and - only when a state is known - discharged energy plus the time spent discharging (from the same zero-crossing split). |
| Summary | `SessionSummary.display{On,Off,Unknown}{Seconds,WallSeconds}`, `display{On,Off}DischargeSeconds`, `display{On,Off}{Out,In}Wh` | Observed time, attributed wall time, discharging time, and energy per bucket. |
| Storage | `MonitoringStore`, `SchemaMigration.VERSION = 6` | `samples.displayOn INTEGER` (nullable, no default); ten v5 `sessions` split columns plus two v6 discharge-seconds columns, all `REAL NOT NULL DEFAULT 0`. Additive only. |
| Presentation | `screenSplitView` in `ui/Presentation.kt` | Pure, JVM-tested view model; `ui/SessionScreen.kt` only draws it. |
| Export | `CsvExporter` | Appended `display_on` column: `1`, `0`, or blank. |

## Invariants

1. `displayOnSeconds + displayOffSeconds + displayUnknownSeconds == observedSeconds`.
   A gap is unobserved time in a known state: it adds wall seconds, never observed
   seconds or energy.
2. `displayOnOutWh + displayOffOutWh == outWh` and the same for `inWh`, whenever
   `displayUnknownSeconds == 0`. Every session recorded by this build satisfies
   that; a pre-v5 session stores zeroes and is labelled, never recomputed.
3. The split never changes a session total. It is a second reading of the same
   energy.

## Attribution accuracy

A screen transition between two polls misattributes at most one interval (≤ 5 s,
1–2 s in high-resolution mode) — seconds against minutes of screen-off time.
Splitting on transition timestamps is a later refinement; it is not needed for the
figures to be honest, and the bound is stated in the accumulator's documentation.

## Presentation rules

- Bucket average is discharge-only: `outWh / dischargeSeconds`, the mean power drawn
  while discharging in that state. Charging is excluded from numerator and
  denominator, so a plugged-in session cannot report the charger's output as
  consumption. Always positive, so the global absolute/signed toggle does not apply.
- Bucket sub-line: discharging duration then discharged energy. Coverage below 50 %
  replaces the energy with `38m 00s of 4h 10m · low coverage` and adds an attention
  note naming that bucket. The doze/high-resolution sentence appears only for the
  screen-off bucket of a low-impact session. A bucket that only charged reads
  `charging only · no discharge measured`.
- Coverage is the only warning trigger. An observed-duration floor was removed after
  a device report flagged 3m 20s at 96 % coverage as "poorly sampled": a short bucket
  is not a badly sampled one, and its duration is already in the sub-line.
- An empty bucket shows `—` with a reason; its detail line reserves two lines so the
  rows below cannot shift. A session with no bucket data at all but non-zero elapsed
  time is pre-v5 history and says so. A v5 session has real out-energy but no stored
  discharging time, so it reports `discharge time not recorded` instead of an average
  on the wrong basis, and never "charging only".
- Coverage per bucket is discharging time divided by discharging plus missing time.
  Doze therefore lowers the screen-off coverage, while observed charging time is not
  counted against either bucket because it is known not to be drain time.
- Session totals keep both directions: Battery out / in still integrate the whole
  session, and the buckets' out-energy must add up to Battery out. The bucket
  averages are a drain comparison, not a second session average.

## Tests

`model/SessionAnalyticsTest` (bucket reconciliation, transition attribution, gaps,
unknown state, zero crossing within one bucket), `monitoring/SchemaMigrationTest`
(additive columns, null stays null), `monitoring/CsvExporterTest` (tri-state
column, appended index), `ui/PresentationTest` (averages, coverage, thin buckets,
doze hint scope, drain rows, legacy and empty states, scale toggle).

## Deliberately not included

Monitor-screen placement and chart screen-off bands were dropped in review. The
Monitor screen is unchanged; a window-scoped split would usually contain no
transition at all.
