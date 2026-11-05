package io.github.anevilburrito.wpm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        /** `percent` points of charge at 45 mAh per point: a 4,500 mAh battery. */
        fun charge(
            from: Int,
            to: Int,
            stepSeconds: Long = 60,
            milliampHoursPerPoint: Long = 45L,
            plugged: Boolean? = null,
            currentCalibrated: Boolean = false,
        ) {
            for (percent in from..to) {
                step(
                    percent,
                    percent * milliampHoursPerPoint * 1000L,
                    stepSeconds,
                    plugged = plugged,
                    currentCalibrated = currentCalibrated,
                )
            }
        }
    }

    @Test fun coarseCounterUpdatesDuringFastStartupDoNotDiscardTheChargingAnchor() {
        val sim = Sim(CapacityEstimator(minPercentSpan = 1))
        assertNull(sim.step(10, 1_000_000L, plugged = true))
        // A cached 3mAh release at a 1s polling cadence is not proof of 10.8A.
        assertNull(sim.step(10, 1_003_000L, stepSeconds = 1, plugged = true))
        val measured = sim.step(11, 1_050_000L, stepSeconds = 59, plugged = true)!!
        assertEquals(5_000.0, measured.milliampHours, 1e-9)
    }

    @Test fun aFastPollFloorCannotHideAnImpossibleCompletedChargingRate() {
        val sim = Sim(CapacityEstimator(minPercentSpan = 1))
        assertNull(sim.step(10, 1_000_000L, plugged = true))
        // Each 1s step passes the 5s cache allowance, but 14.4A over the
        // completed window still exceeds policy and must not become capacity.
        assertNull(sim.step(10, 1_004_000L, stepSeconds = 1, plugged = true))
        assertNull(sim.step(11, 1_008_000L, stepSeconds = 1, plugged = true))
        assertTrue(sim.observations.isEmpty())
    }

    @Test fun anExplicitlyUnpluggedSampleClosesTheWindowInsteadOfExtendingIt() {
        val sim = Sim()
        sim.charge(from = 40, to = 70, plugged = true)
        assertEquals(30, sim.estimator.progressPercent())
        // Still rising counter, but the battery is on its own: this is gauge
        // movement, not charge added, so the window must not survive it.
        sim.step(71, percentTo(71), plugged = false)
        assertEquals(0, sim.estimator.progressPercent())
        // 71 -> 100 is only 29 points from the new anchor, so nothing can publish.
        sim.charge(from = 72, to = 100, plugged = true)
        assertTrue(sim.observations.isEmpty())
    }

    @Test fun anUnpluggedEndpointCannotCompleteAnObservation() {
        val sim = Sim()
        sim.charge(from = 40, to = 99, plugged = true)
        assertTrue(sim.observations.isEmpty())
        // The sample that would have completed the 60-point swing reports the plug
        // was pulled: no observation, and the window does not linger.
        assertNull(sim.step(100, percentTo(100), plugged = false))
        assertTrue(sim.observations.isEmpty())
        assertEquals(0, sim.estimator.progressPercent())
    }

    @Test fun aCalibratedDischargeReportClosesTheWindowEvenWhenPlugged() {
        val sim = Sim()
        sim.charge(from = 40, to = 70, plugged = true)
        assertEquals(30, sim.estimator.progressPercent())
        // Net discharge with the charger connected, with an established current sign:
        // real, and not a charging session.
        sim.step(71, percentTo(71), plugged = true, flow = FlowState.DISCHARGING, currentCalibrated = true)
        assertEquals(0, sim.estimator.progressPercent())
        assertTrue(sim.observations.isEmpty())
    }

    @Test fun anUncalibratedDischargeWordDoesNotVetoAPluggedWindow() {
        // CURRENT_NOW may be mis-signed before calibration; its "discharging" word
        // must not throw away a valid plugged counter window.
        val sim = Sim()
        sim.charge(from = 40, to = 70, plugged = true, currentCalibrated = false)
        sim.step(71, percentTo(71), plugged = true, flow = FlowState.DISCHARGING, currentCalibrated = false)
        assertEquals(31, sim.estimator.progressPercent())
        sim.charge(from = 72, to = 100, plugged = true, currentCalibrated = false)
        assertEquals(1, sim.observations.size)
    }

    @Test fun anUnknownPlugStateStaysNeutral() {
        // Old recordings (and devices without EXTRA_PLUGGED) have no plug state; a
        // missing property must not be read as "unplugged".
        val sim = Sim()
        sim.charge(from = 40, to = 100)
        assertEquals(1, sim.observations.size)
        assertEquals(4_500.0, sim.observations.single().milliampHours, 1e-9)
    }

    @Test fun aPluggedWindowDoesNotNeedACalibratedInstantCurrent() {
        // The window compares the counter with the gauge; CURRENT_NOW plays no part,
        // so an uncalibrated (or missing) instant reading must not veto it.
        val sim = Sim()
        for (percent in 40..100) {
            sim.step(
                percent,
                percentTo(percent),
                plugged = true,
                flow = FlowState.CHARGING,
                currentCalibrated = false,
            )
        }
        assertEquals(1, sim.observations.size)
    }

    @Test fun aSixtyPointChargingWindowReportsTheObservedCapacity() {
        val sim = Sim()
        // 40% -> 100% moves the counter by 60 * 45 mAh = 2,700 mAh, so the battery is
        // 2,700 / 0.60 = 4,500 mAh.
        sim.charge(from = 40, to = 100)
        assertEquals(1, sim.observations.size)
        val measurement = sim.observations.single()
        assertEquals(4_500.0, measurement.milliampHours, 1e-9)
        // Weight is the swing that produced it, so windows stay comparable.
        assertEquals(60.0, measurement.weight, 1e-9)
        assertEquals(0, sim.estimator.progressPercent())
    }

    @Test fun aShortWindowPublishesProgressInsteadOfAnEstimate() {
        val sim = Sim()
        sim.charge(from = 40, to = 90)
        assertTrue(sim.observations.isEmpty())
        // 50 of the required 60 points gathered; the policy is stated, not hidden.
        assertEquals(50, sim.estimator.progressPercent())
    }

    @Test fun aWindowShorterThanSixtyPointsIsNeverPublishedEvenWhenTheRatioLooksClean() {
        val sim = Sim()
        // 59 points, a perfectly consistent 4,500 mAh gauge: still not enough spread.
        sim.charge(from = 41, to = 100)
        assertTrue(sim.observations.isEmpty())
        assertEquals(59, sim.estimator.progressPercent())
    }

    @Test fun missingValuesOrADiscontinuityCloseTheWindow() {
        val missingPercent = Sim()
        missingPercent.charge(from = 40, to = 70)
        assertEquals(30, missingPercent.estimator.progressPercent())
        assertNull(missingPercent.step(null, percentTo(71)))
        // The next reading anchors a new window, so the earlier 30 points are gone.
        assertEquals(0, missingPercent.estimator.progressPercent())

        val missingCounter = Sim()
        missingCounter.charge(from = 40, to = 70)
        assertNull(missingCounter.step(71, null))
        assertEquals(0, missingCounter.estimator.progressPercent())

        val gap = Sim()
        gap.charge(from = 40, to = 100)
        assertEquals(1, gap.observations.size)
        gap.step(40, percentTo(40), gapBefore = true)
        assertEquals(0, gap.estimator.progressPercent())
    }

    @Test fun anUnobservedGapIsNeverBridged() {
        val sim = Sim()
        sim.charge(from = 40, to = 70)
        // Ten minutes later, 41 points higher: the charge moved unobserved.
        sim.step(percent = 71, counterMicroampHours = percentTo(71), stepSeconds = 600)
        assertEquals(0, sim.estimator.progressPercent())
        sim.charge(from = 72, to = 100)
        assertTrue(sim.observations.isEmpty())
    }

    @Test fun aReversePercentageOrCounterRestartsInsteadOfTrustingTheDifference() {
        val reversedPercent = Sim()
        reversedPercent.charge(from = 40, to = 70)
        reversedPercent.step(69, percentTo(69))
        assertEquals(0, reversedPercent.estimator.progressPercent())

        val reversedCounter = Sim()
        reversedCounter.charge(from = 40, to = 70)
        // Percentage rises while the counter falls: a pin/gauge reset, not charging.
        reversedCounter.step(71, percentTo(69))
        assertEquals(0, reversedCounter.estimator.progressPercent())

        // Reversals never contribute: the restarted window is too short to publish.
        reversedCounter.charge(from = 72, to = 100)
        assertTrue(reversedCounter.observations.isEmpty())
    }

    @Test fun anImplausibleCounterRateRestartsTheWindow() {
        val sim = Sim()
        sim.charge(from = 40, to = 50)
        // 2,400 mAh in one second is not a charger: re-anchor, publish nothing.
        sim.step(51, percentTo(51) + 2_400_000L, stepSeconds = 1)
        assertEquals(0, sim.estimator.progressPercent())
        sim.charge(from = 52, to = 100)
        assertTrue(sim.observations.isEmpty())
    }

    @Test fun impossibleCapacitiesAreRejectedInsteadOfClamped() {
        // 60 points but only 6 mAh of charge: implies a 10 mAh battery.
        val tooSmall = Sim()
        for (percent in 40..100) {
            tooSmall.step(percent, 1_800_000L + (percent - 40) * 100L)
        }
        assertTrue(tooSmall.observations.isEmpty())

        // 60 points and 24 Ah of charge: implies 40,000 mAh, not a phone battery.
        val tooLarge = Sim()
        for (percent in 40..100) {
            tooLarge.step(percent, percent * 400_000L, stepSeconds = 240)
        }
        assertTrue(tooLarge.observations.isEmpty())

        // The percentage moving without the counter moving cannot be divided by it.
        val flatCounter = Sim()
        for (percent in 40..100) {
            flatCounter.step(percent, 1_800_000L)
        }
        assertTrue(flatCounter.observations.isEmpty())
    }

    @Test fun aCompletedWindowIsNotReusedByTheNextOne() {
        val sim = Sim()
        sim.charge(from = 20, to = 80)
        assertEquals(1, sim.observations.size)
        // 81% -> 100% is only 20 points on top of the finished window, so no second
        // observation may be squeezed out of charge the first one already counted.
        sim.charge(from = 81, to = 100)
        assertEquals(1, sim.observations.size)
        assertEquals(20, sim.estimator.progressPercent())
    }

    @Test fun resetClosesTheOpenWindow() {
        val sim = Sim()
        sim.charge(from = 40, to = 90)
        assertEquals(50, sim.estimator.progressPercent())
        sim.estimator.reset()
        assertEquals(0, sim.estimator.progressPercent())
        // A reset sample starts a fresh window and cannot publish on its own.
        sim.step(100, percentTo(100))
        assertEquals(0, sim.estimator.progressPercent())
        assertTrue(sim.observations.isEmpty())
    }

    @Test fun repeatedWindowsFormAWeightedAverage() {
        val aggregate = CapacityAggregate()
            .plus(CapacityMeasurement(4_500.0, weight = 60.0))
            .plus(CapacityMeasurement(3_900.0, weight = 60.0))
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
            .plus(CapacityMeasurement(3_000.0, weight = 60.0))
        // 4,500 still dominates, but the short window is not ignored outright.
        assertTrue(aggregate.estimatedMilliampHours!! > 3_900.0)
        assertTrue(aggregate.estimatedMilliampHours!! < 4_500.0)
    }

    @Test fun rejectedObservationsLeaveTheAggregateUntouched() {
        val aggregate = CapacityAggregate().plus(CapacityMeasurement(4_500.0, weight = 60.0))
        assertTrue(aggregate.plus(CapacityMeasurement(4_600.0, weight = 0.0)) === aggregate)
        assertTrue(aggregate.plus(CapacityMeasurement(Double.NaN, weight = 60.0)) === aggregate)
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

    @Test fun defaultPolicySpansSixtyPointsAndRejectsShortWindows() {
        // Guards the published accuracy policy against an accidental tuning change.
        assertEquals(60, CapacityEstimator.MINIMUM_PERCENT_SPAN)
        assertEquals(5 * 60_000L, CapacityEstimator.MAX_WINDOW_GAP_MILLIS)
        assertEquals(0, CapacityEstimator().progressPercent())
        val exactly = Sim()
        exactly.charge(from = 10, to = 70)
        assertEquals(1, exactly.observations.size)
    }

    private fun percentTo(percent: Int): Long = percent * 45_000L

    private fun gaugeSample(percent: Int?, counter: Long?) = BatterySample(
        timestampMillis = 0L,
        elapsedRealtimeMillis = 0L,
        percentage = percent,
        chargeCounterMicroampHours = counter,
    )
}
