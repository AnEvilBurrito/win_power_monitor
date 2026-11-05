package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTelemetryDecoderTest {

    private fun raw(
        current: Long? = null,
        average: Long? = null,
        chargeCounter: Long? = null,
        voltage: Int? = null,
        level: Int? = null,
        scale: Int? = null,
        temperature: Int? = null,
        plugged: Int? = null,
        status: Int? = null,
        health: Int? = null,
        cycleCount: Int? = null,
    ) = RawBatteryReading(
        timestampMillis = 1_000_000L,
        elapsedRealtimeMillis = 500_000L,
        currentMicroamps = current,
        averageMicroamps = average,
        chargeCounterMicroampHours = chargeCounter,
        voltageMillivolts = voltage,
        level = level,
        scale = scale,
        temperatureTenths = temperature,
        plugged = plugged,
        status = status,
        health = health,
        cycleCount = cycleCount,
    )

    @Test
    fun microampsConvertToSignedAmperes() {
        assertEquals(1.5, BatteryTelemetryDecoder.amperesOf(1_500_000)!!, 1e-12)
        assertEquals(-2.0, BatteryTelemetryDecoder.amperesOf(-2_000_000)!!, 1e-12)
        assertEquals(0.0, BatteryTelemetryDecoder.amperesOf(0)!!, 0.0)
        assertNull(BatteryTelemetryDecoder.amperesOf(null))
    }

    @Test
    fun unsupportedCurrentSentinelsBecomeMissing() {
        assertNull(BatteryTelemetryDecoder.amperesOf(BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL))
        assertNull(BatteryTelemetryDecoder.amperesOf(Long.MIN_VALUE))
        assertNull(BatteryTelemetryDecoder.amperesOf(Int.MIN_VALUE.toLong()))
    }

    @Test
    fun everyUnsupportedSentinelIsSanitizedConsistently() {
        for (sentinel in listOf(Long.MIN_VALUE, BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL)) {
            assertTrue(BatteryTelemetryDecoder.isUnsupportedCurrent(sentinel))
            assertNull(BatteryTelemetryDecoder.supportedCurrentOf(sentinel))
            assertNull(BatteryTelemetryDecoder.amperesOf(sentinel))
            // Never a false discharge: without a status the word stays unknown.
            assertEquals(FlowState.UNKNOWN, BatteryTelemetryDecoder.flowOf(sentinel, null))
            // The status may still supply the *word*.
            assertEquals(
                FlowState.CHARGING,
                BatteryTelemetryDecoder.flowOf(sentinel, BatteryTelemetryDecoder.STATUS_CHARGING),
            )
            assertFalse(
                BatteryTelemetryDecoder.hasInconsistentPolarity(
                    sentinel,
                    BatteryTelemetryDecoder.PLUGGED_NONE,
                    BatteryTelemetryDecoder.STATUS_DISCHARGING,
                ),
            )
            val sample = BatteryTelemetryDecoder.decode(
                raw(current = sentinel, voltage = 4000, plugged = 0, status = null),
            )
            assertNull(sample.currentMicroamps)
            assertNull(sample.amps)
            assertNull(sample.watts)
            assertNull(sample.reason)
            assertEquals(FlowState.UNKNOWN, sample.flow)
            assertEquals(4.0, sample.volts!!, 1e-12)
        }
        // Zero is a real reading, not a sentinel.
        assertFalse(BatteryTelemetryDecoder.isUnsupportedCurrent(0))
        assertEquals(0L, BatteryTelemetryDecoder.supportedCurrentOf(0))
    }

    @Test
    fun zeroCurrentIsAReadingNotAMissingValue() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(current = 0, voltage = 4000, plugged = 0, status = BatteryTelemetryDecoder.STATUS_NOT_CHARGING),
        )
        assertEquals(0.0, sample.amps!!, 0.0)
        assertEquals(0.0, sample.watts!!, 0.0)
        assertEquals(FlowState.IDLE, sample.flow)
        assertEquals(0L, sample.currentMicroamps)
    }

    @Test
    fun millivoltsConvertToVoltsAndNonpositiveVoltagesAreUnusable() {
        assertEquals(4.2, BatteryTelemetryDecoder.voltsOf(4200)!!, 1e-12)
        assertNull(BatteryTelemetryDecoder.voltsOf(0))
        assertNull(BatteryTelemetryDecoder.voltsOf(-1200))
        assertNull(BatteryTelemetryDecoder.voltsOf(null))
    }

    @Test
    fun wattsAreAmperesTimesVoltsOrMissing() {
        assertEquals(6.0, BatteryTelemetryDecoder.wattsOf(1.5, 4.0)!!, 1e-12)
        assertNull(BatteryTelemetryDecoder.wattsOf(null, 4.0))
        assertNull(BatteryTelemetryDecoder.wattsOf(1.5, null))
        assertNull(BatteryTelemetryDecoder.wattsOf(Double.NaN, 4.0))
    }

    @Test
    fun percentageHandlesSentinelsScaleAndRounding() {
        assertEquals(50, BatteryTelemetryDecoder.percentageOf(50, 100))
        assertEquals(33, BatteryTelemetryDecoder.percentageOf(1, 3))
        assertEquals(100, BatteryTelemetryDecoder.percentageOf(100, 100))
        assertEquals(0, BatteryTelemetryDecoder.percentageOf(0, 100))
        assertNull(BatteryTelemetryDecoder.percentageOf(-1, 100))
        assertNull(BatteryTelemetryDecoder.percentageOf(101, 100))
        assertNull(BatteryTelemetryDecoder.percentageOf(50, 0))
        assertNull(BatteryTelemetryDecoder.percentageOf(null, 100))
        assertNull(BatteryTelemetryDecoder.percentageOf(50, null))
    }

    @Test
    fun temperatureTenthsAreValidated() {
        assertEquals(30.5, BatteryTelemetryDecoder.celsiusOf(305)!!, 1e-12)
        assertEquals(-40.0, BatteryTelemetryDecoder.celsiusOf(-400)!!, 1e-12)
        assertNull(BatteryTelemetryDecoder.celsiusOf(Int.MIN_VALUE))
        assertNull(BatteryTelemetryDecoder.celsiusOf(1200))
        assertNull(BatteryTelemetryDecoder.celsiusOf(-600))
        assertNull(BatteryTelemetryDecoder.celsiusOf(null))
    }

    @Test
    fun flowFollowsSignedCurrentThenFallsBackToStatusForTheWordOnly() {
        assertEquals(FlowState.CHARGING, BatteryTelemetryDecoder.flowOf(500_000, null))
        assertEquals(FlowState.DISCHARGING, BatteryTelemetryDecoder.flowOf(-500_000, null))
        assertEquals(FlowState.IDLE, BatteryTelemetryDecoder.flowOf(0, BatteryTelemetryDecoder.STATUS_CHARGING))
        assertEquals(
            FlowState.CHARGING,
            BatteryTelemetryDecoder.flowOf(null, BatteryTelemetryDecoder.STATUS_CHARGING),
        )
        assertEquals(
            FlowState.DISCHARGING,
            BatteryTelemetryDecoder.flowOf(null, BatteryTelemetryDecoder.STATUS_DISCHARGING),
        )
        assertEquals(
            FlowState.IDLE,
            BatteryTelemetryDecoder.flowOf(null, BatteryTelemetryDecoder.STATUS_FULL),
        )
        assertEquals(FlowState.UNKNOWN, BatteryTelemetryDecoder.flowOf(null, null))
    }

    @Test
    fun positiveCurrentWhileUnpluggedIsInconsistent() {
        assertTrue(
            BatteryTelemetryDecoder.hasInconsistentPolarity(
                1_000_000,
                BatteryTelemetryDecoder.PLUGGED_NONE,
                BatteryTelemetryDecoder.STATUS_DISCHARGING,
            ),
        )
        assertTrue(
            BatteryTelemetryDecoder.hasInconsistentPolarity(
                1_000_000,
                BatteryTelemetryDecoder.PLUGGED_NONE,
                null,
            ),
        )
        // Negative current with a connected charger can be real net discharge.
        assertFalse(
            BatteryTelemetryDecoder.hasInconsistentPolarity(
                -1_000_000,
                2,
                BatteryTelemetryDecoder.STATUS_CHARGING,
            ),
        )
        // Positive current while charging is consistent.
        assertFalse(
            BatteryTelemetryDecoder.hasInconsistentPolarity(
                900_000,
                2,
                BatteryTelemetryDecoder.STATUS_CHARGING,
            ),
        )
        // No evidence either way: never suppress.
        assertFalse(BatteryTelemetryDecoder.hasInconsistentPolarity(900_000, null, null))
        assertFalse(BatteryTelemetryDecoder.hasInconsistentPolarity(null, 0, BatteryTelemetryDecoder.STATUS_DISCHARGING))
    }

    @Test
    fun inconsistentPolaritySuppressesWattsWithAReasonAndKeepsRawValues() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                current = 1_200_000,
                voltage = 4100,
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
            ),
        )
        assertNull(sample.watts)
        assertEquals(SampleReasons.INCONSISTENT_POLARITY, sample.reason)
        assertEquals(1.2, sample.amps!!, 1e-12)
        assertEquals(4.1, sample.volts!!, 1e-12)
        assertEquals(1_200_000L, sample.currentMicroamps)
        assertEquals(4100, sample.voltageMillivolts)
        // Positive current contradicting the reported state is not trusted as
        // charging; the word must stay unknown rather than confidently wrong.
        assertEquals(FlowState.UNKNOWN, sample.flow)
        // Nothing established the interpretation, so it is not claimed calibrated.
        assertFalse(sample.currentCalibrated)
    }

    @Test
    fun decodeKeepsMissingFieldsIndependentlyNullable() {
        val sample = BatteryTelemetryDecoder.decode(raw(level = 42, scale = 100))
        assertEquals(42, sample.percentage)
        assertNull(sample.watts)
        assertNull(sample.amps)
        assertNull(sample.volts)
        assertNull(sample.currentMicroamps)
        assertNull(sample.voltageMillivolts)
        assertNull(sample.temperatureC)
        assertNull(sample.condition)
        assertNull(sample.plugged)
        assertNull(sample.reason)
        assertEquals(FlowState.UNKNOWN, sample.flow)
        assertEquals(1_000_000L, sample.timestampMillis)
        assertEquals(500_000L, sample.elapsedRealtimeMillis)
    }

    @Test
    fun dischargeDecodesNegativeSignedWatts() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                current = -450_000,
                voltage = 4000,
                level = 80,
                scale = 100,
                temperature = 291,
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
                health = BatteryTelemetryDecoder.HEALTH_GOOD,
            ),
        )
        assertEquals(-1.8, sample.watts!!, 1e-12)
        assertEquals(-0.45, sample.amps!!, 1e-12)
        assertEquals(4.0, sample.volts!!, 1e-12)
        assertEquals(80, sample.percentage)
        assertEquals(29.1, sample.temperatureC!!, 1e-12)
        assertEquals(BatteryCondition.GOOD.name, sample.condition)
        assertEquals(false, sample.plugged)
        assertEquals(FlowState.DISCHARGING, sample.flow)
        assertNull(sample.reason)
        assertFalse(sample.gapBefore)
    }

    @Test
    fun zeroOrMissingVoltageSuppressesWattsButKeepsCurrent() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(current = -500_000, voltage = 0, plugged = 0, status = BatteryTelemetryDecoder.STATUS_DISCHARGING),
        )
        assertNull(sample.watts)
        assertNull(sample.volts)
        assertEquals(-0.5, sample.amps!!, 1e-12)
        assertNull(sample.voltageMillivolts)
    }

    @Test
    fun conditionMappingCoversEveryReportedHealthCode() {
        assertEquals(BatteryCondition.GOOD, BatteryTelemetryDecoder.conditionOf(BatteryTelemetryDecoder.HEALTH_GOOD))
        assertEquals(
            BatteryCondition.OVERHEAT,
            BatteryTelemetryDecoder.conditionOf(BatteryTelemetryDecoder.HEALTH_OVERHEAT),
        )
        assertEquals(BatteryCondition.DEAD, BatteryTelemetryDecoder.conditionOf(BatteryTelemetryDecoder.HEALTH_DEAD))
        assertEquals(
            BatteryCondition.OVER_VOLTAGE,
            BatteryTelemetryDecoder.conditionOf(BatteryTelemetryDecoder.HEALTH_OVER_VOLTAGE),
        )
        assertEquals(
            BatteryCondition.COLD,
            BatteryTelemetryDecoder.conditionOf(BatteryTelemetryDecoder.HEALTH_COLD),
        )
        assertEquals(
            BatteryCondition.UNKNOWN,
            BatteryTelemetryDecoder.conditionOf(BatteryTelemetryDecoder.HEALTH_UNKNOWN),
        )
        assertNull(BatteryTelemetryDecoder.conditionOf(null))
    }

    // --- Instantaneous-only current fields -----------------------------------

    @Test
    fun currentAndEnergyDescribeTheInstantaneousReadingOnly() {
        // The counter-derived rate and the platform average exist, but neither may
        // replace the instantaneous reading.
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                current = -315,
                average = -42_000,
                chargeCounter = 5_000_000,
                voltage = 4000,
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
            ),
            derivedAmps = -0.042,
            derivedWindowMillis = 600_000L,
        )
        assertEquals(CurrentSource.INSTANT, sample.currentSource)
        assertEquals(-315L, sample.currentMicroamps)
        assertEquals(-315L, sample.rawCurrentMicroamps)
        assertEquals(-3.15e-4, sample.amps!!, 1e-15)
        assertEquals(-1.26e-3, sample.watts!!, 1e-12)
        // The averaged evidence is retained verbatim, on its own fields.
        assertEquals(-42_000L, sample.rawAverageMicroamps)
        assertEquals(-0.042, sample.derivedAmps!!, 1e-12)
        assertEquals(600_000L, sample.derivedWindowMillis)
        assertEquals(5_000_000L, sample.chargeCounterMicroampHours)
    }

    @Test
    fun missingInstantStaysMissingEvenWithAverageAndDerivedRate() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                average = -42_000,
                chargeCounter = 5_000_000,
                voltage = 4000,
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
            ),
            derivedAmps = -0.042,
            derivedWindowMillis = 600_000L,
        )
        assertEquals(CurrentSource.NONE, sample.currentSource)
        assertNull(sample.currentMicroamps)
        assertNull(sample.rawCurrentMicroamps)
        assertNull(sample.amps)
        assertNull(sample.watts)
        assertFalse(sample.currentUnitAdjusted)
        assertFalse(sample.currentPolarityAdjusted)
        assertFalse(sample.currentCalibrated)
        // Retained, but never promoted into amps/watts.
        assertEquals(-42_000L, sample.rawAverageMicroamps)
        assertEquals(-0.042, sample.derivedAmps!!, 1e-12)
        // The status may still supply the flow WORD only.
        assertEquals(FlowState.DISCHARGING, sample.flow)
    }

    @Test
    fun unsupportedAverageAndCounterSentinelsStayMissing() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                average = BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL,
                chargeCounter = BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL,
                voltage = 4000,
            ),
        )
        assertEquals(CurrentSource.NONE, sample.currentSource)
        assertNull(sample.rawAverageMicroamps)
        assertNull(sample.chargeCounterMicroampHours)
        assertNull(sample.amps)
    }

    @Test
    fun derivedWindowOnlyAccompaniesAUsableDerivedRate() {
        val noRate = BatteryTelemetryDecoder.decode(
            raw(current = -42_000),
            derivedAmps = null,
            derivedWindowMillis = 600_000L,
        )
        assertNull(noRate.derivedAmps)
        assertNull(noRate.derivedWindowMillis)

        val nonPositiveWindow = BatteryTelemetryDecoder.decode(
            raw(current = -42_000),
            derivedAmps = -0.042,
            derivedWindowMillis = 0L,
        )
        assertEquals(-0.042, nonPositiveWindow.derivedAmps!!, 1e-12)
        assertNull(nonPositiveWindow.derivedWindowMillis)
        // A derived rate never changes the instant-only fields.
        assertEquals(-42_000L, nonPositiveWindow.currentMicroamps)
        assertEquals(CurrentSource.INSTANT, nonPositiveWindow.currentSource)
    }

    @Test
    fun chargeCounterIsRetainedForDiagnosticsUnlessInvalid() {
        val sample = BatteryTelemetryDecoder.decode(raw(chargeCounter = 5_000_000))
        assertEquals(5_000_000L, sample.chargeCounterMicroampHours)
        assertNull(BatteryTelemetryDecoder.decode(raw(chargeCounter = -1)).chargeCounterMicroampHours)
        assertNull(
            BatteryTelemetryDecoder.decode(raw(chargeCounter = Long.MIN_VALUE))
                .chargeCounterMicroampHours,
        )
        assertNull(BatteryTelemetryDecoder.decode(raw()).chargeCounterMicroampHours)
    }

    @Test
    fun noUsableCurrentSourceLeavesAmpsAndWattsMissing() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                voltage = 4000,
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
            ),
        )
        assertEquals(CurrentSource.NONE, sample.currentSource)
        assertNull(sample.amps)
        assertNull(sample.watts)
        assertNull(sample.currentMicroamps)
        assertNull(sample.rawCurrentMicroamps)
        assertFalse(sample.currentUnitAdjusted)
        assertEquals(4.0, sample.volts!!, 1e-12)
        assertEquals(FlowState.DISCHARGING, sample.flow)
    }

    @Test
    fun microampsOfInvertsAmperesOf() {
        assertEquals(42_000L, BatteryTelemetryDecoder.microampsOf(0.042))
        assertEquals(-315L, BatteryTelemetryDecoder.microampsOf(-0.000315))
        assertEquals(0L, BatteryTelemetryDecoder.microampsOf(0.0))
        assertNull(BatteryTelemetryDecoder.microampsOf(null))
        assertNull(BatteryTelemetryDecoder.microampsOf(Double.NaN))
    }

    @Test
    fun reportedCycleCountReachesTheSample() {
        val sample = BatteryTelemetryDecoder.decode(raw(cycleCount = 412))
        assertEquals(412, sample.cycleCount)
    }

    @Test
    fun zeroCycleCountIsARealReadingOnANewBattery() {
        assertEquals(0, BatteryTelemetryDecoder.decode(raw(cycleCount = 0)).cycleCount)
        assertEquals(0, BatteryTelemetryDecoder.supportedCycleCountOf(0))
    }

    @Test
    fun absentOrInvalidCycleCountStaysNull() {
        // No extra at all: the provider passes its Int.MIN_VALUE default through.
        assertNull(BatteryTelemetryDecoder.decode(raw()).cycleCount)
        assertNull(BatteryTelemetryDecoder.decode(raw(cycleCount = Int.MIN_VALUE)).cycleCount)
        assertNull(BatteryTelemetryDecoder.decode(raw(cycleCount = -1)).cycleCount)
        assertNull(BatteryTelemetryDecoder.decode(raw(cycleCount = -412)).cycleCount)
        // The sanitiser itself, so the provider's default is never a count.
        assertNull(BatteryTelemetryDecoder.supportedCycleCountOf(Int.MIN_VALUE))
        assertNull(BatteryTelemetryDecoder.supportedCycleCountOf(-5))
        assertNull(BatteryTelemetryDecoder.supportedCycleCountOf(null))
        assertEquals(7, BatteryTelemetryDecoder.supportedCycleCountOf(7))
    }

    // --- Unit/polarity interpretation precedence ----------------------------

    private val establishedMilliampsInverted = CurrentCalibration(
        unit = CurrentUnitOverride.MILLIAMPS,
        inverted = true,
        evidenceWindows = 3,
    )

    @Test
    fun unitResolutionPrefersManualThenCalibrationThenDocumentedMicroamps() {
        assertEquals(
            CurrentUnitOverride.MICROAMPS,
            BatteryTelemetryDecoder.resolvedUnit(CurrentUnitOverride.MICROAMPS, establishedMilliampsInverted),
        )
        assertEquals(
            CurrentUnitOverride.MILLIAMPS,
            BatteryTelemetryDecoder.resolvedUnit(CurrentUnitOverride.AUTO, establishedMilliampsInverted),
        )
        assertEquals(
            CurrentUnitOverride.AUTO,
            BatteryTelemetryDecoder.resolvedUnit(CurrentUnitOverride.AUTO, CurrentCalibration()),
        )
    }

    @Test
    fun polarityResolutionPrefersManualThenCalibrationThenDocumentedStandard() {
        assertEquals(
            false,
            BatteryTelemetryDecoder.resolvedInversion(
                CurrentPolarityOverride.STANDARD,
                establishedMilliampsInverted,
            ),
        )
        assertEquals(
            true,
            BatteryTelemetryDecoder.resolvedInversion(
                CurrentPolarityOverride.INVERTED,
                CurrentCalibration(unit = CurrentUnitOverride.MICROAMPS, inverted = false, evidenceWindows = 3),
            ),
        )
        assertEquals(
            true,
            BatteryTelemetryDecoder.resolvedInversion(CurrentPolarityOverride.AUTO, establishedMilliampsInverted),
        )
        // Nothing establishes the sign: non-inverted by convention, but no evidence.
        assertNull(
            BatteryTelemetryDecoder.resolvedInversion(CurrentPolarityOverride.AUTO, CurrentCalibration()),
        )
    }

    @Test
    fun savedCalibrationRescalesMilliampPropertiesAndMarksThemCalibrated() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                current = -42,
                voltage = 4000,
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
            ),
            calibration = CurrentCalibration(
                unit = CurrentUnitOverride.MILLIAMPS,
                inverted = false,
                evidenceWindows = 3,
            ),
        )
        assertEquals(-42L, sample.rawCurrentMicroamps)
        assertEquals(-42_000L, sample.currentMicroamps)
        assertTrue(sample.currentUnitAdjusted)
        assertFalse(sample.currentPolarityAdjusted)
        assertTrue(sample.currentCalibrated)
        assertEquals(-0.042, sample.amps!!, 1e-12)
        assertEquals(-0.168, sample.watts!!, 1e-12)
        assertEquals(FlowState.DISCHARGING, sample.flow)
    }

    @Test
    fun manualUnitOverrideWinsOverSavedCalibration() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(current = -42, voltage = 4000),
            currentUnit = CurrentUnitOverride.MICROAMPS,
            calibration = CurrentCalibration(
                unit = CurrentUnitOverride.MILLIAMPS,
                inverted = false,
                evidenceWindows = 3,
            ),
        )
        // Manual scale is the documented one, so the property is left alone.
        assertEquals(-42L, sample.currentMicroamps)
        assertFalse(sample.currentUnitAdjusted)
        assertTrue(sample.currentCalibrated)
        assertEquals(-4.2e-5, sample.amps!!, 1e-15)
    }

    @Test
    fun manualMilliampOverrideAppliesWithoutCalibration() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(current = -42, voltage = 4000),
            currentUnit = CurrentUnitOverride.MILLIAMPS,
        )
        assertEquals(-42_000L, sample.currentMicroamps)
        assertTrue(sample.currentUnitAdjusted)
        // The scale is manual evidence, but nothing established the sign.
        assertFalse(sample.currentPolarityAdjusted)
        assertFalse(sample.currentCalibrated)
        assertEquals(-0.168, sample.watts!!, 1e-12)
    }

    @Test
    fun correctedInvertedReadingWorksInsteadOfBeingSuppressed() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(
                current = -500_000,
                voltage = 4100,
                plugged = 2,
                status = BatteryTelemetryDecoder.STATUS_CHARGING,
            ),
            calibration = CurrentCalibration(
                unit = CurrentUnitOverride.MICROAMPS,
                inverted = true,
                evidenceWindows = 3,
            ),
        )
        // Raw reported discharge while the counter said charge: corrected to +.
        assertEquals(-500_000L, sample.rawCurrentMicroamps)
        assertEquals(500_000L, sample.currentMicroamps)
        assertTrue(sample.currentPolarityAdjusted)
        assertTrue(sample.currentCalibrated)
        assertEquals(0.5, sample.amps!!, 1e-12)
        assertEquals(2.05, sample.watts!!, 1e-12)
        assertEquals(FlowState.CHARGING, sample.flow)
        assertNull(sample.reason)
    }

    @Test
    fun manualStandardPolarityDoesNotSuppressAConsistentReadingButIsNotCalibrationEvidence() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(current = -42_000, voltage = 4000),
            currentPolarity = CurrentPolarityOverride.STANDARD,
        )
        assertEquals(-42_000L, sample.currentMicroamps)
        assertFalse(sample.currentPolarityAdjusted)
        assertEquals(-0.168, sample.watts!!, 1e-12)
        // Manual polarity is evidence for the sign only; the unit is still unknown.
        assertFalse(sample.currentCalibrated)
    }

    @Test
    fun noEvidenceFallsBackToDocumentedMicroampsAndNonInvertedSign() {
        val sample = BatteryTelemetryDecoder.decode(raw(current = -42_000, voltage = 4000))
        assertEquals(-42_000L, sample.currentMicroamps)
        assertFalse(sample.currentUnitAdjusted)
        assertFalse(sample.currentPolarityAdjusted)
        assertFalse(sample.currentCalibrated)
        assertEquals(-0.042, sample.amps!!, 1e-12)
        assertEquals(CurrentSource.INSTANT, sample.currentSource)
    }

    @Test
    fun zeroIsNeverRescaledOrFlippedEvenWhenCalibrated() {
        val sample = BatteryTelemetryDecoder.decode(
            raw(current = 0, voltage = 4000, plugged = 2, status = BatteryTelemetryDecoder.STATUS_FULL),
            calibration = establishedMilliampsInverted,
        )
        assertEquals(0L, sample.rawCurrentMicroamps)
        assertEquals(0L, sample.currentMicroamps)
        assertFalse(sample.currentUnitAdjusted)
        assertFalse(sample.currentPolarityAdjusted)
        assertTrue(sample.currentCalibrated)
        assertEquals(0.0, sample.watts!!, 0.0)
        assertEquals(FlowState.IDLE, sample.flow)
    }

    @Test
    fun implausibleCorrectedValuesAreDroppedNotClamped() {
        // 1000 A as documented microamps: beyond any phone battery.
        val tooLarge = BatteryTelemetryDecoder.decode(raw(current = 1_000_000_000, voltage = 4000))
        assertEquals(CurrentSource.NONE, tooLarge.currentSource)
        assertNull(tooLarge.currentMicroamps)
        assertNull(tooLarge.amps)
        assertNull(tooLarge.watts)
        // Still exported verbatim, so the bogus property is visible.
        assertEquals(1_000_000_000L, tooLarge.rawCurrentMicroamps)

        // 200 mA reported in milliamps is 200 A once rescaled: also implausible.
        val rescaledTooLarge = BatteryTelemetryDecoder.decode(
            raw(current = 200_000, voltage = 4000),
            calibration = CurrentCalibration(unit = CurrentUnitOverride.MILLIAMPS, inverted = false, evidenceWindows = 3),
        )
        assertEquals(CurrentSource.NONE, rescaledTooLarge.currentSource)
        assertNull(rescaledTooLarge.currentMicroamps)
        assertFalse(rescaledTooLarge.currentUnitAdjusted)
        assertEquals(200_000L, rescaledTooLarge.rawCurrentMicroamps)
    }

    @Test
    fun interpretInstantReturnsNullForMissingOrUnsupportedProperties() {
        assertNull(BatteryTelemetryDecoder.interpretInstant(null))
        assertNull(
            BatteryTelemetryDecoder.interpretInstant(
                BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL,
            ),
        )
        assertEquals(
            42_000L,
            BatteryTelemetryDecoder.interpretInstant(42_000)!!.microamps,
        )
    }
}
