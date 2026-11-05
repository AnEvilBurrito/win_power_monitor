package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import kotlin.math.abs

/**
 * Establishes how `BATTERY_PROPERTY_CURRENT_NOW` must be read, by cross-checking
 * it against the **charge counter**, the only second reading a phone exposes for
 * the same quantity.
 *
 * The counter is *not* an independent reference. On some devices it is produced
 * by the same gauge firmware as `CURRENT_NOW`, its absolute scale is unknown, and
 * it can be reset or rolled over by the gauge or by a charge cycle. So it is used
 * only as a **consistency** check: if the raw property tracks the counter's change
 * in time (up to a factor-of-[RATIO_TOLERANCE] band and a fixed sign), the property
 * has one of the two documented scales; the counter never tells us the truth of
 * the current, only that the property moves with it.
 *
 * ## Why several windows, and why not one ratio
 *
 * The documented scale of `CURRENT_NOW` is microamps, but some OEM fuel gauges
 * return milliamps, and a few return microamps that disagree with the counter by
 * an arbitrary factor. Comparing one `CURRENT_NOW` reading with one minute-long
 * average cannot separate "wrong scale" from "this reading happened during a
 * burst". So scale and sign are only inferred from **integrated** raw current over
 * several **separate, non-overlapping windows**, each compared with the charge
 * the counter says moved during that same window:
 *
 * ```
 * ratio = integral(CURRENT_NOW, µA · s) / (counter delta µAh · 3600)
 * ```
 *
 * - ratio ≈ 1 means the property is the documented microamps,
 * - ratio ≈ 1/1000 means the property is milliamps (each unit understates 1000x),
 * - anything else means the property does not track the battery (an under-reading
 *   gauge, a smoothed value, a different quantity): no calibration, never a guess.
 *
 * Sign is read the same way: the integral and the counter delta must agree, and a
 * consistently opposite sign means the raw property is inverted. Plugging in is
 * deliberately ignored — a connected charger is not evidence of which direction
 * the counter moves, and net discharge while plugged is real.
 *
 * ## Evidence rules (documented tolerances)
 *
 * A window counts as evidence only when **all** of these hold:
 *
 * 1. Both properties are present and usable in every poll of the window; a poll
 *    with a missing/unsupported property ends the window (no interpolation).
 * 2. Consecutive polls are `0 < step <= [MAX_GAP_MILLIS]` apart. Sleep, Doze or a
 *    thrown-away poll leaves charge that moved unobserved, so the window restarts.
 * 3. The **open window's own rate** never implies more than [MAX_PLAUSIBLE_AMPS], and
 *    neither does one **poll step** measured over at least
 *    [STEP_REFERENCE_MILLIS]. That bound is a conservative rejection policy, not a
 *    physical law: no phone battery draws anywhere near it, so a window (or a step
 *    over the reference span) averaging more is a reading artefact. A gauge or
 *    charge-cycle reset is *one* way such a jump appears, not the only one, and a
 *    reset whose jump stays inside the bound is not caught here at all (a stall or a
 *    direction flip is caught by rules 4 and 7 instead).
 *
 *    The window is checked as well as the step because the sampled cadence is
 *    temporary: at the 1 s startup cadence a coarsely cached gauge can release
 *    several mAh in one step (1 mAh in 1 s alone implies 3.6 A), and restarting the
 *    window for that would throw away ordinary evidence. A window whose *average*
 *    rate exceeds the bound restarts, so nothing impossible is ever integrated; a
 *    counter that updates only on a percentage change (tens of mAh at once) keeps
 *    being refused rather than smoothed into a fabricated ratio.
 * 4. The counter moves in one direction for the whole window. A direction change
 *    (charging that stops, discharge that starts) is an interruption, and the net
 *    delta of such a window would not describe the integrated current: rejected.
 *    Every raw property value inside the window also stays within the provider's
 *    plausibility cap ([BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS]);
 *    the decoder drops such values before sampling, so the calibrator must drop
 *    them too when it is driven directly.
 * 5. The window spans `[MIN_WINDOW_MILLIS]..[MAX_WINDOW_MILLIS]`. Windows close as
 *    soon as they are long enough, so windows never overlap; two neighbours only
 *    share the boundary reading, never a measurement interval.
 * 6. The counter moved at least [MIN_COUNTER_CHANGE_MICROAMP_HOURS] (3 mAh). A flat
 *    or nearly flat counter means the gauge resolution, not the current, dominates
 *    the comparison. Three steps of a *typical* 1 mAh gauge keep quantisation inside
 *    the tolerance band below; the device's actual resolution is not readable
 *    through the platform API, so this is a heuristic. A device that reports in
 *    coarser units (or only when the percentage changes) simply never reaches
 *    enough windows and stays uncalibrated rather than being calibrated wrongly.
 * 7. The integrated current is non-zero and its ratio to the counter change lands
 *    inside one of two disjoint bands, `1/[RATIO_TOLERANCE]..[RATIO_TOLERANCE]`
 *    (microamps) or `0.001/[RATIO_TOLERANCE]..0.001·[RATIO_TOLERANCE]` (milliamps).
 *    A factor-of-[RATIO_TOLERANCE] tolerance absorbs gauge quantisation and the
 *    load variation that trapezoidal sampling of a bursty signal cannot follow.
 * 8. At least [MIN_WINDOWS] **consecutive** windows agree on **both** unit and sign.
 *    A contradicting window ends the run: the earlier windows are dropped and the
 *    new candidate seeds a fresh run of length one. An alternating device
 *    (A B A B A) therefore never accumulates three of either kind and stays
 *    unconfirmed instead of flipping; only an uninterrupted compatible run
 *    establishes a calibration. The same applies to a window that supports no
 *    conclusion at all (flat counter, no current, ratio in neither band): it is
 *    not compatible evidence, so the pending run ends there. Once established, the
 *    calibration is frozen until [reset]; a late conflict cannot unseat it.
 *
 * ## Stability and failure
 *
 * The returned calibration is stable: once [CurrentCalibration.calibrated], further
 * readings never change it until [reset]. Devices whose counter is unsupported, or
 * whose `CURRENT_NOW` is not a scaled version of the counter, simply never reach
 * enough consecutive evidence; manual unit/polarity overrides remain available for
 * those, and the correction is applied by [BatteryTelemetryDecoder] in preference
 * to this calibration. This is our own evidence rule set; it does not claim to
 * reproduce any third-party app's private calibration.
 *
 * Pure (no Android imports) so every rule is unit tested on the JVM. The sampler
 * updates from the IO dispatcher while lifecycle resets happen on main, so the
 * state is guarded by the instance monitor.
 */
class CurrentCalibrator(
    initial: CurrentCalibration = CurrentCalibration(),
    private val minimumWindowMillis: Long = MIN_WINDOW_MILLIS,
    private val maximumWindowMillis: Long = MAX_WINDOW_MILLIS,
    private val maximumGapMillis: Long = MAX_GAP_MILLIS,
    private val minimumCounterChangeMicroampHours: Long = MIN_COUNTER_CHANGE_MICROAMP_HOURS,
    private val maxPlausibleAmps: Double = MAX_PLAUSIBLE_AMPS,
    private val stepReferenceMillis: Long = STEP_REFERENCE_MILLIS,
    private val minimumWindows: Int = MIN_WINDOWS,
    private val ratioTolerance: Double = RATIO_TOLERANCE,
) {
    /** One accepted piece of evidence: what its scale and sign said. */
    private data class Window(val unit: CurrentUnitOverride, val inverted: Boolean)

    private var calibration: CurrentCalibration = initial
    private val windows = ArrayList<Window>()

    // Open window accumulator. `windowStartElapsedMillis == null` means no window.
    private var windowStartElapsedMillis: Long? = null
    private var lastElapsedMillis: Long = 0L
    private var lastCounterMicroampHours: Long = 0L
    private var lastCurrentMicroamps: Long = 0L
    private var integratedMicroampSeconds: Double = 0.0
    private var counterChangeMicroampHours: Long = 0L
    private var windowDirection: Int = 0

    /**
     * Length of the current run of consecutive compatible windows, whether or not it
     * has established a calibration. A conflicting window, an interruption, or a
     * window that supports no conclusion drops the run back to 0, so this is always
     * the number of agreeing windows immediately behind the last reading.
     */
    @get:Synchronized
    val evidenceWindowCount: Int get() = windows.size

    /**
     * Forgets everything and adopts [initial].
     *
     * Resetting to an already established [initial] keeps that calibration stable
     * (it is returned immediately by [update]); resetting to the default drops it.
     */
    @Synchronized
    fun reset(initial: CurrentCalibration = CurrentCalibration()) {
        calibration = initial
        clearRun()
        clearWindow()
    }

    /**
     * Drops the open measurement window and the run of agreeing windows behind it,
     * keeping the calibration value.
     *
     * Used when a poll could not be read at all: charge moved while nothing was
     * observed, so evidence from before and after the missing poll must never be
     * integrated as one window. The calibration itself is device state, not session
     * evidence — including one discovered in this session whose durable write has
     * not succeeded yet — so it is deliberately *not* reset here.
     */
    @Synchronized
    fun discardPendingEvidence() {
        clearRun()
        clearWindow()
    }

    /**
     * Feeds one poll and returns the calibration that may be used for it.
     *
     * Never guesses: before enough consistent evidence exists the returned value
     * is exactly what was passed to the constructor/[reset], and once established it
     * does not change for the lifetime of the state.
     */
    @Synchronized
    fun update(raw: RawBatteryReading): CurrentCalibration {
        if (calibration.calibrated) return calibration
        val counter = BatteryTelemetryDecoder.supportedChargeCounterOf(raw.chargeCounterMicroampHours)
        val current = BatteryTelemetryDecoder.supportedCurrentOf(raw.currentMicroamps)
        if (counter == null || current == null || !isPlausibleCurrent(current)) {
            // A missing or out-of-range property leaves a hole the integral cannot
            // cross, so the open window and the run behind it both end here.
            clearWindow()
            clearRun()
            return calibration
        }
        val elapsed = raw.elapsedRealtimeMillis
        if (windowStartElapsedMillis == null) {
            startWindow(elapsed, counter, current)
            return calibration
        }
        val stepMillis = elapsed - lastElapsedMillis
        if (stepMillis <= 0L || stepMillis > maximumGapMillis) {
            // Non-monotonic clock, or charge moved while nothing was observed: the
            // windows either side of the hole are not consecutive evidence.
            startWindow(elapsed, counter, current)
            clearRun()
            return calibration
        }
        val stepChange = counter - lastCounterMicroampHours
        if (!isPlausibleStep(stepChange, stepMillis)) {
            // One poll released more charge than any rate policy allows over the
            // reference span: an artefact (a gauge reset is one form), not current, and
            // not a continuous observation either (rule 3).
            startWindow(elapsed, counter, current)
            clearRun()
            return calibration
        }
        val windowSpanMillis = elapsed - (windowStartElapsedMillis ?: elapsed)
        val windowChangeMicroampHours = counterChangeMicroampHours + stepChange
        if (!isPlausibleRate(windowChangeMicroampHours, windowSpanMillis)) {
            // The open window's average rate is beyond any phone: an artefact, not
            // current, and not a continuous observation either. Judged over the window
            // as well as the step, so a coarse gauge releasing several mAh in one fast
            // poll does not restart ordinary evidence (rule 3).
            startWindow(elapsed, counter, current)
            clearRun()
            return calibration
        }
        if (stepChange != 0L) {
            val direction = if (stepChange > 0L) 1 else -1
            if (windowDirection == 0) {
                windowDirection = direction
            } else if (direction != windowDirection) {
                // Interruption/regression inside the window: the net delta would no
                // longer describe the integrated current, and observation was not
                // continuous across it.
                startWindow(elapsed, counter, current)
                clearRun()
                return calibration
            }
        }
        // Trapezoid: the load between two polls is unknown, so the mean of the two
        // readings is the least-assuming estimate of that slice.
        integratedMicroampSeconds +=
            (lastCurrentMicroamps + current) / 2.0 * (stepMillis / 1000.0)
        counterChangeMicroampHours += stepChange
        lastElapsedMillis = elapsed
        lastCounterMicroampHours = counter
        lastCurrentMicroamps = current

        val spanMillis = windowSpanMillis
        if (spanMillis < minimumWindowMillis) return calibration
        if (spanMillis <= maximumWindowMillis &&
            abs(counterChangeMicroampHours) < minimumCounterChangeMicroampHours
        ) {
            // Long enough, but the counter has not moved far enough to out-resolve
            // the gauge: keep accumulating the same window instead of throwing it
            // away and starting a fresh one that would be just as small.
            return calibration
        }
        val window = classifyWindow(
            spanMillis = spanMillis,
            counterChangeMicroampHours = counterChangeMicroampHours,
            integratedMicroampSeconds = integratedMicroampSeconds,
        )
        // Non-overlapping: the next window starts at this boundary reading.
        startWindow(elapsed, counter, current)
        if (window == null) {
            // Flat counter, no current, or a ratio that is neither scale: the window
            // says nothing, so it cannot continue a run of compatible windows.
            clearRun()
        } else {
            accept(window)
        }
        return calibration
    }

    /**
     * Turns one closed window into evidence, or null when the window cannot support
     * a conclusion (flat counter, no current, or a ratio that is neither scale).
     */
    private fun classifyWindow(
        spanMillis: Long,
        counterChangeMicroampHours: Long,
        integratedMicroampSeconds: Double,
    ): Window? {
        if (spanMillis < minimumWindowMillis || spanMillis > maximumWindowMillis) return null
        if (abs(counterChangeMicroampHours) < minimumCounterChangeMicroampHours) return null
        if (!integratedMicroampSeconds.isFinite() || integratedMicroampSeconds == 0.0) return null
        val counterMicroampSeconds = counterChangeMicroampHours * 3600.0
        if (counterMicroampSeconds == 0.0 || !counterMicroampSeconds.isFinite()) return null
        val ratio = abs(integratedMicroampSeconds) / abs(counterMicroampSeconds)
        val lower = 1.0 / ratioTolerance
        val milliampRatio = 0.001
        val unit = when {
            ratio >= lower && ratio <= ratioTolerance -> CurrentUnitOverride.MICROAMPS
            ratio >= milliampRatio * lower && ratio <= milliampRatio * ratioTolerance ->
                CurrentUnitOverride.MILLIAMPS
            else -> return null
        }
        val inverted = (integratedMicroampSeconds < 0.0) != (counterChangeMicroampHours < 0L)
        return Window(unit = unit, inverted = inverted)
    }

    /**
     * Extends the run of agreeing windows, or restarts it at [window] when it
     * contradicts the run.
     *
     * A contradicting window is never merged into the run and never lets the run
     * keep counting: two devices' worth of evidence cannot be reconciled, so the
     * earlier windows are dropped and the new candidate seeds a fresh run of one.
     * That is what keeps an alternating device from reaching [minimumWindows].
     */
    private fun accept(window: Window) {
        if (windows.isNotEmpty() && windows.first() != window) {
            windows.clear()
        }
        windows.add(window)
        if (windows.size < minimumWindows) return
        calibration = CurrentCalibration(
            unit = window.unit,
            inverted = window.inverted,
            evidenceWindows = windows.size,
        )
    }

    /**
     * True when a raw property is inside the provider's plausibility cap. The
     * decoder drops anything beyond [BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS]
     * before it can reach a sample, so the calibrator applies the same cap: a value
     * no phone can report is never integrated into a window.
     */
    private fun isPlausibleCurrent(currentMicroamps: Long): Boolean =
        abs(currentMicroamps) <= BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS

    /**
     * True when one poll step is plausible for a phone battery, measured over at least
     * [stepReferenceMillis].
     *
     * The reference-span floor is what keeps a coarse counter's batch update from
     * looking like a reset: at the 1 s startup cadence one 1 mAh release already
     * implies 3.6 A, and the poll duration says nothing about how much time the gauge
     * accumulated it over. A *plausible* backward step is ordinary discharge and stays
     * usable.
     */
    private fun isPlausibleStep(changeMicroampHours: Long, stepMillis: Long): Boolean {
        if (changeMicroampHours == 0L) return true
        val referenceMillis = maxOf(stepMillis, stepReferenceMillis)
        val impliedAmps =
            abs(changeMicroampHours) * 3600.0 / (referenceMillis / 1000.0) / MICROAMPS_PER_AMP
        return impliedAmps.isFinite() && impliedAmps <= maxPlausibleAmps
    }

    /**
     * True when a measured window's average rate is plausible for a phone battery.
     *
     * The bound is our conservative rejection policy, not a physical limit; a window
     * above it is an artefact such as a gauge reset. A *plausible* backward step is
     * ordinary discharge and stays usable. This is the second half of rule 3: the step
     * check tolerates a coarse gauge, this one bounds what can ever be integrated.
     */
    private fun isPlausibleRate(changeMicroampHours: Long, spanMillis: Long): Boolean {
        if (changeMicroampHours == 0L) return true
        if (spanMillis <= 0L) return false
        val impliedAmps =
            abs(changeMicroampHours) * 3600.0 / (spanMillis / 1000.0) / MICROAMPS_PER_AMP
        return impliedAmps.isFinite() && impliedAmps <= maxPlausibleAmps
    }

    private fun clearWindow() {
        windowStartElapsedMillis = null
        integratedMicroampSeconds = 0.0
        counterChangeMicroampHours = 0L
        windowDirection = 0
    }

    /** Ends the run of consecutive compatible windows; the next one starts a new run. */
    private fun clearRun() {
        windows.clear()
    }

    private fun startWindow(elapsedMillis: Long, counterMicroampHours: Long, currentMicroamps: Long) {
        clearWindow()
        windowStartElapsedMillis = elapsedMillis
        lastElapsedMillis = elapsedMillis
        lastCounterMicroampHours = counterMicroampHours
        lastCurrentMicroamps = currentMicroamps
    }

    companion object {
        /** Shortest window that can hold a meaningful counter change. */
        const val MIN_WINDOW_MILLIS = 15_000L

        /** Longest window compared at once; matches the estimator's gap bound. */
        const val MAX_WINDOW_MILLIS = 5 * 60_000L

        /** Longer between polls and charge moved unobserved: restart the window. */
        const val MAX_GAP_MILLIS = 5 * 60_000L

        /**
         * 3 mAh of counter change. Three steps of a typical 1 mAh gauge keeps
         * quantisation inside [RATIO_TOLERANCE]; below that the gauge, not the
         * current, decides the ratio. A heuristic, not a device guarantee: the actual
         * counter resolution is not exposed by the platform API.
         */
        const val MIN_COUNTER_CHANGE_MICROAMP_HOURS = 3_000L

        /** Consecutive consistent windows required before anything is established. */
        const val MIN_WINDOWS = 3

        /** Factor-of-two agreement required between integrated current and counter. */
        const val RATIO_TOLERANCE = 2.0

        /**
         * 10 A. A deliberately conservative rejection threshold, not a universal
         * physical limit: phone batteries are far below it even while fast-charging,
         * so a measured window averaging more is an artefact rather than current.
         * Applied to the window, never to one short poll step.
         */
        const val MAX_PLAUSIBLE_AMPS = 10.0

        /**
         * Shortest reference span for judging one step: 5 s, the slowest selectable
         * cadence. A gauge may batch its counter updates, so a faster poll step of a
         * few mAh is not an impossible rate by itself.
         */
        const val STEP_REFERENCE_MILLIS = 5_000L

        const val MICROAMPS_PER_AMP = 1_000_000.0
    }
}
