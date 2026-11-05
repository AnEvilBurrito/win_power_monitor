package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Small hand-drawn line icons; no icon or chart dependency is added for these. */
enum class WpmIcon { CHART, SESSION, DIAGNOSTICS, SETTINGS, STOP, PLAY, FREEZE, CLOSE, EXPORT }

@Composable
fun WpmIconView(
    icon: WpmIcon,
    modifier: Modifier = Modifier.size(22.dp),
    tint: Color = LocalWpmColors.current.muted,
) {
    Canvas(modifier) { drawWpmIcon(icon, tint) }
}

private fun DrawScope.drawWpmIcon(icon: WpmIcon, tint: Color) {
    val unit = min(size.width, size.height) / 24f
    val style = Stroke(width = 1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun p(x: Float, y: Float) = Offset(x * unit, y * unit)

    fun polyline(points: List<Pair<Float, Float>>, closed: Boolean = false): Path = Path().apply {
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit)
        }
        if (closed) close()
    }

    when (icon) {
        WpmIcon.CHART -> {
            drawPath(polyline(listOf(3f to 4f, 3f to 20f, 21f to 20f)), tint, style = style)
            drawPath(polyline(listOf(6f to 15f, 10f to 9f, 14f to 12f, 20f to 5f)), tint, style = style)
        }
        WpmIcon.SESSION -> {
            drawRoundRect(
                color = tint,
                topLeft = p(5f, 4f),
                size = Size(14f * unit, 17f * unit),
                cornerRadius = CornerRadius(2f * unit, 2f * unit),
                style = style,
            )
            drawPath(polyline(listOf(9f to 4f, 9f to 2f, 15f to 2f, 15f to 4f)), tint, style = style)
            drawPath(polyline(listOf(9f to 9f, 15f to 9f)), tint, style = style)
            drawPath(polyline(listOf(9f to 13f, 15f to 13f)), tint, style = style)
            drawPath(polyline(listOf(9f to 17f, 13f to 17f)), tint, style = style)
        }
        WpmIcon.DIAGNOSTICS -> {
            drawCircle(tint, radius = 9f * unit, center = p(12f, 12f), style = style)
            drawPath(polyline(listOf(12f to 11f, 12f to 17f)), tint, style = style)
            drawPath(polyline(listOf(12f to 7f, 12f to 8f)), tint, style = style)
        }
        WpmIcon.SETTINGS -> {
            drawPath(polyline(listOf(4f to 7f, 20f to 7f)), tint, style = style)
            drawPath(polyline(listOf(4f to 17f, 20f to 17f)), tint, style = style)
            drawCircle(tint, radius = 3f * unit, center = p(9f, 7f), style = style)
            drawCircle(tint, radius = 3f * unit, center = p(15f, 17f), style = style)
        }
        WpmIcon.STOP -> {
            drawRoundRect(
                color = tint,
                topLeft = p(6f, 6f),
                size = Size(12f * unit, 12f * unit),
                cornerRadius = CornerRadius(1.5f * unit, 1.5f * unit),
            )
        }
        WpmIcon.PLAY -> drawPath(polyline(listOf(8f to 5f, 19f to 12f, 8f to 19f), closed = true), tint)
        WpmIcon.FREEZE -> {
            drawPath(polyline(listOf(8f to 5f, 8f to 19f)), tint, style = style)
            drawPath(polyline(listOf(16f to 5f, 16f to 19f)), tint, style = style)
        }
        WpmIcon.CLOSE -> {
            drawPath(polyline(listOf(6f to 6f, 18f to 18f)), tint, style = style)
            drawPath(polyline(listOf(6f to 18f, 18f to 6f)), tint, style = style)
        }
        WpmIcon.EXPORT -> {
            drawPath(polyline(listOf(12f to 15f, 12f to 3f)), tint, style = style)
            drawPath(polyline(listOf(8f to 7f, 12f to 3f, 16f to 7f)), tint, style = style)
            drawPath(polyline(listOf(5f to 14f, 5f to 20f, 19f to 20f, 19f to 14f)), tint, style = style)
        }
    }
}
