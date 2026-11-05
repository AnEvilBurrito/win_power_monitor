package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitoringMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure validation for the manual settings the UI writes.
 *
 * The design-capacity field must never clamp: a typo either stays unsaved with an
 * explanation or clears the value, and the accepted range matches what the
 * settings model keeps, so a value the UI saves is never silently dropped.
 */
class SettingsInputTest {
    @Test fun blankInputMeansUnsetNotInvalid() {
        listOf("", "   ").forEach { text ->
            val parsed = parseDesignCapacityMilliampHours(text)
            assertNull(text, parsed.value)
            assertFalse(text, parsed.invalid)
        }
    }

    @Test fun validCapacitiesAreAcceptedIncludingGroupingSeparators() {
        assertEquals(4_500.0, parseDesignCapacityMilliampHours("4500").value!!, 1e-9)
        assertEquals(4_500.0, parseDesignCapacityMilliampHours(" 4,500 ").value!!, 1e-9)
        assertEquals(4_500.5, parseDesignCapacityMilliampHours("4500.5").value!!, 1e-9)
        assertEquals(DESIGN_CAPACITY_MIN_MAH, parseDesignCapacityMilliampHours("100").value!!, 1e-9)
        assertEquals(DESIGN_CAPACITY_MAX_MAH, parseDesignCapacityMilliampHours("30000").value!!, 1e-9)
    }

    @Test fun outOfRangeAndGarbageAreRejectedInsteadOfClamped() {
        listOf("99", "30001", "-4500", "0", "abc", "4 5 0 0 x", "1e6", "NaN", "Infinity").forEach { text ->
            val parsed = parseDesignCapacityMilliampHours(text)
            assertNull(text, parsed.value)
            assertTrue(text, parsed.invalid)
        }
    }

    @Test fun acceptedRangeMatchesTheSettingsModel() {
        // Below, inside and above the UI range: the model keeps exactly the values
        // the field accepts and drops the rest, so nothing is clamped on save.
        val inside = MonitorSettings(designCapacityMilliampHours = DESIGN_CAPACITY_MIN_MAH).normalized()
        assertEquals(DESIGN_CAPACITY_MIN_MAH, inside.designCapacityMilliampHours!!, 1e-9)

        val top = MonitorSettings(designCapacityMilliampHours = DESIGN_CAPACITY_MAX_MAH).normalized()
        assertEquals(DESIGN_CAPACITY_MAX_MAH, top.designCapacityMilliampHours!!, 1e-9)

        val below = MonitorSettings(designCapacityMilliampHours = DESIGN_CAPACITY_MIN_MAH - 1.0).normalized()
        assertNull(below.designCapacityMilliampHours)
        val above = MonitorSettings(designCapacityMilliampHours = DESIGN_CAPACITY_MAX_MAH + 1.0).normalized()
        assertNull(above.designCapacityMilliampHours)
    }

    @Test fun designCapacityNeverTouchesTheCurrentOrPolaritySettings() {
        val settings = MonitorSettings(currentUnit = CurrentUnitOverride.MICROAMPS, currentPolarity = CurrentPolarityOverride.STANDARD)
        val withCapacity = settings.copy(designCapacityMilliampHours = 4_500.0).normalized()
        assertEquals(CurrentUnitOverride.MICROAMPS, withCapacity.currentUnit)
        assertEquals(CurrentPolarityOverride.STANDARD, withCapacity.currentPolarity)
    }

    @Test fun theDraftRepeatsWhatWasSavedWithoutGroupingSeparators() {
        assertEquals("4500", designCapacityDraft(4_500.0))
        assertEquals("4500.5", designCapacityDraft(4_500.5))
        // A round trip through the field reproduces the same value.
        assertEquals(4_500.0, parseDesignCapacityMilliampHours(designCapacityDraft(4_500.0)).value!!, 1e-9)
    }

    @Test fun polarityLabelsAreWordsNotInternalNames() {
        assertEquals("Auto", currentPolarityLabel(CurrentPolarityOverride.AUTO))
        assertEquals("Standard", currentPolarityLabel(CurrentPolarityOverride.STANDARD))
        assertEquals("Reversed", currentPolarityLabel(CurrentPolarityOverride.INVERTED))
        CurrentPolarityOverride.entries.forEach { polarity ->
            val label = currentPolarityLabel(polarity)
            assertFalse(label, label.contains("_"))
            assertFalse(label, label == polarity.name)
        }
    }

    @Test fun scaleLabelsStaySymbolsForTheThreeChipRow() {
        assertEquals("Auto", currentUnitLabel(CurrentUnitOverride.AUTO))
        assertEquals("\u00b5A", currentUnitLabel(CurrentUnitOverride.MICROAMPS))
        assertEquals("mA", currentUnitLabel(CurrentUnitOverride.MILLIAMPS))
    }

    @Test fun theSamplingIntervalNoteSeparatesCadenceFromTheMode() {
        // The interval is the cadence and stays a normal setting; the mode decides
        // whether the phone may sleep between readings, so the note must not read as
        // if developer mode or high resolution *is* the interval.
        val low = samplingIntervalNote(MonitorSettings(intervalSeconds = 5, mode = MonitoringMode.LOW_IMPACT))
        assertTrue(low, low.contains("5"))
        assertTrue(low, low.contains("sleep"))
        assertFalse(low, low.contains("(the default)"))

        val high = samplingIntervalNote(MonitorSettings(intervalSeconds = 2, mode = MonitoringMode.HIGH_RESOLUTION))
        assertTrue(high, high.contains("(the default)"))
        assertTrue(high, high.contains("awake"))
        // Both notes say where the cadence is set, so developer mode never looks
        // like the place to change it.
        listOf(low, high).forEach { note ->
            assertTrue(note, note.contains("normal setting"))
            assertTrue(note, note.contains("developer mode"))
        }
    }

    @Test fun theIntervalChipsComeFromTheModelChoices() {
        // The settings chips must offer exactly the model's supported cadences, and
        // the 2 s default is the label the copy marks as default.
        assertEquals(listOf(1, 2, 5), MonitorSettings.ALLOWED_INTERVALS)
        assertEquals(2, MonitorSettings.DEFAULT_INTERVAL_SECONDS)
        MonitorSettings.ALLOWED_INTERVALS.forEach { seconds ->
            val chosen = MonitorSettings(intervalSeconds = seconds).normalized()
            assertEquals(seconds, chosen.intervalSeconds)
        }
    }

    @Test fun theSamplingIntervalStaysAvailableWithoutDeveloperMode() {
        // Normal settings keep the useful options: the interval is saved and read
        // back with developer mode off, and turning developer mode off never clears
        // the chosen interval.
        assertFalse(MonitorSettings().developerMode)
        val chosen = MonitorSettings(intervalSeconds = 5, mode = MonitoringMode.HIGH_RESOLUTION).normalized()
        assertEquals(5, chosen.intervalSeconds)
        assertEquals(MonitoringMode.HIGH_RESOLUTION, chosen.mode)
        val normalized = chosen.copy(developerMode = false).normalized()
        assertEquals(5, normalized.intervalSeconds)
        assertEquals(MonitoringMode.HIGH_RESOLUTION, normalized.mode)
    }
}
