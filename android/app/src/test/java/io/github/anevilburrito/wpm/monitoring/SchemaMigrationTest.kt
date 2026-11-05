package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capacity estimator changed meaning in schema version 4; these tests pin the
 * migration contract that only the stale aggregate is invalidated.
 */
class SchemaMigrationTest {

    @Test fun theCurrentVersionIsTheProvenanceUpgrade() {
        assertEquals(4, SchemaMigration.VERSION)
        assertEquals(4, SchemaMigration.CAPACITY_ESTIMATOR_VERSION)
    }

    @Test fun upgradingFromAnOlderSchemaInvalidatesOnlyTheCapacityAggregate() {
        // v1..v3 aggregates were single-point gauge divisions, not observed swings.
        assertTrue(SchemaMigration.invalidatesCapacityAggregate(1))
        assertTrue(SchemaMigration.invalidatesCapacityAggregate(2))
        assertTrue(SchemaMigration.invalidatesCapacityAggregate(3))
        // Already current: keep the (comparable) observed aggregate.
        assertFalse(SchemaMigration.invalidatesCapacityAggregate(4))
        assertFalse(SchemaMigration.invalidatesCapacityAggregate(5))
        // The invalidation rule is separate from the additive column rule so history
        // is never dropped alongside it.
        assertTrue(SchemaMigration.addsProvenanceColumns(3))
        assertFalse(SchemaMigration.addsProvenanceColumns(4))
    }

    @Test fun provenanceColumnsAreAdditiveAndDefaulted() {
        val names = SchemaMigration.V4_SAMPLE_COLUMNS.map { it.name }
        assertEquals(listOf("rawAverageMicroamps", "currentCalibrated", "currentPolarityAdjusted"), names)
        // Old rows must stay readable: no NOT NULL without a default.
        SchemaMigration.V4_SAMPLE_COLUMNS.forEach { column ->
            if (column.definition.contains("NOT NULL")) {
                assertTrue(column.definition, column.definition.contains("DEFAULT"))
            }
        }
        assertTrue(SchemaMigration.V4_CAPACITY_METHOD_COLUMN.definition.contains("DEFAULT"))
    }

    @Test fun theCapacityMethodMarkerNamesTheObservedSwing() {
        assertEquals("observed_charge_counter_swing_v1", SchemaMigration.CAPACITY_METHOD)
    }
}
