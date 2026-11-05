package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.monitoring.FastCurrentInterpreter.Result
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for [FastCurrentInterpreter].
 *
 * Polls are built [STEP_MILLIS] apart from [FIRST_ELAPSED], so five of them span
 * 8 s — comfortably inside the 1 s minimum separation and the 10 s maximum gap.
 * Every assertion about scale or sign is made against the *tentative*
 * [CurrentCalibration] whose `evidenceWindows` is always 0.
 */
class FastCurrentInterpreterTest {

    private fun poll(
        elapsed: Long,
        current: Long?,
        plugged: Int? = null,
        status: Int? = null,
        counter: Long? = null,
    ) = RawBatteryReading(
        timestampMillis = elapsed,
        elapsedRealtimeMillis = elapsed,
        currentMicroamps = current,
        chargeCounterMicroampHours = counter,
        plugged = plugged,
        status = status,
    )

    private fun window(
        values: List<Long?>,
        stepMillis: Long = STEP_MILLIS,
        plugged: Int? = null,
        status: Int? = null,
        counters: (Int) -> Long? = { null },
    ): List<RawBatteryReading> = values.mapIndexed { index, value ->
        poll(
            elapsed = FIRST_ELAPSED + index * stepMillis,
            current = value,
            plugged = plugged,
            status = status,
            counter = counters(index),
        )
    }

    private fun steady(
        value: Long?,
        count: Int = FastCurrentInterpreter.WINDOW_POLLS,
        stepMillis: Long = STEP_MILLIS,
        plugged: Int? = null,
        status: Int? = null,
        counters: (Int) -> Long? = { null },
    ): List<RawBatteryReading> = window(List(count) { value }, stepMillis, plugged, status, counters)

    private fun feed(
        interpreter: FastCurrentInterpreter,
        readings: List<RawBatteryReading>,
        manualUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        manualPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
    ): Result {
        var latest = interpreter.result
        for (reading in readings) latest = interpreter.update(reading, manualUnit, manualPolarity)
        return latest
    }

    // ---- window completion -------------------------------------------------

    @Test
    fun windowIsNotCompleteBeforeTheFifthPoll() {
        val interpreter = FastCurrentInterpreter()
        val readings = steady(178L)
        for (index in 0 until FastCurrentInterpreter.WINDOW_POLLS - 1) {
            val result = interpreter.update(readings[index])
            assertEquals(index + 1, result.pollCount)
            assertFalse("not complete after ${index + 1} polls", result.complete)
            assertNull(result.interpretation)
        }
        val fifth = interpreter.update(readings.last())
        assertEquals(5, fifth.pollCount)
        assertTrue(fifth.complete)
        assertEquals(milliampsStandard, fifth.interpretation)
    }

    @Test
    fun pollCountSaturatesAndTheWindowSlides() {
        val interpreter = FastCurrentInterpreter()
        var latest = feed(interpreter, steady(178L))
        assertEquals(5, latest.pollCount)
        for (index in 5..8) {
            latest = interpreter.update(poll(FIRST_ELAPSED + index * STEP_MILLIS, 178L))
            assertEquals(5, latest.pollCount)
            assertTrue(latest.complete)
        }
        assertEquals(milliampsStandard, latest.interpretation)
    }

    @Test
    fun oneAndFiveSecondCadencesCompleteTheWindow() {
        val oneSecond = feed(FastCurrentInterpreter(), steady(178L, stepMillis = 1_000L))
        assertTrue(oneSecond.complete)
        assertEquals(milliampsStandard, oneSecond.interpretation)

        val fiveSeconds = feed(FastCurrentInterpreter(), steady(178L, stepMillis = 5_000L))
        assertTrue(fiveSeconds.complete)
        assertEquals(milliampsStandard, fiveSeconds.interpretation)
    }

    @Test
    fun subSecondPollsAreNotIndependentEvidence() {
        // Every poll is 500 ms after the previous one, so only three of the five are
        // far enough apart to count: the window stays incomplete and learns nothing.
        val result = feed(FastCurrentInterpreter(), steady(178L, stepMillis = 500L))
        assertEquals(3, result.pollCount)
        assertFalse(result.complete)
        assertNull(result.interpretation)
    }

    @Test
    fun duplicateTimestampsAndTooClosePollsAreIgnored() {
        val interpreter = FastCurrentInterpreter()
        val partial = feed(
            interpreter,
            listOf(
                poll(FIRST_ELAPSED, 178L),
                poll(FIRST_ELAPSED, 178L),
                poll(FIRST_ELAPSED + 800L, 178L),
                poll(FIRST_ELAPSED + 2_000L, 178L),
                poll(FIRST_ELAPSED + 4_000L, 178L),
                poll(FIRST_ELAPSED + 6_000L, 178L),
            ),
        )
        assertEquals(4, partial.pollCount)
        assertFalse(partial.complete)

        val completed = interpreter.update(poll(FIRST_ELAPSED + 8_000L, 178L))
        assertEquals(5, completed.pollCount)
        assertTrue(completed.complete)
    }

    @Test
    fun gapLongerThanTheMaximumRestartsTheWindow() {
        val interpreter = FastCurrentInterpreter()
        feed(interpreter, steady(178L, count = 3, stepMillis = 2_000L))
        val restarted = interpreter.update(
            poll(FIRST_ELAPSED + 4_000L + FastCurrentInterpreter.MAX_GAP_MILLIS + 1L, 178L),
        )
        assertEquals(1, restarted.pollCount)
        assertFalse(restarted.complete)
        assertNull(restarted.interpretation)
    }

    @Test
    fun gapExactlyAtTheMaximumIsStillOneObservation() {
        val result = feed(
            FastCurrentInterpreter(),
            steady(178L, stepMillis = FastCurrentInterpreter.MAX_GAP_MILLIS),
        )
        assertTrue(result.complete)
        assertEquals(milliampsStandard, result.interpretation)
    }

    @Test
    fun negativeMonotonicTimeIsRejectedWithoutWallClockFallback() {
        val interpreter = FastCurrentInterpreter()
        val bogus = RawBatteryReading(
            timestampMillis = 1_700_000_000_000L,
            elapsedRealtimeMillis = -1L,
            currentMicroamps = 178L,
        )
        val result = interpreter.update(bogus)
        assertEquals(0, result.pollCount)
        assertFalse(result.complete)
        assertNull(result.interpretation)
    }

    @Test
    fun bootTimeZeroAndSmallProviderTimestampJitterAreValid() {
        val interpreter = FastCurrentInterpreter()
        val times = listOf(0L, 999L, 2_001L, 2_998L, 3_999L)
        for ((index, elapsed) in times.withIndex()) {
            val result = interpreter.update(poll(elapsed, -178L))
            assertEquals(index + 1, result.pollCount)
            assertEquals(index == 4, result.complete)
        }
        assertEquals(milliampsStandard, interpreter.result.interpretation)
    }

    // ---- scale heuristic ---------------------------------------------------

    @Test
    fun smallMagnitudesReadAsMilliamps() {
        val result = feed(FastCurrentInterpreter(), steady(178L))
        assertTrue(result.complete)
        assertEquals(milliampsStandard, result.interpretation)
    }

    @Test
    fun fastChargeMilliampValuesReadAsMilliamps() {
        // A 5 A fast charge reported in milliamps is 5000, far below the microamp band.
        val result = feed(FastCurrentInterpreter(), steady(5_000L))
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), result.interpretation)
    }

    @Test
    fun largeMagnitudesReadAsMicroamps() {
        val result = feed(FastCurrentInterpreter(), steady(178_000L))
        assertTrue(result.complete)
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), result.interpretation)
    }

    @Test
    fun magnitudesBetweenTheBandsAreAmbiguousButComplete() {
        val interpreter = FastCurrentInterpreter()
        val result = feed(interpreter, steady(15_000L))
        assertTrue("completion must not wait for a conclusion", result.complete)
        assertEquals(5, result.pollCount)
        // Scale stays unknown; a coherent all-positive window with no platform state
        // still yields the documented sign convention.
        assertEquals(CurrentUnitOverride.AUTO, result.interpretation?.unit)
        assertEquals(false, result.interpretation?.inverted)
        assertFalse(result.interpretation!!.calibrated)

        // The window slides, so a later stable run of unambiguous values can infer it.
        val learned = feed(
            interpreter,
            List(5) { index ->
                poll(FIRST_ELAPSED + (5 + index) * STEP_MILLIS, 200_000L)
            },
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), learned.interpretation)
    }

    @Test
    fun mismatchedMagnitudesLeaveOnlyTheSign() {
        val result = feed(
            FastCurrentInterpreter(),
            window(listOf(178L, 178_000L, 178L, 178_000L, 178L)),
        )
        assertEquals(CurrentUnitOverride.AUTO, result.interpretation?.unit)
        assertEquals(false, result.interpretation?.inverted)
        assertFalse(result.interpretation!!.calibrated)
    }

    @Test
    fun zerosAreIgnoredWhenRealValuesArePresent() {
        val result = feed(FastCurrentInterpreter(), window(listOf(0L, 0L, 178L, 0L, 200L)))
        assertEquals(milliampsStandard, result.interpretation)
    }

    @Test
    fun anAllZeroWindowUsesTheInvariantConvention() {
        val interpreter = FastCurrentInterpreter()
        val result = feed(interpreter, steady(0L))
        assertTrue(result.complete)
        // A true zero is the same number in either unit and either sign, so the
        // documented convention is correct by construction.
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), result.interpretation)

        val slid = feed(interpreter, List(2) { index -> poll(FIRST_ELAPSED + (5 + index) * STEP_MILLIS, 0L) })
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), slid.interpretation)
    }

    @Test
    fun anAllZeroWindowIgnoresCounterMovement() {
        val result = feed(
            FastCurrentInterpreter(),
            steady(0L, plugged = PLUGGED, status = STATUS_CHARGING) { index -> START_COUNTER + 500L * index },
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), result.interpretation)
    }

    @Test
    fun aZeroOnlyEstimateIsDiscardedByTheFirstRealValue() {
        val interpreter = FastCurrentInterpreter()
        val zeros = feed(interpreter, steady(0L))
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), zeros.interpretation)

        // Next cadence poll carries a real value: the microamp guess from the zeros
        // must not be applied to it, so the estimate is dropped and the window restarts.
        val rejected = interpreter.update(poll(FIRST_ELAPSED + 5 * STEP_MILLIS, 178L))
        assertNull(rejected.interpretation)
        assertEquals(1, rejected.pollCount)
        assertFalse(rejected.complete)

        val relearned = feed(
            interpreter,
            List(4) { index -> poll(FIRST_ELAPSED + (6 + index) * STEP_MILLIS, 178L) },
        )
        assertTrue(relearned.complete)
        assertEquals(milliampsStandard, relearned.interpretation)
    }

    // ---- polarity ----------------------------------------------------------

    @Test
    fun unpluggedPositiveValuesAreInverted() {
        val result = feed(
            FastCurrentInterpreter(),
            steady(178L, plugged = BatteryTelemetryDecoder.PLUGGED_NONE),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), result.interpretation)
    }

    @Test
    fun durableCounterDirectionBeatsAChargingStatus() {
        val readings = steady(-1_500L, plugged = PLUGGED, status = STATUS_CHARGING) { index ->
            START_COUNTER - 833L * index
        }
        val withCounter = feed(FastCurrentInterpreter(), readings)
        // The counter fell while the platform claimed charging: net discharge, and the
        // raw negative values mean this is the documented non-inverted convention.
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), withCounter.interpretation)

        val statusOnly = feed(
            FastCurrentInterpreter(),
            steady(-1_500L, plugged = PLUGGED, status = STATUS_CHARGING),
        )
        // Without counter evidence the charging status would have implied inverted.
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), statusOnly.interpretation)
    }

    @Test
    fun counterDirectionBeatsAnUnpluggedStatus() {
        val withCounter = feed(
            FastCurrentInterpreter(),
            steady(178L, plugged = BatteryTelemetryDecoder.PLUGGED_NONE) { index ->
                START_COUNTER + 1_000L * index
            },
        )
        // A rising counter means charging, so positive values are the standard
        // convention even though the platform says the phone is unplugged.
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), withCounter.interpretation)

        val statusOnly = feed(
            FastCurrentInterpreter(),
            steady(178L, plugged = BatteryTelemetryDecoder.PLUGGED_NONE),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), statusOnly.interpretation)
    }

    @Test
    fun incoherentCounterMovementFallsBackToThePlatformState() {
        // Direction reverses inside the window, so the counter is not one movement.
        val counters = listOf(1_000L, 2_000L, 1_500L, 2_500L, 2_000L)
        val result = feed(
            FastCurrentInterpreter(),
            window(
                List(5) { 178L },
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                counters = { index -> counters[index] },
            ),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), result.interpretation)
    }

    @Test
    fun implausibleCounterJumpIsNotEvidence() {
        // ~720 000 A implied by each step: a gauge reset, never a direction.
        val result = feed(
            FastCurrentInterpreter(),
            window(
                List(5) { 178L },
                plugged = BatteryTelemetryDecoder.PLUGGED_NONE,
                counters = { index -> 1_000_000_000L * index },
            ),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), result.interpretation)
    }

    @Test
    fun ambiguousPlatformStateUsesTheDocumentedConvention() {
        // Plugged but not charging, and all five values positive: this must not be read
        // as always-positive inverted firmware.
        val notCharging = feed(
            FastCurrentInterpreter(),
            steady(500L, plugged = PLUGGED, status = STATUS_NOT_CHARGING),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), notCharging.interpretation)

        val full = feed(
            FastCurrentInterpreter(),
            steady(500L, plugged = PLUGGED, status = STATUS_FULL),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), full.interpretation)

        val pluggedOnly = feed(FastCurrentInterpreter(), steady(500L, plugged = PLUGGED))
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), pluggedOnly.interpretation)

        // A status that does state a direction is still usable: discharge expects the
        // raw value to be negative.
        val discharging = feed(
            FastCurrentInterpreter(),
            steady(500L, plugged = PLUGGED, status = STATUS_DISCHARGING),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), discharging.interpretation)
    }

    @Test
    fun mixedSignsKeepTheDocumentedConventionWithoutDirectionEvidence() {
        val mixed = listOf(178L, -178L, 178L, -178L, 178L)
        val ambiguous = feed(FastCurrentInterpreter(), window(mixed))
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), ambiguous.interpretation)

        // With a platform direction to compare against, mixed signs cannot be one
        // convention, so the sign stays unknown while the scale is still inferred.
        val unplugged = feed(
            FastCurrentInterpreter(),
            window(mixed, plugged = BatteryTelemetryDecoder.PLUGGED_NONE),
        )
        assertEquals(CurrentUnitOverride.MILLIAMPS, unplugged.interpretation?.unit)
        assertNull(unplugged.interpretation?.inverted)
    }

    @Test
    fun learnedInterpretationSurvivesFluctuationsAndContradiction() {
        val interpreter = FastCurrentInterpreter()
        assertEquals(milliampsStandard, feed(interpreter, steady(178L)).interpretation)

        // Fluctuating milliamp values keep the same conclusion...
        val fluctuating = feed(
            interpreter,
            window(listOf(1_200L, 1_600L, 2_000L, 2_400L, 2_800L)),
        )
        assertEquals(milliampsStandard, fluctuating.interpretation)
        assertTrue(fluctuating.complete)

        // ...and so does a later window that would have suggested microamps, because a
        // learned component is stable until reset or the counter calibrator.
        val contradicting = feed(
            interpreter,
            List(5) { index -> poll(FIRST_ELAPSED + (10 + index) * STEP_MILLIS, 178_000L) },
        )
        assertEquals(milliampsStandard, contradicting.interpretation)
    }

    @Test
    fun learnedPolarityIsNotReDerivedWhenTheDirectionFlips() {
        val interpreter = FastCurrentInterpreter()
        val discharging = feed(
            interpreter,
            steady(-1_500L, plugged = BatteryTelemetryDecoder.PLUGGED_NONE),
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), discharging.interpretation)

        // Positive values while unplugged would be inferred as inverted on their own.
        val later = feed(
            interpreter,
            List(5) { index ->
                poll(FIRST_ELAPSED + (5 + index) * STEP_MILLIS, 1_500L, plugged = BatteryTelemetryDecoder.PLUGGED_NONE)
            },
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, false, 0), later.interpretation)
    }

    // ---- manual overrides and settings changes -----------------------------

    @Test
    fun manualSettingsApplyWithoutAnyPolls() {
        val interpreter = FastCurrentInterpreter()
        val result = interpreter.update(
            poll(FIRST_ELAPSED, 178L),
            manualUnit = CurrentUnitOverride.MILLIAMPS,
            manualPolarity = CurrentPolarityOverride.INVERTED,
        )
        assertEquals(CurrentCalibration(CurrentUnitOverride.MILLIAMPS, true, 0), result.interpretation)
        assertEquals(1, result.pollCount)
        assertFalse(result.complete)
    }

    @Test
    fun manualUnitOverridesTheMagnitudeHeuristic() {
        val result = feed(
            FastCurrentInterpreter(),
            steady(178_000L),
            manualUnit = CurrentUnitOverride.MILLIAMPS,
        )
        assertEquals(CurrentUnitOverride.MILLIAMPS, result.interpretation?.unit)
        assertEquals(false, result.interpretation?.inverted)
        assertTrue(result.complete)
    }

    @Test
    fun manualPolarityOverridesInference() {
        val result = feed(
            FastCurrentInterpreter(),
            steady(-1_500L, plugged = BatteryTelemetryDecoder.PLUGGED_NONE),
            manualPolarity = CurrentPolarityOverride.INVERTED,
        )
        assertEquals(CurrentUnitOverride.MILLIAMPS, result.interpretation?.unit)
        assertEquals(true, result.interpretation?.inverted)
    }

    @Test
    fun settingsChangeDropsPendingPollsAndTheInference() {
        val interpreter = FastCurrentInterpreter()
        assertEquals(milliampsStandard, feed(interpreter, steady(178L)).interpretation)

        val changed = interpreter.update(
            poll(FIRST_ELAPSED + 5 * STEP_MILLIS, 178L),
            manualUnit = CurrentUnitOverride.MICROAMPS,
        )
        assertEquals(CurrentUnitOverride.MICROAMPS, changed.interpretation?.unit)
        assertNull("the inferred sign must not survive a settings change", changed.interpretation?.inverted)
        assertEquals(1, changed.pollCount)
        assertFalse(changed.complete)
    }

    // ---- failures and resets ----------------------------------------------

    @Test
    fun missingAndImplausiblePropertiesClearTheWindowButKeepTheInference() {
        val failures = listOf<Long?>(
            null,
            BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL,
            Long.MIN_VALUE,
            BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS + 1L,
            -(BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS + 1L),
            Long.MAX_VALUE,
        )
        for (failure in failures) {
            val interpreter = FastCurrentInterpreter()
            assertEquals(milliampsStandard, feed(interpreter, steady(178L)).interpretation)

            val result = interpreter.update(poll(FIRST_ELAPSED + 5 * STEP_MILLIS, failure))
            assertEquals("failure $failure: interpretation", milliampsStandard, result.interpretation)
            assertEquals("failure $failure: polls", 0, result.pollCount)
            assertFalse("failure $failure: complete", result.complete)
        }
    }

    @Test
    fun plausibleBoundaryMagnitudeIsAccepted() {
        val result = feed(
            FastCurrentInterpreter(),
            steady(BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS),
        )
        assertTrue(result.complete)
        assertEquals(CurrentCalibration(CurrentUnitOverride.MICROAMPS, false, 0), result.interpretation)
    }

    @Test
    fun discardPendingEvidenceDropsPartialPollsButKeepsTheInference() {
        val interpreter = FastCurrentInterpreter()
        feed(interpreter, steady(178L, count = 3))
        interpreter.discardPendingEvidence()
        assertEquals(0, interpreter.result.pollCount)
        assertFalse(interpreter.result.complete)
        assertNull(interpreter.result.interpretation)

        assertEquals(milliampsStandard, feed(interpreter, steady(178L)).interpretation)
        interpreter.discardPendingEvidence()
        assertEquals(milliampsStandard, interpreter.result.interpretation)
        assertFalse("a full window cannot bridge an unread poll", interpreter.result.complete)
        assertEquals(0, interpreter.result.pollCount)
    }

    @Test
    fun resetForgetsTheWindowTheInferenceAndTheManualSettings() {
        val interpreter = FastCurrentInterpreter()
        feed(interpreter, steady(178L))
        assertTrue(interpreter.result.complete)

        interpreter.reset()
        assertEquals(Result(), interpreter.result)

        // Nothing is remembered, not even the manual values seen before the reset.
        val afterReset = interpreter.update(poll(FIRST_ELAPSED + 20_000L, 178L))
        assertNull(afterReset.interpretation)
        assertEquals(1, afterReset.pollCount)
        assertFalse(afterReset.complete)
    }

    private companion object {
        const val FIRST_ELAPSED = 5_000L
        const val STEP_MILLIS = 2_000L
        const val START_COUNTER = 5_000_000L
        const val PLUGGED = 1
        const val STATUS_CHARGING = BatteryTelemetryDecoder.STATUS_CHARGING
        const val STATUS_DISCHARGING = BatteryTelemetryDecoder.STATUS_DISCHARGING
        const val STATUS_NOT_CHARGING = BatteryTelemetryDecoder.STATUS_NOT_CHARGING
        const val STATUS_FULL = BatteryTelemetryDecoder.STATUS_FULL

        val milliampsStandard = CurrentCalibration(
            unit = CurrentUnitOverride.MILLIAMPS,
            inverted = false,
            evidenceWindows = 0,
        )
    }
}
