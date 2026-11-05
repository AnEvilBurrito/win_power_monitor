package io.github.anevilburrito.wpm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Live calibrated-current helpers: the primary metric is the normalized,
 * calibrated `CURRENT_NOW` reading. No average fallback and no uncalibrated
 * fallback; a missing value stays missing and readiness says why.
 */
class LiveMeasurementTest {

    private fun sample(
        amps: Double? = -0.315,
        watts: Double? = -1.26,
        calibrated: Boolean = true,
        source: CurrentSource = CurrentSource.INSTANT,
        chargeCounter: Long? = 5_000_000L,
        volts: Double? = 4.0,
        flow: FlowState = FlowState.DISCHARGING,
        derivedAmps: Double? = -0.9,
        derivedWindowMillis: Long? = 60_000L,
    ) = BatterySample(
        timestampMillis = 1_000_000L,
        elapsedRealtimeMillis = 1_000L,
        watts = watts,
        volts = volts,
        amps = amps,
        percentage = 81,
        temperatureC = 30.0,
        currentMicroamps = -315_000L,
        voltageMillivolts = 4000,
        rawCurrentMicroamps = -315L,
        rawAverageMicroamps = -900L,
        currentCalibrated = calibrated,
        currentUnitAdjusted = true,
        currentPolarityAdjusted = true,
        currentSource = source,
        chargeCounterMicroampHours = chargeCounter,
        derivedAmps = derivedAmps,
        derivedWindowMillis = derivedWindowMillis,
        flow = flow,
    )

    @Test
    fun liveAmpsNeedsCalibrationInstantSourceAndFiniteAmps() {
        assertEquals(-0.315, sample().liveAmps!!, 0.0)

        // Unconfirmed scale/sign, a non-instant source, or a non-finite value: the
        // live metric is unavailable. The auxiliary average is never substituted.
        assertNull(sample(calibrated = false).liveAmps)
        assertNull(sample(source = CurrentSource.NONE).liveAmps)
        assertNull(sample(source = CurrentSource.CHARGE_COUNTER).liveAmps)
        assertNull(sample(source = CurrentSource.AVERAGE).liveAmps)
        assertNull(sample(amps = Double.NaN).liveAmps)
        assertNull(sample(amps = Double.POSITIVE_INFINITY).liveAmps)
        assertNull(sample(amps = null).liveAmps)
        // An auxiliary average existing changes nothing about the live value.
        assertEquals(-0.315, sample(derivedAmps = -9.0).liveAmps!!, 0.0)
    }

    @Test
    fun anAlreadyCalibratedDisplaySampleDoesNotNeedAnotherAllocation() {
        val reading = sample()
        assertSame(reading, reading.forLiveDisplay())
    }

    @Test
    fun aTrueZeroIsAReading() {
        val zero = sample(amps = 0.0, watts = 0.0, flow = FlowState.IDLE)
        assertEquals(0.0, zero.liveAmps!!, 0.0)
        assertEquals(0.0, zero.liveWatts!!, 0.0)
        assertEquals(LiveReading.READY, zero.liveReading())
    }

    @Test
    fun liveWattsUsesTheStoredDecoderValueNotARecomputation() {
        // 1 A at 4 V would be 4 W; the stored decoder value must win because it may
        // have been deliberately suppressed (here: kept as a different finite value).
        val stored = sample(amps = -1.0, watts = -0.5)
        assertEquals(-0.5, stored.liveWatts!!, 0.0)

        // No live current means no live power, even if a stored watts value exists.
        assertNull(sample(calibrated = false, watts = -1.26).liveWatts)
        // A decoder-suppressed power stays suppressed: it is not recomputed.
        assertNull(sample(watts = null).liveWatts)
        assertNull(sample(watts = Double.NaN).liveWatts)
        assertNull(sample(watts = Double.POSITIVE_INFINITY).liveWatts)
    }

    @Test
    fun forLiveDisplayReplacesOnlyTheLiveMetricAndKeepsProvenance() {
        val original = sample(amps = 0.4, watts = 5.5).copy(
            gapBefore = true,
            reason = "reason-token",
            cycleCount = 412,
        )
        val display = original.forLiveDisplay()
        assertEquals(0.4, display.amps!!, 0.0)
        assertEquals(5.5, display.watts!!, 0.0)
        assertEquals(FlowState.CHARGING, display.flow)

        val discharging = sample(amps = -0.4, watts = -5.5).forLiveDisplay()
        assertEquals(FlowState.DISCHARGING, discharging.flow)
        val idle = sample(amps = 0.0, watts = 0.0).forLiveDisplay()
        assertEquals(FlowState.IDLE, idle.flow)

        // Raw provenance is copied verbatim; only amps/watts/flow changed.
        assertEquals(original.currentMicroamps, display.currentMicroamps)
        assertEquals(original.rawCurrentMicroamps, display.rawCurrentMicroamps)
        assertEquals(original.rawAverageMicroamps, display.rawAverageMicroamps)
        assertEquals(original.currentSource, display.currentSource)
        assertEquals(original.currentCalibrated, display.currentCalibrated)
        assertEquals(original.currentUnitAdjusted, display.currentUnitAdjusted)
        assertEquals(original.currentPolarityAdjusted, display.currentPolarityAdjusted)
        assertEquals(original.percentage, display.percentage)
        assertEquals(original.volts, display.volts)
        assertEquals(original.derivedAmps, display.derivedAmps)
        assertEquals(original.derivedWindowMillis, display.derivedWindowMillis)
        assertEquals(original.gapBefore, display.gapBefore)
        assertEquals(original.reason, display.reason)
        assertEquals(original.chargeCounterMicroampHours, display.chargeCounterMicroampHours)
        assertEquals(original.cycleCount, display.cycleCount)
        // The display copy is a copy: the original sample is never mutated.
        assertEquals(0.4, original.amps!!, 0.0)
        assertEquals(FlowState.DISCHARGING, original.flow)
    }

    @Test
    fun forLiveDisplayWithholdsMissingOrUnconfirmedValuesAndSplitsTheTrace() {
        val uncalibrated = sample(calibrated = false, flow = FlowState.DISCHARGING).forLiveDisplay()
        assertNull(uncalibrated.amps)
        assertNull(uncalibrated.watts)
        assertEquals(FlowState.UNKNOWN, uncalibrated.flow)
        // Provenance survives even though the display copy carries no live value.
        assertEquals(CurrentSource.INSTANT, uncalibrated.currentSource)
        assertEquals(-315_000L, uncalibrated.currentMicroamps)

        val noSource = sample(source = CurrentSource.NONE).forLiveDisplay()
        assertNull(noSource.amps)
        assertNull(noSource.watts)
        assertEquals(FlowState.UNKNOWN, noSource.flow)

        // Missing power alone does not hide the confirmed current.
        val currentOnly = sample(watts = null).forLiveDisplay()
        assertEquals(-0.315, currentOnly.amps!!, 0.0)
        assertNull(currentOnly.watts)
        assertEquals(FlowState.DISCHARGING, currentOnly.flow)
    }

    @Test
    fun liveReadingIsWaitingWithoutASample() {
        assertEquals(LiveReading.WAITING, (null as BatterySample?).liveReading())
    }

    @Test
    fun liveReadingIsReadyForACalibratedInstantReading() {
        assertEquals(LiveReading.READY, sample().liveReading())
        // Power may be independently unavailable: the current is still a reading.
        assertEquals(LiveReading.READY, sample(watts = null).liveReading())
        assertEquals(LiveReading.READY, sample(watts = Double.NaN).liveReading())
    }

    @Test
    fun liveReadingIsCalibratingOnlyWhileEvidenceCanArrive() {
        // Uncalibrated instant source plus a charge counter to compare against.
        assertEquals(
            LiveReading.CALIBRATING,
            sample(calibrated = false, chargeCounter = 5_000_000L).liveReading(),
        )
        // A device with no counter has no evidence path: never calibrate forever.
        assertEquals(
            LiveReading.UNAVAILABLE,
            sample(calibrated = false, chargeCounter = null).liveReading(),
        )
        // No usable instantaneous source cannot be calibrated either.
        assertEquals(
            LiveReading.UNAVAILABLE,
            sample(source = CurrentSource.NONE, chargeCounter = 5_000_000L).liveReading(),
        )
        assertEquals(
            LiveReading.UNAVAILABLE,
            sample(source = CurrentSource.AVERAGE, chargeCounter = 5_000_000L).liveReading(),
        )
    }

    @Test
    fun aSavedCalibrationIsReadyImmediatelyWithoutACounterAverage() {
        // The old policy waited for a 60 s counter average. The live reading must be
        // ready on the very first poll, even when no derived average exists at all.
        val saved = sample(calibrated = true, derivedAmps = null, derivedWindowMillis = null)
        assertEquals(LiveReading.READY, saved.liveReading())
        assertNull(saved.averageAmps)
        assertFalse(saved.currentSource == CurrentSource.NONE)
    }

    @Test
    fun anUnusableCalibratedFlagCannotResurrectANonInstantSource() {
        val mismatched = sample(calibrated = true, source = CurrentSource.CHARGE_COUNTER)
        assertNull(mismatched.liveAmps)
        assertEquals(LiveReading.UNAVAILABLE, mismatched.liveReading())
        // ForLiveDisplay agrees with the reading state.
        assertEquals(FlowState.UNKNOWN, mismatched.forLiveDisplay().flow)
    }
}
