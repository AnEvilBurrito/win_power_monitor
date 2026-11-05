package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the throttle that decides when a dirty capacity aggregate is written, which
 * is also the retry rule for a write that failed with no new observation.
 */
class CapacityPersistenceTest {

    @Test
    fun aCleanAggregateIsNeverWritten() {
        assertFalse(CapacityPersistence.isDue(dirty = false, nowElapsedMillis = 999_999L, lastPersistElapsedMillis = 0L))
    }

    @Test
    fun theFirstObservationInAProcessIsDueImmediately() {
        // 0 = "never written in this process", even on a freshly booted device.
        assertTrue(CapacityPersistence.isDue(true, nowElapsedMillis = 0L, lastPersistElapsedMillis = 0L))
        assertTrue(CapacityPersistence.isDue(true, nowElapsedMillis = 5_000L, lastPersistElapsedMillis = 0L))
    }

    @Test
    fun aFailedWriteIsRetriedOnlyAfterTheThrottleWindow() {
        val last = 120_000L
        // A retry on the very next poll (a few seconds later) is coalesced.
        assertFalse(CapacityPersistence.isDue(true, nowElapsedMillis = last + 5_000L, lastPersistElapsedMillis = last))
        assertFalse(CapacityPersistence.isDue(true, nowElapsedMillis = last + 59_999L, lastPersistElapsedMillis = last))
        // Once the window elapses, an ordinary poll retries it.
        assertTrue(CapacityPersistence.isDue(true, nowElapsedMillis = last + 60_000L, lastPersistElapsedMillis = last))
    }

    @Test
    fun aStopFlushesWhateverIsStillDirty() {
        assertFalse(CapacityPersistence.isDueAtStop(dirty = false))
        assertTrue(CapacityPersistence.isDueAtStop(dirty = true))
    }
}
