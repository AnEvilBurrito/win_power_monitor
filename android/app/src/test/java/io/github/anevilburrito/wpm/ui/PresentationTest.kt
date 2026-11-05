package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CapacityEstimate
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitorUiState
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.SessionSummary
import io.github.anevilburrito.wpm.model.UnitQuantity
import io.github.anevilburrito.wpm.monitoring.SampleReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTest {
    /**
     * Raw sample: [watts]/[amps] are the **calibrated instantaneous** CURRENT_NOW
     * provenance the live readout shows. [derivedAmps] is the auxiliary 60 s counter
     * average and is deliberately different, so any test proves which one was used.
     */
    private fun sample(
        watts: Double? = -4.8,
        amps: Double? = -1.2,
        volts: Double? = 4.0,
        percentage: Int? = 68,
        flow: FlowState = FlowState.DISCHARGING,
        reason: String? = null,
        derivedAmps: Double? = -0.000315,
        derivedWindowMillis: Long? = 60_000L,
        chargeCounterMicroampHours: Long? = 3_000_000L,
        currentCalibrated: Boolean = true,
        currentSource: CurrentSource = CurrentSource.INSTANT,
        polarityAdjusted: Boolean = false,
    ) = BatterySample(
        timestampMillis = 1_700_000_000_000L,
        elapsedRealtimeMillis = 1_000L,
        watts = watts,
        volts = volts,
        amps = amps,
        percentage = percentage,
        flow = flow,
        reason = reason,
        chargeCounterMicroampHours = chargeCounterMicroampHours,
        derivedAmps = derivedAmps,
        derivedWindowMillis = derivedWindowMillis,
        currentCalibrated = currentCalibrated,
        currentSource = currentSource,
        currentPolarityAdjusted = polarityAdjusted,
    )

    @Test fun freshDischargeShowsTheLiveReadingAbsoluteOrSigned() {
        val absolute = liveReadingView(sample(), running = true, stale = false, absolute = true)
        assertTrue(absolute.live)
        assertEquals(ReadoutState.LIVE, absolute.state)
        // 1.2 A live current at 4.0 V, never the -0.000315 A counter average.
        assertEquals(UnitQuantity("4.80", "W"), absolute.power)
        assertEquals("68", absolute.percentage)
        assertEquals("\u2193 Discharging", absolute.direction)
        assertEquals("4.00 V / 1.20 A", absolute.electrical)
        assertFalse(absolute.lowBattery)
        assertEquals("From the latest calibrated reading", absolute.readiness)

        val signed = liveReadingView(sample(), running = true, stale = false, absolute = false)
        assertEquals(UnitQuantity("\u22124.80", "W"), signed.power)
        assertEquals("4.00 V / \u22121.20 A", signed.electrical)
    }

    @Test fun firstRecordingFrameShowsCalibrationInsteadOfAnUnavailableFlash() {
        // A null first sample is expected startup, even though freshness regards
        // null as stale. The monitor and Diagnostics must agree on this state.
        val first = liveReadingView(null, running = true, stale = true, absolute = true)
        assertEquals(ReadoutState.CALIBRATING, first.state)
        assertEquals("Calibrating", first.headline)
        assertEquals("Calibrating", first.statusLabel)
        assertFalse(first.powerAvailable)
        assertEquals("Waiting for the first reading", first.readiness)
        assertEquals("Ready", liveReadingView(sample(), true, false, true).statusLabel)
    }

    @Test fun theLiveReadoutUsesTheCalibratedInstantNotTheCounterAverage() {
        // Counter average is ~1.26 mW; the live instant is 4.8 W. The readout must
        // show the live reading, and the counter average must stay auxiliary.
        val view = liveReadingView(
            sample(watts = -4.8, amps = -1.2, derivedAmps = -0.000315),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals(UnitQuantity("4.80", "W"), view.power)
        assertEquals("4.00 V / 1.20 A", view.electrical)
        assertFalse(view.power.text.contains("1.26"))
    }

    @Test fun aSavedCalibrationShowsTheReadingWithoutAnyCounterAverage() {
        // The headline no longer waits for the trailing counter window. A fresh,
        // calibrated instant reading is ready even when the counter has no average.
        val view = liveReadingView(
            sample(derivedAmps = null, derivedWindowMillis = null),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals(ReadoutState.LIVE, view.state)
        assertEquals("4.80", view.headline)
        assertTrue(view.powerAvailable)
    }

    @Test fun uncalibratedCurrentShowsCalibratingAndNoNumber() {
        // Unit/sign not established yet, but a counter exists: calibration is a real
        // wait, and nothing numeric is shown as if it were measured.
        val view = liveReadingView(
            sample(currentCalibrated = false),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals(ReadoutState.CALIBRATING, view.state)
        assertEquals(CALIBRATING_TEXT, view.headline)
        assertTrue(view.headlineIsWord)
        assertFalse(view.power.available)
        assertEquals(WpmFormat.DASH, view.power.text)
        assertFalse(view.electrical.contains("A"))
        assertEquals("Flow unknown", view.direction)
        assertEquals("Confirming current scale and sign", view.readiness)
        // Percentage and voltage are real readings and stay visible.
        assertEquals("68", view.percentage)
        assertTrue(view.electrical.startsWith("4.00 V"))
    }

    @Test fun impossibleCalibrationIsUnavailableInsteadOfEndlessCalibrating() {
        val view = liveReadingView(
            sample(currentCalibrated = false, chargeCounterMicroampHours = null),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals(ReadoutState.UNAVAILABLE, view.state)
        assertEquals(UNAVAILABLE_TEXT, view.direction)
        assertEquals("No usable instantaneous current reading", view.readiness)
    }

    @Test fun theMainReadoutAlwaysNamesTheLatestReading() {
        val live = liveReadingView(sample(), running = true, stale = false, absolute = true)
        val stale = liveReadingView(sample(), running = true, stale = true, absolute = true)
        val stopped = liveReadingView(sample(), running = false, stale = true, absolute = true)
        val missing = liveReadingView(null, running = true, stale = true, absolute = true)
        val calibrating = liveReadingView(
            sample(currentCalibrated = false),
            running = true,
            stale = false,
            absolute = true,
        )
        listOf(live, stale, stopped, missing, calibrating).forEach { view ->
            // Same caption in every state: the label never appears or vanishes, so
            // a state change cannot shift the layout.
            assertEquals(LIVE_POWER_CAPTION, view.caption)
            assertTrue(view.caption.contains("latest reading"))
            assertTrue("readiness must never be blank", view.readiness.isNotBlank())
            assertTrue("direction must never be blank", view.direction.isNotBlank())
        }
    }

    @Test fun theCaptionNeverClaimsAnAveragePrimaryMetric() {
        assertEquals("Power & current \u00b7 latest reading", LIVE_POWER_CAPTION)
        assertFalse(LIVE_POWER_CAPTION.contains("Average"))
        assertFalse(LIVE_POWER_CAPTION.contains("rolling"))
        assertFalse(LIVE_POWER_CAPTION.contains("guarantee"))
    }

    @Test fun phoneScaleReadingCarriesMilliwattsAndMicroamps() {
        val live = sample(watts = -0.00126, amps = -0.000315, volts = 4.0)
        val absolute = liveReadingView(live, running = true, stale = false, absolute = true)
        assertEquals(UnitQuantity("1.26", "mW"), absolute.power)
        assertEquals("mW", absolute.powerUnit)
        assertTrue(absolute.powerAvailable)
        assertEquals("4.00 V / 315 \u00b5A", absolute.electrical)

        val signed = liveReadingView(live, running = true, stale = false, absolute = false)
        assertEquals(UnitQuantity("\u22121.26", "mW"), signed.power)
        assertEquals("4.00 V / \u2212315 \u00b5A", signed.electrical)
    }

    @Test fun missingVoltageKeepsAnIndependentlyValidCurrentAndPercentage() {
        // The decoder suppressed watts because no usable voltage exists. Current and
        // percentage are still real, so the readout stays live for those fields.
        val view = liveReadingView(
            sample(watts = null, amps = -1.2, volts = null),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals(ReadoutState.LIVE, view.state)
        assertEquals(WpmFormat.DASH, view.power.text)
        assertFalse(view.powerAvailable)
        assertEquals("68", view.percentage)
        assertEquals("\u2014 V / 1.20 A", view.electrical)
        assertEquals("\u2193 Discharging", view.direction)
    }

    @Test fun staleSampleDoesNotLookLive() {
        val view = liveReadingView(sample(), running = true, stale = true, absolute = true)
        assertFalse(view.live)
        assertEquals(ReadoutState.UNAVAILABLE, view.state)
        assertEquals(WpmFormat.DASH, view.power.text)
        assertFalse("a stale reading must not show a unit suffix", view.powerAvailable)
        assertEquals(WpmFormat.DASH, view.percentage)
        assertEquals(UNAVAILABLE_TEXT, view.direction)
        // The reason lives in the reserved readiness line, not in a banner that
        // appears and vanishes per poll.
        assertEquals("No recent sample from the recorder", view.readiness)
    }

    @Test fun stoppedSessionShowsDashesAndNoFabricatedReason() {
        val view = liveReadingView(sample(), running = false, stale = true, absolute = true)
        assertFalse(view.live)
        assertEquals(ReadoutState.STOPPED, view.state)
        assertEquals(WpmFormat.DASH, view.power.text)
        assertEquals(WpmFormat.DASH, view.headline)
        assertEquals("Not recording", view.direction)
        assertEquals("\u2014 V / \u2014", view.electrical)
        assertEquals("Start recording to measure", view.readiness)
    }

    @Test fun unavailableCurrentKeepsPercentageAndVoltage() {
        // An uncalibrated device with no counter can never confirm current: that is
        // unavailable, not an endless calibration.
        val view = liveReadingView(
            sample(
                watts = null,
                amps = null,
                volts = 4.02,
                percentage = 68,
                derivedAmps = null,
                derivedWindowMillis = null,
                chargeCounterMicroampHours = null,
                currentCalibrated = false,
            ),
            running = true,
            stale = false,
            absolute = true,
        )
        assertFalse(view.live)
        assertEquals(ReadoutState.UNAVAILABLE, view.state)
        assertEquals(WpmFormat.DASH, view.power.text)
        assertEquals("68", view.percentage)
        assertEquals("4.02 V / \u2014", view.electrical)
        assertEquals(UNAVAILABLE_TEXT, view.direction)
    }

    @Test fun aShortFlatCounterWindowDoesNotAffectTheLiveReading() {
        // A zero-length counter window used to blank the old average readout. It is
        // auxiliary now, so the calibrated live reading is unaffected.
        listOf(
            sample(derivedAmps = -1.194, derivedWindowMillis = 0L),
            sample(derivedAmps = -1.194, derivedWindowMillis = null),
        ).forEach { flat ->
            val view = liveReadingView(flat, running = true, stale = false, absolute = true)
            assertEquals(ReadoutState.LIVE, view.state)
            assertTrue(view.power.available)
        }
    }

    @Test fun directionFollowsTheCalibratedLiveSign() {
        val live = liveReadingView(
            sample(amps = 1.2, watts = 4.8, flow = FlowState.CHARGING),
            running = true, stale = false, absolute = false,
        )
        assertEquals("\u2191 Charging", live.direction)
        assertEquals(FlowState.CHARGING, live.flow)
        val idle = liveReadingView(
            sample(amps = 0.0, watts = 0.0, flow = FlowState.IDLE),
            running = true, stale = false, absolute = false,
        )
        assertEquals("Idle", idle.direction)
    }

    @Test fun lowBatteryAddsAWordNotJustColor() {
        val view = liveReadingView(sample(percentage = 18), running = true, stale = false, absolute = true)
        assertTrue(view.lowBattery)
    }

    @Test fun alternatingReadingsNeverProduceAPerSampleBanner() {
        // Reported defect: a per-sample note appeared and vanished on each update.
        // Every state now differs only inside fixed slots, so nothing can flicker.
        val usable = liveReadingView(sample(), running = true, stale = false, absolute = true)
        val calibrating = liveReadingView(
            sample(currentCalibrated = false), running = true, stale = false, absolute = true,
        )
        val unavailable = liveReadingView(
            sample(currentCalibrated = false, chargeCounterMicroampHours = null),
            running = true,
            stale = false,
            absolute = true,
        )
        val stale = liveReadingView(sample(), running = true, stale = true, absolute = true)
        listOf(usable, calibrating, unavailable, stale).forEach { view ->
            assertTrue(view.direction.isNotBlank())
            assertTrue(view.readiness.isNotBlank())
            assertEquals(LIVE_POWER_CAPTION, view.caption)
        }
        assertEquals("\u2193 Discharging", usable.direction)
        assertEquals("Flow unknown", calibrating.direction)
        assertEquals(UNAVAILABLE_TEXT, unavailable.direction)
    }

    @Test fun normalBatteryFlowIsNeverWarningColoured() {
        // Ordinary charging and discharging use normal text; the tone stays out of
        // the warning role, which is reserved for real failures.
        listOf(
            liveReadingView(sample(amps = -1.2, watts = -4.8), running = true, stale = false, absolute = true),
            liveReadingView(sample(amps = 1.2, watts = 4.8), running = true, stale = false, absolute = true),
        ).forEach { view ->
            assertEquals(FlowTone.NORMAL, flowTone(view))
        }
        // A placeholder or stopped header is muted, never warning-coloured.
        listOf(
            liveReadingView(sample(currentCalibrated = false), running = true, stale = false, absolute = true),
            liveReadingView(sample(), running = true, stale = true, absolute = true),
            liveReadingView(sample(), running = false, stale = true, absolute = true),
        ).forEach { view ->
            assertEquals(FlowTone.MUTED, flowTone(view))
        }
    }

    @Test fun everyStableReasonTokenHasAUserFacingLabel() {
        listOf(
            SampleReasons.GAP,
            SampleReasons.INTERVAL_CHANGED,
            SampleReasons.MODE_CHANGED,
            SampleReasons.INCONSISTENT_POLARITY,
            SampleReasons.CURRENT_CALIBRATION_CHANGED,
        ).forEach { token ->
            val text = reasonText(token)
            assertNotNull(token, text)
            assertFalse(token, text!!.contains("_"))
            assertFalse(token, text == token)
        }
    }

    @Test fun unknownInternalTokenNeverReachesTheUserAsCopy() {
        val token = "fuel_gauge_reset"
        assertEquals("This reading was kept with an additional sampling note.", reasonText(token))
    }

    @Test fun boundaryFormatterIsUsedForRecordedReasons() {
        assertEquals(
            "Monitoring mode changed.",
            reasonText(SampleReasons.MODE_CHANGED) { "Monitoring mode changed." },
        )
        assertNull(reasonText(null) { "never used" })
        assertNull(reasonText("  ") { "never used" })
    }

    @Test fun windowStatisticsKeepOrderedExtremaPerScale() {
        val summary = SessionSummary(
            magnitudeAverageWatts = 4.35,
            signedAverageWatts = -4.35,
            minimumMagnitudeWatts = 1.2,
            maximumMagnitudeWatts = 7.8,
            minimumSignedWatts = -7.8,
            maximumSignedWatts = -1.2,
        )
        val absolute = statsView(summary, absolute = true)
        assertEquals(UnitQuantity("4.35", "W"), absolute.average)
        assertEquals(UnitQuantity("1.20", "W"), absolute.minimum)
        assertEquals(UnitQuantity("7.80", "W"), absolute.maximum)
        assertEquals("Session average power (absolute)", absolute.averageLabel)

        val signed = statsView(summary, absolute = false)
        assertEquals(UnitQuantity("\u22124.35", "W"), signed.average)
        assertEquals(UnitQuantity("\u22127.80", "W"), signed.minimum)
        assertEquals(UnitQuantity("\u22121.20", "W"), signed.maximum)
        assertEquals("Session average power (signed)", signed.averageLabel)
    }

    @Test fun milliwattStatisticsCarryTheirOwnUnit() {
        val summary = SessionSummary(
            magnitudeAverageWatts = 0.00126,
            minimumMagnitudeWatts = 0.0009,
            maximumMagnitudeWatts = 0.0021,
        )
        val stats = statsView(summary, absolute = true)
        assertEquals(UnitQuantity("1.26", "mW"), stats.average)
        assertEquals(UnitQuantity("900", "\u00b5W"), stats.minimum)
        assertEquals(UnitQuantity("2.10", "mW"), stats.maximum)
    }

    @Test fun missingStatisticsStayDashesWithoutUnits() {
        val empty = statsView(SessionSummary(), absolute = true)
        assertEquals(WpmFormat.DASH, empty.average.text)
        assertEquals(WpmFormat.DASH, empty.minimum.text)
        assertEquals(WpmFormat.DASH, empty.maximum.text)
        assertFalse(empty.average.available)
        assertFalse(empty.minimum.available)
        assertFalse(empty.maximum.available)
    }

    @Test fun sessionEventsComeFromRecordedSamplesNewestFirst() {
        val summary = SessionSummary(startedAtMillis = 1_700_000_000_000L, elapsedSeconds = 120.0)
        val samples = listOf(
            BatterySample(timestampMillis = 1_700_000_000_000L, elapsedRealtimeMillis = 0L, watts = -2.0),
            BatterySample(timestampMillis = 1_700_000_060_000L, elapsedRealtimeMillis = 60_000L, watts = -3.0, gapBefore = true),
            BatterySample(
                timestampMillis = 1_700_000_090_000L,
                elapsedRealtimeMillis = 90_000L,
                watts = -4.0,
                reason = SampleReasons.MODE_CHANGED,
            ),
        )
        val events = sessionEvents(summary, samples, lowImpact = true, limit = 4)
        assertEquals(3, events.size)
        assertEquals("Reading note", events[0].title)
        assertEquals("monitoring mode changed", events[0].detail)
        assertEquals("Sampling resumed", events[1].title)
        assertEquals("Recording started", events[2].title)
    }

    @Test fun noSamplesStillReportsTheRecordedStart() {
        val summary = SessionSummary(startedAtMillis = 1_700_000_000_000L)
        val events = sessionEvents(summary, emptyList(), lowImpact = false)
        assertEquals(1, events.size)
        assertEquals("High-resolution mode", events[0].detail)
    }

    @Test fun everydayPolicyCopyHasNoWakeLockJargon() {
        val low = policyView(running = true, settings = MonitorSettings())
        assertEquals("Low impact \u00b7 recording in the background", low.title)
        assertFalse("everyday copy must not talk about wake locks", low.title.contains("wake lock"))
        assertFalse(low.detail.contains("wake lock"))
        assertTrue(low.detail.contains("${MonitorSettings.DEFAULT_INTERVAL_SECONDS}s"))

        // A held lock in low impact mode is developer detail, not a headline.
        val lowHeld = policyView(running = true, settings = MonitorSettings(), wakeLockHeld = true)
        assertEquals(low.title, lowHeld.title)
        assertFalse(lowHeld.detail.contains("wake lock"))
    }

    @Test fun stoppedSessionNeverClaimsATechnicalPolicy() {
        val highStopped = policyView(
            running = false,
            settings = MonitorSettings(mode = MonitoringMode.HIGH_RESOLUTION),
            wakeLockHeld = true,
        )
        assertEquals("High resolution selected \u00b7 stopped", highStopped.title)
        assertTrue(highStopped.detail.contains("History is kept"))
        assertFalse(highStopped.detail.contains("wake lock"))
    }

    @Test fun selectedHighResolutionWithoutTheLockIsReportedTruthfully() {
        val unavailable = policyView(
            running = true,
            settings = MonitorSettings(mode = MonitoringMode.HIGH_RESOLUTION),
            wakeLockHeld = false,
        )
        assertEquals("High resolution \u00b7 readings may pause", unavailable.title)
        assertFalse(unavailable.title.contains("screen can stay off"))
        assertTrue(unavailable.detail.contains("Android may delay readings"))
    }

    @Test fun heldWakeLockDrivesTheHighResolutionWording() {
        val held = policyView(
            running = true,
            settings = MonitorSettings(mode = MonitoringMode.HIGH_RESOLUTION),
            wakeLockHeld = true,
        )
        assertEquals("High resolution \u00b7 screen can stay off", held.title)
        assertTrue(held.detail.contains("uses more battery"))
        assertFalse(held.detail.contains("wake lock"))
    }

    @Test fun technicalCadenceAndWakeLockLiveInTheDeveloperDetail() {
        val detail = developerPolicyDetail(
            settings = MonitorSettings(intervalSeconds = 15, mode = MonitoringMode.HIGH_RESOLUTION),
            running = true,
            wakeLockHeld = true,
        )
        assertTrue(detail.contains("15 s"))
        assertTrue(detail.contains("wake lock held"))
        assertTrue(detail.contains("1 s"))

        val unavailable = developerPolicyDetail(
            settings = MonitorSettings(mode = MonitoringMode.HIGH_RESOLUTION),
            running = true,
            wakeLockHeld = false,
        )
        assertTrue(unavailable.contains("requested but not held"))

        val stopped = developerPolicyDetail(MonitorSettings(), running = false, wakeLockHeld = true)
        assertTrue(stopped.contains("not held (not recording)"))
    }

    @Test fun recordingNoteNeverPrintsThePolicyObjectToString() {
        val running = recordingNote(policyView(running = true, settings = MonitorSettings()), running = true)
        assertFalse("the object toString leaked into user copy", running.contains("PolicyView("))
        assertFalse(running.contains("PolicyView"))
        assertFalse(running.contains("title="))
        assertFalse(running.contains("detail="))
        assertEquals(
            listOf("Low impact \u00b7 recording in the background", "Continues when you leave the app"),
            running.split("\n"),
        )

        val stopped = recordingNote(policyView(running = false, settings = MonitorSettings()), running = false)
        assertFalse(stopped.contains("PolicyView"))
        assertTrue(stopped.contains("history saved"))
    }

    @Test fun settingsUseTheIntervalChoicesAndTheTwoSecondDefault() {
        // One source of truth: the chips, the copy and the model agree on 1/2/5 s,
        // and only the constant default is labelled as the default.
        assertEquals(2, MonitorSettings.DEFAULT_INTERVAL_SECONDS)
        assertEquals(listOf(1, 2, 5), MonitorSettings.ALLOWED_INTERVALS)
        assertEquals(MonitorSettings.DEFAULT_INTERVAL_SECONDS, MonitorSettings().intervalSeconds)
        assertTrue(intervalDescription(2).contains("Every 2 seconds"))
        assertTrue(intervalDescription(2).contains("(the default)"))
        assertFalse(intervalDescription(1).contains("default"))
        assertFalse(intervalDescription(5).contains("default"))
        listOf(0, 3, 15, 30).forEach { invalid ->
            assertEquals(
                MonitorSettings.DEFAULT_INTERVAL_SECONDS,
                MonitorSettings(intervalSeconds = invalid).normalized().intervalSeconds,
            )
        }
    }

    @Test fun chartCaptionNamesTheReadingsAndTheWindow() {
        assertEquals("Readings \u00b7 last 5 minutes", chartCaptionText(ChartWindow.FIVE_MINUTES, null, true, false))
        assertEquals(
            "Readings \u00b7 last 30 minutes",
            chartCaptionText(ChartWindow.THIRTY_MINUTES, null, true, false),
        )
        assertEquals(
            "Readings \u00b7 last 2 hours",
            chartCaptionText(ChartWindow.TWO_HOURS, null, true, false),
        )
        // Frozen, stopped and stale states keep their own stable one-line copy.
        assertTrue(chartCaptionText(ChartWindow.FIVE_MINUTES, null, true, true).contains("Waiting"))
        assertTrue(chartCaptionText(ChartWindow.FIVE_MINUTES, null, false, true).contains("Stopped"))
        // The chart is not a smoothing mode any more: no average-primary wording.
        ChartWindow.entries.forEach { window ->
            val caption = chartCaptionText(window, null, true, false)
            assertFalse(caption.contains("Average"))
            assertFalse(caption.contains("Rolling"))
        }
    }

    @Test fun everyOneLineChartCaptionFitsTheLandscapeToolbar() {
        // The toolbar caption is maxLines = 1 and clipped at LABEL_MIN_WIDTH_DP. A
        // trailing explanatory clause used to be cut mid-word; every caption is now
        // short enough that only the window wording is appended.
        ChartWindow.entries.forEach { window ->
            val caption = chartCaptionText(window, null, true, false)
            assertTrue("$caption is too long for one line", caption.length <= 40)
            assertFalse(caption.contains("blank space"))
        }
    }

    private fun capacityState(
        running: Boolean = true,
        capacity: CapacityEstimate = CapacityEstimate(),
    ) = MonitorUiState(running = running, capacity = capacity)

    private fun capacitySample(
        chargeCounterMicroampHours: Long? = 3_120_000L,
        amps: Double? = -0.05,
        derivedAmps: Double? = null,
        cycleCount: Int? = null,
        flow: FlowState = FlowState.DISCHARGING,
        percentage: Int? = 68,
    ) = BatterySample(
        timestampMillis = 1_700_000_000_000L,
        elapsedRealtimeMillis = 1_000L,
        amps = amps,
        percentage = percentage,
        cycleCount = cycleCount,
        chargeCounterMicroampHours = chargeCounterMicroampHours,
        derivedAmps = derivedAmps,
        derivedWindowMillis = if (derivedAmps == null) null else 60_000L,
        flow = flow,
    )

    @Test fun designCapacityIsOnlyWhatTheUserSupplied() {
        val unset = capacityView(capacityState(), capacitySample())
        assertEquals("Not set", unset.designCapacity)
        assertFalse("no digit may be invented", unset.designCapacity.any { it.isDigit() })

        val supplied = capacityView(
            capacityState(),
            capacitySample(),
            designCapacityMilliampHours = 4_500.0,
        )
        assertEquals("4,500 mAh \u00b7 user supplied", supplied.designCapacity)
    }

    @Test fun gaugeEstimateIsSeparateFromObservedCapacity() {
        val view = capacityView(
            capacityState(capacity = CapacityEstimate(estimatedMilliampHours = 4_200.0, estimateCount = 2)),
            capacitySample(),
        )
        assertEquals("\u2248 4,588 mAh \u00b7 single reading", view.gaugeCapacity)
        assertEquals("\u2248 4,200 mAh \u00b7 2 charging windows", view.observedCapacity)
        assertFalse(view.observedCapacity.contains("single reading"))
    }

    @Test fun observedCapacityPendingAndUnavailableCopyIsTruthful() {
        val pending = capacityView(capacityState(), capacitySample())
        assertEquals("Not measured yet \u00b7 needs a longer charging session", pending.observedCapacity)

        val noCounter = capacityView(capacityState(), capacitySample(chargeCounterMicroampHours = null))
        assertEquals("Not exposed by this device", noCounter.observedCapacity)

        val stopped = capacityView(capacityState(running = false), capacitySample())
        assertEquals("Not measured yet", stopped.observedCapacity)

        val gaugeMissing = capacityView(capacityState(), capacitySample(chargeCounterMicroampHours = null))
        assertEquals("Not exposed by this device", gaugeMissing.gaugeCapacity)
        assertEquals("No reading yet", capacityView(capacityState(), null).gaugeCapacity)
    }

    @Test fun observedCapacityCountsChargingWindowsNotPolls() {
        val one = capacityView(
            capacityState(capacity = CapacityEstimate(estimatedMilliampHours = 5_000.0, estimateCount = 1)),
            capacitySample(),
        )
        assertEquals("\u2248 5,000 mAh \u00b7 1 charging window", one.observedCapacity)
    }

    @Test fun estimatedHealthNeedsBothObservedAndDesign() {
        val both = capacityView(
            capacityState(capacity = CapacityEstimate(estimatedMilliampHours = 4_380.0, estimateCount = 2)),
            capacitySample(),
            designCapacityMilliampHours = 4_500.0,
        )
        assertEquals("\u2248 97% of design capacity", both.health)

        assertEquals(
            "Needs an observed and a design capacity",
            capacityView(capacityState(), capacitySample(), designCapacityMilliampHours = 4_500.0).health,
        )
        assertEquals(
            "Needs an observed and a design capacity",
            capacityView(
                capacityState(capacity = CapacityEstimate(estimatedMilliampHours = 4_380.0, estimateCount = 2)),
                capacitySample(),
            ).health,
        )
    }

    @Test fun cycleCountIsReportedOrExplained() {
        assertEquals("418", capacityView(capacityState(), capacitySample(cycleCount = 418)).cycleCount)

        val absent = capacityView(capacityState(), capacitySample())
        assertEquals("Not reported by this device", absent.cycleCount)

        val noSample = capacityView(capacityState(), null)
        assertEquals("Not reported by this device", noSample.cycleCount)
    }

    @Test fun runtimeUsesTheCounterAverageNeverTheInstantRate() {
        // 1000 mAh remaining at a 50 mA counter average is 20 hours.
        val view = capacityView(
            capacityState(),
            capacitySample(chargeCounterMicroampHours = 1_000_000L, amps = -0.05, derivedAmps = -0.05),
        )
        assertEquals("\u2248 20h 00m of use at the recent counter average rate", view.runtime)

        // The same sample with a live rate but no counter average must not report a
        // runtime: the runtime stays a separate counter-average statistic.
        val noAverage = capacityView(
            capacityState(),
            capacitySample(chargeCounterMicroampHours = 1_000_000L, amps = -0.05, derivedAmps = null),
        )
        assertEquals("Waiting for an average rate", noAverage.runtime)
        assertFalse(noAverage.runtime.contains("h"))
    }

    @Test fun chargingRuntimeCountsDownToFullAtTheAverageRate() {
        // (4380 - 1000) mAh at a 1 A average is 3.38 h.
        val view = capacityView(
            capacityState(capacity = CapacityEstimate(estimatedMilliampHours = 4_380.0, estimateCount = 2)),
            capacitySample(
                chargeCounterMicroampHours = 1_000_000L,
                amps = 1.0,
                derivedAmps = 1.0,
                flow = FlowState.CHARGING,
            ),
        )
        assertEquals("\u2248 3h 22m to full at the recent counter average rate", view.runtime)
        assertFalse("no promised completion time", view.runtime.contains("will"))
    }

    @Test fun chargingRuntimeNeedsACapacityFigure() {
        val none = capacityView(
            capacityState(),
            capacitySample(
                chargeCounterMicroampHours = 1_000_000L,
                amps = 1.0,
                derivedAmps = 1.0,
                flow = FlowState.CHARGING,
            ),
        )
        assertEquals("Charging time unavailable until capacity is observed", none.runtime)

        // A user-supplied design capacity is for estimated health only. Settings
        // promises it never changes runtime, so it must not become a charging
        // target here: the row keeps saying the observed capacity is missing.
        val withDesign = capacityView(
            capacityState(),
            capacitySample(
                chargeCounterMicroampHours = 1_000_000L,
                amps = 1.0,
                derivedAmps = 1.0,
                flow = FlowState.CHARGING,
            ),
            designCapacityMilliampHours = 4_380.0,
        )
        assertEquals("Charging time unavailable until capacity is observed", withDesign.runtime)
    }

    @Test fun changingTheManualDesignCapacityNeverChangesReadingsOrRuntime() {
        // The same device state with and without a typed design capacity: the
        // instant reading, percentage and runtime must be byte-identical because
        // design capacity feeds the health ratio only.
        val sample = capacitySample(
            chargeCounterMicroampHours = 1_000_000L,
            amps = 1.0,
            derivedAmps = 1.0,
            flow = FlowState.CHARGING,
            percentage = 42,
        )
        val without = capacityView(capacityState(), sample)
        val with = capacityView(capacityState(), sample, designCapacityMilliampHours = 4_380.0)
        assertEquals(without.runtime, with.runtime)
        assertEquals(without.gaugeCapacity, with.gaugeCapacity)
        assertEquals(without.observedCapacity, with.observedCapacity)

        // The live readout is built without any capacity input at all.
        assertEquals(
            liveReadingView(sample, running = true, stale = false, absolute = true).percentage,
            "42",
        )
    }

    @Test fun dischargingRuntimeNeedsNoCapacityAndAppearsImmediately() {
        val view = capacityView(
            capacityState(),
            capacitySample(
                chargeCounterMicroampHours = 1_000_000L,
                amps = -0.05,
                derivedAmps = -0.05,
                flow = FlowState.DISCHARGING,
            ),
        )
        assertEquals("\u2248 20h 00m of use at the recent counter average rate", view.runtime)
        // Design capacity is absent and the estimate still appears.
        assertEquals("Not set", view.designCapacity)
    }

    @Test fun runtimeIsWithheldWhenTheReadingIsNotLive() {
        val chargeCounterMissing = capacityView(
            capacityState(),
            capacitySample(chargeCounterMicroampHours = null, derivedAmps = -0.05),
        )
        assertEquals("Not exposed by this device", chargeCounterMissing.runtime)

        val stale = capacityView(capacityState(), capacitySample(derivedAmps = -0.05), stale = true)
        assertEquals("No recent reading", stale.runtime)

        val stopped = capacityView(capacityState(running = false), capacitySample(derivedAmps = -0.05))
        assertEquals("Not recording", stopped.runtime)

        val empty = capacityView(capacityState(), null)
        assertEquals("No reading yet", empty.runtime)
    }

    @Test fun diagnosticsShowTheLiveReadingAndKeepTheCounterAverageSeparate() {
        val sample = BatterySample(
            timestampMillis = 0L,
            elapsedRealtimeMillis = 0L,
            amps = -1.0,
            watts = -4.0,
            volts = 4.0,
            currentCalibrated = true,
            currentSource = CurrentSource.INSTANT,
            derivedAmps = -0.000315,
            derivedWindowMillis = 60_000L,
        )
        // Normal diagnostics are the live calibrated reading.
        assertEquals("\u22124.00 W", livePowerText(sample))
        assertEquals("\u22121.00 A", liveCurrentText(sample))
        // The counter average is auxiliary dev comparison, and stays separate.
        assertEquals("\u2212315 \u00b5A", averageCurrentText(sample))
        assertEquals("\u22121.26 mW", averagePowerText(sample))

        // Uncalibrated instant values never become a live reading; the counter
        // average still does not fall back to them.
        val uncalibrated = BatterySample(
            timestampMillis = 0L,
            elapsedRealtimeMillis = 0L,
            amps = -1.0,
            watts = -4.0,
            volts = 4.0,
            currentSource = CurrentSource.INSTANT,
        )
        assertEquals(WpmFormat.DASH, livePowerText(uncalibrated))
        assertEquals(WpmFormat.DASH, liveCurrentText(uncalibrated))
        assertEquals(WpmFormat.DASH, averageCurrentText(uncalibrated))
        assertEquals(WpmFormat.DASH, averagePowerText(uncalibrated))
        assertEquals(WpmFormat.DASH, livePowerText(null))
        assertEquals(WpmFormat.DASH, liveCurrentText(null))
        assertEquals(WpmFormat.DASH, averageCurrentText(null))
        assertEquals(WpmFormat.DASH, averagePowerText(null))
    }

    @Test fun theNotificationAdvisoryNeverOccupiesTheRecordingErrorSlot() {
        // Screenshot defect: "Notifications are disabled…" was drawn as an orange
        // recording error above the readout even though recording was running.
        assertEquals(
            null,
            dashboardError("Notifications are disabled. Recording can continue; use the app to stop it."),
        )
        assertEquals(null, dashboardError("notification permission denied"))
        assertEquals(null, dashboardError(null))
        assertEquals(null, dashboardError("   "))
    }

    @Test fun realFailuresStayVisibleInTheErrorSlot() {
        // Export, service start, stop and persistence failures are actionable and
        // must never be filtered out with the advisory.
        listOf(
            "Export failed: file unavailable",
            "Android could not start recording: service unavailable",
            "Android could not start recording: notification permission denied",
            "Could not stop recording: null",
            "Could not reset calibration: unavailable",
            "Samples could not be written to this device",
        ).forEach { error ->
            assertEquals(error, dashboardError(error))
        }
    }

    @Test fun calibrationSummaryStaysJargonFree() {
        assertEquals(
            "Calibrating while recording",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = true),
        )
        assertEquals(
            "Not calibrated",
            calibrationSummary(MonitorSettings(), CurrentCalibration(), running = false),
        )
        assertEquals(
            "Calibrated from recorded evidence",
            calibrationSummary(
                MonitorSettings(),
                CurrentCalibration(unit = CurrentUnitOverride.MICROAMPS, inverted = false, evidenceWindows = 3),
                running = true,
            ),
        )
        val manual = calibrationSummary(
            MonitorSettings(currentUnit = CurrentUnitOverride.MILLIAMPS),
            CurrentCalibration(),
            running = true,
        )
        assertEquals("Manual correction applied", manual)
        listOf("mA", "\u00b5A", "reversed", "windows").forEach { jargon ->
            assertFalse(manual.contains(jargon))
        }
    }

    @Test fun ordinaryDiagnosticsCadenceLineIsShort() {
        assertEquals("Updates every 15 s.", updateLine(15))
        assertFalse(updateLine(5).contains("battery change"))
    }

    @Test fun calibrationReportsManualCorrectionsFirst() {
        val manualScale = calibrationView(
            settings = MonitorSettings(currentUnit = CurrentUnitOverride.MILLIAMPS),
            calibration = CurrentCalibration(),
            running = true,
        )
        assertEquals("Manual correction", manualScale.status)
        assertTrue(manualScale.detail.contains("mA"))
        assertTrue(manualScale.detail.contains("override"))
        // A manual correction is not saved automatic calibration; recording can
        // still restart the evidence, but nothing is "saved" to reset.
        assertFalse(manualScale.savedCalibration)
        assertEquals("Restart calibration", manualScale.actionLabel)
        assertTrue(manualScale.actionNote.contains("Manual corrections"))

        val manualPolarity = calibrationView(
            settings = MonitorSettings(currentPolarity = CurrentPolarityOverride.INVERTED),
            calibration = CurrentCalibration(),
            running = false,
        )
        assertEquals("Manual correction", manualPolarity.status)
        assertTrue(manualPolarity.detail.contains("reversed"))
    }

    @Test fun savedCalibrationIsReportedWithItsEvidence() {
        val saved = calibrationView(
            settings = MonitorSettings(),
            calibration = CurrentCalibration(
                unit = CurrentUnitOverride.MILLIAMPS,
                inverted = true,
                evidenceWindows = 4,
            ),
            running = true,
        )
        assertEquals("Automatic calibration saved", saved.status)
        assertTrue(saved.detail.contains("mA"))
        assertTrue(saved.detail.contains("reversed"))
        assertTrue(saved.detail.contains("4 independent measurement windows"))
        assertTrue(saved.resetAvailable)
        assertEquals("Reset saved calibration", saved.actionLabel)
    }

    @Test fun uncalibratedCopySaysThereIsNotEnoughEvidence() {
        val recording = calibrationView(MonitorSettings(), CurrentCalibration(), running = true)
        assertEquals("Not calibrated yet", recording.status)
        assertTrue(recording.detail.contains("not enough consistent evidence"))
        assertFalse(recording.detail.contains("saved"))
        // Recording may hold unpublished evidence, so a restart is always offered
        // even though nothing has been saved yet.
        assertTrue("a restart is possible while recording", recording.resetAvailable)
        assertFalse(recording.savedCalibration)
        assertEquals("Restart calibration", recording.actionLabel)
        assertTrue(recording.actionNote.contains("discards the evidence"))
        assertTrue(recording.instruction.contains("while recording"))
        // No deep discharge or full charge is ever required for current calibration.
        assertTrue(recording.instruction.contains("no deep discharge or full charge"))

        val stopped = calibrationView(MonitorSettings(), CurrentCalibration(), running = false)
        assertTrue(stopped.instruction.contains("Start recording"))
        assertFalse("nothing saved and not recording", stopped.resetAvailable)
        assertEquals("Nothing saved to reset. Start recording to calibrate.", stopped.actionNote)

        val partial = calibrationView(
            MonitorSettings(),
            CurrentCalibration(unit = CurrentUnitOverride.MICROAMPS, inverted = null, evidenceWindows = 1),
            running = true,
        )
        assertEquals("Not calibrated yet", partial.status)
        assertTrue("partial evidence can still be reset", partial.resetAvailable)
        assertTrue(partial.savedCalibration)
    }

    @Test fun resetFeedbackIsShortAndStateful() {
        assertTrue(calibrationResetFeedback(running = true).contains("restarts while recording"))
        assertTrue(calibrationResetFeedback(running = false).contains("cleared"))
    }

    @Test fun capacityCopyNeverLeaksInternalTokens() {
        val views = listOf(
            capacityView(capacityState(), null),
            capacityView(capacityState(running = false), capacitySample()),
            capacityView(capacityState(), capacitySample(), designCapacityMilliampHours = 4_500.0),
            capacityView(
                capacityState(capacity = CapacityEstimate(estimatedMilliampHours = 4_380.0, estimateCount = 2)),
                capacitySample(cycleCount = 418, amps = 1.0, derivedAmps = 1.0, flow = FlowState.CHARGING),
                designCapacityMilliampHours = 4_500.0,
            ),
        )
        val forbidden = listOf("CapacityView(", "PolicyView(", "null", "=", "_", "title=", "detail=")
        views.forEach { view ->
            listOf(
                view.designCapacity,
                view.gaugeCapacity,
                view.observedCapacity,
                view.health,
                view.cycleCount,
                view.runtime,
            ).forEach { text ->
                assertTrue(text, text.isNotBlank())
                forbidden.forEach { token ->
                    assertFalse("'$text' leaked '$token'", text.contains(token))
                }
            }
        }
    }

    @Test fun calibrationCopyNeverLeaksInternalTokens() {
        val views = listOf(
            calibrationView(MonitorSettings(), CurrentCalibration(), running = true),
            calibrationView(MonitorSettings(), CurrentCalibration(), running = false),
            calibrationView(
                MonitorSettings(currentUnit = CurrentUnitOverride.MICROAMPS),
                CurrentCalibration(unit = CurrentUnitOverride.MILLIAMPS, inverted = false, evidenceWindows = 3),
                running = true,
            ),
        )
        val forbidden = listOf("CurrentCalibration(", "MonitorSettings(", "null", "=", "_")
        views.forEach { view ->
            listOf(view.status, view.detail, view.instruction, view.actionLabel, view.actionNote).forEach { text ->
                assertTrue(text, text.isNotBlank())
                forbidden.forEach { token ->
                    assertFalse("'$text' leaked '$token'", text.contains(token))
                }
            }
        }
    }
}
