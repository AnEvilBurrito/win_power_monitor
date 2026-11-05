package io.github.anevilburrito.wpm.model

import org.junit.Assert.*
import org.junit.Test

class SessionAnalyticsTest {
    private fun sample(seconds: Long, watts: Double?, gap: Boolean = false) = BatterySample(
        timestampMillis = 1_000_000L + seconds * 1000,
        elapsedRealtimeMillis = seconds * 1000,
        watts = watts,
        gapBefore = gap,
    )

    @Test fun constantDischargeHasSignedAndMagnitudeStatistics() {
        val acc = SessionAccumulator(7, 1_000_000, 0)
        listOf(0L, 15L, 30L).forEach { acc.add(sample(it, -6.0), 15) }
        val summary = acc.snapshot(1_030_000)
        assertEquals(0.05, summary.outWh, 1e-10)
        assertEquals(0.0, summary.inWh, 0.0)
        assertEquals(30.0, summary.observedSeconds, 0.0)
        assertEquals(-6.0, summary.signedAverageWatts!!, 0.0)
        assertEquals(6.0, summary.magnitudeAverageWatts!!, 0.0)
        assertEquals(1_030_000L, summary.endedAtMillis)
    }

    @Test fun zeroCrossingsKeepBothEnergyDirections() {
        val acc = SessionAccumulator(1, 1_000_000, 0)
        acc.add(sample(0, -4.0), 30)
        val summary = acc.add(sample(30, 4.0), 30)
        assertEquals(30.0 / 3600.0, summary.outWh, 1e-10)
        assertEquals(30.0 / 3600.0, summary.inWh, 1e-10)
        assertEquals(0.0, summary.signedAverageWatts!!, 0.0)
        assertEquals(2.0, summary.magnitudeAverageWatts!!, 0.0)
    }

    @Test fun sleepGapsDoNotInventEnergyOrObservedTime() {
        val acc = SessionAccumulator(1, 1_000_000, 0)
        listOf(0L, 15L, 120L, 135L).forEach { acc.add(sample(it, -4.0), 15) }
        val summary = acc.snapshot()
        assertEquals(135.0, summary.elapsedSeconds, 0.0)
        assertEquals(30.0, summary.observedSeconds, 0.0)
        assertEquals(120.0 / 3600.0, summary.outWh, 1e-10)
        assertEquals(1, summary.gapCount)
    }

    @Test fun missingRatesAndExplicitCadenceBoundariesBreakIntegration() {
        val acc = SessionAccumulator(1, 1_000_000, 0)
        acc.add(sample(0, -4.0), 15)
        acc.add(sample(15, null), 15)
        acc.add(sample(30, -4.0), 15)
        acc.add(sample(45, -4.0, true), 15)
        val summary = acc.add(sample(60, -4.0), 15)
        assertEquals(15.0, summary.observedSeconds, 0.0)
        assertEquals(3, summary.gapCount)
        assertEquals(60.0 / 3600.0, summary.outWh, 1e-10)
    }

    @Test fun averagesAreTimeWeightedAndExtremaAreOrdered() {
        val acc = SessionAccumulator(1, 1_000_000, 0)
        acc.add(sample(0, -2.0), 15)
        acc.add(sample(10, -4.0), 15)
        val summary = acc.add(sample(30, -8.0), 15)
        assertEquals(-5.0, summary.signedAverageWatts!!, 1e-10)
        assertEquals(-8.0, summary.minimumSignedWatts!!, 0.0)
        assertEquals(-2.0, summary.maximumSignedWatts!!, 0.0)
        assertEquals(2.0, summary.minimumMagnitudeWatts!!, 0.0)
        assertEquals(8.0, summary.maximumMagnitudeWatts!!, 0.0)
    }

    @Test fun firstPointAndNonFiniteRatesHaveNoAverage() {
        val acc = SessionAccumulator(1, 1_000_000, 0)
        assertNull(acc.add(sample(0, -3.0), 15).signedAverageWatts)
        val summary = acc.add(sample(15, Double.NaN), 15)
        assertEquals(-3.0, summary.minimumSignedWatts!!, 0.0)
        assertNull(summary.magnitudeAverageWatts)
    }

    @Test fun wallClockChangesDoNotChangeMonotonicIntegration() {
        val acc = SessionAccumulator(1, 1_000_000, 0)
        acc.add(sample(0, 4.0), 15)
        val summary = acc.add(sample(15, 4.0).copy(timestampMillis = -200_000), 15)
        assertEquals(15.0, summary.observedSeconds, 0.0)
        assertEquals(60.0 / 3600.0, summary.inWh, 1e-10)
    }

    @Test fun staleAndRebootTimesAreUnavailable() {
        assertFalse(isSampleStale(sample(100, 1.0), 145_000, 15))
        assertTrue(isSampleStale(sample(100, 1.0), 145_001, 15))
        assertTrue(isSampleStale(sample(100, 1.0), 50_000, 15))
        assertTrue(isSampleStale(null, 0, 15))
        assertEquals(75_000L, gapThresholdMillis(30))
    }

    @Test fun windowClipsWithoutAddingUnsampledCoverage() {
        val samples = listOf(sample(0, -9.0), sample(290, -2.0), sample(300, -4.0))
        val summary = summarizeWindow(samples, 30, 15)
        assertEquals(2, summary.sampleCount)
        assertEquals(10.0, summary.observedSeconds, 0.0)
        assertEquals(-4.0, summary.minimumSignedWatts!!, 0.0)
    }

    @Test fun invalidSettingsFallBackToSupportedInterval() {
        assertEquals(1, MonitorSettings(intervalSeconds = 1).normalized().intervalSeconds)
        assertEquals(2, MonitorSettings().intervalSeconds)
        assertEquals(2, MonitorSettings(intervalSeconds = 2).normalized().intervalSeconds)
        assertEquals(5, MonitorSettings(intervalSeconds = 5).normalized().intervalSeconds)
        // The old 15/30 cadence and any other value now normalise to the 2 s default.
        assertEquals(2, MonitorSettings(intervalSeconds = 15).normalized().intervalSeconds)
        assertEquals(2, MonitorSettings(intervalSeconds = 30).normalized().intervalSeconds)
        assertEquals(2, MonitorSettings(intervalSeconds = 3).normalized().intervalSeconds)
    }

    @Test fun theDefaultSelectorKeepsIntegratingTheStoredInstantPower() {
        // Backward compatibility: with no selector the accumulator still uses [watts],
        // so stored history and legacy callers are unchanged.
        val acc = SessionAccumulator(1, 1_000_000, 0)
        acc.add(instantAndAverage(0, instant = -2.0, average = -8.0), 15)
        val summary = acc.add(instantAndAverage(15, instant = -2.0, average = -8.0), 15)
        assertEquals(-2.0, summary.signedAverageWatts!!, 1e-12)
        assertEquals(2.0 * 15.0 / 3600.0, summary.outWh, 1e-12)
    }

    @Test fun aNewLiveRecordingIntegratesTheCalibratedInstantPower() {
        // What the repository does for new recordings: same samples, live metric.
        val acc = SessionAccumulator(1, 1_000_000, 0, samplePowerWatts = { it.liveWatts })
        acc.add(instantAndAverage(0, instant = -2.0, average = -8.0), 2)
        val summary = acc.add(instantAndAverage(2, instant = -2.0, average = -8.0), 2)
        assertEquals(-2.0, summary.signedAverageWatts!!, 1e-12)
        assertEquals(2.0 * 2.0 / 3600.0, summary.outWh, 1e-12)
        assertEquals(-2.0, summary.minimumSignedWatts!!, 1e-12)
        assertEquals(-2.0, summary.maximumSignedWatts!!, 1e-12)
        assertEquals(2, summary.sampleCount)
    }

    @Test fun selectedEnergyMatchesTheChosenMetricNotTheOtherColumn() {
        // The same pair of samples, integrated once with the live selector and once
        // with the explicitly auxiliary counter average. The two energies must be the
        // two measurements, never a mix: this is the "source-matched energy" rule.
        val samples = listOf(
            instantAndAverage(0, instant = -2.0, average = -8.0),
            instantAndAverage(15, instant = -2.0, average = -8.0),
        )
        val live = SessionAccumulator(1, 1_000_000, 0, samplePowerWatts = { it.liveWatts })
        val average = SessionAccumulator(1, 1_000_000, 0, samplePowerWatts = { it.averageWatts })
        samples.forEach {
            live.add(it, 15)
            average.add(it, 15)
        }
        val liveSummary = live.snapshot()
        val averageSummary = average.snapshot()
        assertEquals(2.0 * 15.0 / 3600.0, liveSummary.outWh, 1e-12)
        assertEquals(8.0 * 15.0 / 3600.0, averageSummary.outWh, 1e-12)
        assertEquals(-2.0, liveSummary.signedAverageWatts!!, 1e-12)
        assertEquals(-8.0, averageSummary.signedAverageWatts!!, 1e-12)
    }

    @Test fun unconfirmedInstantPowerIsAGapNotEnergy() {
        val acc = SessionAccumulator(1, 1_000_000, 0, samplePowerWatts = { it.liveWatts })
        acc.add(uncalibratedInstant(0, instant = -2.0), 2)
        val summary = acc.add(uncalibratedInstant(2, instant = -2.0), 2)
        // The stored instant watts exist but the reading is not calibrated, so the
        // live selector returns null: no fabricated energy from an unconfirmed value.
        assertEquals(0.0, summary.observedSeconds, 0.0)
        assertEquals(0.0, summary.outWh, 0.0)
        assertEquals(1, summary.gapCount)
        assertNull(summary.signedAverageWatts)
    }

    @Test fun aMissingLiveReadingIsAGapNotTheAuxiliaryAverageFallback() {
        val acc = SessionAccumulator(1, 1_000_000, 0, samplePowerWatts = { it.liveWatts })
        acc.add(instantAndAverage(0, instant = -2.0, average = -8.0), 2)
        // The reading became unconfirmed (or was decoder-suppressed): no live value.
        // The counter average that still exists must not be substituted.
        val summary = acc.add(instantAndAverage(2, instant = null, average = -8.0), 2)
        assertEquals(0.0, summary.observedSeconds, 0.0)
        assertEquals(0.0, summary.outWh, 0.0)
        assertEquals(1, summary.gapCount)
        assertEquals(-2.0, summary.minimumSignedWatts!!, 1e-12)
    }

    @Test fun windowSummaryCanUseTheSameLiveSelector() {
        val samples = listOf(
            instantAndAverage(0, instant = -2.0, average = -8.0),
            instantAndAverage(30, instant = -2.0, average = -8.0),
        )
        val instant = summarizeWindow(samples, 60, 2)
        assertEquals(-2.0, instant.signedAverageWatts!!, 1e-12)
        val live = summarizeWindow(samples, 60, 2, samplePowerWatts = { it.liveWatts })
        assertEquals(-2.0, live.signedAverageWatts!!, 1e-12)
        val averaged = summarizeWindow(samples, 60, 2, samplePowerWatts = { it.averageWatts })
        assertEquals(-8.0, averaged.signedAverageWatts!!, 1e-12)
    }

    @Test fun oneSecondCadenceHasATrueFloorInGapAndStalenessRules() {
        // The 1 s selection is valid, so its gap/stale thresholds must not round up to
        // the old 5 s-era expectations.
        assertEquals(30_000L, gapThresholdMillis(1))
        assertEquals(30_000L, gapThresholdMillis(2))
        assertEquals(75_000L, gapThresholdMillis(30))
        assertFalse(isSampleStale(sample(100, 1.0), 145_000, 1))
        assertTrue(isSampleStale(sample(100, 1.0), 145_001, 1))
    }

    /** One sample carrying both a raw instant reading and an optional auxiliary average. */
    private fun instantAndAverage(
        seconds: Long,
        instant: Double?,
        average: Double?,
    ) = BatterySample(
        timestampMillis = 1_000_000L + seconds * 1000,
        elapsedRealtimeMillis = seconds * 1000,
        // 1 V keeps the arithmetic obvious: the average power equals the derived
        // rate's number, while the instant power is the separate [instant] value.
        watts = instant,
        volts = 1.0,
        amps = instant,
        currentCalibrated = instant != null,
        currentSource = if (instant == null) CurrentSource.NONE else CurrentSource.INSTANT,
        derivedAmps = average,
        derivedWindowMillis = if (average == null) null else 60_000L,
    )

    /** A stored instant watt value whose interpretation was never established. */
    private fun uncalibratedInstant(seconds: Long, instant: Double) = BatterySample(
        timestampMillis = 1_000_000L + seconds * 1000,
        elapsedRealtimeMillis = seconds * 1000,
        watts = instant,
        volts = 1.0,
        amps = instant,
        currentCalibrated = false,
        currentSource = CurrentSource.INSTANT,
    )
}
