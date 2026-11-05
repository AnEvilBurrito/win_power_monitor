package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import kotlin.math.abs

/**
 * Fast, **tentative** reading of the scale and sign of
 * `BATTERY_PROPERTY_CURRENT_NOW`, learned from a single five-poll window.
 *
 * [CurrentCalibrator] performs a longer charge-counter consistency check (not
 * an independent accuracy measurement). Waiting for it used to block live
 * readings for tens of seconds or minutes. This class exists only to bridge that gap: within the
 * first few seconds of a recording it offers a *plausible* interpretation that
 * the service may use until the counter calibrator supersedes it. It never
 * accumulates durable evidence and never claims calibrated status:
 *
 * - the returned [CurrentCalibration.evidenceWindows] is **always 0**. The service
 *   keeps it out of calibration publication, whose automatic-save guard also
 *   rejects values below the counter-evidence threshold;
 * - the interpretation is a snapshot, not a conclusion — [CurrentCalibrator]'s
 *   `evidenceWindows` counter and its thresholds are not touched or reused here.
 *
 * ## The window
 *
 * Five timed polls are enough for this heuristic, not for an accuracy claim.
 * Equal readings may be cached hardware data; polling does not force a refresh.
 * A poll is accepted only when:
 *
 * 1. `CURRENT_NOW` is present, not the platform's "unsupported" sentinel, and its
 *    magnitude is inside [BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS].
 *    Anything else is a hole: the polls collected so far are dropped, because
 *    evidence either side of a missing property cannot be one observation, and no
 *    zero is ever fabricated in its place.
 * 2. It is at least [MIN_STEP_MILLIS] newer than the previous accepted poll. A
 *    repeated timestamp, a non-monotonic clock, or a poll closer than that is *not*
 *    an independent observation and is ignored without touching the window (a
 *    duplicate must not complete the window early).
 * 3. It is at most [MAX_GAP_MILLIS] newer. Sleep/Doze or a thrown-away poll leaves
 *    time unobserved, so the window restarts at that poll instead of spanning the
 *    hole.
 *
 * At most [WINDOW_POLLS] polls are retained, so [Result.pollCount] saturates at 5
 * and the window *slides*: at the 1/2/5 s cadences a sixth poll simply retires the
 * oldest one and the window stays complete. [Result.complete] means "this window
 * holds five independent polls spanning at least [MIN_WINDOW_SPAN_MILLIS]" and is
 * true even when the window supports no conclusion — it is not a promise that the
 * inference succeeded, only that the fast burst should not keep running.
 *
 * ## Unit heuristic
 *
 * The documented scale is microamps, but some gauges return milliamps. Magnitude
 * alone separates typical phone currents:
 *
 * | `abs(raw)` | reading |
 * |---|---|
 * | `1..`[MILLIAMP_MAX_MAGNITUDE] | milliamps (an actual 2–5 A fast charge is 2000–5000) |
 * | [MICROAMP_MIN_MAGNITUDE]`..`[BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS] | microamps |
 * | in between | ambiguous, no inference |
 *
 * Zero samples carry no magnitude, so they are ignored for the unit *and* the
 * sign; the non-zero samples must all classify the same way or the window is
 * ambiguous. The one exception is a window of **only** zeros: a true zero reads
 * the same in either unit and either sign, so it establishes the documented
 * microamps + non-inverted convention. Such a *zero-only* estimate is recorded as
 * such: the first non-zero value after it discards the estimate and starts a fresh
 * window, so an all-idle phone can never leave a microamp guess applied to a gauge
 * that actually reports milliamps.
 *
 * ## Polarity rules
 *
 * In order of precedence:
 *
 * 1. the manual polarity override wins outright;
 * 2. a coherent charge-counter movement across the window wins over the platform
 *    status. "Coherent" is sign-only, not a magnitude comparison: the counter is
 *    read for direction, never for the value of the current. It must be present in
 *    every poll of the window, never reverse direction (a charge cycle or a gauge
 *    reset inside the window is not one observation), and neither a single step nor
 *    the whole window may imply more than [MAX_PLAUSIBLE_AMPS] measured over at
 *    least [STEP_REFERENCE_MILLIS];
 * 3. otherwise the platform state supplies the *expected* direction: unplugged or
 *    `STATUS_DISCHARGING` expects discharge; `STATUS_CHARGING` expects charge, a
 *    deliberate heuristic — net discharge while plugged is real, so a counter
 *    direction always outranks it;
 * 4. a state that says nothing about direction (`FULL`, `NOT_CHARGING`, unknown, or
 *    a plugged flag without a status) falls back to the documented non-inverted
 *    convention. A device that only ever reports positive values is emphatically
 *    **not** evidence of inverted firmware. The standard convention is an explicit
 *    assumption, not a measured conclusion.
 *
 * The observed raw signs must be coherent too: every non-zero sample has to share
 * one sign, and all applicable platform evidence has to expect one direction. Only
 * then does comparing "raw positive means charging" against the expected direction
 * yield the tentative inversion.
 *
 * ## Stability
 *
 * Once a component is learned it is frozen for the life of the state: a later
 * window only fills in a component that is still unknown, it never overwrites one
 * that is already known. Movement does not matter — a polarity learned while
 * discharging is not re-derived when the charger is connected. Only three things
 * unseat it:
 *
 * 1. [reset];
 * 2. a change of the manual unit/polarity settings, because the same raw value
 *    would then mean something different, so both the pending polls and the
 *    inference derived from them are dropped and a fresh five-poll window (or the
 *    new manual values) applies;
 * 3. the zero-only estimate described above, whose exception exists precisely so a
 *    unit can never be carried over from zeros to real values.
 *
 * A durable, counter-checked calibration from [CurrentCalibrator] supersedes the
 * result by construction: the service prefers that calibration and this class is
 * not consulted for it.
 *
 * ## Result
 *
 * [Result.interpretation] is the merged tentative interpretation: the manual
 * override where one exists, otherwise what the window inferred. It is `null`
 * while nothing at all is known, and may be *partial* (`unit == AUTO` or
 * `inverted == null`) when only one of the two was inferable —
 * [CurrentCalibration.calibrated] is the completeness flag. `evidenceWindows` is
 * always 0 because this is not evidence.
 *
 * Pure (no Android imports) so every rule is unit tested on the JVM. The sampler
 * updates from the IO dispatcher while lifecycle resets happen on main, so all
 * state is guarded by the instance monitor.
 */
class FastCurrentInterpreter {

    /** One accepted poll, reduced to what the fast rules need. */
    private data class Poll(
        val elapsedMillis: Long,
        val currentMicroamps: Long,
        val plugged: Int?,
        val status: Int?,
        val chargeCounterMicroampHours: Long?,
    )

    /**
     * Snapshot of the fast interpretation.
     *
     * @param interpretation tentative scale/sign, `null` while nothing is known. It
     *   can be partial, so check [CurrentCalibration.calibrated] before treating it
     *   as complete. [CurrentCalibration.evidenceWindows] is always 0.
     * @param pollCount accepted polls in the current window, 0..5 (saturating).
     * @param complete true when the current window holds five independent polls
     *   spanning at least [MIN_WINDOW_SPAN_MILLIS], whether or not they established
     *   anything.
     */
    data class Result(
        val interpretation: CurrentCalibration? = null,
        val pollCount: Int = 0,
        val complete: Boolean = false,
    )

    private val polls = ArrayList<Poll>(WINDOW_POLLS)

    /** Learned scale; null while unknown. Never overwritten once set. */
    private var inferredUnit: CurrentUnitOverride? = null

    /** Learned sign convention; null while unknown. Never overwritten once set. */
    private var inferredInverted: Boolean? = null

    /** True when [inferredUnit]/[inferredInverted] came from an all-zero window. */
    private var zeroOnlyEstimate = false

    /** Manual overrides seen so a settings change can drop stale evidence. */
    private var activeManualUnit = CurrentUnitOverride.AUTO
    private var activeManualPolarity = CurrentPolarityOverride.AUTO

    /** The current snapshot; see [Result]. */
    @get:Synchronized
    val result: Result
        get() = snapshot()

    /**
     * Feeds one poll and returns the resulting snapshot.
     *
     * [manualUnit] and [manualPolarity] are the user's overrides in force for this
     * poll. Passing different values than the previous call is a settings change:
     * the pending window and the inference derived from it are dropped, so the next
     * five polls are interpreted under the new meaning (a manual value itself
     * applies immediately).
     */
    @Synchronized
    fun update(
        raw: RawBatteryReading,
        manualUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        manualPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
    ): Result {
        if (manualUnit != activeManualUnit || manualPolarity != activeManualPolarity) {
            // A raw value read as milliamps and then as microamps is a different
            // quantity: neither the pending polls nor the inference survive.
            activeManualUnit = manualUnit
            activeManualPolarity = manualPolarity
            polls.clear()
            clearInference()
        }

        val current = BatteryTelemetryDecoder.supportedCurrentOf(raw.currentMicroamps)
        if (current == null || !isPlausibleMagnitude(current)) {
            // Missing, unsupported sentinel, or beyond any phone: a hole. Nothing is
            // fabricated and no window may reach across it.
            polls.clear()
            return snapshot()
        }

        // Evidence must use one monotonic clock, including the valid boot-time 0.
        // Wall time is never a fallback: clock corrections could manufacture polls.
        val elapsed = raw.elapsedRealtimeMillis
        if (elapsed < 0L) {
            polls.clear()
            return snapshot()
        }

        val previous = polls.lastOrNull()
        if (previous != null) {
            val step = elapsed - previous.elapsedMillis
            // Duplicate timestamps and non-monotonic clocks carry no new evidence.
            if (step <= 0L) return snapshot()
            // Too close to be an independent poll; must not complete the window early.
            if (step < MIN_STEP_MILLIS) return snapshot()
            // Time moved unobserved: the window cannot span the hole.
            if (step > MAX_GAP_MILLIS) polls.clear()
        }

        if (zeroOnlyEstimate && current != 0L) {
            // The all-zero window said nothing about scale, so a real value must never
            // be read with the guess it produced. Start a fresh window.
            polls.clear()
            clearInference()
        }

        polls.add(
            Poll(
                elapsedMillis = elapsed,
                currentMicroamps = current,
                plugged = raw.plugged,
                status = raw.status,
                chargeCounterMicroampHours =
                    BatteryTelemetryDecoder.supportedChargeCounterOf(raw.chargeCounterMicroampHours),
            ),
        )
        while (polls.size > WINDOW_POLLS) polls.removeAt(0)
        learn()
        return snapshot()
    }

    /** Forgets the window, the tentative inference, and the manual settings seen. */
    @Synchronized
    fun reset() {
        polls.clear()
        clearInference()
        activeManualUnit = CurrentUnitOverride.AUTO
        activeManualPolarity = CurrentPolarityOverride.AUTO
    }

    /**
     * Drops the pending poll window, keeping the interpretation that was
     * already inferred.
     *
     * Used when a poll could not be observed and the partial window around it is not
     * evidence any more. A learned interpretation is device state, not window
     * evidence, so it is deliberately not cleared here — only [reset] and a settings
     * change drop it.
     */
    @Synchronized
    fun discardPendingEvidence() {
        // Even a full ambiguous window is pending evidence. Do not stitch it
        // across an unread poll; the already learned device interpretation survives.
        polls.clear()
    }

    /** Merges the manual overrides with the inferred components into one snapshot. */
    private fun snapshot(): Result {
        val unit = when (activeManualUnit) {
            CurrentUnitOverride.MILLIAMPS -> CurrentUnitOverride.MILLIAMPS
            CurrentUnitOverride.MICROAMPS -> CurrentUnitOverride.MICROAMPS
            CurrentUnitOverride.AUTO -> inferredUnit ?: CurrentUnitOverride.AUTO
        }
        val inverted = when (activeManualPolarity) {
            CurrentPolarityOverride.INVERTED -> true
            CurrentPolarityOverride.STANDARD -> false
            CurrentPolarityOverride.AUTO -> inferredInverted
        }
        val interpretation = when {
            unit == CurrentUnitOverride.AUTO && inverted == null -> null
            // Evidence counter stays 0: this class never claims durable evidence.
            else -> CurrentCalibration(unit = unit, inverted = inverted, evidenceWindows = 0)
        }
        return Result(
            interpretation = interpretation,
            pollCount = polls.size,
            complete = isComplete(),
        )
    }

    private fun isComplete(): Boolean {
        if (polls.size < WINDOW_POLLS) return false
        return spanMillis() >= MIN_WINDOW_SPAN_MILLIS
    }

    private fun spanMillis(): Long {
        if (polls.size < 2) return 0L
        return polls.last().elapsedMillis - polls.first().elapsedMillis
    }

    /**
     * Infers whatever the completed window can support, filling in only components
     * that are still unknown.
     */
    private fun learn() {
        if (!isComplete()) return
        val nonZero = polls.filter { it.currentMicroamps != 0L }
        if (nonZero.isEmpty()) {
            if (inferredUnit == null && inferredInverted == null) {
                // A true zero is unit and sign invariant, so the documented convention
                // is the only reading that is correct either way. Flagged as zero-only
                // so the first real value discards it instead of trusting it.
                inferredUnit = CurrentUnitOverride.MICROAMPS
                inferredInverted = false
                zeroOnlyEstimate = true
            }
            return
        }
        if (inferredUnit == null) inferredUnit = unitOf(nonZero)
        if (inferredInverted == null) inferredInverted = inversionOf(polls)
    }

    /**
     * Scale implied by the window's non-zero magnitudes, or null when the magnitudes
     * disagree or land in the ambiguous band.
     */
    private fun unitOf(nonZero: List<Poll>): CurrentUnitOverride? {
        var unit: CurrentUnitOverride? = null
        for (poll in nonZero) {
            val magnitude = abs(poll.currentMicroamps)
            val pollUnit = when {
                magnitude <= MILLIAMP_MAX_MAGNITUDE -> CurrentUnitOverride.MILLIAMPS
                magnitude >= MICROAMP_MIN_MAGNITUDE -> CurrentUnitOverride.MICROAMPS
                // Between the two bands: no documented reading.
                else -> return null
            }
            if (unit == null) unit = pollUnit else if (unit != pollUnit) return null
        }
        return unit
    }

    /**
     * Sign convention implied by the window, or null when it cannot be one coherent
     * convention (mixed raw signs, contradicting platform evidence).
     */
    private fun inversionOf(window: List<Poll>): Boolean? {
        val nonZero = window.filter { it.currentMicroamps != 0L }
        if (nonZero.isEmpty()) return null
        val negative = nonZero.count { it.currentMicroamps < 0L }
        // Every non-zero sample must agree before a sign can be compared with an
        // expected direction; a sign that changes inside the window is not a
        // convention.
        val coherentSign = negative == 0 || negative == nonZero.size
        val rawCharges = negative == 0

        // Counter direction beats platform status: a plugged phone can discharge.
        counterChargeDirection(window)?.let { counterCharges ->
            return if (!coherentSign) null else rawCharges != counterCharges
        }

        val applicable = window.mapNotNull { expectedChargeOf(it) }.distinct()
        val expectedCharge = when (applicable.size) {
            // No usable platform state: the documented non-inverted convention is all
            // that is left. A device that only reports positive values is *not*
            // evidence of inverted firmware. Standard polarity is an assumption.
            0 -> return false
            1 -> applicable.first()
            // The platform state disagreed with itself across the window.
            else -> return null
        }
        return if (!coherentSign) null else rawCharges != expectedCharge
    }

    /**
     * True when the counter rose (charging), false when it fell (discharging), null
     * when the counter cannot support a direction.
     *
     * Only the direction is used: the counter's absolute scale is unknown, so no
     * ratio is ever computed. Every poll needs a counter, no step may reverse, and no
     * step or window may imply more than [MAX_PLAUSIBLE_AMPS] measured over at least
     * [STEP_REFERENCE_MILLIS] (a reset shows up as exactly that).
     */
    private fun counterChargeDirection(window: List<Poll>): Boolean? {
        var direction = 0
        var netChange = 0L
        for (index in 1 until window.size) {
            val previous = window[index - 1].chargeCounterMicroampHours ?: return null
            val counter = window[index].chargeCounterMicroampHours ?: return null
            val change = counter - previous
            if (change == 0L) continue
            val reference = maxOf(
                STEP_REFERENCE_MILLIS,
                window[index].elapsedMillis - window[index - 1].elapsedMillis,
            )
            if (!isPlausibleCounterRate(change, reference)) return null
            val sign = if (change > 0L) 1 else -1
            if (direction == 0) {
                direction = sign
            } else if (sign != direction) {
                // A charge cycle or a gauge reset inside the window: not one movement.
                return null
            }
            netChange += change
        }
        if (direction == 0) return null
        if (!isPlausibleCounterRate(netChange, maxOf(STEP_REFERENCE_MILLIS, spanMillis()))) return null
        return direction > 0
    }

    /** Direction the platform state expects; null when it says nothing or is ambiguous. */
    private fun expectedChargeOf(poll: Poll): Boolean? = when {
        poll.plugged == BatteryTelemetryDecoder.PLUGGED_NONE -> false
        poll.status == BatteryTelemetryDecoder.STATUS_DISCHARGING -> false
        // Heuristic only: a plugged phone can still be discharging, which is why a
        // counter direction is consulted first.
        poll.status == BatteryTelemetryDecoder.STATUS_CHARGING -> true
        else -> null
    }

    /**
     * True when [changeMicroampHours] over [referenceMillis] implies current a phone
     * battery could actually carry.
     */
    private fun isPlausibleCounterRate(changeMicroampHours: Long, referenceMillis: Long): Boolean {
        if (changeMicroampHours == 0L) return true
        if (referenceMillis <= 0L) return false
        val magnitude = if (changeMicroampHours < 0L) -changeMicroampHours else changeMicroampHours
        val impliedAmps = magnitude.toDouble() * MICROAMP_HOURS_TO_AMP_MILLIS / referenceMillis
        return impliedAmps.isFinite() && impliedAmps <= MAX_PLAUSIBLE_AMPS
    }

    /**
     * True when a property is inside the provider's plausibility cap. Compared
     * without negation so no `Long.MIN_VALUE` overflow can slip through (the
     * sentinel is already rejected by the decoder helper).
     */
    private fun isPlausibleMagnitude(currentMicroamps: Long): Boolean =
        currentMicroamps <= BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS &&
            currentMicroamps >= -BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS

    private fun clearInference() {
        inferredUnit = null
        inferredInverted = null
        zeroOnlyEstimate = false
    }

    companion object {
        /** Accepted polls needed for a window; also the sliding-window size. */
        const val WINDOW_POLLS = 5

        /** Timestamp-jitter allowance; the service still requests polls >=1 s apart. */
        const val MIN_STEP_MILLIS = 900L

        /** Four nominal one-second steps, allowing bounded provider-time jitter. */
        const val MIN_WINDOW_SPAN_MILLIS = 4 * MIN_STEP_MILLIS

        /** Longest gap a window may span; a slower poll leaves time unobserved. */
        const val MAX_GAP_MILLIS = 10_000L

        /** `abs(raw)` up to here reads as milliamps (an actual 2–5 A is 2000–5000). */
        const val MILLIAMP_MAX_MAGNITUDE = 10_000L

        /** `abs(raw)` from here up to the provider cap reads as microamps. */
        const val MICROAMP_MIN_MAGNITUDE = 20_000L

        /**
         * 10 A. A conservative rejection bound for counter movement, not a physical
         * law. Faster charging hardware can exceed it; in that case this short
         * counter-direction shortcut is declined, not treated as proof of a reset.
         */
        const val MAX_PLAUSIBLE_AMPS = 10.0

        /** Shortest reference span for a counter step: the slowest selectable cadence. */
        const val STEP_REFERENCE_MILLIS = 5_000L

        /** µAh -> amps over milliseconds: `delta * 3.6 / millis`. */
        const val MICROAMP_HOURS_TO_AMP_MILLIS = 3.6
    }
}
