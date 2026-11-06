package io.github.anevilburrito.wpm.monitoring

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CapacityAggregate
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.SessionSummary

/** Latest persisted session plus its retention flag. */
internal data class StoredSession(val summary: SessionSummary, val historyTruncated: Boolean)

/** Persisted observed-capacity aggregate and when it was last updated. */
internal data class StoredCapacity(val aggregate: CapacityAggregate, val updatedAtMillis: Long)

/** Result of one bounded retention pass. */
internal data class PruneOutcome(val deletedRows: Int, val remainingRowsForSession: Int)

/**
 * Framework `SQLiteOpenHelper` storage: raw samples plus the full-session summary.
 *
 * Pruning deletes *samples only*; the session summary row keeps the complete
 * energy/statistics record so retention never resets the aggregate.
 */
internal class MonitoringStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startedAtMillis INTEGER NOT NULL,
                endedAtMillis INTEGER,
                elapsedSeconds REAL NOT NULL DEFAULT 0,
                observedSeconds REAL NOT NULL DEFAULT 0,
                outWh REAL NOT NULL DEFAULT 0,
                inWh REAL NOT NULL DEFAULT 0,
                signedAverageWatts REAL,
                magnitudeAverageWatts REAL,
                minimumSignedWatts REAL,
                maximumSignedWatts REAL,
                minimumMagnitudeWatts REAL,
                maximumMagnitudeWatts REAL,
                sampleCount INTEGER NOT NULL DEFAULT 0,
                gapCount INTEGER NOT NULL DEFAULT 0,
                historyTruncated INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId INTEGER NOT NULL,
                timestampMillis INTEGER NOT NULL,
                elapsedRealtimeMillis INTEGER NOT NULL,
                watts REAL,
                amps REAL,
                volts REAL,
                percentage INTEGER,
                temperatureC REAL,
                condition TEXT,
                plugged INTEGER,
                flow TEXT NOT NULL,
                currentMicroamps INTEGER,
                voltageMillivolts INTEGER,
                rawCurrentMicroamps INTEGER,
                rawAverageMicroamps INTEGER,
                chargeCounterMicroampHours INTEGER,
                derivedAmps REAL,
                derivedWindowMillis INTEGER,
                currentUnitAdjusted INTEGER NOT NULL DEFAULT 0,
                currentCalibrated INTEGER NOT NULL DEFAULT 0,
                currentPolarityAdjusted INTEGER NOT NULL DEFAULT 0,
                currentSource TEXT,
                gapBefore INTEGER NOT NULL DEFAULT 0,
                reason TEXT,
                cycleCount INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_samples_session ON samples(sessionId, id)")
        db.execSQL("CREATE INDEX idx_samples_time ON samples(timestampMillis)")
        createCapacityTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Additive only: version 1 history is kept, never dropped. Everything
            // added here is nullable (or defaults to 0) so old rows stay readable
            // as "this reading has no charge-counter provenance".
            addSampleColumn(db, COL_SAMPLE_RAW_CURRENT, "INTEGER")
            addSampleColumn(db, COL_SAMPLE_CHARGE_COUNTER, "INTEGER")
            addSampleColumn(db, COL_SAMPLE_DERIVED_AMPS, "REAL")
            addSampleColumn(db, COL_SAMPLE_DERIVED_WINDOW, "INTEGER")
            addSampleColumn(db, COL_SAMPLE_UNIT_ADJUSTED, "INTEGER NOT NULL DEFAULT 0")
            addSampleColumn(db, COL_SAMPLE_CURRENT_SOURCE, "TEXT")
        }
        if (oldVersion < 3) {
            // Version 3 adds the platform cycle count (nullable: many devices never
            // report one) and the single-row capacity aggregate (whose meaning was
            // superseded in version 4). Both are additive, so no history is rewritten
            // and an older row simply has no cycle count and no capacity contribution.
            addSampleColumn(db, COL_SAMPLE_CYCLE_COUNT, "INTEGER")
            createCapacityTable(db)
        }
        if (oldVersion < SchemaMigration.VERSION) {
            // Version 4 records the instant reading's provenance (the unmodified
            // platform average, and whether calibration/polarity were established)
            // and changes what `capacity_state` means: from a single-point gauge
            // division to an observed charging-window swing. The columns are
            // additive, so every recorded sample stays readable.
            SchemaMigration.V4_SAMPLE_COLUMNS.forEach { column ->
                addSampleColumn(db, column.name, column.definition)
            }
            addCapacityColumn(db, SchemaMigration.V4_CAPACITY_METHOD_COLUMN)
            if (SchemaMigration.invalidatesCapacityAggregate(oldVersion)) {
                // Only the aggregate is dropped: it described a different quantity,
                // so it cannot honestly be shown as an observed capacity. Sessions
                // and samples are history and are never deleted by a migration.
                db.delete(TABLE_CAPACITY, null, null)
            }
        }
    }

    /**
     * One row, `id = 1`, holding the observed-capacity aggregate and the marker for
     * which estimator produced it. A missing row means "no observation yet", which
     * is exactly what the UI must show, so the table is never seeded with a
     * fabricated value.
     */
    private fun createCapacityTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CAPACITY (
                $COL_CAPACITY_ID INTEGER PRIMARY KEY CHECK ($COL_CAPACITY_ID = 1),
                $COL_CAPACITY_SAMPLE_COUNT INTEGER NOT NULL,
                $COL_CAPACITY_WEIGHT_PERCENT REAL NOT NULL,
                $COL_CAPACITY_WEIGHTED_MAH REAL NOT NULL,
                $COL_CAPACITY_UPDATED_AT INTEGER NOT NULL,
                $COL_CAPACITY_METHOD TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
    }

    /** `ALTER TABLE ADD COLUMN` on the capacity row, skipped when present. */
    private fun addCapacityColumn(db: SQLiteDatabase, column: SchemaMigration.Column) {
        if (hasColumn(db, TABLE_CAPACITY, column.name)) return
        db.execSQL("ALTER TABLE $TABLE_CAPACITY ADD COLUMN ${column.name} ${column.definition}")
    }

    /** `ALTER TABLE ADD COLUMN`, skipped when the column already exists. */
    private fun addSampleColumn(db: SQLiteDatabase, column: String, definition: String) {
        if (hasColumn(db, TABLE_SAMPLES, column)) return
        db.execSQL("ALTER TABLE $TABLE_SAMPLES ADD COLUMN $column $definition")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(name) == column) return true
            }
        }
        return false
    }

    fun createSession(startedAtMillis: Long): Long {
        val values = ContentValues().apply {
            put(COL_STARTED_AT, startedAtMillis)
            put(COL_ENDED_AT, null as Long?)
            put(COL_ELAPSED_SECONDS, 0.0)
            put(COL_OBSERVED_SECONDS, 0.0)
            put(COL_OUT_WH, 0.0)
            put(COL_IN_WH, 0.0)
            put(COL_SAMPLE_COUNT, 0)
            put(COL_GAP_COUNT, 0)
            put(COL_HISTORY_TRUNCATED, 0)
        }
        return writableDatabase.insertOrThrow(TABLE_SESSIONS, null, values)
    }

    /**
     * Commits one sample batch and its session summary atomically. A failure
     * rolls both back, so retrying a batch can never duplicate rows or leave the
     * stored summary older than the stored samples.
     */
    fun saveBatch(sessionId: Long, samples: List<BatterySample>, summary: SessionSummary) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (sample in samples) {
                db.insertOrThrow(TABLE_SAMPLES, null, sampleValues(sessionId, sample))
            }
            db.update(
                TABLE_SESSIONS,
                summaryValues(summary),
                "$COL_ID = ?",
                arrayOf(summary.id.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finishSession(summary: SessionSummary) {
        val values = summaryValues(summary).apply {
            put(COL_ENDED_AT, summary.endedAtMillis ?: summary.startedAtMillis)
        }
        writableDatabase.update(
            TABLE_SESSIONS,
            values,
            "$COL_ID = ?",
            arrayOf(summary.id.toString()),
        )
    }

    fun markHistoryTruncated(sessionId: Long) {
        val values = ContentValues().apply { put(COL_HISTORY_TRUNCATED, 1) }
        writableDatabase.update(TABLE_SESSIONS, values, "$COL_ID = ?", arrayOf(sessionId.toString()))
    }

    /**
     * The persisted observed-capacity aggregate, or null when none has been measured
     * by the current estimator.
     *
     * Read-only and independent of the samples table, so a missing, empty or
     * foreign-method `capacity_state` row simply means "no observation", never a
     * zero and never an old gauge estimate relabelled as observed capacity.
     */
    fun readCapacity(): StoredCapacity? {
        readableDatabase.query(
            TABLE_CAPACITY,
            null,
            "$COL_CAPACITY_ID = ?",
            arrayOf(CAPACITY_ROW_ID.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            // A row written by any other estimator (or before the marker existed) is
            // a different quantity and must not be presented as an observed capacity.
            if (cursor.getString(cursor.getColumnIndexOrThrow(COL_CAPACITY_METHOD)) !=
                SchemaMigration.CAPACITY_METHOD
            ) {
                return null
            }
            // The column keeps its original name; renaming it would mean a rewrite
            // for no benefit, so the mapping is documented in the companion below.
            val weightTotal = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_CAPACITY_WEIGHT_PERCENT))
            if (!weightTotal.isFinite() || weightTotal <= 0.0) return null
            val aggregate = CapacityAggregate(
                sampleCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CAPACITY_SAMPLE_COUNT)),
                weightTotal = weightTotal,
                weightedMilliampHours = cursor.getDouble(
                    cursor.getColumnIndexOrThrow(COL_CAPACITY_WEIGHTED_MAH),
                ),
            )
            // A rejected observation stores nothing usable; treat it as absent.
            if (aggregate.estimatedMilliampHours == null) return null
            return StoredCapacity(
                aggregate = aggregate,
                updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CAPACITY_UPDATED_AT)),
            )
        }
    }

    /**
     * Replaces the single capacity row. Deliberately its own write, outside the
     * sample batch transaction: a capacity failure must never roll back (or
     * block) recorded samples, and vice versa.
     */
    fun writeCapacity(aggregate: CapacityAggregate, updatedAtMillis: Long) {
        val values = ContentValues().apply {
            put(COL_CAPACITY_ID, CAPACITY_ROW_ID)
            put(COL_CAPACITY_SAMPLE_COUNT, aggregate.sampleCount)
            put(COL_CAPACITY_WEIGHT_PERCENT, aggregate.weightTotal)
            put(COL_CAPACITY_WEIGHTED_MAH, aggregate.weightedMilliampHours)
            put(COL_CAPACITY_UPDATED_AT, updatedAtMillis)
            put(COL_CAPACITY_METHOD, SchemaMigration.CAPACITY_METHOD)
        }
        // -1 (not an exception) means the row was not written, which the caller
        // must surface as a recoverable notice instead of a silent success.
        val row = writableDatabase.insertWithOnConflict(
            TABLE_CAPACITY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(row != -1L) { "capacity_state row was not written" }
    }

    fun latestSession(): StoredSession? {
        readableDatabase.query(
            TABLE_SESSIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StoredSession(
                summary = readSummary(cursor),
                historyTruncated = cursor.getInt(cursor.getColumnIndexOrThrow(COL_HISTORY_TRUNCATED)) != 0,
            )
        }
    }

    /** Oldest-first window of the newest [limit] rows for one session. */
    fun loadSamples(sessionId: Long, limit: Int): List<BatterySample> {
        if (sessionId <= 0L || limit <= 0) return emptyList()
        val sql =
            "SELECT * FROM (SELECT * FROM $TABLE_SAMPLES WHERE $COL_SAMPLE_SESSION = ? " +
                "ORDER BY $COL_SAMPLE_ID DESC LIMIT ?) ORDER BY $COL_SAMPLE_ID ASC"
        readableDatabase.rawQuery(sql, arrayOf(sessionId.toString(), limit.toString())).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            val indices = SampleIndices(cursor)
            val result = ArrayList<BatterySample>(cursor.count)
            do {
                result.add(indices.read(cursor))
            } while (cursor.moveToNext())
            return result
        }
    }

    fun lastSampleTimestamp(sessionId: Long): Long? {
        if (sessionId <= 0L) return null
        readableDatabase.rawQuery(
            "SELECT MAX($COL_SAMPLE_TIME) FROM $TABLE_SAMPLES WHERE $COL_SAMPLE_SESSION = ?",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getLong(0)
        }
    }

    fun storedSampleCount(sessionId: Long): Int {
        if (sessionId <= 0L) return 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_SAMPLES WHERE $COL_SAMPLE_SESSION = ?",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Bounded retention: drop samples older than [staleBeforeMillis], then the
     * oldest rows above [maxRows]. Session summaries are never deleted.
     */
    fun prune(staleBeforeMillis: Long, maxRows: Int, sessionId: Long): PruneOutcome {
        val db = writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            deleted += db.delete(
                TABLE_SAMPLES,
                "$COL_SAMPLE_TIME < ?",
                arrayOf(staleBeforeMillis.toString()),
            )
            val total = countRows(db)
            val excess = total - maxRows.coerceAtLeast(0)
            if (excess > 0) {
                db.execSQL(
                    "DELETE FROM $TABLE_SAMPLES WHERE $COL_SAMPLE_ID IN " +
                        "(SELECT $COL_SAMPLE_ID FROM $TABLE_SAMPLES ORDER BY $COL_SAMPLE_ID ASC LIMIT $excess)",
                )
                deleted += excess
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return PruneOutcome(deleted, storedSampleCount(sessionId))
    }

    private fun countRows(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_SAMPLES", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun summaryValues(summary: SessionSummary) = ContentValues().apply {
        put(COL_STARTED_AT, summary.startedAtMillis)
        put(COL_ENDED_AT, summary.endedAtMillis)
        put(COL_ELAPSED_SECONDS, summary.elapsedSeconds)
        put(COL_OBSERVED_SECONDS, summary.observedSeconds)
        put(COL_OUT_WH, summary.outWh)
        put(COL_IN_WH, summary.inWh)
        put(COL_AVG_SIGNED, summary.signedAverageWatts)
        put(COL_AVG_MAGNITUDE, summary.magnitudeAverageWatts)
        put(COL_MIN_SIGNED, summary.minimumSignedWatts)
        put(COL_MAX_SIGNED, summary.maximumSignedWatts)
        put(COL_MIN_MAGNITUDE, summary.minimumMagnitudeWatts)
        put(COL_MAX_MAGNITUDE, summary.maximumMagnitudeWatts)
        put(COL_SAMPLE_COUNT, summary.sampleCount)
        put(COL_GAP_COUNT, summary.gapCount)
    }

    private fun sampleValues(sessionId: Long, sample: BatterySample) = ContentValues().apply {
        put(COL_SAMPLE_SESSION, sessionId)
        put(COL_SAMPLE_TIME, sample.timestampMillis)
        put(COL_SAMPLE_ELAPSED, sample.elapsedRealtimeMillis)
        put(COL_SAMPLE_WATTS, sample.watts)
        put(COL_SAMPLE_AMPS, sample.amps)
        put(COL_SAMPLE_VOLTS, sample.volts)
        put(COL_SAMPLE_PERCENT, sample.percentage)
        put(COL_SAMPLE_TEMPERATURE, sample.temperatureC)
        put(COL_SAMPLE_CONDITION, sample.condition)
        put(COL_SAMPLE_PLUGGED, sample.plugged?.let { if (it) 1 else 0 })
        put(COL_SAMPLE_FLOW, sample.flow.name)
        put(COL_SAMPLE_CURRENT, sample.currentMicroamps)
        put(COL_SAMPLE_VOLTAGE, sample.voltageMillivolts)
        put(COL_SAMPLE_RAW_CURRENT, sample.rawCurrentMicroamps)
        put(COL_SAMPLE_RAW_AVERAGE, sample.rawAverageMicroamps)
        put(COL_SAMPLE_CHARGE_COUNTER, sample.chargeCounterMicroampHours)
        put(COL_SAMPLE_DERIVED_AMPS, sample.derivedAmps)
        put(COL_SAMPLE_DERIVED_WINDOW, sample.derivedWindowMillis)
        put(COL_SAMPLE_UNIT_ADJUSTED, if (sample.currentUnitAdjusted) 1 else 0)
        put(COL_SAMPLE_CALIBRATED, if (sample.currentCalibrated) 1 else 0)
        put(COL_SAMPLE_POLARITY_ADJUSTED, if (sample.currentPolarityAdjusted) 1 else 0)
        put(COL_SAMPLE_CURRENT_SOURCE, sample.currentSource.name)
        put(COL_SAMPLE_GAP, if (sample.gapBefore) 1 else 0)
        put(COL_SAMPLE_REASON, sample.reason)
        put(COL_SAMPLE_CYCLE_COUNT, sample.cycleCount)
    }

    private fun readSummary(cursor: Cursor) = SessionSummary(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        startedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_STARTED_AT)),
        endedAtMillis = cursor.nullableLong(COL_ENDED_AT),
        elapsedSeconds = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_ELAPSED_SECONDS)),
        observedSeconds = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_OBSERVED_SECONDS)),
        outWh = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_OUT_WH)),
        inWh = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_IN_WH)),
        signedAverageWatts = cursor.nullableDouble(COL_AVG_SIGNED),
        magnitudeAverageWatts = cursor.nullableDouble(COL_AVG_MAGNITUDE),
        minimumSignedWatts = cursor.nullableDouble(COL_MIN_SIGNED),
        maximumSignedWatts = cursor.nullableDouble(COL_MAX_SIGNED),
        minimumMagnitudeWatts = cursor.nullableDouble(COL_MIN_MAGNITUDE),
        maximumMagnitudeWatts = cursor.nullableDouble(COL_MAX_MAGNITUDE),
        sampleCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SAMPLE_COUNT)),
        gapCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_GAP_COUNT)),
    )

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.nullableDouble(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    /** Column indices resolved once per cursor instead of once per row. */
    private class SampleIndices(cursor: Cursor) {
        private val time = cursor.getColumnIndexOrThrow(COL_SAMPLE_TIME)
        private val elapsed = cursor.getColumnIndexOrThrow(COL_SAMPLE_ELAPSED)
        private val watts = cursor.getColumnIndexOrThrow(COL_SAMPLE_WATTS)
        private val amps = cursor.getColumnIndexOrThrow(COL_SAMPLE_AMPS)
        private val volts = cursor.getColumnIndexOrThrow(COL_SAMPLE_VOLTS)
        private val percent = cursor.getColumnIndexOrThrow(COL_SAMPLE_PERCENT)
        private val temperature = cursor.getColumnIndexOrThrow(COL_SAMPLE_TEMPERATURE)
        private val condition = cursor.getColumnIndexOrThrow(COL_SAMPLE_CONDITION)
        private val plugged = cursor.getColumnIndexOrThrow(COL_SAMPLE_PLUGGED)
        private val flow = cursor.getColumnIndexOrThrow(COL_SAMPLE_FLOW)
        private val current = cursor.getColumnIndexOrThrow(COL_SAMPLE_CURRENT)
        private val voltage = cursor.getColumnIndexOrThrow(COL_SAMPLE_VOLTAGE)
        private val rawCurrent = cursor.getColumnIndexOrThrow(COL_SAMPLE_RAW_CURRENT)
        private val rawAverage = cursor.getColumnIndexOrThrow(COL_SAMPLE_RAW_AVERAGE)
        private val chargeCounter = cursor.getColumnIndexOrThrow(COL_SAMPLE_CHARGE_COUNTER)
        private val derivedAmps = cursor.getColumnIndexOrThrow(COL_SAMPLE_DERIVED_AMPS)
        private val derivedWindow = cursor.getColumnIndexOrThrow(COL_SAMPLE_DERIVED_WINDOW)
        private val unitAdjusted = cursor.getColumnIndexOrThrow(COL_SAMPLE_UNIT_ADJUSTED)
        private val calibrated = cursor.getColumnIndexOrThrow(COL_SAMPLE_CALIBRATED)
        private val polarityAdjusted = cursor.getColumnIndexOrThrow(COL_SAMPLE_POLARITY_ADJUSTED)
        private val currentSource = cursor.getColumnIndexOrThrow(COL_SAMPLE_CURRENT_SOURCE)
        private val gap = cursor.getColumnIndexOrThrow(COL_SAMPLE_GAP)
        private val reason = cursor.getColumnIndexOrThrow(COL_SAMPLE_REASON)
        private val cycleCount = cursor.getColumnIndexOrThrow(COL_SAMPLE_CYCLE_COUNT)

        fun read(cursor: Cursor) = BatterySample(
            timestampMillis = cursor.getLong(time),
            elapsedRealtimeMillis = cursor.getLong(elapsed),
            watts = if (cursor.isNull(watts)) null else cursor.getDouble(watts),
            amps = if (cursor.isNull(amps)) null else cursor.getDouble(amps),
            volts = if (cursor.isNull(volts)) null else cursor.getDouble(volts),
            percentage = if (cursor.isNull(percent)) null else cursor.getInt(percent),
            temperatureC = if (cursor.isNull(temperature)) null else cursor.getDouble(temperature),
            condition = if (cursor.isNull(condition)) null else cursor.getString(condition),
            plugged = if (cursor.isNull(plugged)) null else cursor.getInt(plugged) != 0,
            flow = if (cursor.isNull(flow)) {
                FlowState.UNKNOWN
            } else {
                runCatching { FlowState.valueOf(cursor.getString(flow)) }.getOrDefault(FlowState.UNKNOWN)
            },
            currentMicroamps = if (cursor.isNull(current)) null else cursor.getLong(current),
            voltageMillivolts = if (cursor.isNull(voltage)) null else cursor.getInt(voltage),
            rawCurrentMicroamps = if (cursor.isNull(rawCurrent)) null else cursor.getLong(rawCurrent),
            rawAverageMicroamps = if (cursor.isNull(rawAverage)) null else cursor.getLong(rawAverage),
            chargeCounterMicroampHours = if (cursor.isNull(chargeCounter)) {
                null
            } else {
                cursor.getLong(chargeCounter)
            },
            derivedAmps = if (cursor.isNull(derivedAmps)) null else cursor.getDouble(derivedAmps),
            derivedWindowMillis = if (cursor.isNull(derivedWindow)) null else cursor.getLong(derivedWindow),
            currentUnitAdjusted = cursor.getInt(unitAdjusted) != 0,
            currentCalibrated = cursor.getInt(calibrated) != 0,
            currentPolarityAdjusted = cursor.getInt(polarityAdjusted) != 0,
            currentSource = if (cursor.isNull(currentSource)) {
                CurrentSource.NONE
            } else {
                runCatching { CurrentSource.valueOf(cursor.getString(currentSource)) }
                    .getOrDefault(CurrentSource.NONE)
            },
            gapBefore = cursor.getInt(gap) != 0,
            reason = if (cursor.isNull(reason)) null else cursor.getString(reason),
            cycleCount = if (cursor.isNull(cycleCount)) null else cursor.getInt(cycleCount),
        )
    }

    private companion object {
        const val DATABASE_NAME = "wpm_history.db"

        /**
         * Version 4 adds instant-reading provenance columns and the capacity
         * method marker; it invalidates only the superseded capacity aggregate.
         * Every bump so far is additive for history.
         */
        const val VERSION = SchemaMigration.VERSION

        const val TABLE_SESSIONS = "sessions"
        const val TABLE_SAMPLES = "samples"
        const val TABLE_CAPACITY = "capacity_state"

        /** The capacity table holds exactly one row, so its primary key is fixed. */
        const val CAPACITY_ROW_ID = 1

        const val COL_CAPACITY_ID = "id"
        const val COL_CAPACITY_SAMPLE_COUNT = "sampleCount"

        /**
         * Historic name only: since v3 this column stores
         * [CapacityAggregate.weightTotal] (the accumulated percentage-swing weight
         * of the accepted charge/discharge windows). Renaming it would mean a migration for
         * no benefit, so the mapping is documented here instead.
         */
        const val COL_CAPACITY_WEIGHT_PERCENT = "weightPercent"
        const val COL_CAPACITY_WEIGHTED_MAH = "weightedMilliampHours"
        const val COL_CAPACITY_UPDATED_AT = "updatedAtMillis"

        /** Which quantity the aggregate holds; see [SchemaMigration.CAPACITY_METHOD]. */
        const val COL_CAPACITY_METHOD = "method"

        const val COL_ID = "id"
        const val COL_STARTED_AT = "startedAtMillis"
        const val COL_ENDED_AT = "endedAtMillis"
        const val COL_ELAPSED_SECONDS = "elapsedSeconds"
        const val COL_OBSERVED_SECONDS = "observedSeconds"
        const val COL_OUT_WH = "outWh"
        const val COL_IN_WH = "inWh"
        const val COL_AVG_SIGNED = "signedAverageWatts"
        const val COL_AVG_MAGNITUDE = "magnitudeAverageWatts"
        const val COL_MIN_SIGNED = "minimumSignedWatts"
        const val COL_MAX_SIGNED = "maximumSignedWatts"
        const val COL_MIN_MAGNITUDE = "minimumMagnitudeWatts"
        const val COL_MAX_MAGNITUDE = "maximumMagnitudeWatts"
        const val COL_SAMPLE_COUNT = "sampleCount"
        const val COL_GAP_COUNT = "gapCount"
        const val COL_HISTORY_TRUNCATED = "historyTruncated"

        const val COL_SAMPLE_ID = "id"
        const val COL_SAMPLE_SESSION = "sessionId"
        const val COL_SAMPLE_TIME = "timestampMillis"
        const val COL_SAMPLE_ELAPSED = "elapsedRealtimeMillis"
        const val COL_SAMPLE_WATTS = "watts"
        const val COL_SAMPLE_AMPS = "amps"
        const val COL_SAMPLE_VOLTS = "volts"
        const val COL_SAMPLE_PERCENT = "percentage"
        const val COL_SAMPLE_TEMPERATURE = "temperatureC"
        const val COL_SAMPLE_CONDITION = "condition"
        const val COL_SAMPLE_PLUGGED = "plugged"
        const val COL_SAMPLE_FLOW = "flow"
        const val COL_SAMPLE_CURRENT = "currentMicroamps"
        const val COL_SAMPLE_VOLTAGE = "voltageMillivolts"
        const val COL_SAMPLE_RAW_CURRENT = "rawCurrentMicroamps"
        const val COL_SAMPLE_RAW_AVERAGE = "rawAverageMicroamps"
        const val COL_SAMPLE_CHARGE_COUNTER = "chargeCounterMicroampHours"
        const val COL_SAMPLE_DERIVED_AMPS = "derivedAmps"
        const val COL_SAMPLE_DERIVED_WINDOW = "derivedWindowMillis"
        const val COL_SAMPLE_UNIT_ADJUSTED = "currentUnitAdjusted"

        /** The instant reading used an established or fully manual interpretation. */
        const val COL_SAMPLE_CALIBRATED = "currentCalibrated"

        /** The saved calibration inverted the instant reading's sign. */
        const val COL_SAMPLE_POLARITY_ADJUSTED = "currentPolarityAdjusted"
        const val COL_SAMPLE_CURRENT_SOURCE = "currentSource"
        const val COL_SAMPLE_GAP = "gapBefore"
        const val COL_SAMPLE_REASON = "reason"
        const val COL_SAMPLE_CYCLE_COUNT = "cycleCount"
    }
}
