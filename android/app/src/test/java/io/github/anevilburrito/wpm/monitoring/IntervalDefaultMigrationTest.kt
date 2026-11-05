package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.MonitorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-time migration to the 1/2/5-second cadence contract, decided without Android
 * preferences.
 *
 * The old 5 s default cannot be distinguished from a deliberate 5 s choice, so the
 * first migration rewrites it once behind a NEW marker. Only 1 and 2 survive that
 * first run; after the marker, explicit 1/2/5 values are authoritative and anything
 * invalid is normalised to 2 and persisted.
 */
class IntervalDefaultMigrationTest {

    @Test
    fun untouchedLegacyDefaultsMoveToTheNewDefaultOnce() {
        // Both historical defaults are indistinguishable from a deliberate choice:
        // they are migrated once, and the marker prevents a second rewrite.
        val old5 = IntervalDefaultMigration.decide(storedIntervalSeconds = 5, alreadyMigrated = false)
        assertEquals(2, old5.intervalSeconds)
        assertTrue(old5.persist)

        val old15 = IntervalDefaultMigration.decide(storedIntervalSeconds = 15, alreadyMigrated = false)
        assertEquals(2, old15.intervalSeconds)
        assertTrue(old15.persist)

        val old30 = IntervalDefaultMigration.decide(storedIntervalSeconds = 30, alreadyMigrated = false)
        assertEquals(2, old30.intervalSeconds)
        assertTrue(old30.persist)
    }

    @Test
    fun theNewContractValuesSurviveTheFirstMigration() {
        // 1 s and 2 s were impossible as old defaults, so they are deliberate.
        val one = IntervalDefaultMigration.decide(storedIntervalSeconds = 1, alreadyMigrated = false)
        assertEquals(1, one.intervalSeconds)
        assertTrue(one.persist)

        val two = IntervalDefaultMigration.decide(storedIntervalSeconds = 2, alreadyMigrated = false)
        assertEquals(2, two.intervalSeconds)
        assertTrue(two.persist)
    }

    @Test
    fun anInvalidOrMissingStoredValueBecomesTheDefaultAndIsPersisted() {
        val invalid = IntervalDefaultMigration.decide(storedIntervalSeconds = 7, alreadyMigrated = false)
        assertEquals(MonitorSettings.DEFAULT_INTERVAL_SECONDS, invalid.intervalSeconds)
        assertTrue(invalid.persist)

        val zero = IntervalDefaultMigration.decide(storedIntervalSeconds = 0, alreadyMigrated = false)
        assertEquals(MonitorSettings.DEFAULT_INTERVAL_SECONDS, zero.intervalSeconds)
        assertTrue(zero.persist)

        val fresh = IntervalDefaultMigration.decide(storedIntervalSeconds = null, alreadyMigrated = false)
        assertEquals(MonitorSettings.DEFAULT_INTERVAL_SECONDS, fresh.intervalSeconds)
        // Persisted only so the marker itself is written; the interval is unchanged.
        assertTrue(fresh.persist)
    }

    @Test
    fun afterMigrationExplicitOneTwoAndFiveSurviveRestarts() {
        listOf(1, 2, 5).forEach { chosen ->
            val kept = IntervalDefaultMigration.decide(
                storedIntervalSeconds = chosen,
                alreadyMigrated = true,
            )
            assertEquals(chosen, kept.intervalSeconds)
            assertFalse(kept.persist)
        }
    }

    @Test
    fun afterMigrationInvalidStoredValuesNormaliseToTwoAndArePersisted() {
        listOf(0, -5, 3, 7, 15, 30).forEach { invalid ->
            val normalized = IntervalDefaultMigration.decide(
                storedIntervalSeconds = invalid,
                alreadyMigrated = true,
            )
            assertEquals(2, normalized.intervalSeconds)
            assertTrue("$invalid must be rewritten", normalized.persist)
        }
    }

    @Test
    fun afterMigrationAMissingValueIsTheDefaultWithoutAWrite() {
        val missing = IntervalDefaultMigration.decide(storedIntervalSeconds = null, alreadyMigrated = true)
        assertEquals(2, missing.intervalSeconds)
        assertFalse(missing.persist)
    }

    @Test
    fun theOld15To5MarkerCannotSuppressThisMigration() {
        // The old marker lived under a different key; the migration API only sees the
        // NEW marker. An install that already ran old marker (alreadyMigrated = false
        // for the new key) still migrates its stored 5 once.
        val alreadyRanOldMarker = IntervalDefaultMigration.decide(
            storedIntervalSeconds = 5,
            alreadyMigrated = false,
        )
        assertEquals(2, alreadyRanOldMarker.intervalSeconds)
        assertTrue(alreadyRanOldMarker.persist)
    }

    @Test
    fun theMigratedValueIsAnAllowedValue() {
        assertTrue(
            IntervalDefaultMigration.MIGRATED_INTERVAL_SECONDS in MonitorSettings.ALLOWED_INTERVALS,
        )
        assertEquals(2, IntervalDefaultMigration.MIGRATED_INTERVAL_SECONDS)
        assertEquals(15, IntervalDefaultMigration.LEGACY_DEFAULT_INTERVAL_SECONDS)
        assertEquals(5, IntervalDefaultMigration.PREVIOUS_DEFAULT_INTERVAL_SECONDS)
    }
}
