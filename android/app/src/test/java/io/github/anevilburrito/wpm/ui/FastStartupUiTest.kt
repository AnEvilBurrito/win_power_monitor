package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.LiveReading
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.UnitQuantity
import io.github.anevilburrito.wpm.model.liveReading
import io.github.anevilburrito.wpm.monitoring.FastCurrentInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI contract for the fast five-poll current interpretation.
 *
 * The backend heuristic ([FastCurrentInterpreter]) is tested on its own. This file
 * only checks what the user sees: that a tentative reading is a **live numeric**
 * state labelled Estimated, that the first polls count up inside the reserved
 * readiness line without any new row or animation, that a finished-but-ambiguous
 * check is actionable instead of an endless Calibrating, and that diagnostics
 * reports the live startup state separately from durable calibration evidence.
 *
 * Nothing here invents a number: an estimated reading is only checked for the value
 * the sample actually carries, and an unavailable one keeps the em dash.
 */
class FastStartupUiTest {

    /**
     * Raw live sample. [currentCalibrated] is false by default because a startup
     * sample genuinely is unconfirmed: the tentative path is the norm, not an edge.
     * The auxiliary counter average is deliberately far away from [amps], so a test
     * that passes proves the live metric was used.
     */
    private fun sample(
        watts: Double? = -4.8,
        amps: Double? = -1.2,
        volts: Double? = 4.0,
        percentage: Int? = 68,
        currentCalibrated: Boolean = false,
        currentSource: CurrentSource = CurrentSource.INSTANT,
        chargeCounter: Long? = 3_000_000L,
        startupPollCount: Int = 0,
        startupCheckComplete: Boolean = false,
    ) = BatterySample(
        timestampMillis = 1_700_000_000_000L,
        elapsedRealtimeMillis = 1_000L,
        watts = watts,
        volts = volts,
        amps = amps,
        percentage = percentage,
        flow = FlowState.DISCHARGING,
        chargeCounterMicroampHours = chargeCounter,
        derivedAmps = -0.000315,
        derivedWindowMillis = 60_000L,
        currentCalibrated = currentCalibrated,
        currentSource = currentSource,
        startupPollCount = startupPollCount,
        startupCheckComplete = startupCheckComplete,
    )

    /** The five-poll window is complete and its interpretation is in use. */
    private fun estimatedSample(chargeCounter: Long? = 3_000_000L) = sample(
        currentSource = CurrentSource.INSTANT_ESTIMATED,
        currentCalibrated = false,
        chargeCounter = chargeCounter,
        startupPollCount = STARTUP_CHECK_POLLS,
        startupCheckComplete = true,
    )

    /** The window is still filling: [poll] of five independent readings are in. */
    private fun pendingSample(poll: Int) = sample(
        currentSource = CurrentSource.INSTANT,
        currentCalibrated = false,
        startupPollCount = poll,
        startupCheckComplete = false,
    )

    /** The window finished without agreeing on scale and sign. */
    private fun ambiguousSample() = sample(
        watts = null,
        amps = -1.2,
        currentSource = CurrentSource.INSTANT,
        currentCalibrated = false,
        startupPollCount = STARTUP_CHECK_POLLS,
        startupCheckComplete = true,
    )

    @Test fun anEstimatedReadingIsALiveNumericState() {
        val view = liveReadingView(estimatedSample(), running = true, stale = false, absolute = true)
        // Live, numeric and complete: only the wording says Estimated.
        assertEquals(ReadoutState.LIVE, view.state)
        assertTrue(view.live)
        assertTrue(view.estimated)
        assertTrue(view.powerAvailable)
        assertFalse(view.headlineIsWord)
        assertEquals("4.80", view.headline)
        assertEquals("W", view.powerUnit)
        assertEquals("Estimated", view.statusLabel)
        assertEquals("\u2193 Discharging", view.direction)
        assertEquals("4.00 V / 1.20 A", view.electrical)
        assertEquals("Estimated \u00b7 checking in background", view.readiness)
        // The tentative value is the sample's own number, never a guess from the
        // counter average (which is ~1.26 mW here).
        assertFalse(view.electrical.contains("315"))
        assertEquals(UnitQuantity("4.80", "W"), view.power)

        val signed = liveReadingView(estimatedSample(), running = true, stale = false, absolute = false)
        assertEquals(UnitQuantity("\u22124.80", "W"), signed.power)
        assertTrue(signed.estimated)

        // Estimated is not a fifth quiet state: the live states agree on the word
        // that is NOT shown, so the headline keeps the numeric slot budget.
        assertEquals(LiveReading.ESTIMATED, estimatedSample().liveReading())
        assertTrue(signed.headline.length <= ReadoutSlots.WIDEST_NUMBER_CHARS)
    }

    @Test fun estimatedOnADeviceWithoutACounterDoesNotPromiseValidation() {
        val view = liveReadingView(
            estimatedSample(chargeCounter = null),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals(ReadoutState.LIVE, view.state)
        assertTrue(view.powerAvailable)
        // The counter calibration can never arrive here, so the line says so instead
        // of claiming a background check that would never finish.
        assertEquals("Estimated \u00b7 counter validation unavailable", view.readiness)
        assertFalse(view.readiness.contains("checking"))
    }

    @Test fun theFirstPollsCountUpInsideTheReservedReadinessLine() {
        val lines = (1..4).map { poll ->
            val view = liveReadingView(pendingSample(poll), running = true, stale = false, absolute = true)
            assertEquals("poll $poll", ReadoutState.CALIBRATING, view.state)
            assertFalse("no number may be invented", view.powerAvailable)
            assertEquals(WpmFormat.DASH, view.power.text)
            // The existing Calibrating headline is kept for these first polls, so the
            // word budget of the readout slots does not change.
            assertEquals(CALIBRATING_TEXT, view.headline)
            assertTrue(view.headlineIsWord)
            assertEquals(CALIBRATING_TEXT, view.statusLabel)
            assertEquals("Flow unknown", view.direction)
            // Only the words in the single readiness line change: no extra row.
            assertEquals("Checking reading $poll/$STARTUP_CHECK_POLLS", view.readiness)
            assertEquals(LIVE_POWER_CAPTION, view.caption)
            // Raw readings that are real stay visible while the count runs.
            assertEquals("68", view.percentage)
            assertTrue(view.electrical.startsWith("4.00 V"))
            view
        }
        assertEquals(4, lines.map { it.readiness }.distinct().size)
        // No blink, no animation state: nothing but the count word differs, and no
        // state grows a line or changes the reserved caption.
        lines.forEach { view ->
            assertFalse(view.readiness.contains("\n"))
            assertTrue(view.readiness.length <= 48)
        }
    }

    @Test fun aCompletedCheckWithoutAnAnswerIsActionableInsteadOfEndlessCalibrating() {
        val value = ambiguousSample()
        assertEquals(LiveReading.UNAVAILABLE, value.liveReading())
        val view = liveReadingView(value, running = true, stale = false, absolute = true)
        assertEquals(ReadoutState.UNAVAILABLE, view.state)
        assertFalse(view.live)
        assertFalse(view.estimated)
        // Nothing numeric is invented for current/power...
        assertEquals(WpmFormat.DASH, view.power.text)
        assertFalse(view.powerAvailable)
        assertEquals("4.00 V / \u2014", view.electrical)
        // ...while the real level and voltage stay readable.
        assertEquals("68", view.percentage)
        // The copy is short, says what is wrong, and names the way out.
        assertEquals(AMBIGUOUS_STARTUP_TEXT, view.readiness)
        assertTrue(view.readiness.contains("manual correction"))
        assertFalse(view.readiness.contains("Calibrating"))
        assertFalse(view.readiness.contains("Checking reading"))
        assertEquals(UNAVAILABLE_TEXT, view.statusLabel)
    }

    @Test fun startupStatesKeepTheSameSlotsAndTheSameCaption() {
        val views = listOf(
            liveReadingView(null, running = true, stale = true, absolute = true),
            liveReadingView(pendingSample(1), running = true, stale = false, absolute = true),
            liveReadingView(estimatedSample(), running = true, stale = false, absolute = true),
            liveReadingView(ambiguousSample(), running = true, stale = false, absolute = true),
            liveReadingView(sample(currentCalibrated = true), running = true, stale = false, absolute = true),
            liveReadingView(sample(currentCalibrated = true), running = true, stale = true, absolute = true),
            liveReadingView(sample(currentCalibrated = true), running = false, stale = true, absolute = true),
        )
        views.forEach { view ->
            // One reserved caption, one reserved readiness line, no blank slot in any
            // startup state: the fixed geometry cannot reflow between them.
            assertEquals(LIVE_POWER_CAPTION, view.caption)
            assertTrue("readiness must never be blank", view.readiness.isNotBlank())
            assertTrue("direction must never be blank", view.direction.isNotBlank())
            assertFalse(view.readiness.contains("\n"))
            assertEquals(view.state == ReadoutState.CALIBRATING, view.headlineIsWord)
        }
        // Only the numeric live states carry a bounding-box-safe number.
        views.filter { it.live }.forEach { view ->
            assertTrue(view.headline.length <= ReadoutSlots.WIDEST_NUMBER_CHARS)
        }
    }

    @Test fun diagnosticsCalibrationShowsTheStartupCheckAsProgress() {
        val pending = calibrationView(
            settings = MonitorSettings(),
            calibration = CurrentCalibration(),
            running = true,
            latest = pendingSample(3),
        )
        assertEquals("Checking the first readings", pending.status)
        assertTrue(pending.detail.contains("Checking reading 3/5"))
        // The old copy claimed that nothing is inferred, which is no longer true.
        assertFalse(pending.detail.contains("nothing is guessed"))
        assertFalse("an estimate is never saved evidence", pending.savedCalibration)
        assertTrue(pending.resetAvailable)
        assertEquals("Restart calibration", pending.actionLabel)
        assertTrue(pending.instruction.contains("while recording"))
    }

    @Test fun diagnosticsReportsTheEstimatedReadingAsEstimatedButNotSaved() {
        val estimated = calibrationView(
            settings = MonitorSettings(),
            calibration = CurrentCalibration(),
            running = true,
            latest = estimatedSample(),
        )
        assertEquals("Estimated reading", estimated.status)
        assertTrue(estimated.detail.contains("first five readings"))
        assertTrue(estimated.detail.contains("Counter validation"))
        assertFalse(estimated.savedCalibration)
        assertFalse(estimated.detail.contains("nothing is guessed"))
        assertEquals("Restart calibration", estimated.actionLabel)

        val noCounter = calibrationView(
            settings = MonitorSettings(),
            calibration = CurrentCalibration(),
            running = true,
            latest = estimatedSample(chargeCounter = null),
        )
        assertEquals("Estimated reading", noCounter.status)
        assertTrue(noCounter.detail.contains("no charge counter"))
        assertFalse(noCounter.detail.contains("Counter validation continues"))
    }

    @Test fun diagnosticsExplainsAnUnclearStartupWithoutClaimingSuccess() {
        val unclear = calibrationView(
            settings = MonitorSettings(),
            calibration = CurrentCalibration(),
            running = true,
            latest = ambiguousSample(),
        )
        assertEquals("Current interpretation unclear", unclear.status)
        assertTrue(unclear.detail.contains("did not agree"))
        assertTrue("the copy must be actionable", unclear.detail.contains("settings", ignoreCase = true))
        assertFalse(unclear.savedCalibration)
        assertFalse("no indefinite calibration claim", unclear.status.contains("Calibrating"))
    }

    @Test fun savedCalibrationOutranksTheLiveEstimateInDiagnostics() {
        val saved = calibrationView(
            settings = MonitorSettings(),
            calibration = CurrentCalibration(
                unit = CurrentUnitOverride.MICROAMPS,
                inverted = false,
                evidenceWindows = 3,
            ),
            running = true,
            latest = estimatedSample(),
        )
        // The durable, counter-confirmed result is reported as such even while a new
        // recording happens to hold a tentative estimate.
        assertEquals("Automatic calibration saved", saved.status)
        assertTrue(saved.detail.contains("3 independent measurement windows"))
        assertTrue(saved.savedCalibration)
    }

    @Test fun theDefaultCalibrationViewStillDescribesOnlyTheDurableState() {
        // Defaults unchanged: callers that pass no sample keep the previous intent.
        val recording = calibrationView(MonitorSettings(), CurrentCalibration(), running = true)
        assertEquals("Not calibrated yet", recording.status)
        assertTrue(recording.detail.contains("not enough consistent evidence"))
        assertFalse(recording.savedCalibration)
        assertEquals("Not calibrated yet", calibrationView(MonitorSettings(), CurrentCalibration(), running = false).status)
        assertEquals("Manual correction", calibrationView(
            MonitorSettings(currentPolarity = CurrentPolarityOverride.INVERTED),
            CurrentCalibration(),
            running = true,
            latest = estimatedSample(),
        ).status)
    }

    @Test fun calibrationSummaryFollowsTheLiveStartupState() {
        assertEquals(
            "Checking reading 2/5",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = true, latest = pendingSample(2)),
        )
        assertEquals(
            "Estimated \u00b7 counter validation pending",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = true, latest = estimatedSample()),
        )
        assertEquals(
            "Current interpretation unclear",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = true, latest = ambiguousSample()),
        )
        assertEquals(
            "Calibrated from recorded evidence",
            calibrationSummary(
                MonitorSettings(),
                CurrentCalibration(unit = CurrentUnitOverride.MICROAMPS, inverted = false, evidenceWindows = 2),
                running = true,
                latest = estimatedSample(),
            ),
        )
        assertEquals(
            "Manual correction applied",
            calibrationSummary(
                MonitorSettings(currentUnit = CurrentUnitOverride.MILLIAMPS),
                CurrentCalibration(),
                running = true,
                latest = estimatedSample(),
            ),
        )
        // Intact defaults: no sample, or stopped recording.
        assertEquals(
            "Calibrating while recording",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = true),
        )
        assertEquals(
            "Not calibrated",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = false, latest = estimatedSample()),
        )
    }

    @Test fun diagnosticsNeverPromisesCounterValidationWhenNoCounterExists() {
        assertEquals(
            "Estimated \u00b7 no counter validation",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), true,
                latest = estimatedSample(chargeCounter = null)),
        )
    }

    @Test fun unsupportedCurrentDoesNotLeaveDiagnosticsStuckCalibrating() {
        val missing = pendingSample(1).copy(currentSource = CurrentSource.NONE, amps = null)
        assertEquals(UNAVAILABLE_TEXT,
            calibrationSummary(MonitorSettings(), CurrentCalibration(), true, latest = missing))
    }

    @Test fun thePowerParagraphNeverCallsAnEstimateCalibrated() {
        val estimated = livePowerCaption(estimated = true)
        assertTrue(estimated.contains("estimated"))
        assertFalse("an estimate must not be called calibrated", estimated.contains("calibrated"))
        assertTrue(estimated.contains("still being confirmed"))

        val confirmed = livePowerCaption(estimated = false)
        assertTrue(confirmed.contains("calibrated"))
        assertFalse(confirmed.contains("estimated"))
        listOf(estimated, confirmed).forEach { caption ->
            assertTrue(caption.contains("not charger input"))
            assertTrue(caption.contains("not a rolling average"))
        }
        // The confirmed wording is the one already shipped, unchanged.
        assertTrue(confirmed.startsWith("Power is the latest calibrated battery current"))
    }

    @Test fun developerRowsSeparateTheEstimateFromDurableEvidence() {
        assertEquals("3/$STARTUP_CHECK_POLLS \u00b7 in progress", startupCheckText(pendingSample(3)))
        assertEquals("$STARTUP_CHECK_POLLS/$STARTUP_CHECK_POLLS \u00b7 complete", startupCheckText(estimatedSample()))
        assertEquals("0/$STARTUP_CHECK_POLLS \u00b7 not started", startupCheckText(sample()))
        // A stapled count can never claim more polls than the window holds.
        assertEquals("$STARTUP_CHECK_POLLS/$STARTUP_CHECK_POLLS \u00b7 in progress", startupCheckText(sample(startupPollCount = 9)))
        assertEquals(WpmFormat.DASH, startupCheckText(null))

        assertTrue(interpretationText(estimatedSample()).contains("estimated"))
        assertTrue(interpretationText(estimatedSample()).contains("awaiting counter validation"))
        assertEquals("confirmed", interpretationText(sample(currentCalibrated = true)))
        assertEquals("unconfirmed instantaneous", interpretationText(pendingSample(2)))
        assertEquals("no instantaneous source", interpretationText(sample(currentSource = CurrentSource.NONE, amps = null)))
        assertEquals(WpmFormat.DASH, interpretationText(null))
    }

    @Test fun theUiWindowIsTheInterpreterWindow() {
        // One number, two layers: the readiness count cannot drift from the backend.
        assertEquals(FastCurrentInterpreter.WINDOW_POLLS, STARTUP_CHECK_POLLS)
        assertEquals(5, STARTUP_CHECK_POLLS)
    }

    @Test fun notificationStringsMatchTheUiWording() {
        val xml = stringsXml()
        assertTrue(xml.contains("<string name=\"notification_live_checking\">Checking reading %1\$d/5</string>"))
        assertTrue(xml.contains("<string name=\"notification_live_estimated\">$ESTIMATED_TEXT</string>"))
        assertTrue(xml.contains("<string name=\"notification_live_ambiguous\">Current interpretation unclear</string>"))
        // The notification count and the on-screen count are the same sentence, so
        // the persistent surface cannot disagree with the app.
        val template = "Checking reading %1\$d/5"
        assertEquals(template.replace("%1\$d", "3"), startupProgressText(pendingSample(3)))
        assertNotEquals(
            startupProgressText(pendingSample(1)),
            startupProgressText(pendingSample(2)),
        )
    }

    /** `strings.xml` resolved from the module or repository working directory. */
    private fun stringsXml(): String {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map { File(it) }
            .firstOrNull { it.isFile }
        assertNotNull("strings.xml not found from ${File(".").absolutePath}", file)
        return file!!.readText()
    }
}
