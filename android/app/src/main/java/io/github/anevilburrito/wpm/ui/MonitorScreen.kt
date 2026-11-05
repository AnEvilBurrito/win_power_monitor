package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.UnitQuantity
import kotlin.math.roundToInt

/**
 * Portrait monitor: stacked readouts, chart, window statistics, session link.
 *
 * The readout is latest-reading and slot-based: the big slot holds the calibrated
 * instantaneous power (or the word that explains why there is none), the unit slot
 * is reserved even when empty, and the battery percentage has its own end-aligned
 * slot. Every
 * single-line label sets `maxLines = 1` and `softWrap = false` so a narrow row can
 * clip a caption but can never wrap it character-by-character.
 */
@Composable
internal fun MonitorPortrait(scope: DashboardScope, actions: DashboardActions) {
    val colors = LocalWpmColors.current
    val reading = scope.reading()
    val stats = statsView(scope.windowSummary, scope.settings.absolute)
    val axisUnit = chartUnitFor(scope)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        MonitorStatusLine(scope)
        dashboardError(scope.state.error)?.let { error ->
            AttentionNote(error, color = colors.warning)
        }
        // Constant caption: the main number is always named, whether or not a
        // usable calibrated reading exists yet, so nothing appears or vanishes here.
        Text(
            text = reading.caption,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 1,
            softWrap = false,
        )
        PowerNumberRow(
            reading = reading,
            landscape = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = reading.direction,
                style = telemetry(11.sp),
                color = flowColor(reading),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = reading.electrical,
                style = telemetry(11.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
        }
        // Reserved ready-state line: the same height in every state, measured from
        // the real font scale, so calibrating, live, missing and stopped can not
        // reflow the chart below.
        Text(
            text = reading.readiness,
            style = captionText(),
            color = colors.muted,
            modifier = Modifier
                .padding(top = 2.dp)
                .height(ReadoutSlots.readinessLineHeightDp(LocalDensity.current.fontScale).dp),
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = LandscapeRules.LABEL_MIN_WIDTH_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Power",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    color = colors.text,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = " ${WpmFormat.SEPARATOR} ${axisUnit.suffix}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.muted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            ScaleToggle(scope, actions)
        }
        RangeToolbar(scope, actions)
        ChartCaption(scope.window, scope.frozen, scope.state.running, scope.stale)
        ChartArea(
            modifier = Modifier
                .fillMaxWidth()
                .height(LandscapeRules.PORTRAIT_PLOT_HEIGHT_DP.dp)
                .padding(top = 4.dp),
            samples = scope.liveSamples,
            windowSeconds = scope.window.seconds,
            intervalSeconds = scope.settings.intervalSeconds,
            absolute = scope.settings.absolute,
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = colors.rule.copy(alpha = 0.5f), thickness = 1.dp)
        WindowStatsRow(stats, Modifier.padding(top = 14.dp))
        HorizontalDivider(
            color = colors.rule.copy(alpha = 0.5f),
            thickness = 1.dp,
            modifier = Modifier.padding(top = 14.dp),
        )
        SessionLinkRow(scope, actions)
        WpmModeNote(
            policy = policyView(scope.state.running, scope.settings, scope.state.wakeLockHeld),
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

/**
 * Big number, bounded unit slot and bounded battery slot.
 *
 * The number and unit are packed together inside a stable outer field. Unused
 * numeric capacity goes AFTER the pair, not between them. This keeps mW beside
 * 358.1 rather than far to the right, without moving the battery or resizing the
 * row when digits/units/readiness change. Suffix width is measured using the real
 * font, across every supported power unit, and cached by font configuration.
 *
 * At a font scale where a single line would make the number too small to read, the
 * battery slot moves under the number (a stable arrangement, still chosen from
 * width and font scale only). If even that cannot show the word headline at a
 * legible size, the word is repeated in a stable one-line status message, so
 * `Calibrating` is never clipped.
 */
@Composable
private fun PowerNumberRow(
    reading: ReadingView,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWpmColors.current
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val unitStyle = telemetry(ReadoutSlots.unitSizeSp(landscape).sp)
    val textMeasurer = rememberTextMeasurer()
    val measuredUnitWidthDp = remember(textMeasurer, unitStyle, density) {
        val widthPx = PowerUnit.entries.maxOf { unit ->
            textMeasurer.measure(
                text = unit.suffix,
                style = unitStyle,
                softWrap = false,
                maxLines = 1,
            ).size.width
        }
        with(density) { widthPx.toDp().value }
    }
    BoxWithConstraints(modifier) {
        val plan = ReadoutSlots.numberRowPlan(
            maxWidth.value.roundToInt(), fontScale, landscape, measuredUnitWidthDp,
        )
        Column(Modifier.fillMaxWidth()) {
            if (plan.percentBelow) {
                PowerValueSlot(reading, plan)
                PercentageSlot(reading, plan, Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    PowerValueSlot(reading, plan)
                    Spacer(Modifier.weight(1f))
                    PercentageSlot(reading, plan, Modifier.width(plan.percentSlotDp.dp))
                }
            }
            if (!plan.wordFits) {
                // Reserve this fallback line in *every* state at this font scale;
                // showing it only for Calibrating would move the chart on readiness.
                Text(
                    text = if (reading.headlineIsWord) reading.headline else " ",
                    style = telemetry(ReadoutSlots.WORD_FALLBACK_SP.sp),
                    color = colors.text,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Fixed outer footprint; adjacent number/unit share their actual text baseline. */
@Composable
private fun PowerValueSlot(reading: ReadingView, plan: ReadoutSlots.NumberRowPlan) {
    val colors = LocalWpmColors.current
    Box(
        modifier = Modifier
            .width(plan.powerGroupWidthDp.dp)
            .height(plan.headlineHeightDp.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (reading.headlineIsWord && !plan.wordFits) WpmFormat.DASH else reading.headline,
                style = if (reading.headlineIsWord) {
                    telemetry(plan.wordSizeSp.sp)
                } else {
                    telemetry(plan.numberSizeSp.sp, FontWeight.Medium)
                },
                color = if (reading.live) colors.text else colors.muted,
                modifier = Modifier
                    .widthIn(max = (if (reading.headlineIsWord) plan.powerGroupWidthDp else plan.numberFieldDp).dp)
                    .alignByBaseline()
                    .testTag("power-value"),
                maxLines = 1,
                softWrap = false,
            )
            if (reading.powerAvailable) {
                Spacer(Modifier.width(ReadoutSlots.UNIT_GAP_DP.dp))
                Text(
                    text = reading.power.unit,
                    style = telemetry(plan.unitSizeSp.sp),
                    color = colors.muted,
                    modifier = Modifier
                        .width(plan.unitTextWidthDp.dp)
                        .alignByBaseline()
                        .testTag("power-unit"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Fixed-width battery slot: digits grow leftwards inside it.
 *
 * The caption is `battery` or the shorter `low`, so the widest caption is what the
 * slot was budgeted for and the low-battery state cannot widen the column.
 */
@Composable
private fun PercentageSlot(
    reading: ReadingView,
    plan: ReadoutSlots.NumberRowPlan,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWpmColors.current
    val tone = if (reading.lowBattery) colors.low else null
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = reading.percentage,
                style = telemetry(plan.percentSizeSp.sp),
                color = tone ?: colors.text,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "%",
                style = telemetry(plan.percentMarkSizeSp.sp),
                color = tone ?: colors.muted,
                modifier = Modifier.padding(bottom = plan.percentMarkBaselineOffsetDp.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = if (reading.lowBattery) "low" else "battery",
            style = captionText(),
            color = tone ?: colors.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Axis unit for the current window, shared by the chart caption and the axis. */
@Composable
internal fun chartUnitFor(scope: DashboardScope): PowerUnit =
    remember(scope.liveSamples, scope.window, scope.settings.absolute) {
        chartAxisUnit(scope.liveSamples, scope.window.seconds, scope.settings.absolute)
    }

/**
 * Landscape strip above the plot: power, battery, flow/state, window stats.
 *
 * The strip has a minimum height from [LandscapeRules] and every cell is a
 * single-line label, so it can never be squeezed into a column of letters. The
 * instant caption that used to sit under the big number is gone: the toolbar and
 * the chart already say what the number is, and the line cost real height in the
 * canvas budget.
 */
@Composable
internal fun LandscapeReadingsStrip(scope: DashboardScope, widthDp: Int) {
    val colors = LocalWpmColors.current
    val reading = scope.reading()
    val stats = statsView(scope.windowSummary, scope.settings.absolute)
    val compact = LandscapeRules.usesCompactStrip(widthDp)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("readings-strip")
            .heightIn(min = LandscapeRules.readingsStripHeightDp(widthDp).dp)
            .padding(horizontal = 12.dp),
    ) {
        dashboardError(scope.state.error)?.let { error ->
            // Capped at the two lines LandscapeRules.NOTICE_HEIGHT_DP budgets.
            AttentionNote(error, color = colors.warning, maxLines = 2)
        }
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PowerNumberRow(
                    reading = reading,
                    landscape = true,
                    modifier = Modifier.weight(1.2f),
                )
                FlowCell(reading, Modifier.weight(1f))
            }
            WindowStatsRow(stats, Modifier.padding(bottom = 4.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PowerNumberRow(
                    reading = reading,
                    landscape = true,
                    modifier = Modifier.weight(1.2f),
                )
                FlowCell(reading, Modifier.weight(1f))
                WindowStatsRow(stats, Modifier.weight(1.4f))
            }
        }
    }
}

/**
 * Three fixed lines: direction, voltage/current and the ready-state note.
 *
 * All three are always drawn, so calibrating, unavailable and live cost the strip
 * exactly the same height and the chart below never moves.
 */
@Composable
private fun FlowCell(reading: ReadingView, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    Column(modifier = modifier.padding(start = 12.dp)) {
        Text(
            text = reading.direction,
            style = telemetry(10.sp),
            color = flowColor(reading),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = reading.electrical,
            style = telemetry(10.sp),
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = reading.readiness,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Landscape chart toolbar.
 *
 * One line when the window is wide enough for the caption to keep
 * [LandscapeRules.LABEL_MIN_WIDTH_DP]; below [LandscapeRules.TWO_ROW_TOOLBAR_BELOW_WIDTH_DP]
 * the controls move to their own line instead of stealing the caption's width.
 * The control group is intrinsic and bounded, so it can never push the caption
 * down to a few dp.
 */
@Composable
internal fun ChartFullToolbar(scope: DashboardScope, actions: DashboardActions, widthDp: Int) {
    val colors = LocalWpmColors.current
    val axisUnit = chartUnitFor(scope)
    val twoRows = LandscapeRules.usesTwoRowToolbar(widthDp)
    val height = LandscapeRules.toolbarHeightDp(widthDp).dp
    if (twoRows) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("landscape-toolbar")
                .heightIn(min = height)
                .padding(horizontal = 12.dp),
        ) {
            ToolbarLabel(scope, axisUnit, Modifier.fillMaxWidth())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LandscapeRules.TOOLBAR_ROW_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LandscapeRules.CONTROL_SPACING_DP.dp),
            ) {
                RangeChips(scope, actions)
                Spacer(Modifier.weight(1f))
                ScaleToggle(scope, actions, showLabel = widthDp >= 420)
                FreezeButton(scope, actions)
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("landscape-toolbar")
                .heightIn(min = height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LandscapeRules.CONTROL_SPACING_DP.dp),
        ) {
            ToolbarLabel(
                scope = scope,
                axisUnit = axisUnit,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = LandscapeRules.LABEL_MIN_WIDTH_DP.dp),
            )
            RangeChips(scope, actions)
            ScaleToggle(scope, actions)
            FreezeButton(scope, actions)
        }
    }
}

@Composable
private fun ToolbarLabel(scope: DashboardScope, axisUnit: PowerUnit, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Power",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = " ${WpmFormat.SEPARATOR} ${axisUnit.suffix}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = chartCaptionText(scope.window, scope.frozen, scope.state.running, scope.stale),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            // Ordinary states stay muted; warning colour is reserved for real errors.
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RangeToolbar(scope: DashboardScope, actions: DashboardActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Intrinsic chips, then the flexible gap: the chips are never stretched.
        RangeChips(scope, actions)
        Spacer(Modifier.weight(1f))
        FreezeButton(scope, actions)
    }
}

/** Three intrinsic-width chips; no fillMaxWidth anywhere, so one chip cannot grow. */
@Composable
private fun RangeChips(scope: DashboardScope, actions: DashboardActions) {
    Row(
        modifier = Modifier
            .testTag("range-chips")
            .widthIn(min = LandscapeRules.rangeChipsWidthDp().dp),
        horizontalArrangement = Arrangement.spacedBy(LandscapeRules.CHIP_SPACING_DP.dp),
    ) {
        ChartWindow.entries.forEach { window ->
            WpmChip(
                text = window.label,
                selected = scope.window == window,
                enabled = scope.frozen == null,
                minWidth = LandscapeRules.CHIP_MIN_WIDTH_DP.dp,
                onClick = { actions.viewModel.selectWindow(window) },
            )
        }
    }
}

/** Absolute/signed display switch; transforms every readout without refreezing. */
@Composable
private fun ScaleToggle(scope: DashboardScope, actions: DashboardActions, showLabel: Boolean = true) {
    val colors = LocalWpmColors.current
    val absolute = scope.settings.absolute
    val toggle = Modifier
        .heightIn(min = 48.dp)
        .clip(RoundedCornerShape(8.dp))
        .toggleable(
            value = absolute,
            role = Role.Switch,
            onValueChange = { enabled -> actions.onSettingsChange(scope.settings.copy(absolute = enabled)) },
        )
        .padding(horizontal = 6.dp)
    Row(
        modifier = if (showLabel) toggle else toggle.semantics { contentDescription = "Absolute scale" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showLabel) {
            Text(
                text = "Absolute scale",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (absolute) colors.text else colors.muted,
                maxLines = 1,
                softWrap = false,
            )
        }
        WpmMiniSwitch(checked = absolute)
    }
}

@Composable
private fun FreezeButton(scope: DashboardScope, actions: DashboardActions) {
    val colors = LocalWpmColors.current
    val frozen = scope.frozen != null
    val enabled = frozen || (scope.state.running && scope.state.samples.isNotEmpty())
    val tint = when {
        frozen -> colors.trace
        enabled -> colors.muted
        else -> colors.rule
    }
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    actions.viewModel.toggleFreeze(
                        scope.state,
                        scope.liveElapsedMillis,
                        System.currentTimeMillis(),
                    )
                },
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WpmIconView(WpmIcon.FREEZE, Modifier.size(18.dp), tint = tint)
        Text(
            text = if (frozen) "Resume" else "Freeze",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Recording state on the left, reading age on the right.
 *
 * Both halves are weighted and both texts are aligned to their outer edge, so a
 * longer status string (`Sample 2m 30s ago`, `Frozen 09:41:06`) can only use its
 * own half: it cannot move the recording-state label, and the label cannot move it.
 * The strings are kept inside [ReadoutSlots.STATUS_TEXT_CHARS] so the narrowest
 * supported portrait shows them whole; at a much larger font scale they ellipsise
 * instead of pushing anything.
 */
@Composable
private fun MonitorStatusLine(scope: DashboardScope) {
    val colors = LocalWpmColors.current
    val dotColor = when {
        scope.frozen != null -> colors.trace
        scope.state.running -> colors.trace
        else -> colors.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ReadoutSlots.STATUS_ROW_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WpmStatusDot(dotColor)
            Text(
                text = when {
                    scope.frozen != null -> "Frozen view"
                    scope.state.running -> "Recording"
                    else -> "Stopped"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = statusDetail(scope),
            style = telemetry(ReadoutSlots.STATUS_TEXT_SP.sp),
            color = colors.muted,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Freshness text: recording state stays separate from measurement age.
 *
 * Every variant is at most [ReadoutSlots.STATUS_TEXT_CHARS] characters, because
 * the age half only owns half of the narrowest portrait row.
 */
@Composable
private fun statusDetail(scope: DashboardScope): String {
    val frozen = scope.frozen
    if (frozen != null) {
        return "Frozen " + WpmFormat.clockWithSeconds(frozen.capturedAtWallMillis)
    }
    if (!scope.state.running) return "History kept"
    val latest = scope.latest ?: return "Waiting for sample"
    val ageSeconds = (scope.monitorElapsedMillis - latest.elapsedRealtimeMillis) / 1000.0
    val age = WpmFormat.age(ageSeconds)
    return if (scope.stale) "Stale $age" else "Sample $age ago"
}

@Composable
private fun ChartCaption(
    window: ChartWindow,
    frozen: FrozenSnapshot?,
    running: Boolean,
    stale: Boolean,
) {
    val colors = LocalWpmColors.current
    Text(
        text = chartCaptionText(window, frozen, running, stale),
        style = telemetry(10.sp),
        // Ordinary states (frozen, stopped, waiting) are muted, not warning orange.
        color = colors.muted,
        modifier = Modifier.padding(top = 4.dp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One-line chart caption. Every variant is short enough for the landscape toolbar
 * at [LandscapeRules.LABEL_MIN_WIDTH_DP]; a long explanatory clause was clipped
 * mid-word there, so the window wording is the only thing it adds.
 *
 * The chart plots the latest calibrated readings from the visible history, so
 * the caption names the readings and the window; it is never padded with
 * counter-average wording that would disagree with the number above it.
 */
internal fun chartCaptionText(
    window: ChartWindow,
    frozen: FrozenSnapshot?,
    running: Boolean,
    stale: Boolean,
): String {
    if (frozen != null) return "View frozen \u00b7 recording continues"
    if (!running) return "Stopped \u00b7 history retained"
    if (stale) return "Waiting for a new sample"
    return when (window) {
        ChartWindow.FIVE_MINUTES -> "Readings \u00b7 last 5 minutes"
        ChartWindow.THIRTY_MINUTES -> "Readings \u00b7 last 30 minutes"
        ChartWindow.TWO_HOURS -> "Readings \u00b7 last 2 hours"
    }
}

/**
 * Window statistics: three weighted cells, or one per line at a large font scale.
 *
 * The cells already flex, so the only thing that could still move a neighbour is a
 * child that asks for more than its cell. [StatCell] bounds the value and the unit
 * instead; the one-line-per-statistic fallback exists because a 14sp value in a
 * third of a 320dp phone is genuinely too narrow at a large accessibility scale.
 */
@Composable
internal fun WindowStatsRow(stats: StatsView, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    if (ReadoutSlots.statsStackVertically(fontScale)) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatCell("Window average", stats.average, Modifier.fillMaxWidth())
            StatCell("Min", stats.minimum, Modifier.fillMaxWidth())
            StatCell("Max", stats.maximum, Modifier.fillMaxWidth())
        }
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatCell("Window average", stats.average, Modifier.weight(1.3f))
        StatCell("Min", stats.minimum, Modifier.weight(1f))
        StatCell("Max", stats.maximum, Modifier.weight(1f))
    }
}

/**
 * Statistic value with its own magnitude-aware suffix; no suffix when missing.
 *
 * The value slot is weight-filled and the unit slot is a fixed width, so a
 * statistic that becomes available, grows a digit or changes magnitude tier can
 * never move the suffix or the next cell. Both slots keep the same line height, so
 * an empty unit cannot shrink the row either.
 */
@Composable
private fun StatCell(label: String, value: UnitQuantity, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    val fontScale = LocalDensity.current.fontScale
    val lineHeight = ReadoutSlots.statLineHeightDp(fontScale).dp
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(lineHeight),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = value.text,
                    style = telemetry(ReadoutSlots.STAT_VALUE_SP.sp),
                    color = colors.text,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Box(
                modifier = Modifier
                    .width(ReadoutSlots.statUnitSlotWidthDp(fontScale).dp)
                    .height(lineHeight),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (value.available) {
                    Text(
                        text = value.unit,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = ReadoutSlots.STAT_UNIT_SP.sp,
                        ),
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 1.dp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionLinkRow(scope: DashboardScope, actions: DashboardActions) {
    val colors = LocalWpmColors.current
    val summary = scope.state.summary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { actions.viewModel.selectTab(DashboardTab.SESSION) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session",
                style = captionText(),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = WpmFormat.durationHms(summary.elapsedSeconds),
                style = telemetry(11.sp),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = WpmFormat.withUnit(WpmFormat.energy(summary.outWh)) + " out \u2197",
            style = captionText(),
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Direction-word colour. Ordinary charging and discharging are normal text: the
 * amber discharge tint was the same family as the warning colour and made normal
 * battery flow look like a problem. Unavailable/stopped states are muted.
 */
@Composable
private fun flowColor(reading: ReadingView): Color {
    val colors = LocalWpmColors.current
    return when (flowTone(reading)) {
        FlowTone.NORMAL -> colors.text
        FlowTone.MUTED -> colors.muted
    }
}
