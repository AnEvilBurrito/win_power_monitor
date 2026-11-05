package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.Units

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
