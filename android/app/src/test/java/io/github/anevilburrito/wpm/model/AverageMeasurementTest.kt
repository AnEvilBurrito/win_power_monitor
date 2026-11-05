package io.github.anevilburrito.wpm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The average-first contract: the stored sample keeps instant provenance, while the
 * display copy carries the counter-derived average. Every helper must leave a
 * missing input missing instead of manufacturing 0 or falling back to the instant
 * reading.
 */
class AverageMeasurementTest {

    private fun sample(
        derivedAmps: Double? = -0.315,
        derivedWindowMillis: Long? = 60_000L,
        volts: Double? = 4.0,
        amps: Double? = -0.5,
        watts: Double? = -2.0,
        flow: FlowState = FlowState.DISCHARGING,
        currentSource: CurrentSource = CurrentSource.INSTANT,
    ) = BatterySample(
        timestampMillis = 1_000_000L,
        elapsedRealtimeMillis = 10_000L,
        watts = watts,
        volts = volts,
        amps = amps,
        percentage = 80,
        flow = flow,
        // Instant provenance that must survive the average display copy untouched.
        currentMicroamps = -500_000L,
        rawCurrentMicroamps = -500_000L,
        derivedAmps = derivedAmps,
        derivedWindowMillis = derivedWindowMillis,
        currentSource = currentSource,
    )

    @Test
    fun averageAmpsIsTheValidatedCounterRate() {
        assertEquals(-0.315, sample().averageAmps!!, 0.0)
        assertEquals(0.315, sample(derivedAmps = 0.315).averageAmps!!, 0.0)
        // A reported zero rate is a real average (the counter did not move long
        // enough to mean 0 A), not a missing one.
        assertEquals(0.0, sample(derivedAmps = 0.0).averageAmps!!, 0.0)
    }

    @Test
    fun averageAmpsIsMissingWithoutAUsableWindow() {
        assertNull(sample(derivedAmps = null).averageAmps)
        assertNull(sample(derivedWindowMillis = null).averageAmps)
        // A rate without a positive measured window has no averaging basis.
        assertNull(sample(derivedWindowMillis = 0L).averageAmps)
        assertNull(sample(derivedWindowMillis = -1L).averageAmps)
        assertNull(sample(derivedAmps = Double.NaN).averageAmps)
        assertNull(sample(derivedAmps = Double.POSITIVE_INFINITY).averageAmps)
    }

    @Test
    fun averageWattsIsAverageCurrentTimesTheLatestVoltage() {
        // -0.315 A * 4.0 V = -1.26 W: an estimate, not a window-integrated V*I.
        assertEquals(-1.26, sample().averageWatts!!, 1e-12)
        assertEquals(1.26, sample(derivedAmps = 0.315).averageWatts!!, 1e-12)
        assertEquals(0.0, sample(derivedAmps = 0.0).averageWatts!!, 0.0)
    }

    @Test
    fun averageWattsStaysMissingWithoutCurrentOrUsableVoltage() {
        assertNull(sample(derivedAmps = null).averageWatts)
        assertNull(sample(derivedWindowMillis = null).averageWatts)
        // A missing/nonsense voltage must not fabricate 0 W.
        assertNull(sample(volts = null).averageWatts)
        assertNull(sample(volts = 0.0).averageWatts)
        assertNull(sample(volts = -4.0).averageWatts)
        assertNull(sample(volts = Double.NaN).averageWatts)
        assertNull(sample(volts = Double.POSITIVE_INFINITY).averageWatts)
    }

    @Test
    fun averageDoesNotDependOnTheInstantReading() {
        // The counter path needs no CURRENT_NOW at all: a device whose instant
        // property is unsupported still has a valid average.
        val noInstant = sample(
            amps = null,
            watts = null,
            currentSource = CurrentSource.NONE,
            flow = FlowState.CHARGING,
        )
        assertEquals(-0.315, noInstant.averageAmps!!, 0.0)
        assertEquals(-1.26, noInstant.averageWatts!!, 1e-12)
        val display = noInstant.forAverageDisplay()
        assertEquals(-0.315, display.amps!!, 0.0)
        assertEquals(-1.26, display.watts!!, 1e-12)
        assertEquals(FlowState.DISCHARGING, display.flow)
    }

    @Test
    fun forAverageDisplayCopiesAverageIntoAmpsWattsAndFlow() {
        val display = sample(
            derivedAmps = -0.315,
            amps = -0.5,
            watts = -2.0,
            flow = FlowState.CHARGING,
        ).forAverageDisplay()
        assertEquals(-0.315, display.amps!!, 0.0)
        assertEquals(-1.26, display.watts!!, 1e-12)
        // Flow follows the average's sign, so a stale instant flow cannot mislabel it.
        assertEquals(FlowState.DISCHARGING, display.flow)
    }

    @Test
    fun forAverageDisplayKeepsEverythingElseVerbatim() {
        val original = sample()
        val display = original.forAverageDisplay()
        // Provenance of the instant reading is untouched on the copy.
        assertEquals(original.currentMicroamps, display.currentMicroamps)
        assertEquals(original.rawCurrentMicroamps, display.rawCurrentMicroamps)
        assertEquals(CurrentSource.INSTANT, display.currentSource)
        assertEquals(original.volts!!, display.volts!!, 0.0)
        assertEquals(original.percentage, display.percentage)
        assertEquals(original.timestampMillis, display.timestampMillis)
        assertEquals(original.elapsedRealtimeMillis, display.elapsedRealtimeMillis)
        assertEquals(original.derivedAmps!!, display.derivedAmps!!, 0.0)
        assertEquals(original.derivedWindowMillis, display.derivedWindowMillis)
    }

    @Test
    fun forAverageDisplayNeverFallsBackToTheInstantReading() {
        // Missing average: the copy is missing too, even though the instant values
        // exist. Substituting them would present one sample as an average.
        val display = sample(derivedAmps = null, derivedWindowMillis = null).forAverageDisplay()
        assertNull(display.amps)
        assertNull(display.watts)
        assertEquals(FlowState.UNKNOWN, display.flow)
        // And the raw state is still available from the original sample.
        assertEquals(-0.5, sample(derivedAmps = null).amps!!, 0.0)
    }

    @Test
    fun zeroAverageIsIdleNotChargingOrDischarging() {
        val display = sample(derivedAmps = 0.0, flow = FlowState.DISCHARGING).forAverageDisplay()
        assertEquals(0.0, display.amps!!, 0.0)
        assertEquals(FlowState.IDLE, display.flow)
        assertFalse(display.flow == FlowState.CHARGING)
        assertTrue(display.averageWatts!! == 0.0)
    }

    @Test
    fun notificationShowsTheAverageWhenItExists() {
        assertEquals(AverageReading.AVERAGE, sample().averageReading())
    }

    @Test
    fun notificationNeverShowsTheInstantReading() {
        // A confident-looking instant reading with no counter window: not reportable.
        val instantOnly = sample(
            derivedAmps = null,
            derivedWindowMillis = null,
            amps = -1.5,
            watts = -6.0,
            currentSource = CurrentSource.INSTANT,
        ).copy(chargeCounterMicroampHours = 4_000_000L)
        assertEquals(AverageReading.CALIBRATING, instantOnly.averageReading())
        assertEquals(-6.0, instantOnly.watts!!, 0.0)
        // No counter at all: unavailable, never an endless calibration.
        val unsupported = instantOnly.copy(chargeCounterMicroampHours = null)
        assertEquals(AverageReading.UNAVAILABLE, unsupported.averageReading())
        // Nothing recorded yet.
        assertEquals(AverageReading.WAITING, (null as BatterySample?).averageReading())
    }

    @Test
    fun aMissingVoltageStillReportsTheAverageCurrent() {
        assertNull(sample(volts = null).averageWatts)
        // Power is derived, current is measured: a missing voltage costs the power
        // figure only, so the notification still reports the average.
        assertEquals(AverageReading.AVERAGE, sample(volts = null).averageReading())
    }
}
