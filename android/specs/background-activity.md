# Background activity: audit and optimisation contract

Status: audit only, source-derived. **No optimisations are implemented here and no
on-device measurement was performed by the author of this document.** Every impact
figure below is either arithmetic from a named constant or explicitly marked
"not verifiable from source". Do not quote these numbers as measured drain.

Scope: while recording and while stopped — every recurring wakeup, timer, IPC,
flash write and main-thread allocation that this app causes, plus what it must keep
because it measures its own battery draw. Read with
[initial-implementation.md](initial-implementation.md) (§3–§6),
[live-current.md](live-current.md) (cadence/startup burst),
[refresh-jitter.md](refresh-jitter.md) (synchronous clock),
[device-acceptance.md](device-acceptance.md) ("Observer impact (unmeasured)") and
[capacity-windows.md](capacity-windows.md) (window evidence).

Paths are relative to `android/`. Line numbers are for the current working tree and
will drift; function names are the durable anchor.

## 1. Inventory of recurring work

### 1.1 While recording (foreground service alive)

| # | Source | Trigger | Cadence | Cost driver | Survives screen-off |
| --- | --- | --- | --- | --- | --- |
| A | Sampler coroutine `MonitoringService.sampleLoop` (L372–478) | one suspended loop, `delay`-based | 1 s during the startup burst, then exactly the selected 1/2/5 s (720/1800/3600 polls per hour) | see B–F per iteration | Yes, subject to Doze (see §1.4) |
| B | `BatteryTelemetryProvider.read()` (L35–112) | each poll | per poll | 3 × `BatteryManager.getLongProperty` IPC (`CURRENT_NOW` L43–48, `CURRENT_AVERAGE` L49–54, `CHARGE_COUNTER` L55–60) + 1 × `getSystemService(BatteryManager)` (L84) | Yes |
| C | `BatteryTelemetryProvider.readStickyBroadcast()` (L120–127) | each poll | per poll | `context.registerReceiver(null, filter)` — one IPC returning the sticky `Intent` plus extra-Bundle unmarshalling; registers nothing (comment L121–122 is correct) | Yes |
| D | `ACTION_BATTERY_CHANGED` receiver (registration L666–680, receiver L85–92, registered in `onCreate` L239) | platform broadcast | **not verifiable from source** (OEM/OS dependent; a charging phone re-emits on counter/gauge changes) | one broadcast dispatch + main-thread `onReceive` + `Channel.trySend`; can resume the `select` in `awaitWakeUp` (L487–500) | Yes |
| E | Wake lock (high-resolution only) `renewWakeLock` L851–880, re-armed on every successful poll L438–440 | each successful poll | per poll | 1 IPC to `PowerManagerService` per re-arm (1800/h at 2 s); `WAKE_LOCK_LEASE_MILLIS` = 10 min (L1094) | **This is why it survives**: the partial lock prevents suspend |
| F | Repository `record()` (L492–551) on the **main** dispatcher (service scope is `Dispatchers.Main.immediate`, L68) | each successful poll | per poll | `SessionAccumulator.add`, `samples.add` + `while (samples.size > MAX_UI_SAMPLES) samples.removeAt(0)` (L515–516), capacity estimator, then `publishLocked()` (L786–801) which does `samples.toList()` (up to `MAX_UI_SAMPLES` = 8192, L820) and allocates a whole new `MonitorUiState` + `StateFlow` write | Yes (independent of UI collectors) |
| G | Sample-batch flush `flushLocked()` (L601–627) | `pending >= FLUSH_MAX_SAMPLES` (4, L836) or `>= FLUSH_MAX_MILLIS` (60 s, L839) | 4 samples → **900 tx/h at 1 s, 450 at 2 s, 180 at 5 s** (at 5 s the 4-sample rule fires at 20 s, so the 60 s cap never binds) | 1 SQLite transaction (`MonitoringStore.saveBatch` L196–220): 4 inserts × 26 columns + 1 update of the 15-column `sessions` row, plus at least one journal sync on the platform default journal mode | Yes |
| H | Retention `pruneLocked()` (L629–673) | `lastFlushElapsed - lastPruneElapsed >= PRUNE_INTERVAL_MILLIS` (5 min, L842) | 12 passes/h | `DELETE` + `SELECT COUNT(*)` over the whole `samples` table (`MonitoringStore.countRows` L391) + `storedSampleCount` (`PRAGMA`-free `COUNT(*) WHERE sessionId`, L351) — full scans at the 130 000-row cap | Yes |
| I | Notification post `updateNotification` (L963–990) | every poll, gated by `NotificationUpdatePolicy.shouldUpdate` | default 60 s; 15 s; or **every poll** on "Instant" | 1 `notify()` IPC; `buildNotification` (L997–1040) constructs **two** `PendingIntent`s per post and sets `setWhen(sample.timestampMillis)` so the row re-lays out every time | Yes |
| J | `observeSettings` collector (L692–734) | settings `StateFlow` changes | user-driven only | one `collect`; wakes the sampler and/or posts a notification | Yes |
| K | Compose staleness ticker `rememberMonotonicMillis` (ui/WpmDashboard.kt L250–281, `STALENESS_TICK_MILLIS` = 5 000 L280) | only while `running && tickerActive` **and** host lifecycle ≥ STARTED (`repeatOnLifecycle` L261) | 5 s (720 recompositions/h) | one `delay` + one `mutableLongStateOf` write → dashboard recomposition; no frame loop, no animation | **No** — gated on STARTED |
| L | Chart/readout projection (ui/WpmDashboard.kt L137–149) | `remember(monitorState.samples, window)`; the key changes on every poll because `publishLocked` publishes a fresh list | per poll, screen on | `filter` + `forLiveDisplay()` copy per point in the window (300 / 1800 / up to 7200 objects for 5 m / 30 m / 2 h at 1 s) + a full `summarizeWindow` pass | No — no collectors when stopped |
| M | Export `writeCsv` (Repository L221–291) | user action | on demand | flush + full re-read of up to `MAX_EXPORT_SAMPLES` rows | n/a |

### 1.2 While stopped (no service, no activity)

- No alarm, job, `Handler`, `workManager` or content observer exists: a grep over
  `app/src/main/java` finds `AlarmManager`, `JobScheduler`, `WorkManager`,
  `setExact*`, `postDelayed`, `Handler(`, `java.util.Timer`,
  `ScheduledExecutor`, `Choreographer` **nowhere** in the app. The only matches are
  in this sentence's source list.
- The `ACTION_BATTERY_CHANGED` receiver is unregistered in `onDestroy`
  (L286, `unregisterBatteryReceiver` L682–690), and the ongoing notification is
  removed and cancelled there too (L296–297). While stopped the app contributes no scheduled
  wakeup at all. The UI ticker is off (`running == false`, WpmDashboard L260) and
  `collectAsStateWithLifecycle` stops collecting with the activity.
- The only stopped-state residue is the repository singleton holding one 8192-entry
  list, the summary, the store and preference handles. `START_NOT_STICKY` means a
  process kill leaves nothing to restart it.

### 1.3 Verified absent or deliberately cheap (do not "fix" as if accidental)

- Notification channel is `IMPORTANCE_LOW` with `setShowBadge(false)`
  (L230–238); the notification itself uses `.setOnlyAlertOnce(true)`,
  `.setSilent(true)`, `PRIORITY_LOW`, `CATEGORY_SERVICE` (L1020–1026): no sound,
  vibration, LED or heads-up per update.
- No per-poll logging: the only `Log` call in the app is the DEBUG-only
  registration failure at L678.
- No Compose animation, `rememberInfiniteTransition` or frame clock anywhere in
  `ui/**`; `ui/PowerChart.kt` L45 states the intent and the code matches.
- No `registerReceiver` other than the one battery trigger and the null-receiver
  sticky query.

### 1.4 Screen-off behaviour (contract, not measurement)

The loop is strictly sequential: `awaitCadence` recomputes the remainder from
`lastSampleElapsed` (L502–509), so after a long suspend exactly **one** sample runs
and then the normal wait resumes — there is no catch-up burst. In low-impact mode
nothing prevents suspend, so Doze defers `delay` and recording simply stops,
producing the documented gaps. The actual screen-off cadence for a given OEM is
**not verifiable from source** and remains a device-acceptance item.

## 2. Waste list

Impact classes are coarse: **CPU** (main/worker CPU time or allocation churn),
**IPC** (binder round-trips per hour), **flash** (durable writes per hour),
**wakeup** (process/CPU wake events per hour).

### W1 — Every poll copies the whole UI buffer on the main thread (P0, CPU)

- Problem: `MonitoringRepository.record()` runs on the service's main dispatcher and,
  for **every** sample, shifts an 8192-entry `ArrayList` (`removeAt(0)`, L516) and
  then copies the entire buffer (`samples.toList()`, L792) into a new
  `MonitorUiState` (`mutableState.value`, L790), even when nothing is collecting
  (screen off, activity gone).
- Evidence: `monitoring/MonitoringRepository.kt` L492–551, L786–801;
  `MAX_UI_SAMPLES = 8192` L820; called from `MonitoringService` L437.
- Impact class: CPU/allocation. At the cap that is ~64 KiB copied and ~64 KiB
  shifted per poll on a 64-bit build: ~225 MiB/h of short-lived arrays at 1 s,
  ~112 MiB/h at 2 s (computed from `MAX_UI_SAMPLES`, **not measured**). It also
  puts that work on the main thread ahead of any lifecycle callback. The cap is
  reached after 8192 samples (~2.3 h at 1 s, ~4.6 h at 2 s), so a long recording is
  the expensive case, not a short one.
- Fix: ring buffer/`ArrayDeque` for `samples` (no `removeAt(0)`), and publish
  without copying the whole buffer — either an immutable persistent structure, or
  skip the `toList()` copy when `state.subscriptionCount == 0` (`StateFlow` is a
  `SharedFlow`, so the count is available without new dependencies). Any gating
  must preserve: the published list stays immutable; a subscriber that attaches
  mid-recording still gets the complete current buffer on its first emission; the
  8192-point 2 h-at-1 s bound and the CSV/export path are unchanged.
- Amends: [initial-implementation.md](initial-implementation.md) §3 ("All published
  collections/snapshots immutable copies") and §5 ("Samples list for UI is bounded").
- **Status: implemented.** `monitoring/SampleSeries.kt` is the immutable chunked
  series (256 samples per chunk, tail chunk copied on write, head offset advanced or
  one head chunk dropped on eviction). `MonitoringRepository` holds one and publishes
  it as-is: appending copies ~2 KiB regardless of length, eviction is O(1), and the
  snapshot is never copied on publish. The `subscriptionCount` gate was rejected:
  `MonitorUiState.latest` derives from `samples`, so publishing a stale list would
  make the notification's reading lag one interval. Tests:
  `SampleSeriesTest` (order, capacity/eviction vs an `ArrayList` reference,
  published-instance immutability, chunk boundaries, randomized 40×300 steps).

### W2 — The high-resolution wake-lock lease is re-armed on every poll (P0, IPC)

- Problem: `renewWakeLock()` is called after each successful sample, re-acquiring a
  10-minute lease by IPC even though the previous lease still has minutes left.
- Evidence: `MonitoringService` L438–440, L851–880 (`wakeLock?.acquire(WAKE_LOCK_LEASE_MILLIS)`
  L873), `WAKE_LOCK_LEASE_MILLIS` L1094. 1800 re-arms/h at 2 s, 3600 at 1 s.
- Impact: IPC to `PowerManagerService` per poll, on the main thread.
- Fix: keep the timed lease (a crash must still self-release in ≤10 min) but re-arm
  only when less than half the lease remains, tracked from a local
  `SystemClock.elapsedRealtime()` stamp at acquire time (~12 re-arms/h). Do **not**
  replace the timed lease with an untimed `acquire()`: the timeout is the leak
  safety net that [device-acceptance.md](device-acceptance.md) relies on
  ("provider failure releases the high-resolution lock").
- Amends: [initial-implementation.md](initial-implementation.md) §5 (wake-lock
  lease wording).
- **Status: implemented.** `monitoring/WakeLockLease.kt` owns the arithmetic
  (`isRenewalDue` when none armed, or half the lease spent; a backwards clock forces
  a renewal). `renewWakeLock()` returns early while the lock is held and the lease is
  fresh, and `releaseWakeLock()` clears the stamp so the next request acquires
  immediately. The timed lease is unchanged. Tests: `WakeLockLeaseTest`.

### W3 — The battery broadcast trigger is consumed with the screen off, where it can only waste a wakeup (P0, wakeup)

- Problem: the `ACTION_BATTERY_CHANGED` trigger exists purely to cut *presentation*
  latency ("exactly the moment the user looks at the screen", L88–92). It is
  registered for the whole service lifetime and consumed unconditionally. A
  delivery that arrives while the display is off and nobody is looking costs a
  broadcast dispatch, a main-thread callback and a coroutine resume for a sample
  that is not allowed to happen sooner anyway.
- Evidence: registration L666–680 and L239; receiver L85–92; consumption
  `awaitWakeUp` L487–500; the spacing floor that proves no extra samples are
  possible: `awaitMinimumSampleSpacing` L521–528 with `minimumSpacing == interval`
  from `StartupSamplingPolicy.decide` (`monitoring/CurrentEstimator.kt` L475–504).
  A trigger that arrives mid-interval ends `awaitCadence` early and is then held
  until the floor expires in one `delay`, so it **cannot** raise the sample count.
- Impact class: wakeup/IPC, count **not verifiable from source** (device dependent);
  on some OEMs a charging phone re-emits this broadcast many times per minute.
- Fix: keep the trigger while a display is on, release it while the screen is off,
  using `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` (transition-only broadcasts, tens per
  day) to toggle it. Optionally also ignore deliveries when the notification rate is
  ≥15 s and the UI is not resumed. Preserve: the exact 1/2/5 s cadence, the
  startup burst, the `RECEIVER_NOT_EXPORTED` flag, and the "no extra polls" claim.
  Recorded data is unaffected because the spacing floor already equals the interval;
  only plug/unplug *visibility* can lag by up to one interval while the screen was
  off.
- Amends: [initial-implementation.md](initial-implementation.md) §5 and the README
  "No extra polls, timers or wake locks" bullet (which stays literally true; the
  spec should say the trigger is display-gated).
- **Status: implemented.** `monitoring/DisplayTriggerGate.kt` is the pure rule
  (listen while interactive; listen when the platform cannot answer).
  `MonitoringService` registers `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` and calls
  `syncBatteryTriggerWithDisplay()` on each transition and once after registering, so
  a service created with the screen already off holds no battery receiver. Cadence,
  the spacing floor, the startup burst and `RECEIVER_NOT_EXPORTED` are untouched.
  Tests: `DisplayTriggerGateTest`.

### W4 — Redundant per-poll lookups in the provider (P2)

- W4a (P2, CPU): `context.getSystemService(BatteryManager)` is called per poll
  (`BatteryTelemetryProvider` L84, from `read()` L39). Cache it in a lazy field; the
  service already has a `Context` for its lifetime. Zero fidelity impact.
- W4b (P2, CPU/IPC, fidelity risk): `registerReceiver(null, filter)` per poll
  (L120–127) is a binder call plus `Intent`/`Bundle` unmarshalling, 3600/h at 1 s.
  Reusing the `Intent` already delivered to the registered receiver would remove
  it, but it would make the sample's `voltageMillivolts`/`level`/`temperature`
  lag by up to one broadcast. **Do not do this** unless a device check shows the
  sticky cache and the delivered intent are byte-identical at poll time; the
  recorded voltage is measurement data, not presentation.
- Amends: nothing required for W4a; W4b must not amend the telemetry contract
  without evidence.

### W5 — Notification rebuild per post, with two fresh `PendingIntent`s (P1, IPC/CPU)

- Problem: `buildNotification` (L997–1040) runs `PendingIntent.getActivity` and
  `PendingIntent.getService` on every post, and `setWhen(...)` changes each time so
  the row re-lays out. Default is 60 s, but "Instant" is a documented user option:
  3600 posts/h at 1 s.
- Impact: IPC + SystemUI work; the channel is silent/low so there is no alerting
  cost, which is already correct.
- Fix: hoist both `PendingIntent`s into lazily-initialised fields (they are
  `FLAG_IMMUTABLE` and never change), and reuse one `NotificationCompat.Builder`
  with `setContentText`/`setWhen` when only the text changed. Keep the
  `NotificationUpdatePolicy` throttle, the force/bypass transitions and the
  confidence-transition bypass exactly as they are.
- Amends: [initial-implementation.md](initial-implementation.md) §5 notification
  paragraph (implementation note only). Add a line to
  [device-acceptance.md](device-acceptance.md) that "Instant" is the highest
  SystemUI cost choice, so acceptance runs are not mixed across rates.

### W6 — Flash write cadence is set by the batch size, not by value (P1, flash — mostly *keep*)

- Problem: 4 samples per transaction gives 900/450/180 transactions per hour at
  1/2/5 s, each with at least one journal sync. `MonitoringStore` never calls
  `setWriteAheadLogging`/`setJournalMode` (grep: no occurrence), so it inherits the
  platform default journal mode — **not verifiable from source**; check
  `PRAGMA journal_mode` on a device.
- Fix, in order of preference:
  1. Do nothing to batch size. Widening the batch past 4 samples/60 s would widen
     the documented abrupt-kill loss tail (README, §6 of initial-implementation) —
     that is a contract loss, not an optimisation.
  2. If a device check shows rollback journal (`delete`/`persist`), enable WAL
     **with `synchronous=FULL`** so a power loss still cannot lose a committed
     batch; WAL reduces commit fsyncs but with `synchronous=NORMAL` it would break
     the "committed history" promise. Keep the batch+summary atomicity and the
     `NonCancellable` write.
  3. Otherwise accept and document that flash cost scales with the selected
     cadence; this is a user-chosen cost, like the cadence itself.
- Amends: [initial-implementation.md](initial-implementation.md) §6 (add the journal
  mode decision explicitly so it is not re-litigated).

### W7 — `COUNT(*)` table scan on every retention pass (P1, CPU)

- Problem: every 5 minutes `prune` runs `DELETE` + a full `SELECT COUNT(*)` over
  `samples` + a second count for the session (`MonitoringStore` L365–395, L351).
  At the 130 000-row cap that is a full scan 12×/h for a number the repository
  already tracks.
- Fix: keep the row total incrementally (committed inserts minus deleted rows, reset
  from the prune result) and gate a pass on "inserted since last prune ≥ N" instead
  of only a time interval; keep the `DELETE ... LIMIT excess` bounded exactly as it
  is, and keep the `historyTruncated` handling.
- Amends: [initial-implementation.md](initial-implementation.md) §6 (retention
  wording: "bounded and serialized, no re-reading all database history each sample"
  — extend it to "…and no full-table count per pruning pass").

### W8 — The startup burst has no failure-based early exit (P2, wakeup)

- Problem: after 5 polls the burst ends in every normal, ambiguous and unsupported
  case, but a run of *failed* polls keeps 1 s polls going to the 90 s cap.
- Evidence: `StartupSamplingPolicy.decide` (`monitoring/CurrentEstimator.kt`
  L475–504), `MAX_BURST_MILLIS` L437; the service passes
  `instantSampleObserved`/`instantReadingSeen` (L448–451, L456–467), which stay
  "supported" when no poll has succeeded.
- Impact: up to 90 polls instead of 45 at 2 s (≈45 extra polls) per recording start
  in the rare total-read-failure case.
- Fix: optional — end the burst after
  `TelemetryFailureDebounce.CONSECUTIVE_FAILURES_BEFORE_NOTICE` consecutive failed
  polls, keeping the 90 s cap as the outer bound. This changes a documented rule
  ("A 90s safety cap bounds failed acquisition") and must be written into
  [live-current.md](live-current.md) first, not slipped in.

### W9 — Screen-on: the visible window is re-projected and re-summarised every poll (P2, CPU)

- Problem: `remember(monitorState.samples, …)` keys on the list identity, which
  changes on every poll, so the whole visible window is re-mapped through
  `forLiveDisplay()` and re-summarised each poll (300/1800/7200 objects at 5 m/30 m/2 h
  for 1 s polling).
- Evidence: `ui/WpmDashboard.kt` L137–149.
- Fix: keep the previous projection and append the newest sample (drop the evicted
  head) instead of re-projecting; recompute the window summary over the same
  incrementally maintained series. Display-only, so fidelity risk is nil, but the
  freeze/absolute-scale/unit-change invalidation paths must still rebuild from
  scratch, and the 5 m/30 m/2 h window switch must still recompute.
- Amends: [initial-implementation.md](initial-implementation.md) §5 ("Recompute
  chart/stats only on data/window/scale changes" — data changes every poll, so it
  needs to say "append, do not re-project").

### W10 — Narrow the staleness ticker's invalidation scope (P2, CPU, screen-on only)

- The 5 s ticker is already correctly gated (running, not frozen, lifecycle
  STARTED) and must stay. Its only remaining cost is that `now` is read in the
  `WpmDashboard` body (L127, L276), so a tick re-composes the whole dashboard.
  Read the clock in a small leaf composable that only the age/staleness text
  consumes. **Do not** reintroduce a cached tick as "now" — that is exactly the
  [refresh-jitter](refresh-jitter.md) defect; `freshnessClockMillis` stays.

## 3. Prioritised roadmap

Effort: S ≤ ~1 h, M ≤ half a day, L > half a day (excluding device verification).
"Fidelity risk" is specifically *risk of changing what the app measures about its
own draw*.

### P0 — clear waste or a regression of a documented contract

All three are implemented; the rows record what shipped and what still needs a device.

| Item | Effort | Fidelity risk | Amends |
| --- | --- | --- | --- |
| W1 immutable chunked sample series + copy-free publish (main-thread CPU/alloc every poll, screen-off included) — **done** | M | None for measurement; the `subscriptionCount` gate was rejected because it would make `latest` stale | initial-implementation §3, §5 |
| W2 wake-lock re-arm only when the lease is half spent — **done** | S | None, provided the timed lease stays | initial-implementation §5 |
| W3 display-gate the battery trigger — **done** | M | None to cadence (floor already equals the interval); only plug/unplug visibility can lag ≤1 interval with the screen off | initial-implementation §5, README bullet |

### P1 — worth doing

| Item | Effort | Fidelity risk | Amends |
| --- | --- | --- | --- |
| W7 incremental retention count | S | None | initial-implementation §6 |
| W5 cached `PendingIntent`s / single builder | S | None | initial-implementation §5, device-acceptance note |
| W6 journal-mode decision (WAL only with `synchronous=FULL`) | S–M | **Yes if WAL is enabled with `synchronous=NORMAL`** — that would lose a committed batch on power loss and break the abrupt-kill contract | initial-implementation §6 |

### P2 — nice to have

| Item | Effort | Fidelity risk | Amends |
| --- | --- | --- | --- |
| W9 incremental window projection | M | None (display only) | initial-implementation §5 |
| W4a cached `BatteryManager` | S | None | — |
| W10 narrower clock read scope | S | None if the synchronous clock is preserved | refresh-jitter (clarification only) |
| W8 burst failure early-exit | S | None, but changes a published rule | live-current (must be edited first) |
| W4b dropped sticky re-query | S | **Yes** — recorded voltage/level could lag one broadcast. Recommended *not* to do | — |

Ordering note: P0 items do not touch energy/statistics maths at all. If any P0
change also alters the recorded sample cadence or the calibration windows, it has
gone wrong — see §4.

## 4. Do not change (measurement correctness)

These are high-resolution by requirement. A future implementer must not "optimise"
them into fewer samples, fewer writes or a slower clock:

1. **The selected 1/2/5 s cadence, and full 1 s fidelity when 1 s is selected.** The
   interval is also the event-spacing floor
   (`StartupSamplingPolicy.decide`, `monitoring/CurrentEstimator.kt` L483–488).
   Coalescing or batching samples "for efficiency" would corrupt interval-based
   energy integration and every window statistic.
2. **The five-poll ~1 s startup burst and its 90 s cap.** Fewer polls would make
   the magnitude/sign estimate unreliable; more would misreport the configured
   cadence.
3. **Current calibration windows:** ≥3 consecutive, non-overlapping windows, each
   ≥15 s and ≥3 mAh, ≤5 min (`CurrentCalibrator` L393–407). Neither the fast cadence
   nor the counter movement check may be weakened.
4. **Observed-capacity 20-percentage-point windows and their gap/reversal resets**
   ([capacity-windows.md](capacity-windows.md)).
5. **The high-resolution partial wake lock and its 10-minute timed lease.** It is
   the mechanism that keeps the device awake to record, and it is the app's largest
   self-draw — the app must keep disclosing that, and must not silently shorten the
   lease or drop the lock while "recording".
6. **The timed notification throttle, its confidence/state bypasses and the default
   60 s.** `NotificationUpdatePolicy` (`monitoring/NotificationUpdatePolicy.kt`) is
   a published behaviour with unit tests.
7. **Batch size 4 samples / 60 s and the atomic batch+summary transaction.**
   Enlarging it widens the documented abrupt-kill loss tail.
8. **The synchronous freshness clock** (`ui/FreshnessClock.kt`,
   [refresh-jitter.md](refresh-jitter.md)). Never restore a cached tick as "now".
9. **`START_NOT_STICKY`, "no exact alarms", "no boot receiver", "no WorkManager
   polling".** The task list for this audit mentions `setExactAndAllowWhileIdle` as
   a possible tool: it is **not available to this app**. The background-monitoring
   contract in the README explicitly rejects exact alarms as a way to evade
   background restrictions, and using one would both break that contract and add a
   wakeup source. The correct answer to Doze here is to keep recording gaps and
   disclose them, not to schedule around Doze.
10. **No wake lock in low-impact mode**, and no persistent setting mutation to
    achieve a cadence.

## 5. Validation plan (must be run on a device; none of it was run here)

No measurement was performed for this document. The JVM unit tests for W1–W3 were
executed on the maintainer's machine under JDK 21 (the machine's default is 25.0.4.1,
whose version string the bundled Kotlin compiler cannot parse, so the Gradle build has
to be pointed at a 21 JDK explicitly — the same version CI uses in `android.yml`).
`testDebugUnitTest`: 535 tests, 0 failures, including `SampleSeriesTest` (9),
`WakeLockLeaseTest` (7) and `DisplayTriggerGateTest` (3). `lintDebug` and
`assembleDebug` also pass, with no new lint findings in the touched files.

The scratch port of `SampleSeries` used before Gradle could run compared 360 000
randomized append/evict steps against an `ArrayList` reference and found no
mismatches, but it missed two real defects that only the Kotlin run exposed: a chunk
was allocated at the length of its first element instead of `CHUNK`, and the first
sample of a new tail chunk sized that chunk to one slot. Dynamic JS arrays grew on
write and hid both. Lesson for the next port: size every chunk explicitly.

Confirm each change independently;
do not stack P0 changes into one build you then measure once.

1. Baseline first. Two control runs of the unmodified build per mode: low-impact at
   2 s with the screen off for ≥1 h, and high-resolution at 2 s for the same
   duration. Keep brightness, radios, workload and starting charge comparable
   (device-acceptance "Observer impact" already requires this).
2. Wakeup and wake-lock census
   (`adb shell dumpsys batterystats --checkin <package>` and
   `adb shell dumpsys power | grep -i wake`): compare "Wake lock" entries and the
   count of `wpm:high-resolution-monitoring` acquisitions per hour. W2 should show a
   ~100× drop in acquisitions without any change to held-time.
3. Broadcast census: platform tools do not charge a received broadcast to the
   receiver (`batterystats` attributes the dispatch to the sender), so count
   `ACTION_BATTERY_CHANGED` deliveries with a temporary DEBUG-only counter placed in
   `onReceive`. That counter is an instrument for the experiment, not shipped code,
   and it must be removed before acceptance. Record deliveries per hour at each
   cadence, screen on and screen off, per OEM.
4. Poll accounting: compare `summary.sampleCount` and elapsed recording time from the
   CSV per run. W1/W3/W9 must leave the **sample count and cadence unchanged**; if
   the count moved, the change is wrong, not the measurement.
5. CPU per poll: `adb shell top -d 5 -p <pid>` / `dumpsys cpuinfo` deltas over a
   30-minute screen-off recording, expressed per 1000 polls using the sample count.
   Watch for reduced main-thread time and lower GC frequency (`logcat` GC lines).
6. Flash: `dumpsys diskstats` before/after a 1-hour run at each cadence, or
   `/proc/diskstats` write-sector deltas. Expect W6 to change this only if the
   journal mode actually changed; verify with `PRAGMA journal_mode` and
   `PRAGMA synchronous` on-device, and re-run the abrupt-kill/`am force-stop` test
   from device-acceptance to prove the committed-history promise still holds.
7. Durability regression test after any persistence change: force-stop / `kill -9`
   the process mid-recording, cold reopen, and confirm committed history loads as
   stopped with only the documented ≤4-sample/60 s tail missing.
8. Screen-off Doze test: device-acceptance already requires a ≥30 min screen-off run
   with Doze; additionally compare observed coverage before/after W3 to confirm the
   trigger gate did not change the number of recorded gaps.

What **not** to conclude:

- Not from an emulator: it has no real gauge, no Doze timing, and no OEM battery
  policy. Emulator runs can only prove the app does not crash.
- Not from a screenshot, a video, or a single synthetic unit test. Those verify
  layout and pure logic respectively.
- Not from whole-battery level alone over a short run: gauge noise, temperature and
  radio state dominate; the app's own draw cannot be isolated that way at the mW
  level it reports.
- Not from comparing two runs with different cadence, notification rate, theme
  (dark vs Latte), screen-on time or starting charge.
- Not from an improvement in one metric alone: W1 should lower CPU/GC but not
  flash; W6 should lower flash but not CPU; if a change moves a metric it was not
  supposed to move, that is evidence of a side effect, not of success.

## 6. Open questions for the maintainer

1. Is the battery-broadcast trigger's presentation latency while the screen is off
   worth anything to you? If not, W3 is a straight P0 win; if you want the
   notification to look fresh the moment the shade opens, keep it and accept the
   unknown delivery count.
2. Do you want `subscriptionCount` gating in W1 (bigger win with the screen off, one
   more behaviour to test) or only the ring buffer (smaller win, no behaviour
   change)?
3. Should W8 (burst failure early-exit) be specified at all, given a total read
   failure is already surfaced by the debounce after 2 polls?
4. May a WAL + `synchronous=FULL` experiment be run at all, given it changes the
   durability mechanism? If not, W6 should be closed as "accepted cost" and written
   into the persistence spec.
5. What per-hour figures would you accept to change the README's honest "overhead
   unmeasured" wording? That is a claim question, not a code question, and this
   audit deliberately makes no such claim.
