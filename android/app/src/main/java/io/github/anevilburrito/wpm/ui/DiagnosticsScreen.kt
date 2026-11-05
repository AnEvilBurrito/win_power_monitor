package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.averageAmps
import io.github.anevilburrito.wpm.model.averageWatts
import io.github.anevilburrito.wpm.model.isSampleStale
import io.github.anevilburrito.wpm.model.liveAmps
import io.github.anevilburrito.wpm.model.liveWatts
import io.github.anevilburrito.wpm.monitoring.SampleText

/**
 * Diagnostics describes the live service in terms a user can act on: is it
 * recording, what the battery reports right now, what is known about the installed
 * battery, and whether the primary live reading is calibrated.
 *
 * Ordinary users see the **latest calibrated** power and current, because that is
 * the number the dashboard leads with. Calibration is now required for that primary
 * readout, so its plain-language status and the reset/retry action live in the
 * normal screen. The raw `CURRENT_NOW`/`AVERAGE` properties, the counter average and
 * window, the correction flags and the wake-lock lease are developer detail and
 * appear only when developer mode is on.
 *
 * The developer section is deliberately short: a handful of provenance rows and two
 * sentences, not a property dump. Raw evidence stays in the CSV export.
 *
 * Freshness is computed from the live monotonic clock here, not from the frozen
 * monitor snapshot: freezing the chart must not make this screen report an old
 * reading as fresh (or a fresh one as stale).
 */
@Composable
internal fun DiagnosticsScreen(scope: DashboardScope, actions: DashboardActions) {
    val colors = LocalWpmColors.current
    val context = LocalContext.current
    val live = scope.state
    val sample = live.latest
    // The mode row reports the service's own policy title, so a high-resolution
    // selection without the CPU lease already reads "readings may pause" instead of
    // a separate technical wake-lock advisory.
    val policy = policyView(live.running, scope.settings, live.wakeLockHeld)
    // Live age of the live sample; a frozen chart snapshot must not decide this.
    val stale = isSampleStale(sample, scope.liveElapsedMillis, scope.settings.intervalSeconds)
    // One shared view for the whole screen, so the battery rows, the calibration
    // copy and the Readings section cannot describe the same poll differently. The
    // five-poll interpretation is a live numeric state here, only labelled
    // Estimated on its readiness line.
    val reading = liveReadingView(sample, live.running, stale, scope.settings.absolute)
    val capacity = capacityView(
        state = live,
        latest = sample,
        stale = stale,
        designCapacityMilliampHours = scope.settings.designCapacityMilliampHours,
    )
    // The normal screen passes the live sample so the startup check is reported as
    // progress or as an Estimated reading, never as "nothing yet".
    val calibration = calibrationView(scope.settings, live.calibration, live.running, latest = sample)
    val ageSeconds = sample?.let { (scope.liveElapsedMillis - it.elapsedRealtimeMillis) / 1000.0 }
    // A stopped session keeps its recorded history, but a reading must not look
    // live: a stale value is reported as such and its rate-dependent values close.
    val readingIsFresh = live.running && sample != null && !stale
    val condition = SampleText.condition(context, sample?.condition)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Text(
            text = "BATTERY INFORMATION",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = colors.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Diagnostics",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
            color = colors.text,
        )
        Text(
            text = "Readings and battery information reported by your device.",
            style = bodyText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        dashboardError(live.error)?.let { error ->
            AttentionNote(error, color = colors.warning)
        }
        WpmSectionHeading(title = "Recording", modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        WpmDataRow("Status", if (live.running) "Recording" else "Stopped")
        WpmDataRow(
            "Mode",
            if (live.running) {
                policy.title
            } else {
                when (scope.settings.mode) {
                    MonitoringMode.HIGH_RESOLUTION -> "High resolution"
                    MonitoringMode.LOW_IMPACT -> "Low impact"
                }
            },
        )
        WpmDataRow("Sampling interval", "${scope.settings.intervalSeconds} s")
        Text(
            text = updateLine(scope.settings.intervalSeconds),
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        WpmSectionHeading(title = "Battery", modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
        WpmDataRow("Level", if (readingIsFresh) levelText(sample?.percentage) else WpmFormat.DASH)
        WpmDataRow("Live power", if (readingIsFresh) livePowerText(sample) else WpmFormat.DASH)
        WpmDataRow("Live current", if (readingIsFresh) liveCurrentText(sample) else WpmFormat.DASH)
        WpmDataRow(
            "Voltage",
            if (readingIsFresh) voltsText(sample?.volts) else WpmFormat.DASH,
        )
        WpmDataRow(
            "Temperature",
            if (readingIsFresh) temperatureText(sample?.temperatureC) else WpmFormat.DASH,
        )
        WpmDataRow("Reported condition", condition ?: "Unavailable")
        WpmDataRow(
            "Last reading",
            when {
                !live.running -> "Session stopped"
                sample == null -> "No reading yet"
                else -> WpmFormat.age(ageSeconds) + " ago"
            },
        )
        Text(
            text = livePowerCaption(reading.estimated),
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        WpmSectionHeading(title = "Capacity", modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
        WpmDataRow("Design capacity", capacity.designCapacity)
        WpmDataRow("Gauge full-capacity estimate", capacity.gaugeCapacity)
        WpmDataRow("Observed capacity", capacity.observedCapacity)
        WpmDataRow("Estimated health", capacity.health)
        WpmDataRow("Cycle count", capacity.cycleCount)
        // A runtime normally wraps to two lines; its waiting/stale messages must
        // not collapse the row and move every section below it on a state change.
        WpmDataRow("Estimated runtime", capacity.runtime, valueMinLines = 2)
        Text(
            text = "Observed capacity is measured over complete charging sessions. The gauge " +
                "estimate is a single reading and is never reported as the observed capacity. " +
                "Design capacity is only what you enter in settings, and it is used only for " +
                "estimated health.",
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Calibration is required for the primary reading, so its user-facing state
        // and the reset/retry action live in the normal screen, not only in developer.
        WpmSectionHeading(title = "Current calibration", modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
        Text(
            text = calibrationSummary(scope.settings, live.calibration, live.running, latest = sample),
            style = bodyText(),
            color = colors.text,
        )
        Text(
            text = calibration.instruction,
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        WpmSecondaryButton(
            text = calibration.actionLabel,
            onClick = actions.viewModel::requestCalibrationReset,
            enabled = calibration.resetAvailable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
        Text(
            text = if (actions.viewModel.calibrationResetRequested) {
                calibrationResetFeedback(live.running)
            } else {
                calibration.actionNote
            },
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        WpmSectionHeading(title = "Readings", modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
        WpmDataRow("Status", reading.statusLabel)
        Text(
            text = reading.readiness,
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (scope.settings.developerMode) {
            DeveloperSection(scope, sample)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * Technical provenance, shown only when the user turned developer mode on.
 *
 * Kept to a short list: the calibrated live reading next to the raw platform
 * properties and the auxiliary counter average, the calibration evidence and the
 * real cadence. It is a diagnostic aid, not a second UI for every stored field.
 */
@Composable
private fun DeveloperSection(
    scope: DashboardScope,
    sample: BatterySample?,
) {
    val colors = LocalWpmColors.current
    val live = scope.state
    // Developer provenance deliberately shows the durable calibration only: the
    // tentative five-poll interpretation has its own rows, so evidence and the live
    // estimate can never be read as the same thing.
    val calibration = calibrationView(scope.settings, live.calibration, live.running)
    WpmSectionHeading(title = "Developer", modifier = Modifier.padding(top = 22.dp, bottom = 2.dp))
    WpmDataRow("Live power / current", livePowerText(sample) + " " + WpmFormat.SEPARATOR + " " + liveCurrentText(sample))
    WpmDataRow("Startup check", startupCheckText(sample))
    WpmDataRow("Current interpretation", interpretationText(sample))
    WpmDataRow(
        "Instant power / current",
        sample?.let {
            WpmFormat.withUnit(WpmFormat.power(it.watts, absolute = false)) + " " +
                WpmFormat.SEPARATOR + " " +
                WpmFormat.withUnit(WpmFormat.current(it.amps, absolute = false))
        } ?: WpmFormat.DASH,
    )
    WpmDataRow(
        "Raw NOW / average property",
        microampsText(sample?.rawCurrentMicroamps) + " " + WpmFormat.SEPARATOR + " " +
            microampsText(sample?.rawAverageMicroamps),
    )
    WpmDataRow(
        "Normalised current",
        microampsText(sample?.currentMicroamps) + " \u00b7 " +
            (sample?.currentSource?.name ?: "NONE"),
    )
    WpmDataRow(
        "Calibrated / sign adjusted",
        flagText(sample?.currentCalibrated) + " " + WpmFormat.SEPARATOR + " " +
            flagText(sample?.currentPolarityAdjusted),
    )
    WpmDataRow("Calibration status", calibration.status)
    WpmDataRow("Calibration scale / sign", calibration.detail)
    WpmDataRow("Counter window", windowText(sample))
    WpmDataRow(
        "Counter average power / current",
        averagePowerText(sample) + " " + WpmFormat.SEPARATOR + " " + averageCurrentText(sample),
    )
    WpmDataRow(
        "Live / counter watts",
        WpmFormat.withUnit(WpmFormat.power(sample?.liveWatts, absolute = false)) +
            " " + WpmFormat.SEPARATOR + " " +
            WpmFormat.withUnit(WpmFormat.power(sample?.averageWatts, absolute = false)),
    )
    Text(
        text = "The main reading is interpreted instantaneous CURRENT_NOW times the " +
            "reported voltage. The charge-counter average is auxiliary: it drives the " +
            "estimated runtime and appears here only for comparison.",
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = "The startup rows describe the tentative first-reading interpretation. " +
            "The calibration rows describe only the durable, counter-confirmed result " +
            "that is saved.",
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = developerPolicyDetail(scope.settings, live.running, live.wakeLockHeld),
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Latest calibrated power for diagnostics. It uses
 * [io.github.anevilburrito.wpm.model.liveWatts] only; the counter average and raw
 * uncalibrated values are never substituted, and an absent value keeps the dash.
 */
internal fun livePowerText(sample: BatterySample?): String =
    WpmFormat.withUnit(WpmFormat.power(sample?.liveWatts, absolute = false))

/** Latest calibrated current for diagnostics; never the counter average. */
internal fun liveCurrentText(sample: BatterySample?): String =
    WpmFormat.withUnit(WpmFormat.current(sample?.liveAmps, absolute = false))

/**
 * Startup check progress for the developer section: how many independent polls
 * the five-poll window holds and whether it has finished.
 */
internal fun startupCheckText(sample: BatterySample?): String {
    if (sample == null) return WpmFormat.DASH
    val polls = sample.startupPollCount.coerceIn(0, STARTUP_CHECK_POLLS)
    val state = when {
        sample.startupCheckComplete -> "complete"
        polls > 0 -> "in progress"
        else -> "not started"
    }
    return "$polls/$STARTUP_CHECK_POLLS \u00b7 $state"
}

/**
 * Which interpretation is actually driving the stored current, in developer words.
 * The tentative one is named as an estimate so it is never mistaken for evidence.
 */
internal fun interpretationText(sample: BatterySample?): String = when {
    sample == null -> WpmFormat.DASH
    sample.currentSource == CurrentSource.INSTANT_ESTIMATED ->
        "estimated (five-poll heuristic, awaiting counter validation)"
    sample.currentCalibrated -> "confirmed"
    sample.currentSource == CurrentSource.INSTANT -> "unconfirmed instantaneous"
    else -> "no instantaneous source"
}

/**
 * Auxiliary counter-average power, developer comparison only. It uses
 * [io.github.anevilburrito.wpm.model.averageWatts] only; the instant reading is
 * never substituted, and an absent average keeps the em dash.
 */
internal fun averagePowerText(sample: BatterySample?): String =
    WpmFormat.withUnit(WpmFormat.power(sample?.averageWatts, absolute = false))

/** Auxiliary counter-average current, developer comparison only. */
internal fun averageCurrentText(sample: BatterySample?): String =
    WpmFormat.withUnit(WpmFormat.current(sample?.averageAmps, absolute = false))

/** `68%`, or the em dash when the platform reported no level. */
private fun levelText(percentage: Int?): String {
    val value = WpmFormat.percent(percentage)
    return if (value == WpmFormat.DASH) WpmFormat.DASH else "$value%"
}

/** `4.02 V`, or the em dash for a missing or nonpositive voltage. */
private fun voltsText(volts: Double?): String {
    val value = WpmFormat.voltage(volts)
    return if (value == WpmFormat.DASH) WpmFormat.DASH else "$value V"
}

/** `31.2 °C`, or the em dash when no temperature was reported. */
private fun temperatureText(celsius: Double?): String {
    val value = WpmFormat.temperature(celsius)
    return if (value == WpmFormat.DASH) WpmFormat.DASH else "$value \u00b0C"
}

/** Raw microamp property, or the em dash. Developer section only. */
private fun microampsText(value: Long?): String =
    value?.let { "$it \u00b5A" } ?: WpmFormat.DASH

private fun flagText(value: Boolean?): String = when (value) {
    null -> WpmFormat.DASH
    true -> "yes"
    false -> "no"
}

/** Counter window behind the average, in seconds; developer detail. */
private fun windowText(sample: BatterySample?): String {
    val millis = sample?.derivedWindowMillis?.takeIf { it > 0L } ?: return WpmFormat.DASH
    return WpmFormat.durationCompact(millis / 1000.0)
}
