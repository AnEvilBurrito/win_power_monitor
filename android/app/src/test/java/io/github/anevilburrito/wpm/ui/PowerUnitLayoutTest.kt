package io.github.anevilburrito.wpm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual font widths are supplied by TextMeasurer in the composable, not guessed here. */
class PowerUnitLayoutTest {
    @Test fun theReportedValueKeepsItsCompleteMilliwattSuffix() {
        val power = WpmFormat.power(0.3581, absolute = true)
        assertEquals("358.1", power.text)
        assertEquals("mW", power.unit)
        val plan = ReadoutSlots.numberRowPlan(375, 1f, false, measuredUnitTextWidthDp = 42f)
        assertTrue(plan.unitTextWidthDp >= 42f + ReadoutSlots.UNIT_PADDING_DP)
        assertFalse(plan.percentBelow)
    }

    @Test fun measuredWideSuffixOverridesTheOldNarrowAdvanceEstimate() {
        val fallback = ReadoutSlots.unitSlotWidthDp(1f, false)
        val measured = ReadoutSlots.unitSlotWidthDp(1f, false, measuredUnitTextWidthDp = 42f)
        assertTrue(measured > fallback)
        assertEquals(42 + ReadoutSlots.UNIT_GAP_DP + ReadoutSlots.UNIT_PADDING_DP, measured)
    }

    @Test fun fractionalMetricsRoundUpAndRetainAnInkGuard() {
        val plan = ReadoutSlots.numberRowPlan(375, 1f, false, measuredUnitTextWidthDp = 42.25f)
        assertTrue(plan.unitTextWidthDp >= 42.25f + ReadoutSlots.UNIT_PADDING_DP)
        assertEquals(plan.unitSlotDp, plan.unitTextWidthDp + ReadoutSlots.UNIT_GAP_DP)
        assertEquals(4, ReadoutSlots.UNIT_GAP_DP)
    }

    @Test fun thePowerGroupAndBatteryFitWithMeasuredMetricsAcrossLayouts() {
        for (landscape in listOf(false, true)) {
            for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                for (width in listOf(189, 240, 284, 375, 600)) {
                    val measured = (if (landscape) 26f else 38f) * scale
                    val plan = ReadoutSlots.numberRowPlan(width, scale, landscape, measured)
                    val batteryOnThisLine = if (plan.percentBelow) 0 else plan.percentSlotDp
                    assertEquals(width, plan.powerGroupWidthDp + plan.spareWidthDp + batteryOnThisLine)
                    assertTrue(plan.unitTextWidthDp >= measured + ReadoutSlots.UNIT_PADDING_DP)
                }
            }
        }
    }

    @Test fun missingOrInvalidMetricsUseASafePositiveFallback() {
        val expected = ReadoutSlots.unitSlotWidthDp(1f, false)
        for (measurement in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            assertEquals(expected, ReadoutSlots.unitSlotWidthDp(1f, false, measurement))
        }
    }

    @Test fun theDefaultThreeColumnLandscapeDoesNotNeedAnExtraBatteryRow() {
        val cell = ReadoutSlots.splitCellWidthDp(620 - 24, 1.2, 3.6, spacingDp = 28)
        val plan = ReadoutSlots.numberRowPlan(cell, 1f, true, measuredUnitTextWidthDp = 18f)
        assertFalse(plan.percentBelow)
        assertTrue(plan.wordFits)
        assertTrue(plan.powerGroupWidthDp + plan.percentSlotDp <= cell)
    }
}
