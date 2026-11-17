package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Distribution of the discharge readings currently on screen.
 *
 * The line chart answers "what happened when"; this answers "where does the time
 * go", which is the same samples counted into watt bins. It is a direct port of the
 * desktop implementation (`src/histogram.rs`, added in `f696503`) so both
 * programs describe one measurement the same way, with the Android-specific
 * population rules noted in [distributionPopulation].
 *
 * Charging is excluded on purpose: its wattage is roughly constant while the pack
 * fills, so it would add a spike and no information.
 *
 * Android-free (no Compose, no `android.*`) so every rule below is unit tested
 * rather than inferred from a composable, exactly like [ChartModel].
 */

/** Below this many readings the shape is noise, so the caller keeps the line chart. */
const val DISTRIBUTION_MIN_SAMPLES = 8

/** Fewer bins hide structure; more than this turn single samples into full bars. */
const val DISTRIBUTION_MIN_BINS = 6
const val DISTRIBUTION_MAX_BINS = 14

/**
 * A log axis only earns its complexity once the support spans a decade. Real
 * battery sessions span two or three times at most, and compressing those into a
 * few columns costs more than it explains.
 */
const val DISTRIBUTION_LOG_SUPPORT_RATIO = 10.0

/**
 * Meters report in fixed increments; bins must be multiples of that increment or
 * the histogram becomes a comb of one-value spikes.
 */
const val DISTRIBUTION_QUANTUM_DECIMALS = 3

/** Readable percentage ceilings for the value axis. */
val DISTRIBUTION_NICE_PERCENT = doubleArrayOf(
    5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 75.0, 100.0,
)

/**
 * Readable steps: 1, 1.5, 2, 2.5, 3, 4, 5, 7.5, x10^k. The 7.5 entry keeps a
 * Freedman-Diaconis width like 0.68 W at 0.75 W instead of jumping to 1 W and
 * flattening the distribution into seven columns.
 */
val DISTRIBUTION_STEP_LADDER = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0)

/** Decade steps the log axis may choose from. */
private val LOG_STEP_LADDER = doubleArrayOf(0.05, 0.1, 0.2, 0.25, 0.5, 1.0)

/** How many coarser/finer steps the linear walk may try. */
private const val STEP_WALK_LIMIT = 16

/** Labels never crowd closer than this along the axis. */
const val DISTRIBUTION_LABEL_SLOT_DP = 44

enum class DistributionScale { LINEAR, LOG }

/**
 * Readings that entered the distribution, plus the count the filters rejected so
 * the header can be honest about `n`.
 */
data class DistributionPopulation(
    val values: List<Double> = emptyList(),
    val excluded: Int = 0,
) {
    val size: Int get() = values.size
    val isEmpty: Boolean get() = values.isEmpty()
}

/** Bins ready to draw: percentages per watt bin. */
data class PowerDistribution(
    val edges: List<Double>,
    val counts: List<Int>,
    val scale: DistributionScale,
    val n: Int,
    /** Readable ceiling for the percentage axis. */
    val topPercent: Double,
) {
    fun bins(): Int = counts.size

    /** Count of one bin as a percentage of every counted sample. */
    fun percent(index: Int): Double = 100.0 * counts[index] / n

    /** Largest percentage actually present, for axis ceilings and tests. */
    fun peakPercent(): Double = counts.indices.maxOf { percent(it) }

    /** Bin width in watts. Meaningless on a log axis; use [widthLabel] there. */
    fun step(): Double = edges[1] - edges[0]

    /** Unit for the whole histogram, chosen once from the final edges. */
    fun axisUnit(): PowerUnit = Units.axisUnit(edges.first(), edges.last())

    /** Tick text for a lower bin edge, with the precision the step actually needs. */
    fun binLabel(index: Int, unit: PowerUnit): String {
        val displayStep = step() * unit.perWatt
        return trimZeros(fixed(edges[index] * unit.perWatt, decimals(displayStep)))
    }

    /** Bin width as text for the header: `0.75 W`, `250 mW`, or `log-spaced`. */
    fun widthLabel(unit: PowerUnit): String = when (scale) {
        DistributionScale.LINEAR -> {
            val displayStep = step() * unit.perWatt
            trimZeros(fixed(displayStep, decimals(displayStep))) + " " + unit.suffix
        }
        DistributionScale.LOG -> "log-spaced"
    }

    /** One bin's wattage span, e.g. `4.5-5.25 W`; both edges share the step precision. */
    fun binRangeLabel(index: Int, unit: PowerUnit): String =
        "${binLabel(index, unit)}\u2013${binLabel(index + 1, unit)} ${unit.suffix}"

    /**
     * Everything one bar means, in one line: its span, how many readings landed in
     * it and what share of the counted population that is.
     *
     * This is the tooltip text *and* the accessible description of a selected bar,
     * so the detail is never reachable only by touching a target too small to hit.
     */
    fun binDetail(index: Int, unit: PowerUnit): String {
        val readings = counts[index]
        val noun = if (readings == 1) "reading" else "readings"
        return "${binRangeLabel(index, unit)} \u00b7 $readings $noun \u00b7 ${percentLabel(percent(index))}"
    }

    /**
     * One accessible sentence for the whole plot. Every number is also drawn as
     * text, so this is a summary rather than the only way to read the chart.
     */
    fun accessibilityDescription(): String {
        val unit = axisUnit()
        return "Power distribution: $n discharge readings, ${bins()} bins, " +
            "${widthLabel(unit)}, peak ${peakPercent().roundToInt()} percent"
    }
}

/**
 * Splits the visible samples into binned values and exclusions.
 *
 * A reading is counted when it is not preceded by a gap, describes a discharge with
 * a finite non-zero magnitude, and was decoded by the same interpretation as the
 * newest reading (the Android analogue of the desktop's meter-identity rule: a
 * battery flow and a differently-calibrated instantaneous reading are not the same
 * quantity). Charging, idle and unknown flows never enter, and the binned value is
 * the discharge **magnitude**, so a bin edge is a positive wattage in either display
 * scale.
 *
 * Callers pass samples already scoped to the visible window
 * (`DashboardScope.liveSamples`), so a frozen view bins the frozen snapshot.
 */
fun distributionPopulation(samples: List<BatterySample>): DistributionPopulation {
    val reference = samples.lastOrNull() ?: return DistributionPopulation()
    val values = ArrayList<Double>(samples.size)
    var excluded = 0
    samples.forEach { sample ->
        val magnitude = sample.watts?.let { abs(it) }?.takeIf { it.isFinite() && it > 0.0 }
        val sameMeter = !sample.gapBefore &&
            sample.flow == FlowState.DISCHARGING &&
            sample.currentSource == reference.currentSource &&
            sample.currentCalibrated == reference.currentCalibrated
        if (magnitude != null && sameMeter) values += magnitude else excluded += 1
    }
    return DistributionPopulation(values = values, excluded = excluded)
}

/**
 * Samples inside `[newest - windowSeconds, newest]`, scoped exactly like
 * [buildChartSeries] so the distribution and the line chart never disagree about
 * which readings are visible.
 */
fun distributionWindow(samples: List<BatterySample>, windowSeconds: Int): List<BatterySample> {
    val latest = samples.lastOrNull() ?: return emptyList()
    val endMillis = latest.elapsedRealtimeMillis
    val startMillis = endMillis - windowSeconds.coerceAtLeast(1) * 1000L
    return samples.filter { it.elapsedRealtimeMillis in startMillis..endMillis }
}

/**
 * Builds the distribution, or reports that there is not enough data yet.
 *
 * `null` means "no bars": the caller shows the collecting/charging placeholder
 * instead of fabricating an empty axis.
 */
fun buildDistribution(population: DistributionPopulation): PowerDistribution? {
    if (population.size < DISTRIBUTION_MIN_SAMPLES) return null
    val values = population.values.filter { it.isFinite() && it > 0.0 }.sorted()
    if (values.size < DISTRIBUTION_MIN_SAMPLES) return null
    val n = values.size
    val quantum = quantum(values)
    val low = values.first()
    val high = values.last()
    val useLog = low > 0.0 && high / low >= DISTRIBUTION_LOG_SUPPORT_RATIO
    val (edges, scale) = if (useLog) logEdges(low, high) else linearEdges(values, low, high, quantum)
    val counts = count(values, edges, scale)
    val peak = counts.indices.maxOf { 100.0 * counts[it] / n }
    val topPercent = DISTRIBUTION_NICE_PERCENT.firstOrNull { it >= peak } ?: 100.0
    return PowerDistribution(
        edges = edges,
        counts = counts,
        scale = scale,
        n = n,
        topPercent = topPercent,
    )
}

/**
 * Header tiers, longest first.
 *
 * The caller draws the first tier that fits its measured slot. The exclusion count
 * is dropped first, then the `Distribution` prefix: `n` and the bin width are the
 * numbers that make the shape honest, and they survive every tier. This is the
 * Android form of the desktop's column-count rule, which drops the exclusion count
 * on a narrow terminal.
 */
fun distributionHeaderTiers(
    distribution: PowerDistribution?,
    population: DistributionPopulation,
): List<String> {
    if (distribution != null) {
        val unit = distribution.axisUnit()
        val width = distribution.widthLabel(unit)
        val counted = "Distribution \u00b7 n=${distribution.n} \u00b7 ${distribution.bins()} bins \u00b7 $width"
        return listOf(
            "$counted \u00b7 ${population.excluded} excluded",
            counted,
            "${distribution.bins()} bins \u00b7 $width",
            "Distribution \u00b7 n=${distribution.n}",
        )
    }
    if (population.excluded > 0) {
        return listOf(
            "Distribution \u00b7 no discharge readings yet (${population.excluded} excluded)",
        )
    }
    return listOf(
        "Distribution \u00b7 collecting ${population.size}/$DISTRIBUTION_MIN_SAMPLES readings",
    )
}

/** Text shown in the empty plot area; null once there are bars to draw. */
fun distributionPlaceholder(
    distribution: PowerDistribution?,
    population: DistributionPopulation,
): String? = when {
    distribution != null -> null
    population.excluded > 0 -> "Charging readings are excluded"
    else -> "Needs $DISTRIBUTION_MIN_SAMPLES discharge readings"
}

/** Indices of the bin edges that get a label at [plotWidthDp]. */fun distributionLabelIndices(binCount: Int, plotWidthDp: Int): List<Int> {
    if (binCount <= 0) return emptyList()
    val shown = (plotWidthDp / DISTRIBUTION_LABEL_SLOT_DP).coerceIn(1, binCount)
    if (shown == 1) return listOf(0)
    return (0 until shown).map { slot ->
        val index = (slot.toDouble() * (binCount - 1) / (shown - 1)).roundToInt()
        index.coerceIn(0, binCount - 1)
    }.distinct()
}

/** Freedman-Diaconis width snapped to a readable step and the meter's increment. */
private fun linearEdges(
    values: List<Double>,
    low: Double,
    high: Double,
    quantum: Double,
): Pair<List<Double>, DistributionScale> {
    val n = values.size.toDouble()
    val iqr = quantile(values, 0.75) - quantile(values, 0.25)
    val freedman = 2.0 * iqr * n.pow(-1.0 / 3.0)
    val fallback = (high - low) / 9.0
    // Ignore a degenerate IQR (a run of repeats gives zero) rather than dividing by it.
    val target = if (freedman.isFinite() && freedman > 0.0) freedman else fallback
    var step = niceStep(max(target, quantum))
    // Walk the ladder until the bin count is readable: coarser when single samples
    // would fill whole bars, finer when the data would sit in two columns.
    for (iteration in 0 until STEP_WALK_LIMIT) {
        val bins = binCount(low, high, step)
        if (bins > DISTRIBUTION_MAX_BINS) {
            step = rescale(step, 1)
        } else if (bins < DISTRIBUTION_MIN_BINS) {
            val finer = rescale(step, -1)
            if (finer + 1e-9 < quantum) break
            step = finer
        } else {
            break
        }
    }
    if (step < quantum) {
        step = quantum
    } else {
        // A meter that reports in 0.1 W steps cannot have 0.25 W bins without
        // leaving some of them permanently empty.
        step = round(step / quantum) * quantum
        if (step <= 0.0) step = quantum
    }
    // The start edge is rounded down so the first bar is never half-empty.
    val start = floor(low / step) * step
    val bins = binCount(start, high, step).coerceIn(DISTRIBUTION_MIN_BINS, DISTRIBUTION_MAX_BINS)
    return edgesFrom(start, step, bins) to DistributionScale.LINEAR
}

private fun logEdges(low: Double, high: Double): Pair<List<Double>, DistributionScale> {
    val span = log10(high) - log10(low)
    val step = LOG_STEP_LADDER.firstOrNull { ceil(span / it) <= DISTRIBUTION_MAX_BINS } ?: 1.0
    val start = floor(log10(low) / step) * step
    val bins = ceil(span / step).toInt().coerceIn(DISTRIBUTION_MIN_BINS, DISTRIBUTION_MAX_BINS)
    val edges = (0..bins).map { index -> 10.0.pow(start + step * index) }
    return edges to DistributionScale.LOG
}

private fun binCount(start: Double, high: Double, step: Double): Int {
    if (step <= 0.0 || !step.isFinite()) return DISTRIBUTION_MIN_BINS
    return ceil((high - start) / step).toInt().coerceAtLeast(1)
}

private fun edgesFrom(start: Double, step: Double, bins: Int): List<Double> =
    (0..bins).map { index -> start + step * index }

private fun count(values: List<Double>, edges: List<Double>, scale: DistributionScale): List<Int> {
    val counts = IntArray(edges.size - 1)
    val last = counts.size - 1
    values.forEach { value ->
        val index = when (scale) {
            DistributionScale.LOG -> {
                val step = ln(edges[1] / edges[0])
                floor(ln(value / edges[0]) / step)
            }
            DistributionScale.LINEAR -> {
                val step = edges[1] - edges[0]
                floor((value - edges[0]) / step)
            }
        }
        // A reading exactly on the last edge belongs to the last bar.
        if (index.isFinite()) counts[index.toInt().coerceIn(0, last)] += 1
    }
    return counts.toList()
}

/** Smallest ladder step at or above [target]. */
private fun niceStep(target: Double): Double {
    if (!target.isFinite() || target <= 0.0) return 1.0
    val decade = 10.0.pow(floor(log10(target)))
    val normalized = target / decade
    val factor = DISTRIBUTION_STEP_LADDER.firstOrNull { normalized <= it + 1e-9 } ?: 10.0
    return factor * decade
}

/** Move one ladder entry up (direction 1) or down (-1), crossing decades. */
private fun rescale(step: Double, direction: Int): Double {
    val decade = 10.0.pow(floor(log10(step)))
    val normalized = step / decade
    val index = DISTRIBUTION_STEP_LADDER.indexOfFirst { abs(it - normalized) < 0.01 * it }
        .coerceAtLeast(0)
    val next = index + direction
    return when {
        next >= DISTRIBUTION_STEP_LADDER.size -> 10.0 * decade
        next < 0 -> 0.75 * decade
        else -> DISTRIBUTION_STEP_LADDER[next] * decade
    }
}

/** Decimals a step needs to print exactly: 0.75 needs two, 2.5 one, 3 none. */
internal fun decimals(step: Double): Int {
    var digits = 0
    while (digits < 4) {
        val scaled = step * 10.0.pow(digits)
        if (abs(scaled - round(scaled)) < 1e-6) break
        digits += 1
    }
    return digits
}

/** 3.00 -> 3, 4.50 -> 4.5: keep the digits the step needs, drop the padding. */
private fun trimZeros(text: String): String =
    if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text

private fun fixed(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

private fun quantile(sorted: List<Double>, q: Double): Double {
    val position = q * (sorted.size - 1)
    val lower = floor(position).toInt()
    val upper = ceil(position).toInt()
    val weight = position - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

/**
 * Smallest increment the meter actually resolves, inferred from adjacent readings.
 *
 * A session of one repeated value has no differences, and the desktop answers with
 * one quantum unit (0.001 W); keep that rather than dividing by zero.
 */
private fun quantum(values: List<Double>): Double {
    val scale = 10.0.pow(DISTRIBUTION_QUANTUM_DECIMALS)
    var divisor = 0L
    for (index in 1 until values.size) {
        val delta = ((values[index] - values[index - 1]) * scale).roundToInt().toLong()
        if (delta >= 1L) divisor = if (divisor == 0L) delta else greatestCommonDivisor(divisor, delta)
    }
    if (divisor == 0L) return 1.0 / scale
    return divisor / scale
}

private tailrec fun greatestCommonDivisor(a: Long, b: Long): Long =
    if (b == 0L) a else greatestCommonDivisor(b, a % b)

/** Percent axis tick text: whole percentages, the same `{:>3.0}%` rule as the desktop. */
fun percentLabel(percent: Double): String = "${round(percent).toInt()}%"

/**
 * Bin under a horizontal position in the plot, or null when there is nothing to hit.
 *
 * [xFraction] is the tap position divided by the plot width, so the composable does
 * not need to know the pixel geometry and the hit test is unit tested here.
 */
fun binIndexAt(xFraction: Float, bins: Int): Int? {
    if (bins <= 0 || !xFraction.isFinite()) return null
    if (xFraction < 0f) return 0
    if (xFraction >= 1f) return bins - 1
    return (xFraction * bins).toInt().coerceIn(0, bins - 1)
}

/** Kept for callers that build a distribution from a sample list directly. */
fun distributionFor(samples: List<BatterySample>, windowSeconds: Int): PowerDistribution? =
    buildDistribution(distributionPopulation(distributionWindow(samples, windowSeconds)))
