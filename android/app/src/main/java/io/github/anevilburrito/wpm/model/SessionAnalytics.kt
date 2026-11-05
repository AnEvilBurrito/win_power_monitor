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
            val oldWatts = before.let(samplePowerWatts)?.takeIf { it.isFinite() }
            val discontinuity = sample.gapBefore || seconds <= 0 || seconds > gapThresholdMillis(intervalSeconds) / 1000.0
            if (discontinuity || watts == null || oldWatts == null) {
                gaps++
            } else {
                observedSeconds += seconds
                if (oldWatts >= 0 && watts >= 0) {
                    inWattSeconds += (oldWatts + watts) * 0.5 * seconds
                } else if (oldWatts <= 0 && watts <= 0) {
                    outWattSeconds += -(oldWatts + watts) * 0.5 * seconds
                } else {
                    // Split the linearly interpolated zero crossing, rather than
                    // abs(net integral), which would erase simultaneous out/in.
                    val firstSeconds = seconds * abs(oldWatts) / (abs(oldWatts) + abs(watts))
                    val firstArea = abs(oldWatts) * firstSeconds * 0.5
                    val secondArea = abs(watts) * (seconds - firstSeconds) * 0.5
                    if (oldWatts < 0) {
                        outWattSeconds += firstArea
                        inWattSeconds += secondArea
                    } else {
                        inWattSeconds += firstArea
                        outWattSeconds += secondArea
                    }
                }
            }
        }
        previous = sample
        return snapshot()
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
