package io.github.anevilburrito.wpm.ui

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A telemetry style used to keep `bodyMedium`'s 20sp line height while replacing
 * the font size. A 34sp or 48sp readout was therefore laid out in a 20dp line box,
 * which clips the glyphs and makes the landscape height arithmetic a fiction. The
 * line height is now proportional to the size, and this pins that rule.
 */
class WpmThemeTest {
    @Test fun lineHeightScalesWithTheReadoutSize() {
        assertEquals(57.6f, 48.sp.proportionalLineHeight().value, 0.01f)
        assertEquals(40.8f, 34.sp.proportionalLineHeight().value, 0.01f)
        assertEquals(24.sp.proportionalLineHeight().value, 18.sp.proportionalLineHeight().value / 0.75f, 0.01f)
    }

    @Test fun aLargeReadoutNeverInheritsTheTwentySpBodyLineHeight() {
        // The inherited bodyMedium line height is 20sp; anything bigger than the
        // font itself would be clipped, so every large readout must be taller.
        listOf(18, 20, 24, 26, 34, 48).forEach { size ->
            val lineHeight = size.sp.proportionalLineHeight().value
            assertTrue("${size}sp line height was $lineHeight", lineHeight > size)
        }
        assertEquals(1.2f, TELEMETRY_LINE_HEIGHT_RATIO, 0.0001f)
    }

    @Test fun lineHeightRatioIsAppliedToTheUnitValueOnly() {
        // A zero or negative clamp is not invented: the helper is a plain ratio.
        assertEquals(0f, 0.sp.proportionalLineHeight().value, 0.001f)
        assertEquals(12f, 10.sp.proportionalLineHeight(ratio = 1.2f).value, 0.001f)
    }
}
