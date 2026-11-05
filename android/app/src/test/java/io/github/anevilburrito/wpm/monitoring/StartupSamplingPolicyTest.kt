package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded startup burst: 1 s polls to establish the calibrated instantaneous
 * interpretation, never more than 90 s per recording/reset, never a setting change,
 * never a wake lock. The burst deliberately does not wait for the counter average.
 */
class StartupSamplingPolicyTest {

    private fun decide(
        now: Long,
        burstStart: Long?,
        calibrationEstablished: Boolean = false,
        instantSupported: Boolean = true,
        configuredIntervalMillis: Long = 2_000L,
    ) = StartupSamplingPolicy.decide(
        nowElapsedMillis = now,
        burstStartElapsedMillis = burstStart,
        instantCalibrationEstablished = calibrationEstablished,
        instantReadingSupported = instantSupported,
        configuredIntervalMillis = configuredIntervalMillis,
    )

    @Test
    fun startBeginsAOneSecondBurst() {
        val started = StartupSamplingPolicy.start(nowElapsedMillis = 42_000L)
        assertEquals(StartupSamplingPolicy.BURST_INTERVAL_MILLIS, started.intervalMillis)
        assertEquals(StartupSamplingPolicy.BURST_MINIMUM_SPACING_MILLIS, started.minimumSpacingMillis)
        assertEquals(42_000L, started.burstStartElapsedMillis)
        assertTrue(started.burstActive)
    }

    @Test
    fun burstPollsFasterThanEverySupportedUserInterval() {
        for (configured in listOf(1_000L, 2_000L, 5_000L)) {
            val decision = decide(now = 1_000L, burstStart = 0L, configuredIntervalMillis = configured)
            assertEquals(StartupSamplingPolicy.BURST_INTERVAL_MILLIS, decision.intervalMillis)
            assertEquals(StartupSamplingPolicy.BURST_MINIMUM_SPACING_MILLIS, decision.minimumSpacingMillis)
            assertTrue(decision.burstActive)
        }
    }

    @Test
    fun noBurstUsesTheConfiguredCadenceAsItsOwnSpacingFloor() {
        // The configured interval is the floor, not a fixed 1.5 s: a 1 s selection is
        // honoured exactly, and battery events cannot speed up 2 s/5 s selections.
        for (configured in listOf(1_000L, 2_000L, 5_000L)) {
            val decision = decide(now = 10_000L, burstStart = null, configuredIntervalMillis = configured)
            assertEquals(configured, decision.intervalMillis)
            assertEquals(configured, decision.minimumSpacingMillis)
            assertNull(decision.burstStartElapsedMillis)
            assertFalse(decision.burstActive)
        }
    }

    @Test
    fun burstEndsAsSoonAsTheInstantInterpretationIsEstablished() {
        val decision = decide(
            now = 20_000L,
            burstStart = 0L,
            calibrationEstablished = true,
            configuredIntervalMillis = 2_000L,
        )
        assertFalse(decision.burstActive)
        assertNull(decision.burstStartElapsedMillis)
        assertEquals(2_000L, decision.intervalMillis)
        // No fixed 1.5 s floor remains.
        assertEquals(2_000L, decision.minimumSpacingMillis)
    }

    @Test
    fun anAvailableCounterAverageDoesNotEndTheBurstEarlyOrHoldItBack() {
        // The old average-first policy kept the burst alive until the average existed.
        // Now the burst only cares about the instantaneous interpretation: it is still
        // running when that is unestablished, and it is over once that is established —
        // regardless of the counter average.
        val unestablished = decide(now = 20_000L, burstStart = 0L, calibrationEstablished = false)
        assertTrue(unestablished.burstActive)
        val established = decide(now = 20_000L, burstStart = 0L, calibrationEstablished = true)
        assertFalse(established.burstActive)
    }

    @Test
    fun anUnsupportedInstantPropertyEndsTheBurstImmediately() {
        // No usable CURRENT_NOW can ever be calibrated, so staying at 1 s would burn
        // the whole cap for evidence that cannot arrive. This no longer waits for an
        // unrelated counter average.
        val decision = decide(
            now = 2_000L,
            burstStart = 0L,
            calibrationEstablished = false,
            instantSupported = false,
            configuredIntervalMillis = 2_000L,
        )
        assertFalse(decision.burstActive)
        assertEquals(2_000L, decision.intervalMillis)
    }

    @Test
    fun aFailedFirstPollDoesNotLookLikeAnUnsupportedProperty() {
        // The caller passes instantReadingSupported = true while no successful poll has
        // been observed yet; a transient read failure must not end the burst.
        val decision = decide(
            now = 1_000L,
            burstStart = 0L,
            calibrationEstablished = false,
            instantSupported = true,
            configuredIntervalMillis = 1_000L,
        )
        assertTrue(decision.burstActive)
        assertEquals(StartupSamplingPolicy.BURST_INTERVAL_MILLIS, decision.intervalMillis)
    }

    @Test
    fun theBurstIsHardCappedAtNinetySeconds() {
        val justInside = decide(now = 89_999L, burstStart = 0L)
        assertTrue(justInside.burstActive)
        val atCap = decide(now = 90_000L, burstStart = 0L, configuredIntervalMillis = 5_000L)
        assertFalse(atCap.burstActive)
        assertEquals(5_000L, atCap.intervalMillis)
        assertEquals(5_000L, atCap.minimumSpacingMillis)
        val pastCap = decide(now = 600_000L, burstStart = 0L)
        assertFalse(pastCap.burstActive)
    }

    @Test
    fun aBackwardsMonotonicClockEndsTheBurstInsteadOfExtendingIt() {
        val decision = decide(now = 1_000L, burstStart = 5_000L)
        assertFalse(decision.burstActive)
        assertNull(decision.burstStartElapsedMillis)
        assertEquals(2_000L, decision.intervalMillis)
    }

    @Test
    fun anExplicitResetStartsANewBoundedBurst() {
        // After the first burst ended, a user reset re-opens it at the new time.
        val first = StartupSamplingPolicy.start(nowElapsedMillis = 0L)
        val ended = StartupSamplingPolicy.decide(
            nowElapsedMillis = 30_000L,
            burstStartElapsedMillis = first.burstStartElapsedMillis,
            instantCalibrationEstablished = true,
            instantReadingSupported = true,
            configuredIntervalMillis = 2_000L,
        )
        assertFalse(ended.burstActive)
        val restarted = StartupSamplingPolicy.start(nowElapsedMillis = 120_000L)
        assertEquals(120_000L, restarted.burstStartElapsedMillis)
        assertTrue(restarted.burstActive)
        // The new burst has its own 90 s budget: fresh evidence is the point of the
        // reset, so an already-established value does not shorten it to nothing.
        val stillRunning = StartupSamplingPolicy.decide(
            nowElapsedMillis = 200_000L,
            burstStartElapsedMillis = restarted.burstStartElapsedMillis,
            instantCalibrationEstablished = false,
            instantReadingSupported = true,
            configuredIntervalMillis = 2_000L,
        )
        assertTrue(stillRunning.burstActive)
    }

    @Test
    fun anImplausiblyFastConfiguredIntervalNeverMakesTheBurstSlower() {
        // Nothing in the app normalises below one second, but the decision stays sane
        // if it ever did: the burst never polls slower than its own 1 s cadence and
        // the normal path never polls slower than the one-second absolute floor.
        val bursting = decide(now = 0L, burstStart = 0L, configuredIntervalMillis = 500L)
        assertEquals(StartupSamplingPolicy.BURST_INTERVAL_MILLIS, bursting.intervalMillis)
        assertEquals(StartupSamplingPolicy.BURST_MINIMUM_SPACING_MILLIS, bursting.minimumSpacingMillis)

        val normal = decide(now = 0L, burstStart = null, configuredIntervalMillis = 500L)
        assertEquals(StartupSamplingPolicy.MINIMUM_INTERVAL_MILLIS, normal.intervalMillis)
        assertEquals(StartupSamplingPolicy.MINIMUM_INTERVAL_MILLIS, normal.minimumSpacingMillis)
    }

    @Test
    fun aOneSecondSelectionIsHonouredWithoutTheOldOnePointFiveSecondFloor() {
        val normal = decide(now = 10_000L, burstStart = null, configuredIntervalMillis = 1_000L)
        assertEquals(1_000L, normal.intervalMillis)
        assertEquals(1_000L, normal.minimumSpacingMillis)
        assertFalse(normal.minimumSpacingMillis > normal.intervalMillis)
    }
}
