package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.LiveReading
import io.github.anevilburrito.wpm.model.NotificationUpdateRate
import io.github.anevilburrito.wpm.model.liveReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification transition/throttle policy: a live reading appearing must bypass the
 * selected throttle even when no counter average exists yet.
 */
class NotificationUpdatePolicyTest {

    private fun liveSample() = BatterySample(
        timestampMillis = 1_000L,
        elapsedRealtimeMillis = 1_000L,
        amps = -0.45,
        watts = -1.72,
        volts = 3.82,
        percentage = 64,
        currentCalibrated = true,
        currentSource = CurrentSource.INSTANT,
        chargeCounterMicroampHours = 5_000_000L,
        // Deliberately no derived average: the live reading must not wait for it.
        derivedAmps = null,
        derivedWindowMillis = null,
    )

    private fun pendingSample() = BatterySample(
        timestampMillis = 1_000L,
        elapsedRealtimeMillis = 1_000L,
        amps = -0.45,
        watts = -1.72,
        volts = 3.82,
        percentage = 64,
        currentCalibrated = false,
        currentSource = CurrentSource.INSTANT,
        chargeCounterMicroampHours = 5_000_000L,
    )

    private fun due(rate: NotificationUpdateRate, elapsedMillis: Long): Boolean =
        NotificationUpdatePolicy.shouldUpdate(
            rate = rate,
            nowElapsedMillis = 100_000L + elapsedMillis,
            lastUpdateElapsedMillis = 100_000L,
            force = false,
            previousShowsSample = true,
            previousShowsLive = true,
            sample = liveSample(),
        )

    @Test fun instantAddsNoThrottleToAnyPollEvenWithTheSameReading() {
        for (elapsed in listOf(0L, 1_000L, 2_000L, 5_000L)) {
            assertTrue(due(NotificationUpdateRate.INSTANT, elapsed))
        }
    }

    @Test fun fifteenSecondsUsesTheExactThreshold() {
        assertFalse(due(NotificationUpdateRate.FIFTEEN_SECONDS, 0L))
        assertFalse(due(NotificationUpdateRate.FIFTEEN_SECONDS, 14_999L))
        assertTrue(due(NotificationUpdateRate.FIFTEEN_SECONDS, 15_000L))
    }

    @Test fun sixtySecondsPreservesTheOldThreshold() {
        assertFalse(due(NotificationUpdateRate.SIXTY_SECONDS, 59_999L))
        assertTrue(due(NotificationUpdateRate.SIXTY_SECONDS, 60_000L))
    }

    @Test fun timedRatesWaitForTheNextAvailablePollWithoutAddingATimer() {
        var lastUpdate = 0L
        val posts = ArrayList<Long>()
        for (now in 2_000L..32_000L step 2_000L) {
            if (NotificationUpdatePolicy.shouldUpdate(
                    rate = NotificationUpdateRate.FIFTEEN_SECONDS,
                    nowElapsedMillis = now,
                    lastUpdateElapsedMillis = lastUpdate,
                    force = false,
                    previousShowsSample = true,
                    previousShowsLive = true,
                    sample = liveSample(),
                )
            ) {
                posts += now
                lastUpdate = now
            }
        }
        assertEquals(listOf(16_000L, 32_000L), posts)
    }

    @Test fun importantTransitionsAndSettingChangesBypassEverySelectedRate() {
        for (rate in NotificationUpdateRate.entries) {
            for (force in listOf(false, true)) {
                assertTrue(NotificationUpdatePolicy.shouldUpdate(
                    rate, 100_001L, 100_000L, force,
                    previousShowsSample = true,
                    previousShowsLive = false,
                    sample = liveSample(), // Calibration has just completed.
                ))
            }
            assertTrue(NotificationUpdatePolicy.shouldUpdate(
                rate, 100_001L, 100_000L, force = true,
                previousShowsSample = true,
                previousShowsLive = true,
                sample = liveSample(), // Changing rate refreshes the posted value now.
            ))
        }
    }

    @Test fun clockRegressionDoesNotLeaveTheNotificationThrottledForever() {
        assertTrue(due(NotificationUpdateRate.SIXTY_SECONDS, -1L))
    }

    @Test
    fun forceAlwaysBypassesTheThrottle() {
        assertTrue(
            NotificationUpdatePolicy.bypassesThrottle(
                force = true,
                previousShowsSample = true,
                previousShowsLive = true,
                sample = liveSample(),
            ),
        )
    }

    @Test
    fun theFirstLiveReadingBypassesTheThrottleEvenWithoutACounterAverage() {
        // Previous notification was posted with the same pending sample (no live
        // value). The moment the live reading exists the line must change at once.
        assertTrue(
            NotificationUpdatePolicy.bypassesThrottle(
                force = false,
                previousShowsSample = true,
                previousShowsLive = false,
                sample = liveSample(),
            ),
        )
    }

    @Test
    fun aPendingReadingDoesNotBypassAnUnchangedThrottle() {
        assertFalse(
            NotificationUpdatePolicy.bypassesThrottle(
                force = false,
                previousShowsSample = true,
                previousShowsLive = false,
                sample = pendingSample(),
            ),
        )
    }

    @Test
    fun aReadingAppearingOrDisappearingBypassesTheThrottle() {
        assertTrue(
            NotificationUpdatePolicy.bypassesThrottle(
                force = false,
                previousShowsSample = false,
                previousShowsLive = false,
                sample = pendingSample(),
            ),
        )
        assertTrue(
            NotificationUpdatePolicy.bypassesThrottle(
                force = false,
                previousShowsSample = true,
                previousShowsLive = true,
                sample = null,
            ),
        )
    }

    @Test
    fun anUnchangedLiveReadingStaysThrottled() {
        assertFalse(
            NotificationUpdatePolicy.bypassesThrottle(
                force = false,
                previousShowsSample = true,
                previousShowsLive = true,
                sample = liveSample(),
            ),
        )
    }

    @Test
    fun readinessNeverReportsAnUncalibratedValueAsLive() {
        // Only a READY sample may carry a live number into the notification.
        assertEquals(LiveReading.CALIBRATING, pendingSample().liveReading())
        assertEquals(LiveReading.READY, liveSample().liveReading())
    }
}
