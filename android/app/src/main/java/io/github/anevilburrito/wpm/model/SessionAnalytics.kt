package io.github.anevilburrito.wpm.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Incremental, monotonic-time integration; no Android or UI dependencies.
 *
 * [samplePowerWatts] selects which power figure is integrated. It defaults to the
 * stored [BatterySample.watts] (the raw decoder value) so existing callers and
 * tests keep their meaning, while a *live* recording passes
 * [io.github.anevilburrito.wpm.model.liveWatts] so its session summary integrates
 * exactly the calibrated instantaneous power the readout and notification show.
 * Uncalibrated or suppressed readings select `null`, so they count as unobserved
 * time instead of contributing a guessed value.
 *
 * [io.github.anevilburrito.wpm.model.averageWatts] (counter-derived average current
 * times the latest voltage) remains available as an explicitly auxiliary metric
 * for diagnostics and legacy exports; it is not a live summary's default.
 * History that is already stored keeps its own summary fields and is never
 * recomputed with a different metric.
 *
 * Each observed interval is also booked to the display state of the sample that
 * closes it ([BatterySample.displayOn]), so a session reports the same energy split
 * by screen state. A screen transition between two polls can therefore misattribute
 * at most one interval — seconds against minutes of screen-off time — and samples
 * predating schema v5 book their seconds to the unknown bucket without claiming any
 * energy for a state that was never recorded.
 */
class SessionAccumulator(
    private val id: Long,
    private val startedAtMillis: Long,
    private val startedElapsedRealtimeMillis: Long,
    private val samplePowerWatts: (BatterySample) -> Double? = { it.watts },
) {
    private var previous: BatterySample? = null
    private var elapsedSeconds = 0.0
    private var observedSeconds = 0.0
    private var outWattSeconds = 0.0
    private var inWattSeconds = 0.0
    private var minimumSigned: Double? = null
    private var maximumSigned: Double? = null
    private var minimumMagnitude: Double? = null
    private var maximumMagnitude: Double? = null
    private var samples = 0
    private var gaps = 0
    private var displayOnSeconds = 0.0
    private var displayOffSeconds = 0.0
    private var displayUnknownSeconds = 0.0
    private var displayOnOutWattSeconds = 0.0
    private var displayOnInWattSeconds = 0.0
    private var displayOffOutWattSeconds = 0.0
    private var displayOffInWattSeconds = 0.0
    private var displayOnWallSeconds = 0.0
    private var displayOffWallSeconds = 0.0
    private var displayUnknownWallSeconds = 0.0
    private var displayOnDischargeSeconds = 0.0
    private var displayOffDischargeSeconds = 0.0

    fun add(sample: BatterySample, intervalSeconds: Int): SessionSummary {
        samples++
        elapsedSeconds = max(elapsedSeconds, max(0.0, (sample.elapsedRealtimeMillis - startedElapsedRealtimeMillis) / 1000.0))
        val watts = samplePowerWatts(sample)?.takeIf { it.isFinite() }
        if (watts != null) {
            minimumSigned = minimumSigned?.let { minOf(it, watts) } ?: watts
            maximumSigned = maximumSigned?.let { maxOf(it, watts) } ?: watts
            minimumMagnitude = minimumMagnitude?.let { minOf(it, abs(watts)) } ?: abs(watts)
            maximumMagnitude = maximumMagnitude?.let { maxOf(it, abs(watts)) } ?: abs(watts)
        }
        val before = previous
        if (before != null) {
            val seconds = (sample.elapsedRealtimeMillis - before.elapsedRealtimeMillis) / 1000.0
            // Wall time is booked before the gap test, because a swallowed interval is
            // still time the phone spent in that display state: it belongs in the
            // coverage denominator of that bucket, not in the other bucket's.
            if (seconds > 0.0) {
                when (sample.displayOn) {
                    true -> displayOnWallSeconds += seconds
                    false -> displayOffWallSeconds += seconds
                    null -> displayUnknownWallSeconds += seconds
                }
            }
            val oldWatts = before.let(samplePowerWatts)?.takeIf { it.isFinite() }
            val discontinuity = sample.gapBefore || seconds <= 0 || seconds > gapThresholdMillis(intervalSeconds) / 1000.0
            if (discontinuity || watts == null || oldWatts == null) {
                gaps++
            } else {
                observedSeconds += seconds
                val areas = intervalAreas(oldWatts, watts, seconds)
                outWattSeconds += areas.outWattSeconds
                inWattSeconds += areas.inWattSeconds
                // Attribute the same interval, and therefore exactly the same energy,
                // to the display state of the sample that closes it. A transition
                // between two polls is misattributed by at most one interval; the
                // alternative (assuming the start of the interval) is no better, and
                // splitting on broadcast timestamps is a later refinement.
                when (sample.displayOn) {
                    true -> {
                        displayOnSeconds += seconds
                        displayOnOutWattSeconds += areas.outWattSeconds
                        displayOnInWattSeconds += areas.inWattSeconds
                        displayOnDischargeSeconds += areas.outSeconds
                    }
                    false -> {
                        displayOffSeconds += seconds
                        displayOffOutWattSeconds += areas.outWattSeconds
                        displayOffInWattSeconds += areas.inWattSeconds
                        displayOffDischargeSeconds += areas.outSeconds
                    }
                    // History written before schema v5. Its seconds are still counted
                    // so the buckets reconcile, but no energy is claimed for a state
                    // that was never recorded.
                    null -> displayUnknownSeconds += seconds
                }
            }
        }
        previous = sample
        return snapshot()
    }

    /**
     * Out/in areas of one interval plus the time spent in each direction, with the
     * zero crossing split as before.
     *
     * [outSeconds] is what a discharge-only average divides by: the time genuinely
     * spent discharging. Charging never enters it, so a charging interval cannot
     * dilute a drain figure.
     */
    private class IntervalAreas(
        val outWattSeconds: Double,
        val inWattSeconds: Double,
        val outSeconds: Double,
    )

    private fun intervalAreas(oldWatts: Double, watts: Double, seconds: Double): IntervalAreas =
        if (oldWatts >= 0 && watts >= 0) {
            IntervalAreas(
                outWattSeconds = 0.0,
                inWattSeconds = (oldWatts + watts) * 0.5 * seconds,
                outSeconds = 0.0,
            )
        } else if (oldWatts <= 0 && watts <= 0) {
            IntervalAreas(
                outWattSeconds = -(oldWatts + watts) * 0.5 * seconds,
                inWattSeconds = 0.0,
                outSeconds = seconds,
            )
        } else {
            // Split the linearly interpolated zero crossing, rather than
            // abs(net integral), which would erase simultaneous out/in.
            val firstSeconds = seconds * abs(oldWatts) / (abs(oldWatts) + abs(watts))
            val firstArea = abs(oldWatts) * firstSeconds * 0.5
            val secondArea = abs(watts) * (seconds - firstSeconds) * 0.5
            if (oldWatts < 0) {
                IntervalAreas(
                    outWattSeconds = firstArea,
                    inWattSeconds = secondArea,
                    outSeconds = firstSeconds,
                )
            } else {
                IntervalAreas(
                    outWattSeconds = secondArea,
                    inWattSeconds = firstArea,
                    outSeconds = seconds - firstSeconds,
                )
            }
        }

    fun snapshot(endedAtMillis: Long? = null): SessionSummary = SessionSummary(
        id = id,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        elapsedSeconds = elapsedSeconds,
        observedSeconds = observedSeconds,
        outWh = outWattSeconds / 3600.0,
        inWh = inWattSeconds / 3600.0,
        signedAverageWatts = if (observedSeconds > 0) (inWattSeconds - outWattSeconds) / observedSeconds else null,
        magnitudeAverageWatts = if (observedSeconds > 0) (inWattSeconds + outWattSeconds) / observedSeconds else null,
        minimumSignedWatts = minimumSigned,
        maximumSignedWatts = maximumSigned,
        minimumMagnitudeWatts = minimumMagnitude,
        maximumMagnitudeWatts = maximumMagnitude,
        sampleCount = samples,
        gapCount = gaps,
        displayOnSeconds = displayOnSeconds,
        displayOffSeconds = displayOffSeconds,
        displayUnknownSeconds = displayUnknownSeconds,
        displayOnWallSeconds = displayOnWallSeconds,
        displayOffWallSeconds = displayOffWallSeconds,
        displayUnknownWallSeconds = displayUnknownWallSeconds,
        displayOnDischargeSeconds = displayOnDischargeSeconds,
        displayOffDischargeSeconds = displayOffDischargeSeconds,
        displayOnOutWh = displayOnOutWattSeconds / 3600.0,
        displayOnInWh = displayOnInWattSeconds / 3600.0,
        displayOffOutWh = displayOffOutWattSeconds / 3600.0,
        displayOffInWh = displayOffInWattSeconds / 3600.0,
    )
}

fun gapThresholdMillis(intervalSeconds: Int): Long = max(30_000L, intervalSeconds.coerceAtLeast(1) * 2500L)

fun isSampleStale(sample: BatterySample?, nowElapsedRealtimeMillis: Long, intervalSeconds: Int): Boolean {
    if (sample == null) return true
    val age = nowElapsedRealtimeMillis - sample.elapsedRealtimeMillis
    return age < 0 || age > max(45_000L, intervalSeconds.coerceAtLeast(1) * 3000L)
}

fun summarizeWindow(
    samples: List<BatterySample>,
    windowSeconds: Int,
    intervalSeconds: Int,
    samplePowerWatts: (BatterySample) -> Double? = { it.watts },
): SessionSummary {
    val latest = samples.lastOrNull() ?: return SessionSummary()
    val cutoff = latest.elapsedRealtimeMillis - windowSeconds.coerceAtLeast(1) * 1000L
    val visible = samples.filter { it.elapsedRealtimeMillis >= cutoff && it.elapsedRealtimeMillis <= latest.elapsedRealtimeMillis }
    val first = visible.firstOrNull() ?: return SessionSummary()
    val accumulator = SessionAccumulator(
        id = 0,
        startedAtMillis = first.timestampMillis,
        startedElapsedRealtimeMillis = first.elapsedRealtimeMillis,
        samplePowerWatts = samplePowerWatts,
    )
    visible.forEach { accumulator.add(it, intervalSeconds) }
    return accumulator.snapshot()
}
