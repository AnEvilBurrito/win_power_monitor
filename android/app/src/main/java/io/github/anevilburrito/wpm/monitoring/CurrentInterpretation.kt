package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings

/**
 * The effective scale and sign actually used to interpret `CURRENT_NOW`.
 *
 * This is the **resolved** interpretation, not its provenance: the user's manual
 * override first, the saved automatic calibration second, and the documented
 * microamp / non-inverted convention last (see
 * [BatteryTelemetryDecoder.resolvedUnit] and
 * [BatteryTelemetryDecoder.resolvedInversion]).
 *
 * It is deliberately *not* derived from a sample's `currentUnitAdjusted` /
 * `currentPolarityAdjusted` flags. Those flags report that a correction was
 * applied to a non-zero value, so both are `false` for a zero reading; they answer
 * "was this reading adjusted?", not "would the same raw value still mean the same
 * watts?". Two successive readings whose raw value would be interpreted
 * differently are not comparable even when both happen to be zero.
 *
 * Scale and sign are separate fields so equality is exactly "the same raw value
 * means the same energy". Provenance is intentionally absent: an established
 * microamp/non-inverted calibration and the documented fallback are the same
 * interpretation, so establishing that calibration is not a discontinuity.
 */
data class CurrentInterpretation(
    val unit: CurrentUnitOverride,
    val inverted: Boolean,
) {
    companion object {
        /**
         * The documented fallback: microamps, non-inverted. Never `null`, so
         * "nothing established yet" still has a concrete, comparable value.
         */
        val DEFAULT = CurrentInterpretation(
            unit = CurrentUnitOverride.MICROAMPS,
            inverted = false,
        )

        /**
         * Resolves the interpretation for [settings] and the calibration actually
         * used for the reading.
         *
         * Only the manual unit/polarity corrections and the calibration matter:
         * theme, absolute scale and design capacity cannot change how a raw
         * current is read, so display-only edits never look like a transition.
         */
        fun of(
            settings: MonitorSettings,
            calibration: CurrentCalibration = CurrentCalibration(),
        ): CurrentInterpretation {
            val unit = when (
                BatteryTelemetryDecoder.resolvedUnit(settings.currentUnit, calibration)
            ) {
                CurrentUnitOverride.MILLIAMPS -> CurrentUnitOverride.MILLIAMPS
                // AUTO is the decoder's marker for "documented microamps", so an
                // unestablished scale and an explicit microamp correction read the
                // same raw value identically and must not be treated as a change.
                else -> CurrentUnitOverride.MICROAMPS
            }
            val inverted = BatteryTelemetryDecoder.resolvedInversion(
                settings.currentPolarity,
                calibration,
            ) ?: false
            return CurrentInterpretation(unit = unit, inverted = inverted)
        }
    }
}

/**
 * Detects the first sample of one recording that is interpreted differently from
 * the sample before it.
 *
 * Once scale or sign changes, a raw current (and therefore the watts of every
 * following sample) means something different, so a session-wide integral would
 * add incomparable numbers. The caller marks that first changed sample as a
 * discontinuity; the earlier samples and the running totals are left exactly as
 * recorded, because they were correct under the interpretation in force then.
 *
 * The first observed interpretation is a baseline, never a transition: a new
 * recording has no earlier sample to be incomparable with. [reset] restores that
 * baseline, so callers invoke it when a recording begins or ends.
 *
 * Pure (no Android imports) so the equality rule is unit tested on the JVM. The
 * sampler observes on the IO dispatcher while lifecycle resets happen on main, so
 * the single baseline is guarded by the instance monitor.
 */
class CurrentInterpretationTracker {
    private var previous: CurrentInterpretation? = null

    /** The most recently observed interpretation, or null before the first one. */
    @get:Synchronized
    val last: CurrentInterpretation? get() = previous

    /**
     * Records [current] and returns true only when it differs from the previously
     * observed interpretation. The first observation after construction/[reset]
     * always returns false.
     */
    @Synchronized
    fun observe(current: CurrentInterpretation): Boolean {
        val earlier = previous
        previous = current
        return earlier != null && earlier != current
    }

    /** Forgets the baseline; the next [observe] starts a new recording. */
    @Synchronized
    fun reset() {
        previous = null
    }
}
