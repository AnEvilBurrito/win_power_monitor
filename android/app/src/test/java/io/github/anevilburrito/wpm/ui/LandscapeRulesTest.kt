package io.github.anevilburrito.wpm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape dashboard collapsed on a real 1080x2340 phone: the chart caption
 * was measured ~11 dp wide and its letters stacked one per line while the readings
 * strip, chips, toggle, freeze control and plot disappeared. These tests pin the
 * arithmetic that makes that impossible, because the composables only consult it.
 *
 * They also pin the honesty of the budget: the "120 dp plot" is the drawable
 * canvas, and the x-axis row and legend are paid for on top of it. A total chart
 * box of 120 dp would leave an ~80 dp canvas, which is exactly the fake fit these
 * tests refuse to assert.
 */
class LandscapeRulesTest {
    /** A landscape 1080x2340 phone minus the safe-drawing insets. */
    private val phoneWidthDp = 851
    private val phoneHeightDp = 393

    @Test fun phoneLandscapeKeepsARealOneHundredTwentyDpCanvas() {
        val chart = LandscapeRules.chartHeightDp(phoneWidthDp, phoneHeightDp)
        val canvas = LandscapeRules.plotHeightDp(phoneWidthDp, phoneHeightDp)
        assertTrue("chart height was $chart dp", chart >= LandscapeRules.CHART_MIN_HEIGHT_DP)
        assertTrue("canvas height was $canvas dp", canvas >= LandscapeRules.PLOT_MIN_HEIGHT_DP)
        // The canvas is the chart minus axis and legend, never the chart box itself.
        assertEquals(LandscapeRules.CHART_MIN_HEIGHT_DP, LandscapeRules.PLOT_MIN_HEIGHT_DP + LandscapeRules.AXIS_AND_LEGEND_HEIGHT_DP)
        assertEquals(chart - LandscapeRules.AXIS_AND_LEGEND_HEIGHT_DP, canvas)
        assertFalse(LandscapeRules.needsScrolling(phoneWidthDp, phoneHeightDp))
    }

    @Test fun aTwoLineNoticeIsBudgetedInsteadOfEatingTheCanvas() {
        val withNotice = LandscapeRules.plotHeightDp(phoneWidthDp, phoneHeightDp, hasNotice = true)
        val withoutNotice = LandscapeRules.plotHeightDp(phoneWidthDp, phoneHeightDp)
        assertEquals(withoutNotice - LandscapeRules.NOTICE_HEIGHT_DP, withNotice)
        // The budget is the real notice: two bodyText lines inside 6dp vertical
        // padding each, not a one-line 20dp guess.
        assertEquals(2 * 17 + 2 * 6, LandscapeRules.NOTICE_HEIGHT_DP)
        // On this phone the notice would leave an impossible canvas, so the detail
        // scrolls at the fixed fallback height instead of promising a chart fit.
        assertTrue(LandscapeRules.needsScrolling(phoneWidthDp, phoneHeightDp, hasNotice = true))
        assertTrue(withNotice < LandscapeRules.PLOT_MIN_HEIGHT_DP)
    }

    @Test fun aShortWindowScrollsInsteadOfCompressingTheChart() {
        // 640x320 dp landscape with a two-row toolbar cannot hold a 120 dp canvas.
        assertTrue(LandscapeRules.needsScrolling(640, 320))
        assertTrue(LandscapeRules.plotHeightDp(640, 320) < LandscapeRules.PLOT_MIN_HEIGHT_DP)
        // The fallback is a whole ChartArea: its canvas still exceeds the minimum.
        assertTrue(
            LandscapeRules.SCROLL_FALLBACK_CHART_HEIGHT_DP - LandscapeRules.AXIS_AND_LEGEND_HEIGHT_DP >=
                LandscapeRules.PLOT_MIN_HEIGHT_DP,
        )
        // Scrolling is decided by the canvas budget, not by the chart box.
        assertTrue(
            LandscapeRules.chartHeightDp(640, 320) < LandscapeRules.CHART_MIN_HEIGHT_DP,
        )
    }

    @Test fun anyFontScaleAboveDefaultUsesTheConservativeScrollFallback() {
        // The dp budgets describe text at the default font scale; above it the same
        // layout would clip real glyphs, so landscape detail scrolls instead.
        assertFalse(LandscapeRules.needsScrolling(phoneWidthDp, phoneHeightDp, fontScale = 1.0f))
        assertTrue(LandscapeRules.needsScrolling(phoneWidthDp, phoneHeightDp, fontScale = 1.01f))
        assertTrue(LandscapeRules.needsScrolling(phoneWidthDp, phoneHeightDp, fontScale = 1.6f))
        assertEquals(1.0f, LandscapeRules.MAX_LAYOUT_FONT_SCALE, 0.0f)
    }

    @Test fun theCaptionNeverGetsSqueezedOnAOneLineToolbar() {
        // On a one-line toolbar the caption gets exactly the width the controls leave.
        assertEquals(
            720 - LandscapeRules.controlsWidthDp(),
            LandscapeRules.labelWidthDp(720),
        )
        for (width in listOf(720, 851, 1000, 1280)) {
            assertFalse(
                "one-line toolbar used at $width dp",
                LandscapeRules.usesTwoRowToolbar(width),
            )
            assertTrue("caption collapses at $width dp", LandscapeRules.labelCannotCollapse(width))
            assertTrue("controls do not fit at $width dp", LandscapeRules.toolbarFitsOnOneLine(width))
        }
    }

    @Test fun narrowWindowsMoveTheControlsToTheirOwnToolbarLine() {
        for (width in listOf(480, 600, 719)) {
            assertTrue("$width dp should use two rows", LandscapeRules.usesTwoRowToolbar(width))
            // The caption gets the full width on the first line.
            assertEquals(width, LandscapeRules.labelWidthDp(width))
            assertTrue(LandscapeRules.labelCannotCollapse(width))
        }
        // The control group alone is what must fit on the second line.
        assertTrue(LandscapeRules.controlsWidthDp() <= 640)
    }

    @Test fun rangeChipsAreThreeIntrinsicChipsAndNeverStretch() {
        assertEquals(148, LandscapeRules.rangeChipsWidthDp())
        assertEquals(3, LandscapeRules.CHIP_COUNT)
        assertEquals(48, LandscapeRules.CHIP_MIN_WIDTH_DP)
        // The chips are a fixed budget inside a wider control group: nothing is
        // fillMaxWidth, so one chip cannot grow into the row like it did on the phone.
        assertTrue(LandscapeRules.rangeChipsWidthDp() < LandscapeRules.controlsWidthDp())
        assertTrue(LandscapeRules.rangeChipsWidthDp() < phoneWidthDp / 3)
    }

    @Test fun toolbarLinesAreAtLeastAsTallAsTheirFortyEightDpChildren() {
        assertTrue(LandscapeRules.READINGS_STRIP_HEIGHT_DP >= 56)
        assertTrue(LandscapeRules.COMPACT_READINGS_STRIP_HEIGHT_DP >= LandscapeRules.READINGS_STRIP_HEIGHT_DP)
        // Chips, switch and freeze control are all 48dp touch targets; a 44dp
        // toolbar line would let them overlap the row below.
        assertEquals(48, LandscapeRules.TOOLBAR_MIN_HEIGHT_DP)
        assertEquals(48, LandscapeRules.TOOLBAR_ROW_HEIGHT_DP)
        assertTrue(LandscapeRules.TOOLBAR_MIN_HEIGHT_DP >= LandscapeRules.CHIP_MIN_WIDTH_DP)
        assertEquals(
            LandscapeRules.TOOLBAR_ROW_HEIGHT_DP * 2,
            LandscapeRules.toolbarHeightDp(600),
        )
        assertEquals(
            LandscapeRules.TOOLBAR_MIN_HEIGHT_DP,
            LandscapeRules.toolbarHeightDp(900),
        )
    }

    @Test fun fixedChromeNeverIncludesTheChartAxisOrLegend() {
        val chrome = LandscapeRules.fixedChromeHeightDp(phoneWidthDp)
        assertEquals(
            LandscapeRules.HEADER_HEIGHT_DP +
                LandscapeRules.READINGS_STRIP_HEIGHT_DP +
                LandscapeRules.TOOLBAR_MIN_HEIGHT_DP +
                LandscapeRules.DECK_HEIGHT_DP,
            chrome,
        )
        // The axis and legend are charged to the chart, not to the chrome, so they
        // cannot be silently counted twice (or not at all).
        assertEquals(
            phoneHeightDp - chrome,
            LandscapeRules.chartHeightDp(phoneWidthDp, phoneHeightDp),
        )
    }

    @Test fun orientationUsesTheInsetsPaddedSizeWithoutABiasFactor() {
        assertTrue(LandscapeRules.isLandscape(851, 393))
        assertTrue(LandscapeRules.isLandscape(393, 393))
        assertFalse(LandscapeRules.isLandscape(393, 851))
        // Exactly square is landscape; the old `maxWidth > maxHeight * 1.05` bias
        // could leave a wide-but-short window in the portrait stack.
        assertTrue(LandscapeRules.isLandscape(500, 500))
    }
}
