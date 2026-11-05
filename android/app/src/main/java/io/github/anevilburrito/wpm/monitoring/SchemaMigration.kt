package io.github.anevilburrito.wpm.monitoring

/**
 * Upgrade rules for `wpm_history.db`, kept Android-free so the additive/destructive
 * decisions are JVM tested instead of only reviewed.
 *
 * Every bump so far is additive; version 4 is the first upgrade that must *discard*
 * something, and only one thing: the stored capacity aggregate. Its estimator
 * changed from a single-point gauge division to an observed charging-window swing,
 * so an old number would be a different quantity presented under the new label.
 * Recorded sessions and raw samples are never touched by the migration.
 */
internal object SchemaMigration {
    /** Schema version written by this build. */
    const val VERSION = 4

    /**
     * Version in which the stored capacity aggregate started meaning *observed
     * charging-window capacity*. Aggregates written before it are not comparable.
     */
    const val CAPACITY_ESTIMATOR_VERSION = 4

    /**
     * Marker stored beside an aggregate. It makes the quantity self-describing, so a
     * row written by the old estimator can never be read back as an observed
     * capacity even if it were somehow still present.
     */
    const val CAPACITY_METHOD = "observed_charge_counter_swing_v1"

    /** One column and its SQL definition. */
    data class Column(val name: String, val definition: String)

    /**
     * Instant-reading provenance added by version 4. Nullable or defaulted, so an
     * older row stays readable as "calibration was not known for this reading".
     */
    val V4_SAMPLE_COLUMNS = listOf(
        Column("rawAverageMicroamps", "INTEGER"),
        Column("currentCalibrated", "INTEGER NOT NULL DEFAULT 0"),
        Column("currentPolarityAdjusted", "INTEGER NOT NULL DEFAULT 0"),
    )

    /** The `capacity_state` marker column added by version 4. */
    val V4_CAPACITY_METHOD_COLUMN = Column("method", "TEXT NOT NULL DEFAULT ''")

    /** Whether an upgrade from [oldVersion] adds the v4 sample provenance columns. */
    fun addsProvenanceColumns(oldVersion: Int): Boolean = oldVersion < VERSION

    /**
     * Whether an upgrade from [oldVersion] must invalidate the stored capacity
     * aggregate. Only the aggregate: session summaries and samples are preserved.
     */
    fun invalidatesCapacityAggregate(oldVersion: Int): Boolean =
        oldVersion < CAPACITY_ESTIMATOR_VERSION
}
