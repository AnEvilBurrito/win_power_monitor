package io.github.anevilburrito.wpm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation state for the calibration reset/restart flow.
 *
 * The button only *asks* for a reset; the host callback runs after confirmation,
 * so a stray tap can never discard a calibrated scale or pending evidence.
 */
class WpmViewModelTest {
    @Test fun resetOnlyRunsAfterTheConfirmationDialog() {
        val viewModel = WpmViewModel()
        assertFalse(viewModel.calibrationResetConfirmOpen)

        viewModel.requestCalibrationReset()
        assertTrue(viewModel.calibrationResetConfirmOpen)
        // Nothing has happened yet: the host callback is only wired to confirm.
        assertFalse(viewModel.calibrationResetRequested)

        viewModel.cancelCalibrationReset()
        assertFalse(viewModel.calibrationResetConfirmOpen)
        assertFalse(viewModel.calibrationResetRequested)

        viewModel.requestCalibrationReset()
        viewModel.completeCalibrationReset()
        assertFalse("the dialog closes on confirm", viewModel.calibrationResetConfirmOpen)
        assertTrue("the outcome is reported in diagnostics", viewModel.calibrationResetRequested)
    }

    @Test fun leavingDiagnosticsClearsTheResetFeedback() {
        val viewModel = WpmViewModel()
        viewModel.selectTab(DashboardTab.DIAGNOSTICS)
        viewModel.requestCalibrationReset()
        viewModel.completeCalibrationReset()
        assertTrue(viewModel.calibrationResetRequested)

        viewModel.selectTab(DashboardTab.MONITOR)
        assertFalse(viewModel.calibrationResetRequested)

        // Re-opening diagnostics shows the neutral state note, not stale feedback.
        viewModel.selectTab(DashboardTab.DIAGNOSTICS)
        assertFalse(viewModel.calibrationResetRequested)
    }

    @Test fun stoppingRecordingDropsAFrozenView() {
        val viewModel = WpmViewModel()
        viewModel.requestStop()
        assertTrue(viewModel.stopConfirmationOpen)
        viewModel.completeStop()
        assertFalse(viewModel.stopConfirmationOpen)
        assertNull("a stopped session is shown live, not frozen", viewModel.frozen)
        assertEquals(ChartWindow.THIRTY_MINUTES, viewModel.window)
    }
}
