package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.AppTheme
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.NotificationUpdateRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preference encodings are the only part of [SettingsStore] that can run on the JVM,
 * so the lenient-fallback rules are pinned here.
 */
class SettingsCodecTest {

    @Test fun oldInstallsAndUnknownNotificationSettingsKeepSixtySeconds() {
        for (stored in listOf(null, "", "invalid", "15")) {
            assertEquals(NotificationUpdateRate.SIXTY_SECONDS, SettingsCodec.notificationUpdateRateOf(stored))
        }
    }

    @Test fun allNotificationUpdateRatesRoundTripByTheirStoredName() {
        for (rate in NotificationUpdateRate.entries) {
            assertEquals(rate, SettingsCodec.notificationUpdateRateOf(rate.name))
        }
    }

    @Test fun unknownStoredNamesFallBackToTheDocumentedDefault() {
        assertEquals(MonitoringMode.LOW_IMPACT, SettingsCodec.modeOf(null, MonitoringMode.LOW_IMPACT))
        assertEquals(MonitoringMode.HIGH_RESOLUTION, SettingsCodec.modeOf("HIGH_RESOLUTION", MonitoringMode.LOW_IMPACT))
        assertEquals(MonitoringMode.LOW_IMPACT, SettingsCodec.modeOf("BOGUS", MonitoringMode.LOW_IMPACT))
        assertEquals(AppTheme.STORM, SettingsCodec.themeOf("noir", AppTheme.STORM))
        assertEquals(AppTheme.MOCHA, SettingsCodec.themeOf("MOCHA", AppTheme.STORM))
        assertEquals(
            CurrentUnitOverride.AUTO,
            SettingsCodec.currentUnitOf("microamps", CurrentUnitOverride.AUTO),
        )
        assertEquals(
            CurrentUnitOverride.MILLIAMPS,
            SettingsCodec.currentUnitOf("MILLIAMPS", CurrentUnitOverride.AUTO),
        )
        assertEquals(
            CurrentPolarityOverride.AUTO,
            SettingsCodec.currentPolarityOf(null, CurrentPolarityOverride.AUTO),
        )
        assertEquals(
            CurrentPolarityOverride.INVERTED,
            SettingsCodec.currentPolarityOf("INVERTED", CurrentPolarityOverride.AUTO),
        )
    }

    @Test fun designCapacityRoundTripsExactlyAndRejectsNonsense() {
        assertEquals("4380.5", SettingsCodec.designCapacityText(4380.5))
        assertEquals(4380.5, SettingsCodec.designCapacityOf("4380.5")!!, 0.0)
        // A cleared value is absent, not a zero.
        assertNull(SettingsCodec.designCapacityText(null))
        assertNull(SettingsCodec.designCapacityOf(null))
        assertNull(SettingsCodec.designCapacityOf(""))
        assertNull(SettingsCodec.designCapacityOf("not a number"))
        // Non-finite text must not become a capacity.
        assertNull(SettingsCodec.designCapacityOf("NaN"))
        assertNull(SettingsCodec.designCapacityOf("Infinity"))
        assertNull(SettingsCodec.designCapacityText(Double.NaN))
        // Range validation is [MonitorSettings.normalized]'s job, not the codec's:
        // the exact stored number survives so the settings object can decide.
        assertEquals(50_000.0, SettingsCodec.designCapacityOf("50000.0")!!, 0.0)
    }

    @Test fun calibrationNeedsBothUnitAndPolarityToCountAsEstablished() {
        val established = SettingsCodec.calibrationOf(
            unit = CurrentUnitOverride.MILLIAMPS.name,
            inverted = "true",
            evidenceWindows = 4,
        )
        assertEquals(CurrentUnitOverride.MILLIAMPS, established.unit)
        assertEquals(true, established.inverted)
        assertEquals(4, established.evidenceWindows)
        assertTrue(established.calibrated)

        // Half-evidence loads as uncalibrated: the unit alone proves nothing about sign.
        val partial = SettingsCodec.calibrationOf(
            unit = CurrentUnitOverride.MICROAMPS.name,
            inverted = null,
            evidenceWindows = 3,
        )
        assertFalse(partial.calibrated)
        assertNull(partial.inverted)

        // A non-inverted calibration is still a complete calibration.
        assertTrue(
            SettingsCodec.calibrationOf(
                unit = CurrentUnitOverride.MICROAMPS.name,
                inverted = "false",
                evidenceWindows = 3,
            ).calibrated,
        )

        // Unknown names and negative window counts cannot claim evidence.
        val unknown = SettingsCodec.calibrationOf(unit = "WATTS", inverted = "maybe", evidenceWindows = -5)
        assertEquals(CurrentUnitOverride.AUTO, unknown.unit)
        assertNull(unknown.inverted)
        assertEquals(0, unknown.evidenceWindows)
        assertFalse(unknown.calibrated)
        assertEquals(CurrentCalibration(), unknown)
    }

    @Test fun developerModeDefaultsOffAndRoundTrips() {
        // A missing preference row is off: a fresh install never shows the technical
        // section, raw telemetry or manual corrections.
        assertFalse(SettingsCodec.developerModeOf(null))
        assertTrue(SettingsCodec.developerModeOf(true))
        assertFalse(SettingsCodec.developerModeOf(false))
        // An explicit fallback is never stronger than the stored value.
        assertTrue(SettingsCodec.developerModeOf(null, fallback = true))
        assertFalse(SettingsCodec.developerModeOf(false, fallback = true))
    }

    @Test fun invertedFlagEncodesOnlyKnownPolarity() {
        assertEquals("true", SettingsCodec.invertedText(true))
        assertEquals("false", SettingsCodec.invertedText(false))
        assertNull(SettingsCodec.invertedText(null))
        // Round trip: the encoded text decodes back to the same tri-state.
        listOf(true, false, null).forEach { polarity ->
            val decoded = SettingsCodec.calibrationOf(
                unit = CurrentUnitOverride.MICROAMPS.name,
                inverted = SettingsCodec.invertedText(polarity),
                evidenceWindows = 3,
            )
            assertEquals(polarity, decoded.inverted)
        }
    }
}
