package io.github.anevilburrito.wpm.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Observed full capacity of the installed battery, from charging or discharging windows.
 *
 * This app uses no model database or private design-capacity interface, and no
 * independent laboratory coulomb counter. Its observations use the reported gauge:
 * how much the charge counter moved while the reported percentage changed. Over a long
 * enough swing that ratio is a usable capacity, but it is a gauge observation, not
 * an independent measurement, and it is deliberately kept separate from the
 * single-point [CapacityMath.gaugeFullCapacityMilliampHours] helper the UI shows.
 *
 * A value exists only after a window has been completed; until then
 * [estimatedMilliampHours] is `null` and [observedPercent] reports how much of the
 * required swing the open window has gathered.
 */
data class CapacityEstimate(
    val estimatedMilliampHours: Double? = null,
    /** Completed charge-counter windows behind the value, not the number of polls. */
    val estimateCount: Int = 0,
    val lastUpdatedMillis: Long? = null,
    /**
     * Percentage points gathered so far in the window currently being observed
     * (`0..`[CapacityEstimator.MINIMUM_PERCENT_SPAN]). Progress only: it is never
     * published as a capacity and never persisted.
     */
    val observedPercent: Int = 0,
) {
    val available: Boolean get() = estimatedMilliampHours != null
}

/** One completed capacity observation and the weight it carries in the average. */
data class CapacityMeasurement(val milliampHours: Double, val weight: Double)

/**
 * Weighted accumulation of completed capacity observations, persisted across
 * sessions.
 *
 * [CapacityMeasurement.weight] is the percentage swing that produced the
 * observation, so every accepted window carries a comparable weight (at least
 * [CapacityEstimator.MINIMUM_PERCENT_SPAN]) and a barely-qualifying window cannot
 * outvote a long one. Evidence is capped by halving both totals when
 * [MAX_WEIGHT] is exceeded, so a months-old observation cannot outvote the current
 * gauge forever and the stored numbers stay bounded.
 */
data class CapacityAggregate(
    val sampleCount: Int = 0,
    val weightTotal: Double = 0.0,
    val weightedMilliampHours: Double = 0.0,
) {
    val estimatedMilliampHours: Double?
        get() = if (weightTotal > 0.0) weightedMilliampHours / weightTotal else null

    fun plus(measurement: CapacityMeasurement): CapacityAggregate {
        if (!measurement.milliampHours.isFinite() || measurement.weight <= 0.0) return this
        var weight = weightTotal
        var weighted = weightedMilliampHours
        if (weight > MAX_WEIGHT) {
            weight /= 2.0
            weighted /= 2.0
        }
        return CapacityAggregate(
            sampleCount = sampleCount + 1,
            weightTotal = weight + measurement.weight,
            weightedMilliampHours = weighted + measurement.milliampHours * measurement.weight,
        )
    }

    fun toEstimate(lastUpdatedMillis: Long?, observedPercent: Int = 0): CapacityEstimate = CapacityEstimate(
        estimatedMilliampHours = estimatedMilliampHours,
        estimateCount = sampleCount,
        lastUpdatedMillis = lastUpdatedMillis,
        observedPercent = observedPercent,
    )

    companion object {
        /** Bounds the stored totals; halving keeps the ratio while ageing evidence out. */
        const val MAX_WEIGHT = 300_000.0
    }
}

/**
 * Stateful observed-capacity estimator: one monotonic charge or discharge window.
 *
 * Policy (an accuracy policy, not a mathematical prerequisite):
 *
 * 1. Anchor a valid percentage and nonnegative charge counter. Infer direction from
 *    their movement; an explicit unplugged state allows discharge only, and a
 *    calibrated current's flow provides a direction hint. Unknown properties and
 *    uncalibrated instantaneous current stay neutral. A plugged phone may discharge.
 * 2. Each property may stay flat between updates, but nonzero counter/percentage
 *    steps must agree with the window's direction. Re-anchor on reversals or
 *    conflicting trusted direction instead of mixing charge and discharge legs.
 * 3. Missing values and `gapBefore` clear the window. Non-positive elapsed steps,
 *    gaps over [MAX_WINDOW_GAP_MILLIS] and implausible counter rates re-anchor it.
 *    Charge that moved unobserved is never attributed to an observed window.
 * 4. At [MINIMUM_PERCENT_SPAN] percentage points, report
 *    `abs(deltaCharge) / (abs(deltaPercent) / 100)`. Reject values outside
 *    [MIN_PLAUSIBLE_MILLIAMP_HOURS]..[MAX_PLAUSIBLE_MILLIAMP_HOURS], never clamp.
 *    Re-anchor at the endpoint so subsequent windows do not reuse charge. Weight
 *    and progress use the absolute percentage-point span in either direction.
 * 5. [reset] closes the open window; the durable aggregate survives. Earlier
 *    charging-only observations measure the same quantity and remain valid.
 *
 * Pure Kotlin, no Android imports. Callers may feed it from the sampler thread and
 * reset it from the lifecycle thread, so the small state is guarded by the instance
 * monitor.
 */
class CapacityEstimator(
    private val minPercentSpan: Int = MINIMUM_PERCENT_SPAN,
    private val maxGapMillis: Long = MAX_WINDOW_GAP_MILLIS,
    private val maxPlausibleAmps: Double = MAX_PLAUSIBLE_CHARGE_AMPS,
) {
    private data class Point(
        val elapsedMillis: Long,
        val chargeCounterMicroampHours: Long,
        val percent: Int,
        val directionHint: Int,
    )

    private var anchor: Point? = null
    private var latest: Point? = null
    private var direction = 0

    /** Closes the open window; no completed observation is lost or altered. */
    @Synchronized
    fun reset() {
        anchor = null
        latest = null
        direction = 0
    }

    /**
     * Percentage points gathered toward [MINIMUM_PERCENT_SPAN] in the open window,
     * in the open charging or discharging window.
     */
    @Synchronized
    fun progressPercent(): Int {
        val start = anchor ?: return 0
        val current = latest ?: return 0
        return abs(current.percent - start.percent).coerceIn(0, minPercentSpan)
    }

    /**
     * Feeds one recorded sample.
     *
     * @return the observation when this sample completed a window, else `null`.
     * Never a guess, never a clamp, never an overlapping window.
     */
    @Synchronized
    fun add(sample: BatterySample): CapacityMeasurement? {
        val point = pointOf(sample)
        if (point == null || sample.gapBefore) {
            // Missing values or a known discontinuity: nothing about this stretch is
            // comparable with what came before it.
            reset()
            return null
        }
        val start = anchor
        val previous = latest
        if (start == null || previous == null) {
            restart(point)
            return null
        }
        val stepMillis = point.elapsedMillis - previous.elapsedMillis
        val chargeStep = point.chargeCounterMicroampHours - previous.chargeCounterMicroampHours
        val percentStep = point.percent - previous.percent
        val chargeDirection = chargeStep.compareTo(0L)
        val percentDirection = percentStep.compareTo(0)
        val expectedDirection = direction.takeIf { it != 0 }
            ?: point.directionHint.takeIf { it != 0 }
            ?: chargeDirection.takeIf { it != 0 }
            ?: percentDirection
        if (stepMillis <= 0L ||
            stepMillis > maxGapMillis ||
            (chargeDirection != 0 && chargeDirection != expectedDirection) ||
            (percentDirection != 0 && percentDirection != expectedDirection) ||
            (point.directionHint != 0 && point.directionHint != expectedDirection) ||
            // Faster polling can expose a batch of cached gauge movement in a
            // single second; don't interpret its release as a one-second surge.
            impliedAmps(chargeStep, maxOf(stepMillis, STEP_REFERENCE_MILLIS)) > maxPlausibleAmps
        ) {
            // Reverse counter/percentage, an unobserved gap, sleep, a gauge reset or an
            // impossible rate: restart from here instead of bridging the discontinuity.
            restart(point)
            return null
        }
        latest = point
        direction = expectedDirection
        val signedPercentSpan = point.percent - start.percent
        val spanPercent = abs(signedPercentSpan)
        if (spanPercent < minPercentSpan) return null
        val signedChargeChange = point.chargeCounterMicroampHours - start.chargeCounterMicroampHours
        val chargeMicroampHours = abs(signedChargeChange)
        if (chargeMicroampHours <= 0L ||
            signedChargeChange.compareTo(0L) != signedPercentSpan.compareTo(0) ||
            impliedAmps(chargeMicroampHours, point.elapsedMillis - start.elapsedMillis) > maxPlausibleAmps
        ) {
            // The percentage moved while the counter did not: the gauge is not
            // describing this stretch of charge, so nothing here can be divided by it.
            restart(point)
            return null
        }
        val milliampHours = chargeMicroampHours / 1000.0 * 100.0 / spanPercent
        if (!milliampHours.isFinite() ||
            milliampHours < MIN_PLAUSIBLE_MILLIAMP_HOURS ||
            milliampHours > MAX_PLAUSIBLE_MILLIAMP_HOURS
        ) {
            // Reject rather than clamp: a number this implausible is a gauge failure,
            // not a battery.
            restart(point)
            return null
        }
        // The endpoint starts the next window, so no charge is counted twice.
        restart(point)
        return CapacityMeasurement(milliampHours, weight = spanPercent.toDouble())
    }

    private fun pointOf(sample: BatterySample): Point? {
        val percent = sample.percentage?.takeIf { it in 0..100 } ?: return null
        val counter = sample.chargeCounterMicroampHours?.takeIf { it >= 0L } ?: return null
        return Point(
            elapsedMillis = sample.elapsedRealtimeMillis,
            chargeCounterMicroampHours = counter,
            percent = percent,
            directionHint = directionHint(sample),
        )
    }

    /** Magnitude of the charge/discharge rate implied by one step, in amperes. */
    private fun impliedAmps(deltaMicroampHours: Long, deltaMillis: Long): Double {
        if (deltaMillis <= 0L) return Double.POSITIVE_INFINITY
        val amps = deltaMicroampHours * 3600.0 / (deltaMillis / 1000.0) / MICROAMPS_PER_AMP
        return abs(amps)
    }

    /**
     * Trusted hints reject gauge movement contrary to the battery's known state.
     * Plugged does not imply charging. Uncalibrated CURRENT_NOW may be inverted,
     * so it must not veto either direction of counter/percentage observation.
     */
    private fun directionHint(sample: BatterySample): Int = when {
        sample.plugged == false -> -1
        sample.currentCalibrated && sample.flow == FlowState.CHARGING -> 1
        sample.currentCalibrated && sample.flow == FlowState.DISCHARGING -> -1
        else -> 0
    }

    private fun restart(point: Point) {
        anchor = point
        latest = point
        direction = point.directionHint
    }

    companion object {
        /** Absolute percentage-point swing required in either direction (inclusive). */
        const val MINIMUM_PERCENT_SPAN = 20

        /** Longer than this and charge moved unobserved: re-anchor, never bridge. */
        const val MAX_WINDOW_GAP_MILLIS = 5 * 60_000L

        /**
         * Conservative rejection policy, not a universal physical limit. Apply
         * it to both short counter batches and completed charge/discharge windows.
         */
        const val MAX_PLAUSIBLE_CHARGE_AMPS = 10.0

        /** Gauge updates may be cached across several fast startup polls. */
        const val STEP_REFERENCE_MILLIS = 5_000L

        /** Rejects nonsense from a broken gauge rather than clamping it. */
        const val MIN_PLAUSIBLE_MILLIAMP_HOURS = 100.0
        const val MAX_PLAUSIBLE_MILLIAMP_HOURS = 30_000.0

        const val MICROAMPS_PER_AMP = 1_000_000.0
    }
}

/** Time estimates derived from the measured charge counter and current. */
object CapacityMath {
    /**
     * Single-point *gauge* full capacity: remaining charge counter divided by the
     * reported state of charge.
     *
     * Useful for a quick cross-check, and labelled as a gauge estimate wherever it
     * is shown. It is deliberately **not** persisted into the observed capacity:
     * one point inherits the full gauge quantisation error of both the counter and
     * the percentage. Returns null for missing/zero data and for nonsense outside
     * the plausible window (never a clamp).
     */
    fun gaugeFullCapacityMilliampHours(sample: BatterySample?): Double? {
        val reading = sample ?: return null
        val percent = reading.percentage?.takeIf { it in GAUGE_MINIMUM_PERCENT..100 } ?: return null
        val remainingMicroampHours = reading.chargeCounterMicroampHours?.takeIf { it > 0L } ?: return null
        val milliampHours = (remainingMicroampHours / 1000.0) / (percent / 100.0)
        if (!milliampHours.isFinite()) return null
        if (milliampHours < CapacityEstimator.MIN_PLAUSIBLE_MILLIAMP_HOURS) return null
        if (milliampHours > CapacityEstimator.MAX_PLAUSIBLE_MILLIAMP_HOURS) return null
        return milliampHours
    }

    /**
     * Below this state of charge one reported point is too coarse to divide by, so
     * the gauge helper stays unavailable.
     */
    const val GAUGE_MINIMUM_PERCENT = 5

    /**
     * Time until the battery is empty (discharge) or full (charge), or `null`.
     *
     * Requires a remaining charge counter and a rate big enough to matter; a
     * charging estimate additionally needs a usable-capacity value, because the
     * counter is the charge that remains, not the charge the battery holds. Pass
     * the *average* rate (derived from the counter), never an instant reading: a
     * charging time computed from one burst is not a forecast.
     */
    fun runtimeSeconds(
        remainingMicroampHours: Long?,
        amps: Double?,
        capacityMilliampHours: Double? = null,
    ): Double? {
        val remaining = remainingMicroampHours?.takeIf { it > 0L } ?: return null
        if (amps == null || !amps.isFinite()) return null
        val magnitudeAmps = abs(amps)
        if (magnitudeAmps < MIN_RUNTIME_AMPS) return null
        val remainingMilliampHours = remaining / 1000.0
        val targetMilliampHours = if (amps < 0.0) {
            remainingMilliampHours
        } else {
            val capacity = capacityMilliampHours?.takeIf { it > remainingMilliampHours } ?: return null
            capacity - remainingMilliampHours
        }
        val seconds = targetMilliampHours / (magnitudeAmps * 1000.0) * 3600.0
        return seconds.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Below this the estimate is dominated by counter resolution (0.5 mA). */
    const val MIN_RUNTIME_AMPS = 0.0005

    /** Convenience for the UI: percent of a known capacity still available. */
    fun chargePercent(remainingMicroampHours: Long?, capacityMilliampHours: Double?): Int? {
        val remaining = remainingMicroampHours?.takeIf { it > 0L } ?: return null
        val capacity = capacityMilliampHours?.takeIf { it > 0.0 } ?: return null
        val ratio = (remaining / 1000.0) / capacity
        if (!ratio.isFinite() || ratio < 0.0) return null
        return (ratio * 100.0).roundToInt().coerceIn(0, 100)
    }
}
