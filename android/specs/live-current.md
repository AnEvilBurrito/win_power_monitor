# Responsive instantaneous current: 0.7.0 contract

Supersedes the primary-metric/cadence parts of average-first-ui.md and the former
requirement to hide CURRENT_NOW until long counter calibration completes.
Desktop behavior is unchanged. No installation, upload or commit is requested.

## Primary readings

- Readout, chart, window statistics, new session totals and notification use
  normalized instantaneous CURRENT_NOW, never a counter/platform-average fallback.
- `liveAmps` accepts confirmed/manual INSTANT + currentCalibrated, or explicitly
  tentative INSTANT_ESTIMATED. Non-finite/missing values remain unavailable; actual
  zero is retained. Estimated is a confidence label, not a synthesized sensor value.
- `liveWatts` uses finite stored decoder watts only when live current is usable.
  Do not recompute suppressed voltage/polarity failures.
- `forLiveDisplay()` preserves raw properties, confidence/source and gap markers.
  Runtime still uses the auxiliary recent charge-counter average, not instant load.
- Saved counter calibration and fully manual interpretation work on the first poll.

## Five-poll startup check

- Start/reset requests five polls roughly one second apart. Count arriving readings
  in the existing fixed readiness line: Checking reading 1/5 through 4/5, then show
  numbers labelled Estimated when the heuristic supports an interpretation.
- Five polls span about four seconds from the first poll, not five independent
  hardware refreshes. Scheduling, failed polls and sleep can delay acquisition.
  The policy allows 100ms raw-timestamp jitter; the sampler still requests >=1s.
- Five agreeing nonzero magnitudes: abs(raw) 1..10000 suggests mA; >=20000 suggests
  µA. The 10001..19999 band is ambiguous. These are heuristics, not universal
  physical boundaries: tiny true µA currents or unusually large mA currents can
  fool magnitude inference. Never claim this proves sensor accuracy.
- Sign: manual override first, then coherent plausible counter direction, then
  expected discharge when unplugged/DISCHARGING, or expected charge for CHARGING.
  Charging status is explicitly a heuristic: plugged phones can net-discharge.
  Unknown/full/not-charging states use the documented standard convention as an
  assumption, not proof that positive-only firmware is correct.
- A five-zero window can show a real zero regardless of scale/sign. Its provisional
  convention is discarded on the first nonzero value and fresh inference begins.
- Missing/sentinel/implausible inputs do not count as zeros. Duplicate/too-close
  timestamps cannot fill the window; evidence never falls back to wall time.
- A completed ambiguous check becomes unavailable with a correction path, not an
  indefinite Calibrating. Background evidence collection can still resolve it.
- End the temporary burst at completion (including ambiguity), saved/manual
  readiness, unsupported NOW, or the 90s failure safety cap. Resume selected 1/2/5s
  cadence, default2s, without a setting mutation, timer or extra wake lock.

## Background validation, persistence and history

- Keep CurrentCalibrator's >=3 consecutive, non-overlapping >=15s/3mAh windows
  unchanged. It validates gauge consistency, not independent absolute accuracy.
  It continues after startup at the configured cadence and supersedes the estimate.
- Heuristics are ephemeral and never auto-saved as confirmed calibration. Automatic
  publication requires the counter-evidence threshold even if a guess is misrouted.
  Manual unit/sign independently override automatic choices. Reset revisions and
  failed-save retry protection remain intact; reset clears fast and counter state.
- Persist INSTANT_ESTIMATED in the existing source text column, with
  currentCalibrated=false. History restoration cannot upgrade its confidence.
  Startup poll counts are transient feedback, not stored measurement evidence.
- Changed effective unit/sign marks an integration/trace gap. Do not bridge the
  correction or rewrite earlier samples/totals. Same-interpretation confirmation
  changes confidence only and does not manufacture a gap.
- New CSV summaries use instant_interpreted and disclose possible estimated segments;
  retain instant_calibrated/average for known legacy summaries and stored_unknown
  for restored totals whose metric is not recorded. Raw properties stay available.

## UI and overhead safeguards

- Preserve synchronous FreshnessClock, freeze semantics, bounded adjacent numeric/
  unit group, actual-font suffix measurement, two-line runtime reservation, font
  scaling, chart-first landscape, batching, 130000-row/7-day retention and lifecycle.
- Notification confidence/availability transitions bypass its selectable Instant/
  15s/60s throttle; routine polling progress follows the selected notification rate.
- No animations, blinking, redraw timer or per-poll warning banners. Keep real
  recording/data-loss failures. Normal Diagnostics exposes progress/estimated/
  saved status and reset; raw values and manual controls stay in Developer mode.
- This is our own documented heuristic, not a verified copy of AccuBattery's private
  implementation. Its public guidance discusses unit/sign detection, not a guarantee
  of absolute battery accuracy. Full OEM/device and overhead acceptance is pending.
