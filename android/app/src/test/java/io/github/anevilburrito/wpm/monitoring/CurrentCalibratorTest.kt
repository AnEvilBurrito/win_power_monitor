package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidence rules for [CurrentCalibrator].
 *
 * A sequence of polls is built [STEP_MILLIS] apart. `CURRENT_NOW` reports
 * [rawMicroamps] (which may be scaled or inverted relative to reality) and the
 * charge counter moves by exactly the charge [trueMicroamps] implies over each
 * step, so the comparison the calibrator makes is fully under test control.
 * *
 * A window closes as soon as it is [CurrentCalibrator.MIN_WINDOW_MILLIS] long, i.e.
 * after exactly three [STEP_MILLIS] steps, so each `appendSteady(pollCount = 3, ...)`
 * segment is one closed window and seven such segments would be seven windows. The
 * calibrator only counts a **consecutive** run of compatible windows, so tests that
 * splice a contradicting window, hole, artefact or unusable window into a run must
 * expect the earlier windows to be gone.
 */
class CurrentCalibratorTest {

    private fun reading(elapsed: Long, current: Long?, counter: Long?) = RawBatteryReading(
        timestampMillis = elapsed,
        elapsedRealtimeMillis = elapsed,
        currentMicroamps = current,
        chargeCounterMicroampHours = counter,
    )

    /** First poll of a run: no window exists yet, it only anchors one. */
    private fun start(current: Long = TRUE_MICROAMPS, counter: Long = START_COUNTER) =
        mutableListOf(reading(0L, current, counter))

    /**
     * Appends [pollCount] polls [stepMillis] apart, where the counter moves by the
     * charge [trueMicroamps] implies and the raw property reports [rawMicroamps].
     */
    private fun MutableList<RawBatteryReading>.appendSteady(
        pollCount: Int,
        rawMicroamps: Long? = TRUE_MICROAMPS,
        trueMicroamps: Long = TRUE_MICROAMPS,
        stepMillis: Long = STEP_MILLIS,
    ) {
        var elapsed = last().elapsedRealtimeMillis
        var counter = last().chargeCounterMicroampHours ?: START_COUNTER
        val deltaPerStep = trueMicroamps * stepMillis / MICROAMP_HOURS_PER_MICROAMP_MILLIS
        repeat(pollCount) {
            elapsed += stepMillis
            counter += deltaPerStep
            add(reading(elapsed, rawMicroamps, counter))
        }
    }

    private fun run(readings: List<RawBatteryReading>): Pair<CurrentCalibrator, CurrentCalibration> {
        val calibrator = CurrentCalibrator()
        var latest = CurrentCalibration()
        for (item in readings) latest = calibrator.update(item)
        return calibrator to latest
    }

    /**
     * Two clean windows (no calibration yet) followed by the third, which must
     * establish it. Uses the 9-poll/10-poll boundary of a 5 s cadence.
     */
    private fun twoWindowRun() = start().apply { appendSteady(pollCount = 8) }

    private val establishedMicroamps = CurrentCalibration(
        unit = CurrentUnitOverride.MICROAMPS,
        inverted = false,
        evidenceWindows = 3,
    )

    @Test
    fun noCalibrationBeforeThreeCompleteWindows() {
        val (calibrator, calibration) = run(twoWindowRun())
        assertEquals(CurrentCalibration(), calibration)
        assertFalse(calibration.calibrated)
        assertEquals(2, calibrator.evidenceWindowCount)

        val readings = twoWindowRun().apply { appendSteady(pollCount = 1) }
        val (establishedCalibrator, established) = run(readings)
        assertTrue(established.calibrated)
        assertEquals(establishedMicroamps, established)
        assertEquals(3, establishedCalibrator.evidenceWindowCount)
    }

    @Test
    fun steadyMicroampChargeEstablishesDocumentedMicroampsAndStandardSign() {
        val (_, calibration) = run(twoWindowRun().apply { appendSteady(pollCount = 1) })
        assertEquals(CurrentUnitOverride.MICROAMPS, calibration.unit)
        assertEquals(false, calibration.inverted)
        assertEquals(3, calibration.evidenceWindows)
    }

    @Test
    fun dischargeIsEvidenceToo() {
        val readings = start(current = -TRUE_MICROAMPS).apply {
            appendSteady(pollCount = 9, rawMicroamps = -TRUE_MICROAMPS, trueMicroamps = -TRUE_MICROAMPS)
        }
        val (_, calibration) = run(readings)
        assertEquals(CurrentUnitOverride.MICROAMPS, calibration.unit)
        assertEquals(false, calibration.inverted)
    }

    @Test
    fun milliampScaledPropertyEstablishesMilliamps() {
        // 1000x smaller values than the counter implies.
        val readings = start(current = 720L).apply { appendSteady(pollCount = 9, rawMicroamps = 720L) }
        val (_, calibration) = run(readings)
        assertEquals(CurrentUnitOverride.MILLIAMPS, calibration.unit)
        assertEquals(false, calibration.inverted)
        assertTrue(calibration.calibrated)
    }

    @Test
    fun consistentlyOppositeSignEstablishesInvertedPolarity() {
        // The counter charges while the property reports discharge.
        val readings = start(current = -TRUE_MICROAMPS).apply {
            appendSteady(pollCount = 9, rawMicroamps = -TRUE_MICROAMPS)
        }
        val (_, calibration) = run(readings)
        assertEquals(CurrentUnitOverride.MICROAMPS, calibration.unit)
        assertEquals(true, calibration.inverted)
    }

    @Test
    fun invertedMilliampDeviceEstablishesBothCorrectionsTogether() {
        val readings = start(current = -720L).apply { appendSteady(pollCount = 9, rawMicroamps = -720L) }
        val (_, calibration) = run(readings)
        assertEquals(CurrentUnitOverride.MILLIAMPS, calibration.unit)
        assertEquals(true, calibration.inverted)
    }

    @Test
    fun toleranceAcceptsModerateWindowNoise() {
        // Each window's integrated current differs from the counter by 20-25%.
        val readings = start().apply {
            appendSteady(pollCount = 3, rawMicroamps = 576_000L) // 0.80x
            appendSteady(pollCount = 3, rawMicroamps = 864_000L) // 1.20x
            appendSteady(pollCount = 3, rawMicroamps = 684_000L) // 0.95x
        }
        val (_, calibration) = run(readings)
        assertEquals(CurrentUnitOverride.MICROAMPS, calibration.unit)
        assertEquals(false, calibration.inverted)
        assertEquals(3, calibration.evidenceWindows)
    }

    @Test
    fun alternatingWindowsNeverEstablishACalibration() {
        // A B A B A B A, one 15 s window each: four "A" windows and three "B"
        // windows in total, but never three consecutive ones. Retaining earlier
        // votes would have established A at the fifth window; a contradicting window
        // must restart the run instead, so every conflict seeds a run of one.
        val calibrator = CurrentCalibrator()
        var last = CurrentCalibration()
        val readings = start().apply {
            appendSteady(pollCount = 3, rawMicroamps = TRUE_MICROAMPS) // A: uA, standard
            appendSteady(pollCount = 3, rawMicroamps = -TRUE_MICROAMPS) // B: uA, inverted
            appendSteady(pollCount = 3, rawMicroamps = TRUE_MICROAMPS) // A
            appendSteady(pollCount = 3, rawMicroamps = -TRUE_MICROAMPS) // B
            appendSteady(pollCount = 3, rawMicroamps = TRUE_MICROAMPS) // A
            appendSteady(pollCount = 3, rawMicroamps = -TRUE_MICROAMPS) // B
            appendSteady(pollCount = 3, rawMicroamps = TRUE_MICROAMPS) // A
        }
        for (item in readings) last = calibrator.update(item)
        assertEquals(CurrentCalibration(), last)
        assertFalse(last.calibrated)
        assertNull(last.inverted)
        // Only the last window survives: the run restarted at every conflict.
        assertEquals(1, calibrator.evidenceWindowCount)
    }

    @Test
    fun aFreshConsistentRunRecoversAfterAConflict() {
        // A A B, then three consecutive A windows: the conflict is not merged and
        // leaves no residue, so the run that establishes is the consecutive one.
        val calibrator = CurrentCalibrator()
        var last = CurrentCalibration()
        val readings = start().apply {
            appendSteady(pollCount = 3) // A
            appendSteady(pollCount = 3) // A
            appendSteady(pollCount = 3, rawMicroamps = -TRUE_MICROAMPS) // B
            appendSteady(pollCount = 9) // A A A
        }
        for (item in readings) last = calibrator.update(item)
        assertEquals(establishedMicroamps, last)
        assertEquals(3, calibrator.evidenceWindowCount)
    }

    @Test
    fun underReadingGaugeIsNeitherScaleAndNeverCalibrates() {
        // Device evidence: CURRENT_NOW = -315 while the real drain is -42 mA. The
        // ratio is ~0.0075, which is neither microamps nor milliamps.
        val readings = start(current = -315L).apply {
            appendSteady(pollCount = 80, rawMicroamps = -315L, trueMicroamps = -42_000L)
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(CurrentCalibration(), calibration)
        assertNull(calibration.inverted)
        assertEquals(0, calibrator.evidenceWindowCount)
    }

    @Test
    fun flatCounterIsNotEvidenceEvenWhenThePropertyClaimsCurrent() {
        // The gauge does not move: no window can reach the minimum change, and a
        // window wider than the maximum is discarded instead of used.
        val calibrator = CurrentCalibrator()
        calibrator.update(reading(0L, TRUE_MICROAMPS, START_COUNTER))
        var last = CurrentCalibration()
        for (index in 1..80) {
            last = calibrator.update(reading(index * STEP_MILLIS, TRUE_MICROAMPS, START_COUNTER))
        }
        assertEquals(CurrentCalibration(), last)
        assertEquals(0, calibrator.evidenceWindowCount)
    }

    @Test
    fun unsupportedCounterNeverCalibrates() {
        val calibrator = CurrentCalibrator()
        var last = CurrentCalibration()
        for (index in 0..40) {
            last = calibrator.update(reading(index * STEP_MILLIS, TRUE_MICROAMPS, null))
        }
        assertEquals(CurrentCalibration(), last)
        assertEquals(0, calibrator.evidenceWindowCount)
    }

    @Test
    fun missingPropertiesBreakTheOpenWindow() {
        // Two steps into a window, the counter disappears: the partial integral is
        // unusable, so the next window starts from scratch and the two windows that
        // follow the hole are not enough on their own.
        val readings = start().apply {
            appendSteady(pollCount = 2)
            add(reading(15_000L, TRUE_MICROAMPS, null))
            appendSteady(pollCount = 8)
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun gapLongerThanFiveMinutesRestartsTheWindow() {
        val readings = start().apply {
            appendSteady(pollCount = 2)
            // 400 s later: charge moved while nothing was observed.
            add(reading(410_000L, TRUE_MICROAMPS, 5_100_000L))
            appendSteady(pollCount = 8)
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun implausibleCounterStepIsAResetNotEvidence() {
        val readings = start().apply {
            appendSteady(pollCount = 2)
            // -500,000 uAh in one 5 s step implies 360,000 A: a gauge reset.
            add(reading(15_000L, TRUE_MICROAMPS, 5_000_000L - 500_000L))
            appendSteady(pollCount = 8, trueMicroamps = TRUE_MICROAMPS)
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun aCoarseCounterStepAtAFastCadenceDoesNotRestartTheRun() {
        // The bounded startup burst polls once a second, and a coarse gauge can
        // release several mAh in a single poll: 3000 uAh in one second implies 10.8 A,
        // which judged per step looked like a reset and dropped the open window *and*
        // the run of agreeing windows behind it, so calibration could never finish.
        val calibrator = CurrentCalibrator()
        var latest = CurrentCalibration()
        var counter = START_COUNTER
        latest = calibrator.update(reading(0L, TRUE_MICROAMPS, counter))
        for (second in 1L..45L) {
            if (second % 15L == 0L) counter += 3_000L
            latest = calibrator.update(reading(second * 1_000L, TRUE_MICROAMPS, counter))
        }
        assertTrue(latest.calibrated)
        assertEquals(CurrentUnitOverride.MICROAMPS, latest.unit)
        assertEquals(false, latest.inverted)
        assertEquals(3, calibrator.evidenceWindowCount)
    }

    @Test
    fun anImpossibleWindowRateAtAFastCadenceIsStillAReset() {
        val calibrator = CurrentCalibrator()
        var latest = CurrentCalibration()
        var counter = START_COUNTER
        latest = calibrator.update(reading(0L, TRUE_MICROAMPS, counter))
        for (second in 1L..30L) {
            if (second % 15L == 0L) counter += 3_000L
            latest = calibrator.update(reading(second * 1_000L, TRUE_MICROAMPS, counter))
        }
        // Two clean windows are behind the open one.
        assertEquals(2, calibrator.evidenceWindowCount)
        // +1,000,000 uAh inside the open window implies millions of amps: a gauge
        // reset, still rejected although each poll step is only a second long.
        latest = calibrator.update(reading(31_000L, TRUE_MICROAMPS, counter + 1_000_000L))
        assertEquals(0, calibrator.evidenceWindowCount)
        assertFalse(latest.calibrated)
    }

    @Test
    fun directionReversalInsideAWindowIsRejected() {
        val readings = start().apply {
            appendSteady(pollCount = 2) // charging
            // Discharge step: plausible on its own, but the window changed direction.
            add(reading(15_000L, TRUE_MICROAMPS, 5_002_000L - 1_000L))
            appendSteady(pollCount = 8)
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun conflictingWindowsRestartTheRunInsteadOfBeingMerged() {
        // Two microamp/standard windows, then a device that is consistently inverted.
        // The two runs cannot be reconciled, so the earlier windows are dropped: the
        // fresh run is what counts, and it has to be consecutive on its own.
        val calibrator = CurrentCalibrator()
        var last = CurrentCalibration()
        val readings = start().apply {
            appendSteady(pollCount = 6) // two microamp/standard windows
            appendSteady(pollCount = 30, rawMicroamps = -TRUE_MICROAMPS) // inverted
        }
        for (item in readings) last = calibrator.update(item)
        assertEquals(CurrentUnitOverride.MICROAMPS, last.unit)
        assertEquals(true, last.inverted)
        assertEquals(3, last.evidenceWindows)
        assertEquals(3, calibrator.evidenceWindowCount)
    }

    @Test
    fun conflictingWindowIsNotMergedIntoAPendingRun() {
        // A A B A A: four compatible windows, but never three in a row, because the
        // B window restarts the run. Retaining votes across the conflict would
        // establish A here.
        val calibrator = CurrentCalibrator()
        var last = CurrentCalibration()
        val readings = start().apply {
            appendSteady(pollCount = 3) // A
            appendSteady(pollCount = 3) // A
            appendSteady(pollCount = 3, rawMicroamps = -TRUE_MICROAMPS) // B
            appendSteady(pollCount = 3) // A
            appendSteady(pollCount = 3) // A
        }
        for (item in readings) last = calibrator.update(item)
        assertEquals(CurrentCalibration(), last)
        assertEquals(2, calibrator.evidenceWindowCount)
    }

    @Test
    fun unusableWindowBreaksTheRunAroundIt() {
        // Two good windows, one window whose ratio (3.6x) is in neither band, then two
        // more good ones. The unusable window is not compatible evidence, so the
        // four good windows never become three consecutive ones.
        val readings = start().apply {
            appendSteady(pollCount = 6) // two good windows
            appendSteady(pollCount = 3, rawMicroamps = 3_000_000L) // 3.6x: no scale
            appendSteady(pollCount = 8) // two good windows
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun gapBetweenClosedWindowsBreaksTheRun() {
        // Two closed windows, a 400 s hole, then two more: observation was not
        // continuous across the hole, so the earlier windows are not consecutive
        // with the later ones and there are only two of either.
        val readings = start().apply {
            appendSteady(pollCount = 6) // two windows
            add(reading(410_000L, TRUE_MICROAMPS, 5_100_000L))
            appendSteady(pollCount = 8) // two more
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun unsupportedCounterBetweenClosedWindowsBreaksTheRun() {
        val readings = start().apply {
            appendSteady(pollCount = 6) // two windows
            add(reading(35_000L, TRUE_MICROAMPS, null))
            // appendSteady re-anchors the counter at START_COUNTER because the last
            // poll had none; only the deltas inside a window matter.
            appendSteady(pollCount = 8) // two more
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun propertyBeyondTheProviderPlausibilityCapCannotParticipate() {
        // The decoder strips a raw property above 100 A before sampling; a pure
        // calibrator driven directly must be equally strict. The value must not be
        // integrated into the open window and must break the run, so the four
        // windows either side of it never become three consecutive ones.
        val readings = start().apply {
            appendSteady(pollCount = 8) // two windows
            add(
                reading(
                    last().elapsedRealtimeMillis + STEP_MILLIS,
                    BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS + 1L,
                    (last().chargeCounterMicroampHours ?: START_COUNTER) + 1_000L,
                ),
            )
            appendSteady(pollCount = 9) // two more windows
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(CurrentCalibration(), calibration)
        assertEquals(2, calibrator.evidenceWindowCount)
    }

    @Test
    fun establishedCalibrationSurvivesContradictingWindows() {
        // Once established, even a long run of contradicting windows cannot unseat
        // it or add evidence: the value stays stable until reset.
        val (calibrator, calibration) = run(twoWindowRun().apply { appendSteady(pollCount = 1) })
        assertEquals(establishedMicroamps, calibration)
        var elapsed = 50_000L
        var counter = 5_004_000L
        repeat(30) {
            elapsed += STEP_MILLIS
            counter += 1_000L
            assertEquals(
                establishedMicroamps,
                calibrator.update(reading(elapsed, -TRUE_MICROAMPS, counter)),
            )
        }
        assertEquals(
            establishedMicroamps,
            calibrator.update(reading(elapsed + STEP_MILLIS, null, null)),
        )
        assertEquals(3, calibrator.evidenceWindowCount)
    }

    @Test
    fun establishedCalibrationIsStableUntilReset() {
        val (calibrator, calibration) = run(twoWindowRun().apply { appendSteady(pollCount = 1) })
        assertEquals(establishedMicroamps, calibration)
        // Contradictory, missing and implausible readings leave it untouched.
        assertEquals(establishedMicroamps, calibrator.update(reading(50_000L, -720L, 5_003_000L)))
        assertEquals(establishedMicroamps, calibrator.update(reading(55_000L, null, null)))
        assertEquals(establishedMicroamps, calibrator.update(reading(60_000L, 0L, 5_004_000L)))
        assertEquals(establishedMicroamps, calibrator.update(reading(65_000L, 720L, 5_005_000L)))
        assertEquals(3, calibrator.evidenceWindowCount)

        calibrator.reset()
        assertEquals(CurrentCalibration(), calibrator.update(reading(70_000L, 720L, 5_006_000L)))
        assertEquals(0, calibrator.evidenceWindowCount)
    }

    @Test
    fun resetAdoptsAnAlreadyEstablishedCalibration() {
        val calibrator = CurrentCalibrator()
        calibrator.reset(establishedMicroamps)
        // Stable immediately: no evidence run is needed.
        assertEquals(establishedMicroamps, calibrator.update(reading(1_000L, 720L, 5_000_000L)))
        assertEquals(establishedMicroamps, calibrator.update(reading(6_000L, null, null)))
    }

    @Test
    fun resetDropsAPendingRunSoEvidenceCannotCarryOver() {
        val calibrator = CurrentCalibrator()
        var last = CurrentCalibration()
        for (item in twoWindowRun()) last = calibrator.update(item)
        assertEquals(2, calibrator.evidenceWindowCount)
        calibrator.reset()
        assertEquals(0, calibrator.evidenceWindowCount)
        // A fresh two-window run after the reset still is not enough, which proves
        // the two windows before the reset were not carried over.
        for (item in start().apply { appendSteady(pollCount = 8) }) last = calibrator.update(item)
        assertEquals(CurrentCalibration(), last)
        assertEquals(2, calibrator.evidenceWindowCount)
    }

    @Test
    fun discardingPendingEvidenceDropsTheRunWithoutDiscardingTheCalibration() {
        val calibrator = CurrentCalibrator()
        for (item in twoWindowRun()) calibrator.update(item)
        assertEquals(2, calibrator.evidenceWindowCount)

        // A poll that could not be read: the windows before it and any windows after
        // it must never become one run of three.
        calibrator.discardPendingEvidence()
        assertEquals(0, calibrator.evidenceWindowCount)
        var latest = CurrentCalibration()
        for (item in start().apply { appendSteady(pollCount = 8) }) latest = calibrator.update(item)
        assertEquals(CurrentCalibration(), latest)
        assertEquals(2, calibrator.evidenceWindowCount)
    }

    @Test
    fun discardingPendingEvidenceKeepsAnEstablishedValue() {
        // Three windows establish µA/non-inverted during this session; its durable
        // write may still be pending, so dropping pending evidence must not lose it.
        val (calibrator, calibration) = run(twoWindowRun().apply { appendSteady(pollCount = 1) })
        assertEquals(establishedMicroamps, calibration)
        calibrator.discardPendingEvidence()
        assertEquals(
            establishedMicroamps,
            calibrator.update(reading(60_000L, 720L, 5_004_000L)),
        )
        assertEquals(0, calibrator.evidenceWindowCount)
    }

    @Test
    fun nonPositiveElapsedTimeRestartsTheWindow() {
        val readings = start().apply {
            appendSteady(pollCount = 2)
            // Same instant again (clock did not advance) is not a measurement.
            add(reading(10_000L, TRUE_MICROAMPS, 5_002_000L))
            appendSteady(pollCount = 8)
        }
        val (calibrator, calibration) = run(readings)
        assertEquals(2, calibrator.evidenceWindowCount)
        assertEquals(CurrentCalibration(), calibration)
    }

    @Test
    fun pluggedStatusIsNotTreatedAsChargingEvidence() {
        // Plugged in, but the counter still says net discharge: the sign must come
        // from the counter, not from the charger being connected.
        val readings = start(current = -TRUE_MICROAMPS).apply {
            appendSteady(pollCount = 9, rawMicroamps = -TRUE_MICROAMPS, trueMicroamps = -TRUE_MICROAMPS)
        }.map { it.copy(plugged = 2, status = BatteryTelemetryDecoder.STATUS_CHARGING) }
        val (_, calibration) = run(readings)
        assertEquals(CurrentUnitOverride.MICROAMPS, calibration.unit)
        assertEquals(false, calibration.inverted)
    }

    private companion object {
        const val STEP_MILLIS = 5_000L

        /** 720 mA: one 5 s step moves exactly 1000 uAh. */
        const val TRUE_MICROAMPS = 720_000L

        const val START_COUNTER = 5_000_000L

        /** uA * ms -> uAh. */
        const val MICROAMP_HOURS_PER_MICROAMP_MILLIS = 3_600_000L
    }
}
