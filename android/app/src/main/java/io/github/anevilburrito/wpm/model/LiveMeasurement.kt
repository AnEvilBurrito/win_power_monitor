package io.github.anevilburrito.wpm.model

/**
 * Primary metric: normalized CURRENT_NOW, never a counter/platform average.
 *
 * Confirmed/manual readings use INSTANT + currentCalibrated. Five-poll heuristic
 * readings use INSTANT_ESTIMATED and remain explicitly unconfirmed. The source
 * travels through SQLite/CSV so restored history cannot turn an estimate into
 * calibration evidence. Missing/non-finite readings stay missing; true zero stays.
 */
val BatterySample.liveAmps: Double?
    get() {
        if (currentSource != CurrentSource.INSTANT_ESTIMATED) {
            if (!currentCalibrated || currentSource != CurrentSource.INSTANT) return null
        }
        return amps?.takeIf { it.isFinite() }
    }

/**
 * Use the finite stored decoder power only when live current is usable. Never
 * recompute it: that would resurrect suppressed voltage/polarity failures.
 */
val BatterySample.liveWatts: Double?
    get() {
        if (liveAmps == null) return null
        return watts?.takeIf { it.isFinite() }
    }

/** Keep raw properties, confidence/source, timestamps and gaps on the display copy. */
fun BatterySample.forLiveDisplay(): BatterySample {
    val amps = liveAmps
    val watts = liveWatts
    val liveFlow = when {
        amps == null -> FlowState.UNKNOWN
        amps > 0.0 -> FlowState.CHARGING
        amps < 0.0 -> FlowState.DISCHARGING
        else -> FlowState.IDLE
    }
    if (amps == this.amps && watts == this.watts && liveFlow == flow) return this
    return copy(amps = amps, watts = watts, flow = liveFlow)
}

/** Shared by dashboard/diagnostics and notification; confidence is not a number. */
enum class LiveReading {
    WAITING,
    CALIBRATING,
    READY,
    ESTIMATED,
    UNAVAILABLE,
}

/**
 * The brief startup check works without a charge counter. After five polls an
 * ambiguous inference is unavailable/actionable, not an endless Calibrating.
 * Counter validation may still establish an interpretation in the background.
 * Samples from earlier builds retain their existing readiness interpretation.
 */
fun BatterySample?.liveReading(): LiveReading {
    if (this == null) return LiveReading.WAITING
    if (liveAmps != null) return if (currentSource == CurrentSource.INSTANT_ESTIMATED) {
        LiveReading.ESTIMATED
    } else {
        LiveReading.READY
    }
    val instantSourceUsable = currentSource == CurrentSource.INSTANT && amps?.isFinite() == true
    return if (!currentCalibrated && instantSourceUsable && !startupCheckComplete &&
        (startupPollCount > 0 || chargeCounterMicroampHours != null)
    ) {
        LiveReading.CALIBRATING
    } else {
        LiveReading.UNAVAILABLE
    }
}
