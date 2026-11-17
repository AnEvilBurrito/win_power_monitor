package io.github.anevilburrito.wpm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.anevilburrito.wpm.model.MonitorUiState

enum class DashboardTab { MONITOR, SESSION, DIAGNOSTICS }

/** Approved chart ranges; the values are the only window lengths the dashboard offers. */
enum class ChartWindow(val seconds: Int, val label: String) {
    FIVE_MINUTES(300, "5m"),
    THIRTY_MINUTES(1800, "30m"),
    TWO_HOURS(7200, "2h"),
}

/**
 * Which reading of the same samples the plot shows.
 *
 * [LINE] is `what happened when`, [DISTRIBUTION] is `where does the time go`
 * (percent per watt bin). Deliberately *not* a setting: it is a way of looking at
 * data, not something that changes what is recorded, so it is never persisted —
 * the desktop also opens on the line chart every launch.
 */
enum class ChartView { LINE, DISTRIBUTION }

/**
 * A frozen copy of the monitor view. Recording and diagnostics keep running; only
 * the readings/history/statistics shown here are held still and timestamped.
 */
data class FrozenSnapshot(
    val state: MonitorUiState,
    val capturedAtWallMillis: Long,
    val capturedAtElapsedMillis: Long,
)

/**
 * Presentation-only ViewModel so the current tab, chart window, freeze state and
 * dialogs survive configuration changes without touching the sampler or the
 * repository. It collects no telemetry and holds no timers.
 */
class WpmViewModel : ViewModel() {
    var tab by mutableStateOf(DashboardTab.MONITOR)
        private set
    var window by mutableStateOf(ChartWindow.THIRTY_MINUTES)
        private set
    var chartView by mutableStateOf(ChartView.LINE)
        private set
    var frozen by mutableStateOf<FrozenSnapshot?>(null)
        private set
    var settingsOpen by mutableStateOf(false)
        private set
    var highResolutionPending by mutableStateOf(false)
        private set
    var stopConfirmationOpen by mutableStateOf(false)
        private set

    /** The reset/restart confirmation dialog is open. */
    var calibrationResetConfirmOpen by mutableStateOf(false)
        private set

    /** One-shot feedback: the user confirmed a reset/restart this visit. */
    var calibrationResetRequested by mutableStateOf(false)
        private set

    fun selectTab(value: DashboardTab) {
        tab = value
        if (value != DashboardTab.DIAGNOSTICS) calibrationResetRequested = false
    }

    fun selectWindow(value: ChartWindow) {
        window = value
    }

    fun selectChartView(value: ChartView) {
        chartView = value
    }

    fun toggleChartView() {
        chartView = when (chartView) {
            ChartView.LINE -> ChartView.DISTRIBUTION
            ChartView.DISTRIBUTION -> ChartView.LINE
        }
    }

    fun openSettings() {
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

    /** Freezes or resumes the displayed monitor view at [nowWallMillis]. */
    fun toggleFreeze(state: MonitorUiState, nowElapsedMillis: Long, nowWallMillis: Long) {
        frozen = if (frozen != null) {
            null
        } else if (state.running && state.samples.isNotEmpty()) {
            FrozenSnapshot(state, nowWallMillis, nowElapsedMillis)
        } else {
            null
        }
    }

    fun resumeView() {
        frozen = null
    }

    fun requestHighResolution() {
        highResolutionPending = true
    }

    fun cancelHighResolution() {
        highResolutionPending = false
    }

    fun requestStop() {
        stopConfirmationOpen = true
    }

    fun cancelStop() {
        stopConfirmationOpen = false
    }

    fun completeStop() {
        stopConfirmationOpen = false
        frozen = null
    }

    /** Asks for confirmation before discarding the saved/pending calibration. */
    fun requestCalibrationReset() {
        calibrationResetConfirmOpen = true
    }

    fun cancelCalibrationReset() {
        calibrationResetConfirmOpen = false
    }

    /** Confirmed: the host performs the reset, and diagnostics shows the outcome. */
    fun completeCalibrationReset() {
        calibrationResetConfirmOpen = false
        calibrationResetRequested = true
    }
}
