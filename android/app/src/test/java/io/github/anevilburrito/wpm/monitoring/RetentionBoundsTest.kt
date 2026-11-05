package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.MonitorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retention is limited by BOTH age and rows, not a week-long storage guarantee. */
class RetentionBoundsTest {
    private val sevenDaysSeconds = 7L * 24 * 60 * 60

    @Test fun fasterPollingDoesNotExpandTheDurableStorageBudget() {
        assertEquals(130_000, MonitoringRepository.MAX_STORED_SAMPLES)
        assertEquals(sevenDaysSeconds * 1000L, MonitoringRepository.RETENTION_MILLIS)
    }

    @Test fun theRowCeilingIsReachedBeforeSevenDaysAtOneAndTwoSeconds() {
        for (cadence in listOf(1, MonitorSettings.DEFAULT_INTERVAL_SECONDS)) {
            val retainedSeconds = MonitoringRepository.MAX_STORED_SAMPLES.toLong() * cadence
            assertTrue(retainedSeconds < sevenDaysSeconds)
        }
        // At 5s the age limit is reached first instead.
        assertTrue(MonitoringRepository.MAX_STORED_SAMPLES.toLong() * 5 > sevenDaysSeconds)
    }

    @Test fun exportBoundMatchesTheStoredBound() {
        assertEquals(MonitoringRepository.MAX_STORED_SAMPLES, MonitoringRepository.MAX_EXPORT_SAMPLES)
    }

    @Test fun theLiveWindowStillCoversTheTwoHourChartAtEverySelectableCadence() {
        for (cadence in MonitorSettings.ALLOWED_INTERVALS) {
            val samplesInTwoHours = 2 * 60 * 60 / cadence + 1
            assertTrue(MonitoringRepository.MAX_UI_SAMPLES >= samplesInTwoHours)
        }
        assertEquals(8192, MonitoringRepository.MAX_UI_SAMPLES)
    }
}
