package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Battery health condition as reported by the platform.
 *
 * This is the *reported condition*, never an estimate of retained capacity or
 * battery wear.
 */
enum class BatteryCondition { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, UNSPECIFIED_FAILURE, COLD, UNKNOWN }

/**
 * Raw platform values for one poll, before any unit conversion.
 *
 * Intentionally Android-free so decoding rules can be unit tested on the JVM.
 * `null` means "the platform did not provide a usable value"; `0` is a real
 * reading and must never be conflated with a missing one.
 */
data class RawBatteryReading(
    val timestampMillis: Long,
    val elapsedRealtimeMillis: Long,
    /** Exactly what `BATTERY_PROPERTY_CURRENT_NOW` returned (documented as microamps). */
    val currentMicroamps: Long? = null,
    /** `BATTERY_PROPERTY_CURRENT_AVERAGE` in microamps; already a platform average. */
    val averageMicroamps: Long? = null,
    /** `BATTERY_PROPERTY_CHARGE_COUNTER` in microamp-hours; a cumulative total. */
    val chargeCounterMicroampHours: Long? = null,
    val voltageMillivolts: Int? = null,
    val level: Int? = null,
    val scale: Int? = null,
    val temperatureTenths: Int? = null,
    /** `0` = on battery, `>0` = something is plugged in. */
    val plugged: Int? = null,
    /** Platform `EXTRA_STATUS` value. */
    val status: Int? = null,
    /** Platform `EXTRA_HEALTH` value. */
    val health: Int? = null,
    /**
     * `EXTRA_CYCLE_COUNT` (API 34+) when the platform tracks cycles, else null.
     * A stale extra can carry the `Int.MIN_VALUE` "unknown" marker, so it is
     * sanitised by [BatteryTelemetryDecoder.supportedCycleCountOf] before landing
     * here; `0` stays a real reading on a new battery.
     */
    val cycleCount: Int? = null,
) {
    val isPlugged: Boolean? get() = plugged?.let { it != BatteryTelemetryDecoder.PLUGGED_NONE }
}

/**
 * Pure unit/sentinel/polarity rules for `BatteryManager` + `ACTION_BATTERY_CHANGED`.
 *
 * No root, sysfs or synthesised fallbacks: a value that the platform does not
 * report stays `null`.
 *
 * The sample's `amps`/`watts`/`currentMicroamps`/`currentSource` describe
 * `BATTERY_PROPERTY_CURRENT_NOW` **only** — the latest hardware reading. The
 * charge-counter rate ([CurrentEstimator]) and `CURRENT_AVERAGE` are averages,
 * so they are carried separately ([BatterySample.derivedAmps],
 * [BatterySample.rawAverageMicroamps]) and are never substituted for the
 * instantaneous reading.
 */
object BatteryTelemetryDecoder {
    /** Some devices report the "property unsupported" marker sign-extended. */
    const val CURRENT_UNSUPPORTED_SENTINEL: Long = Int.MIN_VALUE.toLong()

    const val PLUGGED_NONE = 0
    const val STATUS_UNKNOWN = 1
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    const val HEALTH_UNKNOWN = 1
    const val HEALTH_GOOD = 2
    const val HEALTH_OVERHEAT = 3
    const val HEALTH_DEAD = 4
    const val HEALTH_OVER_VOLTAGE = 5
    const val HEALTH_UNSPECIFIED_FAILURE = 6
    const val HEALTH_COLD = 7

    /** Plausible battery temperature window; anything else is treated as invalid. */
    const val MIN_TEMPERATURE_CELSIUS = -40.0
    const val MAX_TEMPERATURE_CELSIUS = 100.0

    /** Rescale factor applied when the saved calibration proved milliamps. */
    const val MILLIAMP_TO_MICROAMP = 1_000L

    const val MICROAMPS_PER_AMP = 1_000_000.0

    /**
     * 100 A. Beyond any phone battery, so a raw property or a rescaled value above
     * this is a bogus property rather than a reading. Exceeding it drops the
     * instantaneous value instead of clamping it to a plausible-looking number.
     */
    const val MAX_PLAUSIBLE_CURRENT_MICROAMPS = 100_000_000L

    /**
     * True for a value the platform uses to mean "this property is unsupported".
     * Covers both the 32-bit marker and its sign-extended `Long` form, so no
     * conversion, flow rule or sample can resurrect it as a real reading.
     */
    fun isUnsupportedCurrent(currentMicroamps: Long): Boolean =
        currentMicroamps == CURRENT_UNSUPPORTED_SENTINEL || currentMicroamps == Long.MIN_VALUE

    /** The current, unless it is a missing/unsupported sentinel. Zero stays a reading. */
    fun supportedCurrentOf(currentMicroamps: Long?): Long? =
        currentMicroamps?.takeIf { !isUnsupportedCurrent(it) }

    /** Charge counter in microamp-hours, or null when absent/unsupported/negative. */
    fun supportedChargeCounterOf(chargeCounterMicroampHours: Long?): Long? =
        chargeCounterMicroampHours?.takeIf { it >= 0L && it != Long.MIN_VALUE }

    /**
     * `EXTRA_CYCLE_COUNT` value, or null when the platform does not report one.
     *
     * The extra is only present on API 34+, so an absent extra arrives as the
     * `Int.MIN_VALUE` default; a negative count is equally meaningless. Zero is a
     * real reading (a brand-new battery), so it is never conflated with "unknown".
     */
    fun supportedCycleCountOf(cycleCount: Int?): Int? =
        cycleCount?.takeIf { it != Int.MIN_VALUE && it >= 0 }

    /**
     * Unit to interpret `CURRENT_NOW` with, in the documented precedence:
     *
     * 1. the user's manual override ([manual]),
     * 2. the saved automatic calibration ([calibration]),
     * 3. the documented microamp scale — returned as [CurrentUnitOverride.AUTO]
     *    because that is also the "no established interpretation" marker.
     */
    fun resolvedUnit(manual: CurrentUnitOverride, calibration: CurrentCalibration): CurrentUnitOverride =
        when {
            manual != CurrentUnitOverride.AUTO -> manual
            calibration.unit != CurrentUnitOverride.AUTO -> calibration.unit
            else -> CurrentUnitOverride.AUTO
        }

    /**
     * Sign convention to interpret `CURRENT_NOW` with, in the same precedence:
     * the manual polarity override, then the saved calibration, then the
     * documented non-inverted convention.
     *
     * @return `true` when the property's sign is inverted, `false` when it is the
     * documented convention, `null` when nothing establishes either — a `null`
     * reading is interpreted non-inverted but is *not* claimed as calibrated.
     */
    fun resolvedInversion(
        manual: CurrentPolarityOverride,
        calibration: CurrentCalibration,
    ): Boolean? = when (manual) {
        CurrentPolarityOverride.INVERTED -> true
        CurrentPolarityOverride.STANDARD -> false
        CurrentPolarityOverride.AUTO -> calibration.inverted
    }

    /**
     * One interpreted instantaneous reading.
     *
     * [unitAdjusted] and [polarityAdjusted] are truthful provenance flags: they
     * say a correction was actually applied to a non-zero reading, not merely
     * that one was available.
     */
    data class InstantCurrent(
        val microamps: Long,
        val unitAdjusted: Boolean,
        val polarityAdjusted: Boolean,
    )

    /**
     * Applies the established unit/polarity interpretation to the raw property.
     *
     * Nothing is inferred here: a unit that nothing established is read as the
     * documented microamps, and a polarity that nothing established is read as the
     * documented sign. Scale is only ever established by [CurrentCalibrator] or by
     * the user's manual override, never from a single instantaneous-versus-average
     * ratio.
     *
     * @return null when the property is absent, an unsupported sentinel, or the
     * corrected value would be implausibly large for a phone battery. An unusable
     * reading is dropped, never clamped, and zero is dropped no further than zero.
     */
    fun interpretInstant(
        rawCurrentMicroamps: Long?,
        currentUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        currentPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
        calibration: CurrentCalibration = CurrentCalibration(),
    ): InstantCurrent? = interpretInstant(
        rawCurrentMicroamps = rawCurrentMicroamps,
        unit = resolvedUnit(currentUnit, calibration),
        inverted = resolvedInversion(currentPolarity, calibration),
    )

    private fun interpretInstant(rawCurrentMicroamps: Long?, unit: CurrentUnitOverride, inverted: Boolean?): InstantCurrent? {
        val raw = supportedCurrentOf(rawCurrentMicroamps) ?: return null
        val rescale = unit == CurrentUnitOverride.MILLIAMPS && raw != 0L
        if (rescale && abs(raw) > MAX_PLAUSIBLE_CURRENT_MICROAMPS / MILLIAMP_TO_MICROAMP) return null
        val rescaled = if (rescale) raw * MILLIAMP_TO_MICROAMP else raw
        if (abs(rescaled) > MAX_PLAUSIBLE_CURRENT_MICROAMPS) return null
        // Zero has no sign, so flipping it is not an adjustment worth claiming.
        val flip = inverted == true && rescaled != 0L
        return InstantCurrent(
            microamps = if (flip) -rescaled else rescaled,
            unitAdjusted = rescale,
            polarityAdjusted = flip,
        )
    }

    /** Amps -> signed microamps, rounded; the inverse of [amperesOf]. */
    fun microampsOf(amperes: Double?): Long? {
        if (amperes == null || !amperes.isFinite()) return null
        return (amperes * MICROAMPS_PER_AMP).roundToLong()
    }

    /** Microamps -> amps. */
    fun amperesOf(currentMicroamps: Long?): Double? {
        val microamps = supportedCurrentOf(currentMicroamps) ?: return null
        return microamps / 1_000_000.0
    }

    /** Millivolts -> volts; nonpositive voltages are unusable for watt maths. */
    fun voltsOf(voltageMillivolts: Int?): Double? {
        val millivolts = voltageMillivolts ?: return null
        if (millivolts <= 0) return null
        return millivolts / 1000.0
    }

    /** W = A * V, only when both operands are usable and finite. */
    fun wattsOf(amperes: Double?, volts: Double?): Double? {
        if (amperes == null || volts == null) return null
        if (!amperes.isFinite() || !volts.isFinite()) return null
        val watts = amperes * volts
        return if (watts.isFinite()) watts else null
    }

    fun percentageOf(level: Int?, scale: Int?): Int? {
        if (level == null || scale == null || scale <= 0) return null
        if (level < 0 || level > scale) return null
        return Math.round(level * 100.0 / scale).toInt().coerceIn(0, 100)
    }

    fun celsiusOf(temperatureTenths: Int?): Double? {
        val tenths = temperatureTenths ?: return null
        if (tenths == Int.MIN_VALUE) return null
        val celsius = tenths / 10.0
        if (!celsius.isFinite()) return null
        if (celsius < MIN_TEMPERATURE_CELSIUS || celsius > MAX_TEMPERATURE_CELSIUS) return null
        return celsius
    }

    fun conditionOf(health: Int?): BatteryCondition? = when (health) {
        null -> null
        HEALTH_GOOD -> BatteryCondition.GOOD
        HEALTH_OVERHEAT -> BatteryCondition.OVERHEAT
        HEALTH_DEAD -> BatteryCondition.DEAD
        HEALTH_OVER_VOLTAGE -> BatteryCondition.OVER_VOLTAGE
        HEALTH_UNSPECIFIED_FAILURE -> BatteryCondition.UNSPECIFIED_FAILURE
        HEALTH_COLD -> BatteryCondition.COLD
        HEALTH_UNKNOWN -> BatteryCondition.UNKNOWN
        else -> BatteryCondition.UNKNOWN
    }

    /**
     * Flow follows the trusted net current. Only when the current is missing may
     * the reported status supply the *word*; it never manufactures watts.
     */
    fun flowOf(currentMicroamps: Long?, status: Int?): FlowState {
        val microamps = supportedCurrentOf(currentMicroamps)
        if (microamps != null) {
            return when {
                microamps > 0 -> FlowState.CHARGING
                microamps < 0 -> FlowState.DISCHARGING
                else -> FlowState.IDLE
            }
        }
        return when (status) {
            STATUS_CHARGING -> FlowState.CHARGING
            STATUS_DISCHARGING -> FlowState.DISCHARGING
            STATUS_NOT_CHARGING, STATUS_FULL -> FlowState.IDLE
            else -> FlowState.UNKNOWN
        }
    }

    /**
     * True when a positive current is reported while the device is on battery or
     * discharging. That is not a polarity we can trust, so watts are suppressed
     * with an explanatory reason instead of guessing an inversion.
     *
     * Called with the *corrected* current (after any established or manual
     * inversion), so a device whose sign is known to be reversed stops tripping
     * this rule once its reading has been corrected.
     */
    fun hasInconsistentPolarity(currentMicroamps: Long?, plugged: Int?, status: Int?): Boolean {
        val microamps = supportedCurrentOf(currentMicroamps) ?: return false
        if (microamps <= 0) return false
        val unplugged = (plugged != null && plugged == PLUGGED_NONE) || status == STATUS_DISCHARGING
        return unplugged
    }

    /**
     * Converts one raw poll into a sample whose current/energy fields come from
     * `CURRENT_NOW` alone.
     *
     * 1. `currentMicroamps`/`amps`/`watts`/`currentSource` describe the latest
     *    instantaneous reading ([CurrentSource.INSTANT]), or nothing at all
     *    ([CurrentSource.NONE]) when the property is missing. They are never
     *    derived from the counter or `CURRENT_AVERAGE`: a missing instant stays
     *    missing even when a counter rate exists.
     * 2. The interpretation of that reading follows [resolvedUnit] and
     *    [resolvedInversion]: manual settings first, saved [calibration] second,
     *    documented microamps/non-inverted last.
     * 3. [derivedAmps]/[derivedWindowMillis] are the counter-derived **auxiliary
     *    average** (see
     *    [io.github.anevilburrito.wpm.model.BatterySample.averageAmps]), kept
     *    beside the instant fields rather than replacing them, and
     *    [RawBatteryReading.averageMicroamps] is kept verbatim as
     *    [BatterySample.rawAverageMicroamps] for export. Neither feeds amps/watts: the
     *    live readout uses the calibrated instantaneous reading
     *    ([io.github.anevilburrito.wpm.model.liveAmps]).
     * 4. [BatterySample.currentCalibrated] is true only when both the unit and the
     *    polarity of this reading have manual or previously established evidence.
     *
     * Missing properties stay independently nullable; zero current is a retained
     * reading, not a missing one. Nothing is invented, clamped or zero-filled.
     */
    fun decode(
        raw: RawBatteryReading,
        derivedAmps: Double? = null,
        derivedWindowMillis: Long? = null,
        currentUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        currentPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
        calibration: CurrentCalibration = CurrentCalibration(),
        /** Interpretation supplied by the startup heuristic, never durable evidence. */
        provisional: Boolean = false,
        startupPollCount: Int = 0,
        startupCheckComplete: Boolean = false,
    ): BatterySample {
        val rawCurrent = supportedCurrentOf(raw.currentMicroamps)
        val rawAverage = supportedCurrentOf(raw.averageMicroamps)
        val derived = derivedAmps?.takeIf { it.isFinite() }
        val windowMillis = if (derived == null) null else derivedWindowMillis?.takeIf { it > 0L }

        val instant = interpretInstant(rawCurrent, currentUnit, currentPolarity, calibration)
        val instantMicroamps = instant?.microamps
        val unitEvidence = currentUnit != CurrentUnitOverride.AUTO ||
            calibration.unit != CurrentUnitOverride.AUTO
        val polarityEvidence = currentPolarity != CurrentPolarityOverride.AUTO ||
            calibration.inverted != null
        val estimated = provisional && unitEvidence && polarityEvidence &&
            (currentUnit == CurrentUnitOverride.AUTO || currentPolarity == CurrentPolarityOverride.AUTO)

        val voltageMillivolts = raw.voltageMillivolts?.takeIf { it > 0 }
        val volts = voltsOf(voltageMillivolts)
        val amperes = amperesOf(instantMicroamps)
        // Polarity is judged on the corrected instantaneous value.
        val inconsistent = hasInconsistentPolarity(instantMicroamps, raw.plugged, raw.status)
        val watts = if (inconsistent) null else wattsOf(amperes, volts)
        return BatterySample(
            timestampMillis = raw.timestampMillis,
            elapsedRealtimeMillis = raw.elapsedRealtimeMillis,
            watts = watts,
            volts = volts,
            amps = amperes,
            percentage = percentageOf(raw.level, raw.scale),
            temperatureC = celsiusOf(raw.temperatureTenths),
            condition = conditionOf(raw.health)?.name,
            plugged = raw.isPlugged,
            // An untrusted polarity must not be labelled confidently as charging.
            flow = if (inconsistent) FlowState.UNKNOWN else flowOf(instantMicroamps, raw.status),
            currentMicroamps = instantMicroamps,
            voltageMillivolts = voltageMillivolts,
            // What the property itself returned, before any interpretation.
            rawCurrentMicroamps = rawCurrent,
            rawAverageMicroamps = rawAverage,
            currentCalibrated = instant != null && unitEvidence && polarityEvidence && !estimated,
            currentPolarityAdjusted = instant?.polarityAdjusted == true,
            chargeCounterMicroampHours = supportedChargeCounterOf(raw.chargeCounterMicroampHours),
            cycleCount = supportedCycleCountOf(raw.cycleCount),
            derivedAmps = derived,
            derivedWindowMillis = windowMillis,
            currentUnitAdjusted = instant?.unitAdjusted == true,
            currentSource = when {
                instant == null -> CurrentSource.NONE
                estimated -> CurrentSource.INSTANT_ESTIMATED
                else -> CurrentSource.INSTANT
            },
            gapBefore = false,
            reason = if (inconsistent) SampleReasons.INCONSISTENT_POLARITY else null,
            startupPollCount = startupPollCount,
            startupCheckComplete = startupCheckComplete,
        )
    }
}
