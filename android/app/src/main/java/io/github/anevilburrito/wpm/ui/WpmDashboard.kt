package io.github.anevilburrito.wpm.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitorUiState
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.SessionSummary
import io.github.anevilburrito.wpm.model.forLiveDisplay
import io.github.anevilburrito.wpm.model.isSampleStale
import io.github.anevilburrito.wpm.model.summarizeWindow
import io.github.anevilburrito.wpm.monitoring.SampleText
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay

/** Callbacks owned by the host activity (permissions, service start/stop, export). */
internal class DashboardActions(
    val viewModel: WpmViewModel,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onSettingsChange: (MonitorSettings) -> Unit,
    val onExport: () -> Unit,
    /** Host-provided: clears the published and persisted automatic calibration. */
    val onResetCalibration: () -> Unit,
)

/** Derived, already-transformed values shared by every screen of one composition. */
internal data class DashboardScope(
    val state: MonitorUiState,
    val monitorState: MonitorUiState,
    val settings: MonitorSettings,
    val tab: DashboardTab,
    val window: ChartWindow,
    val frozen: FrozenSnapshot?,
    val latest: BatterySample?,
    /**
     * The displayed samples converted **once** for live display
     * ([forLiveDisplay]): `amps`/`watts`/`flow` are the calibrated instantaneous
     * CURRENT_NOW reading. The readout, the chart, the axis unit and the window
     * statistics all read this list; [monitorState] keeps the raw samples for the
     * developer diagnostics, the counter-average runtime and the export.
     */
    val liveSamples: List<BatterySample>,
    val windowSummary: SessionSummary,
    val stale: Boolean,
    val liveElapsedMillis: Long,
    val monitorElapsedMillis: Long,
)

/**
 * Approved native dashboard. The host supplies repository state and settings and
 * owns Android permissions, service control and CSV export; this composable only
 * renders and reports intent. Presentation state (tab, window, freeze, dialogs)
 * lives in a ViewModel so rotation keeps it.
 */
@Composable
fun WpmDashboard(
    state: MonitorUiState,
    settings: MonitorSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettingsChange: (MonitorSettings) -> Unit,
    onExport: () -> Unit,
    // Required: the host wires this to `repository.resetCurrentCalibration()`. A
    // default no-op made a wired-looking reset button that silently did nothing.
    onResetCalibration: () -> Unit,
) {
    val viewModel: WpmViewModel = viewModel()
    val normalized = settings.normalized()
    // A freeze only describes a running session; an ended session reverts to its
    // stopped, timestamped history instead of looking live.
    LaunchedEffect(state.running) {
        if (!state.running) viewModel.resumeView()
    }
    WpmTheme(normalized.theme) {
        val colors = LocalWpmColors.current
        val frozen = viewModel.frozen?.takeIf { state.running }
        val monitorState = frozen?.state ?: state
        val liveElapsed = rememberMonotonicMillis(
            running = state.running,
            frozenStamp = frozen?.capturedAtWallMillis,
            // A frozen monitor view has nothing to redraw. Diagnostics reports the
            // live age of the live sample, so it keeps the ticker while visible.
            tickerActive = frozen == null || viewModel.tab == DashboardTab.DIAGNOSTICS,
        )
        val monitorElapsed = frozen?.capturedAtElapsedMillis ?: liveElapsed
        val latest = monitorState.samples.lastOrNull()
        val stale = isSampleStale(latest, monitorElapsed, normalized.intervalSeconds)
        // Project only the visible window, not all retained UI samples
        // into new objects on every poll. Chart, axis and window statistics share
        // this calibrated-live projection; raw history stays unchanged for export
        // and for the separately averaged runtime.
        val liveSamples = remember(monitorState.samples, viewModel.window) {
            val end = latest?.elapsedRealtimeMillis
            if (end == null) emptyList() else {
                val start = end - viewModel.window.seconds * 1_000L
                monitorState.samples.asSequence()
                    .filter { it.elapsedRealtimeMillis in start..end }
                    .map { it.forLiveDisplay() }
                    .toList()
            }
        }
        val windowSummary = remember(liveSamples, viewModel.window, normalized.intervalSeconds) {
            summarizeWindow(liveSamples, viewModel.window.seconds, normalized.intervalSeconds)
        }
        val scope = DashboardScope(
            state = state,
            monitorState = monitorState,
            settings = normalized,
            tab = viewModel.tab,
            window = viewModel.window,
            frozen = frozen,
            latest = latest,
            liveSamples = liveSamples,
            windowSummary = windowSummary,
            stale = stale,
            liveElapsedMillis = liveElapsed,
            monitorElapsedMillis = monitorElapsed,
        )
        val actions = DashboardActions(
            viewModel,
            onStart,
            onStop,
            onSettingsChange,
            onExport,
            onResetCalibration,
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                // Children must not inherit fill-width minimum constraints from this box.
                propagateMinConstraints = false,
            ) {
                val widthDp = maxWidth.value.roundToInt()
                val heightDp = maxHeight.value.roundToInt()
                if (LandscapeRules.isLandscape(widthDp, heightDp)) {
                    LandscapeDashboard(scope, actions, widthDp, heightDp)
                } else {
                    PortraitDashboard(scope, actions)
                }
            }
            if (viewModel.settingsOpen) {
                SettingsDialog(
                    settings = normalized,
                    onDismiss = viewModel::closeSettings,
                    onSettingsChange = onSettingsChange,
                    onRequestHighResolution = viewModel::requestHighResolution,
                )
            }
            if (viewModel.highResolutionPending) {
                HighResolutionDialog(
                    onConfirm = {
                        viewModel.cancelHighResolution()
                        onSettingsChange(normalized.copy(mode = MonitoringMode.HIGH_RESOLUTION))
                    },
                    onDismiss = viewModel::cancelHighResolution,
                )
            }
            if (viewModel.stopConfirmationOpen) {
                StopRecordingDialog(
                    onConfirm = {
                        viewModel.completeStop()
                        onStop()
                    },
                    onDismiss = viewModel::cancelStop,
                )
            }
            if (viewModel.calibrationResetConfirmOpen) {
                ResetCalibrationDialog(
                    onConfirm = {
                        viewModel.completeCalibrationReset()
                        onResetCalibration()
                    },
                    onDismiss = viewModel::cancelCalibrationReset,
                )
            }
        }
    }
}

/**
 * Monotonic "now" used for reading age and staleness.
 *
 * It is refreshed when visible data changes, when the window resumes, and by a
 * coarse ticker while a recording is on screen. Without the ticker the age was
 * recomputed only when a new sample arrived, so it always read `0s` and a wedged
 * service could never be reported as stale. The ticker is intentionally slow (one
 * update per few seconds, no per-frame animation) so the age line stays stable
 * instead of blinking.
 *
 * The ticker is gated three ways, because it was previously a plain `while(true)`
 * that kept waking the composition every few seconds while the app was in the
 * background:
 *
 *  - only while recording;
 *  - only while nothing being displayed is frozen ([tickerActive]);
 *  - only while the host lifecycle is at least STARTED. Leaving the app stops it,
 *    and returning refreshes `now` once immediately instead of waiting a period.
 */
@Composable
private fun rememberMonotonicMillis(
    running: Boolean,
    frozenStamp: Long?,
    tickerActive: Boolean,
): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(running, frozenStamp, tickerActive, lifecycleOwner) {
        if (!running || !tickerActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = SystemClock.elapsedRealtime()
            while (true) {
                delay(STALENESS_TICK_MILLIS)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        now = SystemClock.elapsedRealtime()
        onPauseOrDispose { }
    }
    // A poll itself recomposes the UI. Read the clock in that same composition,
    // not in a later effect: the previous tick may precede the new sample and
    // otherwise falsely mark it stale. The ticker remains lifecycle-gated above.
    return freshnessClockMillis(now, SystemClock.elapsedRealtime())
}

/** Age/staleness refresh period; well inside every supported stale threshold. */
private const val STALENESS_TICK_MILLIS = 5_000L

@Composable
private fun PortraitDashboard(scope: DashboardScope, actions: DashboardActions) {
    Column(Modifier.fillMaxSize()) {
        AppBar(scope, actions, showStatus = false)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (scope.tab) {
                DashboardTab.MONITOR -> MonitorPortrait(scope, actions)
                DashboardTab.SESSION -> SessionScreen(scope, actions)
                DashboardTab.DIAGNOSTICS -> DiagnosticsScreen(scope, actions)
            }
        }
        RecordingControls(scope, actions)
        BottomNav(scope, actions, horizontal = false)
    }
}

@Composable
private fun LandscapeDashboard(
    scope: DashboardScope,
    actions: DashboardActions,
    widthDp: Int,
    heightDp: Int,
) {
    val fontScale = LocalDensity.current.fontScale
    // A flexible chart only exists when the surrounding chrome (including a possible
    // two-line recording-error notice and the axis/legend under the canvas) leaves
    // the canvas at least LandscapeRules.PLOT_MIN_HEIGHT_DP; otherwise the detail
    // scrolls at a fixed chart height instead of compressing every row towards zero.
    // Only a real recording-level error takes that notice: a per-sample reading note
    // would appear and vanish on almost every update.
    val hasNotice = scope.tab == DashboardTab.MONITOR && dashboardError(scope.state.error) != null
    val scrollDetail = LandscapeRules.needsScrolling(widthDp, heightDp, fontScale, hasNotice)
    Column(Modifier.fillMaxSize()) {
        AppBar(scope, actions)
        if (scope.tab == DashboardTab.MONITOR) {
            MonitorLandscapeContent(scope, actions, widthDp, scrollDetail)
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (scope.tab) {
                    DashboardTab.SESSION -> SessionScreen(scope, actions)
                    DashboardTab.DIAGNOSTICS -> DiagnosticsScreen(scope, actions)
                    DashboardTab.MONITOR -> Unit
                }
            }
        }
        LandscapeDeck(scope, actions, widthDp)
    }
}

/**
 * Landscape monitor: fixed readings strip, fixed toolbar, flexible plot, fixed deck.
 *
 * Nothing here relies on a Row distributing space to a squeezed label: the strip and
 * the toolbar have minimum heights, the label has a minimum width and single-line
 * text, and the plot is the only child that gives space away.
 */
@Composable
private fun ColumnScope.MonitorLandscapeContent(
    scope: DashboardScope,
    actions: DashboardActions,
    widthDp: Int,
    scrollDetail: Boolean,
) {
    // The minimum is the whole ChartArea (canvas + axis + legend); the canvas
    // minimum inside it stays LandscapeRules.PLOT_MIN_HEIGHT_DP.
    val chartModifier = if (scrollDetail) {
        Modifier
            .fillMaxWidth()
            .height(LandscapeRules.SCROLL_FALLBACK_CHART_HEIGHT_DP.dp)
    } else {
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .heightIn(min = LandscapeRules.CHART_MIN_HEIGHT_DP.dp)
    }
    val content: @Composable () -> Unit = {
        LandscapeReadingsStrip(scope, widthDp)
        ChartFullToolbar(scope, actions, widthDp)
            ChartArea(
                modifier = chartModifier.padding(horizontal = 12.dp),
                samples = scope.liveSamples,
                windowSeconds = scope.window.seconds,
                intervalSeconds = scope.settings.intervalSeconds,
                absolute = scope.settings.absolute,
                showLegend = true,
            )
    }
    if (scrollDetail) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    } else {
        content()
    }
}

/** One toolbar: brand, live recording state and the settings entry. */
@Composable
private fun AppBar(scope: DashboardScope, actions: DashboardActions, showStatus: Boolean = true) {
    val colors = LocalWpmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "wpm",
                style = telemetry(24.sp, FontWeight.Bold),
                color = colors.text,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "_",
                style = telemetry(24.sp, FontWeight.Bold),
                color = colors.trace,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "BATTERY FLOW",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (showStatus) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val statusColor = when {
                    scope.frozen != null -> colors.trace
                    scope.state.running -> colors.trace
                    else -> colors.muted
                }
                WpmStatusDot(statusColor)
                Text(
                    text = when {
                        scope.frozen != null -> "Frozen view"
                        scope.state.running -> "Recording"
                        else -> "Stopped"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.text,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = actions.viewModel::openSettings),
            contentAlignment = Alignment.Center,
        ) {
            WpmIconView(WpmIcon.SETTINGS, Modifier.size(22.dp), tint = colors.muted)
        }
    }
    HorizontalDivider(color = colors.rule.copy(alpha = 0.5f), thickness = 1.dp)
}

/** Start/Stop control and the background-policy hint, fixed above navigation. */
@Composable
private fun RecordingControls(scope: DashboardScope, actions: DashboardActions) {
    val colors = LocalWpmColors.current
    val policy = policyView(scope.state.running, scope.settings, scope.state.wakeLockHeld)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (scope.state.running) {
            WpmSecondaryButton(
                text = "Stop recording",
                onClick = actions.viewModel::requestStop,
                modifier = Modifier.fillMaxWidth(),
                icon = WpmIcon.STOP,
            )
        } else {
            WpmPrimaryButton(
                text = "Start recording",
                onClick = actions.onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = recordingNote(policy, scope.state.running),
            style = captionText(),
            color = colors.muted,
        )
    }
}

/** Three-destination navigation; either the portrait bar or a slim landscape row. */
@Composable
private fun BottomNav(scope: DashboardScope, actions: DashboardActions, horizontal: Boolean) {
    val colors = LocalWpmColors.current
    Column {
        HorizontalDivider(color = colors.rule.copy(alpha = 0.5f), thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavButton(DashboardTab.MONITOR, "Monitor", WpmIcon.CHART, scope, actions, horizontal, Modifier.weight(1f))
            NavButton(DashboardTab.SESSION, "Session", WpmIcon.SESSION, scope, actions, horizontal, Modifier.weight(1f))
            NavButton(DashboardTab.DIAGNOSTICS, "Diagnostics", WpmIcon.DIAGNOSTICS, scope, actions, horizontal, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavButton(
    tab: DashboardTab,
    label: String,
    icon: WpmIcon,
    scope: DashboardScope,
    actions: DashboardActions,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWpmColors.current
    val selected = scope.tab == tab
    val tint = if (selected) colors.trace else colors.muted
    val content: @Composable () -> Unit = {
        WpmIconView(icon, Modifier.size(if (horizontal) 20.dp else 22.dp), tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = if (horizontal) 10.sp else 9.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
    val click = { actions.viewModel.selectTab(tab) }
    if (horizontal) {
        Row(
            modifier = modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(7.dp))
                .selectable(selected = selected, role = Role.Tab, onClick = click)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    } else {
        Column(
            modifier = modifier
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(7.dp))
                .selectable(selected = selected, role = Role.Tab, onClick = click)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            content()
        }
    }
}

/** Slim bottom deck for the landscape composition: nav, session context, Stop. */
@Composable
private fun LandscapeDeck(
    scope: DashboardScope,
    actions: DashboardActions,
    widthDp: Int,
) {
    val colors = LocalWpmColors.current
    val summary = scope.state.summary
    val wide = widthDp >= 640
    val energy = WpmFormat.energy(summary.outWh)
    Column {
        HorizontalDivider(color = colors.rule.copy(alpha = 0.5f), thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.weight(1.5f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavButton(DashboardTab.MONITOR, "Monitor", WpmIcon.CHART, scope, actions, true, Modifier.weight(1f))
                NavButton(DashboardTab.SESSION, "Session", WpmIcon.SESSION, scope, actions, true, Modifier.weight(1f))
                NavButton(DashboardTab.DIAGNOSTICS, "Diagnostics", WpmIcon.DIAGNOSTICS, scope, actions, true, Modifier.weight(1f))
            }
            if (wide) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { actions.viewModel.selectTab(DashboardTab.SESSION) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Session " + WpmFormat.durationHms(summary.elapsedSeconds),
                        style = telemetry(11.sp),
                        color = colors.text,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = WpmFormat.withUnit(energy) + " out",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = colors.muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Text(
                text = when {
                    !scope.state.running -> "Stopped"
                    else -> "Recording"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
            if (scope.state.running) {
                WpmSecondaryButton(
                    text = "Stop",
                    onClick = actions.viewModel::requestStop,
                    icon = WpmIcon.STOP,
                )
            } else {
                WpmPrimaryButton(text = "Start", onClick = actions.onStart)
            }
        }
    }
}

/**
 * Reading presentation for this composition.
 *
 * [latest] is the raw sample; [liveReadingView] derives the calibrated-live copy
 * once. The counter average is deliberately not consulted: a saved or manual
 * calibration shows the reading immediately, before any 60 s average exists.
 */
@Composable
internal fun DashboardScope.reading(): ReadingView =
    liveReadingView(latest, state.running, stale, settings.absolute)

/**
 * Warning/attention banner shared by the screens; never fabricated.
 *
 * [maxLines] bounds the height where the landscape budget needs it; the landscape
 * notice is capped at the two lines [LandscapeRules.NOTICE_HEIGHT_DP] pays for.
 */
@Composable
internal fun AttentionNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = LocalWpmColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(width = 2.dp, height = 14.dp)
                .background(color ?: colors.warning),
        )
        Text(
            text = text,
            style = bodyText(),
            color = color ?: colors.warning,
            modifier = Modifier.weight(1f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
