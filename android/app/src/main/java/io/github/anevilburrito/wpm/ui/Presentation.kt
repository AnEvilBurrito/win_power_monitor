package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CapacityEstimate
import io.github.anevilburrito.wpm.model.CapacityMath
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitorUiState
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.SessionSummary
import io.github.anevilburrito.wpm.model.LiveReading
import io.github.anevilburrito.wpm.model.UnitQuantity
import io.github.anevilburrito.wpm.model.averageAmps
import io.github.anevilburrito.wpm.model.forLiveDisplay
import io.github.anevilburrito.wpm.model.liveReading
import io.github.anevilburrito.wpm.monitoring.SampleReasons
import kotlin.math.roundToInt

/**
 * The four stable states of the latest-reading readout.
 *
 * Every state keeps the same slots (number-or-dash, unit, percentage, direction,
 * readiness line), so moving between them cannot reflow the header:
 *
 *  - [LIVE]: a usable instantaneous current/power exists and is shown. This covers
 *    both a confirmed calibration and the tentative five-poll interpretation
 *    ([CurrentSource.INSTANT_ESTIMATED]), which is a live *numeric* state whose only
 *    difference is the word *Estimated* on the readiness line.
 *  - [CALIBRATING]: recording with a fresh reading, but the current unit/sign is
 *    not established yet and the quick five-poll check is still filling. Nothing
 *    numeric is shown as if it were measured, and the readiness line names the
 *    reading count that is arriving. This is never a wait for the unrelated
 *    counter average, and it cannot last beyond the completed five-poll check.
 *  - [UNAVAILABLE]: no usable instantaneous reading can be formed (unsupported
 *    source or impossible calibration), or the latest reading is stale.
 *  - [STOPPED]: nothing is being recorded; saved history stays readable.
 */
enum class ReadoutState { LIVE, CALIBRATING, UNAVAILABLE, STOPPED }

/**
 * Everything the monitor header needs, already transformed for the chosen scale.
 *
 * The headline is the **latest calibrated instantaneous** power/current
 * ([BatterySample.liveWatts] / [BatterySample.liveAmps], i.e. normalised
 * `CURRENT_NOW`), not the 60 s charge-counter average and not a session mean. The
 * counter average stays on accessors the runtime/diagnostics use separately, and
 * the raw provenance stays on the raw sample for the developer section and CSV.
 *
 * [power] carries the magnitude-aware unit so the screen can draw `12.6` large
 * and `mW` small; without a usable calibrated reading the number stays the em dash
 * and the unit slot stays empty but reserved.
 *
 * There is deliberately no per-sample message field: a note built from the latest
 * reading was reported to appear and vanish on updates. [direction] and
 * [readiness] are the only state words, both single-line fixed slots.
 */
data class ReadingView(
    val state: ReadoutState,
    val lowBattery: Boolean,
    val power: UnitQuantity,
    val percentage: String,
    val direction: String,
    val flow: FlowState,
    /** Auto-scaled `4.02 V / 315 µA`, or `— V / —` when nothing is available. */
    val electrical: String,
    /** One always-present line that explains the state; never blank. */
    val readiness: String,
    /**
     * True when the number comes from the tentative five-poll interpretation
     * ([CurrentSource.INSTANT_ESTIMATED]) rather than a confirmed calibration. The
     * reading is still a live numeric state; only the wording says Estimated.
     */
    val estimated: Boolean = false,
) {
    val live: Boolean get() = state == ReadoutState.LIVE
    val watts: String get() = power.text
    val powerUnit: String get() = power.unit
    val powerAvailable: Boolean get() = live && power.available
    val statusLabel: String get() = when (state) {
        ReadoutState.LIVE -> if (estimated) ESTIMATED_TEXT else "Ready"
        ReadoutState.CALIBRATING -> CALIBRATING_TEXT
        ReadoutState.UNAVAILABLE -> UNAVAILABLE_TEXT
        ReadoutState.STOPPED -> "Stopped"
    }

    /**
     * The big slot: the latest reading, or the word that says why there is none.
     * Rendering a word instead of a number is what keeps a calibrating header
     * honest without inventing a zero.
     */
    val headline: String get() = when (state) {
        ReadoutState.LIVE -> power.text
        ReadoutState.CALIBRATING -> CALIBRATING_TEXT
        ReadoutState.UNAVAILABLE, ReadoutState.STOPPED -> WpmFormat.DASH
    }

    /** True when [headline] is a word and must be drawn smaller than a number. */
    val headlineIsWord: Boolean get() = state == ReadoutState.CALIBRATING

    /**
     * Constant label so the big number always says which measurement it is,
     * whether or not the current poll produced a usable reading.
     */
    val caption: String get() = LIVE_POWER_CAPTION
}

/** The primary readout is the latest calibrated CURRENT_NOW reading. */
const val LIVE_POWER_CAPTION = "Power & current \u00b7 latest reading"

/**
 * Latest-reading view for the monitor header.
 *
 * [sample] must be the **raw** latest sample: the live display copy is derived here
 * exactly once ([BatterySample.forLiveDisplay]) so a caller that already holds
 * live-display samples cannot silently double-convert. Raw provenance stays on the
 * raw sample for the developer diagnostics and the CSV export.
 *
 * The shared [liveReading] helper decides ready / calibrating / unavailable, so the
 * header, the diagnostics and later surfaces cannot disagree. A saved or manual
 * calibration is enough: the reading is shown on the first confirmed poll and never
 * waits for the unrelated 60 s counter average.
 *
 * A stopped service or a stale latest poll shows dashes instead of an old reading.
 * A missing voltage or power never hides an independently valid current or
 * percentage, and an impossible calibration says so instead of calibrating forever.
 */
fun liveReadingView(
    sample: BatterySample?,
    running: Boolean,
    stale: Boolean,
    absolute: Boolean,
): ReadingView {
    val calibration = sample.liveReading()
    val live = sample?.forLiveDisplay()
    // The five-poll interpretation already carries a numeric live value, so it is
    // the same live state: nothing about the slots, geometry or availability
    // changes, only the readiness wording.
    val estimated = calibration == LiveReading.ESTIMATED
    val state = when {
        !running -> ReadoutState.STOPPED
        // The first frame has no sample yet: that is expected startup, not failure.
        sample == null -> ReadoutState.CALIBRATING
        stale -> ReadoutState.UNAVAILABLE
        calibration == LiveReading.READY || calibration == LiveReading.ESTIMATED -> ReadoutState.LIVE
        calibration == LiveReading.CALIBRATING -> ReadoutState.CALIBRATING
        else -> ReadoutState.UNAVAILABLE
    }
    val usable = state == ReadoutState.LIVE
    val fresh = running && !stale && sample != null
    val flow = if (usable) live?.flow ?: FlowState.UNKNOWN else FlowState.UNKNOWN
    val direction = when (state) {
        ReadoutState.STOPPED -> "Not recording"
        // The big slot already says Calibrating; the line below says which flow is
        // unknown, so the two labels never repeat the same word.
        ReadoutState.CALIBRATING -> "Flow unknown"
        ReadoutState.UNAVAILABLE -> UNAVAILABLE_TEXT
        ReadoutState.LIVE -> when (flow) {
            FlowState.CHARGING -> "\u2191 Charging"
            FlowState.DISCHARGING -> "\u2193 Discharging"
            FlowState.IDLE -> "Idle"
            else -> "Flow unknown"
        }
    }
    val readiness = when {
        state == ReadoutState.STOPPED -> "Start recording to measure"
        sample == null -> "Waiting for the first reading"
        state == ReadoutState.LIVE ->
            if (estimated) estimatedReadiness(sample) else "From the latest calibrated reading"
        state == ReadoutState.CALIBRATING -> startupProgressText(sample)
        stale -> "No recent sample from the recorder"
        ambiguousStartup(sample) -> AMBIGUOUS_STARTUP_TEXT
        else -> "No usable instantaneous current reading"
    }
    val percentageValue = sample?.percentage?.takeIf { it in 0..100 }
    // Power may be unavailable while current is valid (a suppressed or missing
    // voltage); each numeric slot therefore decides its own availability.
    val power = if (usable) WpmFormat.power(live?.watts, absolute) else WpmFormat.power(null, absolute)
    val current = if (usable) WpmFormat.current(live?.amps, absolute) else WpmFormat.current(null, absolute)
    val percentage = if (fresh) WpmFormat.percent(percentageValue) else WpmFormat.DASH
    val volts = if (fresh) WpmFormat.voltage(sample?.volts) else WpmFormat.DASH
    return ReadingView(
        state = state,
        lowBattery = fresh && percentageValue != null && percentageValue <= LOW_BATTERY_PERCENT,
        power = power,
        percentage = percentage,
        direction = direction,
        flow = flow,
        electrical = "$volts V ${WpmFormat.SEPARATOR} ${WpmFormat.withUnit(current)}",
        readiness = readiness,
        estimated = estimated,
    )
}

/**
 * Five accepted polls make the tentative interpretation, mirroring
 * `FastCurrentInterpreter.WINDOW_POLLS`.
 *
 * Kept as a UI constant instead of importing the interpreter so the presentation
 * layer stays free of the recording machinery; `FastStartupUiTest` asserts the two
 * numbers are the same, so the count can never drift from the backend window.
 */
const val STARTUP_CHECK_POLLS = 5

/** The word that marks a tentative-but-numeric live reading. */
const val ESTIMATED_TEXT = "Estimated"

/**
 * Readiness while the tentative interpretation is driving the live number.
 *
 * "Checking in background" is only claimed when the device exposes a charge
 * counter, because that is what the durable confirmation compares against. A
 * device without one can never confirm the scale from the counter, so it says so
 * instead of promising a validation that cannot arrive.
 */
private const val ESTIMATED_CHECKING = "Estimated \u00b7 checking in background"
private const val ESTIMATED_NO_COUNTER = "Estimated \u00b7 counter validation unavailable"

/**
 * Readiness when the five-poll check finished without a usable interpretation.
 *
 * The cheap heuristic ran out of evidence, so the honest state is unavailable
 * with a concrete next step, not an endless Calibrating and not an invented value.
 */
const val AMBIGUOUS_STARTUP_TEXT = "Scale and sign unclear \u00b7 use manual correction"

/** Fallback when the startup count is not yet meaningful (for example 0). */
private const val CONFIRMING_TEXT = "Confirming current scale and sign"

private fun estimatedReadiness(sample: BatterySample?): String =
    if (sample?.chargeCounterMicroampHours == null) ESTIMATED_NO_COUNTER else ESTIMATED_CHECKING

/**
 * Startup line that names the arriving reading count: `Checking reading 3/5`.
 *
 * Only the words inside the reserved single readiness line change; there is no
 * progress row, no animation and no blinking, so the fixed geometry is untouched.
 * The existing Calibrating headline is kept for these first polls.
 */
internal fun startupProgressText(sample: BatterySample?): String {
    val poll = sample?.startupPollCount ?: 0
    return if (poll in 1..STARTUP_CHECK_POLLS) "Checking reading $poll/$STARTUP_CHECK_POLLS" else CONFIRMING_TEXT
}

/**
 * True when the five-poll check is over, an instantaneous property exists, and no
 * interpretation was established: the actionable "unclear" case.
 */
private fun ambiguousStartup(sample: BatterySample?): Boolean =
    sample != null &&
        sample.startupCheckComplete &&
        !sample.currentCalibrated &&
        sample.currentSource == CurrentSource.INSTANT &&
        sample.amps?.isFinite() == true

/**
 * Ordinary charging/discharging is normal text colour, never the warning colour.
 * Kept as a pure decision so the "no orange for normal flow" rule is testable.
 */
enum class FlowTone { NORMAL, MUTED }

fun flowTone(reading: ReadingView): FlowTone =
    if (reading.live) FlowTone.NORMAL else FlowTone.MUTED

/**
 * Recording-level error to show on the dashboard, or null.
 *
 * Older hosts reported a **notification-permission advisory** through the same
 * `MonitorUiState.error` channel as real failures, which is how an orange
 * "Notifications are disabled" notice ended up on the dashboard screenshot. That
 * advisory is not a recording failure: it is suppressed here (the recording is
 * running and Stop is on screen). Real failures — service start failures, export
 * failures, data loss — are returned unchanged and stay actionable.
 */
fun dashboardError(error: String?): String? {
    if (error.isNullOrBlank()) return null
    val normalized = error.trim().lowercase()
    // Match only the old advisories, not a real service failure whose explanation
    // happens to mention notification permission.
    return if (normalized in NOTIFICATION_ADVISORIES) null else error
}

/** Ordinary advisories that must never occupy the recording-error slot. */
private val NOTIFICATION_ADVISORIES = setOf(
    "notifications are disabled. recording can continue; use the app to stop it.",
    "notification permission denied",
)

/** The word shown in the big slot until the current unit/sign is established. */
const val CALIBRATING_TEXT = "Calibrating"

/** The word shown when no usable instantaneous reading can be formed. */
const val UNAVAILABLE_TEXT = "Current unavailable"

/** Window/session statistics with ordered extrema for the active scale. */
data class StatsView(
    val average: UnitQuantity,
    val minimum: UnitQuantity,
    val maximum: UnitQuantity,
    val averageLabel: String,
)

fun statsView(summary: SessionSummary, absolute: Boolean): StatsView = StatsView(
    average = WpmFormat.power(
        if (absolute) summary.magnitudeAverageWatts else summary.signedAverageWatts,
        absolute,
    ),
    minimum = WpmFormat.power(
        if (absolute) summary.minimumMagnitudeWatts else summary.minimumSignedWatts,
        absolute,
    ),
    maximum = WpmFormat.power(
        if (absolute) summary.maximumMagnitudeWatts else summary.maximumSignedWatts,
        absolute,
    ),
    averageLabel = if (absolute) "Session average power (absolute)" else "Session average power (signed)",
)

/** Real, recorded session event derived from stored samples (never invented). */
data class SessionEvent(val timestampMillis: Long, val title: String, val detail: String)

/**
 * Bounded recent-event list built from the retained samples: session start,
 * reported sampling interruptions and reading notes. Newest first.
 */
fun sessionEvents(
    summary: SessionSummary,
    samples: List<BatterySample>,
    lowImpact: Boolean,
    limit: Int = 4,
    reasonLabel: (String?) -> String? = SampleReasons::label,
): List<SessionEvent> {
    val events = ArrayList<SessionEvent>()
    if (summary.startedAtMillis > 0L) {
        events += SessionEvent(
            summary.startedAtMillis,
            "Recording started",
            if (lowImpact) "Low-impact mode" else "High-resolution mode",
        )
    }
    samples.forEachIndexed { index, sample ->
        if (sample.gapBefore && index > 0) {
            events += SessionEvent(sample.timestampMillis, "Sampling resumed", "Readings paused before this sample")
        }
        reasonText(sample.reason, reasonLabel)?.let { reason ->
            events += SessionEvent(sample.timestampMillis, "Reading note", reason)
        }
    }
    return events.sortedByDescending { it.timestampMillis }.take(limit.coerceAtLeast(1))
}

/**
 * Active background policy summary in everyday words.
 *
 * The everyday note says what happens to readings, not how the app schedules CPU
 * time: wake-lock wording lives in [developerPolicyDetail] because it is a
 * developer concept, and it was also what made the mode note read like an
 * engineering changelog on a phone.
 *
 * The title follows the mode the service reports holding, never the selected mode
 * alone: a high-resolution selection whose lease could not be taken must not claim
 * that the CPU stays awake.
 */
data class PolicyView(val title: String, val detail: String)

fun policyView(
    running: Boolean,
    settings: MonitorSettings,
    wakeLockHeld: Boolean = false,
): PolicyView {
    val highResolution = settings.mode == MonitoringMode.HIGH_RESOLUTION
    val interval = settings.intervalSeconds
    if (!running) {
        return PolicyView(
            title = if (highResolution) "High resolution selected \u00b7 stopped" else "Low impact \u00b7 stopped",
            detail = "Recording is stopped, so no readings are being taken. History is kept.",
        )
    }
    if (!highResolution) {
        return PolicyView(
            title = "Low impact \u00b7 recording in the background",
            detail = "A reading every ${interval}s while the phone is awake. Sleep gaps are marked in history.",
        )
    }
    return if (wakeLockHeld) {
        PolicyView(
            title = "High resolution \u00b7 screen can stay off",
            detail = "A reading every ${interval}s. This uses more battery than low impact.",
        )
    } else {
        PolicyView(
            title = "High resolution \u00b7 readings may pause",
            detail = "A reading every ${interval}s. Android may delay readings while the phone sleeps.",
        )
    }
}

/**
 * Developer-only technical policy detail: the real cadence, the wake-lock lease and
 * the bounded calibration burst. Never shown unless developer mode is on.
 */
fun developerPolicyDetail(
    settings: MonitorSettings,
    running: Boolean,
    wakeLockHeld: Boolean,
): String {
    val lock = when {
        !running -> "not held (not recording)"
        wakeLockHeld -> "held"
        settings.mode == MonitoringMode.HIGH_RESOLUTION -> "requested but not held"
        else -> "not held (low impact)"
    }
    return "Poll every ${settings.intervalSeconds} s plus battery-change triggers \u00b7 " +
        "wake lock $lock \u00b7 startup calibration samples about every 1 s, bounded"
}

/**
 * Note under the Stop button.
 *
 * Built from [PolicyView] fields, never from the object itself: interpolating the
 * whole value rendered `PolicyView(title=…, detail=…).title` on a real phone.
 */
fun recordingNote(policy: PolicyView, running: Boolean): String =
    if (running) {
        "${policy.title}\nContinues when you leave the app"
    } else {
        "Recording stopped \u00b7 history saved"
    }

/** Interval explanation that names the default only for the default value. */
fun intervalDescription(intervalSeconds: Int): String {
    val suffix = if (intervalSeconds == MonitorSettings.DEFAULT_INTERVAL_SECONDS) " (the default)" else ""
    return "Every ${intervalSeconds} seconds$suffix. Shorter intervals use more battery. " +
        "Android may delay readings while your phone sleeps."
}

/**
 * User-facing text for a recorded sample note. The recording layer stores stable
 * tokens such as `inconsistent_polarity`; [format] turns them into words (the
 * localised UI strings at the Compose boundary, short English labels otherwise).
 * An unrecognised internal token is replaced by a neutral sentence so identifiers
 * never reach the user as copy.
 */
fun reasonText(reason: String?, format: (String?) -> String? = SampleReasons::label): String? {
    if (reason.isNullOrBlank()) return null
    val text = format(reason)?.takeIf { it.isNotBlank() } ?: return UNKNOWN_REASON
    if (text == reason && isInternalToken(reason)) return UNKNOWN_REASON
    return text
}

/** Recorded reasons are lower-case snake_case tokens; any prose is left untouched. */
private fun isInternalToken(reason: String): Boolean =
    reason.none { it == ' ' } && reason.all { it.isLowerCase() || it == '_' || it.isDigit() }

/**
 * Battery-capacity copy for diagnostics.
 *
 * Four different numbers are kept apart instead of being blurred into one
 * "capacity" line:
 *
 *  - [designCapacity]: the rated capacity the user typed in, or `Not set`.
 *    Android exposes none on a stock device (`PowerProfile` is hidden and the
 *    sysfs node needs root), so it is never inferred.
 *  - [gaugeCapacity]: a single-point gauge estimate (counter / state of charge).
 *    A gauge observation, not a measurement of usable capacity.
 *  - [observedCapacity]: the observed capacity built from complete charging
 *    windows; `estimateCount` counts windows, not polls.
 *  - [health]: observed against design, only when both exist. The design capacity
 *    the user types in is used for **health only**.
 *
 * [runtime] is the trailing **counter** average rate, deliberately separate from
 * the instant primary reading. A discharging estimate needs no capacity figure and
 * appears as soon as a counter average exists; a charging estimate needs
 * the *observed* capacity, and says so until it is measured rather than borrowing
 * the user-supplied design value. It is withheld when recording is stopped or the
 * latest poll is stale so an old rate is never presented as current.
 */
data class CapacityView(
    val designCapacity: String,
    val gaugeCapacity: String,
    val observedCapacity: String,
    val health: String,
    val cycleCount: String,
    val runtime: String,
)

fun capacityView(
    state: MonitorUiState,
    latest: BatterySample?,
    stale: Boolean = false,
    designCapacityMilliampHours: Double? = null,
): CapacityView {
    val design = designCapacityMilliampHours?.takeIf { it.isFinite() && it > 0.0 }
    val observed = state.capacity.estimatedMilliampHours?.takeIf { it.isFinite() && it > 0.0 }
    return CapacityView(
        designCapacity = designCapacityText(design),
        gaugeCapacity = gaugeCapacityText(latest),
        observedCapacity = observedCapacityText(state.capacity, latest, state.running),
        health = healthText(observed, design),
        // A cycle count is a hardware property, not a rate: the last stored reading
        // still reports it truthfully after the recorder stops.
        cycleCount = WpmFormat.cycleCount(latest?.cycleCount),
        runtime = runtimeText(state, latest, stale),
    )
}

/**
 * Design capacity the user typed in, or a plain statement that it is unset.
 *
 * Android reports no design capacity on a stock device, so this value is always
 * user-supplied: it is never read from telemetry, never guessed from a model
 * lookup, and it never feeds back into the reported percentage, current or runtime.
 * It is the denominator of the health ratio and nothing else, which is exactly what
 * Settings promises the user.
 */
private fun designCapacityText(designMilliampHours: Double?): String =
    designMilliampHours?.let { WpmFormat.milliampHours(it) + " \u00b7 user supplied" } ?: DESIGN_CAPACITY_NOT_SET

/**
 * Single-point gauge estimate (`counter / state of charge`) for the latest reading.
 * It is a gauge observation, not the observed charging-session capacity, so it is
 * labelled separately and never presented as the measured capacity.
 */
private fun gaugeCapacityText(latest: BatterySample?): String {
    if (latest == null) return "No reading yet"
    val estimate = CapacityMath.gaugeFullCapacityMilliampHours(latest)
        ?.takeIf { it.isFinite() && it > 0.0 }
    if (estimate != null) return "\u2248 " + WpmFormat.milliampHours(estimate) + " \u00b7 single reading"
    if (latest.chargeCounterMicroampHours == null) return COUNTER_NOT_EXPOSED
    return "Unavailable"
}

/**
 * Capacity observed over complete charging windows (charge-counter change against
 * percentage change). [CapacityEstimate.estimateCount] counts windows, not polls.
 */
private fun observedCapacityText(
    capacity: CapacityEstimate,
    latest: BatterySample?,
    running: Boolean,
): String {
    val estimate = capacity.estimatedMilliampHours?.takeIf { it.isFinite() && it > 0.0 }
    if (estimate != null) {
        val amount = WpmFormat.milliampHours(estimate)
        val windows = capacity.estimateCount
        if (windows <= 0) return "\u2248 $amount"
        return "\u2248 $amount \u00b7 $windows ${if (windows == 1) "charging window" else "charging windows"}"
    }
    if (!running) return OBSERVED_NOT_MEASURED
    if (latest?.chargeCounterMicroampHours == null) return COUNTER_NOT_EXPOSED
    // The policy asks for a long charging window; explain that without a banner.
    return OBSERVED_PENDING
}

/** Ratio of observed to user-supplied design capacity; nothing when either is absent. */
private fun healthText(observedMilliampHours: Double?, designMilliampHours: Double?): String {
    val observed = observedMilliampHours ?: return HEALTH_NEEDS_BOTH
    val design = designMilliampHours?.takeIf { it.isFinite() && it > 0.0 } ?: return HEALTH_NEEDS_BOTH
    val ratio = observed / design
    if (!ratio.isFinite() || ratio <= 0.0) return HEALTH_NEEDS_BOTH
    return "\u2248 ${(ratio * 100.0).roundToInt()}% of design capacity"
}

/**
 * Time at the trailing **counter average** rate, in plain words.
 *
 * The rate is [BatterySample.averageAmps] (charge-counter average over a measured
 * window) only: the instantaneous primary reading is never substituted, because a
 * spike is not a runtime and mixing the two would make runtime jump with the
 * readout. The row label and the sentence both name the counter average so it is
 * not mistaken for the instant number above it.
 *
 * The **observed** capacity is the only capacity that can turn the remaining
 * counter into a charging time; it is the usable capacity the app measured. A
 * user-supplied design capacity is deliberately not used as a fallback: it is
 * rated capacity for the health ratio only, and Settings already promises that it
 * never changes runtime. Before it is observed the row says charging time is
 * unavailable instead of guessing a target. A discharging estimate needs no
 * capacity at all, so it still appears as soon as the average rate does.
 */
private fun runtimeText(
    state: MonitorUiState,
    latest: BatterySample?,
    stale: Boolean,
): String {
    if (!state.running) return "Not recording"
    if (latest == null) return "No reading yet"
    if (stale) return "No recent reading"
    if (latest.chargeCounterMicroampHours == null) return COUNTER_NOT_EXPOSED
    val amps = latest.averageAmps?.takeIf { it != 0.0 } ?: return "Waiting for an average rate"
    val observed = state.capacity.estimatedMilliampHours?.takeIf { it.isFinite() && it > 0.0 }
    val charging = amps > 0.0
    if (charging && observed == null) return CHARGING_NEEDS_OBSERVED
    val seconds = CapacityMath.runtimeSeconds(
        remainingMicroampHours = latest.chargeCounterMicroampHours,
        amps = amps,
        capacityMilliampHours = if (charging) observed else null,
    )
    return when {
        seconds != null ->
            "\u2248 " + WpmFormat.durationCompact(seconds) +
                if (charging) " to full at the recent counter average rate" else " of use at the recent counter average rate"
        else -> "Unavailable"
    }
}

/**
 * Saved current-calibration status for diagnostics.
 *
 * Manual corrections take priority over the saved automatic calibration. A saved
 * calibration is reported only when the calibrator actually established it; while
 * recording without enough evidence the copy says so instead of implying success.
 *
 * [resetAvailable] is true while recording **even when nothing is saved yet**: the
 * calibrator can hold private evidence that is not published, and discarding it is
 * the only way to restart calibration without waiting for it to finish. That is
 * why the action is a reset or a restart depending on [savedCalibration].
 */
data class CalibrationView(
    val status: String,
    val detail: String,
    val resetAvailable: Boolean,
    val instruction: String,
    /** Button label for the reset/restart action. */
    val actionLabel: String,
    /** One concise line under the button; [savedCalibration] says which state it is. */
    val actionNote: String,
    val savedCalibration: Boolean,
)

fun calibrationView(
    settings: MonitorSettings,
    calibration: CurrentCalibration,
    running: Boolean,
    /**
     * The raw latest sample, or null for the durable status alone. Supplied by the
     * normal diagnostics screen so the five-poll startup check and the tentative
     * Estimated state are visible instead of being described as "nothing yet".
     */
    latest: BatterySample? = null,
): CalibrationView {
    val instruction = if (running) {
        "Calibration happens while recording. Keep recording during steady charging or use; " +
            "no deep discharge or full charge is needed."
    } else {
        "Start recording during steady charging or use; no deep discharge or full charge is needed."
    }
    // Published evidence, or any evidence windows the calibrator has already built.
    val savedCalibration = calibration.unit != CurrentUnitOverride.AUTO ||
        calibration.inverted != null ||
        calibration.evidenceWindows > 0
    // Recording always allows a restart: unpublished evidence is discardable too.
    val resetAvailable = savedCalibration || running
    val manualScale = settings.currentUnit != CurrentUnitOverride.AUTO
    val manualPolarity = settings.currentPolarity != CurrentPolarityOverride.AUTO
    val manual = manualScale || manualPolarity
    val actionLabel = if (savedCalibration) "Reset saved calibration" else "Restart calibration"
    val manualNote = if (manual) " Manual corrections in settings are separate." else ""
    val actionNote = when {
        savedCalibration -> "Removes the saved automatic calibration. Recording rebuilds it." + manualNote
        running -> "Nothing is saved yet, so this discards the evidence gathered so far." + manualNote
        else -> "Nothing saved to reset. Start recording to calibrate."
    }
    if (manual) {
        val parts = ArrayList<String>(2)
        if (manualScale) {
            parts += "scale set to " + if (settings.currentUnit == CurrentUnitOverride.MILLIAMPS) "mA" else "\u00b5A"
        }
        if (manualPolarity) {
            parts += "polarity " + if (settings.currentPolarity == CurrentPolarityOverride.INVERTED) "reversed" else "standard"
        }
        return CalibrationView(
            status = "Manual correction",
            detail = "You chose " + parts.joinToString(" and ") +
                ". Manual settings override the saved automatic calibration.",
            resetAvailable = resetAvailable,
            instruction = instruction,
            actionLabel = actionLabel,
            actionNote = actionNote,
            savedCalibration = savedCalibration,
        )
    }
    if (calibration.calibrated) {
        val unit = if (calibration.unit == CurrentUnitOverride.MILLIAMPS) "mA" else "\u00b5A"
        val polarity = if (calibration.inverted == true) "reversed" else "standard"
        val windows = calibration.evidenceWindows
        val basis = if (windows > 0) " from $windows independent measurement windows" else ""
        return CalibrationView(
            status = "Automatic calibration saved",
            detail = "Current scale $unit, polarity $polarity$basis.",
            resetAvailable = true,
            instruction = instruction,
            actionLabel = actionLabel,
            actionNote = actionNote,
            savedCalibration = true,
        )
    }
    // Nothing saved yet: report what the live startup check is actually doing. The
    // number may already come from the tentative five-poll interpretation, so the
    // copy must not claim that nothing is inferred, and a finished check must not
    // read as an endless calibration wait.
    startupCalibrationCopy(latest, running)?.let { startup ->
        return CalibrationView(
            status = startup.status,
            detail = startup.detail,
            resetAvailable = resetAvailable,
            instruction = instruction,
            actionLabel = actionLabel,
            actionNote = actionNote,
            savedCalibration = savedCalibration,
        )
    }
    return CalibrationView(
        status = "Not calibrated yet",
        detail = "There is not enough consistent evidence to confirm the current scale and polarity. " +
            "A reading from the first few polls is labelled Estimated until it is confirmed.",
        resetAvailable = resetAvailable,
        instruction = instruction,
        actionLabel = actionLabel,
        actionNote = actionNote,
        savedCalibration = savedCalibration,
    )
}

/** Startup copy for the normal diagnostics section: status and one detail line. */
private data class StartupCalibrationCopy(val status: String, val detail: String)

/**
 * What the not-yet-confirmed current interpretation is doing right now, or null
 * when there is nothing startup-specific to say (not recording, no sample).
 *
 * This is deliberately separate from the durable calibration evidence: it reports
 * the live provisional state and never claims that anything was saved.
 */
private fun startupCalibrationCopy(latest: BatterySample?, running: Boolean): StartupCalibrationCopy? {
    if (!running || latest == null) return null
    val reading = latest.liveReading()
    return when {
        reading == LiveReading.ESTIMATED -> StartupCalibrationCopy(
            status = "$ESTIMATED_TEXT reading",
            detail = if (latest.chargeCounterMicroampHours == null) {
                "The scale and sign come from the first five readings of this recording. " +
                    "This device reports no charge counter, so the estimate stays labelled " +
                    "$ESTIMATED_TEXT and is never saved as calibration."
            } else {
                "The scale and sign come from the first five readings of this recording. " +
                    "Counter validation continues in the background and replaces the estimate " +
                    "when it succeeds."
            },
        )
        reading == LiveReading.CALIBRATING -> StartupCalibrationCopy(
            status = "Checking the first readings",
            detail = "${startupProgressText(latest)}. The live number is shown as " +
                "$ESTIMATED_TEXT when five readings support an interpretation; calibration keeps " +
                "running in the background.",
        )
        ambiguousStartup(latest) -> StartupCalibrationCopy(
            status = "Current interpretation unclear",
            detail = "The first five readings did not agree on a scale and sign, so no current " +
                "is shown. Open Settings → Developer mode for manual scale/sign correction.",
        )
        else -> null
    }
}

/**
 * Power paragraph under the live values.
 *
 * The same wording is used in every confirmed state; the tentative interpretation
 * gets its own sentence so the normal screen never calls an estimate calibrated.
 */
fun livePowerCaption(estimated: Boolean): String = if (estimated) {
    "Power is the latest estimated battery current times the reported voltage. " +
        "It is a live reading, not a rolling average or a session total, and it is " +
        "not charger input or per-app use. The scale and sign are still being confirmed."
} else {
    "Power is the latest calibrated battery current times the reported voltage. " +
        "It is a live reading, not a rolling average or a session total, and it is " +
        "not charger input or per-app use."
}

private const val LOW_BATTERY_PERCENT = 20
private const val UNKNOWN_REASON = "This reading was kept with an additional sampling note."

/** Worded absence; a design capacity is only ever what the user typed in. */
private const val DESIGN_CAPACITY_NOT_SET = "Not set"

/** Worded absence for a charging estimate that still needs the observed capacity. */
private const val CHARGING_NEEDS_OBSERVED = "Charging time unavailable until capacity is observed"

/** Devices without a charge counter can never produce a counter-based figure. */
private const val COUNTER_NOT_EXPOSED = "Not exposed by this device"

/** Observed capacity needs a long charging window; never a flashing progress banner. */
private const val OBSERVED_PENDING = "Not measured yet \u00b7 needs a longer charging session"

private const val OBSERVED_NOT_MEASURED = "Not measured yet"

private const val HEALTH_NEEDS_BOTH = "Needs an observed and a design capacity"

/**
 * One plain sentence for when the Diagnostics values change.
 *
 * Deliberately free of developer concepts such as a poll, a window or a plot size;
 * the technical cadence line lives in the developer section.
 */
fun updateLine(intervalSeconds: Int): String = "Updates every $intervalSeconds s."

/**
 * Normal-mode calibration state: no unit/polarity/window jargon.
 *
 * The developer section reports the established scale, polarity and evidence-window
 * count; here the user only needs to know whether readings are calibrated yet.
 */
fun calibrationSummary(
    settings: MonitorSettings,
    calibration: CurrentCalibration,
    running: Boolean,
    /** The raw latest sample, so the startup check can be shown as progress. */
    latest: BatterySample? = null,
): String {
    val manual = settings.currentUnit != CurrentUnitOverride.AUTO ||
        settings.currentPolarity != CurrentPolarityOverride.AUTO
    if (manual) return "Manual correction applied"
    return when {
        // A durable confirmation always outranks the live provisional state.
        calibration.calibrated -> "Calibrated from recorded evidence"
        !running -> "Not calibrated"
        latest == null -> "Calibrating while recording"
        latest.liveReading() == LiveReading.ESTIMATED ->
            if (latest.chargeCounterMicroampHours == null) "$ESTIMATED_TEXT \u00b7 no counter validation"
            else "$ESTIMATED_TEXT \u00b7 counter validation pending"
        latest.liveReading() == LiveReading.CALIBRATING -> startupProgressText(latest)
        ambiguousStartup(latest) -> "Current interpretation unclear"
        latest.liveReading() == LiveReading.UNAVAILABLE -> UNAVAILABLE_TEXT
        latest.liveReading() == LiveReading.READY -> "Confirmed interpretation active"
        else -> "Checking the first readings"
    }
}

/**
 * One concise line after the reset/restart action was confirmed, so the state is
 * visible without a developer-style detail line.
 */
fun calibrationResetFeedback(running: Boolean): String =
    if (running) "Reset requested \u00b7 calibration restarts while recording." else "Reset requested \u00b7 saved calibration cleared."
