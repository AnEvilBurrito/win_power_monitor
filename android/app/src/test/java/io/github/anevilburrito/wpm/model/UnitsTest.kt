package io.github.anevilburrito.wpm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    @Test fun phoneScalePowerUsesMilliwattsInsteadOfZeroWatts() {
        // A 0.3 mA load at 4 V is 1.26 mW: the old fixed-watt format printed 0.00.
        assertEquals(UnitQuantity("1.26", "mW"), Units.power(0.00126, absolute = true))
        assertEquals(UnitQuantity("12.6", "mW"), Units.power(0.0126, absolute = true))
        assertEquals(UnitQuantity("315", "\u00b5W"), Units.power(0.000315, absolute = true))
    }

    @Test fun wattRangeStaysInWatts() {
        assertEquals(UnitQuantity("4.80", "W"), Units.power(4.8, absolute = true))
        assertEquals(UnitQuantity("999.6", "mW"), Units.power(0.9996, absolute = true))
        assertEquals(UnitQuantity("0.00", "W"), Units.power(0.0, absolute = true))
    }

    @Test fun signRulesFollowTheSelectedScale() {
        assertEquals(UnitQuantity("\u221212.6", "mW"), Units.power(-0.0126, absolute = false))
        assertEquals(UnitQuantity("12.6", "mW"), Units.power(-0.0126, absolute = true))
        assertEquals(UnitQuantity("+12.6", "mW"), Units.power(0.0126, absolute = false))
        // Rounding to zero must not print a signed zero, and the unit stays honest.
        assertEquals(UnitQuantity("0", "\u00b5W"), Units.power(-1e-9, absolute = false))
    }

    @Test fun missingValuesKeepThePlaceholderAndADefaultUnit() {
        val missing = Units.power(null, absolute = true)
        assertEquals(Units.DASH, missing.text)
        assertEquals("W", missing.unit)
        assertFalse(missing.available)
        assertFalse(Units.power(Double.NaN, absolute = true).available)
        assertFalse(Units.current(Double.POSITIVE_INFINITY, absolute = true).available)
        assertFalse(Units.energy(null).available)
    }

    @Test fun energyTotalsScaleDownToMicroWattHours() {
        assertEquals(UnitQuantity("0.000", "Wh"), Units.energy(0.0))
        assertEquals(UnitQuantity("2.35", "mWh"), Units.energy(0.00235))
        assertEquals(UnitQuantity("840", "\u00b5Wh"), Units.energy(0.00084))
        assertEquals(UnitQuantity("1.500", "Wh"), Units.energy(1.5))
    }

    @Test fun currentScaleMatchesWhatThePlatformReports() {
        assertEquals(UnitQuantity("\u2212315", "\u00b5A"), Units.current(-0.000315, absolute = false))
        assertEquals(UnitQuantity("\u221242.0", "mA"), Units.current(-0.042, absolute = false))
        assertEquals(UnitQuantity("1.85", "A"), Units.current(1.85, absolute = true))
        assertEquals(UnitQuantity("0.00", "A"), Units.current(0.0, absolute = true))
    }

    @Test fun axisUnitFollowsThePlotMagnitude() {
        assertEquals(PowerUnit.MILLIWATTS, Units.axisUnit(0.0, 0.002))
        assertEquals(PowerUnit.MICROWATTS, Units.axisUnit(0.0, 0.0004))
        assertEquals(PowerUnit.WATTS, Units.axisUnit(0.0, 1.2))
        assertEquals(PowerUnit.MILLIWATTS, Units.axisUnit(-0.004, 0.002))
    }

    @Test fun axisTicksAreCompactInTheChosenUnit() {
        assertEquals("1", Units.axisTick(0.001, PowerUnit.MILLIWATTS, absolute = true))
        assertEquals("0", Units.axisTick(0.0, PowerUnit.MILLIWATTS, absolute = true))
        assertEquals("2.5", Units.axisTick(0.0025, PowerUnit.MILLIWATTS, absolute = true))
        assertEquals("\u22122", Units.axisTick(-0.002, PowerUnit.MILLIWATTS, absolute = false))
        assertEquals("+4", Units.axisTick(0.004, PowerUnit.MILLIWATTS, absolute = false))
        assertEquals(Units.DASH, Units.axisTick(Double.NaN, PowerUnit.MILLIWATTS, absolute = true))
    }

    @Test fun combinedTextIsAvailableForNotifications() {
        assertEquals("\u2212320 \u00b5A", Units.currentText(-0.00032, absolute = false))
        assertEquals("1.26 mW", Units.powerText(0.00126, absolute = true))
        assertEquals("2.35 mWh", Units.energyText(0.00235))
        assertEquals(Units.DASH, Units.powerText(null, absolute = true))
        assertTrue(Units.powerText(null, absolute = true).isNotEmpty())
    }
}
