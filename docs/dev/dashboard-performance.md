# Dashboard rendering performance

## Scope

The dashboard previously redrew after every 100 ms event poll. Each frame scanned
history to expand axes, rebuilt chart point vectors, and recalculated window
statistics even when no samples had arrived.

The dashboard now requests redraws on live samples, key input, and terminal resize,
with a one-second idle tick for the program clock, viewport, stale status, and
other status changes. Event polling remains bounded to 100 ms, and queued samples
are still drained and logged on every loop. Sampling and tray cadence are unchanged.
This is not a new hardware polling rate or a promise of fresher source telemetry.

`PreparedHistory` retains chart points and window statistics until displayed
history or signed/stepped mode changes. Axis history scans also happen only when
rebuilding the cache. Idle frames advance the viewport without rescanning history.
Theme changes and resizes reuse prepared data because colours and layout are
applied at draw time. Pause uses frozen history; incoming live samples do not
invalidate that cache. Pause/resume, reset, and display-mode changes invalidate it.

The cache retains point arrays, not another copy of every `Sample`. Rasterization
still visits the cached points, so large histories are not constant-time renders.

## Reproduce

```sh
cargo test --locked --release --test dashboard_performance -- --ignored --nocapture
```

The ignored test compares the uncached and cached paths in one executable, using
20 synthetic 120x32 `TestBackend` frames for each history size. It reports initial
history/axis preparation separately; cached frame timing excludes that preparation.
Rebuild cost still applies when samples or display modes change. There are no
wall-clock pass/fail thresholds in ordinary CI.

Representative local Windows release-mode results:

| Samples | Uncached ms/frame | Cached ms/frame | History + axes preparation, ms |
| ---: | ---: | ---: | ---: |
| 61 | 0.250 | 0.182 | 0.014 |
| 10,000 | 0.861 | 0.546 | 0.555 |
| 500,000 | 51.004 | 17.697 | 32.477 |

The pre-change benchmark measured 0.250, 0.841, and 50.482 ms/frame respectively,
consistent with the uncached comparison. Results vary by machine and load.
These timings exclude terminal I/O, diagnostics-overlay workloads, and real
hardware polling. They are not measurements of process CPU usage or battery
savings. Separately, deterministic scheduler tests verify one idle redraw per
second instead of approximately ten, without delaying input/sample requests.

## Regression coverage

- Clock/viewport ticks do not rescan history or change y-axis bounds.
- Prepared and uncached render buffers match across signed/stepped modes, gaps,
  history eviction/reset, stale readings, paused/live views, themes, diagnostics,
  and different terminal sizes.
- Live samples invalidate prepared history; paused samples do not.
- Key/resize requests redraw without rebuilding history-derived data.
- Theme cycling repaints terminal and RGB palettes without changing saved config
  or history.

Native Linux/macOS runtime and real-terminal CPU/power measurements remain for
platform CI and hardware validation; local synthetic timing is not a substitute.
