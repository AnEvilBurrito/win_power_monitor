package io.github.anevilburrito.wpm.monitoring

import android.content.Context
import android.os.SystemClock
import io.github.anevilburrito.wpm.R
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CapacityAggregate
import io.github.anevilburrito.wpm.model.CapacityEstimate
import io.github.anevilburrito.wpm.model.CapacityEstimator
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitorUiState
import io.github.anevilburrito.wpm.model.SessionAccumulator
import io.github.anevilburrito.wpm.model.SessionSummary
import io.github.anevilburrito.wpm.model.liveWatts
import io.github.anevilburrito.wpm.model.gapThresholdMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Single owner of published monitoring state, persisted history and export.
 *
 * The activity only reads [state]/[settings]; the foreground service owns sampling
 * and calls the `internal` recording methods. All mutation is serialised through
 * one [Mutex] and every database touch runs on [Dispatchers.IO], so no caller
 * blocks the main thread and no asynchronous cleanup can overwrite a newer
 * session.
 */
class MonitoringRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = MonitoringStore(appContext)
    private val settingsStore = SettingsStore(appContext)
    private val mutex = Mutex()

    private val mutableState = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = mutableState.asStateFlow()

    private val mutableSettings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<MonitorSettings> = mutableSettings.asStateFlow()

    private val mutableWakeLockHeld = MutableStateFlow(false)

    /** Diagnostics: whether the high-resolution partial wake lock is held. */
    val wakeLockHeld: StateFlow<Boolean> = mutableWakeLockHeld.asStateFlow()

    private var loadCompleted = false
    private var recordingToken = 0L
    private var tokenCounter = 0L
    private var sessionId = 0L
    private var accumulator: SessionAccumulator? = null
    private var summary = SessionSummary()

    /**
     * Whether [summary] was accumulated by this process with the interpreted live
     * (instantaneous) metric.
     *
     * A summary read back from storage carries no metric marker: sessions recorded by
     * an earlier build summed raw instantaneous watts, or the former counter average,
     * and those stored numbers are never rewritten (history keeps its provenance). So
     * the export states `summary_metric=instant_interpreted` only when this process
     * actually produced the numbers, and an explicit "stored, metric unknown"
     * otherwise — never an unearned claim.
     */
    private var summaryUsesLivePower = false
    private var samples = ArrayList<BatterySample>()
    private var pending = ArrayList<BatterySample>()
    private var committedSamples = 0
    private var lastFlushElapsed = 0L
    private var lastPruneElapsed = 0L
    private var previousElapsed: Long? = null
    private var pendingGapReason: String? = null
    private var truncated = false

    /**
     * Observed-capacity state.
     *
     * [capacityEstimator] is stateful: it watches one monotonic charge or discharge window at a
     * time and reports only when that window spanned
     * [CapacityEstimator.MINIMUM_PERCENT_SPAN] percentage points of the reported
     * gauge, so a recorded poll is *evidence*, not an observation.
     * [capacityAggregate] is the durable, session-spanning average of the completed
     * observations and [capacityUpdatedAt] when it last changed. [capacityDirty]
     * means the in-memory aggregate is ahead of `capacity_state`; because a completed
     * observation is rare the write is only [throttled][CapacityPersistence] against a
     * burst, and it is flushed at stop. An empty aggregate means "not observed yet",
     * never zero.
     */
    private val capacityEstimator = CapacityEstimator()
    private var capacityAggregate = CapacityAggregate()
    private var capacityUpdatedAt: Long? = null
    private var capacityDirty = false
    private var lastCapacityPersistElapsed = 0L

    /**
     * Established current calibration: published in [MonitorUiState.calibration] and
     * persisted separately from [MonitorSettings] because it is evidence, not a
     * preference.
     *
     * The sampler owns the in-memory calibrator; this is the durable copy. Making it
     * part of the published state means a reset is visible to that calibrator on its
     * next poll, so the two can never silently disagree. The accompanying revision
     * (see [CalibrationPublishState]) is the part that makes a reset win against
     * evidence a sampler gathered *before* it.
     */
    private val calibrationState = CalibrationPublishState()

    private val calibration: CurrentCalibration get() = calibrationState.value

    private val calibrationRevision: Long get() = calibrationState.revision

    /**
     * A recoverable notice, e.g. a failed poll or an interrupted old session.
     *
     * [MonitorUiState.error] is reserved for real recording problems (poll
     * failures, durability failures, data loss, start failures). Ordinary advice —
     * notably "notifications are disabled" — must never be routed here: it is a
     * permission preference, not a recording failure, and putting it in this slot
     * makes the dashboard look broken while recording is healthy. Such advice
     * belongs to the host/developer diagnostics surface instead.
     */
    private var transientError: String? = null

    /**
     * Recoverable notice for a failed `capacity_state` write. Kept apart from
     * [transientError] so a later successful capacity write clears only its own
     * notice and never a poll/start failure message.
     */
    private var capacityError: String? = null

    /** Sticky storage failure; cleared only by a later successful durable write. */
    private var persistenceError: String? = null

    /** Permanent data loss; only a new session clears this. */
    private var lostSamplesError: String? = null

    /**
     * Restores the latest saved session as STOPPED. Idempotent and safe when the
     * activity and the service call it concurrently.
     */
    suspend fun load() {
        mutex.withLock {
            if (loadCompleted) return
            try {
                // Persisted capacity and calibration are independent of the session
                // history: a failure to read either must not hide the samples, so
                // each is restored in its own guarded step.
                restoreCapacityLocked()
                restoreCalibrationLocked()
                val stored = withContext(Dispatchers.IO) { store.latestSession() }
                if (stored == null) {
                    summary = SessionSummary()
                    samples = ArrayList()
                    sessionId = 0L
                    committedSamples = 0
                    truncated = false
                    // Nothing was measured here, so no metric can be claimed.
                    summaryUsesLivePower = false
                } else {
                    var restored = stored.summary
                    // A session without an end marker was interrupted. Label it
                    // stopped rather than fabricating a resumption.
                    if (restored.endedAtMillis == null) {
                        val lastSample = withContext(Dispatchers.IO) {
                            store.lastSampleTimestamp(restored.id)
                        }
                        val endedAt = maxOf(restored.startedAtMillis, lastSample ?: restored.startedAtMillis)
                        restored = restored.copy(endedAtMillis = endedAt)
                        withContext(Dispatchers.IO) { store.finishSession(restored) }
                        transientError = appContext.getString(R.string.error_session_interrupted)
                    }
                    val restoredSamples = withContext(Dispatchers.IO) {
                        store.loadSamples(restored.id, MAX_UI_SAMPLES)
                    }
                    val storedCount = withContext(Dispatchers.IO) {
                        store.storedSampleCount(restored.id)
                    }
                    summary = restored
                    // A restored summary came from the database, which does not mark
                    // which metric it summed: do not claim the live metric for it.
                    summaryUsesLivePower = false
                    samples = ArrayList(restoredSamples)
                    sessionId = restored.id
                    committedSamples = storedCount
                    truncated = stored.historyTruncated || storedCount < restored.sampleCount
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                persistenceError = appContext.getString(R.string.error_history_unavailable, describe(failure))
            }
            loadCompleted = true
            previousElapsed = null
            pendingGapReason = null
            // Nothing has been observed in this process yet.
            publishLocked()
        }
    }

    /** Validates and persists settings, then publishes them immediately. */
    fun saveSettings(value: MonitorSettings) {
        val normalized = value.normalized()
        settingsStore.save(normalized)
        if (mutableSettings.value != normalized) mutableSettings.value = normalized
    }

    /**
     * Exports the retained sample history of the latest session as UTF-8 CSV.
     * Pending in-memory samples are flushed first. The caller owns [output].
     */
    suspend fun writeCsv(output: OutputStream) {
        val snapshot = mutex.withLock {
            if (recordingToken != 0L) {
                try {
                    flushLocked()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    val message = appContext.getString(R.string.error_history_unavailable, describe(failure))
                    persistenceError = message
                    publishLocked()
                    // Never present a partially saved session as a successful export.
                    throw IOException(message, failure)
                }
            }
            val settings = mutableSettings.value
            ExportSnapshot(
                sessionId = sessionId,
                summary = summary,
                historyTruncated = truncated,
                capacity = capacityEstimateLocked(),
                summaryMetric = if (summaryUsesLivePower) {
                    CsvExporter.SummaryMetric.INSTANT_INTERPRETED
                } else {
                    CsvExporter.SummaryMetric.STORED_UNKNOWN
                },
                // Provenance for the export: what the user supplied and which
                // calibration interpreted the instantaneous readings.
                designCapacityMilliampHours = settings.designCapacityMilliampHours,
                calibration = calibration,
                currentUnit = settings.currentUnit,
                currentPolarity = settings.currentPolarity,
            )
        }
        val rows = try {
            withContext(Dispatchers.IO) { store.loadSamples(snapshot.sessionId, MAX_EXPORT_SAMPLES) }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val message = appContext.getString(R.string.error_export_failed, describe(failure))
            mutex.withLock {
                persistenceError = message
                publishLocked()
            }
            throw IOException(message, failure)
        }
        val exportedTruncated = snapshot.historyTruncated || rows.size < snapshot.summary.sampleCount
        withContext(Dispatchers.IO) {
            CsvExporter.write(
                output = output,
                summary = snapshot.summary,
                samples = rows,
                historyTruncated = exportedTruncated,
                capacity = snapshot.capacity,
                designCapacityMilliampHours = snapshot.designCapacityMilliampHours,
                calibration = snapshot.calibration,
                currentUnit = snapshot.currentUnit,
                currentPolarity = snapshot.currentPolarity,
                summaryMetric = snapshot.summaryMetric,
            )
        }
    }

    /**
     * Clears the established current calibration: published and persisted state, and
     * (through the published revision) the sampler's in-memory evidence on its next
     * poll. The manual unit/polarity overrides in [settings] are user choices and are
     * kept: a manual correction is not evidence, so an unrelated reset must not erase
     * it.
     *
     * Always advances the published revision and always publishes, even when the
     * stored calibration is already empty. An in-flight sample can hold evidence
     * gathered before this call; if the reset were a no-op it would publish that
     * evidence afterwards and resurrect the cleared calibration.
     */
    suspend fun resetCurrentCalibration() {
        mutex.withLock { commitExplicitCalibrationLocked(CurrentCalibration()) }
    }

    /**
     * Saves (and publishes) the current calibration verbatim as an explicit user
     * write, so it also advances the revision and cannot be overridden by stale
     * sampler evidence.
     *
     * Deliberately not conditionally established: an uncalibrated value is a valid
     * "no evidence" record, and refusing it would silently keep a stale one. Use
     * [resetCurrentCalibration] to clear it intentionally.
     */
    suspend fun saveCurrentCalibration(value: CurrentCalibration) {
        mutex.withLock { commitExplicitCalibrationLocked(value) }
    }

    /**
     * Persists an explicit user write first, then makes it the published state.
     *
     * The durable write happens before the in-memory value changes so a failure is
     * recorded as [CalibrationPublishState.pendingSave] instead of being masked by a
     * deduplicating equality check on the next publish. It runs on [Dispatchers.IO]
     * because it is a synchronous preference commit.
     *
     * Must be called with [mutex] held.
     */
    private suspend fun commitExplicitCalibrationLocked(value: CurrentCalibration) {
        val persisted = persistCalibrationLocked(value)
        calibrationState.commitExplicit(value, durableWriteSucceeded = persisted)
        publishLocked()
    }

    /**
     * Publishes calibration evidence discovered by the sampler.
     *
     * Advisory: a "calibration" error is not what happens on a refused staleness
     * check; only [CalibrationPublish.result] tells the sampler what to do.
     *
     * @param expectedRevision the revision the sampler observed before it gathered
     * this evidence. Checked atomically inside the lock, so a user reset/save that
     * lands in between is never overwritten. A stale value is refused, and the
     * returned snapshot is what the sampler must adopt instead.
     * @return the published calibration/revision after the attempt. Only
     * [CalibrationPublishResult.ACCEPTED]/[CalibrationPublishResult.ALREADY_CURRENT]
     * confirm that the returned value is durable and authoritative.
     */
    internal suspend fun publishCalibration(
        value: CurrentCalibration,
        expectedRevision: Long,
    ): CalibrationPublish {
        return mutex.withLock {
            // Retry a durable write that failed earlier, even when there is no new
            // evidence: an in-memory-only calibration would otherwise never be saved.
            retryPendingCalibrationSaveLocked()
            val snapshot = calibrationPublish(CalibrationPublishResult.ALREADY_CURRENT)
            if (!value.calibrated) return@withLock snapshot
            when (calibrationState.precheck(expectedRevision, value)) {
                CalibrationPublishState.Precheck.STALE_REVISION ->
                    return@withLock calibrationPublish(CalibrationPublishResult.STALE_REVISION)
                CalibrationPublishState.Precheck.ALREADY_CURRENT -> return@withLock snapshot
                CalibrationPublishState.Precheck.WRITE -> {
                    val persisted = persistCalibrationLocked(value)
                    return@withLock when (
                        calibrationState.commitDiscovered(value, durableWriteSucceeded = persisted)
                    ) {
                        CalibrationPublishState.PublishResult.ACCEPTED -> {
                            publishLocked()
                            calibrationPublish(CalibrationPublishResult.ACCEPTED)
                        }
                        CalibrationPublishState.PublishResult.ALREADY_CURRENT -> snapshot
                        CalibrationPublishState.PublishResult.NOT_SAVED ->
                            calibrationPublish(CalibrationPublishResult.NOT_SAVED)
                        CalibrationPublishState.PublishResult.STALE_REVISION ->
                            calibrationPublish(CalibrationPublishResult.STALE_REVISION)
                    }
                }
            }
        }
    }

    /** Must be called with [mutex] held. */
    private fun calibrationPublish(result: CalibrationPublishResult) = CalibrationPublish(
        result = result,
        calibration = calibration,
        revision = calibrationRevision,
    )

    /** Must be called with [mutex] held. */
    private suspend fun retryPendingCalibrationSaveLocked() {
        if (!calibrationState.pendingSave) return
        if (persistCalibrationLocked(calibration)) calibrationState.markSaved()
    }

    /**
     * Must be called with [mutex] held; the preference commit runs off the main
     * thread and is non-cancellable, so a user reset that raced the activity being
     * destroyed is still recorded instead of being silently dropped.
     */
    private suspend fun persistCalibrationLocked(value: CurrentCalibration): Boolean = try {
        withContext(Dispatchers.IO + NonCancellable) { settingsStore.saveCalibration(value) }
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        false
    }

    /** Starts a new session. Returns the token that guards stop/cancel races. */
    internal suspend fun beginRecording(): Long = mutex.withLock {
        if (recordingToken != 0L) {
            // A previous service instance may still hold the old token. Hand out a
            // fresh one so its late cleanup can never end the session this caller
            // has just adopted; the session itself is reused, never duplicated.
            tokenCounter += 1
            recordingToken = tokenCounter
            return@withLock recordingToken
        }
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        val id = withContext(Dispatchers.IO) { store.createSession(startedAt) }
        tokenCounter += 1
        recordingToken = tokenCounter
        sessionId = id
        // A live session's summary integrates exactly the calibrated instantaneous
        // metric the readout and notification show ([liveWatts]). Uncalibrated or
        // decoder-suppressed readings select null, so they break the integral instead
        // of contributing a guessed value. Restored sessions keep their stored summary
        // fields, so history is never recomputed under a new metric.
        val fresh = SessionAccumulator(
            id = id,
            startedAtMillis = startedAt,
            startedElapsedRealtimeMillis = startedElapsed,
            samplePowerWatts = { it.liveWatts },
        )
        accumulator = fresh
        // From here until the next load() the summary is this process's own, built
        // from the live metric the export may therefore name.
        summaryUsesLivePower = true
        summary = fresh.snapshot()
        samples = ArrayList()
        pending = ArrayList()
        committedSamples = 0
        lastFlushElapsed = startedElapsed
        lastPruneElapsed = startedElapsed
        previousElapsed = null
        pendingGapReason = null
        truncated = false
        transientError = null
        persistenceError = null
        lostSamplesError = null
        // The capacity aggregate deliberately survives the session boundary: it is a
        // property of the installed battery, not of one recording. The open charging
        // window does not: it starts fresh at this recording's first usable sample.
        capacityEstimator.reset()
        publishLocked()
        recordingToken
    }

    /**
     * Ends [token]'s session after flushing its tail. Returns false when a newer
     * recording has superseded the token, so stale cleanup cannot stop it.
     */
    internal suspend fun endRecording(token: Long): Boolean = mutex.withLock {
        if (token == 0L || token != recordingToken) return@withLock false
        val active = accumulator
        recordingToken = 0L
        accumulator = null
        previousElapsed = null
        pendingGapReason = null
        // No further sample can complete an open window, so it is closed rather than
        // left to be resumed by a later recording with a different counter baseline.
        capacityEstimator.reset()
        val endedAt = System.currentTimeMillis()
        summary = active?.snapshot(endedAt) ?: summary.copy(endedAtMillis = endedAt)
        withContext(NonCancellable) {
            try {
                flushLocked()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                persistenceError = appContext.getString(R.string.error_history_unavailable, describe(failure))
            }
            try {
                val finalSummary = summary
                withContext(Dispatchers.IO) { store.finishSession(finalSummary) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                persistenceError = appContext.getString(R.string.error_history_unavailable, describe(failure))
            }
            // The tail of the capacity estimate is written now rather than waiting
            // for the next throttle window: nothing follows a stop to retry it.
            if (CapacityPersistence.isDueAtStop(capacityDirty)) persistCapacityLocked()
            // Same for an in-memory-only calibration: no poll follows a stop.
            retryPendingCalibrationSaveLocked()
        }
        publishLocked()
        true
    }

    /** Appends one polled sample, batching the database writes. */
    internal suspend fun record(sample: BatterySample) {
        mutex.withLock {
            if (recordingToken == 0L) return
            val active = accumulator ?: return
            val interval = mutableSettings.value.intervalSeconds
            val reason = pendingGapReason
            pendingGapReason = null
            val previous = previousElapsed
            val broken = reason != null || (
                previous != null &&
                    (sample.elapsedRealtimeMillis - previous <= 0L ||
                        sample.elapsedRealtimeMillis - previous > gapThresholdMillis(interval))
                )
            val stored = if (broken) {
                sample.copy(gapBefore = true, reason = sample.reason ?: reason ?: SampleReasons.GAP)
            } else {
                sample
            }
            // A discontinuity (reset, reboot, sleep gap, cadence/mode change) is not
            // part of a comparable window. The observed-capacity estimator sees the
            // same flag and restarts its window from this sample.
            previousElapsed = stored.elapsedRealtimeMillis
            summary = active.add(stored, interval)
            samples.add(stored)
            while (samples.size > MAX_UI_SAMPLES) samples.removeAt(0)
            pending.add(stored)
            if (pending.size > MAX_UNSAVED_SAMPLES) {
                pending.subList(0, pending.size - MAX_UNSAVED_SAMPLES).clear()
                lostSamplesError = appContext.getString(R.string.error_unsaved_samples_dropped)
            }
            accumulateCapacityLocked(stored)
            // A completed observation is rare, so a failed durable write would
            // otherwise sit in memory until the session stops: retry it (throttled)
            // on ordinary polls too.
            retryPendingCapacityLocked()
            // A calibration whose preference write failed is equally memory-only; the
            // guard makes a healthy poll free, and this keeps the retry from having to
            // wait for the next discovered value (which may never come).
            retryPendingCalibrationSaveLocked()
            publishLocked()
            val now = SystemClock.elapsedRealtime()
            val due = summary.sampleCount <= 1 ||
                pending.size >= FLUSH_MAX_SAMPLES ||
                now - lastFlushElapsed >= FLUSH_MAX_MILLIS
            if (due) {
                try {
                    flushLocked()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    val message = appContext.getString(R.string.error_history_unavailable, describe(failure))
                    persistenceError = message
                    publishLocked()
                    // Fatal: the caller must stop recording and release the wake
                    // lock instead of claiming the session is still being saved.
                    throw MonitoringPersistenceException(message, failure)
                }
            }
        }
    }

    /** Marks the next sample as the start of a new trace segment. */
    internal suspend fun noteDiscontinuity(reason: String) {
        mutex.withLock {
            if (recordingToken != 0L) pendingGapReason = reason
        }
    }

    /** Flushes batched samples without ending the session. */
    internal suspend fun flush() {
        mutex.withLock {
            // Repeated failed polls must not rewrite unchanged metadata to disk.
            if (recordingToken == 0L || pending.isEmpty()) return
            try {
                flushLocked()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                val message = appContext.getString(R.string.error_history_unavailable, describe(failure))
                persistenceError = message
                publishLocked()
                throw MonitoringPersistenceException(message, failure)
            }
        }
    }

    /** A failed poll notice; a persistence failure stays visible regardless. */
    internal suspend fun reportError(message: String?) {
        mutex.withLock {
            if (transientError != message) {
                transientError = message
                publishLocked()
            }
        }
    }

    /** Clears the recoverable poll/notice error only, never a durability failure. */
    internal suspend fun clearError() {
        mutex.withLock {
            if (transientError != null) {
                transientError = null
                publishLocked()
            }
        }
    }

    internal fun setWakeLockHeld(held: Boolean) {
        if (mutableWakeLockHeld.value != held) mutableWakeLockHeld.value = held
    }

    /** Must be called with [mutex] held. */
    private suspend fun flushLocked() {
        val id = sessionId
        if (id == 0L) return
        val batch = pending.toList()
        val currentSummary = summary
        // One SQLite transaction for the batch *and* its summary, so a failed
        // summary write rolls the samples back with it and a retry cannot
        // duplicate rows. NonCancellable keeps a cancellation from leaving a
        // half-written batch behind.
        withContext(Dispatchers.IO + NonCancellable) {
            store.saveBatch(id, batch, currentSummary)
        }
        // Only discard the tail after the batch is durable; a failed write keeps
        // `pending` intact so the next attempt retries the same rows.
        pending.clear()
        committedSamples += batch.size
        lastFlushElapsed = SystemClock.elapsedRealtime()
        if (persistenceError != null) {
            // A successful durable write is evidence that storage recovered.
            persistenceError = null
            publishLocked()
        }
        if (lastFlushElapsed - lastPruneElapsed >= PRUNE_INTERVAL_MILLIS) {
            pruneLocked()
        }
    }

    /** Bounded retention: samples only, never the aggregate summary. */
    private suspend fun pruneLocked() {
        val id = sessionId
        if (id == 0L) return
        lastPruneElapsed = SystemClock.elapsedRealtime()
        val outcome = try {
            withContext(Dispatchers.IO) {
                store.prune(
                    staleBeforeMillis = System.currentTimeMillis() - RETENTION_MILLIS,
                    maxRows = MAX_STORED_SAMPLES,
                    sessionId = id,
                )
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // Retention failing loses nothing that is already committed; record it
            // without aborting the recording.
            persistenceError = appContext.getString(R.string.error_history_unavailable, describe(failure))
            publishLocked()
            return
        }
        if (outcome.remainingRowsForSession < committedSamples) {
            truncated = true
            try {
                withContext(Dispatchers.IO) { store.markHistoryTruncated(id) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // The flag is informational metadata; committing it again is not
                // worth aborting a recording that is still writing fine.
                persistenceError = appContext.getString(R.string.error_history_unavailable, describe(failure))
            }
            publishLocked()
        }
    }

    /**
     * Feeds one recorded sample into the observed-capacity estimator.
     *
     * [CapacityEstimator] rejects gaps, missing values and reversals itself and
     * returns a measurement only once a charge or discharge window spans at least
     * 20 percentage points. Everything before that is *progress*: the
     * published state goes through [capacityEstimateLocked] on every recorded sample,
     * so the UI can explain the required span in either direction, while
     * the durable aggregate only changes when a window completes.
     *
     * Must be called with [mutex] held.
     */
    private suspend fun accumulateCapacityLocked(sample: BatterySample) {
        val measurement = capacityEstimator.add(sample) ?: return
        // `plus` returns the same instance for a rejected observation, so an
        // unchanged aggregate is never persisted or published as new data.
        val next = capacityAggregate.plus(measurement)
        if (next === capacityAggregate) return
        capacityAggregate = next
        capacityUpdatedAt = System.currentTimeMillis()
        capacityDirty = true
        val now = SystemClock.elapsedRealtime()
        if (CapacityPersistence.isDue(capacityDirty, now, lastCapacityPersistElapsed)) {
            persistCapacityLocked()
        }
    }

    /**
     * Writes the capacity aggregate on its own, outside the sample transaction.
     *
     * Throttled to once per [CAPACITY_PERSIST_INTERVAL_MILLIS] (plus the stop-time
     * flush) because a completed observation is rare and cannot arrive faster than a
     * charge or discharge window: the throttle only coalesces a burst. A failure keeps the
     * in-memory aggregate, leaves it dirty and raises a recoverable notice: the
     * observation is real, only its durability failed, and recording must continue.
     * [retryPendingCapacityLocked] retries it on later polls, so a write that failed
     * just after a window completed is not left pending until the session stops.
     * Nothing is written when no value was observed.
     *
     * Must be called with [mutex] held.
     */
    private suspend fun persistCapacityLocked() {
        val aggregate = capacityAggregate
        val updatedAt = capacityUpdatedAt ?: System.currentTimeMillis()
        lastCapacityPersistElapsed = SystemClock.elapsedRealtime()
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                store.writeCapacity(aggregate, updatedAt)
            }
            capacityDirty = false
            if (capacityError != null) {
                // Clear exactly the notice this write raised: it lives in its own
                // slot, so a poll/start message published since is untouched.
                capacityError = null
                publishLocked()
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // Recoverable: still dirty, so a later poll (or the stop flush) retries,
            // and the in-memory estimate (and its published value) is unchanged.
            capacityError = appContext.getString(R.string.error_capacity_not_saved, describe(failure))
        }
    }

    /**
     * Retries a dirty capacity aggregate on an ordinary poll, when no new
     * observation arrived to trigger [persistCapacityLocked] itself.
     *
     * Throttled by the same [CapacityPersistence.isDue] rule as a fresh observation,
     * so a persistently failing write is not retried on every tick.
     *
     * Must be called with [mutex] held.
     */
    private suspend fun retryPendingCapacityLocked() {
        val now = SystemClock.elapsedRealtime()
        if (!CapacityPersistence.isDue(capacityDirty, now, lastCapacityPersistElapsed)) return
        persistCapacityLocked()
    }

    /** Restores the persisted aggregate; a read failure leaves "not observed". */
    private suspend fun restoreCapacityLocked() {
        val stored = try {
            withContext(Dispatchers.IO) { store.readCapacity() }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            capacityAggregate = CapacityAggregate()
            capacityUpdatedAt = null
            return
        }
        capacityAggregate = stored?.aggregate ?: CapacityAggregate()
        capacityUpdatedAt = stored?.updatedAtMillis
        // Freshly restored from disk, so nothing is pending.
        capacityDirty = false
    }

    /**
     * Restores the established calibration. A corrupt or partial record loads as
     * "not established" rather than as half-evidence (see [SettingsCodec]).
     *
     * Must be called with [mutex] held.
     */
    private fun restoreCalibrationLocked() {
        val stored = try {
            settingsStore.loadCalibration()
        } catch (_: Throwable) {
            CurrentCalibration()
        }
        // The revision is not persisted: it exists to invalidate evidence gathered
        // in this process, and every sampler seeds itself from the published state.
        calibrationState.restore(stored)
    }

    /**
     * The published/exported observed capacity; absent until a window completes.
     *
     * [CapacityEstimate.observedPercent] is the progress of the window still open, so
     * it never has to be inferred from the sample list by a consumer.
     */
    private fun capacityEstimateLocked(): CapacityEstimate = capacityAggregate.toEstimate(
        lastUpdatedMillis = capacityUpdatedAt,
        observedPercent = capacityEstimator.progressPercent(),
    )

    private fun publishLocked() {
        val running = recordingToken != 0L
        // Keep the legacy process-local flag used by the host in sync exactly once.
        MonitoringSession.setRunning(running)
        mutableState.value = MonitorUiState(
            running = running,
            samples = samples.toList(),
            summary = summary,
            error = lostSamplesError ?: persistenceError ?: transientError ?: capacityError,
            historyTruncated = truncated,
            loaded = loadCompleted,
            capacity = capacityEstimateLocked(),
            calibration = calibration,
            calibrationRevision = calibrationRevision,
        )
    }

    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName

    private data class ExportSnapshot(
        val sessionId: Long,
        val summary: SessionSummary,
        val historyTruncated: Boolean,
        val capacity: CapacityEstimate,
        val summaryMetric: CsvExporter.SummaryMetric,
        val designCapacityMilliampHours: Double?,
        val calibration: CurrentCalibration,
        val currentUnit: CurrentUnitOverride,
        val currentPolarity: CurrentPolarityOverride,
    )

    companion object {
        /** Bounded live history covering the 2h chart even at the 1s option. */
        const val MAX_UI_SAMPLES = 8192

        /**
         * Global durable bound across all sessions, unchanged by faster polling.
         * Retain at most 7 days OR this many rows, whichever limit is reached
         * first. That is about 3 days at the 2s default, 1.5 days at 1s. A time
         * ceiling is not a promise to retain a full week at every sampling rate.
         */
        const val MAX_STORED_SAMPLES = 130_000

        /** Rows read for one export; the UI says retained history only. */
        const val MAX_EXPORT_SAMPLES = MAX_STORED_SAMPLES

        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Batch at most this many samples ... */
        const val FLUSH_MAX_SAMPLES = 4

        /** ... or this long, so an abrupt kill loses at most that tail. */
        const val FLUSH_MAX_MILLIS = 60_000L

        private const val MAX_UNSAVED_SAMPLES = 240
        private const val PRUNE_INTERVAL_MILLIS = 5 * 60_000L

        /**
         * Minimum spacing between durable `capacity_state` writes; see
         * [CapacityPersistence].
         */
        const val CAPACITY_PERSIST_INTERVAL_MILLIS = CapacityPersistence.INTERVAL_MILLIS

        @Volatile
        private var instance: MonitoringRepository? = null

        fun get(context: Context): MonitoringRepository =
            instance ?: synchronized(this) {
                instance ?: MonitoringRepository(context).also { instance = it }
            }
    }
}

/**
 * A durable write failed: the batch and its summary were rolled back, so the
 * session can no longer honestly claim to be recording. Callers stop recording
 * and surface the repository's published error instead of retrying forever.
 */
internal class MonitoringPersistenceException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
