# Android UI study 01

Open **[mobile-mockup.html](mobile-mockup.html)** directly in a desktop or mobile
browser. No server, installation, internet connection, or build is required.

**This is an interactive design artifact, not the Android implementation.** Every
reading, aggregate, timestamp, session event, and notification is synthetic. The
browser never accesses battery APIs or starts background monitoring. These HTML
assets are not intended to ship in a WebView; the app remains native Compose.

## Try it

- The preview starts in **Landscape** (horizontal). Use **Phone orientation** to
  compare Portrait. Rotation preserves recording, the selected scale, and data.
- Use **Monitor**, **Session**, **Diagnostics** in the bottom controls. Landscape
  keeps navigation below the plot, not in a side rail.
- Toggle **Absolute scale** in the chart header or settings. On shows magnitudes;
  off shows negative discharge and positive charge. Watts/current, chart ticks,
  zero baseline, and window/session statistics change together. Signed min/max
  keep their numeric ordering. Energy-out/in totals stay separate positive values,
  raw diagnostics retain source signs, and CSV always uses signed values.
- Switch Tokyo Night Storm, Catppuccin Mocha/Latte, and monochrome palettes.
- Preview charging, low battery, stale data, and a missing current property.
- Change the chart window. The 2h view intentionally has blank space before the
  example session started; a sleep gap is never connected or treated as zero.
- **Freeze view** freezes dashboard telemetry/history, not background recording.
  Diagnostics and the notification remain outside that frozen view. Changing the
  review scenario while frozen demonstrates the distinction.
- Open settings: low impact is the default; high resolution requires explicit
  confirmation because keeping the CPU awake affects the measurement itself.
- Stop from the app (with confirmation) or directly from the notification;
  history remains visible. Starting again resets the preview to its example
  session, not a real new recording.
- Export a tiny CSV example. Its four rows are marked synthetic, use negative
  watts for discharge, and include a gap marker. It is not the full chart or a
  recomputation of the example statistics.

The sketches and summary numbers illustrate layout, not an integration test.
Changing an instantaneous reading scenario does not recompute session history.
The mock-up does not animate values or simulate continuous polling.

## Proposed information hierarchy

### Monitor: glance, then leave

1. Recording state is separate from measurement freshness.
2. Large, neutral watts plus an explicit direction word/arrow.
3. Battery percentage and voltage/current in a smaller supporting row.
4. One chart, with a stable cool trace regardless of the current flow direction.
5. Familiar window average/min/max, then a link to the session.
6. A brief explanation of the active background policy.

A fixed stop/start control, active monitoring-policy summary, and navigation
remain accessible while content scrolls. Portrait keeps the vertically stacked
layout. Landscape is a separate, chart-first instrument composition on an
880 × 452 reference phone:

1. A compact top strip combines watts, battery, flow/freshness, and window stats.
2. One toolbar groups the time window, absolute/signed scale, and freeze control.
3. The plot runs nearly edge-to-edge beneath it: **792px of an 880px screen**
   (90% of the device width), rather than a portrait-ratio chart in a side panel.
4. One slim bottom deck holds navigation, session context, mode, and Stop.

The landscape chart calculates its SVG coordinates from the full available plot
width/height; it does not retain a 336 × 180 portrait viewBox or stretch text to
fill a wide box. The dashboard fits without vertical scrolling in the reference
size. A narrow browser window can scroll sideways within the landscape review
stage; the page itself does not overflow.
This is a reference design, not an Android orientation lock or a finished native
layout across every handset size. Settings use a labeled button, not a hidden
swipe gesture.
Touch controls target at least 48 CSS pixels in the sketch (48dp in Compose);
selected chart-range chips are at least 44 CSS pixels wide. Labels accompany
icons; color is never the sole state signal. Small micro-labels and font scaling
still need native-device accessibility review before implementation.

### Session: trust the record

Elapsed time, separate battery-out/in energy, observed coverage, gap events, and
on-demand export. No daily score, decorative gauges, or unsupported health
percentages. The default session is 41m 06s with a 2m 30s gap: 38m 36s observed,
approximately 94% coverage, and illustrative 2.800 Wh out at 4.35 W average.

### Diagnostics: inspect the evidence

Provider, net-battery-flow scope, last poll age, raw units, and optional metadata.
Android's reported “Good” condition is explicitly not retained-capacity health.
Missing design/full capacity and runtime estimates stay unavailable.

A successful recent poll is not proof the hardware fuel gauge produced a new
reading. The implementation should track repeated values, property availability,
and per-field freshness where possible, rather than labeling every poll “fresh.”

### Notification: useful without opening the app

A compact recording status, timestamped last reading, Open, and Stop. The actual
layout is controlled by Android; the review card is only an illustration.
An ended session would remove its ongoing notification; the mock-up leaves a
stopped card visible so the ending state can be reviewed.

## Relationship to the terminal application

Palettes reuse roles and RGB values from [src/theme.rs](../../src/theme.rs).
Hierarchy follows the [terminal design review](../../docs/dashboard-redesign-mockups.md):
neutral numbers, monospace telemetry, a single stable trace, explicit flow words,
window/session statistics, gaps, and diagnostics separated from the main view.
Low battery adds both a red accent (except mono) and the word **low**. Routine
discharge is not an alarm. Missing current does not hide an otherwise valid
percentage/voltage; a wholly stale snapshot masks its current values.

Mobile changes are deliberate: a stacked portrait dashboard and a dedicated
full-width landscape plotting surface, named navigation, scrollable detail,
and explicit recording controls instead of keyboard shortcuts. A screen exit is not a service
stop. Background execution remains subject to Android/OEM policy and force-stop.

## User-facing copy boundary

The phone UI, settings, confirmation dialogs, and notification use intended
end-user language. Sampling help explains that shorter intervals use more battery
and Android may delay readings during sleep; it does not discuss profiling,
proposals, implementation status, or synthetic fixtures. Those caveats belong on
the outer review page and in this document, which continue to make clear that
this is a non-monitoring design artifact. No actual storage or service features
have been added by changing the preview's copy.

## Lightweight implementation guardrails

These are design constraints to validate, **not measured performance claims**:

- **No wake lock by default.** Low-impact mode accepts suspend and gaps. The 15s
  while-awake sampling target is a proposal to profile, not a guaranteed cadence
  or a claim of optimal battery usage.
- The service samples once; UI, history storage, and notification consume that
  shared result. Do not start a second polling loop for the dashboard.
- Observe/recompose only when visible. No per-frame redraw, smooth scrolling
  trace animation, blinking indicator, or UI refresh while the screen is off.
- Bound and decimate chart points to the visible pixel width; avoid rebuilding
  all-session history on every sample. Load other screens on demand.
- Batch bounded local writes; enforce retention. Export only on explicit action.
  Persistence and recovery are separate work, not implemented by this mock-up.
- Throttle routine notification refreshes independently of sampling; refresh
  important state changes promptly and keep reading timestamps visible.
- No network, ads, analytics SDK, cloud sync, or third-party charting framework
  needed for this layout. Use native text/layout and a small Compose Canvas.
- High-resolution mode must explain observer impact. Neither a foreground
  service nor a wake lock is a promise of uninterrupted execution.
- Measure overhead on real phones with the UI open and closed, screen off,
  and multiple cadences. Compare against a control run; do not invent a “0.1%”
  consumption claim or infer the app's own watts from whole-battery telemetry.

## Browser validation

Checked in Chrome 156 using an isolated, writable test profile:

- 55 checks covering navigation, scenarios, themes, freeze semantics, stop/start,
  wake-lock confirmation, sampling settings, CSV fixtures, offline loading,
  orientation persistence, absolute/signed transformations, zero baselines,
  min/max ordering, and the absence of developer-facing text inside app surfaces.
- Geometry checks require at least 90% device width for the landscape plot,
  no SVG letterboxing, readings above/navigation below the plot, and no vertical
  scrolling for the default reference dashboard. Repeated rotation preserves the
  current page, unique controls, and the original portrait structure.
- All three screens checked in both orientations at 320, 390, 768, and 1440px
  viewport widths (24 combinations): no page or internal app-content horizontal
  overflow; visible device buttons have at least 48px height. The landscape
  review stage intentionally scrolls sideways on narrow browser windows.
- Settings dialog fits all four widths. Browser screenshots cover portrait and
  landscape, signed flow, light/dark palettes, and user-facing settings.
- No page JavaScript errors or external page requests during the checks.

These checks do not validate native accessibility/font scaling, actual Android
background execution, battery telemetry, or the app's own energy overhead.

## Review questions

1. Does this still feel like WPM, without feeling like a terminal squeezed down?
2. Is watts → chart → statistics the right first-screen hierarchy?
3. Does the full-width landscape chart use the space effectively, with the
   readings strip above and controls below?
4. Is low-impact recording versus CPU-awake recording understandable?
5. Would you prefer denser information or larger text/fewer first-screen fields?

No Android runtime changes should follow until the basic hierarchy is approved.
