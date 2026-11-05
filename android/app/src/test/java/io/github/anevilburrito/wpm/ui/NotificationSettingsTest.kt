package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.NotificationUpdateRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationSettingsTest {
    @Test fun choicesHaveTheRequestedLabelsAndNoAdditionalOptions() {
        assertEquals(
            listOf("Instant", "15s", "60s"),
            NotificationUpdateRate.entries.map(::notificationUpdateRateLabel),
        )
        assertEquals(listOf(0L, 15_000L, 60_000L),
            NotificationUpdateRate.entries.map { it.minimumIntervalMillis })
    }

    @Test fun theNewOptionPreservesTheExistingDefaultAndNeedsNoDeveloperMode() {
        val settings = MonitorSettings()
        assertFalse(settings.developerMode)
        assertEquals(NotificationUpdateRate.SIXTY_SECONDS, settings.notificationUpdateRate)
        assertEquals(2, settings.intervalSeconds)
    }

    @Test fun changingNotificationRateDoesNotChangeSamplingOrMeasurementSettings() {
        val before = MonitorSettings(
            intervalSeconds = 1,
            currentUnit = CurrentUnitOverride.MILLIAMPS,
            currentPolarity = CurrentPolarityOverride.STANDARD,
            designCapacityMilliampHours = 4_500.0,
        )
        for (rate in NotificationUpdateRate.entries) {
            val after = before.copy(notificationUpdateRate = rate).normalized()
            assertEquals(rate, after.notificationUpdateRate)
            assertEquals(before, after.copy(notificationUpdateRate = before.notificationUpdateRate))
        }
    }
}
