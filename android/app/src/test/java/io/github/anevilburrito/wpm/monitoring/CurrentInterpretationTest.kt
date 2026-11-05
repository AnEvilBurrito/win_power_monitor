package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.AppTheme
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitoringMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effective current interpretation and the recording discontinuity it triggers.
 *
 * These tests pin the rule the sampler relies on: two samples are comparable only
 * when the **resolved** scale and sign agree. The resolution is manual override
 * first, saved calibration second, documented microamp/non-inverted last — never a
 * decoded sample's `currentUnitAdjusted`/`currentPolarityAdjusted` flags, because
 * those are false for a zero reading that is nonetheless read differently after a
 * change.
 */
class CurrentInterpretationTest {

    private val establishedMicroamps = CurrentCalibration(
        unit = CurrentUnitOverride.MICROAMPS,
        inverted = false,
        evidenceWindows = 3,
    )

    private val establishedMilliampsInverted = CurrentCalibration(
        unit = CurrentUnitOverride.MILLIAMPS,
        inverted = true,
        evidenceWindows = 3,
    )

    private fun interpretation(
        currentUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        currentPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
        calibration: CurrentCalibration = CurrentCalibration(),
        theme: AppTheme = AppTheme.STORM,
        absolute: Boolean = true,
        designCapacityMilliampHours: Double? = null,
        intervalSeconds: Int = MonitorSettings.DEFAULT_INTERVAL_SECONDS,
        mode: MonitoringMode = MonitoringMode.LOW_IMPACT,
    ): CurrentInterpretation = CurrentInterpretation.of(
        MonitorSettings(
            intervalSeconds = intervalSeconds,
            mode = mode,
            theme = theme,
            absolute = absolute,
            currentUnit = currentUnit,
            currentPolarity = currentPolarity,
            designCapacityMilliampHours = designCapacityMilliampHours,
        ),
        calibration,
    )

    // --- Effective interpretation ------------------------------------------

    @Test
    fun nothingEstablishedResolvesToTheDocumentedMicroampsAndSign() {
        assertEquals(CurrentInterpretation.DEFAULT, interpretation())
        assertEquals(CurrentUnitOverride.MICROAMPS, interpretation().unit)
        assertFalse(interpretation().inverted)
    }

    @Test
    fun implicitAndExplicitMicroampsAreTheSameInterpretation() {
        // AUTO is the decoder's marker for "documented microamps", so switching the
        // manual override between Auto and µA changes nothing about the numbers.
        assertEquals(
            interpretation(currentUnit = CurrentUnitOverride.AUTO),
            interpretation(currentUnit = CurrentUnitOverride.MICROAMPS),
        )
    }

    @Test
    fun autoFallsBackToTheSavedCalibration() {
        assertEquals(
            CurrentInterpretation(CurrentUnitOverride.MILLIAMPS, inverted = true),
            interpretation(calibration = establishedMilliampsInverted),
        )
    }

    @Test
    fun manualCorrectionsOutrankTheSavedCalibration() {
        // Manual µA/standard masks a saved mA/inverted calibration entirely.
        assertEquals(
            CurrentInterpretation.DEFAULT,
            interpretation(
                currentUnit = CurrentUnitOverride.MICROAMPS,
                currentPolarity = CurrentPolarityOverride.STANDARD,
                calibration = establishedMilliampsInverted,
            ),
        )
    }

    @Test
    fun manualCorrectionAndAnIdenticalCalibrationAreTheSameInterpretation() {
        assertEquals(
            interpretation(
                currentUnit = CurrentUnitOverride.MILLIAMPS,
                currentPolarity = CurrentPolarityOverride.INVERTED,
            ),
            interpretation(calibration = establishedMilliampsInverted),
        )
    }

    @Test
    fun scaleAndSignAreIndependentDimensions() {
        assertNotEquals(
            interpretation(),
            interpretation(currentUnit = CurrentUnitOverride.MILLIAMPS),
        )
        assertNotEquals(
            interpretation(),
            interpretation(currentPolarity = CurrentPolarityOverride.INVERTED),
        )
    }

    @Test
    fun displayAndCadenceSettingsNeverChangeTheInterpretation() {
        assertEquals(
            interpretation(),
            interpretation(
                theme = AppTheme.LATTE,
                absolute = false,
                designCapacityMilliampHours = 4_321.0,
                intervalSeconds = 30,
                mode = MonitoringMode.HIGH_RESOLUTION,
            ),
        )
    }

    // --- Transition tracker -------------------------------------------------

    @Test
    fun firstObservationIsABaselineNotATransition() {
        val tracker = CurrentInterpretationTracker()
        assertNull(tracker.last)
        assertFalse(
            tracker.observe(interpretation(calibration = establishedMilliampsInverted)),
        )
        assertEquals(
            CurrentInterpretation(CurrentUnitOverride.MILLIAMPS, inverted = true),
            tracker.last,
        )
    }

    @Test
    fun unchangedInterpretationIsNeverATransition() {
        val tracker = CurrentInterpretationTracker()
        val current = interpretation()
        assertFalse(tracker.observe(current))
        assertFalse(tracker.observe(current))
        // Establishing exactly the documented interpretation adds evidence, but the
        // watts it produces are unchanged, so nothing is discontinuous.
        assertFalse(tracker.observe(interpretation(calibration = establishedMicroamps)))
        assertFalse(tracker.observe(interpretation()))
    }

    @Test
    fun aScaleChangeIsATransitionExactlyOnce() {
        val tracker = CurrentInterpretationTracker()
        assertFalse(tracker.observe(interpretation()))
        assertTrue(
            tracker.observe(interpretation(calibration = establishedMilliampsInverted)),
        )
        assertFalse(
            tracker.observe(interpretation(calibration = establishedMilliampsInverted)),
        )
    }

    @Test
    fun aManualPolarityChangeIsATransition() {
        val tracker = CurrentInterpretationTracker()
        assertFalse(tracker.observe(interpretation()))
        assertTrue(tracker.observe(interpretation(currentPolarity = CurrentPolarityOverride.INVERTED)))
    }

    @Test
    fun addingAManualCorrectionThatOverridesTheCalibrationIsATransition() {
        val tracker = CurrentInterpretationTracker()
        assertFalse(tracker.observe(interpretation(calibration = establishedMilliampsInverted)))
        // Manual µA/standard now masks the saved mA/inverted calibration.
        assertTrue(
            tracker.observe(
                interpretation(
                    currentUnit = CurrentUnitOverride.MICROAMPS,
                    currentPolarity = CurrentPolarityOverride.STANDARD,
                    calibration = establishedMilliampsInverted,
                ),
            ),
        )
    }

    @Test
    fun resettingASavedCalibrationIsATransitionOnlyWhenTheReadingChanges() {
        // An established mA/inverted calibration is reset: the same raw value now
        // means µA/non-inverted, so the next sample starts a new segment.
        val changing = CurrentInterpretationTracker()
        assertFalse(changing.observe(interpretation(calibration = establishedMilliampsInverted)))
        assertTrue(changing.observe(interpretation()))

        // Resetting a calibration that already resolved to the documented reading
        // changes provenance only, so nothing is discontinuous.
        val unchanged = CurrentInterpretationTracker()
        assertFalse(unchanged.observe(interpretation(calibration = establishedMicroamps)))
        assertFalse(unchanged.observe(interpretation()))
    }

    @Test
    fun trackingResetsAtTheRecordingBoundary() {
        val tracker = CurrentInterpretationTracker()
        assertFalse(tracker.observe(interpretation()))
        assertTrue(
            tracker.observe(interpretation(calibration = establishedMilliampsInverted)),
        )
        tracker.reset()
        assertNull(tracker.last)
        // The same value that was a transition in the previous recording is just the
        // baseline of this one.
        assertFalse(
            tracker.observe(interpretation(calibration = establishedMilliampsInverted)),
        )
        assertTrue(tracker.observe(interpretation()))
    }

    // --- Zero readings and flags -------------------------------------------

    @Test
    fun aZeroReadingCannotHideAnInterpretationChange() {
        val raw = RawBatteryReading(
            timestampMillis = 1_000L,
            elapsedRealtimeMillis = 1_000L,
            currentMicroamps = 0L,
            voltageMillivolts = 4_000,
        )
        val before = BatteryTelemetryDecoder.decode(raw, calibration = CurrentCalibration())
        val after = BatteryTelemetryDecoder.decode(
            raw,
            calibration = establishedMilliampsInverted,
        )
        // Both provenance flags are false: nothing nonzero was rescaled or flipped.
        assertFalse(before.currentUnitAdjusted)
        assertFalse(before.currentPolarityAdjusted)
        assertFalse(after.currentUnitAdjusted)
        assertFalse(after.currentPolarityAdjusted)
        // The effective interpretation still differs, and that is what the sample
        // boundary must follow.
        val tracker = CurrentInterpretationTracker()
        assertFalse(tracker.observe(CurrentInterpretation.of(MonitorSettings(), CurrentCalibration())))
        assertTrue(
            tracker.observe(
                CurrentInterpretation.of(MonitorSettings(), establishedMilliampsInverted),
            ),
        )
    }

    // --- Stored reason token ------------------------------------------------

    @Test
    fun theCalibrationChangeTokenHasItsOwnHumanLabel() {
        val token = SampleReasons.CURRENT_CALIBRATION_CHANGED
        val label = SampleReasons.label(token)
        assertNotNull(label)
        assertNotEquals(token, label)
        assertFalse(label!!.contains("_"))
    }
}
