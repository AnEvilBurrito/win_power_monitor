package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.NotificationUpdateRate
import io.github.anevilburrito.wpm.model.LiveReading
import io.github.anevilburrito.wpm.model.liveReading
import io.github.anevilburrito.wpm.model.liveAmps

/**
 * Pure throttle/transition rules for the ongoing notification.
 *
 * Routine updates follow the user's Instant/15s/60s selection, checked only when
 * the sampler already has an update. No timer, extra sample or wake lock is added.
 * Two meaning changes must never wait for that throttle:
 *
 * 1. a reading appearing or disappearing (sample vs no sample), and
 * 2. the live reading appearing or disappearing.
 *
 * The second rule is what makes a saved-calibration device honest: the very first
 * poll already has a calibrated instantaneous current, so the notification switches
 * away from "Calibrating" immediately instead of showing it for up to a minute
 * while a 60 s counter average is still being acquired. The live metric, not the
 * auxiliary counter average, decides this transition.
 *
 * Kept Android-free so the rule is unit tested rather than inferred from the
 * service's private state.
 */
internal object NotificationUpdatePolicy {
    /** Timed choices post on the first sample at/after the interval, not an extra timer. */
    fun shouldUpdate(
        rate: NotificationUpdateRate,
        nowElapsedMillis: Long,
        lastUpdateElapsedMillis: Long,
        force: Boolean,
        previousShowsSample: Boolean,
        previousShowsLive: Boolean,
        sample: BatterySample?,
        previousLiveReading: LiveReading? = null,
    ): Boolean {
        if (bypassesThrottle(force, previousShowsSample, previousShowsLive, sample, previousLiveReading)) return true
        val elapsed = nowElapsedMillis - lastUpdateElapsedMillis
        return elapsed < 0L || elapsed >= rate.minimumIntervalMillis
    }

    /**
     * True when [sample] changes what the notification says in a way that must
     * bypass the selected routine throttle.
     *
     * @param force an explicit caller-requested update (start/stop/settings).
     * @param previousShowsSample whether the last posted notification had a sample.
     * @param previousShowsLive whether the last posted notification had a live
     *   calibrated current reading.
     */
    fun bypassesThrottle(
        force: Boolean,
        previousShowsSample: Boolean,
        previousShowsLive: Boolean,
        sample: BatterySample?,
        previousLiveReading: LiveReading? = null,
    ): Boolean {
        if (force) return true
        // Confidence/ambiguity transitions matter even when both states have a sample.
        if (previousLiveReading != null && previousLiveReading != sample.liveReading()) return true
        if ((sample != null) != previousShowsSample) return true
        return (sample?.liveAmps != null) != previousShowsLive
    }
}
