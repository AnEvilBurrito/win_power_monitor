package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.PowerUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Port of the desktop distribution rules (`src/histogram.rs`, `f696503`).
 *
 * The 60-reading fixture below is copied verbatim from the Rust test suite so both
 * programs are pinned to the same oracle: if a constant, the step ladder or the
 * counting rule drifts, this file stops agreeing with the desktop.
 */
class PowerDistributionTest {

    /** The 60 discharge readings from a real five-minute session (5 s polling). */
    private val sessionWatts = listOf(
        3.728, 3.743, 3.821, 3.840, 3.841, 3.841, 3.889, 3.987, 4.035, 4.049, 4.052, 4.066,
        4.084, 4.100, 4.112, 4.149, 4.164, 4.225, 4.259, 4.324, 4.370, 4.403, 4.498, 4.627,
        4.664, 4.743, 4.793, 4.803, 4.821, 4.821, 4.883, 4.890, 4.966, 4.997, 5.044, 5.095,
        5.141, 5.175, 5.189, 5.189, 5.190, 5.256, 5.306, 5.335, 5.478, 5.496, 5.561, 5.657,
        5.747, 6.027, 6.223, 6.318, 6.492, 6.595, 6.810, 6.859, 6.979, 7.848, 8.089, 9.292,
    )

    private fun sample(
        elapsedMillis: Long,
        watts: Double?,
        flow: FlowState = FlowState.DISCHARGING,
        gapBefore: Boolean = false,
        source: CurrentSource = CurrentSource.INSTANT,
        calibrated: Boolean = true,
    ) = BatterySample(
        timestampMillis = elapsedMillis,
        elapsedRealtimeMillis = elapsedMillis,
        amps = watts?.let { -abs(it) / 3.8 },
        watts = watts,
        volts = 3.8,
        percentage = 60,
        flow = flow,
        currentCalibrated = calibrated,
        currentSource = source,
        gapBefore = gapBefore,
        chargeCounterMicroampHours = elapsedMillis,
    )

    private fun session(extra: List<BatterySample> = emptyList()): List<BatterySample> {
        val recorded = sessionWatts.mapIndexed { index, watts ->
            sample(index * 5_000L, -watts)
        }
        return recorded + extra
    }

    private fun distributionOf(samples: List<BatterySample>) =
        buildDistribution(distributionPopulation(samples))

    @Test
    fun percentagesDivideByEveryCountedSample() {
        val distribution = distributionOf(session())
        assertNotNull(distribution)
        distribution!!
        assertEquals(60, distribution.n)
        assertEquals(distribution.n, distribution.counts.sum())
        assertEquals(100.0, distribution.counts.indices.sumOf { distribution.percent(it) }, 1e-9)
    }

    @Test
    fun theReferenceSessionLandsOnReadableBins() {
        val distribution = distributionOf(session())!!
        assertEquals(DistributionScale.LINEAR, distribution.scale)
        assertEquals(9, distribution.bins())
        assertEquals(0.75, distribution.step(), 1e-9)
        assertEquals(3.0, distribution.edges.first(), 1e-9)
        assertEquals(listOf(2, 21, 18, 8, 5, 3, 2, 0, 1), distribution.counts)
        // The empty eighth bin is data, not a rendering bug.
        assertEquals(0, distribution.counts[7])
        assertEquals(35.0, distribution.peakPercent(), 1e-9)
        assertEquals(40.0, distribution.topPercent, 1e-9)
        val unit = distribution.axisUnit()
        assertEquals(PowerUnit.WATTS, unit)
        // A 0.75 W step needs two decimals; padding is dropped, not rounded away.
        assertEquals("3", distribution.binLabel(0, unit))
        assertEquals("3.75", distribution.binLabel(1, unit))
        assertEquals("4.5", distribution.binLabel(2, unit))
        assertEquals("0.75 W", distribution.widthLabel(unit))
    }

    @Test
    fun aTwoAndAHalfTimesSpreadStaysLinear() {
        val distribution = distributionOf(session())!!
        assertEquals(DistributionScale.LINEAR, distribution.scale)
        distribution.edges.forEach { assertTrue(it.isFinite()) }
    }

    @Test
    fun binsAreFewEnoughToReadAndEdgesAreEvenlySpaced() {
        val distribution = distributionOf(session())!!
        assertTrue(distribution.bins() in DISTRIBUTION_MIN_BINS..DISTRIBUTION_MAX_BINS)
        val step = distribution.step()
        distribution.edges.zipWithNext().forEach { (lower, upper) ->
            assertEquals("linear axis must have one step", step, upper - lower, 1e-9)
        }
        assertEquals(distribution.n, distribution.counts.sum())
    }

    @Test
    fun aLogAxisNeedsADecadeOfSupport() {
        val wide = (0 until 60).map { index ->
            sample(index * 5_000L, -(2.0 * Math.pow(1.08, index.toDouble())))
        }
        val distribution = distributionOf(wide)!!
        assertEquals(DistributionScale.LOG, distribution.scale)
        assertEquals(10, distribution.bins())
        val ratio = distribution.edges[1] / distribution.edges[0]
        assertEquals(1.5849, ratio, 1e-3)
        assertEquals(ratio, distribution.edges[2] / distribution.edges[1], 1e-9)
        assertEquals(listOf(3, 6, 6, 6, 6, 6, 6, 6, 6, 9), distribution.counts)
        assertEquals(15.0, distribution.peakPercent(), 1e-9)
        assertEquals(15.0, distribution.topPercent, 1e-9)
        assertEquals("log-spaced", distribution.widthLabel(distribution.axisUnit()))
    }

    @Test
    fun chargingReadingsAreExcludedRatherThanBinned() {
        val charging = listOf(
            sample(400_000L, 9.5, FlowState.CHARGING),
            sample(405_000L, 9.6, FlowState.CHARGING),
            sample(410_000L, 0.0, FlowState.IDLE),
        )
        val population = distributionPopulation(session(charging))
        assertEquals(60, population.size)
        assertEquals(3, population.excluded)
        assertEquals(63, population.size + population.excluded)
        // No charging magnitude reaches a bin: the last edge still covers the
        // discharge peak only.
        val distribution = buildDistribution(population)!!
        assertTrue(distribution.edges.last() >= sessionWatts.max())
    }

    @Test
    fun gapsAreExcludedAndAnInterpretationChangeStartsANewPopulation() {
        val withGap = session(listOf(sample(400_000L, 4.2, gapBefore = true)))
        val gapPopulation = distributionPopulation(withGap)
        assertEquals(60, gapPopulation.size)
        assertEquals(1, gapPopulation.excluded)

        val foreign = session(
            listOf(sample(400_000L, 4.2, source = CurrentSource.AVERAGE, calibrated = false)),
        )
        val foreignPopulation = distributionPopulation(foreign)
        assertEquals(1, foreignPopulation.size)
        assertEquals(60, foreignPopulation.excluded)
        // The newest reading is the reference, so only its own interpretation counts.
        assertEquals(4.2, foreignPopulation.values.single(), 1e-9)
    }

    @Test
    fun coarseMetersForceStepsThatAreMultiplesOfTheirIncrement() {
        val coarse = (0 until 60).map { index ->
            sample(index * 5_000L, -(10.0 + (index % 7) / 10.0))
        }
        val distribution = distributionOf(coarse)!!
        val quanta = distribution.step() / 0.1
        assertEquals(quanta, Math.round(quanta).toDouble(), 1e-6)
    }

    @Test
    fun identicalReadingsStillGetAReadableAxis() {
        val flat = (0 until 20).map { index -> sample(index * 5_000L, -5.0) }
        val distribution = distributionOf(flat)!!
        assertEquals(20, distribution.counts.sum())
        assertTrue(distribution.edges.last() > 5.0)
        assertTrue(distribution.edges.first() <= 5.0)
    }

    @Test
    fun aShortRunReportsNoDistributionYet() {
        val short = (0 until DISTRIBUTION_MIN_SAMPLES - 1).map { index ->
            sample(index * 5_000L, -(4.0 + index * 0.1))
        }
        assertNull(distributionOf(short))
        val population = distributionPopulation(short)
        assertEquals(DISTRIBUTION_MIN_SAMPLES - 1, population.size)
        assertEquals(
            listOf("Distribution \u00b7 collecting 7/8 readings"),
            distributionHeaderTiers(null, population),
        )
        assertEquals("Needs 8 discharge readings", distributionPlaceholder(null, population))
    }

    @Test
    fun zeroAndNonFiniteWattsAreDropped() {
        val broken = session(
            listOf(
                sample(400_000L, 0.0),
                sample(405_000L, Double.NaN),
                sample(410_000L, Double.POSITIVE_INFINITY),
                sample(415_000L, null),
            ),
        )
        val population = distributionPopulation(broken)
        assertEquals(60, population.size)
        assertEquals(4, population.excluded)
        val distribution = buildDistribution(population)!!
        assertEquals(60, distribution.counts.sum())
        distribution.counts.forEach { assertTrue(it >= 0) }
    }

    @Test
    fun niceCeilingIsNeverBelowThePeak() {
        assertEquals(40.0, ceiling(35.0), 1e-9)
        assertEquals(40.0, ceiling(40.0), 1e-9)
        assertEquals(50.0, ceiling(41.0), 1e-9)
        assertEquals(100.0, ceiling(100.0), 1e-9)
        assertEquals(100.0, ceiling(140.0), 1e-9)
        val distribution = distributionOf(session())!!
        distribution.counts.indices.forEach {
            assertTrue(distribution.percent(it) <= distribution.topPercent)
        }
    }

    @Test
    fun widthLabelFollowsTheDisplayUnit() {
        val milliwatts = (0 until 60).map { index ->
            sample(index * 5_000L, -(1.2e-3 + index * 3.7e-5))
        }
        val distribution = distributionOf(milliwatts)!!
        assertEquals(PowerUnit.MILLIWATTS, distribution.axisUnit())
        assertTrue(distribution.widthLabel(distribution.axisUnit()).endsWith("mW"))
        // Milliwatt edges keep zero decimals: no "1000 mW"-style over-precision.
        distribution.binLabel(0, distribution.axisUnit()).toDouble().let { assertTrue(it >= 0.0) }

        val watts = distributionOf(session())!!
        assertEquals(PowerUnit.WATTS, watts.axisUnit())
        assertTrue(watts.widthLabel(PowerUnit.WATTS).endsWith("W"))
        assertTrue(!watts.widthLabel(PowerUnit.WATTS).contains(".00"))
    }

    @Test
    fun windowScopeKeepsOnlyTheVisibleSpan() {
        val samples = session()
        val latest = samples.last()
        // Two readings older than a five-minute window never reach n. They are put
        // *before* the session so the newest sample stays the window reference, the
        // same ordering the repository publishes.
        val outside = listOf(
            sample(latest.elapsedRealtimeMillis - 400_000L, -4.0),
            sample(latest.elapsedRealtimeMillis - 310_000L, -4.1),
        )
        val scoped = distributionWindow(outside + samples, 300)
        assertEquals(60, scoped.size)
        val population = distributionPopulation(scoped)
        assertEquals(scoped.size, population.size + population.excluded)
    }

    @Test
    fun distributionDoesNotDependOnTheAbsoluteToggle() {
        // Discharge magnitudes: the signed and absolute copies of the same readings
        // must bin identically, because a bin edge is a positive wattage.
        val signed = distributionOf(session())!!
        val absoluteCopies = session().map { it.copy(watts = it.watts?.let { value -> abs(value) }) }
        val absolute = distributionOf(absoluteCopies)!!
        assertEquals(signed.edges, absolute.edges)
        assertEquals(signed.counts, absolute.counts)
    }

    @Test
    fun headerTiersDropTheExclusionCountBeforeTheNumbers() {
        val population = distributionPopulation(
            session(listOf(sample(400_000L, 9.5, FlowState.CHARGING))),
        )
        val tiers = distributionHeaderTiers(buildDistribution(population), population)
        assertEquals(4, tiers.size)
        assertEquals("Distribution \u00b7 n=60 \u00b7 9 bins \u00b7 0.75 W \u00b7 1 excluded", tiers[0])
        assertEquals("Distribution \u00b7 n=60 \u00b7 9 bins \u00b7 0.75 W", tiers[1])
        assertEquals("9 bins \u00b7 0.75 W", tiers[2])
        assertEquals("Distribution \u00b7 n=60", tiers[3])

        val chargingOnly = distributionPopulation(
            (0 until 6).map { index -> sample(index * 5_000L, 9.0, FlowState.CHARGING) },
        )
        assertEquals(
            listOf("Distribution \u00b7 no discharge readings yet (6 excluded)"),
            distributionHeaderTiers(null, chargingOnly),
        )
        assertEquals("Charging readings are excluded", distributionPlaceholder(null, chargingOnly))
    }

    @Test
    fun accessibilityDescriptionStatesTheSameNumbersAsTheHeader() {
        val population = distributionPopulation(session())
        val distribution = buildDistribution(population)!!
        assertEquals(
            "Power distribution: 60 discharge readings, 9 bins, 0.75 W, peak 35 percent",
            distribution.accessibilityDescription(),
        )
    }

    @Test
    fun labelsThinWithThePlotWidthAndNeverCollide() {
        assertEquals(listOf(0, 4, 8), distributionLabelIndices(9, 150))
        assertEquals((0 until 9).toList(), distributionLabelIndices(9, 880))
        assertEquals(listOf(0), distributionLabelIndices(9, 10))
        assertEquals(emptyList<Int>(), distributionLabelIndices(0, 400))
        // Always ascending and in range, whatever the width.
        for (width in listOf(40, 88, 120, 298, 400, 792)) {
            val indices = distributionLabelIndices(9, width)
            assertEquals(indices.sorted(), indices)
            assertTrue(indices.all { it in 0 until 9 })
            assertTrue(indices.isNotEmpty())
        }
    }

    private fun ceiling(peak: Double): Double =
        DISTRIBUTION_NICE_PERCENT.firstOrNull { it >= peak } ?: 100.0

    @Test
    fun aBarReportsItsSpanCountAndShare() {
        val distribution = distributionOf(session())!!
        val unit = distribution.axisUnit()
        assertEquals("3.75\u20134.5 W", distribution.binRangeLabel(1, unit))
        assertEquals("3.75\u20134.5 W \u00b7 21 readings \u00b7 35%", distribution.binDetail(1, unit))
        // Singular, so a one-reading bar never says "1 readings".
        assertEquals("9\u20139.75 W \u00b7 1 reading \u00b7 2%", distribution.binDetail(8, unit))
        // An empty bar is still readable: the hole in the silhouette is data.
        assertEquals("8.25\u20139 W \u00b7 0 readings \u00b7 0%", distribution.binDetail(7, unit))
    }

    @Test
    fun aTapMapsToTheBarUnderIt() {
        assertEquals(0, binIndexAt(0.0f, 9))
        assertEquals(0, binIndexAt(0.11f, 9))
        assertEquals(4, binIndexAt(0.5f, 9))
        assertEquals(8, binIndexAt(1.0f, 9))
        assertEquals(8, binIndexAt(1.4f, 9))
        assertEquals(0, binIndexAt(-0.2f, 9))
        assertNull(binIndexAt(0.5f, 0))
        // Every bar is reachable, and never two indices for one position.
        for (index in 0 until 14) {
            assertEquals(index, binIndexAt((index + 0.5f) / 14f, 14))
        }
    }
}
