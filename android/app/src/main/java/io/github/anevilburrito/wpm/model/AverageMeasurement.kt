/*
 * Auxiliary counter-average measurement contract.
 *
 * The primary user-facing metric is the calibrated instantaneous reading (see
 * `LiveMeasurement.kt`). This file keeps the trailing charge-counter average as an
 * explicitly **auxiliary** metric: it is used by the remaining-runtime estimate, the
 * developer comparison and the CSV's appended comparison columns, never as a
 * fallback for the live readout.
 *
 * ## What the stored sample means
 *
 * [BatterySample.amps]/[BatterySample.watts]/[BatterySample.currentSource] keep
 * their original meaning on the **stored** sample: the latest
 * `BATTERY_PROPERTY_CURRENT_NOW` reading and how it was interpreted. They are
 * *instant* provenance and are never relabelled as an average.
 *
 * The charge-counter rate is carried beside it in [BatterySample.derivedAmps] and
 * [BatterySample.derivedWindowMillis]. That rate is a *trailing average over a
 * measured window* ([io.github.anevilburrito.wpm.monitoring.CurrentEstimator]), so
 * it is the right number for an auxiliary average/runtime comparison — not for the
 * live readout, which uses the calibrated instant helpers.
 *
 * ## Explicit limitation: estimated average power
 *
 * [averageWatts] is `averageAmps * volts` — the counter-derived average current
 * multiplied by the **latest** voltage reading for that poll. It is therefore an
 * *estimate* of average power, not an exact window-integrated V·I: the true average
 * power is the time integral of V(t)·I(t) over the window, and the pack voltage
 * varies across it (charge tapering, load steps, temperature). At a roughly
 * constant voltage the two agree closely; across a large voltage swing this
 * estimate is biased toward the newer voltage. Any surface that shows this number
 * must not claim independent laboratory accuracy.
 *
 * Missing inputs stay missing: with no counter-derived rate, or no usable positive
 * voltage, the average is `null` — never `0`, and never a substituted instant
 * reading. In particular a missing `CURRENT_NOW` does **not** make the average
 * unavailable, because the counter path does not depend on it (or on its unit
 * calibration).
 */

package io.github.anevilburrito.wpm.model

/**
 * Counter-derived trailing average current, or `null` when no usable average exists.
 *
 * Valid only when [BatterySample.derivedAmps] is finite **and**
 * [BatterySample.derivedWindowMillis] is a positive measured window: a rate without
 * a window has no averaging basis and is treated as missing rather than trusted.
 * The sign follows the counter, so discharge is negative.
 */
val BatterySample.averageAmps: Double?
    get() {
        val amps = derivedAmps?.takeIf { it.isFinite() } ?: return null
        derivedWindowMillis?.takeIf { it > 0L } ?: return null
        return amps
    }

/**
 * Estimated average power in watts, or `null` when unavailable.
 *
 * `averageAmps * volts`, using this sample's voltage. See the file documentation:
 * this is an estimate from counter-derived average current times the latest
 * voltage, not an exact window-integrated V·I. A non-positive or non-finite
 * voltage yields `null` (the same rule as the instant path), never a fabricated 0.
 */
val BatterySample.averageWatts: Double?
    get() {
        val amps = averageAmps ?: return null
        val volts = volts?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val watts = amps * volts
        return watts.takeIf { it.isFinite() }
    }

/**
 * A display copy whose power/current fields are the auxiliary average metric.
 *
 * Not the live readout: use [io.github.anevilburrito.wpm.model.forLiveDisplay] for
 * the primary calibrated instantaneous metric. This helper remains for diagnostics,
 * legacy presentation and tests.
 *
 * [amps] and [watts] are replaced by [averageAmps]/[averageWatts] and [flow] is
 * derived from the average's sign, so chart, readout and window statistics all
 * describe the same rolling average. Everything else is copied verbatim:
 *
 * - the *stored* sample keeps the instant values, so raw diagnostics and the CSV
 *   export still show `CURRENT_NOW` provenance;
 * - [BatterySample.currentMicroamps], [BatterySample.currentSource], the raw
 *   platform fields and the calibration flags are therefore untouched on the copy.
 *   They continue to describe the instant reading, not [amps]/[watts];
 * - a missing average leaves the copied [amps]/[watts] `null` and [flow]
 *   [FlowState.UNKNOWN]: nothing is invented from a short or flat counter, and the
 *   instant reading is never silently substituted.
 *
 * Pure and Android-free so the conversion is unit tested on the JVM.
 */
fun BatterySample.forAverageDisplay(): BatterySample {
    val amps = averageAmps
    val watts = averageWatts
    return copy(
        amps = amps,
        watts = watts,
        flow = flowOf(amps),
    )
}

/** Signed flow from a (possibly missing) average current; zero is idle. */
private fun flowOf(amps: Double?): FlowState = when {
    amps == null -> FlowState.UNKNOWN
    amps > 0.0 -> FlowState.CHARGING
    amps < 0.0 -> FlowState.DISCHARGING
    else -> FlowState.IDLE
}

/**
 * What an auxiliary average-aware surface may report.
 *
 * **Not** the live notification/readout state: those use
 * [io.github.anevilburrito.wpm.model.LiveReading] from the calibrated instantaneous
 * metric. This enum remains for the auxiliary average/runtime comparison so its
 * existing behaviour and tests stay intact.
 *
 * Deliberately an enum rather than formatted text: the choice must be testable
 * without Android, and the caller supplies the wording.
 */
enum class AverageReading {
    /** No reading at all yet. */
    WAITING,

    /** A usable average exists: show it, and only it. */
    AVERAGE,

    /** The platform exposes a counter but no usable average has been measured yet. */
    CALIBRATING,

    /** No counter at all, so an average can never arrive. */
    UNAVAILABLE,
}

/**
 * Chooses between an auxiliary average, Calibrating and Unavailable for the last
 * sample. Diagnostic/legacy only; the notification and primary readout use
 * [io.github.anevilburrito.wpm.model.liveReading].
 *
 * The rule is unchanged: an instantaneous reading is never reported here, no matter
 * how confident it looks — only the counter-derived average, or an honest
 * wait/unavailable state.
 *
 * `CALIBRATING` requires the platform to still expose a charge counter
 * ([BatterySample.chargeCounterMicroampHours]). Without one the average cannot
 * arrive, so claiming to calibrate forever would be a lie. With one, the wait is
 * exactly the initial acquisition, which needs a measured counter window; the
 * average is *not* gated on the unrelated instantaneous unit/polarity calibration.
 */
fun BatterySample?.averageReading(): AverageReading {
    if (this == null) return AverageReading.WAITING
    return when {
        // Current is the measured quantity (the counter delta); power is derived from
        // it, so a missing voltage costs the power figure, not the whole average.
        averageAmps != null -> AverageReading.AVERAGE
        chargeCounterMicroampHours != null -> AverageReading.CALIBRATING
        else -> AverageReading.UNAVAILABLE
    }
}
