package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import kotlin.math.abs

/**
 * A rate derived from two `CHARGE_COUNTER` readings plus the measured window.
 *
 * [windowMillis] is reported so a sample can say *how much* elapsed time the
 * rate covers; a short window is a coarser answer than a long one.
 */
data class CurrentEstimate(val amps: Double, val windowMillis: Long)

/**
 * Derives a **trailing average** current from `BATTERY_PROPERTY_CHARGE_COUNTER`
 * (µAh) deltas.
 *
 * This rate is an average over a measured window, never an instantaneous reading:
 * it must not be substituted for `CURRENT_NOW`, and [BatterySample.derivedAmps]
 * carries it *beside* the instant fields. It is explicitly **auxiliary**: the live
 * readout uses the calibrated instantaneous current
 * ([io.github.anevilburrito.wpm.model.liveAmps] /
 * [io.github.anevilburrito.wpm.model.forLiveDisplay]), while this average stays
 * available for the remaining-runtime estimate, the developer comparison and the
 * CSV's appended auxiliary columns.
 *
 * What the counter is good for: a delta over a measured window is the average
 * charge the gauge believes moved. Differencing cancels a constant offset, not
 * an incorrect unit scale. What it is not: ground
 * truth. The counter is itself a gauge estimate — it can be coarse, interpolated,
 * clamped, reset at a charge cycle, or simply disagree with the real pack, so this
 * rate is evidence rather than a reference measurement.
 *
 * Rules (deliberately conservative; nothing is invented or clamped):
 *
 * 1. The rate is a **trailing** average: the anchor is kept as close to
 *    [windowMillis] in the past as the poll history allows, so the readout follows
 *    load changes within about a minute instead of converging on the session
 *    average forever. A slower cadence simply reports a wider measured window.
 * 2. Nothing is reported until the measured window is at least [windowMillis]
 *    long, so gauge quantisation cannot masquerade as watts — with exactly one
 *    exception (rule 6): while a baseline's *first* window is still growing toward
 *    [windowMillis], its rate may be reported on a shorter window once the counter
 *    has moved meaningfully. That window then keeps answering as it grows, so the
 *    readout never falls back to "no average yet" after a first valid value.
 * 3. The baseline is dropped and re-anchored at the current reading (reporting
 *    nothing for that poll) when:
 *    - the counter is absent or negative (unsupported property),
 *    - the elapsed time since the previous poll is non-positive,
 *    - the step since the previous poll is longer than [maximumGapMillis] (sleep,
 *      Doze, a cadence change), so charge that moved while unobserved is never
 *      attributed to a window that did not watch it,
 *    - a **single step** implies more than [maximumRateAmps] when measured over at
 *      least [stepReferenceMillis], or
 *    - the **measured window's own rate** exceeds [maximumRateAmps] (for example, a
 *      gauge reset).
 *    The step gets a reference span floor and the window a rate check because the
 *    counter batches its updates: a coarsely cached gauge releases several mAh in one
 *    poll (1 mAh in 1 s alone implies 3.6 A, and a gauge that reports every few
 *    seconds can easily produce ten times that at a 1 s cadence), and calling that a
 *    reset would restart the window on ordinary readings. Judging the whole window
 *    as well means an average no phone could sustain can never be published, and a
 *    counter that moves only on a percentage change (tens of mAh at once) is refused
 *    instead of being smoothed into a fabricated number. Plausibly sized backward
 *    steps remain ordinary discharge; rejected windows restart rather than being
 *    clamped.
 * 4. `amps = deltaMicroampHours * 3600 / deltaSeconds / 1_000_000`.
 * 5. An unchanged counter is reported as exactly 0 A only after
 *    [zeroDeltaWindowMillis] without movement; shorter flat stretches stay `null`
 *    because the counter's resolution cannot distinguish "no current" from "less
 *    than one counter step". A short window therefore never manufactures a 0 A
 *    reading, and the early window of rule 6 requires real movement.
 * 6. **Acquisition, then one growing window.** Before this baseline has reported
 *    any rate, a window of at least [initialWindowMillis] is accepted early when
 *    the counter moved at least [initialMovementMicroampHours] net *in one
 *    direction* across it. A reversal inside that window disqualifies the shortcut
 *    (mixed movement over a few seconds is not a trustworthy average), and the
 *    poll keeps waiting for the ordinary [windowMillis] window.
 *
 *    After that first rate the *same* window keeps answering on every poll until
 *    it reaches [windowMillis], with its rate recomputed from the growing span.
 *    Reporting nothing again there would say "no average yet" for a window that
 *    already has one, so the readout would drop back to Calibrating for the rest of
 *    the window for no measurement reason. Continuation is not a second shortcut:
 *    the number stays the window's measured net movement divided by the time it
 *    took, so it decays as the window grows, and it stops being reported as soon as
 *    the net movement falls below [initialMovementMicroampHours] (quantisation
 *    would then dominate). From [windowMillis] on, the ordinary trailing rules
 *    apply, including rule 5's flat policy.
 *
 *    [initialMovementMicroampHours] is a *heuristic*, not proof: the counter's
 *    resolution is not observable through the platform API, and some devices
 *    quantise coarsely (or only update the counter when the reported percentage
 *    changes). "Three typical mAh" makes the early window far more likely to
 *    reflect current than quantisation; it cannot guarantee that on every device,
 *    so the early average is presented as what it is — a short measured window that
 *    lengthens into the full trailing window.
 *
 * Pure (no Android imports) so every rule is unit tested on the JVM. Callers may
 * read/reset from different threads (the sampler reads on IO, lifecycle resets
 * happen on main), so the small state is guarded by the instance monitor.
 */
class CurrentEstimator(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val zeroDeltaWindowMillis: Long = ZERO_DELTA_WINDOW_MILLIS,
    private val maximumGapMillis: Long = MAX_GAP_MILLIS,
    private val maximumRateAmps: Double = MAX_PLAUSIBLE_RATE_AMPS,
    private val stepReferenceMillis: Long = STEP_REFERENCE_MILLIS,
    private val initialWindowMillis: Long = INITIAL_WINDOW_MILLIS,
    private val initialMovementMicroampHours: Long = INITIAL_MOVEMENT_MICROAMP_HOURS,
) {
    private data class Poll(val elapsedMillis: Long, val microampHours: Long)

    private val polls = ArrayDeque<Poll>()
    private var lastChangeElapsedMillis: Long? = null

    /**
     * True until this baseline has reported its first rate. While it is true a shorter
     * window ([initialWindowMillis]) may be used, and only with meaningful movement.
     */
    private var initialAcquisitionPending = true

    /**
     * True once the early window has answered and while the same window is still
     * growing toward [windowMillis]. It keeps reporting that window instead of
     * falling back to "no average yet" (see rule 6).
     */
    private var growingWindow = false

    /** Direction of the counter movement since the current anchor, 0 while flat. */
    private var windowDirection = 0

    /** True when the counter changed direction inside the current window. */
    private var windowDirectionReversed = false

    /** Drops the baseline; the next reading simply re-anchors it. */
    @Synchronized
    fun reset() {
        polls.clear()
        lastChangeElapsedMillis = null
        restartState()
    }

    /**
     * True when a baseline exists, i.e. at least one poll has been anchored since the
     * last reset or re-anchor. Says nothing about whether a rate was reported; use
     * the return value of [update] for that.
     */
    @Synchronized
    fun hasBaseline(): Boolean = polls.isNotEmpty()

    /**
     * Feeds one poll. Returns the trailing average when the measured window supports
     * one, otherwise `null` — never a guess from a single reading. This is an average,
     * so it is not a substitute for the instantaneous property.
     */
    @Synchronized
    fun update(elapsedRealtimeMillis: Long, chargeCounterMicroampHours: Long?): CurrentEstimate? {
        val counter = chargeCounterMicroampHours
            ?.takeIf { it >= 0L && it != Long.MIN_VALUE }
        if (counter == null) {
            // Absent/unsupported counter: no baseline can describe a window.
            reset()
            return null
        }
        val previous = polls.lastOrNull()
        if (previous == null) {
            restart(elapsedRealtimeMillis, counter)
            return null
        }
        val stepMillis = elapsedRealtimeMillis - previous.elapsedMillis
        if (stepMillis <= 0L || stepMillis > maximumGapMillis) {
            // Non-monotonic clock, or an unobserved gap: the difference no longer
            // describes a window we actually measured.
            restart(elapsedRealtimeMillis, counter)
            return null
        }
        val stepChange = counter - previous.microampHours
        if (isImplausibleStep(stepChange, stepMillis)) {
            // One poll released more charge than any rate policy allows, even when
            // that step is spread over the reference span: a gauge reset (rule 3).
            restart(elapsedRealtimeMillis, counter)
            return null
        }
        if (stepChange != 0L) {
            lastChangeElapsedMillis = elapsedRealtimeMillis
            val direction = if (stepChange > 0L) 1 else -1
            if (windowDirection == 0) {
                windowDirection = direction
            } else if (direction != windowDirection) {
                windowDirectionReversed = true
            }
        }
        polls.addLast(Poll(elapsedRealtimeMillis, counter))
        // Keep the anchor as recent as possible while still at least one window
        // old, so the rate tracks the last minute instead of the whole session.
        while (polls.size > 2 &&
            elapsedRealtimeMillis - polls.elementAt(1).elapsedMillis >= windowMillis
        ) {
            polls.removeFirst()
        }
        val anchor = polls.first()
        val spanMillis = elapsedRealtimeMillis - anchor.elapsedMillis
        val deltaMicroampHours = counter - anchor.microampHours
        if (isImplausibleRate(deltaMicroampHours, spanMillis)) {
            // The window's own average is beyond any phone battery: restart rather
            // than publish a fabricated average (rule 3).
            restart(elapsedRealtimeMillis, counter)
            return null
        }
        if (spanMillis < windowMillis) {
            if (initialAcquisitionPending) {
                // Short of the trailing window: only a first acquisition with
                // meaningful one-directional movement may answer earlier. A flat or
                // barely-moving short counter says nothing (rule 5/6), and the
                // anchor is kept so the ordinary window still closes on schedule.
                if (!windowDirectionReversed &&
                    spanMillis >= initialWindowMillis &&
                    abs(deltaMicroampHours) >= initialMovementMicroampHours
                ) {
                    val amps = amperesOf(deltaMicroampHours, spanMillis)
                    if (amps.isFinite()) {
                        initialAcquisitionPending = false
                        growingWindow = true
                        return CurrentEstimate(amps = amps, windowMillis = spanMillis)
                    }
                }
                return null
            }
            if (growingWindow && abs(deltaMicroampHours) >= initialMovementMicroampHours) {
                // The window that answered first keeps answering while it grows to
                // [windowMillis]: same measured movement, longer measured span. A
                // reversal inside it needs no special case any more — the number is
                // the net movement of the whole window, which is what a trailing
                // average is — but movement below the meaningful change cannot
                // out-resolve the gauge, so nothing is published then.
                val amps = amperesOf(deltaMicroampHours, spanMillis)
                if (amps.isFinite()) {
                    return CurrentEstimate(amps = amps, windowMillis = spanMillis)
                }
            }
            return null
        }
        if (deltaMicroampHours == 0L) {
            val flatFor = elapsedRealtimeMillis - (lastChangeElapsedMillis ?: anchor.elapsedMillis)
            return if (flatFor >= zeroDeltaWindowMillis) {
                // A reported rate closes the initial acquisition even when it is 0 A.
                initialAcquisitionPending = false
                growingWindow = false
                CurrentEstimate(amps = 0.0, windowMillis = spanMillis)
            } else {
                null
            }
        }
        val amps = amperesOf(deltaMicroampHours, spanMillis)
        if (!amps.isFinite()) {
            restart(elapsedRealtimeMillis, counter)
            return null
        }
        // The ordinary window answered, so any later rate keeps using it.
        initialAcquisitionPending = false
        growingWindow = false
        return CurrentEstimate(amps = amps, windowMillis = spanMillis)
    }

    /**
     * `deltaMicroampHours * 3600 / deltaSeconds / 1e6`: charge over time, µAh/s
     * scaled to amperes. The sign follows the counter, so discharge is negative.
     */
    private fun amperesOf(deltaMicroampHours: Long, spanMillis: Long): Double =
        deltaMicroampHours * 3600.0 / (spanMillis / 1000.0) / MICROAMPS_PER_AMP

    /**
     * Rejects one poll step that released more charge than any phone could have passed
     * in [stepReferenceMillis], using the same conservative policy as calibration.
     *
     * The floor on the reference span is what keeps a coarse counter's batch update
     * from looking like a reset: at the 1 s startup cadence the step's own duration is
     * far shorter than the time the gauge may have accumulated it over.
     */
    private fun isImplausibleStep(deltaMicroampHours: Long, stepMillis: Long): Boolean {
        if (deltaMicroampHours == 0L) return false
        val referenceMillis = maxOf(stepMillis, stepReferenceMillis)
        val impliedAmps = abs(amperesOf(deltaMicroampHours, referenceMillis))
        return !impliedAmps.isFinite() || impliedAmps > maximumRateAmps
    }

    /**
     * Rejects a window whose own average rate is beyond any phone battery, using the
     * same conservative policy as calibration. A gauge reset is one possible cause;
     * this is not a physical proof. It bounds what can ever be *published*: no window
     * averaging above [maximumRateAmps] is reported (rule 3).
     */
    private fun isImplausibleRate(deltaMicroampHours: Long, spanMillis: Long): Boolean {
        if (spanMillis <= 0L) return false
        val impliedAmps = abs(amperesOf(deltaMicroampHours, spanMillis))
        return !impliedAmps.isFinite() || impliedAmps > maximumRateAmps
    }

    private fun restart(elapsedMillis: Long, microampHours: Long) {
        polls.clear()
        polls.addLast(Poll(elapsedMillis, microampHours))
        lastChangeElapsedMillis = elapsedMillis
        // A fresh baseline re-opens the early-acquisition window: the previous
        // baseline was interrupted, so nothing is being shortened unfairly.
        restartState()
    }

    private fun restartState() {
        initialAcquisitionPending = true
        growingWindow = false
        windowDirection = 0
        windowDirectionReversed = false
    }

    companion object {
        /**
         * Trailing average window: long enough to smooth gauge quantisation,
         * short enough that a load change shows up within a minute.
         */
        const val WINDOW_MILLIS = 60_000L

        /** Movement-free time after which "the counter did not move" means 0 A. */
        const val ZERO_DELTA_WINDOW_MILLIS = 600_000L

        /**
         * Shortest early window for a baseline's *first* average.
         *
         * It is only half of the shortcut: [initialMovementMicroampHours] must have
         * moved across it as well. 3 mAh inside 15 s means at least ~720 mA, so a
         * device idling at a few hundred mA still waits for the whole trailing window
         * instead of being shown an early, quantisation-limited number — the shortcut
         * exists for a rate high enough to out-resolve a typical gauge quickly, not to
         * shorten every first window.
         */
        const val INITIAL_WINDOW_MILLIS = 15_000L

        /**
         * Meaningful net counter movement for the early window: 3 mAh, i.e. three
         * steps of a *typical* 1 mAh gauge, matched to
         * [CurrentCalibrator.MIN_COUNTER_CHANGE_MICROAMP_HOURS].
         *
         * The device's actual counter resolution is not readable through the
         * platform API, so this is a heuristic rather than a guarantee that 3 mAh is
         * above every quantisation step: a device that reports in coarser units (or
         * only on a percentage change) can still dominate a short window with it.
         * Such a device simply keeps waiting for the full trailing window more often.
         */
        const val INITIAL_MOVEMENT_MICROAMP_HOURS = 3_000L

        /** Longer than this and charge moved while unobserved: re-anchor instead. */
        const val MAX_GAP_MILLIS = 5 * 60_000L

        /**
         * Conservative rate-rejection policy shared with calibration, not a
         * universal physical limit. Above it, re-anchor rather than publish.
         *
         * Applied both to a single poll step (over at least
         * [STEP_REFERENCE_MILLIS]) and to the measured window's average rate, because
         * a coarsely cached counter legitimately releases several mAh in one step at a
         * fast cadence.
         */
        const val MAX_PLAUSIBLE_RATE_AMPS = 10.0

        /**
         * Shortest reference span for judging one step: 5 s, the slowest selectable
         * cadence. A gauge may batch its counter updates, so a faster poll step can
         * carry several seconds of real charge and is not an implausible rate on its
         * own.
         */
        const val STEP_REFERENCE_MILLIS = 5_000L

        const val MICROAMPS_PER_AMP = 1_000_000.0
    }
}

/**
 * Bounded startup sampling policy: the *temporary* cadence used to acquire this
 * recording's first calibrated instantaneous interpretation.
 *
 * Startup requests five roughly one-second polls for [FastCurrentInterpreter].
 * This produces a labelled estimate when possible, without waiting for the longer
 * [CurrentCalibrator] consistency check, which continues at the selected cadence.
 * An ambiguous completed startup check also ends the burst.
 * Unlike the former average-first policy, the burst does **not** wait for the
 * charge-counter average: the live reading is the primary metric and must not be
 * held back by a 60 s counter window.
 *
 * ## Bounds and non-goals
 *
 * - It is **bounded**: at most [MAX_BURST_MILLIS] per recording, and per explicit
 *   calibration reset. It is not an extra sampling mode and it never runs beyond
 *   that window.
 * - It ends as soon as the instantaneous interpretation is established, or as soon
 *   as no usable `CURRENT_NOW` exists (nothing can ever be calibrated then). It does
 *   not wait for the unrelated counter average.
 * - It **ends and stays ended**: once the burst has stopped, no later poll restarts
 *   it. A failed or uncalibrated reading afterwards is a real change of evidence,
 *   not a cadence problem. Only a new recording or an explicit calibration reset
 *   re-opens a burst (see [start]).
 * - It does **not** acquire a wake lock, busy-loop or mutate any setting: the
 *   caller only shortens its own wait between polls, so the user's configured
 *   cadence resumes untouched and the wake-lock policy is unchanged.
 * - It conveys no trust: a denser poll stream does not weaken the counter-evidence
 *   durations or the three-consecutive-window calibration rule. Quantisation does
 *   not shrink because more readings exist, so those policies are untouched.
 * - Attempt time is the caller's basis for the cadence: the interval is measured
 *   from the previous poll *attempt* (not from the end of its processing), so the
 *   extra work of a burst poll cannot stretch the cadence.
 * - Outside the burst the configured interval is also the event-trigger floor, so a
 *   battery broadcast can never make a 2 s (or 5 s) selection sample faster than the
 *   user chose. A 1 s selection is therefore honoured exactly, not rounded up to the
 *   old 1.5 s floor.
 *
 * Pure and Android-free; every branch is unit tested on the JVM.
 */
object StartupSamplingPolicy {
    /**
     * The fastest cadence the app supports (also the 1 s user choice). Everything
     * else is floored at this value so an invalid/zero setting cannot cause a storm.
     */
    const val MINIMUM_INTERVAL_MILLIS = 1_000L

    /** Temporary cadence between polls while the burst is active. */
    const val BURST_INTERVAL_MILLIS = MINIMUM_INTERVAL_MILLIS

    /** Event-trigger floor during the burst: bursts of platform broadcasts still wait. */
    const val BURST_MINIMUM_SPACING_MILLIS = MINIMUM_INTERVAL_MILLIS

    /**
     * Absolute event-trigger floor when no configured interval is known yet. The
     * normal floor is the selected interval itself (see [decide]), not this value.
     */
    const val ABSOLUTE_MINIMUM_SPACING_MILLIS = MINIMUM_INTERVAL_MILLIS

    /** Hard cap on one burst; a recording/reset beyond this uses the normal cadence. */
    const val MAX_BURST_MILLIS = 90_000L

    /**
     * The cadence to use for the next poll.
     *
     * [burstActive] is true while [burstStartElapsedMillis] is set (the burst is
     * still running), so a caller can report it as developer detail.
     */
    data class Decision(
        val intervalMillis: Long,
        val minimumSpacingMillis: Long,
        val burstStartElapsedMillis: Long?,
    ) {
        val burstActive: Boolean get() = burstStartElapsedMillis != null
    }

    /** Starts a fresh burst at [nowElapsedMillis]; used at start and on explicit reset. */
    fun start(nowElapsedMillis: Long): Decision = Decision(
        intervalMillis = BURST_INTERVAL_MILLIS,
        minimumSpacingMillis = BURST_MINIMUM_SPACING_MILLIS,
        burstStartElapsedMillis = nowElapsedMillis,
    )

    /**
     * Decides the next cadence.
     *
     * @param nowElapsedMillis monotonic now, from the same clock as
     *   [burstStartElapsedMillis]; both must be attempt/loop times, not wall clock.
     * @param burstStartElapsedMillis monotonic start of the active burst, or null
     *   when none is running.
     * @param instantCalibrationEstablished whether `CURRENT_NOW`'s unit and polarity
     *   are established (manual or evidence).
     * @param instantReadingSupported whether a usable `CURRENT_NOW` exists: true when
     *   one has been seen since the burst began, or when no successful poll has been
     *   observed yet (absence is not yet evidence). False means the property is
     *   unsupported, so calibration can never finish and the burst must end.
     * @param configuredIntervalMillis the user's configured cadence (1/2/5 s).
     */
    fun decide(
        nowElapsedMillis: Long,
        burstStartElapsedMillis: Long?,
        instantCalibrationEstablished: Boolean,
        instantReadingSupported: Boolean,
        configuredIntervalMillis: Long,
        startupCheckComplete: Boolean = false,
    ): Decision {
        val normalInterval = configuredIntervalMillis.coerceAtLeast(MINIMUM_INTERVAL_MILLIS)
        if (burstStartElapsedMillis == null) {
            // The configured interval is the normal spacing floor as well, so a
            // battery broadcast cannot silently raise the selected sampling rate.
            return Decision(normalInterval, normalInterval, null)
        }
        val elapsedMillis = nowElapsedMillis - burstStartElapsedMillis
        val calibrationSettled = instantCalibrationEstablished || startupCheckComplete || !instantReadingSupported
        // A negative elapsed span means the monotonic clock moved backwards;
        // treating the burst as over is the safe, bounded answer.
        val expired = elapsedMillis < 0L || elapsedMillis >= MAX_BURST_MILLIS
        if (expired || calibrationSettled) {
            return Decision(normalInterval, normalInterval, null)
        }
        // [normalInterval] is at least one second, so the burst is never slower than
        // the configured cadence: it only ever shortens the wait.
        return Decision(
            intervalMillis = BURST_INTERVAL_MILLIS,
            minimumSpacingMillis = BURST_MINIMUM_SPACING_MILLIS,
            burstStartElapsedMillis = burstStartElapsedMillis,
        )
    }
}
