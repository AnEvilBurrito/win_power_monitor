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
        assertEquals(6, SchemaMigration.VERSION)
        assertEquals(4, SchemaMigration.CAPACITY_ESTIMATOR_VERSION)
        assertEquals(5, SchemaMigration.DISPLAY_STATE_VERSION)
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

    @Test fun theDisplayStateUpgradeIsAdditiveAndNeverAssumesAScreenState() {
        // v5 adds the display state to samples and the split to summaries.
        assertTrue(SchemaMigration.addsDisplayStateColumns(4))
        assertFalse(SchemaMigration.addsDisplayStateColumns(5))
        // An upgraded sample must read back as "not recorded" (NULL), because a
        // default of 0 would mean "screen off" and invent screen-off time.
        val sampleColumn = SchemaMigration.V5_SAMPLE_COLUMNS.single()
        assertEquals("displayOn", sampleColumn.name)
        assertEquals("INTEGER", sampleColumn.definition)
        assertFalse(sampleColumn.definition.contains("DEFAULT"))
        // The summary keeps its real totals and reports no split for old sessions.
        val names = SchemaMigration.V5_SESSION_COLUMNS.map { it.name }
        assertEquals(
            listOf(
                "displayOnSeconds",
                "displayOffSeconds",
                "displayUnknownSeconds",
                "displayOnWallSeconds",
                "displayOffWallSeconds",
                "displayUnknownWallSeconds",
                "displayOnOutWh",
                "displayOnInWh",
                "displayOffOutWh",
                "displayOffInWh",
            ),
            names,
        )
        SchemaMigration.V5_SESSION_COLUMNS.forEach { column ->
            assertTrue(column.name, column.definition.contains("DEFAULT 0"))
        }
        // A v5 upgrade from v4 must not discard the observed capacity aggregate.
        assertFalse(SchemaMigration.invalidatesCapacityAggregate(4))
        assertFalse(SchemaMigration.invalidatesCapacityAggregate(5))
        assertFalse(SchemaMigration.invalidatesCapacityAggregate(6))
    }

    @Test fun theDischargeBasisUpgradeIsAdditive() {
        // v6 adds the per-bucket discharging time that the drain average divides by.
        assertTrue(SchemaMigration.addsDischargeSecondsColumns(5))
        assertFalse(SchemaMigration.addsDischargeSecondsColumns(6))
        // A v5 session keeps its totals and simply reports no discharge average.
        assertEquals(
            listOf("displayOnDischargeSeconds", "displayOffDischargeSeconds"),
            SchemaMigration.V6_SESSION_COLUMNS.map { it.name },
        )
        SchemaMigration.V6_SESSION_COLUMNS.forEach { column ->
            assertTrue(column.name, column.definition.contains("DEFAULT 0"))
        }
    }
}
