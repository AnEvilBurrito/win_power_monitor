package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChartModelTest {
    private fun sample(seconds: Long, watts: Double?, gap: Boolean = false) = BatterySample(
        timestampMillis = 1_700_000_000_000L + seconds * 1000L,
        elapsedRealtimeMillis = seconds * 1000L,
        watts = watts,
        gapBefore = gap,
    )

    @Test fun serialSamplesFormOneContinuousSegment() {
        val samples = listOf(
            sample(0, -2.0),
            sample(15, -3.0),
            sample(30, -4.0),
        )
        val series = buildChartSeries(samples, windowSeconds = 300, intervalSeconds = 15, absolute = true, maxPoints = 200)
        assertEquals(1, series.segments.size)
        assertEquals(3, series.segments[0].points.size)
        assertTrue(series.gaps.isEmpty())
        assertEquals(0.0, series.lower, 0.0)
        assertEquals(5.0, series.upper, 0.0)
        assertEquals(PowerUnit.WATTS, series.unit)
        assertTrue(series.segments[0].points.all { it.value >= 0.0 })
    }

    @Test fun sleepGapSplitsTheTraceAndIsReported() {
        val samples = listOf(
            sample(0, -2.0),
            sample(15, -3.0),
            sample(30, -4.0),
            sample(200, -5.0),
        )
        val series = buildChartSeries(samples, 300, 15, absolute = true, maxPoints = 200)
        assertEquals(2, series.segments.size)
        assertEquals(1, series.gaps.size)
        assertEquals(30_000L, series.gaps[0].startMillis)
        assertEquals(200_000L, series.gaps[0].endMillis)
        assertTrue(series.hasGaps)
    }

    @Test fun explicitGapFlagBreaksEvenAShortDelta() {
        val samples = listOf(
            sample(0, -2.0),
            sample(15, -3.0),
            sample(30, -4.0, gap = true),
        )
        val series = buildChartSeries(samples, 300, 15, absolute = false, maxPoints = 200)
        assertEquals(2, series.segments.size)
        assertEquals(1, series.gaps.size)
        assertEquals(-4.0, series.segments[1].points.single().value, 0.0)
    }

    @Test fun missingWattsIsNotZeroAndBreaksTheTrace() {
        val samples = listOf(
            sample(0, -2.0),
            sample(15, -3.0),
            sample(30, null),
            sample(45, -4.0),
            sample(60, -5.0),
        )
        val series = buildChartSeries(samples, 300, 15, absolute = true, maxPoints = 200)
        assertEquals(2, series.segments.size)
        assertEquals(1, series.missingValueCount)
        assertEquals(2, series.segments[0].points.size)
        assertEquals(2, series.segments[1].points.size)
        assertTrue(series.segments.flatMap { it.points }.none { it.value == 0.0 })
    }

    @Test fun windowOnlyIncludesVisibleSamples() {
        val samples = (0 until 20).map { sample(it * 15L, -1.0 - it) }
        val series = buildChartSeries(samples, windowSeconds = 60, intervalSeconds = 15, absolute = true, maxPoints = 200)
        assertEquals(225_000L, series.startMillis)
        assertEquals(285_000L, series.endMillis)
        val points = series.segments.flatMap { it.points }
        assertEquals(5, points.size)
        assertTrue(points.all { it.elapsedMillis in series.startMillis..series.endMillis })
        assertEquals(series.startMillis, points.first().elapsedMillis)
        assertEquals(series.endMillis, points.last().elapsedMillis)
    }

    @Test fun signedScaleKeepsZeroInsideTheAxis() {
        val samples = listOf(sample(0, -6.0), sample(15, 2.0))
        val series = buildChartSeries(samples, 300, 15, absolute = false, maxPoints = 200)
        assertTrue(series.lower <= 0.0)
        assertTrue(series.upper >= 0.0)
        assertEquals(-6.0, series.segments.single().points.first().value, 0.0)
        assertEquals(2.0, series.segments.single().points.last().value, 0.0)
    }

    @Test fun emptyStateHasNoFabricatedPoints() {
        val series = buildChartSeries(emptyList(), 300, 15, absolute = true, maxPoints = 200)
        assertTrue(series.isEmpty)
        assertFalse(series.hasGaps)
        assertEquals(0, series.plottedPointCount)
    }

    @Test fun decimationPreservesFirstLastAndExtrema() {
        val points = (0 until 1000).map { index ->
            ChartPoint(index * 1000L, if (index == 777) 50.0 else 1.0)
        }
        val segment = decimate(points, maxPoints = 100)
        assertTrue(segment.points.size <= 100)
        assertEquals(points.first(), segment.points.first())
        assertEquals(points.last(), segment.points.last())
        assertTrue(segment.points.any { it.value == 50.0 })
        // Chronological order is required for a line path.
        assertTrue(segment.points.zipWithNext().all { (a, b) -> a.elapsedMillis < b.elapsedMillis })
    }

    @Test fun decimationKeepsShortSegmentsUntouched() {
        val points = listOf(ChartPoint(0, 1.0), ChartPoint(1000, 3.0), ChartPoint(2000, 2.0))
        val segment = decimate(points, maxPoints = 100)
        assertEquals(points, segment.points)
    }

    @Test fun boundsAlwaysIncludeZero() {
        val absolute = computeBounds(listOf(4.0, 5.0), absolute = true)
        assertEquals(0.0, absolute.first, 0.0)
        assertTrue(absolute.second >= 5.0)

        val noData = computeBounds(emptyList(), absolute = true)
        assertEquals(0.0, noData.first, 0.0)
        assertTrue(noData.second > 0.0)

        val signed = computeBounds(listOf(-6.0, 2.0), absolute = false)
        assertTrue(signed.first <= -6.0)
        assertTrue(signed.second >= 2.0)
        assertTrue(signed.first < 0.0 && signed.second > 0.0)
    }

    @Test fun absoluteScaleHasNoOneWattFloor() {
        // The reported defect: every reading sat on a fixed 0–1 W axis.
        val bounds = computeBounds(listOf(0.00126), absolute = true)
        assertEquals(0.0, bounds.first, 0.0)
        assertEquals(0.005, bounds.second, 1e-12)
        assertTrue("a milliwatt window must not be floored to 1 W", bounds.second < 1.0)

        val empty = computeBounds(emptyList(), absolute = true)
        assertTrue(empty.second < 1.0)
        assertEquals(0.005, empty.second, 1e-12)
    }

    @Test fun milliwattSamplesGetMilliwattBoundsAndTicks() {
        val samples = listOf(sample(0, -0.00126), sample(15, -0.0012), sample(30, -0.0013))
        val series = buildChartSeries(samples, 300, 15, absolute = true, maxPoints = 200)
        assertEquals(PowerUnit.MILLIWATTS, series.unit)
        assertEquals(0.0, series.lower, 0.0)
        assertEquals(0.005, series.upper, 1e-12)
        assertEquals("5", Units.axisTick(series.upper, series.unit, absolute = true))
        assertEquals("2.5", Units.axisTick((series.lower + series.upper) / 2.0, series.unit, absolute = true))
        assertEquals("0", Units.axisTick(series.lower, series.unit, absolute = true))
    }

    @Test fun seriesUnitFollowsThePlottedMagnitude() {
        val watts = buildChartSeries(listOf(sample(0, -4.8)), 300, 15, absolute = true, maxPoints = 200)
        assertEquals(PowerUnit.WATTS, watts.unit)
        val milliwatts = buildChartSeries(listOf(sample(0, -0.0126)), 300, 15, absolute = true, maxPoints = 200)
        assertEquals(PowerUnit.MILLIWATTS, milliwatts.unit)
        // The 4 mW minimum span keeps even a 200 µW window on a readable mW axis.
        val microwatts = buildChartSeries(listOf(sample(0, -0.0002)), 300, 15, absolute = true, maxPoints = 200)
        assertEquals(PowerUnit.MILLIWATTS, microwatts.unit)
    }

    @Test fun chartAxisUnitMatchesTheSeriesWithoutMeasuringTheCanvas() {
        val samples = listOf(sample(0, -0.00126), sample(15, -0.0012))
        assertEquals(PowerUnit.MILLIWATTS, chartAxisUnit(samples, 300, absolute = true))
        assertEquals(
            buildChartSeries(samples, 300, 15, absolute = true, maxPoints = 200).unit,
            chartAxisUnit(samples, 300, absolute = true),
        )
        // Nothing recorded yet: the caption still names the honest default axis.
        assertEquals(PowerUnit.MILLIWATTS, chartAxisUnit(emptyList(), 300, absolute = true))
    }

    @Test fun signedBoundsHaveAReadableMinimumSpan() {
        val bounds = computeBounds(emptyList(), absolute = false)
        assertTrue(abs(bounds.second - bounds.first) >= MIN_BOUNDS_SPAN_WATTS)
        assertTrue(bounds.first <= 0.0 && bounds.second >= 0.0)
        // The span is a few milliwatts, not the old whole-watt minimum.
        assertTrue(abs(bounds.second - bounds.first) < 0.05)

        val tinyDischarge = computeBounds(listOf(-0.00126), absolute = false)
        assertTrue(tinyDischarge.first <= -0.00126)
        assertTrue(tinyDischarge.second >= 0.0)
        assertTrue(abs(tinyDischarge.second - tinyDischarge.first) >= MIN_BOUNDS_SPAN_WATTS)
    }

    @Test fun niceCeilIsStrictlyAboveAnExactBoundary() {
        assertEquals(8.0, niceCeil(7.8), 0.0)
        assertEquals(8.0, niceCeil(5.0), 0.0)
        assertEquals(5.0, niceCeil(4.8), 0.0)
        assertEquals(0.005, niceCeil(0.004), 1e-12)
        assertEquals(0.0, niceCeil(0.0), 0.0)
        assertEquals(0.0, niceCeil(-3.0), 0.0)
    }

    @Test fun wallClockLabelsTrackTheNewestSample() {
        val samples = listOf(sample(0, -2.0), sample(60, -3.0))
        val series = buildChartSeries(samples, 30, 15, absolute = true, maxPoints = 200)
        assertEquals(1_700_000_030_000L, series.startWallMillis)
        assertEquals(1_700_000_060_000L, series.endWallMillis)
    }
}
