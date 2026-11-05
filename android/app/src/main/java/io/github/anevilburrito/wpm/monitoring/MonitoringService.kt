package io.github.anevilburrito.wpm.monitoring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.anevilburrito.wpm.BuildConfig
import io.github.anevilburrito.wpm.MainActivity
import io.github.anevilburrito.wpm.R
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.LiveReading
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.Units
import io.github.anevilburrito.wpm.model.liveAmps
import io.github.anevilburrito.wpm.model.liveReading
import io.github.anevilburrito.wpm.model.liveWatts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground-first monitoring service: one coroutine sampler, one wake lock (high
 * resolution only) and one ongoing notification. The activity never samples.
 *
 * All lifecycle state ([phase], [generation], [activeToken], the wake lock and the
 * notification throttle) is confined to the main thread: the platform callbacks
 * already run there and every coroutine below uses [Dispatchers.Main.immediate],
 * so no lock is needed and `onStartCommand`/`onDestroy` cannot race the sampler.
 * Provider reads and database work are moved to [Dispatchers.IO].
 *
 * START_NOT_STICKY is intentional: a killed process must not silently resume an
 * empty or invented session. An unfinished saved session is restored as stopped.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Survives service destruction long enough to finish the last database write. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val settingsChanged = Channel<Unit>(Channel.CONFLATED)

    /**
     * Early-sample trigger fed by `ACTION_BATTERY_CHANGED`.
     *
     * The configured 1/2/5 s poll is the base cadence, but a real platform change
     * (plug, unplug,
     * charge step) is exactly the moment the user looks at the screen, so waiting up
     * to a full interval makes the dashboard look asleep. CONFLATED because the only
     * thing a trigger means is "read again now": a burst of broadcasts collapses
     * into one token instead of queueing one sample each.
     */
    private val batteryChanged = Channel<Unit>(Channel.CONFLATED)

    /** Only the system sends `ACTION_BATTERY_CHANGED`; nothing else may wake us. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            batteryChanged.trySend(Unit)
        }
    }

    private var batteryReceiverRegistered = false

    private lateinit var repository: MonitoringRepository
    private lateinit var provider: BatteryTelemetryProvider

    private var samplerJob: Job? = null
    private var startJob: Job? = null
    private var settingsJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Derives the average current from `CHARGE_COUNTER` deltas for the *current*
     * recording. One instance per recording so a new session never inherits the
     * previous session's counter baseline. The sampler feeds it on IO while the
     * lifecycle callbacks reset it on main, so the field is volatile.
     */
    @Volatile
    private var currentEstimator: CurrentEstimator? = null

    /**
     * Infers the instantaneous current's unit and polarity from charge-counter evidence
     * gathered during this recording. One instance per recording, seeded with the
     * persisted calibration so already-established evidence is not re-proven. The
     * sampler updates it on IO while lifecycle callbacks drop it on main, so the field
     * is volatile.
     */
    @Volatile
    private var currentCalibrator: CurrentCalibrator? = null

    /** Ephemeral startup guesses; never passed to the calibration persistence path. */
    private val startupInterpreter = FastCurrentInterpreter()

    /**
     * Baseline of the interpretation in force for this recording (effective scale
     * and sign). A sample whose raw current means something different from the one
     * before it is marked as a discontinuity, so a session integral never adds watts
     * from two interpretations. One instance per recording.
     */
    @Volatile
    private var interpretationTracker: CurrentInterpretationTracker? = null

    /**
     * The published calibration-revision the sampler has adopted.
     *
     * An explicit `resetCurrentCalibration()`/`saveCurrentCalibration()` always
     * advances the published revision, so a *no-op* reset (nothing was established
     * yet) is still detected and replaces the in-memory evidence instead of being
     * ignored by a stale calibrator. This is the only field that decides adoption:
     * the value itself is read from the same published snapshot.
     */
    @Volatile
    private var calibrationRevisionInUse = 0L

    private enum class Phase { IDLE, STARTING, RUNNING, STOPPING }

    private var phase = Phase.IDLE

    /** Set once the platform has destroyed this instance; no work may restart. */
    private var destroyed = false

    /** A Start that arrived while stopping; it is replayed instead of dropped. */
    private var pendingStart = false

    /** Invalidates in-flight start/sampler callbacks on every lifecycle change. */
    private var generation = 0

    private var activeToken = 0L
    private var lastNotifyElapsed = 0L

    /** Whether the notification currently describes a reading or its absence. */
    private var notificationShowsSample = false

    /**
     * Whether the notification currently shows a live reading (rather than
     * Calibrating/unavailable). Used like [notificationShowsSample]: the moment the
     * first calibrated reading arrives bypasses the routine throttle, so the
     * notification cannot keep saying "Calibrating" for up to a minute after the
     * dashboard already shows a reading. Deliberately keyed on the live metric, never
     * on the 60 s counter average.
     */
    private var notificationShowsLive = false
    private var notificationReadingState = LiveReading.WAITING
    private var telemetryError: String? = null
    private var sampleNotified = false
    private var wakeLockUnsupported = false

    /** Consecutive failed polls; the notice is debounced by [TelemetryFailureDebounce]. */
    private var consecutiveTelemetryFailures = 0

    /** Message of the most recent failed poll, republished once the debounce fires. */
    private var lastReadFailure: String? = null

    /** Start of the previous sample attempt, for the cadence and spacing floor. */
    private var lastSampleElapsed = 0L

    /**
     * Monotonic start of the bounded startup burst, or null when it is not running.
     * See [StartupSamplingPolicy]: at most [StartupSamplingPolicy.MAX_BURST_MILLIS]
     * of ~1 s polls to establish this recording's instantaneous current
     * interpretation. It never waits for the unrelated counter average, never changes
     * a setting and never adds a wake lock.
     *
     * Volatile because a calibration reset is noticed inside [calibrationFor] on the
     * IO dispatcher while the sampler loop reads/writes it on main. The value is
     * advisory only: a lost update can cost a few seconds of burst, never a sample.
     */
    @Volatile
    private var startupBurstStartElapsed: Long? = null

    /**
     * Whether a usable `CURRENT_NOW` reading has been seen since the burst began.
     * If the property is unsupported, its unit/polarity can never be established and
     * must not keep the burst alive. Volatile for the same main/IO reason as
     * [startupBurstStartElapsed].
     */
    @Volatile
    private var instantReadingSeen = false

    /**
     * Whether any successful poll has been observed since the burst began. Distinguishes
     * "`CURRENT_NOW` is unsupported" from "no sample has been read yet": a failed
     * first poll must not look like a missing property and end the burst prematurely.
     */
    @Volatile
    private var instantSampleObserved = false

    /** Event-trigger floor for the next poll; 1 s in a burst, the configured interval otherwise. */
    private var minimumSampleSpacingMillis = StartupSamplingPolicy.ABSOLUTE_MINIMUM_SPACING_MILLIS

    override fun onCreate() {
        super.onCreate()
        repository = MonitoringRepository.get(this)
        provider = BatteryTelemetryProvider(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop(startId)
            return START_NOT_STICKY
        }
        when (phase) {
            Phase.STARTING, Phase.RUNNING -> {
                // Repeated Start: keep the single sampler and the single session.
                val promoted = promoteForeground(
                    buildNotification(repository.state.value.latest, stopping = false),
                )
                if (!promoted) requestStop(startId)
            }
            Phase.STOPPING -> {
                // Never dropped: replay the start once the stop has finished.
                pendingStart = true
            }
            Phase.IDLE -> {
                // Promote first and abort before launching any work if it fails.
                if (!promoteForeground(buildNotification(sample = null, stopping = false))) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                sampleNotified = false
                notificationShowsLive = false
                launchStart()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Invalidate every in-flight callback before any cleanup so no stale job
        // can mark a newer or restarted recording as stopped.
        generation += 1
        phase = Phase.IDLE
        destroyed = true
        pendingStart = false
        startJob?.cancel()
        samplerJob?.cancel()
        settingsJob?.cancel()
        startJob = null
        samplerJob = null
        settingsJob = null
        unregisterBatteryReceiver()
        releaseWakeLock()
        releaseEstimator()
        val token = takeToken()
        if (token != 0L) {
            // The repository token guard keeps this stale cleanup from ending a
            // session that a newer service instance already started.
            cleanupScope.launch { endSession(token) }
        }
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Start ---------------------------------------------------------------

    private fun launchStart() {
        if (destroyed) return
        phase = Phase.STARTING
        val startedGeneration = ++generation
        startJob = scope.launch { startRecording(startedGeneration) }
    }

    private suspend fun startRecording(startedGeneration: Int) {
        var token = 0L
        try {
            repository.load()
            if (!isCurrent(startedGeneration)) return
            withContext(NonCancellable) {
                // Capture and assign inside the non-cancellable block: a stop or
                // destroy landing between the database insert and the assignment
                // must not orphan a live repository token.
                token = repository.beginRecording()
                activeToken = token
            }
            if (!isCurrent(startedGeneration)) {
                if (activeToken == token) activeToken = 0L
                endSession(token)
                return
            }
            observeSettings(startedGeneration)
            applyMode(repository.settings.value.mode)
            updateNotification(sample = null, force = true)
            // Fresh baseline for this recording; the first polls only anchor it.
            currentEstimator = CurrentEstimator()
            // Calibration evidence also starts fresh per recording, but an already
            // established calibration is the starting value: it is device state, not
            // session state, and must not be lost when recording stops and starts.
            val published = repository.state.value
            calibrationRevisionInUse = published.calibrationRevision
            currentCalibrator = CurrentCalibrator(initial = published.calibration)
            startupInterpreter.reset()
            // Fresh interpretation baseline. The first poll of a recording has no
            // earlier sample it could be incomparable with, so it never gaps.
            interpretationTracker = CurrentInterpretationTracker()
            // Five roughly one-second polls enable a labelled startup estimate;
            // the longer counter check continues at normal cadence afterwards.
            // Errors cannot extend the startup burst past its 90 s safety cap.
            // No setting mutation or additional wake lock.
            startStartupBurst()
            phase = Phase.RUNNING
            samplerJob = scope.launch { sampleLoop(startedGeneration) }
        } catch (cancelled: CancellationException) {
            // Never leave a token this start created alive without a sampler.
            if (token != 0L && activeToken == token) activeToken = 0L
            endSession(token)
            throw cancelled
        } catch (failure: Throwable) {
            fail(startedGeneration, failure)
        }
    }

    /**
     * One sampler. Never busy-waits: it samples, then sleeps until the cadence
     * elapses or a wake-up trigger arrives — a settings change (so cadence and
     * wake-lock mode follow) or a platform `ACTION_BATTERY_CHANGED` delivery (so a
     * real change shows up immediately instead of up to one interval later).
     *
     * The cadence comes from [StartupSamplingPolicy]: ~1 s only inside the bounded
     * startup burst that establishes the recording's instantaneous interpretation,
     * then exactly the user's configured 1/2/5 s interval. Both waits are measured
     * from the poll *attempt*.
     */
    private suspend fun sampleLoop(samplerGeneration: Int) {
        try {
            while (currentCoroutineContext().isActive && isCurrent(samplerGeneration)) {
                lastSampleElapsed = SystemClock.elapsedRealtime()
                val sample = readSample()
                if (sample == null) {
                    // A single failed poll must not publish an error: one transient
                    // provider hiccup would flash the UI warning on and off at every
                    // tick. The notice appears only once consecutive polls have
                    // failed, and one success clears it. Durable-write failures are
                    // untouched by this debounce — the repository publishes those
                    // immediately (they lose recording).
                    consecutiveTelemetryFailures += 1
                    val message = lastReadFailure
                    if (message != null &&
                        TelemetryFailureDebounce.onFailure(
                            consecutiveFailures = consecutiveTelemetryFailures,
                            alreadyNotified = telemetryError != null,
                        ) == TelemetryFailureDebounce.Decision.NOTICE
                    ) {
                        telemetryError = message
                        repository.reportError(message)
                    }
                    // Telemetry failed: drop the counter baseline so recovery starts
                    // a fresh measured window instead of spanning the outage.
                    currentEstimator?.reset()
                    // The calibrator's open window and pending run would otherwise
                    // integrate the polls on both sides of the missing one. Only that
                    // unestablished evidence is dropped: the calibration itself is
                    // device state (saved, or discovered but not yet on disk) and must
                    // survive a transient read failure.
                    currentCalibrator?.discardPendingEvidence()
                    startupInterpreter.discardPendingEvidence()
                    // Telemetry failed: release the lease and save any earlier
                    // good readings; a prolonged provider outage must not leave
                    // a small pending batch uncommitted indefinitely.
                    releaseWakeLock()
                    repository.flush()
                    // Throttled like a routine update: the sample-vs-waiting change
                    // itself bypasses the throttle once, so a single failed poll
                    // does not repaint the notification on every tick.
                    updateNotification(sample = null, force = false)
                } else {
                    // A failed poll is unobserved time. Even when the outage is
                    // shorter than the repository's gap threshold, the samples on
                    // either side must not be integrated as one window.
                    val recoveredFromFailure = consecutiveTelemetryFailures > 0
                    consecutiveTelemetryFailures = 0
                    lastReadFailure = null
                    val recovered = telemetryError != null
                    if (TelemetryFailureDebounce.onSuccess(hadNotice = recovered) ==
                        TelemetryFailureDebounce.Decision.CLEAR
                    ) {
                        telemetryError = null
                        // Clears only the poll notice; a durability failure stays.
                        // Missing properties are not failures at all (they decode to
                        // null fields), so this debounce never sees them.
                        repository.clearError()
                    }
                    if (recoveredFromFailure && repository.state.value.latest != null) {
                        // Only when a sample precedes the outage: with nothing recorded
                        // yet there is no earlier reading to bridge, so the first sample
                        // of the recording stays an ordinary start.
                        repository.noteDiscontinuity(SampleReasons.GAP)
                    }
                    repository.record(sample)
                    if (repository.settings.value.mode == MonitoringMode.HIGH_RESOLUTION) {
                        renewWakeLock()
                    }
                    updateNotification(sample = sample, force = !sampleNotified || recovered)
                    sampleNotified = true
                    // Remember whether the platform exposes a usable instantaneous
                    // reading at all: an unsupported property can never be calibrated,
                    // so it must not keep the startup burst alive. A successful poll is
                    // recorded separately so a single failed read before the first
                    // sample cannot be mistaken for "unsupported".
                    instantSampleObserved = true
                    if (sample.currentSource == CurrentSource.INSTANT ||
                        sample.currentSource == CurrentSource.INSTANT_ESTIMATED
                    ) instantReadingSeen = true
                }
                // Cadence: the bounded startup burst first (1 s, max 90 s), then the
                // user's configured 1/2/5 s interval. Only this loop's own wait
                // changes — no setting is written and no wake lock is involved.
                val decision = StartupSamplingPolicy.decide(
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                    burstStartElapsedMillis = startupBurstStartElapsed,
                    instantCalibrationEstablished = sample?.currentCalibrated == true,
                    startupCheckComplete = sample?.startupCheckComplete == true,
                    // "Supported" means a usable reading exists, or no successful poll
                    // has happened yet so absence is not evidence. The counter average
                    // deliberately plays no part: the live reading is primary.
                    instantReadingSupported = !instantSampleObserved || instantReadingSeen,
                    configuredIntervalMillis =
                        repository.settings.value.intervalSeconds.coerceAtLeast(1) * 1_000L,
                )
                startupBurstStartElapsed = decision.burstStartElapsedMillis
                minimumSampleSpacingMillis = decision.minimumSpacingMillis
                awaitCadence(decision.intervalMillis)
                awaitMinimumSampleSpacing()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(samplerGeneration, failure)
        }
    }

    /**
     * Sleeps for at most [timeoutMillis], returning early on either trigger.
     *
     * `select` (not two awaits back to back) keeps the cadence exact: whichever
     * trigger arrives first ends the wait and the other stays pending.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitWakeUp(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            select<Unit> {
                settingsChanged.onReceive { }
                batteryChanged.onReceive { }
            }
        }
    }

    /**
     * Waits out the remainder of [cadenceMillis], measured from the previous poll
     * **attempt**, so a poll's own work cannot stretch the cadence (relevant for the
     * 1 s startup burst). A settings or battery trigger can end the wait early; the
     * spacing floor is applied afterwards by [awaitMinimumSampleSpacing].
     */
    private suspend fun awaitCadence(cadenceMillis: Long) {
        val remaining = cadenceMillis - (SystemClock.elapsedRealtime() - lastSampleElapsed)
        if (remaining > 0L) awaitWakeUp(remaining)
    }

    /**
     * Floor on the gap between two samples, measured from the previous attempt.
     *
     * `ACTION_BATTERY_CHANGED` can be delivered many times per second (a charging
     * phone updates the charge counter constantly), and each delivery would
     * otherwise start a sample: platform reads, a repository write decision and a
     * rerender. So after a trigger is collected the sampler waits out whatever is
     * left of [minimumSampleSpacingMillis] in one plain `delay` — bounded, no busy
     * loop — and then drops any trigger that arrived while waiting, because the
     * sample it asks for is the one about to run. The floor is 1 s only inside the
     * bounded startup burst; afterwards it is the **configured interval itself**, so
     * a 1 s selection is honoured and a 2 s/5 s selection cannot be sped up by a
     * burst of platform broadcasts.
     */
    private suspend fun awaitMinimumSampleSpacing() {
        val remaining =
            minimumSampleSpacingMillis - (SystemClock.elapsedRealtime() - lastSampleElapsed)
        if (remaining <= 0L) return
        delay(remaining)
        // CONFLATED, so this discards at most the single token a burst left behind.
        batteryChanged.tryReceive()
    }

    private suspend fun readSample(): BatterySample? = try {
        // The provider touches BatteryManager and a sticky broadcast: never on main.
        withContext(Dispatchers.IO) {
            val raw = provider.read()
            // Counter deltas give the trailing average rate; CURRENT_NOW is the instant
            // reading. Neither is substituted for the other here.
            val estimate = currentEstimator
                ?.update(raw.elapsedRealtimeMillis, raw.chargeCounterMicroampHours)
            // Read the overrides per sample: they can be changed while recording and
            // must apply from the next reading on.
            val settings = repository.settings.value
            val confirmed = calibrationFor(raw)
            val startup = if (confirmed.calibrated) FastCurrentInterpreter.Result() else {
                startupInterpreter.update(raw, settings.currentUnit, settings.currentPolarity)
            }
            val tentative = startup.interpretation?.takeIf { it.calibrated }
            val calibration = if (confirmed.calibrated) confirmed else tentative ?: confirmed
            // Compare the effective interpretation, not a sample's
            // currentUnitAdjusted/currentPolarityAdjusted flags: those describe an
            // adjustment actually applied to a non-zero value, so they are both false
            // for a zero reading that is still read differently after the change.
            val interpretationChanged = interpretationTracker
                ?.observe(CurrentInterpretation.of(settings, calibration)) == true
            val sample = BatteryTelemetryDecoder.decode(
                raw = raw,
                derivedAmps = estimate?.amps,
                derivedWindowMillis = estimate?.windowMillis,
                currentUnit = settings.currentUnit,
                currentPolarity = settings.currentPolarity,
                calibration = calibration,
                provisional = !confirmed.calibrated && tentative != null,
                startupPollCount = startup.pollCount,
                startupCheckComplete = startup.complete,
            )
            if (interpretationChanged) {
                // Mark the first differently interpreted sample and leave every
                // earlier sample and running total untouched: they were correct under
                // the interpretation in force then. A per-sample caveat already on the
                // reading wins the single reason token because the gap is flagged
                // regardless.
                sample.copy(
                    gapBefore = true,
                    reason = sample.reason ?: SampleReasons.CURRENT_CALIBRATION_CHANGED,
                )
            } else {
                sample
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // Never published from here: the sampler decides, so a lone failure stays
        // silent (see TelemetryFailureDebounce).
        lastReadFailure = getString(R.string.error_sampling_failed, describe(failure))
        null
    }

    /**
     * Feeds one raw poll to the calibrator and returns the calibration that interprets
     * it.
     *
     * Provenance is deliberately *not* logged per poll: the values travel with the
     * sample into the database and the CSV export, which is where they belong. A
     * per-poll logcat line was pure noise at every selectable cadence.
     *
     * The calibrator returns stable established evidence until it is explicitly reset,
     * so after the first established result nothing more is published. An external
     * reset/save arrives through the published *revision*: a reset always bumps it,
     * even when the stored value was already empty, so evidence gathered before the
     * reset can neither survive in the calibrator nor be republished by this poll.
     *
     * The calibrator monitor is never held across the repository call: `update`
     * returns its value, then the durable publish is awaited. On a lost race the
     * returned snapshot is authoritative and the sample's stale evidence is dropped.
     */
    private suspend fun calibrationFor(raw: RawBatteryReading): CurrentCalibration {
        // One read of the published state, so calibration and revision cannot tear.
        val published = repository.state.value
        val calibrator = currentCalibrator ?: return published.calibration
        if (CalibrationPublishState.needsAdoption(
                publishedRevision = published.calibrationRevision,
                adoptedRevision = calibrationRevisionInUse,
            )
        ) {
            // The user reset or saved a calibration (possibly after this poll was
            // requested): it wins over any in-memory evidence, reset included.
            calibrator.reset(published.calibration)
            startupInterpreter.reset()
            calibrationRevisionInUse = published.calibrationRevision
            // An explicit reset/save also re-opens the bounded startup burst: the
            // user has just asked for fresh evidence, so sampling briefly again is
            // the point of the action. The counter average is untouched (it is
            // independent of the instantaneous unit/polarity) and the burst still
            // expires on the same 90 s cap — repeated resets can extend it only by as
            // many times as the user actually asks. Adoption happens on the next poll,
            // so a reset while the sampler is asleep starts the burst up to one
            // configured interval later; that is bounded by the user's 1/2/5 s
            // cadence and needs no extra wake-up trigger.
            startStartupBurst()
        }
        val updated = calibrator.update(raw)
        if (!updated.calibrated || updated == published.calibration) return updated
        val publish = try {
            repository.publishCalibration(updated, expectedRevision = calibrationRevisionInUse)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A failed publish must not cost the reading: the calibrator keeps
            // returning this value, so the next poll retries against the same
            // revision.
            return updated
        }
        return when (publish.result) {
            CalibrationPublishResult.ACCEPTED, CalibrationPublishResult.ALREADY_CURRENT ->
                publish.calibration
            // The evidence is real, only its durability failed. Decode with it and
            // let the next poll retry the write; the published state stays as-is.
            CalibrationPublishResult.NOT_SAVED -> updated
            CalibrationPublishResult.STALE_REVISION -> {
                // A reset/save landed while this sample was in flight: abandon its
                // stale evidence and decode with the state that actually won.
                calibrator.reset(publish.calibration)
                startupInterpreter.reset()
                calibrationRevisionInUse = publish.revision
                publish.calibration
            }
        }
    }

    /**
     * Registers the `ACTION_BATTERY_CHANGED` trigger for the life of the service.
     *
     * Not exported: only the system sends this broadcast, and no other app should be
     * able to drive our sampler. A failed registration costs latency only — the poll
     * still runs — so it is not a recording failure.
     */
    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            batteryReceiverRegistered = true
        } catch (failure: Throwable) {
            batteryReceiverRegistered = false
            if (BuildConfig.DEBUG) Log.d(TAG, "battery trigger unavailable: ${describe(failure)}")
        }
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) return
        batteryReceiverRegistered = false
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Throwable) {
            // Already gone, or the process is being torn down.
        }
    }

    private fun observeSettings(observedGeneration: Int) {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            var previous = repository.settings.value
            repository.settings.collect { current ->
                if (!isCurrent(observedGeneration)) return@collect
                if (current == previous) return@collect
                val modeChanged = current.mode != previous.mode
                val intervalChanged = current.intervalSeconds != previous.intervalSeconds
                val notificationRateChanged = current.notificationUpdateRate != previous.notificationUpdateRate
                val interpretationChanged =
                    current.currentUnit != previous.currentUnit ||
                        current.currentPolarity != previous.currentPolarity
                previous = current
                if (modeChanged || intervalChanged) {
                    // Cadence/mode changes are documented discontinuities: the counter
                    // window that spans them is not a comparable average any more.
                    currentEstimator?.reset()
                    repository.noteDiscontinuity(
                        if (intervalChanged) SampleReasons.INTERVAL_CHANGED else SampleReasons.MODE_CHANGED,
                    )
                    applyMode(current.mode)
                    // Wake the sampler so the new cadence starts immediately.
                    settingsChanged.trySend(Unit)
                    updateNotification(sample = repository.state.value.latest, force = true)
                } else if (interpretationChanged) {
                    // Only the interpretation of the instantaneous current changed.
                    // The counter-derived trailing rate is unaffected, so it keeps its
                    // window; the sample read next records the transition itself as a
                    // discontinuity (see readSample). Waking the sampler makes that
                    // sample the first one under the new interpretation immediately.
                    settingsChanged.trySend(Unit)
                }
                if (notificationRateChanged && !modeChanged && !intervalChanged) {
                    // Apply now without waking the sampler, resetting calibration,
                    // or marking an energy/history gap for a presentation-only choice.
                    updateNotification(sample = repository.state.value.latest, force = true)
                }
            }
        }
    }

    // --- Stop / failure ------------------------------------------------------

    private fun requestStop(startId: Int) {
        when (phase) {
            Phase.IDLE -> {
                stopSelf(startId)
            }
            Phase.STOPPING -> {
                // A second Stop wins over a deferred Start; the in-flight stop
                // already ends the session exactly once.
                pendingStart = false
            }
            Phase.STARTING, Phase.RUNNING -> beginStop(startId)
        }
    }

    private fun beginStop(startId: Int) {
        phase = Phase.STOPPING
        // Cancel and invalidate startup/sampler callbacks *before* ending the
        // session so no in-flight sample can extend it after the final flush.
        generation += 1
        startJob?.cancel()
        samplerJob?.cancel()
        settingsJob?.cancel()
        startJob = null
        samplerJob = null
        settingsJob = null
        releaseWakeLock()
        releaseEstimator()
        // Best effort: the service is normally already foreground here.
        promoteForeground(buildNotification(repository.state.value.latest, stopping = true))
        val token = takeToken()
        scope.launch {
            withContext(NonCancellable) {
                endSession(token)
                if (!destroyed && pendingStart) {
                    pendingStart = false
                    phase = Phase.IDLE
                    launchStart()
                } else {
                    phase = Phase.IDLE
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun fail(failedGeneration: Int, failure: Throwable) {
        if (failedGeneration != generation || phase == Phase.STOPPING) return
        phase = Phase.STOPPING
        generation += 1
        val current = currentCoroutineContext()[Job]
        if (startJob !== current) startJob?.cancel()
        if (samplerJob !== current) samplerJob?.cancel()
        if (settingsJob !== current) settingsJob?.cancel()
        startJob = null
        samplerJob = null
        settingsJob = null
        releaseWakeLock()
        releaseEstimator()
        val token = takeToken()
        withContext(NonCancellable) {
            if (failure !is MonitoringPersistenceException) {
                // A storage failure already published its own, more accurate message.
                try {
                    repository.reportError(getString(R.string.error_start_failed, describe(failure)))
                } catch (_: Throwable) {
                    // Nothing else can surface this at this point.
                }
            }
            endSession(token)
            phase = Phase.IDLE
            stopSelf()
        }
    }

    /**
     * Ends one repository token exactly once. [MonitoringRepository.endRecording]
     * refuses a superseded token and is already non-cancellable at the database,
     * so a late cleanup can never stop a newer session.
     */
    private suspend fun endSession(token: Long) {
        if (token == 0L) return
        withContext(NonCancellable) {
            try {
                repository.endRecording(token)
            } catch (_: Throwable) {
                // The repository surfaces any failure through its published state.
            }
        }
    }

    private fun isCurrent(startedGeneration: Int): Boolean =
        startedGeneration == generation &&
            (phase == Phase.STARTING || phase == Phase.RUNNING)

    /** Caller runs on the main thread. */
    private fun takeToken(): Long {
        val token = activeToken
        activeToken = 0L
        return token
    }

    // --- Wake lock -----------------------------------------------------------

    private fun applyMode(mode: MonitoringMode) {
        if (mode == MonitoringMode.HIGH_RESOLUTION && activeToken != 0L) {
            renewWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    /**
     * Timed partial wake-lock lease, renewed while high-resolution recording runs.
     * Never a bright/display lock.
     */
    private fun renewWakeLock() {
        // A stop that raced a sample in flight must not re-acquire the lease.
        if (phase != Phase.RUNNING && phase != Phase.STARTING) return
        if (activeToken == 0L) return
        if (wakeLockUnsupported) return
        if (repository.settings.value.mode != MonitoringMode.HIGH_RESOLUTION) return
        try {
            val existing = wakeLock
            if (existing == null) {
                val powerManager = getSystemService(PowerManager::class.java)
                if (powerManager == null ||
                    !powerManager.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK)
                ) {
                    wakeLockUnsupported = true
                    repoScopeError(getString(R.string.error_wake_lock_unsupported))
                    return
                }
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG,
                ).apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(WAKE_LOCK_LEASE_MILLIS)
            repository.setWakeLockHeld(wakeLock?.isHeld == true)
        } catch (failure: Throwable) {
            releaseWakeLock()
            repoScopeError(getString(R.string.error_wake_lock, describe(failure)))
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null) {
            try {
                if (lock.isHeld) lock.release()
            } catch (_: Throwable) {
                // Nothing useful to do if the system already dropped the lease.
            }
        }
        repository.setWakeLockHeld(false)
    }

    /**
     * Drops the per-recording estimator, calibrator and interpretation baseline;
     * stale evidence must not feed a new session, and the startup burst is over.
     */
    private fun releaseEstimator() {
        currentEstimator?.reset()
        currentEstimator = null
        currentCalibrator = null
        startupInterpreter.reset()
        interpretationTracker?.reset()
        interpretationTracker = null
        startupBurstStartElapsed = null
        instantReadingSeen = false
        instantSampleObserved = false
        minimumSampleSpacingMillis = StartupSamplingPolicy.ABSOLUTE_MINIMUM_SPACING_MILLIS
    }

    /**
     * Opens a bounded startup burst (see [StartupSamplingPolicy]).
     *
     * Only loop-local state changes: no wake lock is acquired, no busy loop is
     * created and no setting is written, so the configured cadence and the
     * background cost of an ordinary recording are unchanged once the cap expires.
     */
    private fun startStartupBurst() {
        val decision = StartupSamplingPolicy.start(SystemClock.elapsedRealtime())
        startupBurstStartElapsed = decision.burstStartElapsedMillis
        minimumSampleSpacingMillis = decision.minimumSpacingMillis
        instantReadingSeen = false
        instantSampleObserved = false
    }

    private fun repoScopeError(message: String) {
        scope.launch {
            try {
                repository.reportError(message)
            } catch (_: CancellationException) {
                // Service is going away.
            }
        }
    }

    // --- Notification --------------------------------------------------------

    /** Returns true only when this service is actually in the foreground. */
    private fun promoteForeground(notification: Notification): Boolean {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            true
        } catch (failure: Throwable) {
            repoScopeError(getString(R.string.error_start_failed, describe(failure)))
            false
        }
    }

    /**
     * Routine updates follow the selected Instant/15s/60s rate; a change between
     * "a reading" and "no reading", or between "not live yet" and "live reading
     * available", is shown at once, because a stale line beside a fresh state would
     * misreport the app. The live transition matters because the notification shows
     * interpreted instantaneous power/current with its confidence: Estimated must
     * become confirmed at once, and an ambiguous completed check must not look
     * stuck. The 60 s counter average deliberately has no say here.
     */
    private fun updateNotification(sample: BatterySample?, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val hasLive = sample?.liveAmps != null
        if (!NotificationUpdatePolicy.shouldUpdate(
                rate = repository.settings.value.notificationUpdateRate,
                nowElapsedMillis = now,
                lastUpdateElapsedMillis = lastNotifyElapsed,
                force = force,
                previousShowsSample = notificationShowsSample,
                previousShowsLive = notificationShowsLive,
                sample = sample,
                previousLiveReading = notificationReadingState,
            )
        ) {
            return
        }
        if (!canPostNotifications()) return
        lastNotifyElapsed = now
        notificationShowsSample = sample != null
        notificationShowsLive = hasLive
        notificationReadingState = sample.liveReading()
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(sample, stopping = false))
        } catch (_: SecurityException) {
            // Notifications may be denied; the foreground service still runs.
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(sample: BatterySample?, stopping: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = getString(
            if (stopping) R.string.notification_title_stopping else R.string.notification_title_running,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(title)
            .setContentText(notificationText(sample))
            .setWhen(sample?.timestampMillis ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    /**
     * Ongoing-notification line: live power/current with explicit confidence, or an
     * explicit Calibrating/Unavailable state.
     *
     * A startup estimate is explicitly labelled, never presented as confirmed.
     * Counter-average values remain auxiliary and cannot replace CURRENT_NOW.
     *
     * The first five polls report Checking N/5. A tentative interpretation shows
     * Estimated beside the actual instantaneous reading; an ambiguous completed
     * check is unavailable rather than an endless Calibrating. A charge counter
     * is not required for the short startup check.
     */
    private fun notificationText(sample: BatterySample?): String {
        val percent = sample?.percentage?.let { getString(R.string.percent_format, it) }
            ?: getString(R.string.notification_level_unavailable)
        return when (sample.liveReading()) {
            LiveReading.WAITING -> getString(R.string.notification_text_waiting)
            LiveReading.CALIBRATING -> getString(
                R.string.notification_text_live_pending,
                if ((sample?.startupPollCount ?: 0) > 0) {
                    getString(R.string.notification_live_checking, sample!!.startupPollCount.coerceIn(1, 5))
                } else getString(R.string.notification_live_calibrating),
                percent,
            )
            LiveReading.UNAVAILABLE -> getString(
                R.string.notification_text_live_pending,
                getString(if (sample?.startupCheckComplete == true) {
                    R.string.notification_live_ambiguous
                } else R.string.notification_live_unavailable),
                percent,
            )
            LiveReading.ESTIMATED -> getString(R.string.notification_live_estimated) + " · " +
                getString(
                    R.string.notification_text_live_reading,
                    sample?.liveWatts?.let { Units.powerText(it, absolute = false) }
                        ?: getString(R.string.notification_power_unavailable),
                    Units.currentText(sample?.liveAmps, absolute = false),
                    percent,
                )
            LiveReading.READY -> getString(
                R.string.notification_text_live_reading,
                // Same magnitude-aware formatter as the dashboard: a phone idles in
                // the milliwatt range, so a hard-coded "0.00 W" would read as "no
                // power". Power may be independently unavailable (invalid voltage or
                // an inconsistent sign) without hiding the confirmed live current or
                // the percentage.
                sample?.liveWatts?.let { Units.powerText(it, absolute = false) }
                    ?: getString(R.string.notification_power_unavailable),
                Units.currentText(sample?.liveAmps, absolute = false),
                percent,
            )
        }
    }

    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName

    companion object {
        const val ACTION_START = "io.github.anevilburrito.wpm.START_MONITORING"
        const val ACTION_STOP = "io.github.anevilburrito.wpm.STOP_MONITORING"

        private const val CHANNEL_ID = "monitoring"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "wpm:high-resolution-monitoring"
        private const val WAKE_LOCK_LEASE_MILLIS = 10L * 60 * 1000

        /** See [TelemetryFailureDebounce.CONSECUTIVE_FAILURES_BEFORE_NOTICE]. */
        const val CONSECUTIVE_FAILURES_BEFORE_NOTICE =
            TelemetryFailureDebounce.CONSECUTIVE_FAILURES_BEFORE_NOTICE

        /**
         * Absolute floor on the spacing between samples when no configured interval
         * is known yet, so a burst of platform broadcasts cannot start a storm. Once
         * a cadence is known, [StartupSamplingPolicy] returns the selected interval as
         * the floor (1 s is honoured; 2 s/5 s cannot be sped up by events).
         */
        const val MIN_SAMPLE_SPACING_MILLIS = StartupSamplingPolicy.ABSOLUTE_MINIMUM_SPACING_MILLIS

        private const val TAG = "WpmMonitoring"
    }
}
