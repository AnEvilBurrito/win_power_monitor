package io.github.anevilburrito.wpm.monitoring

/**
 * Upgrade rules for `wpm_history.db`, kept Android-free so the additive/destructive
 * decisions are JVM tested instead of only reviewed.
 *
 * Every bump so far is additive to history; version 4 is the only one that must
 * *discard* something, and only one thing: the stored capacity aggregate. Its
 * estimator changed from a single-point gauge division to an observed
 * charging-window swing, so an old number would be a different quantity presented
 * under the new label. Recorded sessions and raw samples are never touched by the
 * migration.
 *
 * Version 5 adds the display state of a sample and the display-state split of a
 * session summary. Both are additive: an existing row reads back as "screen state
 * not recorded" (null on a sample, zero bucket seconds on a summary), which the UI
 * labels instead of inferring.
 */
internal object SchemaMigration {
    /** Schema version written by this build. */
    const val VERSION = 6

    /** Version that introduced the display-state split. */
    const val DISPLAY_STATE_VERSION = 5

    /**
     * Version in which the stored capacity aggregate started meaning *observed
     * charging-window capacity*. Aggregates written before it are not comparable.
     */
    const val CAPACITY_ESTIMATOR_VERSION = 4

    /**
     * Marker stored beside an aggregate. It makes the quantity self-describing, so a
     * row written by the old estimator can never be read back as an observed
     * capacity even if it were somehow still present. Accepting 20-point windows
     * in either direction changes the evidence policy, not the stored quantity:
     * earlier 60-point charging observations remain valid under the same marker.
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

    /**
     * Display state added by version 5. Nullable on the sample, because only a poll
     * taken by this build knows whether the display was on, and a default would turn
     * "not recorded" into a claim about the screen.
     */
    val V5_SAMPLE_COLUMNS = listOf(Column("displayOn", "INTEGER"))

    /**
     * The session summary's display-state split, added by version 5. Zeroed rather
     * than defaulted to a plausible split: an earlier session's screen state is
     * unknown, and the UI says so.
     */
    val V5_SESSION_COLUMNS = listOf(
        Column("displayOnSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayOffSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayUnknownSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayOnWallSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayOffWallSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayUnknownWallSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayOnOutWh", "REAL NOT NULL DEFAULT 0"),
        Column("displayOnInWh", "REAL NOT NULL DEFAULT 0"),
        Column("displayOffOutWh", "REAL NOT NULL DEFAULT 0"),
        Column("displayOffInWh", "REAL NOT NULL DEFAULT 0"),
    )

    /** Whether an upgrade from [oldVersion] adds the v4 sample provenance columns. */
    fun addsProvenanceColumns(oldVersion: Int): Boolean = oldVersion < CAPACITY_ESTIMATOR_VERSION

    /** Whether an upgrade from [oldVersion] adds the v5 display-state columns. */
    fun addsDisplayStateColumns(oldVersion: Int): Boolean = oldVersion < DISPLAY_STATE_VERSION

    /**
     * Discharging time per bucket, added by version 6.
     *
     * The bucket average is discharge-only, so it needs a denominator that excludes
     * charging. Version 5 stored the mixed split only; a session recorded with it
     * keeps its real totals and reports no discharge average rather than having one
     * computed from the wrong basis.
     */
    val V6_SESSION_COLUMNS = listOf(
        Column("displayOnDischargeSeconds", "REAL NOT NULL DEFAULT 0"),
        Column("displayOffDischargeSeconds", "REAL NOT NULL DEFAULT 0"),
    )

    /** Whether an upgrade from [oldVersion] adds the v6 discharge-basis columns. */
    fun addsDischargeSecondsColumns(oldVersion: Int): Boolean = oldVersion < VERSION

    /**
     * Whether an upgrade from [oldVersion] must invalidate the stored capacity
     * aggregate. Only the aggregate: session summaries and samples are preserved.
     */
    fun invalidatesCapacityAggregate(oldVersion: Int): Boolean =
        oldVersion < CAPACITY_ESTIMATOR_VERSION
}
