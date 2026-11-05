package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.MonitorSettings

/** What to store for the sampling interval after the one-time default change. */
data class IntervalMigration(
    val intervalSeconds: Int,
    /** True when the store should write this value (and the migration flag) back. */
    val persist: Boolean,
)

/**
 * One-time migration to the current 1 / 2 / 5 second cadence contract.
 *
 * Earlier builds shipped a 15 s default and then a 5 s default. Both stored values
 * are indistinguishable from a deliberate user choice: a `5` in preferences may be
 * the old default or a real 5 s selection, and there is no extra marker to tell
 * them apart. The contract resolves that by running this migration exactly once
 * behind a **new** marker (`interval_default_2s_migrated`), independent of the
 * older `15 -> 5` marker:
 *
 * - First run after the upgrade: only the new-cadence values `1` and `2` are
 *   preserved. Everything else — the old 5 s and 15 s defaults, 30 s, an invalid
 *   value, or no value at all — becomes the 2 s default. This necessarily
 *   migrates a deliberate old 5 s choice too, once; the marker prevents it from
 *   ever happening again.
 * - After that marker exists, explicit `1` / `2` / `5` values survive restarts
 *   untouched. Any other stored value (15, 30, 0, a negative or corrupt number) is
 *   invalid under [MonitorSettings.ALLOWED_INTERVALS] and is normalised to 2 and
 *   persisted.
 *
 * [MonitorSettings.normalized] already clamps invalid values for in-memory use;
 * this object exists so the *corrected* value and the marker actually reach disk,
 * and so the one-time upgrade decision is explicit and unit tested without Android
 * preferences.
 */
object IntervalDefaultMigration {
    /** The default shipped by the first builds; kept for documentation/tests. */
    const val LEGACY_DEFAULT_INTERVAL_SECONDS = 15

    /** The default shipped immediately before the current 1 / 2 / 5 contract. */
    const val PREVIOUS_DEFAULT_INTERVAL_SECONDS = 5

    /** The current default, and the value every invalid/legacy value migrates to. */
    const val MIGRATED_INTERVAL_SECONDS = MonitorSettings.DEFAULT_INTERVAL_SECONDS

    /** New-contract values that may survive the one-time migration unchanged. */
    private val RETAINED_ON_FIRST_MIGRATION = setOf(1, 2)

    /**
     * @param storedIntervalSeconds the persisted interval, or null on a fresh
     * install where nothing was ever saved.
     * @param alreadyMigrated whether the **new** 2 s migration marker is set.
     */
    fun decide(storedIntervalSeconds: Int?, alreadyMigrated: Boolean): IntervalMigration {
        if (!alreadyMigrated) {
            val retained = storedIntervalSeconds?.takeIf { it in RETAINED_ON_FIRST_MIGRATION }
            return IntervalMigration(
                intervalSeconds = retained ?: MIGRATED_INTERVAL_SECONDS,
                // Always write: a fresh install must record the marker too, and an
                // untouched legacy default must be replaced on disk.
                persist = true,
            )
        }
        if (storedIntervalSeconds == null) {
            // Nothing stored, but the marker proves this is not a first-run upgrade.
            return IntervalMigration(intervalSeconds = MIGRATED_INTERVAL_SECONDS, persist = false)
        }
        val valid = storedIntervalSeconds in MonitorSettings.ALLOWED_INTERVALS
        return IntervalMigration(
            intervalSeconds = if (valid) storedIntervalSeconds else MIGRATED_INTERVAL_SECONDS,
            persist = !valid,
        )
    }
}
