package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.monitoring.SampleText

/** Session screen: duration, separate energy totals, coverage, events, export. */
@Composable
internal fun SessionScreen(scope: DashboardScope, actions: DashboardActions) {
    val colors = LocalWpmColors.current
    val context = LocalContext.current
    val summary = scope.state.summary
    val samples = scope.state.samples
    val coverage = WpmFormat.coveragePercent(summary.observedSeconds, summary.elapsedSeconds)
    val missingSeconds = (summary.elapsedSeconds - summary.observedSeconds).coerceAtLeast(0.0)
    val stats = statsView(summary, scope.settings.absolute)
    // Nothing recorded means nothing to export; the control stays truthful.
    val exportable = summary.sampleCount > 0 || samples.isNotEmpty()
    val events = sessionEvents(
        summary = summary,
        samples = samples,
        lowImpact = scope.settings.mode != MonitoringMode.HIGH_RESOLUTION,
    ) { reason -> SampleText.reason(context, reason) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Text(
            text = "LOCAL RECORDING",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = colors.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "This session",
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp),
            color = colors.text,
        )
        Text(
            text = sessionDescription(scope),
            style = bodyText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
        )
        dashboardError(scope.state.error)?.let { error ->
            AttentionNote(error, color = colors.warning)
        }
        Column(Modifier.padding(top = 12.dp)) {
            Text(
                text = WpmFormat.durationHms(summary.elapsedSeconds),
                style = telemetry(34.sp),
                color = colors.text,
            )
            Text("elapsed time", style = captionText(), color = colors.muted)
        }
        HorizontalDivider(
            color = colors.rule.copy(alpha = 0.5f),
            thickness = 1.dp,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EnergyCell("Battery out", summary.outWh, Modifier.weight(1f))
            EnergyCell("Battery in", summary.inWh, Modifier.weight(1f))
        }
        HorizontalDivider(color = colors.rule.copy(alpha = 0.5f), thickness = 1.dp)
        WpmSectionHeading(
            title = "Observed coverage",
            modifier = Modifier.padding(top = 16.dp),
            trailing = {
                Text(
                    text = coverage?.let { "$it%" } ?: WpmFormat.DASH,
                    style = telemetry(13.sp),
                    color = colors.text,
                )
            },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(4.dp)
                .background(colors.surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction = (coverage ?: 0).coerceIn(0, 100) / 100f)
                    .height(4.dp)
                    .background(colors.trace),
            )
        }
        Text(
            text = WpmFormat.durationCompact(summary.observedSeconds) + " sampled " +
                WpmFormat.SEPARATOR + " " + WpmFormat.durationCompact(missingSeconds) + " missing",
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            // Plain wording: the totals leave out periods with no reading; there is
            // no interpolation to explain.
            text = "Energy totals integrate live readings, including startup estimates, " +
                "and exclude missing time and correction gaps.",
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        WpmDataRow(stats.averageLabel, WpmFormat.withUnit(stats.average))
        WpmDataRow(
            "Minimum / maximum",
            WpmFormat.withUnit(stats.minimum) + " / " + WpmFormat.withUnit(stats.maximum),
        )
        WpmDataRow("Recorded samples", summary.sampleCount.toString())
        WpmDataRow("Sampling gaps", summary.gapCount.toString())
        WpmSectionHeading(title = "Session events", modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
        if (events.isEmpty()) {
            Text(
                text = "No interruptions recorded yet.",
                style = captionText(),
                color = colors.muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            events.forEach { event ->
                SessionEventRow(event)
            }
        }
        WpmSecondaryButton(
            text = "Export CSV",
            onClick = actions.onExport,
            enabled = exportable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            icon = WpmIcon.EXPORT,
        )
        Text(
            text = if (exportable) {
                "Includes timestamps, signed power readings and gap markers. " +
                    "Only history retained on this device is exported."
            } else {
                "Nothing recorded yet, so there is nothing to export."
            },
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (scope.state.historyTruncated) {
            AttentionNote(
                text = "Only recently retained samples are stored on this device. " +
                    "Session energy and totals still cover the whole session.",
                color = colors.warning,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Out/in energy in Wh, mWh or µWh; the suffix follows the value's magnitude. */
@Composable
private fun EnergyCell(label: String, wattHours: Double, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    val energy = WpmFormat.energy(wattHours)
    Column(modifier = modifier) {
        Text(label, style = captionText(), color = colors.muted, maxLines = 1, softWrap = false)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = energy.text,
                style = telemetry(21.sp),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
            if (energy.available) {
                Text(
                    text = energy.unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SessionEventRow(event: SessionEvent) {
    val colors = LocalWpmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = WpmFormat.clock(event.timestampMillis),
            style = telemetry(10.sp),
            color = colors.muted,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(event.title, style = bodyText(), color = colors.text)
            Text(event.detail, style = captionText(), color = colors.muted)
        }
    }
}

@Composable
private fun sessionDescription(scope: DashboardScope): String {
    val summary = scope.state.summary
    if (summary.startedAtMillis <= 0L) return "No session recorded yet. Start recording to build history."
    val start = WpmFormat.clock(summary.startedAtMillis)
    if (scope.state.running) return "Started $start \u00b7 recording in the background"
    val endMillis = summary.endedAtMillis ?: scope.state.latest?.timestampMillis
    return if (endMillis != null) {
        "$start\u2013" + WpmFormat.clock(endMillis) + " \u00b7 saved session"
    } else {
        "Started $start \u00b7 saved session"
    }
}
