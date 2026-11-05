package io.github.anevilburrito.wpm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.anevilburrito.wpm.model.AppTheme

/**
 * Terminal-derived instrument roles. Values mirror the approved mock-up (and the
 * desktop palette roles) so the mobile surface stays recognizably WPM.
 */
data class WpmColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val rule: Color,
    val trace: Color,
    val charge: Color,
    val discharge: Color,
    val warning: Color,
    val low: Color,
    val light: Boolean,
)

private val StormColors = WpmColors(
    background = Color(0xFF24283B),
    surface = Color(0xFF2B3046),
    text = Color(0xFFC0CAF5),
    muted = Color(0xFFA9B1D6),
    rule = Color(0xFF565F89),
    trace = Color(0xFF7DCFFF),
    charge = Color(0xFF9ECE6A),
    discharge = Color(0xFFE0AF68),
    warning = Color(0xFFFF9E64),
    low = Color(0xFFFF8797),
    light = false,
)

private val MochaColors = WpmColors(
    background = Color(0xFF1E1E2E),
    surface = Color(0xFF262638),
    text = Color(0xFFCDD6F4),
    muted = Color(0xFFBAC2DE),
    rule = Color(0xFF6C7086),
    trace = Color(0xFF94E2D5),
    charge = Color(0xFFA6E3A1),
    discharge = Color(0xFFF9E2AF),
    warning = Color(0xFFFAB387),
    low = Color(0xFFFF8797),
    light = false,
)

private val LatteColors = WpmColors(
    background = Color(0xFFEFF1F5),
    surface = Color(0xFFE6E9EF),
    text = Color(0xFF4C4F69),
    muted = Color(0xFF5C5F77),
    rule = Color(0xFF8C8FA1),
    trace = Color(0xFF007F8B),
    charge = Color(0xFF38751D),
    discharge = Color(0xFF986900),
    warning = Color(0xFFC3440B),
    low = Color(0xFFB91C35),
    light = true,
)

private val MonoColors = WpmColors(
    background = Color(0xFF171717),
    surface = Color(0xFF222222),
    text = Color(0xFFF0F0F0),
    muted = Color(0xFFC0C0C0),
    rule = Color(0xFF808080),
    trace = Color(0xFFF0F0F0),
    charge = Color(0xFFF0F0F0),
    discharge = Color(0xFFF0F0F0),
    warning = Color(0xFFF0F0F0),
    low = Color(0xFFF0F0F0),
    light = false,
)

fun colorsFor(theme: AppTheme): WpmColors = when (theme) {
    AppTheme.STORM -> StormColors
    AppTheme.MOCHA -> MochaColors
    AppTheme.LATTE -> LatteColors
    AppTheme.MONO -> MonoColors
}

val LocalWpmColors = staticCompositionLocalOf { StormColors }

private val WpmTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp),
    )
}

@Composable
fun WpmTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val colors = colorsFor(theme)
    val scheme = if (colors.light) {
        lightColorScheme(
            primary = colors.trace,
            onPrimary = colors.background,
            secondary = colors.charge,
            onSecondary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.muted,
            outline = colors.rule,
            error = colors.low,
            onError = colors.background,
        )
    } else {
        darkColorScheme(
            primary = colors.trace,
            onPrimary = colors.background,
            secondary = colors.charge,
            onSecondary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.muted,
            outline = colors.rule,
            error = colors.low,
            onError = colors.background,
        )
    }
    CompositionLocalProvider(LocalWpmColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = WpmTypography, content = content)
    }
}

/**
 * Line height as a multiple of the font size.
 *
 * A telemetry style that only replaced `fontSize` kept `bodyMedium`'s 20sp
 * `lineHeight`, so a 34sp or 48sp readout was laid out in a 20dp line box and the
 * glyphs overlapped the line below (or were clipped) while layout arithmetic still
 * budgeted a single line. Scaling the line height with the size makes the measured
 * text height match the budget.
 */
const val TELEMETRY_LINE_HEIGHT_RATIO = 1.2f

/** Proportional line height for a telemetry size; pure, so it is unit tested. */
internal fun TextUnit.proportionalLineHeight(ratio: Float = TELEMETRY_LINE_HEIGHT_RATIO): TextUnit =
    (value * ratio).sp

/** Monospace telemetry text; the size is explicit because readouts drive hierarchy. */
@Composable
fun telemetry(size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        fontWeight = weight,
        // Explicit proportional line height: never the inherited 20sp body line box.
        lineHeight = size.proportionalLineHeight(),
        letterSpacing = 0.sp,
    )

@Composable
fun microLabel(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = 10.sp,
    fontWeight = FontWeight.Normal,
)

@Composable
fun bodyText(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun captionText(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 15.sp)
