# Refresh jitter: supplied-video analysis and clock fix

## Evidence

Analysed the user-supplied recordings under `app/build/outputs/apk/debug/` using
ffprobe and ffmpeg, not inferred from a still screenshot:

- `1000028225.mp4`: 1066×496, duration 8.917s, variable frame rate. Main readout
  repeatedly alternates between valid ~1.77–1.86W / 47% and `Stale —`, dashes,
  `Average unavailable`, `No recent sample from the recorder`. This is not merely
  different-width digits. The recording indicator stays active.
- `1000028226.mp4`: 1048×712, duration ~5.484s. Runtime alternates between a valid
  estimate and `No recent reading`; Readings status simultaneously becomes
  `Average unavailable`. The runtime's two-line estimate collapses to a one-line
  message, moving everything below it.
- In frames extracted at 4.5s and 5s from the second video, horizontal rules are at
  y=2/110/218/644 and y=2/110/253/679 respectively: the lower content moves **35px**,
  while the upper rules stay put.

Local analysis artifacts (not committed/uploaded): `.test-uses/video-jitter/`.
Contact sheets sample at 2fps, left-to-right/top-to-bottom; individual frames also
preserve the original video resolution.

```bash
ffprobe -v error -show_entries format=duration:stream=width,height,avg_frame_rate -of json app/build/outputs/apk/debug/1000028225.mp4
ffmpeg -i app/build/outputs/apk/debug/1000028225.mp4 -vf "fps=2,scale=640:-2,tile=3x6" -frames:v 1 .test-uses/video-jitter/calibration-contact.png
ffmpeg -i app/build/outputs/apk/debug/1000028226.mp4 -vf "fps=2,scale=600:-2,tile=3x4" -frames:v 1 .test-uses/video-jitter/runtime-contact.png
```

## Matching code defect

`rememberMonotonicMillis()` previously returned a cached clock tick and scheduled
`now = SystemClock.elapsedRealtime()` in `LaunchedEffect(latestElapsedMillis, ...)`.
The effect runs AFTER the composition triggered by a new sample. That composition
can therefore see:

```
cached now = 100000ms
new sample = 101000ms
computed age = -1000ms -> stale
```

`isSampleStale()` correctly rejects negative ages (future-dated history after a
reboot must not look live). The error was supplying an old clock as the current
frame's time. A later effect then refreshed the clock and reversed the stale
state, producing two opposing UI states per poll. Both monitor and runtime used
this same clock, explaining their simultaneous transitions in the recordings.

## Fix in 0.5.1-dev / code 6

- Read the monotonic clock synchronously in the composition that consumes the
  sample snapshot, shared by all live freshness decisions.
- Keep the coarse, lifecycle-gated ticker only to request age/staleness redraws
  when samples stop. Remove the sample-keyed clock effect and its redundant
  second composition.
- Do NOT clamp time to the latest sample or remove the negative-age guard:
  genuinely future-dated history and genuinely old data remain stale.
- Reserve at least two lines for the Diagnostics runtime value so its normal
  two-line estimate and one-line waiting/stale states do not cause the observed
  row collapse. Longer accessibility text may still grow; it is not truncated.
- No changes to calibration evidence, sampling cadence, wake locks or persistence.

## Verification and remaining check

`FreshnessClockTest` replays arrivals between clock ticks at 1/5/15/30s cadences,
initial acquisition, runtime updates, true stale expiry, recovery, future-dated
history and frozen-monitor/live-diagnostics separation.

Automated tests are not a replacement for a new device video. Recheck both views
with the patched APK, including initial calibration and several minutes of normal
updates. Other OEM/rendering issues cannot be ruled out by these recordings alone.
