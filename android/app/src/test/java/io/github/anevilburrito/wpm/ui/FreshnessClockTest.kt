package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.MonitorUiState
import io.github.anevilburrito.wpm.model.isSampleStale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the live -> false stale -> live refresh sequence seen in both videos,
 * now with **calibrated instantaneous** fixtures: the primary readout no longer
 * waits for a 60 s counter average, so freshness must be proven for the reading the
 * user actually sees.
 */
class FreshnessClockTest {
    private fun sample(elapsedMillis: Long) = BatterySample(
        timestampMillis = 1_700_000_000_000L + elapsedMillis,
        elapsedRealtimeMillis = elapsedMillis,
        // Calibrated CURRENT_NOW: this is the primary live reading.
        watts = -1.9,
        volts = 3.8,
        amps = -0.5,
        percentage = 80,
        currentCalibrated = true,
        currentSource = CurrentSource.INSTANT,
        chargeCounterMicroampHours = 4_000_000L,
        // The auxiliary counter average stays on the raw sample for the runtime.
        derivedAmps = -0.5,
        derivedWindowMillis = 60_000L,
    )

    private fun runtime(reading: BatterySample, now: Long, interval: Int = 5): String = capacityView(
        state = MonitorUiState(running = true, samples = listOf(reading)),
        latest = reading,
        stale = isSampleStale(reading, now, interval),
    ).runtime

    @Test fun aNewSampleMustNotUseThePreviousTicksTime() {
        val reading = sample(101_000L)
        val previousTick = 100_000L
        // This is the old bug, not a stale gauge: "now" is older than the sample.
        assertTrue(isSampleStale(reading, previousTick, 5))
        val now = freshnessClockMillis(previousTick, 101_001L)
        assertFalse(isSampleStale(reading, now, 5))
        val view = liveReadingView(reading, true, isSampleStale(reading, now, 5), true)
        assertEquals(ReadoutState.LIVE, view.state)
        assertEquals("1.90", view.headline)
        assertEquals("80", view.percentage)
    }

    @Test fun repeatedSamplesBetweenTicksNeverBlankTheReadout() {
        // The supported cadences are 1/2/5 s; the ticker stays slower than all of
        // them, so every poll arrives between ticks.
        for (interval in listOf(1, 2, 5)) {
            var tick = 100_000L
            for (poll in 1..120) {
                val reading = sample(100_000L + poll * interval * 1_000L)
                // The new sample arrives before the ticker effect can catch up.
                val frameNow = freshnessClockMillis(tick, reading.elapsedRealtimeMillis + 2L)
                assertFalse("poll $poll at ${interval}s", isSampleStale(reading, frameNow, interval))
                assertEquals(
                    ReadoutState.LIVE,
                    liveReadingView(reading, true, isSampleStale(reading, frameNow, interval), true).state,
                )
                if (poll % 5 == 0) tick = frameNow
            }
        }
    }

    @Test fun aSavedCalibrationShowsTheLiveReadingBeforeAnyCounterAverage() {
        // The central change of 0.6.0: a calibrated instant reading is ready even
        // when the counter has not produced a window yet. No 60 s average gates it.
        val reading = sample(100_000L).copy(
            derivedAmps = null,
            derivedWindowMillis = null,
        )
        val now = freshnessClockMillis(100_000L, reading.elapsedRealtimeMillis + 1L)
        val view = liveReadingView(reading, true, isSampleStale(reading, now, 2), true)
        assertEquals(ReadoutState.LIVE, view.state)
        assertEquals("1.90", view.headline)
        assertTrue(view.powerAvailable)
        assertTrue(view.electrical.contains("500.0 mA"))
        assertTrue(view.electrical.contains("3.80 V"))
    }

    @Test fun runtimeDoesNotAlternateWithNoRecentReadingOnEveryPoll() {
        var tick = 100_000L
        val expected = runtime(sample(100_000L), 100_001L)
        assertTrue(expected.contains("8h"))
        assertTrue("runtime says which average it uses", expected.contains("counter average"))
        for (poll in 1..60) {
            val reading = sample(100_000L + poll * 1_000L)
            val now = freshnessClockMillis(tick, reading.elapsedRealtimeMillis + 1L)
            assertEquals(expected, runtime(reading, now))
            if (poll % 5 == 0) tick = now
        }
    }

    @Test fun uncalibratedCurrentStaysCalibratingWithoutAlternatingOnFreshPolls() {
        for (poll in 1..14) {
            // A counter exists but the instant unit/sign is not established yet:
            // this is a genuine calibration wait, not a counter-average wait.
            val reading = sample(100_000L + poll * 1_000L).copy(
                currentCalibrated = false,
                derivedAmps = null,
                derivedWindowMillis = null,
            )
            val now = freshnessClockMillis(100_000L, reading.elapsedRealtimeMillis + 1L)
            val view = liveReadingView(reading, true, isSampleStale(reading, now, 2), true)
            assertEquals(ReadoutState.CALIBRATING, view.state)
            assertEquals("Calibrating", view.headline)
            assertFalse(view.powerAvailable)
            assertEquals("80", view.percentage)
        }
    }

    @Test fun impossibleCalibrationIsUnavailableNotEndlessCalibration() {
        val reading = sample(100_000L).copy(
            currentCalibrated = false,
            chargeCounterMicroampHours = null,
        )
        val now = freshnessClockMillis(100_000L, reading.elapsedRealtimeMillis + 1L)
        val view = liveReadingView(reading, true, isSampleStale(reading, now, 2), true)
        assertEquals(ReadoutState.UNAVAILABLE, view.state)
        assertEquals("Current unavailable", view.direction)
    }

    @Test fun realStalenessStillExpiresWithoutANewSample() {
        val reading = sample(100_000L)
        assertFalse(isSampleStale(reading, freshnessClockMillis(140_000L, 145_000L), 5))
        val expired = freshnessClockMillis(145_000L, 145_001L)
        assertTrue(isSampleStale(reading, expired, 5))
        assertEquals("No recent reading", runtime(reading, expired))
        assertEquals(
            ReadoutState.UNAVAILABLE,
            liveReadingView(reading, true, isSampleStale(reading, expired, 5), true).state,
        )
    }

    @Test fun everySelectableCadenceRetainsTheRealFortyFiveSecondStalenessFloor() {
        val reading = sample(100_000L)
        for (interval in listOf(1, 2, 5)) {
            assertFalse(isSampleStale(reading, freshnessClockMillis(140_000L, 145_000L), interval))
            assertTrue(isSampleStale(reading, freshnessClockMillis(145_000L, 145_001L), interval))
        }
    }

    @Test fun aGenuinelyFutureDatedSampleIsNotClampedIntoBeingFresh() {
        val beforeReboot = sample(900_000L)
        val now = freshnessClockMillis(9_000L, 10_000L)
        assertEquals(10_000L, now)
        assertTrue(isSampleStale(beforeReboot, now, 5))
        assertEquals("No recent reading", runtime(beforeReboot, now))
    }

    @Test fun missingSamplesRemainMissing() {
        val now = freshnessClockMillis(100_000L, 101_000L)
        assertTrue(isSampleStale(null, now, 5))
        val view = liveReadingView(null, true, true, true)
        assertEquals(ReadoutState.CALIBRATING, view.state)
        assertFalse(view.powerAvailable)
        assertEquals("Waiting for the first reading", view.readiness)
    }

    @Test fun recoveryFromARealGapUsesTheNewFramesClockImmediately() {
        val oldReading = sample(100_000L)
        assertTrue(isSampleStale(oldReading, 150_000L, 5))
        val recovered = sample(151_000L)
        val now = freshnessClockMillis(150_000L, 151_001L)
        assertFalse(isSampleStale(recovered, now, 5))
        assertTrue(runtime(recovered, now).contains("8h"))
        assertEquals(
            ReadoutState.LIVE,
            liveReadingView(recovered, true, isSampleStale(recovered, now, 5), true).state,
        )
    }

    @Test fun frozenMonitorKeepsItsCapturedTimeWhileDiagnosticsUsesLiveTime() {
        val frozenReading = sample(100_000L)
        val capturedAt = 101_000L
        val liveNow = freshnessClockMillis(145_000L, 150_000L)
        // Freeze still explicitly chooses capturedAt, not the new live clock.
        assertFalse(isSampleStale(frozenReading, capturedAt, 5))
        assertTrue(isSampleStale(frozenReading, liveNow, 5))
    }
}
