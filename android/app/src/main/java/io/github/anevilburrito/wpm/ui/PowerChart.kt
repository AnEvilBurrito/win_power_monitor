package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.Units
import kotlin.math.roundToInt

private val AxisWidth = 44.dp

/**
 * Full-width plotting surface. The series is decimated against the actual measured
 * canvas width, so landscape uses nearly the whole window and portrait keeps the
 * approved stacked chart. Drawing is a pure function of the prepared series: no
 * animation, ticker or background redraw.
 *
 * Callers pass **live-display** samples ([io.github.anevilburrito.wpm.model.forLiveDisplay]),
 * so the plotted trace is the same calibrated instantaneous reading as the number
 * above it.
 * Axis labels use [Units.axisTick] with the unit implied by the series bounds, so
 * a milliwatt plot reads `0 / 2.5 / 5` with `mW` in the caption instead of a flat
 * line on a 0–1 W frame.
 */
@Composable
fun ChartArea(
    modifier: Modifier,
    samples: List<BatterySample>,
    windowSeconds: Int,
    intervalSeconds: Int,
    absolute: Boolean,
    showLegend: Boolean = true,
    minPlotHeight: Dp = LandscapeRules.PLOT_MIN_HEIGHT_DP.dp,
) {
    val colors = LocalWpmColors.current
    BoxWithConstraints(modifier, propagateMinConstraints = false) {
        val density = LocalDensity.current
        val plotWidthPx = with(density) { (maxWidth - AxisWidth).toPx() }.coerceAtLeast(1f)
        val maxPoints = (plotWidthPx / 2.5f).toInt().coerceIn(16, 2048)
        val series = remember(samples, windowSeconds, intervalSeconds, absolute, maxPoints) {
            buildChartSeries(samples, windowSeconds, intervalSeconds, absolute, maxPoints)
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = minPlotHeight),
            ) {
                AxisLabels(series, absolute)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("chart-plot"),
                ) {
                    if (series.isEmpty) {
                        Text(
                            text = "No readings in this window",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.muted,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) { drawSeries(series, colors) }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                Text(
                    text = if (series.isEmpty) WpmFormat.DASH else WpmFormat.clock(series.startWallMillis),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = colors.muted,
                    modifier = Modifier.width(AxisWidth),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = if (series.isEmpty) WpmFormat.DASH else WpmFormat.clock(series.endWallMillis),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = colors.muted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (showLegend) {
                ChartLegend(series, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** `Power / mW` caption text; the unit comes from the plotted bounds. */
fun chartCaptionTitle(unit: PowerUnit): String = "Power ${WpmFormat.SEPARATOR} ${unit.suffix}"

@Composable
private fun AxisLabels(series: ChartSeries, absolute: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    val middle = (series.lower + series.upper) / 2.0
    Column(
        modifier = modifier
            .width(AxisWidth)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(series.upper, middle, series.lower).forEach { value ->
            Text(
                text = Units.axisTick(value, series.unit, absolute),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                modifier = Modifier.padding(end = 4.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private fun DrawScope.drawSeries(series: ChartSeries, colors: WpmColors) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return
    val span = series.spanMillis.coerceAtLeast(1L)

    fun xOf(millis: Long): Float =
        (((millis - series.startMillis).toDouble() / span.toDouble()) * width.toDouble())
            .toFloat()
            .coerceIn(0f, width)

    fun yOf(value: Double): Float {
        val range = series.upper - series.lower
        val ratio = if (range <= 0.0) 0.5 else (value - series.lower) / range
        return (height - ratio.coerceIn(0.0, 1.0) * height).toFloat()
    }

    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val y = height * fraction
        drawLine(colors.rule.copy(alpha = 0.45f), Offset(0f, y), Offset(width, y), strokeWidth = 1f)
    }
    if (series.lower <= 0.0 && series.upper >= 0.0) {
        val zeroY = yOf(0.0)
        drawLine(colors.muted.copy(alpha = 0.75f), Offset(0f, zeroY), Offset(width, zeroY), strokeWidth = 1.5f)
    }
    series.gaps.forEach { gap ->
        val gapStart = xOf(gap.startMillis)
        val gapEnd = xOf(gap.endMillis)
        if (gapEnd > gapStart) {
            drawRect(
                color = colors.rule.copy(alpha = 0.22f),
                topLeft = Offset(gapStart, 0f),
                size = Size(gapEnd - gapStart, height),
            )
        }
    }
    val traceStyle = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    series.segments.forEach { segment ->
        val points = segment.points
        if (points.size == 1) {
            drawCircle(
                color = colors.trace,
                radius = 1.6.dp.toPx(),
                center = Offset(xOf(points[0].elapsedMillis), yOf(points[0].value)),
            )
        } else if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = xOf(point.elapsedMillis)
                val y = yOf(point.value)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors.trace, style = traceStyle)
        }
    }
    series.segments.lastOrNull()?.points?.lastOrNull()?.let { last ->
        drawCircle(
            color = colors.trace,
            radius = 2.7.dp.toPx(),
            center = Offset(xOf(last.elapsedMillis), yOf(last.value)),
        )
    }
}

/** Key under the plot; explicit words so color is never the only signal. */
@Composable
fun ChartLegend(series: ChartSeries, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(Modifier.width(13.dp).height(2.dp)) { drawRect(colors.trace) }
            Text(
                text = "Battery flow",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = if (series.hasGaps) "Blank region = no samples" else "No gaps in this window",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Height reserved inside the distribution canvas for its one-line header. */
private val DistributionHeaderHeight = 14.dp

/**
 * Distribution of the visible discharge readings, replacing the line chart in place.
 *
 * It answers a different question about the same samples ("where does the time go"
 * rather than "what happened when"), so the layout, the plot height, the 44dp axis
 * gutter and the axis+legend budget are all identical to [ChartArea]. Bars are
 * percentages of counted samples, the header states `n`, bin count and bin width,
 * and the edge labels thin with the measured width instead of colliding.
 *
 * Nothing here animates or polls: the bins are a pure function of the sample list,
 * computed once per list change.
 */
@Composable
fun DistributionChart(
    modifier: Modifier,
    samples: List<BatterySample>,
    showLegend: Boolean = true,
    minPlotHeight: Dp = LandscapeRules.PLOT_MIN_HEIGHT_DP.dp,
) {
    val colors = LocalWpmColors.current
    BoxWithConstraints(modifier, propagateMinConstraints = false) {
        val density = LocalDensity.current
        val plotWidthDp = (maxWidth - AxisWidth).value.roundToInt()
        val population = remember(samples) { distributionPopulation(samples) }
        val distribution = remember(population) { buildDistribution(population) }
        val headerTiers = remember(distribution, population) {
            distributionHeaderTiers(distribution, population)
        }
        val placeholder = remember(distribution, population) {
            distributionPlaceholder(distribution, population)
        }
        val textMeasurer = rememberTextMeasurer()
        val headerStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
        val header = remember(headerTiers, headerStyle, plotWidthDp, density) {
            val available = with(density) { plotWidthDp.dp.toPx() }
            headerTiers.firstOrNull { tier ->
                textMeasurer.measure(
                    text = tier,
                    style = headerStyle,
                    softWrap = false,
                    maxLines = 1,
                ).size.width <= available
            } ?: headerTiers.last()
        }
        val description = remember(distribution) {
            distribution?.accessibilityDescription() ?: "Power distribution: collecting readings"
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = minPlotHeight),
            ) {
                PercentAxis(distribution)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("distribution-plot"),
                ) {
                    if (distribution == null) {
                        Text(
                            text = placeholder.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 8.dp),
                        )
                    }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = description },
                    ) {
                        if (distribution != null) drawBins(distribution, colors)
                    }
                    Text(
                        text = header,
                        style = headerStyle,
                        color = colors.muted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(DistributionHeaderHeight)
                            .padding(start = 2.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                Spacer(Modifier.width(AxisWidth))
                if (distribution != null) {
                    BinEdgeLabels(distribution, plotWidthDp, Modifier.weight(1f))
                }
            }
            if (showLegend) {
                DistributionLegend(population, distribution, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Percent axis: ceiling, half and zero, in the same gutter the line chart uses. */
@Composable
private fun PercentAxis(distribution: PowerDistribution?, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    val top = distribution?.topPercent ?: 100.0
    Column(
        modifier = modifier
            .width(AxisWidth)
            .fillMaxHeight()
            .padding(top = DistributionHeaderHeight),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(top, top / 2.0, 0.0).forEach { value ->
            Text(
                text = percentLabel(value),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                modifier = Modifier.padding(end = 4.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Lower bin edges under the plot, thinned so no two labels can collide.
 */
@Composable
private fun BinEdgeLabels(
    distribution: PowerDistribution,
    plotWidthDp: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWpmColors.current
    val unit = distribution.axisUnit()
    val indices = remember(distribution, plotWidthDp) {
        distributionLabelIndices(distribution.bins(), plotWidthDp)
    }
    val bins = distribution.bins().toFloat()
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        indices.forEach { index ->
            Text(
                text = distribution.binLabel(index, unit),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset(
                    x = with(density) { ((index / bins) * widthPx).toDp() },
                ),
            )
        }
    }
}

/**
 * Bars plus the faint rules behind them.
 *
 * Bars start below the header band so the title never overlaps data. A gap is kept
 * between bars while the plot can spare it, and an empty bin draws nothing because
 * the hole is data.
 */
private fun DrawScope.drawBins(distribution: PowerDistribution, colors: WpmColors) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return
    val plotTop = DistributionHeaderHeight.toPx().coerceAtMost(height / 2f)
    val plotHeight = (height - plotTop).coerceAtLeast(1f)
    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val y = plotTop + plotHeight * fraction
        drawLine(colors.rule.copy(alpha = 0.45f), Offset(0f, y), Offset(width, y), strokeWidth = 1f)
    }
    drawLine(
        colors.muted.copy(alpha = 0.75f),
        Offset(0f, height),
        Offset(width, height),
        strokeWidth = 1.5f,
    )
    val bins = distribution.bins()
    if (bins <= 0) return
    val slot = width / bins
    val gapPx = if (slot >= 3f) 1.dp.toPx().coerceAtMost(slot / 4f) else 0f
    val barWidth = (slot - gapPx).coerceAtLeast(minOf(2f, slot)).coerceAtMost(slot)
    val top = distribution.topPercent.takeIf { it > 0.0 } ?: 100.0
    distribution.counts.forEachIndexed { index, count ->
        if (count <= 0) return@forEachIndexed
        val ratio = (distribution.percent(index) / top).coerceIn(0.0, 1.0)
        val barHeight = (plotHeight * ratio).toFloat()
        if (barHeight <= 0f) return@forEachIndexed
        drawRect(
            color = colors.trace,
            topLeft = Offset(index * slot + gapPx / 2f, height - barHeight),
            size = Size(barWidth, barHeight),
        )
    }
}

/** Legend under the plot: explicit words, so meaning never depends on colour. */
@Composable
private fun DistributionLegend(
    population: DistributionPopulation,
    distribution: PowerDistribution?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWpmColors.current
    val counted = distribution?.n ?: population.size
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(Modifier.width(9.dp).height(9.dp)) { drawRect(colors.trace) }
            Text(
                text = "Bars \u00b7 % of counted discharge readings",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = if (population.excluded > 0) {
                "n=$counted \u00b7 ${population.excluded} excluded"
            } else {
                "n=$counted \u00b7 all counted"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = colors.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}
