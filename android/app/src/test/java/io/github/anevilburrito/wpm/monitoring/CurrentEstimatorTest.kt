package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for the charge-counter derived rate. All times are monotonic millis and
 * all counters are microamp-hours, exactly as the provider reports them.
 */
class CurrentEstimatorTest {

    private fun estimator() = CurrentEstimator()

    @Test
    fun chargeIsPositiveAmperesOverTheMeasuredWindow() {
        val estimator = estimator()
        // Anchoring poll: no rate yet, because a single reading has no window.
        assertNull(estimator.update(1_000_000L, 5_000_000L))
        val estimate = estimator.update(1_120_000L, 5_003_000L)!!
        // +3000 uAh over 120 s -> 90 mA.
        assertEquals(0.09, estimate.amps, 1e-12)
        assertEquals(120_000L, estimate.windowMillis)
    }

    @Test
    fun dischargeIsNegativeAmperesAndKeepsItsSign() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 4_000_000L))
        val estimate = estimator.update(300_000L, 3_996_000L)!!
        // -4000 uAh over 300 s -> -48 mA.
        assertEquals(-0.048, estimate.amps, 1e-12)
        assertEquals(-48.0, estimate.amps * 1000.0, 1e-9)
        assertEquals(300_000L, estimate.windowMillis)
    }

    @Test
    fun noRateIsReportedBeforeTheMinimumWindow() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // 59,999 ms is one millisecond short of a trustworthy baseline.
        assertNull(estimator.update(59_999L, 1_001_000L))
        // The baseline was NOT re-anchored, so the very next poll reports the
        // full accumulated window instead of starting over.
        val estimate = estimator.update(60_000L, 1_001_000L)!!
        assertEquals(60_000L, estimate.windowMillis)
        // +1000 uAh over the whole 60 s window -> 60 mA.
        assertEquals(0.06, estimate.amps, 1e-12)
    }

    @Test
    fun exactZeroIsReportedOnlyAfterTheLongZeroWindow() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 2_000_000L))
        // A flat counter over minutes cannot distinguish "idle" from "less than one
        // counter step", so it stays unknown until the long zero window elapses.
        assertNull(estimator.update(60_000L, 2_000_000L))
        assertNull(estimator.update(300_000L, 2_000_000L))
        assertNull(estimator.update(599_999L, 2_000_000L))
        val estimate = estimator.update(600_000L, 2_000_000L)!!
        assertEquals(0.0, estimate.amps, 0.0)
        // Sparse polls (5 minutes apart) leave a 5 minute trailing window; the
        // point is that it is bounded by the poll history, not the whole session.
        assertEquals(300_000L, estimate.windowMillis)
    }

    @Test
    fun theRateIsATrailingAverageNotASessionAverage() {
        val estimator = estimator()
        // 90 mA for 10 minutes, then 900 mA for the last 2 minutes.
        var counter = 5_000_000L
        assertNull(estimator.update(0L, counter))
        var elapsed = 0L
        while (elapsed < 600_000L) {
            elapsed += 60_000L
            counter += 1_500L // 90 mA over 60 s
            assertEquals(0.09, estimator.update(elapsed, counter)!!.amps, 1e-9)
        }
        while (elapsed < 720_000L) {
            elapsed += 60_000L
            counter += 15_000L // 900 mA over 60 s
            val estimate = estimator.update(elapsed, counter)!!
            assertEquals(0.9, estimate.amps, 1e-9)
            // A stale session-long baseline would report ~250 mA here instead.
            assertEquals(60_000L, estimate.windowMillis)
        }
    }

    @Test
    fun decreasingCounterRebaselinesInsteadOfReportingANegativeJump() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 5_000_000L))
        // Charge-cycle / gauge reset: the counter dropped far faster than any real
        // current policy permits (1,000,000 uAh in 120 s implies 30 A).
        assertNull(estimator.update(120_000L, 4_000_000L))
        // The new baseline is the post-reset reading; a valid delta now reports.
        val estimate = estimator.update(240_000L, 4_001_000L)!!
        assertEquals(0.03, estimate.amps, 1e-12)
        assertEquals(120_000L, estimate.windowMillis)
    }

    @Test
    fun aPlausiblySizedBackwardStepIsDischargeNotAReset() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 5_000_000L))
        // The counter normally *falls* while discharging: 2000 uAh in 120 s is
        // 60 mA, so it is a measurement, not a reset.
        val estimate = estimator.update(120_000L, 4_998_000L)!!
        assertEquals(-0.06, estimate.amps, 1e-12)
        assertEquals(120_000L, estimate.windowMillis)
    }

    @Test
    fun resetDetectionUsesTheImpliedRateNotTheTotalAmount() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 5_000_000L))
        // 800,000 uAh over 300 s implies 9.6 A: implausible for a phone but still
        // below the reset threshold, so it is reported (rates are never clamped).
        assertEquals(-9.6, estimator.update(300_000L, 4_200_000L)!!.amps, 1e-9)

        val resetEstimator = estimator()
        assertNull(resetEstimator.update(0L, 5_000_000L))
        // 900,000 uAh over 300 s implies 10.8 A, above our rejection threshold.
        assertNull(resetEstimator.update(300_000L, 4_100_000L))
    }

    @Test
    fun nonPositiveElapsedTimeRebaselines() {
        val estimator = estimator()
        assertNull(estimator.update(100_000L, 1_000_000L))
        // Same instant again: a zero-length window is not a measurement.
        assertNull(estimator.update(100_000L, 1_000_000L))
        val estimate = estimator.update(200_000L, 1_002_000L)!!
        // Re-anchored at 100,000 ms, so the window is 100 s, not 200 s.
        assertEquals(100_000L, estimate.windowMillis)
        assertEquals(2000 * 3600.0 / 100.0 / 1_000_000.0, estimate.amps, 1e-12)
    }

    @Test
    fun gapLongerThanFiveMinutesRebaselines() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // 5 min + 1 ms: charge moved while unreported, so it is not attributable.
        assertNull(estimator.update(300_001L, 1_010_000L))
        val estimate = estimator.update(420_001L, 1_013_000L)!!
        assertEquals(0.09, estimate.amps, 1e-12)
        assertEquals(120_000L, estimate.windowMillis)
    }

    @Test
    fun absentOrNegativeCountersReportNothingAndClearTheBaseline() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 5_000_000L))
        assertTrue(estimator.hasBaseline())
        assertNull(estimator.update(120_000L, null))
        assertFalse(estimator.hasBaseline())
        assertNull(estimator.update(240_000L, -1L))
        assertFalse(estimator.hasBaseline())
        // Long.MIN_VALUE is the framework "unsupported" marker.
        assertNull(estimator.update(360_000L, Long.MIN_VALUE))
        assertFalse(estimator.hasBaseline())
        // A usable reading anchors again and then reports.
        assertNull(estimator.update(480_000L, 5_000_000L))
        val estimate = estimator.update(600_000L, 5_002_000L)!!
        assertEquals(0.06, estimate.amps, 1e-12)
        assertEquals(120_000L, estimate.windowMillis)
    }

    @Test
    fun theWindowGrowsWithTheCadenceButStaysTrailing() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // Polls 60 s apart: the anchor rolls forward one poll at a time.
        val first = estimator.update(60_000L, 1_001_000L)!!
        val second = estimator.update(120_000L, 1_003_000L)!!
        val third = estimator.update(180_000L, 1_004_000L)!!
        assertEquals(60_000L, first.windowMillis)
        assertEquals(60_000L, second.windowMillis)
        assertEquals(60_000L, third.windowMillis)
        // Each rate describes its own trailing minute.
        assertEquals(1000 * 3600.0 / 60.0 / 1_000_000.0, first.amps, 1e-12)
        assertEquals(2000 * 3600.0 / 60.0 / 1_000_000.0, second.amps, 1e-12)
        assertEquals(1000 * 3600.0 / 60.0 / 1_000_000.0, third.amps, 1e-12)
    }

    @Test
    fun resetDropsTheBaselineSoTheNextReadingOnlyAnchors() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        assertEquals(0.09, estimator.update(120_000L, 1_003_000L)!!.amps, 1e-12)
        estimator.reset()
        assertFalse(estimator.hasBaseline())
        // 200 s of unmodelled charge must not be attributed to the old baseline.
        assertNull(estimator.update(320_000L, 1_100_000L))
        assertEquals(120_000L, estimator.update(440_000L, 1_101_000L)!!.windowMillis)
    }

    @Test
    fun positiveCounterResetDoesNotBecomeAFalseRuntimeRate() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        assertNull(estimator.update(60_000L, 2_000_000L)) // +60 A artefact.
        val recovered = estimator.update(120_000L, 2_001_000L)!!
        assertEquals(0.06, recovered.amps, 1e-12)
        assertEquals(60_000L, recovered.windowMillis)
    }

    @Test
    fun ratesAreNotSilentlyClamped() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 0L))
        // A rate inside the policy bound is preserved exactly, never clamped.
        val estimate = estimator.update(60_000L, 150_000L)!!
        assertEquals(9.0, estimate.amps, 1e-9)
    }

    @Test
    fun meaningfulShortMovementYieldsTheFirstAverageEarly() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // 3000 uAh (3 mAh, three typical gauge steps) over 15 s: the first average
        // may arrive on the short window because the movement out-resolves the
        // counter. 3000 * 3600 / 15 / 1e6 = 0.72 A.
        val early = estimator.update(15_000L, 1_003_000L)!!
        assertEquals(0.72, early.amps, 1e-12)
        assertEquals(CurrentEstimator.INITIAL_WINDOW_MILLIS, early.windowMillis)
    }

    @Test
    fun afterTheFirstAverageTheSameWindowKeepsAnsweringAsItGrows() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        assertEquals(0.72, estimator.update(15_000L, 1_003_000L)!!.amps, 1e-12)
        // The shortcut is for acquisition only, but it must not take the average away
        // again: the same measured window keeps answering while it grows.
        val at30 = estimator.update(30_000L, 1_006_000L)!!
        assertEquals(0.72, at30.amps, 1e-12)
        assertEquals(30_000L, at30.windowMillis)
        val at45 = estimator.update(45_000L, 1_009_000L)!!
        assertEquals(0.72, at45.amps, 1e-12)
        assertEquals(45_000L, at45.windowMillis)
        val full = estimator.update(60_000L, 1_012_000L)!!
        assertEquals(60_000L, full.windowMillis)
        // 12 mAh over 60 s = 0.72 A.
        assertEquals(0.72, full.amps, 1e-12)
    }

    /**
     * The regression: with a per-second startup cadence the first average arrives on
     * the 15 s window. Every later poll of that same window must keep answering until
     * the trailing minute closes — a single `null` there would put the dashboard back
     * into Calibrating for no measurement reason at all.
     */
    @Test
    fun everyPollFromFifteenToNinetySecondsReportsAnAverageOnceAcquired() {
        val estimator = estimator()
        // A steady -720 mA discharge seen by a *coarse* 1 mAh gauge that releases
        // 1000 uAh every 5 s, polled once a second.
        var counter = 5_000_000L
        val readings = ArrayList<Pair<Long, Long>>()
        readings.add(0L to counter)
        for (second in 1L..90L) {
            if (second % 5L == 0L) counter -= 1_000L
            readings.add(second * 1_000L to counter)
        }
        var firstAverageAt: Long? = null
        val reportedAt = ArrayList<Long>()
        for ((elapsed, value) in readings) {
            val estimate = estimator.update(elapsed, value)
            if (estimate == null) {
                // Only the acquisition polls before 15 s may be empty. Any null after a
                // valid average existed is exactly the flicker this guards against.
                assertNull(
                    "no average at ${elapsed}ms after one had already been reported",
                    firstAverageAt,
                )
                continue
            }
            if (firstAverageAt == null) firstAverageAt = elapsed
            reportedAt.add(elapsed)
            // Same measured window, longer span: still the discharge, never a sign
            // flip, never faster than the true rate.
            assertTrue(-0.72 <= estimate.amps && estimate.amps < 0.0)
            assertEquals(minOf(elapsed, 60_000L), estimate.windowMillis)
        }
        assertEquals(15_000L, firstAverageAt)
        // 15..90 s inclusive, every single second.
        assertEquals((15L..90L).map { it * 1_000L }, reportedAt)
    }

    @Test
    fun aCoarseCounterStepAtAFastCadenceDoesNotRebaseline() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // 1 s polls and a gauge that releases its whole 3 mAh update in one poll.
        // Judged over that single step this is 10.8 A; over the measured window it is
        // the 2.16 A average it really is, so the window must not restart.
        assertNull(estimator.update(5_000L, 1_003_000L))
        var firstAverageAt: Long? = null
        var elapsed = 5_000L
        while (elapsed < 59_000L) {
            elapsed += 1_000L
            val estimate = estimator.update(elapsed, 1_003_000L)
            if (estimate == null) {
                assertNull(firstAverageAt)
            } else {
                if (firstAverageAt == null) firstAverageAt = elapsed
            }
        }
        // An uninterrupted baseline acquires on the 15 s window: a restart at 5 s
        // would have pushed the first average 5 s later.
        assertEquals(15_000L, firstAverageAt)
        val full = estimator.update(60_000L, 1_003_000L)!!
        assertEquals(60_000L, full.windowMillis)
        // 3 mAh over the measured minute: 180 mA.
        assertEquals(0.18, full.amps, 1e-12)
    }

    @Test
    fun anImpossibleWindowRateStillRebaselinesAtAFastCadence() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        var elapsed = 0L
        while (elapsed < 10_000L) {
            elapsed += 1_000L
            assertNull(estimator.update(elapsed, 1_000_000L))
        }
        // +1,000,000 uAh across 11 s implies 327 A. Every individual poll step is only
        // one second long, but the *window* rate is impossible, so it is still a reset.
        assertNull(estimator.update(11_000L, 2_000_000L))
        val recovered = estimator.update(71_000L, 2_003_000L)!!
        assertEquals(60_000L, recovered.windowMillis)
        assertEquals(0.18, recovered.amps, 1e-12)
    }

    @Test
    fun aModerateJumpIsStillADiscontinuityAtTheNormalCadence() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 5_000_000L))
        assertNull(estimator.update(30_000L, 4_999_000L))
        val full = estimator.update(60_000L, 4_998_000L)!!
        assertEquals(60_000L, full.windowMillis)
        // 100 mAh lost in one 5 s step. At the cadence the device really polls at that
        // is 72 A, so it is a discontinuity — even though the 60 s window would have
        // averaged a plausible-looking -5.5 A. The reference-span floor used for the
        // 1 s startup burst must not hide this.
        assertNull(estimator.update(65_000L, 4_898_000L))
        // Recovery is an ordinary re-anchored window, never a clamped value.
        val recovered = estimator.update(125_000L, 4_895_000L)!!
        assertEquals(60_000L, recovered.windowMillis)
        assertEquals(-0.18, recovered.amps, 1e-12)
    }

    @Test
    fun aGrowingWindowStopsAnsweringWhenTheNetMovementIsNoLongerMeaningful() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        assertEquals(0.72, estimator.update(15_000L, 1_003_000L)!!.amps, 1e-12)
        // The counter gives most of the movement back: only 1000 uAh net is left, which
        // a 20 s window cannot out-resolve. Unknown is the honest answer; the raw
        // division would claim a 50 mA average.
        assertNull(estimator.update(20_000L, 1_001_000L))
        // Once the ordinary window closes, it reports what it measured.
        val full = estimator.update(60_000L, 1_001_000L)!!
        assertEquals(60_000L, full.windowMillis)
        assertEquals(0.06, full.amps, 1e-12)
    }

    @Test
    fun aCounterThatStopsMovingKeepsTheGrowingWindowAverage() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        assertEquals(0.72, estimator.update(15_000L, 1_003_000L)!!.amps, 1e-12)
        // Nothing moves afterwards, so the same 3 mAh is spread over a longer window.
        // The average decays instead of disappearing: the total movement is still real.
        val at30 = estimator.update(30_000L, 1_003_000L)!!
        assertEquals(0.36, at30.amps, 1e-12)
        assertEquals(30_000L, at30.windowMillis)
        val at60 = estimator.update(60_000L, 1_003_000L)!!
        assertEquals(0.18, at60.amps, 1e-12)
        assertEquals(60_000L, at60.windowMillis)
    }

    @Test
    fun shortWindowJustBelowTheMovementThresholdStillWaits() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // One microamp-hour short of meaningful: the gauge could be deciding this.
        assertNull(estimator.update(15_000L, 1_002_999L))
        // The anchor was kept, so the ordinary window still closes at 60 s.
        val full = estimator.update(60_000L, 1_012_000L)!!
        assertEquals(60_000L, full.windowMillis)
        assertEquals(0.72, full.amps, 1e-12)
    }

    @Test
    fun shortFlatCounterNeverManufacturesZero() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 2_000_000L))
        // A flat 15 s counter is not evidence of 0 A: it stays unknown.
        assertNull(estimator.update(15_000L, 2_000_000L))
        // ... and so does a flat 60 s counter (the zero window is much longer).
        assertNull(estimator.update(60_000L, 2_000_000L))
        // Polled a minute apart: the flat stretch stays unknown while a *gap* would
        // have re-anchored the baseline instead.
        assertNull(estimator.update(120_000L, 2_000_000L))
        assertNull(estimator.update(180_000L, 2_000_000L))
        assertNull(estimator.update(240_000L, 2_000_000L))
        assertNull(estimator.update(300_000L, 2_000_000L))
        assertNull(estimator.update(360_000L, 2_000_000L))
        assertNull(estimator.update(420_000L, 2_000_000L))
        assertNull(estimator.update(480_000L, 2_000_000L))
        assertNull(estimator.update(540_000L, 2_000_000L))
        val zero = estimator.update(600_000L, 2_000_000L)!!
        assertEquals(0.0, zero.amps, 0.0)
        // The rate still describes a bounded trailing window, not the whole session.
        assertEquals(60_000L, zero.windowMillis)
    }

    @Test
    fun reversalInsideTheShortWindowDisqualifiesTheShortcut() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        // Charging, then discharge: the net is still +3500 uAh but the movement is
        // mixed, so a 15 s average would describe neither direction.
        assertNull(estimator.update(10_000L, 1_004_000L))
        assertNull(estimator.update(15_000L, 1_003_500L))
        val full = estimator.update(60_000L, 1_004_000L)!!
        assertEquals(60_000L, full.windowMillis)
        assertEquals(240.0, full.amps * 1000.0, 1e-9)
    }

    @Test
    fun theEarlyWindowKeepsTheCountersSign() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 5_000_000L))
        // Discharge: -3000 uAh over 15 s = -0.72 A, never a magnitude-only rate.
        val early = estimator.update(15_000L, 4_997_000L)!!
        assertEquals(-0.72, early.amps, 1e-12)
        assertEquals(15_000L, early.windowMillis)
    }

    @Test
    fun anInterruptedBaselineMayAcquireEarlyAgain() {
        val estimator = estimator()
        assertNull(estimator.update(0L, 1_000_000L))
        assertEquals(0.72, estimator.update(15_000L, 1_003_000L)!!.amps, 1e-12)
        // A sleep-length gap drops the baseline: the old evidence is gone, so the
        // next acquisition is a fresh one and may use the short window again.
        assertNull(estimator.update(400_000L, 1_010_000L))
        val early = estimator.update(415_000L, 1_013_000L)!!
        assertEquals(CurrentEstimator.INITIAL_WINDOW_MILLIS, early.windowMillis)
        assertEquals(0.72, early.amps, 1e-12)
    }
}
