package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.*
import org.junit.Assert.*
import org.junit.Test

class FastStartupIntegrationTest {
    private fun raw(index: Int, current: Long = -178L, counter: Long? = null) = RawBatteryReading(
        timestampMillis = 1_700_000_000_000L + index * 1_000L,
        elapsedRealtimeMillis = 1_000L + index * 1_000L,
        currentMicroamps = current,
        chargeCounterMicroampHours = counter,
        voltageMillivolts = 4_000,
        plugged = 0,
        status = BatteryTelemetryDecoder.STATUS_DISCHARGING,
        level = 60, scale = 100,
    )

    private fun decode(raw: RawBatteryReading, startup: FastCurrentInterpreter.Result): BatterySample {
        val tentative = startup.interpretation?.takeIf { it.calibrated }
        return BatteryTelemetryDecoder.decode(
            raw, calibration = tentative ?: CurrentCalibration(),
            provisional = tentative != null,
            startupPollCount = startup.pollCount, startupCheckComplete = startup.complete,
        )
    }

    private fun estimated(): BatterySample {
        val fast = FastCurrentInterpreter()
        return (0..4).map { index -> decode(raw(index), fast.update(raw(index))) }.last()
    }

    @Test fun fivePollsWorkWithoutAChargeCounterAndKeepConfidenceHonest() {
        val fast = FastCurrentInterpreter()
        for (index in 0..4) {
            val sample = decode(raw(index), fast.update(raw(index)))
            assertEquals(index + 1, sample.startupPollCount)
            assertFalse(sample.currentCalibrated)
            if (index < 4) {
                assertEquals(LiveReading.CALIBRATING, sample.liveReading())
                assertNull(sample.liveAmps)
                assertNull(sample.forLiveDisplay().watts)
            } else {
                assertEquals(LiveReading.ESTIMATED, sample.liveReading())
                assertEquals(CurrentSource.INSTANT_ESTIMATED, sample.currentSource)
                assertEquals(-0.178, sample.liveAmps!!, 1e-9)
                assertEquals(-0.712, sample.liveWatts!!, 1e-9)
            }
        }
    }

    @Test fun ambiguousFifthPollEndsTheWaitWithoutInventingAReading() {
        val fast = FastCurrentInterpreter()
        val sample = (0..4).map { index ->
            val reading = raw(index, current = -15_000L)
            decode(reading, fast.update(reading))
        }.last()
        assertTrue(sample.startupCheckComplete)
        assertEquals(LiveReading.UNAVAILABLE, sample.liveReading())
        assertNull(sample.liveWatts)
    }

    @Test fun estimatesCannotPassAutomaticCalibrationPublicationEvenIfMisrouted() {
        val fast = FastCurrentInterpreter()
        (0..4).forEach { fast.update(raw(it)) }
        val guess = fast.result.interpretation!!
        assertEquals(0, guess.evidenceWindows)
        val publication = CalibrationPublishState()
        assertEquals(CalibrationPublishState.Precheck.ALREADY_CURRENT, publication.precheck(0, guess))
        assertEquals(CalibrationPublishState.PublishResult.ALREADY_CURRENT,
            publication.commitDiscovered(guess, durableWriteSucceeded = true))
        assertFalse(publication.value.calibrated)
        assertEquals(0L, publication.revision)
    }

    @Test fun backgroundCounterValidationContinuesAfterTheFastReadingAppears() {
        val fast = FastCurrentInterpreter()
        val counter = CurrentCalibrator()
        for (index in 0..45) {
            val reading = raw(index, current = -720L, counter = 4_000_000L - 200L * index)
            val confirmed = counter.update(reading)
            val startup = fast.update(reading)
            val sample = if (confirmed.calibrated) {
                BatteryTelemetryDecoder.decode(reading, calibration = confirmed)
            } else decode(reading, startup)
            if (index in 4..44) assertEquals(LiveReading.ESTIMATED, sample.liveReading())
            if (index == 45) {
                assertEquals(LiveReading.READY, sample.liveReading())
                assertTrue(sample.currentCalibrated)
                assertEquals(3, confirmed.evidenceWindows)
                assertEquals(-0.720, sample.liveAmps!!, 1e-9)
            }
        }
    }

    @Test fun savedAndFullyManualInterpretationsStillWorkOnTheFirstPoll() {
        val saved = BatteryTelemetryDecoder.decode(raw(0), calibration =
            CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 3))
        val manual = BatteryTelemetryDecoder.decode(raw(0),
            currentUnit = CurrentUnitOverride.MILLIAMPS,
            currentPolarity = CurrentPolarityOverride.STANDARD,
            calibration = CurrentCalibration(CurrentUnitOverride.MICROAMPS, true), provisional = true)
        for (sample in listOf(saved, manual)) {
            assertEquals(LiveReading.READY, sample.liveReading())
            assertEquals(-0.178, sample.liveAmps!!, 1e-9)
            assertEquals(CurrentSource.INSTANT, sample.currentSource)
        }
    }

    @Test fun invalidVoltageDoesNotGetResurrectedByTheEstimatedPath() {
        val fast = FastCurrentInterpreter()
        (0..4).forEach { fast.update(raw(it)) }
        val sample = decode(raw(5).copy(voltageMillivolts = 0), fast.result)
        assertEquals(LiveReading.ESTIMATED, sample.liveReading())
        assertNotNull(sample.liveAmps)
        assertNull(sample.liveWatts)
    }

    @Test fun sourceProvenanceSurvivesLossOfTransientProgressOnHistoryRestore() {
        val sample = estimated()
        // These are exactly the durable source/confidence fields stored by SQLite;
        // the live-only progress fields deliberately have defaults after restoration.
        val restored = sample.copy(currentSource = CurrentSource.valueOf(sample.currentSource.name),
            startupPollCount = 0, startupCheckComplete = false)
        assertEquals(LiveReading.ESTIMATED, restored.liveReading())
        assertFalse(restored.currentCalibrated)
        assertTrue(CsvExporter.row(restored).contains("INSTANT_ESTIMATED"))
    }

    @Test fun aCorrectionSplitsIntegrationAndNeverRewritesEarlierEstimatedEnergy() {
        val first = estimated()
        val tracker = CurrentInterpretationTracker()
        val settings = MonitorSettings()
        val guessed = CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false)
        tracker.observe(CurrentInterpretation.of(settings, guessed))
        val accumulator = SessionAccumulator(1, first.timestampMillis, first.elapsedRealtimeMillis,
            samplePowerWatts = { it.liveWatts })
        accumulator.add(first, 1)
        val before = accumulator.add(first.copy(elapsedRealtimeMillis = 6_000), 1)
        val correction = CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 3)
        val changed = tracker.observe(CurrentInterpretation.of(settings, correction))
        val corrected = BatteryTelemetryDecoder.decode(raw(6), calibration = correction).copy(gapBefore = changed)
        assertTrue(changed)
        val after = accumulator.add(corrected, 1)
        assertEquals(before.outWh, after.outWh, 0.0)
        assertEquals(before.observedSeconds, after.observedSeconds, 0.0)
        assertEquals(-0.178, first.liveAmps!!, 1e-9)
        assertEquals(-0.000178, corrected.liveAmps!!, 1e-12)
    }

    @Test fun theFifthPollRestoresEveryConfiguredCadenceWithoutWaitingForValidation() {
        val sample = estimated()
        for (seconds in MonitorSettings.ALLOWED_INTERVALS) {
            val decision = StartupSamplingPolicy.decide(5_000, 1_000,
                instantCalibrationEstablished = sample.currentCalibrated,
                startupCheckComplete = sample.startupCheckComplete,
                instantReadingSupported = true, configuredIntervalMillis = seconds * 1_000L)
            assertFalse(decision.burstActive)
            assertEquals(seconds * 1_000L, decision.intervalMillis)
            assertEquals(seconds * 1_000L, decision.minimumSpacingMillis)
        }
    }

    @Test fun confidenceAndAmbiguityTransitionsBypassTheNotificationThrottle() {
        val estimated = estimated()
        val confirmed = estimated.copy(currentSource = CurrentSource.INSTANT, currentCalibrated = true)
        for ((sample, previous) in listOf(
            estimated to LiveReading.CALIBRATING,
            confirmed to LiveReading.ESTIMATED,
            BatteryTelemetryDecoder.decode(raw(4)).copy(startupCheckComplete = true) to LiveReading.CALIBRATING,
        )) {
            assertTrue(NotificationUpdatePolicy.shouldUpdate(NotificationUpdateRate.SIXTY_SECONDS,
                5_000, 4_999, false, true, previous == LiveReading.ESTIMATED,
                sample, previousLiveReading = previous))
        }
        assertFalse(NotificationUpdatePolicy.shouldUpdate(NotificationUpdateRate.SIXTY_SECONDS,
            5_000, 4_999, false, true, true, estimated,
            previousLiveReading = LiveReading.ESTIMATED))
    }

    @Test fun newSummaryMetadataExplicitlyAllowsEstimatedSegments() {
        val metadata = CsvExporter.powerMethodMetadataLine(CsvExporter.SummaryMetric.INSTANT_INTERPRETED)
        assertTrue(metadata.contains("summary_metric=instant_interpreted"))
        assertTrue(metadata.contains("startup_estimates_possible=true"))
        assertTrue(metadata.contains("estimated_sample_source=INSTANT_ESTIMATED"))
        assertFalse(metadata.contains("calibrated"))
        assertFalse(CsvExporter.powerMethodMetadataLine().contains("summary_metric=instant_interpreted"))
    }
}
