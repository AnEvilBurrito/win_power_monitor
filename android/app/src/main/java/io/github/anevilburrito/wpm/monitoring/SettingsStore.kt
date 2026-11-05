package io.github.anevilburrito.wpm.monitoring

import android.content.Context
import io.github.anevilburrito.wpm.model.AppTheme
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.NotificationUpdateRate

/**
 * Small durable preference store; never holds measurement history.
 *
 * Two kinds of value live here:
 *
 * - [MonitorSettings] (cadence, theme, absolute scale, the opt-in developer mode,
 *   manual unit **and** polarity corrections, optional design capacity). A manual
 *   correction is a user choice and must survive restarts, and the design capacity
 *   is user-supplied data that the app must never confuse with a measurement.
 *   Developer mode is only a visibility flag: turning it off never clears the
 *   advanced values stored next to it.
 * - The established [CurrentCalibration] discovered by the in-memory calibrator,
 *   persisted separately so it survives restarts without being a "setting": it is
 *   evidence, and [MonitoringRepository.resetCurrentCalibration] deletes it.
 *
 * Nothing written here changes the reported battery percentage, the runtime's
 * remaining counter or the observed capacity.
 */
internal class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): MonitorSettings {
        val defaults = MonitorSettings()
        val migration = IntervalDefaultMigration.decide(
            storedIntervalSeconds = if (prefs.contains(KEY_INTERVAL)) {
                prefs.getInt(KEY_INTERVAL, defaults.intervalSeconds)
            } else {
                null
            },
            // Deliberately the NEW marker, not the older 15 -> 5 one: an install
            // that already ran that migration still has not seen the 1/2/5
            // contract, and its stored 5 must be moved to the new default once.
            alreadyMigrated = prefs.getBoolean(KEY_INTERVAL_MIGRATED_2S, false),
        )
        if (migration.persist) {
            // One-time: the old 5 s default cannot be distinguished from a
            // deliberate 5 s choice, so the first read after the upgrade migrates
            // it to 2 s and records the marker. After that an explicit 1/2/5 choice
            // is never overridden again; invalid stored values are normalised to 2
            // and persisted.
            prefs.edit()
                .putInt(KEY_INTERVAL, migration.intervalSeconds)
                .putBoolean(KEY_INTERVAL_MIGRATED_2S, true)
                .apply()
        }
        return MonitorSettings(
            intervalSeconds = migration.intervalSeconds,
            notificationUpdateRate = SettingsCodec.notificationUpdateRateOf(
                prefs.getString(KEY_NOTIFICATION_RATE, null),
            ),
            mode = SettingsCodec.modeOf(prefs.getString(KEY_MODE, null), defaults.mode),
            theme = SettingsCodec.themeOf(prefs.getString(KEY_THEME, null), defaults.theme),
            absolute = prefs.getBoolean(KEY_ABSOLUTE, defaults.absolute),
            // Developer mode is opt-in and off by default; a missing row is off.
            developerMode = SettingsCodec.developerModeOf(
                if (prefs.contains(KEY_DEVELOPER_MODE)) {
                    prefs.getBoolean(KEY_DEVELOPER_MODE, defaults.developerMode)
                } else {
                    null
                },
            ),
            currentUnit = SettingsCodec.currentUnitOf(
                prefs.getString(KEY_CURRENT_UNIT, null),
                defaults.currentUnit,
            ),
            currentPolarity = SettingsCodec.currentPolarityOf(
                prefs.getString(KEY_CURRENT_POLARITY, null),
                defaults.currentPolarity,
            ),
            // Validity (finite, 100..30000 mAh) is enforced by
            // [MonitorSettings.normalized]; an unparseable stored value is simply
            // "not set" instead of being coerced to a made-up number.
            designCapacityMilliampHours = SettingsCodec.designCapacityOf(
                prefs.getString(KEY_DESIGN_CAPACITY, null),
            ),
        ).normalized()
    }

    fun save(settings: MonitorSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putInt(KEY_INTERVAL, normalized.intervalSeconds)
            .putString(KEY_NOTIFICATION_RATE, normalized.notificationUpdateRate.name)
            // An explicit save is itself proof the stored value is a user choice:
            // mark the new migration complete so no later read can rewrite it.
            .putBoolean(KEY_INTERVAL_MIGRATED_2S, true)
            .putString(KEY_MODE, normalized.mode.name)
            .putString(KEY_THEME, normalized.theme.name)
            .putBoolean(KEY_ABSOLUTE, normalized.absolute)
            .putBoolean(KEY_DEVELOPER_MODE, normalized.developerMode)
            // The manual current corrections survive restarts: a device that needs
            // them needs them every launch, not once.
            .putString(KEY_CURRENT_UNIT, normalized.currentUnit.name)
            .putString(KEY_CURRENT_POLARITY, normalized.currentPolarity.name)
            // A null design capacity removes the key (putString(null) clears it), so
            // "the user cleared it" and "never supplied" read back identically.
            .putString(KEY_DESIGN_CAPACITY, SettingsCodec.designCapacityText(normalized.designCapacityMilliampHours))
            .apply()
    }

    /** The saved evidence, or the uncalibrated default when nothing is stored. */
    fun loadCalibration(): CurrentCalibration = SettingsCodec.calibrationOf(
        unit = prefs.getString(KEY_CALIBRATION_UNIT, null),
        inverted = prefs.getString(KEY_CALIBRATION_INVERTED, null),
        evidenceWindows = prefs.getInt(KEY_CALIBRATION_WINDOWS, 0),
    )

    /**
     * Persists the calibration only; the normal settings are untouched, so a
     * calibration write and a settings write can never clobber each other.
     *
     * Synchronous (`commit`) on purpose, and it reports the outcome: the caller
     * must not present a value as saved when the write failed, because the next
     * publish of the same value would then be deduplicated into a permanent
     * in-memory-only calibration. Call it off the main thread.
     *
     * @return false when the write did not reach disk.
     */
    fun saveCalibration(value: CurrentCalibration): Boolean {
        val normalized = value.sanitized()
        return prefs.edit()
            .putString(KEY_CALIBRATION_UNIT, normalized.unit.name)
            .putString(KEY_CALIBRATION_INVERTED, SettingsCodec.invertedText(normalized.inverted))
            .putInt(KEY_CALIBRATION_WINDOWS, normalized.evidenceWindows)
            .commit()
    }

    private fun CurrentCalibration.sanitized(): CurrentCalibration = SettingsCodec.calibrationOf(
        unit = unit.name,
        inverted = SettingsCodec.invertedText(inverted),
        evidenceWindows = evidenceWindows,
    )

    private companion object {
        const val FILE_NAME = "wpm_settings"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_NOTIFICATION_RATE = "notification_update_rate"
        const val KEY_MODE = "monitoring_mode"
        const val KEY_THEME = "theme"
        const val KEY_ABSOLUTE = "absolute_scale"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_CURRENT_UNIT = "current_unit_override"
        const val KEY_CURRENT_POLARITY = "current_polarity_override"
        const val KEY_DESIGN_CAPACITY = "design_capacity_milliamp_hours"
        const val KEY_CALIBRATION_UNIT = "calibration_unit"
        const val KEY_CALIBRATION_INVERTED = "calibration_inverted"
        const val KEY_CALIBRATION_WINDOWS = "calibration_evidence_windows"

        /**
         * New marker for the 1/2/5-second default migration. Deliberately distinct
         * from the older `interval_default_migrated` (15 -> 5) key so an install
         * that already ran that migration is still migrated once to the new
         * contract.
         */
        const val KEY_INTERVAL_MIGRATED_2S = "interval_default_2s_migrated"
    }
}

/**
 * Pure preference encodings, Android-free so the round-trips are JVM tested.
 *
 * Every decoder is lenient in exactly one direction: an unknown or unparseable
 * stored string falls back to the documented default instead of crashing or
 * inventing a value.
 */
internal object SettingsCodec {
    /** Missing/unknown keys retain the pre-option 60s notification behaviour. */
    fun notificationUpdateRateOf(value: String?): NotificationUpdateRate =
        NotificationUpdateRate.entries.firstOrNull { it.name == value }
            ?: NotificationUpdateRate.SIXTY_SECONDS

    fun modeOf(value: String?, fallback: MonitoringMode): MonitoringMode =
        MonitoringMode.entries.firstOrNull { it.name == value } ?: fallback

    fun themeOf(value: String?, fallback: AppTheme): AppTheme =
        AppTheme.entries.firstOrNull { it.name == value } ?: fallback

    fun currentUnitOf(value: String?, fallback: CurrentUnitOverride): CurrentUnitOverride =
        CurrentUnitOverride.entries.firstOrNull { it.name == value } ?: fallback

    fun currentPolarityOf(
        value: String?,
        fallback: CurrentPolarityOverride,
    ): CurrentPolarityOverride =
        CurrentPolarityOverride.entries.firstOrNull { it.name == value } ?: fallback

    /**
     * Developer mode is a plain boolean. A missing row means off, so an install
     * that never opted in never sees the technical sections or raw telemetry.
     */
    fun developerModeOf(value: Boolean?, fallback: Boolean = false): Boolean = value ?: fallback

    /** Stored design capacity, or null when absent/unparseable/non-finite. */
    fun designCapacityOf(value: String?): Double? =
        value?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Stores the exact value as text; a Double's shortest form round-trips. */
    fun designCapacityText(value: Double?): String? =
        value?.takeIf { it.isFinite() }?.toString()

    /** `"true"`/`"false"` for a known polarity, null for "not established". */
    fun invertedText(value: Boolean?): String? = when (value) {
        true -> "true"
        false -> "false"
        null -> null
    }

    /**
     * Decodes a stored calibration. A partial record (unit known, polarity not)
     * loads as uncalibrated rather than as half-evidence: [CurrentCalibration.calibrated]
     * requires both, and a bogus window count is not allowed to claim evidence.
     */
    fun calibrationOf(unit: String?, inverted: String?, evidenceWindows: Int): CurrentCalibration {
        val polarity = when (inverted) {
            "true" -> true
            "false" -> false
            else -> null
        }
        return CurrentCalibration(
            unit = CurrentUnitOverride.entries.firstOrNull { it.name == unit }
                ?: CurrentUnitOverride.AUTO,
            inverted = polarity,
            evidenceWindows = evidenceWindows.coerceAtLeast(0),
        )
    }
}
