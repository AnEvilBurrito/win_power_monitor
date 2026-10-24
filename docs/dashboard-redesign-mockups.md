# Dashboard redesign: preliminary review

Status: design proposals, not production renders or new runtime behavior.
The initial design spec supplies the single-chart, distinct-band structure;
current measurement and platform contracts take precedence over its historical
charging ETA, health acquisition, and platform assumptions.

Open [the coloured review sheet](dashboard-redesign-mockups.html) for illustrative
terminal sketches. All readings are synthetic. Character plots show placement,
not fixture-accurate curves. These proposals do not change application code.

## Communication hierarchy

1. Current watts and explicit flow direction: the primary glance target.
2. Live/frozen/unavailable state and battery percentage: contextual truth.
3. One power chart: history, not a second live indicator.
4. Window/session statistics and per-battery capacity: comparison and detail.
5. Provider, polling, health, derived estimates: diagnostics, not dashboard noise.

Keep labels and units lower emphasis than values without making them illegible.
Use aligned columns, whitespace, and weight before adding colour or separators.
Reserve inverse/background selection for an actually focused control, not a
decorative LIVE badge. No coloured panels, heatmaps, or battery-gauge rainbow.

## Colour contract

| Role | Use | Do not use for |
| --- | --- | --- |
| Foreground | Routine numeric values, current watts, capacity | Indicating an alarm by itself |
| Secondary | Labels, units, timestamps, key hints | Live values that need inspection |
| Border | Sparse rules and chart scaffolding | Primary text or a prominent axis tint |
| Trace | One stable, theme-mapped cool accent for measured power | Changing the entire chart when the latest state changes |
| Charge | Small charging word/arrow accent | Whole KPI strip or historical trace |
| Discharge | Small discharging word/arrow accent, distinct from warning | Treating ordinary discharge as a fault |
| Attention | PAUSED, transition, or stale status with explicit wording | Routine discharge or a merely low estimate |
| Error | Actual acquisition/logging failure, with a reason | Mixed flow, unknown capacity, or unvalidated health thresholds |
| Low battery (red) | Known current battery percentage at or below 20%, paired with `low` | Whole KPI strip, trace, stale values, or battery health |
| Selection | Focused item in a future interactive menu | All section headings |

Colour is redundant with words/signs. Mono keeps the same hierarchy using weight,
spacing, arrows, and labels. Themes map these roles, not independent ornaments.
Charging/discharging are operating states, not good/bad judgments. Mixed flow
gets a neutral `MIXED` label unless an actual collection problem requires attention.

Named themes must keep readable foreground/background contrast: target at least
4.5:1 for meaningful text and 3:1 for trace/scaffolding needed to read data.
Do not achieve hierarchy by barely visible text. Default terminal colours are
user-controlled; inspect light/dark terminal profiles and mono separately.
Health is neutral text, including above-100% values; no invented health alarm bands.

## A. Chart-first bands (recommended)

Use two current-value rows on roomy terminals; one compact row at 80 columns.
Only a state word/arrow and the trace receive routine accent colour.
The header drops point count/rate/span because they already belong in diagnostics
or the chart/window context. A label above the trace states its scope explicitly.

```text
 wpm   LIVE   Battery IOCTL                                       ? diagnostics

 12.40 W  v DISCHARGING                  Battery 81%   ETA ~2h 14m
 2 batteries                            15.62 V       0.79 A
 -----------------------------------------------------------------------------
 Power / magnitude W                                      window 5m
 30 |                      /\
 15 |       /\____/\______/  \___
  0 |_______                    \_______________________________
    12:09:38                                               12:14:38
 -----------------------------------------------------------------------------
              avg        min        max W
 Window      12.20       6.00      19.80
 Session     11.80       4.20      23.10     out 0.420 / in 0.000 Wh
 bat 1: 18.0 / 23.0 Wh; bat 2: 30.0 / 45.0 Wh
 Program 00:35:20    stats 00:35:20
 q quit   space pause   r reset   e export                       ? diagnostics
```

The drawing is abbreviated vertically; the chart occupies all flexible rows.
Retain existing min/max semantics and signed/absolute toggle, not a new claim
that magnitude min/max are positive in signed mode. ETA keeps its provenance
marker; it is discharge-only and is not a time-to-full estimate.

## B. Dense instrument strip (alternative)

Use a single current-value row even on wide terminals, preserving one extra
chart row. This stays closer to the current dashboard but makes state and scope
more explicit. Less whitespace, more scanning across the row.

```text
 wpm  LIVE  Battery IOCTL                                         ? diagnostics
 12.40 W  v discharge | 81% | ETA ~2h 14m | 15.62 V | 0.79 A | 2 bat
 -----------------------------------------------------------------------------
 Power / magnitude W                                      window 5m
 30 |                      /\
 15 |       /\____/\______/  \___
  0 |_______                    \_______________________________
    12:09:38                                               12:14:38
 -----------------------------------------------------------------------------
 Window  avg 12.20  min 6.00  max 19.80 W
 Session avg 11.80  min 4.20  max 23.10 W    out 0.420 / in 0.000 Wh
 bat 1: 18.0 / 23.0 Wh; bat 2: 30.0 / 45.0 Wh
 Program 00:35:20    stats 00:35:20
 q quit   space pause   r reset   e export                       ? diagnostics
```

## Compact and state rules

- At 80x24, A collapses electrical detail into diagnostics and keeps one KPI row.
- Around 60x14, omit extra rules, electrical detail, and energy. Keep watts,
  flow, percentage, chart, window/session values, elapsed time, and help access;
  long capacities use a bounded line with the full list in diagnostics.
- At very small sizes, prioritize state and watts, then help/quit. Do not replace
  a valid reading with an alarm solely because the terminal is small.
- PAUSED freezes telemetry/chart/analytics with a visible frozen label; program
  elapsed remains live. Do not dim the chart below readable contrast.
- Stale: current watts/electrical/percentage/ETA/capacity become unavailable;
  retained chart history stays visible, labeled historical. No line through gaps.
- Charging: label charge, keep the trace accent stable, no time-to-full claim.
- Mixed: explicit MIXED, combined net flow stays labeled; no combined ETA.
- System input: label verified system-input scope; omit battery gauge/capacity/ETA.
- Do not recolour historical samples according to the latest flow state. Signed
  charts retain a visible zero baseline and the existing independent axis bounds.

## Diagnostics proposal

Use short sections, aligned values, one scrollable surface, and a visible range.
Keep controls concise and put telemetry first, rather than opening with a long
instruction paragraph. Preserve keyboard scrolling and existing close/quit keys.

```text
 + Diagnostics ----------------------------------------------------- 1/28 +
 | Measurement                                                          |
 | Source       Battery IOCTL      Scope     combined net battery flow   |
 | Poll         #420               Age       1.2 s                       |
 | Battery 1                                                            |
 | Remaining    18.0 Wh            Full      23.0 Wh                      |
 | Design       26.0 Wh            Health    88.5% retained capacity      |
 | Battery 2                                                            |
 | Remaining    30.0 Wh            Full      45.0 Wh                      |
 | Design       50.0 Wh            Health    90.0% retained capacity      |
 | Derived estimates   observed 12:14:30 / age 8s / coverage 300s          |
 | Idle proxy   ~8.5 W             Below mean readings  62%               |
 | Average ETA  ~3h 56m            Scope     contiguous discharge         |
 | Proxy estimates are not measured system-idle detection.               |
 | ...                                                                  |
 | arrows/PgUp/PgDn scroll   Home/End   ? close   q quit                    |
 + -------------------------------------------------------------------- +
```

Unavailable values carry a short reason, not a warning tint by default. Expose
the estimator's window and age; never make cached values appear live. Capacity
and health are per battery, not an unweighted combined percentage.

## Review gate before runtime edits

Choose A or B, then review discharge/charge/mixed/stale/paused/system-input in
80x24 and 60x14, mono, a dark theme, and Latte. Verify line lengths, absence of
overlap, stable numeric columns, actual theme contrast, chart height, and complete
diagnostics reachability with TestBackend fixtures. Generate fresh production
render artifacts after implementation; the attached coloured sheet is only a
design sketch. No selection, extra refreshes, or new metric calculations are
required to render these layouts.