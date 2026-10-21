# wpm — TUI Design Spec

**Document:** 1 of 3 — visual and interaction design
**Companion docs:** `architecture-and-requirements.md`, `install-and-publishing.md`
**Status:** design reference; implemented display updates noted below

---

## Implemented display clarification

Magnitude is now the default for KPI watts/current, chart and statistics. `--signed`
selects the original signed convention; `--abs` explicitly selects magnitude and `a`
toggles the display. Exports always retain signs. Direction/state remains explicit.
The historical signed mockups below illustrate signed mode, not the default.

The five implemented bands are separated by thin horizontal rules, with no outer chart box. Narrow views omit volts/amps and
reduce statistics height. The Y range includes zero; each occupied side is padded by
10%, floored at 10 W and rounded outward to a 1/2/5 × power-of-ten endpoint. Empty
ranges use 0–10 W. Bounds persist and only expand when an actual reading exceeds
them; dropping old samples never shrinks the scale. Reset clears the scale. Signed
and magnitude modes retain independent bounds. Tick labels are lower/midpoint/upper values; the zero line is
independent, so magnitude ticks for 27.1 W are 0/25/50, never 0/0/30.

Remaining time is discharge-only: Windows reported runtime, otherwise capacity/rate
or percentage-trend estimates, explicitly labelled. Drain %/hr uses least squares
on up to five minutes of contiguous valid discharge percentages, at least two points
and ten seconds. Missing/gapped/charging/rising data resets the trend; flat slopes
are unknown. Time-to-full and health/cycles remain unavailable (shown as —). The battery gauge
and local HH:MM:SS clock-time X ticks are implemented.
Poll count/completion age/query time/unchanged counts are diagnostics, not a claim
that repeated readings prove firmware staleness. The tray uses 1–3 integer magnitude
digits and decimal signed tooltip readings, rather than miniature decimal glyphs.
The configured X span is fixed from session start to start + span while filling,
then rolls at full span. Pause freezes the viewport, trace, KPI and statistics,
without stopping sampling/logging/tray. Reset starts a fresh viewport at reset time.
Clock labels use a startup wall-clock/monotonic anchor (including across reset),
not the current wall clock or independently shifted sample timestamps. Help (`?`)
holds polling diagnostics, drain estimate and ETA provenance; the main bands stay clean.
These implementation clarifications supersede conflicting draft details below.

## 1. Purpose and scope

`wpm` is a terminal dashboard for Windows that shows live power consumption, battery
state, and rolling energy statistics. It is launched from PowerShell as `wpm` with no
arguments and is configured by flags (`--rate`, `--x-length`) or a config file.

In scope: real-time sampling, a single time-series chart, aggregate statistics, CSV/JSON
export, a default-enabled Windows notification-area wattage icon alongside the dashboard
(`--tray=false` disables only the icon), and graceful degradation on machines without a
battery. Tray behavior and lifecycle are defined in
[architecture §4.1](./architecture-and-requirements.md#41-windows-notification-area-system-tray).

Out of scope: per-process or per-component power attribution, long-term database
storage, historical backfill from `powercfg /batteryreport`, standalone GUI or tray-only
background mode, remote telemetry, cross-platform support.

---

## 2. Design principles

**P1 — One time-series, one place.** The chart is the only element permitted to render
history. Any new feature that wants to show data over time either feeds that chart or
does not ship. This rule exists because the first draft of this design had a sparkline
above a chart, both showing the same five minutes of wattage.

**P2 — Never fabricate a number.** A driver that returns nothing is *not* reporting
zero. Unknown values render as `—`, `n/a`, or a gap in the line. A flat zero line is
never drawn in place of missing data.

**P3 — Direction before magnitude.** The sign convention (negative = discharging,
positive = charging) is surfaced as a zero baseline plus `↓`/`↑` glyphs, so state is
legible without reading values or parsing color.

**P4 — Every band answers a different question.** Bands are ordered by time horizon:
now → over the window → aggregate → controls. No two bands answer the same question.

**P5 — Degrade, don't break.** Narrow terminals, absent batteries, and failed providers
all produce a reduced-but-truthful layout, never a crash and never a blank screen.

**P6 — The chart is the last thing to lose space.** Under width or height pressure,
chrome is shed in a fixed order (§9).

---

## 3. Layout anatomy

Five fixed bands, top to bottom. Only band C flexes.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ A  status bar                                                       1 row    │
├──────────────────────────────────────────────────────────────────────────────┤
│ B  KPI strip — current values only                                  1 row    │
├──────────────────────────────────────────────────────────────────────────────┤
│ C  chart — the single time-series                              flex, min 6  │
├──────────────────────────────────────────────────────────────────────────────┤
│ D  stats band — session and window aggregates                     2–3 rows  │
├──────────────────────────────────────────────────────────────────────────────┤
│ E  key hints                                                        1 row    │
└──────────────────────────────────────────────────────────────────────────────┘
```

| Band | Question answered | Fixed height | Content |
|---|---|---|---|
| **A** status bar | What am I, is it live | 1 | binary name, state, provider, rate, span, point count, run mode, help hint |
| **B** KPI strip | What is happening *now* | 1 | signed watts, volts, amps, battery gauge, charge %, ETA |
| **C** chart | What happened *over the window* | flex ≥6 | signed wattage vs clock time, zero baseline, gap markers |
| **D** stats band | How has it *behaved* | 2–3 | session + window aggregates, energy, drain rate, health, cycles |
| **E** key hints | How do I drive it | 1 | live keybindings only |

Frame chrome: horizontal rules only, no outer border, no title box. Rules are drawn in
`dim`; they separate bands without consuming width at the edges.

---

## 4. Default view (120 × 32, discharging)

The ASCII mockups below are layout sketches, not numeric chart fixtures. Where their
curve placement or labels conflict with §6, the signed rendering rules in §6 take
precedence: discharging plots below zero, charging above zero. Charging statistics
shown in §5.1 require `--all-states`; the default retains discharge-only statistics.

```
 wpm · Discharging · root\wmi · 5.0s · 5m00s · 60 pts · live            ? help
──────────────────────────────────────────────────────────────────────────────────
   12.40 W ↓        15.62 V        0.794 A      ██████████████░░░░░░░░  81%  2h 14m
──────────────────────────────────────────────────────────────────────────────────
   W │
  30 ┤                         ╭╮
     │                    ╭────╯╰╮
  15 ┤       ╭╮   ╭──╮  ╭─╯      ╰╮
     │    ╭──╯╰───╯  ╰──╯          ╰─╮
   0 ┼────╯                           ╰──────────────────────────────────────────
 −15 ┤ ░ charge
     └──────────────────────────────────────────────────────────────────────────
       12:09:38                                              12:14:38
──────────────────────────────────────────────────────────────────────────────────
  session    avg −11.80    min −23.10    max −4.20 W     energy 0.42 Wh
  window     avg −12.20    min −19.80    max −6.00 W     drain  9.4 %/hr
                                                         health 87% · 214 cyc
──────────────────────────────────────────────────────────────────────────────────
  q quit · space pause · r reset · e export · l log · ? help
```

### 4.1 Band A — status bar

Left segment: `wpm` · state · provider · rate · span · point count · run mode.
Right segment: contextual hint (`? help`, or `⏸ PAUSED` on the far right when paused).

- Provider is the name of the active wattage probe: `root\wmi`, `pdh`, `k32`, or `none`.
- Span is `--x-length` rendered as `5m00s`; point count is derived
  (`ceil(x_length / rate)`) and is diagnostic only — it is how a mismatch between typed
  flags and actual retention becomes visible without arithmetic.
- Run mode is `live`, `PAUSED <duration>`, or `degraded`.

### 4.2 Band B — KPI strip

Order is fixed: **watts → volts → amps → gauge → charge % → ETA**.

- Watts are signed, two decimals, with `↓` when discharging, `↑` when charging,
  and no glyph when idle or AC/full.
- Volts and amps are omitted together (never one without the other) below 100 columns.
- The gauge is 24 cells in monochrome block characters, scaled to charge %.
- During charging the ETA reads time-to-full; during discharge, time-to-empty; when
  unknown it reads `—`.
- Unknown values print `—`, never `0.00`.

### 4.3 Band C — chart

Y axis: signed watts, always includes 0, minimum 10 W total span.
Positive (charging) region uses the `charge` tint at low intensity as a background block;
negative region is unshaded (the rest state). Zero line is `dim` and full width.
X axis: two clock labels only — left edge and right edge of the window (`HH:MM:SS`, or
`HH:MM` below 100 columns). No gridlines, no legend, no title.

### 4.4 Band D — stats band

Two labelled rows plus a right-hand column of slow-moving facts.

| Scope | Window | Purpose |
|---|---|---|
| `session` | since launch, reset by `r` | O(1) incremental, matches the original PowerShell script's behaviour |
| `window` | last `x_length` seconds | describes current behaviour; recomputed over the ring each tick |

Statistics are **state-scoped to `Discharging` by default** — charging samples and
AC/full zeros are excluded so `max` means peak *draw*, not peak charge rate. `--all-states`
opts out.

Energy converts to `Wh in` while charging. Health and cycle count come from the battery
WMI classes and are static after first probe.

---

## 5. State views

### 5.1 Charging

Same shape, no layout switch. Energy label flips to `Wh in`, drain becomes `charge %/hr`,
ETA becomes time-to-full. The chart's positive region fills.

```
 wpm · Charging · root\wmi · 5.0s · 5m00s · 60 pts · live               ? help
──────────────────────────────────────────────────────────────────────────────────
    18.70 W ↑        16.41 V        1.139 A      ██████████████████░░░░░  74%  0h 38m
──────────────────────────────────────────────────────────────────────────────────
   W │
  30 ┤                                              ╭╮
     │                                        ╭────╯╰╮
  15 ┤                        ╭──╮  ╭╮   ╭──╯      ╰╮
     │      ╭╮   ╭╮  ╭╮  ╭──╯  ╰──╯╰──╯            ╰────
   0 ┼──────╯╰───╯╰──╯╰──╯                       ╮─────
     └──────────────────────────────────────────────────────────────────────────
       12:09:38                                              12:14:38
──────────────────────────────────────────────────────────────────────────────────
  session    avg +12.40    min −1.10    max +24.80 W     energy 1.08 Wh in
  window     avg +16.90    min +4.20    max +19.60 W     charge 21.3 %/hr
                                                         health 87% · 214 cyc
──────────────────────────────────────────────────────────────────────────────────
  q quit · space pause · r reset · e export · l log · ? help
```

### 5.2 Paused, with a sampling gap

Pause freezes only the displayed dashboard snapshot and dims the chart. Sampling,
age-based ring eviction, statistics, logging, and the tray remain live; resume displays
the latest snapshot without inserting an artificial sampling gap. Export while paused
uses the frozen displayed window. The status bar shows `⏸ PAUSED <duration>` at the right
edge and the footer swaps to the resume affordance. A real sampling gap
(suspend/resume, or a stalled provider, detected when `dt > 2 × rate`) renders `┊` at the
gap position and is counted in band D as `N gap(s) (total)`. The line is never drawn
through a gap.

```
 wpm · Discharging · root\wmi · 5.0s · 5m00s · 60 pts · ⏸ PAUSED 44s      ? help
──────────────────────────────────────────────────────────────────────────────────
   12.40 W ↓        15.62 V        0.794 A      ██████████████░░░░░░░░  81%  — paused
──────────────────────────────────────────────────────────────────────────────────
   W │
  30 ┤                         ╭╮
     │                    ╭────╯╰╮
  15 ┤       ╭╮   ╭──╮  ╭─╯      ╰╮
     │    ╭──╯╰───╯  ╰──╯          ╰─╮      ┊  ┊
   0 ┼────╯                           ╰─────┊──┊─────────────────────────────────
     └──────────────────────────────────────────────────────────────────────────
       12:09:38                                              12:14:38
──────────────────────────────────────────────────────────────────────────────────
  session    avg −11.80    min −23.10    max −4.20 W     energy 0.42 Wh
  window     avg −12.20    min −19.80    max −6.00 W     1 gap (2m 12s)
──────────────────────────────────────────────────────────────────────────────────
  space resume · r reset · e export · l log · ? help
```

### 5.3 No battery (desktop)

Empty axes with a stated reason, and probe results printed honestly. `--require-battery`
turns this into a hard exit with code 3 instead.

```
 wpm · Unsupported · no battery detected · 5.0s · 5m00s · 60 pts
──────────────────────────────────────────────────────────────────────────────────
   Wattage unavailable — no battery and no \Power Meter counter on this system.
   Retained for %/ETA if a battery appears; run  wpm --self-test  for details.
──────────────────────────────────────────────────────────────────────────────────
   W │
     │            no time series available
   0 ┼──────────────────────────────────────────────────────────────────────────
     └──────────────────────────────────────────────────────────────────────────
──────────────────────────────────────────────────────────────────────────────────
  provider: PDH ✗ (counter set absent)   WMI ✗ (no BatteryStatus)   k32 ✓
──────────────────────────────────────────────────────────────────────────────────
  q quit · ? help
```

### 5.4 Raw log tail (`l`)

A 4-row append-only tail beneath the chart, off by default. This is the **only**
permitted second scrolling region, and it does not duplicate the chart: it carries
per-tick state transitions, gap notices, and provider errors — things a line chart
structurally cannot express.

```
──────────────────────────────────────────────────────────────────────────────────
  12:14:20  Discharging  −12.40 W  15.62 V  0.794 A   81%
  12:14:22  Discharging  −12.60 W  15.61 V  0.807 A   81%
  12:14:24  Discharging  −24.80 W  15.44 V  1.606 A   81%   ← activity spike
  12:14:26  Discharging  −12.20 W  15.62 V  0.781 A   81%
  12:14:28  ┊ gap 2m 12s (suspend/resume)
──────────────────────────────────────────────────────────────────────────────────
```

---

## 6. Sign convention and color semantics

| Condition | Watts | Glyph | Chart region | Tint token |
|---|---|---|---|---|
| Discharging | negative | `↓` | below zero | `discharge` |
| Charging | positive | `↑` | above zero, tinted block | `charge` |
| AC / full (confirmed) | `0.00` | none | on the zero line | `neutral` |
| Idle (confirmed zero battery flow) | `0.00` | none | on the zero line | `neutral` |
| Unknown / unavailable | `—` | none | gap, no point plotted | `unknown` |

Color is never the sole carrier of meaning (P3): the glyph and the sign both encode
direction. `--theme mono` must leave the whole UI comprehensible with `--no-color`.

---

## 7. Keybindings

| Key | Action |
|---|---|
| `q` / `Ctrl+C` | quit (restores terminal, flushes log) |
| `space` | pause / resume sampling display |
| `r` | reset session statistics |
| `e` | export current window to `wpm-<timestamp>.csv` in CWD, confirm in status bar |
| `l` | toggle raw log tail |
| `?` | help overlay (single-page, dismiss with any key) |
| `w` | cycle chart window: `1m → 5m → 15m → 1h → --x-length` (display-only; does not change retention) |

Footer shows only keys currently valid (§5.2 footer differs from §4 footer).

`w` is display-only by design: retention stays at `--x-length` so cycling the window
never silently destroys data the user is watching.

---

## 8. Rendering rules

### 8.1 Two regimes

Because `--x-length` is a time span, sample count varies with `--rate` and can be
either side of the terminal width.

| Case | Example at 120 columns | Rule |
|---|---|---|
| samples > virtual columns | `--rate 0.25` → 1200 pts | envelope bucket per column, preserving min/max so single-tick spikes survive |
| samples ≤ virtual columns | `--rate 5` → 60 pts | plot samples directly, no bucketing |

Implementation: a Braille `Canvas` at 2×4 sub-cell resolution (240 × 120 virtual pixels
at 120 × 30), so upsampling is native and the envelope pass runs only when sample count
exceeds the virtual column count. One code path, two behaviours.

Interpolation is **linear** by default; `--step` selects sample-and-hold for users who
prefer the chart not to imply continuity between ticks.

### 8.2 Axis rules

- Y range includes 0 always; minimum total span 10 W; headroom 10% above max magnitude.
- Ticks: 3–5 labels, halving in count as height shrinks. Labels are actual watts.
- Zero line spans the full plot width and is never omitted, even when all data is
  one-signed.
- X labels: window edges only. Never a label per tick (unreadable at 0.25 s rates).

### 8.3 Gap rules

A gap is recorded when `dt > 2 × rate`. Gaps break the line, render `┊`, are excluded
from energy integration, and are counted in band D. Gaps are not interpolated.

---

## 9. Responsive breakpoints

Shed in this exact order when space is short.

| Trigger | Action |
|---|---|
| width < 100 | drop V/A from band B; right-align gauge |
| width < 80 | shorten band A (drop provider and point count); X labels to `HH:MM`; footer to glyphs |
| width < 60 | drop band D to two lines; drop the cycles figure |
| height < 24 | band D collapses to one line (`avg/min/max/energy`) |
| height < 20 | band D hidden entirely; chart keeps its 6 rows |
| height < 14 | refuse to draw; print `error: terminal too small (min 60×14)` and exit 2 |

The chart is the last element to lose space and the first to be restored.

---

## 10. Anti-patterns explicitly rejected

| Rejected | Why |
|---|---|
| Sparkline above the chart | two views of one series; the original design flaw |
| Legend box | single-series chart; a legend is pure overhead |
| Menu bar / tab strip | five bands already; chrome competes with data |
| Modal dialogs | `?` help is the only overlay, single page, any-key dismiss |
| Auto-scaling Y that starts at the minimum | makes a 6 W idle look like a flatline or a crisis |
| Plotting `0` for missing samples | violates P2; creates fake calm |
| Per-tick X labels | illegible below 10 s rates |
| Silent stats blending charging and discharging | makes `max` mean the wrong thing |

---

## 11. Open questions

1. Should `w` cycling the display window also *reduce* retention, freeing memory at high
   rates? Current answer: no — display-only, keep the invariant simple.
2. Should band B show instantaneous `d(W)/dt` as a trend arrow? Deferred until real data
   shows it is legible.
3. Theme token set for a light-background terminal — needs a real 16-color pass.
4. Whether `e` should export the visible window or the full retained ring; currently the
   visible window.
