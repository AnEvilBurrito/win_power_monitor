package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.Units
import io.github.anevilburrito.wpm.model.gapThresholdMillis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One plotted value in monotonic (elapsedRealtime) coordinates. */
data class ChartPoint(val elapsedMillis: Long, val value: Double)

/** A contiguous run of samples; a gap never has a line drawn across it. */
data class ChartSegment(val points: List<ChartPoint>)

/** Blank region between two samples that must not be connected or counted as zero. */
data class ChartGap(val startMillis: Long, val endMillis: Long)

/**
 * Immutable, already-decimated plot description. Values are transformed for the
 * selected scale (absolute or signed), so drawing only maps them to pixels.
 *
 * [unit] is the display unit implied by the final bounds, so the axis labels and
 * the chart caption both read `mW` on a phone that only moves milliwatts.
 */
data class ChartSeries(
    val segments: List<ChartSegment> = emptyList(),
    val gaps: List<ChartGap> = emptyList(),
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val startWallMillis: Long = 0L,
    val endWallMillis: Long = 0L,
    val lower: Double = 0.0,
    val upper: Double = DEFAULT_UPPER_WATTS,
    val unit: PowerUnit = PowerUnit.WATTS,
    val windowSampleCount: Int = 0,
    val plottedPointCount: Int = 0,
    val missingValueCount: Int = 0,
) {
    val isEmpty: Boolean get() = segments.isEmpty()
    val hasGaps: Boolean get() = gaps.isNotEmpty()
    val spanMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/**
 * Builds a bounded plot for [windowSeconds] ending at the newest sample.
 *
 * Points keep monotonic elapsedRealtime for x spacing; the wall-clock labels are
 * derived from the newest sample so rotating a device or changing the wall clock
 * cannot move the history. Missing watts and discontinuities split the trace.
 */
fun buildChartSeries(
    samples: List<BatterySample>,
    windowSeconds: Int,
    intervalSeconds: Int,
    absolute: Boolean,
    maxPoints: Int,
): ChartSeries {
    val latest = samples.lastOrNull() ?: return ChartSeries()
    val endMillis = latest.elapsedRealtimeMillis
    val startMillis = endMillis - windowSeconds.coerceAtLeast(1) * 1000L
    val ordered = samples
        .filter { it.elapsedRealtimeMillis in startMillis..endMillis }
        .sortedBy { it.elapsedRealtimeMillis }

    val segments = ArrayList<MutableList<ChartPoint>>()
    val gaps = ArrayList<ChartGap>()
    val values = ArrayList<Double>(ordered.size)
    var current: MutableList<ChartPoint>? = null
    var previousSample: BatterySample? = null
    var missing = 0
    val gapThreshold = gapThresholdMillis(intervalSeconds)

    ordered.forEach { sample ->
        val raw = sample.watts?.takeIf { it.isFinite() }
        val value = raw?.let { if (absolute) abs(it) else it }
        if (value == null) {
            // A missing property is not zero watts: it breaks the trace.
            missing++
            current = null
            previousSample = null
            return@forEach
        }
        val before = previousSample
        val deltaMillis = if (before == null) -1L else sample.elapsedRealtimeMillis - before.elapsedRealtimeMillis
        if (before != null && (deltaMillis <= 0L || sample.gapBefore || deltaMillis > gapThreshold)) {
            if (deltaMillis > 0L && (sample.gapBefore || deltaMillis > gapThreshold)) {
                gaps += ChartGap(before.elapsedRealtimeMillis, sample.elapsedRealtimeMillis)
            }
            current = null
        }
        val segment = current ?: ArrayList<ChartPoint>().also {
            current = it
            segments += it
        }
        val point = ChartPoint(sample.elapsedRealtimeMillis, value)
        segment += point
        values += value
        previousSample = sample
    }

    val bounds = computeBounds(values, absolute)
    val budget = maxPoints.coerceIn(8, 4096)
    val decimated = segments.map { decimate(it, budget) }
    val plotted = decimated.sumOf { it.points.size }
    val latestWall = latest.timestampMillis
    return ChartSeries(
        segments = decimated,
        gaps = gaps,
        startMillis = startMillis,
        endMillis = endMillis,
        startWallMillis = latestWall - (endMillis - startMillis),
        endWallMillis = latestWall,
        lower = bounds.first,
        upper = bounds.second,
        unit = Units.axisUnit(bounds.first, bounds.second),
        windowSampleCount = ordered.size,
        plottedPointCount = plotted,
        missingValueCount = missing,
    )
}

/**
 * Axis unit for the visible window without decimating anything. The chart header
 * uses it to label the axis (`Power / mW`) before the canvas is measured,
 * so the label and the drawn ticks always agree.
 */
fun chartAxisUnit(
    samples: List<BatterySample>,
    windowSeconds: Int,
    absolute: Boolean,
): PowerUnit {
    val bounds = computeBounds(windowWatts(samples, windowSeconds, absolute), absolute)
    return Units.axisUnit(bounds.first, bounds.second)
}

/** Visible, finite, scale-transformed watts in the window. */
private fun windowWatts(samples: List<BatterySample>, windowSeconds: Int, absolute: Boolean): List<Double> {
    val latest = samples.lastOrNull() ?: return emptyList()
    val endMillis = latest.elapsedRealtimeMillis
    val startMillis = endMillis - windowSeconds.coerceAtLeast(1) * 1000L
    return samples
        .filter { it.elapsedRealtimeMillis in startMillis..endMillis }
        .sortedBy { it.elapsedRealtimeMillis }
        .mapNotNull { sample -> sample.watts?.takeIf { it.isFinite() } }
        .map { value -> if (absolute) abs(value) else value }
}

/**
 * Reduces one segment to at most [maxPoints] points while preserving the first and
 * last point of the segment and the minimum/maximum of every bucket. Callers pass
 * whole segments, so gap boundaries are always kept.
 */
fun decimate(points: List<ChartPoint>, maxPoints: Int): ChartSegment {
    if (maxPoints < 4 || points.size <= maxPoints) return ChartSegment(points)
    val bucketBudget = max(1, maxPoints / 4)
    val bucketSize = max(1, ceil(points.size.toDouble() / bucketBudget.toDouble()).toInt())
    val out = ArrayList<ChartPoint>(min(points.size, maxPoints + 4))
    var index = 0
    while (index < points.size) {
        val endExclusive = min(points.size, index + bucketSize)
        appendBucketExtrema(points, index, endExclusive, out)
        index = endExclusive
    }
    return ChartSegment(out)
}

private fun appendBucketExtrema(
    points: List<ChartPoint>,
    from: Int,
    toExclusive: Int,
    out: MutableList<ChartPoint>,
) {
    var minIndex = from
    var maxIndex = from
    for (i in from until toExclusive) {
        if (points[i].value < points[minIndex].value) minIndex = i
        if (points[i].value > points[maxIndex].value) maxIndex = i
    }
    val candidates = intArrayOf(from, minIndex, maxIndex, toExclusive - 1).sorted()
    var last = -1
    candidates.forEach { i ->
        if (i != last) {
            out += points[i]
            last = i
        }
    }
}

/**
 * Y bounds that always include zero. Absolute scale is never negative; signed
 * scale keeps negative discharge below the zero baseline.
 *
 * There is deliberately **no** fixed 1 W floor: a phone that idles at 1.26 mW
 * gets a 5 mW axis with mW ticks instead of a flat line pinned to a 0–1 W frame.
 * [MIN_BOUNDS_SPAN_WATTS] (4 mW) keeps a near-zero window readable instead.
 */
fun computeBounds(values: List<Double>, absolute: Boolean): Pair<Double, Double> {
    var lower = 0.0
    var upper = 0.0
    values.forEach { value ->
        if (!value.isFinite()) return@forEach
        if (value < lower) lower = value
        if (value > upper) upper = value
    }
    if (absolute) {
        return 0.0 to niceCeil(max(upper, MIN_BOUNDS_SPAN_WATTS))
    }
    if (upper - lower < MIN_BOUNDS_SPAN_WATTS) {
        val center = (upper + lower) / 2.0
        lower = min(lower, center - MIN_BOUNDS_SPAN_WATTS / 2.0)
        upper = max(upper, center + MIN_BOUNDS_SPAN_WATTS / 2.0)
    }
    val signedLower = -niceCeil(-lower)
    return (if (signedLower == 0.0) 0.0 else signedLower) to niceCeil(upper)
}

/**
 * Smallest "readable" value at or above [value] using 1/2/2.5/4/5/8 × 10^n.
 * Strictly greater than an exact boundary so a peak never sits on the frame.
 */
fun niceCeil(value: Double): Double {
    if (!value.isFinite() || value <= 0.0) return 0.0
    val exponent = floor(log10(value)).toInt()
    val power = 10.0.pow(exponent.toDouble())
    val normalized = value / power
    val factor = NICE_FACTORS.firstOrNull { it > normalized + 1e-9 } ?: 10.0
    return factor * power
}

/** Smallest axis span a near-zero window is padded to: 4 mW, not 1 W. */
const val MIN_BOUNDS_SPAN_WATTS = 0.004

private val NICE_FACTORS = doubleArrayOf(1.0, 2.0, 2.5, 4.0, 5.0, 8.0, 10.0)

/** Bounds of an empty, not-yet-measured plot (0 .. 5 mW). Declared after the factors it uses. */
private val DEFAULT_UPPER_WATTS = niceCeil(MIN_BOUNDS_SPAN_WATTS)
