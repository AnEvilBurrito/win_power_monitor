# Android device acceptance (pending)

This full checklist is **not yet executed**. Automated JVM/lint/package checks
are not device acceptance. Supplied 0.5.0 videos confirm a refresh regression;
see [frame analysis and the 0.5.1 clock fix](refresh-jitter.md). Verification of
the patched APK on a phone is still pending.
Do not claim background reliability, OEM accuracy or low battery overhead from
those automated checks. Record phone model, Android version, build hash, settings,
foreground/background time, and results for each run; do not upload private data.

## Install / launch / layout

- [ ] Install the debug APK through Android Studio Run on a physical ARM64 phone.
- [ ] Start with no history. Empty charts/current readings are unavailable, not
      demo values; navigation/Stop/settings remain reachable.
- [ ] Record; check portrait, landscape, repeated rotation, font scaling/TalkBack,
      gestures/cutouts/insets, small windows and split-screen.
- [ ] Landscape plot uses nearly the full available width with readouts above
      and controls below; no portrait-ratio letterboxing or side rail.
- [ ] In portrait, only the selected range chip is highlighted: three chips
      (5m / 30m / 2h) are all visible at intrinsic width, none stretched across the
      row. In landscape, the readings strip, toolbar label, all chips, scale,
      freeze and plot are all visible in both one-row and two-row toolbar widths.
- [ ] At default and enlarged font scales, the chart canvas, clock axis and legend
      do not overlap the bottom controls. Short/enlarged layouts scroll safely.
- [ ] Confirm `358.1 mW`, W and µW show the complete suffix directly beside the
      number, not after an empty seven-character numeric field. Test portrait and
      compact/wide landscape at enlarged fonts; the last stroke of W must not clip.
- [ ] Watch repeated updates for >=5min, including Calibrating → live, missing,
      stale and stopped transitions. Record a screen video for frame comparison:
      the outer number+unit group, battery and chart must stay anchored. Test
      signed values, 9.99 → 10.00, low-battery caption and font scales 1/1.3/1.5/2.
      No per-reading warning flashes. Real recording failures remain actionable.
- [ ] Recheck the two supplied video scenarios with 0.5.1+: new samples must not
      flash Stale/dashes or No recent reading before immediately returning. Runtime
      must not collapse one line and move lower sections on each update. Leave the
      service without new samples to ensure genuine staleness still appears.
- [ ] Deny notification permission: the orange 'Notifications are disabled' banner
      is absent. Normal charging/discharging is not orange warning text.
- [ ] The note under the Stop button reads a sentence, never
      `PolicyView(title=...).title`.
- [ ] Chart axis shows the data's unit (mW on an idle phone) with readable ticks
      and a visible trace instead of a flat line on a 0–1 W frame.
- [ ] Tab, window, theme, absolute toggle and frozen monitor view survive rotation.
- [ ] Freeze does not stop sampling, storage or notification. Resume shows newer
      data. Absolute/signed conversion does not unfreeze the snapshot.

## Real telemetry

- [ ] Compare percentage/voltage/charge state against Android's reported values.
- [ ] With saved calibration, start a fresh recording: calibrated NOW power/current
      appear on the first usable sample, with no 60s counter-window wait. Without
      calibration, see five-poll progress then labelled Estimated values, without
      waiting for counter validation. Missing/ambiguous data says unavailable.
      Missing voltage hides power only, not independently valid current or percentage.
- [ ] Settings offer 1/2/5s, default2. CSV timestamps honour all three awake polling
      intervals, including true1s (no old1.5s floor). Startup uses about1s until
      five-poll completion, even if ambiguous (90s failure safety cap), not until
      counter validation or average readiness. No extra
      wake lock/setting mutation. Normal battery events cannot increase the chosen
      polling rate. Hardware can still repeat cached properties between polls.
- [ ] On reset/start without saved calibration, watch Checking reading1/5 through
      4/5, then Estimated live values in about4s after the first valid poll. Check
      signed/absolute, mA/µA-like gauges, actual2-5A mA values, five zeros followed
      by nonzero, and ambiguous/missing data. No fabricated zero or endless spinner.
- [ ] Estimated readings keep their label until counter confirmation. Confirmation
      with a different scale/sign marks a gap, never rewrites prior energy; agreeing
      confirmation does not create a needless gap. Estimates are not saved as
      calibration. Stop/restart/reset and read failures cannot recycle stale evidence.
- [ ] Main readout/chart/window stats/new totals/notification use interpreted NOW,
      not the counter average. Compare with the Developer normalized instant values,
      timestamps and CSV amps/watts. No artificial smoothing/60s lag. Window average
      is a statistic over the selected history, not a live smoothing filter.
- [ ] Compare main current with AccuBattery's Now under steady unplugged use and
      charging, never against its average. Record model/OS, versions/settings,
      timestamps and screenshots. Neither app is a laboratory reference.
- [ ] Record until three consecutive consistent NOW calibration windows complete.
      Saved scale/polarity survives app/service restart; short or contradictory
      evidence does not produce a false confirmation. No deep discharge required.
- [ ] Counter average stays separate and is used only for runtime/developer/CSV.
      Missing CURRENT_NOW cannot fall back to that average. Uncalibrated current
      cannot become Ready merely because a usable counter average exists.
- [ ] In normal Diagnostics, reset/restart NOW calibration while recording (including before confirmation);
      old in-flight evidence cannot restore it. Manual overrides survive reset.
- [ ] In Developer mode, test Auto/µA/mA and Auto/Standard/Reversed; overrides take precedence,
      interpretation changes mark a trace gap, and changing theme/design capacity
      does not. Connected chargers can still net-discharge.
- [ ] Without CHARGE_COUNTER, saved/manual-calibrated NOW is still usable on the
      main screen, but runtime is unavailable. Without saved/manual evidence,
      five-poll inference can still show Estimated; Diagnostics must not promise
      counter validation on this hardware. Manual scale/sign remains in dev mode.
- [ ] Unsupported current/voltage produces dashes; valid percentage and other
      fields remain independently visible. True zero current is not missing.
- [ ] Enter/save/clear design capacity; invalid/out-of-range input is rejected,
      not clamped. The value persists, is labelled user supplied and affects only
      estimated health, never current, reported %, observed capacity or runtime.
- [ ] Gauge full-capacity estimate can appear immediately with counter + percentage;
      it stays clearly separate from observed capacity.
- [ ] Record a monotonic charging window spanning >=20 percentage points (e.g.
      20 -> 40): observed capacity appears, persists and counts completed capacity
      windows, not polls. Gaps, direction changes and counter/percentage reversals
      reset incomplete windows.
- [ ] Record a monotonic discharging window spanning >=20 percentage points (e.g.
      80 -> 60), including a plugged phone that discharges: observed capacity appears
      from the same counter/percentage ratio and the copy names capacity windows, not
      charging windows. A 19-point window reports progress only, and no window may
      bridge a charge leg into a discharge leg or across a recording gap.
- [ ] Copy says charging **or** discharging and the 20-point span everywhere it
      explains observed capacity (Diagnostics caption and pending text, README/help).
      Unknown plug/flow state and uncalibrated current do not block a valid window.
- [ ] With the screen off in high-resolution mode, verify a continuous >=20-point
      window still completes when the counter updates in batches every 10-15 seconds
      but polls run every 1/2/5 seconds. Compare CSV timestamps/counter movement;
      cached batches must not repeatedly reset progress as false >10 A surges.
      Real gaps, reversals and implausible whole-window rates must still be rejected.
- [ ] Estimated health is unavailable until both observed and design capacity exist;
      health is observed / user-supplied design * 100 in either direction.
- [ ] Without CHARGE_COUNTER, gauge/observed capacity and runtime stay unavailable.
- [ ] "Cycle count" shows the platform number on Android 14+ hardware that reports
      it, and states it is not reported where it is absent.
- [ ] Runtime uses recent average current, not an instant burst. Discharge needs
      remaining charge + average; charging also needs observed capacity. Copy says
      at recent average rate, and stopped/stale readings are not forecasts.
- [ ] CSV metadata identifies observed capacity method, user design capacity and
      calibration/manual settings; rows retain raw NOW/AVERAGE, counter, average
      window, calibration/correction flags, signed readings, cycle count and gaps.
      Raw names denote API units, not a promise that OEMs follow those units.
- [ ] Stale readings and loaded-after-reboot history are not shown as current.
      Poll timestamps do not imply a new fuel-gauge hardware update.
- [ ] Temperature/condition map correctly. "Good" is not numerical health.
      Capacity/retained-health/runtime stay unavailable, not invented.

## Background lifecycle and mode

- [ ] At default 2s low-impact mode, leave the app, lock screen, wait >=30min;
      revisit and inspect observed coverage, timestamps, gaps and exported data.
- [ ] Repeat with Doze and OEM battery restrictions. A foreground service is not
      a promise of a fixed screen-off cadence. Never bridge sleep gaps as zero.
- [ ] High-resolution selection asks for confirmation. Confirm only for testing;
      inspect actual held-lease diagnostic. The screen need not remain on.
- [ ] Switch to low impact and Stop: verify wake lock is released. The flag is the
      app's held API lease, not proof Android/OEM will honor it throughout Doze.
- [ ] Test permission denial and later grant for notifications. No crash on denial;
      notification may be hidden, but the app/Android active-app controls can Stop.
- [ ] Normal Settings offers Notification update rate: Instant / 15s / 60s, default
      60s on fresh/updated installs without a saved choice. Restart to verify all
      selections persist independently of poll cadence and developer mode.
- [ ] While recording, choose each rate. Instant posts every poll; timed choices
      wait until the first poll at/after the threshold (15s at 2s polling can take
      16s). Changing the choice refreshes now, without an extra sample, calibration
      reset, history gap or wake-lock change. Check System UI behaviour on-device;
      Android may coalesce/defer what it displays.
- [ ] Important readiness/availability/error changes bypass every chosen rate.
      Notification Stop acts directly without opening a screen, ends recording,
      flushes the pending tail and removes the ongoing notification.
- [ ] Repeated Start does not duplicate samplers. Rapid Start/Stop/settings changes
      do not leave a wake lock, overwrite a newer session, or prevent retry.
- [ ] Provider failure releases the high-resolution lock while unavailable;
      recovery resumes a single sampler. Storage failures stop recording honestly.
- [ ] Force-stop/process kill then cold reopen: committed history loads as stopped
      or interrupted; no automatic restart or invented readings. Up to the bounded
      uncommitted 4-sample/60s tail may be lost on abrupt termination.

## Persistence / export

- [ ] Stop then reopen the process: settings and committed summary/history survive.
- [ ] Developer mode defaults off on fresh/updated installs without opt-in; toggle
      on/off and restart. Raw/window/scale/sign/wake-lock details only appear when
      enabled. Normal Diagnostics still has calibration status/reset. Design capacity and normal interval/mode/theme
      options remain visible. Hiding dev mode does not discard saved corrections.
- [ ] Old summaries remain unchanged. Export marks restored summary metric unknown
      instead of relabelling old averaged-power totals as live. New live summaries
      explicitly declare INSTANT_INTERPRETED, allow estimated segments, and preserve
      per-row INSTANT_ESTIMATED/currentCalibrated=false through restart/export.
      Check the2h live plot at1s; the bounded
      8192-point UI buffer must cover the window. Durable cap stays130,000 rows.
- [ ] Upgrade v3 (and older) installs: schema v4 keeps sample/session history and
      summary, adds nullable/defaulted provenance columns, and invalidates ONLY the
      old capacity aggregate. No old estimate is relabelled as observed capacity.
- [ ] Hide the app while recording: no UI age ticker/redraw loop continues in the
      stopped activity; returning refreshes age/staleness promptly.
- [ ] Upgrade from a5/15/30s setting: it becomes2s once even if the older migration
      marker exists. Later1/2/5 selections stick across restarts. Calibration, design
      capacity, theme, developer mode and retained sessions remain unchanged.
- [ ] Export via Android's document picker; cancellation is harmless, failures are
      visible, and success appears only after all requested writes complete.
- [ ] CSV is UTF-8 with signed values, blanks, timestamps, gaps and retention
      metadata. It currently has `#` metadata lines before the column header;
      importers should skip those lines. Never call retained detail a full archive.
- [ ] Verify sums/observed coverage against a short manually checked trace with
      gaps, mixed charge/discharge and different sampling intervals.
- [ ] Exercise storage failure/full-disk on a disposable test device/emulator;
      no silent loss, falsely successful export, or permanent CPU wake lock.
- [ ] Retention bounds/pruning preserve full-session summary; history trimming is
      disclosed and no old raw samples are fabricated to match aggregate totals.

## Observer impact (unmeasured)

- [ ] Compare a control run without recording to low-impact runs at1/2/5s.
- [ ] Compare screen-on dashboard against screen-off recording separately.
- [ ] Measure high-resolution mode separately; keep CPU-awake bias explicit.
- [ ] Keep screen brightness, radios, workload, temperature and charge conditions
      controlled; run long enough to exceed gauge noise. Repeat across OEMs.
- [ ] Use platform profiling/power instrumentation where possible. Whole-battery
      telemetry cannot isolate WPM's own watts by itself. Publish no arbitrary
      "0.1%" or negligible-drain claim without evidence.

Release signing, final application ID, CI publishing and distribution policy are
separate gates. This initial APK is debug-signed and intended for development.
