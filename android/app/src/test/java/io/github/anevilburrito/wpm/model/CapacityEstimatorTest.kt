package io.github.anevilburrito.wpm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract: `android/specs/capacity-windows.md`.
 *
 * One monotonic **charging or discharging** window spanning at least 20 absolute
 * percentage points (never 20% of the starting percentage). Percent `0..100` and
 * counters `>= 0` are valid inputs, so empty-battery endpoints are exercised, and a
 * flat or zero counter must never fabricate a capacity.
 *
 * Direction is taken from a trustworthy hint at the anchor (+1 calibrated charging,
 * -1 unplugged or calibrated discharging, 0 otherwise) and otherwise inferred from
 * the first counter/percentage movement. Counter or percentage may individually stay
 * flat, but a contradictory nonzero step or hint re-anchors at the current sample.
 * Missing/invalid values and `gapBefore` clear the window so the next sample anchors.
 */
class CapacityEstimatorTest {

    /**
     * Drives one [CapacityEstimator] with a monotonic clock and explicit counters,
     * so a test states the gauge evidence it wants rather than a poll schedule.
     */
    private class Sim(val estimator: CapacityEstimator = CapacityEstimator()) {
        var elapsedMillis = 0L
        val observations = ArrayList<CapacityMeasurement>()

        fun step(
            percent: Int?,
            counterMicroampHours: Long?,
            stepSeconds: Long = 60,
            gapBefore: Boolean = false,
            plugged: Boolean? = null,
            flow: FlowState = FlowState.UNKNOWN,
            currentCalibrated: Boolean = false,
        ): CapacityMeasurement? {
            elapsedMillis += stepSeconds * 1000
            val measurement = estimator.add(
                BatterySample(
                    timestampMillis = elapsedMillis,
                    elapsedRealtimeMillis = elapsedMillis,
                    percentage = percent,
                    chargeCounterMicroampHours = counterMicroampHours,
                    gapBefore = gapBefore,
                    plugged = plugged,
                    flow = flow,
                    currentCalibrated = currentCalibrated,
                ),
            )
            if (measurement != null) observations.add(measurement)
            return measurement
        }

        fun progress(): Int = estimator.progressPercent()

        /**
         * Monotonic sweep, one reported point per sample, using a 45 mAh-per-point
         * gauge (a 4,500 mAh battery) unless told otherwise. 60-second samples keep
         * the implied 2.7 A rate comfortably inside the 10 A policy bound; `to < from`
         * produces the matching descending counter.
         */
        fun sweep(
            from: Int,
            to: Int,
            stepSeconds: Long = 60,
            milliampHoursPerPoint: Long = 45L,
            plugged: Boolean? = null,
            flow: FlowState = FlowState.UNKNOWN,
            currentCalibrated: Boolean = false,
        ) {
            val direction = if (to >= from) 1 else -1
            var percent = from
            while (true) {
                step(
                    percent,
                    percent.toLong() * milliampHoursPerPoint * 1000L,
                    stepSeconds,
                    plugged = plugged,
                    flow = flow,
                    currentCalibrated = currentCalibrated,
                )
                if (percent == to) return
                percent += direction
            }
        }

        fun charge(
            from: Int,
            to: Int,
            stepSeconds: Long = 60,
            milliampHoursPerPoint: Long = 45L,
            plugged: Boolean? = null,
            flow: FlowState = FlowState.UNKNOWN,
            currentCalibrated: Boolean = false,
        ) = sweep(from, to, stepSeconds, milliampHoursPerPoint, plugged, flow, currentCalibrated)

        fun discharge(
            from: Int,
            to: Int,
            stepSeconds: Long = 60,
            milliampHoursPerPoint: Long = 45L,
            plugged: Boolean? = null,
            flow: FlowState = FlowState.UNKNOWN,
            currentCalibrated: Boolean = false,
        ) = sweep(from, to, stepSeconds, milliampHoursPerPoint, plugged, flow, currentCalibrated)
    }

    // ------------------------------------------------------------------ charging

    @Test fun aTwentyPointChargingWindowReportsTheObservedCapacity() {
        val sim = Sim()
        // 40% -> 60% moves the counter by 20 * 45 mAh = 900 mAh, so the battery is
        // 900 / 0.20 = 4,500 mAh.
        sim.charge(from = 40, to = 60)
        assertEquals(1, sim.observations.size)
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        // Weight is the absolute point span that produced it.
        assertEquals(20.0, measurement.weight, 1e-9)
        assertEquals(0, sim.progress())
    }

    @Test fun aNineteenPointChargingWindowPublishesProgressOnly() {
        val sim = Sim()
        sim.charge(from = 40, to = 59)
        assertTrue(sim.observations.isEmpty())
        assertEquals(19, sim.progress())
    }

    @Test fun anOvershootingChargingWindowUsesItsActualSpan() {
        val sim = Sim()
        assertNull(sim.step(40, percentTo(40)))
        for (percent in 41..59) {
            assertNull(sim.step(percent, percentTo(percent)))
        }
        // One sample jumps from 19 to 21 points: 945 mAh / 0.21 = 4,500 mAh, and the
        // weight is the real span rather than a truncated 20.
        sim.step(61, percentTo(61))
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        assertEquals(21.0, measurement.weight, 1e-9)
    }

    // ---------------------------------------------------------------- discharging

    @Test fun aTwentyPointDischargeWindowReportsTheObservedCapacity() {
        val sim = Sim()
        // Unplugged, 80% -> 60%: the counter falls 900 mAh over 20 points of gauge.
        sim.discharge(from = 80, to = 60, plugged = false)
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        assertEquals(20.0, measurement.weight, 1e-9)
        assertEquals(0, sim.progress())
    }

    @Test fun aNineteenPointDischargeWindowPublishesProgressOnly() {
        val sim = Sim()
        sim.discharge(from = 80, to = 61, plugged = false)
        assertTrue(sim.observations.isEmpty())
        assertEquals(19, sim.progress())
    }

    @Test fun anOvershootingDischargeWindowUsesItsActualSpan() {
        val sim = Sim()
        assertNull(sim.step(80, percentTo(80), plugged = false))
        for (percent in 79 downTo 61) {
            assertNull(sim.step(percent, percentTo(percent), plugged = false))
        }
        // 19 -> 21 points in one descending sample: the real span is the weight.
        sim.step(59, percentTo(59), plugged = false)
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        assertEquals(21.0, measurement.weight, 1e-9)
    }

    @Test fun aPluggedPhoneMayDischarge() {
        val sim = Sim()
        // Charger connected but the battery drains, with an established current sign.
        sim.discharge(
            from = 80,
            to = 60,
            plugged = true,
            flow = FlowState.DISCHARGING,
            currentCalibrated = true,
        )
        assertEquals(1, sim.observations.size)
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    @Test fun aDischargeWithUnknownPlugAndFlowIsInferredFromTheCounter() {
        // Missing plug state and an uncalibrated "discharging" word are neutral: the
        // falling counter/percentage pair is enough to establish the direction.
        val unknown = Sim()
        unknown.discharge(from = 80, to = 60)
        assertEquals(1, unknown.observations.size)

        val uncalibrated = Sim()
        uncalibrated.discharge(
            from = 80,
            to = 60,
            flow = FlowState.DISCHARGING,
            currentCalibrated = false,
        )
        assertEquals(1, uncalibrated.observations.size)
    }

    // ------------------------------------------------------------ trusted hints

    @Test fun aChargingHintNeverApprovesADischargingMovement() {
        val sim = Sim()
        sim.step(
            80,
            percentTo(80),
            plugged = true,
            flow = FlowState.CHARGING,
            currentCalibrated = true,
        )
        // Trusted charging, but the gauge only falls: every sample is contradictory.
        for (percent in 79 downTo 61) {
            assertNull(
                sim.step(
                    percent,
                    percentTo(percent),
                    plugged = true,
                    flow = FlowState.CHARGING,
                    currentCalibrated = true,
                ),
            )
        }
        assertTrue(sim.observations.isEmpty())
        assertEquals(0, sim.progress())
        // Once the movement agrees with the hint, a normal window publishes again.
        for (percent in 61..81) {
            sim.step(
                percent,
                percentTo(percent),
                plugged = true,
                flow = FlowState.CHARGING,
                currentCalibrated = true,
            )
        }
        assertEquals(1, sim.observations.size)
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    @Test fun aDischargeHintNeverApprovesARisingWindow() {
        val sim = Sim()
        // Trusted discharge while counter and percentage both rise: nothing may open.
        for (percent in 40..60) {
            sim.step(
                percent,
                percentTo(percent),
                plugged = true,
                flow = FlowState.DISCHARGING,
                currentCalibrated = true,
            )
        }
        assertTrue(sim.observations.isEmpty())
        assertEquals(0, sim.progress())
    }

    @Test fun anUnpluggedRisingGaugeAdjustmentCannotCountAsCharging() {
        val sim = Sim()
        sim.charge(
            from = 40,
            to = 50,
            plugged = true,
            flow = FlowState.CHARGING,
            currentCalibrated = true,
        )
        assertEquals(10, sim.progress())
        // Still rising counter and percentage, but the battery is on its own: this is
        // gauge movement, not charge added, and the trusted hint forbids charging.
        assertNull(sim.step(51, percentTo(51), plugged = false))
        assertEquals(0, sim.progress())
        // No later unplugged reading may feed a charging window either.
        sim.charge(from = 52, to = 80, plugged = false)
        assertTrue(sim.observations.isEmpty())
        assertEquals(0, sim.progress())
    }

    @Test fun anUnpluggedEndpointCannotCompleteAnObservation() {
        val sim = Sim()
        sim.charge(from = 40, to = 59)
        assertTrue(sim.observations.isEmpty())
        // The sample that would have completed the 20-point swing reports the plug
        // was pulled: no observation, and the window does not linger.
        assertNull(sim.step(60, percentTo(60), plugged = false))
        assertTrue(sim.observations.isEmpty())
        assertEquals(0, sim.progress())
    }

    @Test fun aTrustedHintChangeReAnchorsInsteadOfBridgingLegs() {
        val sim = Sim()
        sim.charge(
            from = 40,
            to = 50,
            plugged = true,
            flow = FlowState.CHARGING,
            currentCalibrated = true,
        )
        assertEquals(10, sim.progress())
        // The trusted flow flips to discharging while the gauge still points up: the
        // charging leg must end here instead of absorbing the new one.
        assertNull(
            sim.step(
                51,
                percentTo(51),
                plugged = true,
                flow = FlowState.DISCHARGING,
                currentCalibrated = true,
            ),
        )
        assertEquals(0, sim.progress())
        // A genuine discharge then measures on its own.
        sim.discharge(
            from = 50,
            to = 30,
            plugged = true,
            flow = FlowState.DISCHARGING,
            currentCalibrated = true,
        )
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        assertEquals(20.0, measurement.weight, 1e-9)
    }

    @Test fun anUncalibratedDischargeWordDoesNotVetoAPluggedWindow() {
        // CURRENT_NOW may be mis-signed before calibration; its "discharging" word
        // must not throw away a valid plugged counter window.
        val sim = Sim()
        sim.charge(
            from = 40,
            to = 60,
            plugged = true,
            flow = FlowState.DISCHARGING,
            currentCalibrated = false,
        )
        assertEquals(1, sim.observations.size)
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    @Test fun aPluggedWindowDoesNotNeedACalibratedInstantCurrent() {
        // The window compares the counter with the gauge; CURRENT_NOW plays no part,
        // so an uncalibrated (or missing) instant reading must not veto it.
        val sim = Sim()
        sim.charge(
            from = 40,
            to = 60,
            plugged = true,
            flow = FlowState.CHARGING,
            currentCalibrated = false,
        )
        assertEquals(1, sim.observations.size)
    }

    @Test fun anUnknownPlugStateStaysNeutral() {
        // Old recordings (and devices without EXTRA_PLUGGED) have no plug state; a
        // missing property must not be read as "unplugged".
        val sim = Sim()
        sim.charge(from = 40, to = 60)
        assertEquals(1, sim.observations.size)
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    // ------------------------------------------- direction changes and flat steps

    @Test fun aFlatCounterStepDoesNotBreakDirectionInference() {
        val sim = Sim()
        assertNull(sim.step(40, percentTo(40)))
        // Percentage moves while the counter does not: the direction is still clear.
        assertNull(sim.step(41, percentTo(40)))
        assertEquals(1, sim.progress())
        for (percent in 42..60) {
            sim.step(percent, percentTo(percent))
        }
        // 1,800,000 -> 2,700,000 uAh over 20 points is still a 4,500 mAh battery.
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
    }

    @Test fun aFlatPercentageStepDoesNotBreakDirectionInference() {
        val sim = Sim()
        assertNull(sim.step(40, percentTo(40)))
        // Counter moves while the percentage is flat: direction inferred from charge.
        assertNull(sim.step(40, percentTo(41)))
        for (percent in 41..60) {
            sim.step(percent, percentTo(percent))
        }
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
    }

    @Test fun aFlatPatternWithoutSpanNeverFabricatesCapacity() {
        val sim = Sim()
        assertNull(sim.step(40, percentTo(40)))
        // The counter climbs but the gauge never moves: zero span, zero observations.
        var counter = percentTo(40)
        for (i in 1..40) {
            counter += 45_000L
            sim.step(40, counter)
        }
        assertTrue(sim.observations.isEmpty())
        assertEquals(0, sim.progress())
    }

    @Test fun aDirectionChangeNeverBridgesTheChargingAndDischargingLegs() {
        val sim = Sim()
        sim.charge(from = 40, to = 50)
        assertEquals(10, sim.progress())
        // The gauge turns around: this starts a new leg, not a longer charging window.
        assertNull(sim.step(49, percentTo(49)))
        assertEquals(0, sim.progress())
        sim.discharge(from = 48, to = 30)
        assertTrue(sim.observations.isEmpty())
        assertEquals(19, sim.progress())
        val measurement = sim.step(29, percentTo(29))!!
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        assertEquals(20.0, measurement.weight, 1e-9)
    }

    @Test fun aCounterReversalRestartsTheWindow() {
        val sim = Sim()
        sim.charge(from = 40, to = 50)
        // Percentage rises while the counter falls: a pin/gauge reset, not charging.
        assertNull(sim.step(51, percentTo(49)))
        assertEquals(0, sim.progress())
        // Re-anchored at the reversal, so a fresh 20-point span still measures.
        var counter = percentTo(49)
        for (percent in 52..71) {
            counter += 45_000L
            sim.step(percent, counter)
        }
        assertEquals(1, sim.observations.size)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
    }

    @Test fun aPercentageReversalRestartsTheWindow() {
        val sim = Sim()
        sim.charge(from = 40, to = 50)
        // The counter rises while the gauge falls: contradictory evidence.
        assertNull(sim.step(49, percentTo(51)))
        assertEquals(0, sim.progress())
        var counter = percentTo(51)
        for (percent in 50..69) {
            counter += 45_000L
            sim.step(percent, counter)
        }
        assertEquals(1, sim.observations.size)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
    }

    @Test fun reversalsWithOneFlatPropertyRestartInEitherDirection() {
        for (direction in listOf(-1, 1)) {
            val start = if (direction > 0) 40 else 80
            val end = start + direction * 5
            val counterReversal = Sim()
            counterReversal.sweep(start, end)
            counterReversal.step(end, percentTo(end - direction))
            assertEquals(0, counterReversal.progress())
            assertTrue(counterReversal.observations.isEmpty())

            val percentageReversal = Sim()
            percentageReversal.sweep(start, end)
            percentageReversal.step(end - direction, percentTo(end))
            assertEquals(0, percentageReversal.progress())
            assertTrue(percentageReversal.observations.isEmpty())
        }
    }

    @Test fun clearingEitherDirectionAllowsAFreshWindowInTheOppositeDirection() {
        for (direction in listOf(-1, 1)) {
            for (cause in listOf("percentage", "counter", "invalid", "gap", "reset")) {
                val sim = Sim()
                val start = if (direction > 0) 40 else 80
                sim.sweep(start, start + direction * 5)
                assertEquals(5, sim.progress())
                when (cause) {
                    "percentage" -> sim.step(null, percentTo(start))
                    "counter" -> sim.step(start, null)
                    "invalid" -> sim.step(start, Long.MIN_VALUE)
                    "gap" -> sim.step(start, percentTo(start), gapBefore = true)
                    "reset" -> sim.estimator.reset()
                }
                assertEquals(0, sim.progress())
                // Unknown flow/plug state ensures no hint masks stale direction.
                sim.sweep(start, start - direction * 20)
                assertEquals(1, sim.observations.size)
                assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
            }
        }
    }

    @Test fun anUncalibratedChargingWordDoesNotVetoADischargingWindow() {
        val sim = Sim()
        sim.discharge(80, 60, flow = FlowState.CHARGING, currentCalibrated = false)
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    // --------------------------------------------- missing/invalid/discontinuous

    @Test fun missingOrInvalidValuesClearTheWindow() {
        fun chargedWindow(): Sim {
            val sim = Sim()
            sim.charge(from = 40, to = 45)
            assertEquals(5, sim.progress())
            return sim
        }

        val missingPercent = chargedWindow()
        assertNull(missingPercent.step(null, percentTo(46)))
        assertEquals(0, missingPercent.progress())
        // The next reading anchors a new window; the earlier points are gone.
        assertNull(missingPercent.step(47, percentTo(47)))
        assertEquals(0, missingPercent.progress())

        val missingCounter = chargedWindow()
        assertNull(missingCounter.step(46, null))
        assertEquals(0, missingCounter.progress())

        val highPercent = chargedWindow()
        assertNull(highPercent.step(101, percentTo(101)))
        assertEquals(0, highPercent.progress())

        val lowPercent = chargedWindow()
        assertNull(lowPercent.step(-1, -45_000L))
        assertEquals(0, lowPercent.progress())

        val negativeCounter = chargedWindow()
        assertNull(negativeCounter.step(46, -1L))
        assertEquals(0, negativeCounter.progress())

        val gapBefore = chargedWindow()
        assertNull(gapBefore.step(46, percentTo(46), gapBefore = true))
        assertEquals(0, gapBefore.progress())
        assertNull(gapBefore.step(47, percentTo(47)))
        assertEquals(0, gapBefore.progress())
    }

    @Test fun anUnobservedGapIsNeverBridgedWhileCharging() {
        val sim = Sim()
        sim.charge(from = 40, to = 50)
        // Ten minutes later, a point higher: the charge moved unobserved.
        assertNull(sim.step(percent = 51, counterMicroampHours = percentTo(51), stepSeconds = 600))
        assertEquals(0, sim.progress())
        // Re-anchored at the far side, so only a full 20 points from there can publish.
        sim.charge(from = 52, to = 71)
        assertEquals(1, sim.observations.size)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
    }

    @Test fun anUnobservedGapIsNeverBridgedWhileDischarging() {
        val sim = Sim()
        sim.discharge(from = 80, to = 70, plugged = false)
        assertEquals(10, sim.progress())
        assertNull(sim.step(percent = 69, counterMicroampHours = percentTo(69), stepSeconds = 600, plugged = false))
        assertEquals(0, sim.progress())
        sim.discharge(from = 68, to = 49, plugged = false)
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        assertEquals(20.0, measurement.weight, 1e-9)
    }

    @Test fun aNonPositiveElapsedStepReAnchorsAtTheCurrentSample() {
        val sim = Sim()
        sim.charge(from = 40, to = 50)
        assertEquals(10, sim.progress())
        // No time passed, so the two readings cannot be compared: restart here.
        assertNull(sim.step(51, percentTo(51), stepSeconds = 0))
        assertEquals(0, sim.progress())
        sim.charge(from = 52, to = 71)
        assertEquals(1, sim.observations.size)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
    }

    // ------------------------------------------------------------ rate plausibility

    @Test fun coarseCounterUpdatesDuringFastStartupDoNotDiscardTheWindow() {
        for (direction in listOf(-1, 1)) {
            val sim = Sim()
            val start = if (direction > 0) 40 else 60
            var counter = percentTo(start)
            assertNull(sim.step(start, counter, stepSeconds = 1))
            // Cached 4.5 mAh batches would imply 16.2 A at the 1s poll cadence,
            // but pass the 5s reference floor in either direction.
            for (i in 1..600) {
                val percent = start + direction * (i / 30)
                if (i % 3 == 0) counter += direction * 4_500L
                sim.step(percent, counter, stepSeconds = 1)
            }
            val measurement = sim.observations.single()
            assertEquals(4_500.0, measurement.milliampHours, 1e-9)
            assertEquals(20.0, measurement.weight, 1e-9)
        }
    }

    @Test fun theFullWindowRateCheckRejectsAFastPolledBurstInBothDirections() {
        // Each 1-second step passes the 5-second cache floor (9.36 A implied), but the
        // completed window is 46.8 A: it must never become a capacity.
        val charging = Sim()
        assertNull(charging.step(40, 1_000_000L, stepSeconds = 1, plugged = true))
        var counter = 1_000_000L
        for (i in 1..100) {
            counter += 13_000L
            assertNull(
                charging.step(40 + i * 20 / 100, counter, stepSeconds = 1, plugged = true),
            )
        }
        assertTrue(charging.observations.isEmpty())
        assertEquals(0, charging.progress())

        val discharging = Sim()
        assertNull(discharging.step(60, 2_300_000L, stepSeconds = 1, plugged = false))
        counter = 2_300_000L
        for (i in 1..100) {
            counter -= 13_000L
            assertNull(
                discharging.step(60 - i * 20 / 100, counter, stepSeconds = 1, plugged = false),
            )
        }
        assertTrue(discharging.observations.isEmpty())
        assertEquals(0, discharging.progress())
    }

    @Test fun anImplausibleCounterRateRestartsTheWindow() {
        val sim = Sim()
        sim.charge(from = 40, to = 45, plugged = true)
        // 2,400 mAh in one second is not a charger: re-anchor, publish nothing.
        val inflated = percentTo(46) + 2_400_000L
        assertNull(sim.step(46, inflated, stepSeconds = 1, plugged = true))
        assertEquals(0, sim.progress())
        // The re-anchored sample starts the next window, which measures normally.
        var counter = inflated
        for (percent in 47..66) {
            counter += 45_000L
            sim.step(percent, counter, plugged = true)
        }
        assertEquals(1, sim.observations.size)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)

        val discharging = Sim()
        discharging.discharge(from = 60, to = 55, plugged = false)
        assertNull(discharging.step(54, percentTo(54) - 2_400_000L, stepSeconds = 1, plugged = false))
        assertEquals(0, discharging.progress())
        assertTrue(discharging.observations.isEmpty())
    }

    @Test fun impossibleCapacitiesAreRejectedInsteadOfClamped() {
        for (direction in listOf(-1, 1)) {
            val tooSmall = Sim()
            val tooLarge = Sim()
            val flatCounter = Sim()
            for (i in 0..20) {
                val percent = 50 + direction * i
                // 2 mAh / 20 points implies 10 mAh: reject, never clamp to 100.
                tooSmall.step(percent, 10_000L + direction * i * 100L)
                // 8 Ah / 20 points implies 40,000 mAh, with a plausible 6 A rate.
                tooLarge.step(percent, 20_000_000L + direction * i * 400_000L, stepSeconds = 240)
                flatCounter.step(percent, 1_800_000L)
            }
            assertTrue(tooSmall.observations.isEmpty())
            assertEquals(0, tooSmall.progress())
            assertTrue(tooLarge.observations.isEmpty())
            assertEquals(0, tooLarge.progress())
            assertTrue(flatCounter.observations.isEmpty())
        }
    }

    // ------------------------------------------------------------------ endpoints

    @Test fun zeroPercentAndZeroCounterEndpointsAreValid() {
        // A recording that starts on an empty gauge: 0% and a zero counter are valid.
        val charging = Sim()
        charging.charge(from = 0, to = 20)
        assertEquals(1, charging.observations.size)
        assertEquals(4_500.0, charging.observations.single().milliampHours, 1e-9)
        assertEquals(20.0, charging.observations.single().weight, 1e-9)

        // ... and a discharge may end at empty, too.
        val discharging = Sim()
        discharging.discharge(from = 20, to = 0, plugged = false)
        assertEquals(1, discharging.observations.size)
        assertEquals(4_500.0, discharging.observations.single().milliampHours, 1e-9)
        assertEquals(20.0, discharging.observations.single().weight, 1e-9)
    }

    @Test fun aZeroCounterAnchorWithNoMovementNeverFabricatesCapacity() {
        val sim = Sim()
        assertNull(sim.step(0, 0L, plugged = true))
        for (percent in 1..20) {
            assertNull(sim.step(percent, 0L, plugged = true))
        }
        assertTrue(sim.observations.isEmpty())
        // The rejected window re-anchored at 20%, so the next span still measures.
        assertEquals(0, sim.progress())
        var counter = 0L
        for (percent in 21..40) {
            counter += 45_000L
            sim.step(percent, counter, plugged = true)
        }
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    // ------------------------------------------ non-overlap, weights, aggregation

    @Test fun aCompletedWindowIsNotReusedByTheNextOne() {
        val sim = Sim()
        sim.charge(from = 20, to = 40)
        assertEquals(1, sim.observations.size)
        assertEquals(20.0, sim.observations.single().weight, 1e-9)
        // 41..59 is only 19 points on top of the finished window: no second
        // observation may be squeezed out of charge the first one already counted.
        sim.charge(from = 41, to = 59)
        assertEquals(1, sim.observations.size)
        assertEquals(19, sim.progress())
        sim.step(60, percentTo(60))
        assertEquals(2, sim.observations.size)
        assertEquals(20.0, sim.observations[1].weight, 1e-9)
        assertEquals(4_500.0, sim.observations[1].milliampHours, 1e-9)
    }

    @Test fun repeatedChargingWindowsEachCarryTheirOwnSpan() {
        val sim = Sim()
        // 20 -> 100 publishes at 40, 60, 80 and 100; each weight is its own span.
        sim.charge(from = 20, to = 100)
        assertEquals(4, sim.observations.size)
        sim.observations.forEach {
            assertEquals(4_500.0, it.milliampHours, 1e-9)
            assertEquals(20.0, it.weight, 1e-9)
        }
        assertEquals(0, sim.progress())
    }

    @Test fun repeatedDischargingWindowsEachCarryTheirOwnSpan() {
        val sim = Sim()
        // 100 -> 40 publishes at 80, 60 and 40.
        sim.discharge(from = 100, to = 40, plugged = false)
        assertEquals(3, sim.observations.size)
        sim.observations.forEach {
            assertEquals(4_500.0, it.milliampHours, 1e-9)
            assertEquals(20.0, it.weight, 1e-9)
        }
        assertEquals(0, sim.progress())
    }

    @Test fun aNewDischargeObservationAggregatesWithEarlierChargingEvidence() {
        val sim = Sim()
        sim.charge(from = 40, to = 60, plugged = true)
        assertEquals(1, sim.observations.size)
        assertEquals(4_500.0, sim.observations[0].milliampHours, 1e-9)
        // Unplugged: the next leg is a discharge with a 4,000 mAh gauge ratio.
        sim.discharge(from = 59, to = 39, milliampHoursPerPoint = 40L, plugged = false)
        assertEquals(2, sim.observations.size)
        assertEquals(4_000.0, sim.observations[1].milliampHours, 1e-9)

        var aggregate = CapacityAggregate()
        sim.observations.forEach { aggregate = aggregate.plus(it) }
        assertEquals(2, aggregate.sampleCount)
        assertEquals(40.0, aggregate.weightTotal, 1e-9)
        assertEquals(4_250.0, aggregate.estimatedMilliampHours!!, 1e-9)
    }

    @Test fun repeatedWindowsFormAWeightedAverage() {
        val aggregate = CapacityAggregate()
            .plus(CapacityMeasurement(4_500.0, weight = 20.0))
            .plus(CapacityMeasurement(3_900.0, weight = 20.0))
        assertEquals(2, aggregate.sampleCount)
        assertEquals(4_200.0, aggregate.estimatedMilliampHours!!, 1e-9)

        val estimate = aggregate.toEstimate(lastUpdatedMillis = 1_700_000_000_000L, observedPercent = 14)
        assertEquals(4_200.0, estimate.estimatedMilliampHours!!, 1e-9)
        assertEquals(2, estimate.estimateCount)
        assertEquals(1_700_000_000_000L, estimate.lastUpdatedMillis)
        assertEquals(14, estimate.observedPercent)
        assertTrue(estimate.available)
    }

    @Test fun aLongerWindowOutvotesABarelyQualifyingOne() {
        val aggregate = CapacityAggregate()
            .plus(CapacityMeasurement(4_500.0, weight = 100.0))
            .plus(CapacityMeasurement(3_000.0, weight = 20.0))
        // 4,500 still dominates, but the short window is not ignored outright.
        assertTrue(aggregate.estimatedMilliampHours!! > 3_900.0)
        assertTrue(aggregate.estimatedMilliampHours!! < 4_500.0)
    }

    @Test fun rejectedObservationsLeaveTheAggregateUntouched() {
        val aggregate = CapacityAggregate().plus(CapacityMeasurement(4_500.0, weight = 20.0))
        assertTrue(aggregate.plus(CapacityMeasurement(4_600.0, weight = 0.0)) === aggregate)
        assertTrue(aggregate.plus(CapacityMeasurement(Double.NaN, weight = 20.0)) === aggregate)
    }

    @Test fun theAggregateStaysBoundedSoOldEvidenceAgesOut() {
        var aggregate = CapacityAggregate()
        repeat(6_000) { aggregate = aggregate.plus(CapacityMeasurement(4_000.0, weight = 60.0)) }
        assertTrue(aggregate.weightTotal <= 2 * CapacityAggregate.MAX_WEIGHT)
        assertEquals(6_000, aggregate.sampleCount)
        assertEquals(4_000.0, aggregate.estimatedMilliampHours!!, 1e-6)
        aggregate = aggregate.plus(CapacityMeasurement(3_000.0, weight = 60.0))
        assertTrue(aggregate.estimatedMilliampHours!! in 3_000.0..4_000.0)
    }

    @Test fun anEmptyAggregateIsNotAnEstimate() {
        val estimate = CapacityAggregate().toEstimate(lastUpdatedMillis = null)
        assertFalse(estimate.available)
        assertNull(estimate.estimatedMilliampHours)
        assertEquals(0, estimate.estimateCount)
        assertEquals(0, estimate.observedPercent)
    }

    @Test fun legacySixtyPointObservationsCombineWithANewTwentyPointDischarge() {
        // Earlier releases accepted 60-point charging windows only; those observations
        // measure the same quantity and stay valid next to a new 20-point discharge.
        val aggregate = CapacityAggregate()
            .plus(CapacityMeasurement(4_500.0, weight = 60.0))
            .plus(CapacityMeasurement(3_900.0, weight = 20.0))
        assertEquals(2, aggregate.sampleCount)
        assertEquals(80.0, aggregate.weightTotal, 1e-9)
        assertEquals(4_350.0, aggregate.estimatedMilliampHours!!, 1e-9)
    }

    @Test fun resetClosesTheOpenWindow() {
        val sim = Sim()
        sim.charge(from = 40, to = 55)
        assertEquals(15, sim.progress())
        sim.estimator.reset()
        assertEquals(0, sim.progress())
        // A reset sample starts a fresh window and cannot publish on its own.
        sim.step(56, percentTo(56))
        assertEquals(0, sim.progress())
        sim.charge(from = 57, to = 70)
        assertTrue(sim.observations.isEmpty())
    }

    // ------------------------------------------------------ gauge and runtime math

    @Test fun theGaugeHelperIsValidatedAndNeverPersisted() {
        // 3,600 mAh remaining at 80% reads as a 4,500 mAh gauge estimate.
        assertEquals(
            4_500.0,
            CapacityMath.gaugeFullCapacityMilliampHours(
                BatterySample(
                    timestampMillis = 0L,
                    elapsedRealtimeMillis = 0L,
                    percentage = 80,
                    chargeCounterMicroampHours = 3_600_000L,
                ),
            )!!,
            1e-9,
        )
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(null))
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = null, counter = 3_600_000L)))
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 80, counter = null)))
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 80, counter = 0L)))
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 0, counter = 3_600_000L)))
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 4, counter = 3_600_000L)))
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 101, counter = 3_600_000L)))
        // 10 uAh at 80% implies 0.0125 mAh: counter noise, not a battery.
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 80, counter = 10L)))
        // 40 Ah at 80% implies 50,000 mAh: not a phone battery. Rejected, not clamped.
        assertNull(CapacityMath.gaugeFullCapacityMilliampHours(gaugeSample(percent = 80, counter = 40_000_000L)))
    }

    @Test fun runtimeIsTimeToEmptyWhileDischarging() {
        // 3,600 mAh left at -360 mA -> 10 hours.
        assertEquals(36_000.0, CapacityMath.runtimeSeconds(3_600_000L, -0.36)!!, 1e-6)
        // 3,600 mAh left at +600 mA with a 4,800 mAh capacity -> 2 hours to full.
        assertEquals(
            7_200.0,
            CapacityMath.runtimeSeconds(3_600_000L, 0.6, capacityMilliampHours = 4_800.0)!!,
            1e-6,
        )
    }

    @Test fun runtimeIsUnavailableWithoutTrustworthyInputs() {
        assertNull(CapacityMath.runtimeSeconds(null, -0.5))
        assertNull(CapacityMath.runtimeSeconds(0L, -0.5))
        assertNull(CapacityMath.runtimeSeconds(3_600_000L, null))
        assertNull(CapacityMath.runtimeSeconds(3_600_000L, 0.0))
        assertNull(CapacityMath.runtimeSeconds(3_600_000L, Double.NaN))
        // Counter resolution decides the answer below half a milliamp.
        assertNull(CapacityMath.runtimeSeconds(3_600_000L, -0.0001))
        // Charging without a known capacity cannot say when it is full.
        assertNull(CapacityMath.runtimeSeconds(3_600_000L, 0.6))
        // A capacity below the remaining charge is nonsense.
        assertNull(CapacityMath.runtimeSeconds(3_600_000L, 0.6, capacityMilliampHours = 3_000.0))
    }

    @Test fun chargePercentUsesTheMeasuredCounter() {
        assertEquals(50, CapacityMath.chargePercent(2_250_000L, 4_500.0))
        assertEquals(0, CapacityMath.chargePercent(1L, 4_500.0))
        assertNull(CapacityMath.chargePercent(2_250_000L, null))
        assertNull(CapacityMath.chargePercent(null, 4_500.0))
        assertEquals(100, CapacityMath.chargePercent(9_000_000L, 4_500.0))
    }

    @Test fun defaultPolicySpansTwentyPointsInBothDirections() {
        // Guards the published accuracy policy against an accidental tuning change.
        assertEquals(20, CapacityEstimator.MINIMUM_PERCENT_SPAN)
        assertEquals(5 * 60_000L, CapacityEstimator.MAX_WINDOW_GAP_MILLIS)
        assertEquals(0, CapacityEstimator().progressPercent())

        val charging = Sim()
        charging.charge(from = 10, to = 30)
        assertEquals(1, charging.observations.size)
        assertEquals(20.0, charging.observations.single().weight, 1e-9)

        val discharging = Sim()
        discharging.discharge(from = 30, to = 10, plugged = false)
        assertEquals(1, discharging.observations.size)
        assertEquals(20.0, discharging.observations.single().weight, 1e-9)
    }

    private fun percentTo(percent: Int): Long = percent * 45_000L

    private fun gaugeSample(percent: Int?, counter: Long?) = BatterySample(
        timestampMillis = 0L,
        elapsedRealtimeMillis = 0L,
        percentage = percent,
        chargeCounterMicroampHours = counter,
    )
}
