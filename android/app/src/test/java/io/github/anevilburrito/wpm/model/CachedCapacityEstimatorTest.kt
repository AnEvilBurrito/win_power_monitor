package io.github.anevilburrito.wpm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression coverage for gauge batches that span multiple successful polls. */
class CachedCapacityEstimatorTest {
    @Test fun tenSecondCounterBatchesSurviveFasterPollingInBothDirections() {
        checkBatchedWindows(batchMicroampHours = 15_588L, updatesPerWindow = 50, updateSeconds = 10)
    }

    @Test fun fifteenSecondCounterBatchesSurviveFasterPollingInBothDirections() {
        checkBatchedWindows(batchMicroampHours = 15_588L, updatesPerWindow = 50, updateSeconds = 15)
    }

    @Test fun largerCounterBatchesSurviveFasterPollingInBothDirections() {
        checkBatchedWindows(batchMicroampHours = 19_485L, updatesPerWindow = 40, updateSeconds = 10)
    }

    @Test fun consecutiveCachedWindowsDoNotReuseCharge() {
        checkBatchedWindows(15_588L, updatesPerWindow = 50, updateSeconds = 10, windows = 2)
    }

    @Test fun theTenAmpLimitIsInclusiveOverTheCounterUpdateInterval() {
        checkRateBoundary(batchMicroampHours = 25_000L, expectedProgress = 1)
    }

    @Test fun aRateAboveTenAmpsStillRestartsTheWindow() {
        checkRateBoundary(batchMicroampHours = 25_001L, expectedProgress = 0)
    }

    @Test fun anImplausibleBatchIsRejectedEvenAfterFlatPolls() {
        for (direction in listOf(-1, 1)) {
            val estimator = CapacityEstimator()
            for (seconds in 0..60 step 5) {
                assertNull(estimator.add(sample(seconds.toLong(), 50, 2_000_000L)))
            }
            // 200 mAh over the entire observed 65 s is still over 10 A.
            assertNull(estimator.add(sample(65, 50 + direction, 2_000_000L + direction * 200_000L)))
            assertEquals(0, estimator.progressPercent())
        }
    }

    @Test fun anAcceptedCounterUpdateConsumesItsCachedTime() {
        for (direction in listOf(-1, 1)) {
            val estimator = CapacityEstimator()
            for (seconds in 0..60 step 5) {
                estimator.add(sample(seconds.toLong(), 50, 2_000_000L))
            }
            // This batch can use the preceding flat interval.
            assertNull(estimator.add(sample(65, 50 + direction, 2_000_000L + direction * 15_588L)))
            assertEquals(1, estimator.progressPercent())
            // The next batch has only 5 s, not the same idle minute again.
            assertNull(estimator.add(sample(70, 50 + direction * 2, 2_000_000L + direction * 31_176L)))
            assertEquals(0, estimator.progressPercent())
        }
    }

    @Test fun clearingOrRestartingAWindowForgetsItsCachedTime() {
        val causes = listOf(
            "reset", "missing_percent", "missing_counter", "invalid_percent", "negative_counter",
            "gap_flag", "counter_reversal", "percent_reversal", "trusted_flow", "non_positive_time",
            "time_gap", "rate_jump",
        )
        for (cause in causes) {
            val estimator = CapacityEstimator()
            estimator.add(sample(0, 50, 2_000_000L))
            estimator.add(sample(5, 51, 2_001_000L))
            for (seconds in 10..60 step 5) {
                estimator.add(sample(seconds.toLong(), 51, 2_001_000L))
            }
            var seconds = 65L
            var percent = 51
            var counter = 2_001_000L
            var direction = 1
            when (cause) {
                "reset" -> estimator.reset()
                "missing_percent", "missing_counter", "invalid_percent", "negative_counter", "gap_flag" -> {
                    estimator.add(
                        sample(
                            seconds,
                            if (cause == "missing_percent") null else if (cause == "invalid_percent") 101 else percent,
                            if (cause == "missing_counter") null else if (cause == "negative_counter") -1L else counter,
                            gapBefore = cause == "gap_flag",
                        ),
                    )
                    seconds += 5
                }
                "counter_reversal" -> counter -= 1_000L
                "percent_reversal" -> percent -= 1
                "trusted_flow" -> direction = -1
                "non_positive_time" -> seconds = 60
                "time_gap" -> seconds = 400
                "rate_jump" -> counter += 200_000L
            }
            // A clear needs a fresh anchor; a restart uses this point as its anchor.
            estimator.add(
                sample(
                    seconds, percent, counter,
                    flow = if (cause == "trusted_flow") FlowState.DISCHARGING else FlowState.UNKNOWN,
                    currentCalibrated = cause == "trusted_flow",
                ),
            )
            assertEquals(cause, 0, estimator.progressPercent())
            assertNull(estimator.add(sample(seconds + 5, percent + direction, counter + direction * 15_588L)))
            assertEquals("$cause must not carry cached time across a boundary", 0, estimator.progressPercent())
        }
    }

    @Test fun aCompletedWindowStartsANewCounterClockEvenWhenItsLastCounterStepWasEarlier() {
        val estimator = CapacityEstimator()
        for (seconds in 0..500 step 5) {
            val updates = seconds / 10
            assertNull(
                estimator.add(
                    sample(seconds.toLong(), 40 + minOf(updates * 20 / 50, 19), 1_600_000L + updates * 15_588L),
                ),
            )
        }
        val endpoint = 1_600_000L + 50 * 15_588L
        // Percentage catches up on a flat counter, 10 s after its final change.
        val measurement = estimator.add(sample(510, 60, endpoint))!!
        assertEquals(3_897.0, measurement.milliampHours, 1e-9)
        assertNull(estimator.add(sample(515, 61, endpoint + 15_588L)))
        assertEquals(0, estimator.progressPercent())
    }

    private fun checkRateBoundary(batchMicroampHours: Long, expectedProgress: Int) {
        for (direction in listOf(-1, 1)) {
            val estimator = CapacityEstimator()
            for (seconds in 0..6 step 3) {
                estimator.add(sample(seconds.toLong(), 50, 2_000_000L))
            }
            // Exactly 25 mAh / 9 s = 10 A; the last poll was only 3 s ago.
            assertNull(estimator.add(sample(9, 50 + direction, 2_000_000L + direction * batchMicroampHours)))
            assertEquals(expectedProgress, estimator.progressPercent())
        }
    }

    private fun checkBatchedWindows(
        batchMicroampHours: Long,
        updatesPerWindow: Int,
        updateSeconds: Int,
        windows: Int = 1,
    ) {
        for (direction in listOf(-1, 1)) {
            for (pollSeconds in listOf(1, 2, 5)) {
                val estimator = CapacityEstimator()
                val start = if (direction > 0) 40 else 80
                val counter = start * 38_970L
                val observations = ArrayList<CapacityMeasurement>()
                assertNull(estimator.add(sample(0, start, counter)))
                for (seconds in pollSeconds..updateSeconds * updatesPerWindow * windows step pollSeconds) {
                    val updates = seconds / updateSeconds
                    val measurement = estimator.add(
                        sample(
                            seconds.toLong(),
                            start + direction * updates * 20 / updatesPerWindow,
                            counter + direction * updates * batchMicroampHours,
                        ),
                    )
                    if (seconds % (updateSeconds * updatesPerWindow) == 0) {
                        assertNotNull("direction=$direction, pollSeconds=$pollSeconds", measurement)
                        observations.add(measurement!!)
                    } else {
                        assertNull(measurement)
                    }
                }
                assertEquals(windows, observations.size)
                observations.forEach {
                    assertEquals(3_897.0, it.milliampHours, 1e-9)
                    assertEquals(20.0, it.weight, 1e-9)
                }
                assertEquals(0, estimator.progressPercent())
            }
        }
    }

    private fun sample(
        seconds: Long,
        percent: Int?,
        counter: Long?,
        gapBefore: Boolean = false,
        flow: FlowState = FlowState.UNKNOWN,
        currentCalibrated: Boolean = false,
    ) = BatterySample(
        timestampMillis = seconds * 1000,
        elapsedRealtimeMillis = seconds * 1000,
        percentage = percent,
        chargeCounterMicroampHours = counter,
        gapBefore = gapBefore,
        flow = flow,
        currentCalibrated = currentCalibrated,
    )
}
