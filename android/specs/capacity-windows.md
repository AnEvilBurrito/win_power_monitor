# Observed capacity: charge or discharge windows

This supersedes the charging-only, 60-percentage-point capacity policy in the
historical measurement-refactor contract. Desktop behavior and runtime calculation
are unchanged.

## Required behavior

- Accept a monotonic charging **or discharging** window spanning at least **20
  percentage points** (inclusive), e.g. 20 -> 40 or 80 -> 60. A 19-point window
  reports progress only. This is an absolute percentage-point change, not 20% of
  the starting percentage and not a fixed amount of mAh.
- Capacity in mAh = `abs(counter delta in uAh) / 1000 * 100 / abs(percent delta)`.
  Both nonzero changes must have the same direction. Counter or percentage may
  individually stay flat between updates, but cannot reverse within a window.
- Determine direction from counter/percentage movement, with explicit unplugged
  state allowing discharge only and calibrated flow providing a direction hint.
  Unknown plug/flow and uncalibrated instantaneous current must not block a valid
  window. A plugged phone may discharge. No CURRENT_NOW calibration is required
  for a counter/percentage observation.
- Re-anchor on direction changes, conflicting counter/percentage movement,
  conflicting trusted direction, non-positive elapsed steps, gaps over five
  minutes or implausible current. Clear on missing/invalid values, gapBefore or
  recording reset. Never bridge charge/discharge legs or recording gaps.
- Judge each counter batch over the observed elapsed time since the last accepted
  counter change, or the current window anchor before its first change. Successful
  polls with an unchanged counter must not advance that clock. These are observed
  query times, not a guarantee of the hardware's exact refresh time.
- Keep the 5-second reference floor for that batch-rate check, the 10 A rate bound,
  the full-window rate check over actual elapsed time, and the 100..30,000 mAh
  plausibility range. For example, a 15.588 mAh batch after 10 seconds must not be
  judged as an 11.22 A surge merely because the last poll was 5 seconds ago.
  Percentage 0..100 and nonnegative counters are valid; flat/zero counters never
  fabricate a capacity.
- Clear/restart the batch clock with every window reset/re-anchor, including a
  completed window. Accepted nonzero movement advances it immediately, so later
  batches cannot reuse earlier flat time. Preserve recording gap flags and their
  existing thresholds; this does not relax screen-off sampling continuity.
- Complete/re-anchor at the endpoint so subsequent windows never reuse charge.
  Progress and observation weight use the absolute percentage-point span.
- Keep swing-weighted aggregation and existing durable observations. Earlier
  60-point charging observations measure the same quantity and remain valid;
  no database migration, method-marker change or history rewrite is needed.
- Health remains observed capacity / user-entered design capacity * 100. The
  single-reading gauge estimate stays separate. Preserve storage throttling and
  all existing recording/calibration/runtime behavior.
- UI/help must say charging or discharging and the 20-point requirement. Counts
  describe capacity windows, not charging windows or polls. CSV basis remains
  `observed_charge_counter_swing`, with accurate documentation of both directions.

## Verification

Cover exact/short/overshooting thresholds in both directions, direction and trusted
state changes, neutral/missing calibration, independent counter/percentage resets,
missing values, gaps, discontinuities, bounds, cached counter steps, zero endpoints,
non-overlap, positive weights, legacy aggregation compatibility and health UI.
Exercise 10-15 second counter batches at 1/2/5 second polling in both directions,
the inclusive 10 A boundary, genuinely excessive batches after flat polls, and
batch-clock resets/consumption at every window boundary. Keep real device CSVs
local; synthetic regressions must not depend on their presence.
Physical accuracy and OEM background behavior still need device acceptance.
