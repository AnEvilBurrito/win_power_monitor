package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.PowerUnit
import io.github.anevilburrito.wpm.model.UnitQuantity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WpmFormatTest {
    @Test fun phoneScalePowerIsShownInMilliwattsNotZeroWatts() {
        // 315 µA at 4 V is 1.26 mW; a 2-decimal watt format printed "0.00" here.
        assertEquals(UnitQuantity("1.26", "mW"), WpmFormat.power(0.00126, absolute = true))
        assertEquals(UnitQuantity("12.6", "mW"), WpmFormat.power(0.0126, absolute = true))
        assertEquals(UnitQuantity("315", "\u00b5W"), WpmFormat.power(0.000315, absolute = true))
        assertEquals(UnitQuantity("4.80", "W"), WpmFormat.power(4.8, absolute = true))
        assertEquals("1.26 mW", WpmFormat.withUnit(WpmFormat.power(0.00126, absolute = true)))
    }

    @Test fun absoluteScaleDropsTheSignButKeepsMagnitude() {
        assertEquals(UnitQuantity("4.80", "W"), WpmFormat.power(-4.8, absolute = true))
        assertEquals(UnitQuantity("1.26", "mW"), WpmFormat.power(-0.00126, absolute = true))
        assertEquals(UnitQuantity("0.00", "W"), WpmFormat.power(0.0, absolute = true))
    }

    @Test fun signedScaleShowsDischargeAndChargeDirection() {
        assertEquals(UnitQuantity("\u22124.80", "W"), WpmFormat.power(-4.8, absolute = false))
        assertEquals(UnitQuantity("+6.20", "W"), WpmFormat.power(6.2, absolute = false))
        assertEquals(UnitQuantity("\u221212.6", "mW"), WpmFormat.power(-0.0126, absolute = false))
        // A milliwatt is no longer rounded away as "0.00 W".
        assertEquals(UnitQuantity("\u22121.00", "mW"), WpmFormat.power(-0.001, absolute = false))
        assertEquals(
            UnitQuantity("\u2212315", "\u00b5A"),
            WpmFormat.current(-0.000315, absolute = false),
        )
    }

    @Test fun currentScaleMatchesWhatThePlatformReports() {
        assertEquals(UnitQuantity("\u221242.0", "mA"), WpmFormat.current(-0.042, absolute = false))
        assertEquals(UnitQuantity("1.85", "A"), WpmFormat.current(1.85, absolute = true))
    }

    @Test fun missingValuesNeverBecomeZero() {
        assertEquals(WpmFormat.DASH, WpmFormat.power(null, absolute = true).text)
        assertEquals(WpmFormat.DASH, WpmFormat.power(Double.NaN, absolute = false).text)
        assertFalse(WpmFormat.power(null, absolute = true).available)
        assertEquals(WpmFormat.DASH, WpmFormat.current(null, absolute = true).text)
        assertEquals(WpmFormat.DASH, WpmFormat.percent(null))
        assertEquals(WpmFormat.DASH, WpmFormat.percent(140))
        assertEquals(WpmFormat.DASH, WpmFormat.voltage(null))
        assertEquals(WpmFormat.DASH, WpmFormat.voltage(0.0))
        assertEquals(WpmFormat.DASH, WpmFormat.age(null))
    }

    @Test fun missingValuesKeepTheDashAndDropTheSuffix() {
        assertEquals(WpmFormat.DASH, WpmFormat.withUnit(WpmFormat.power(null, absolute = true)))
        assertEquals(WpmFormat.DASH, WpmFormat.withUnit(WpmFormat.energy(null)))
        assertFalse(WpmFormat.withUnit(WpmFormat.power(null, absolute = true)).contains("W"))
    }

    @Test fun electricalUnitsAreFormattedWithoutFabricating() {
        assertEquals("4.02", WpmFormat.voltage(4.02))
        assertEquals("68", WpmFormat.percent(68))
        assertEquals("31.2", WpmFormat.temperature(31.2))
        assertEquals(WpmFormat.DASH, WpmFormat.temperature(Double.POSITIVE_INFINITY))
        assertEquals(WpmFormat.DASH, WpmFormat.temperature(null))
        // A cold battery keeps its sign instead of reading as a positive number.
        assertEquals("\u22125.0", WpmFormat.temperature(-5.0))
        assertEquals("0.0", WpmFormat.temperature(-0.0))
    }

    @Test fun durationsUseMonotonicSecondPrecision() {
        assertEquals("00:41:06", WpmFormat.durationHms(2466.0))
        assertEquals("00:00:00", WpmFormat.durationHms(-4.0))
        assertEquals("00:00:00", WpmFormat.durationHms(Double.NaN))
        assertEquals("38m 36s", WpmFormat.durationCompact(2316.0))
        assertEquals("2m 30s", WpmFormat.durationCompact(150.0))
        assertEquals("45s", WpmFormat.durationCompact(45.0))
        assertEquals("1h 03m", WpmFormat.durationCompact(3780.0))
        assertEquals("2m 30s", WpmFormat.age(150.0))
    }

    @Test fun coverageIsRoundedAndBounded() {
        assertEquals(94, WpmFormat.coveragePercent(2316.0, 2466.0))
        assertEquals(100, WpmFormat.coveragePercent(120.0, 120.0))
        assertEquals(0, WpmFormat.coveragePercent(0.0, 120.0))
        assertNull(WpmFormat.coveragePercent(10.0, 0.0))
        assertEquals(100, WpmFormat.coveragePercent(500.0, 100.0))
    }

    @Test fun wallClockLabelsUseTheSuppliedZone() {
        val utc = ZoneId.of("UTC")
        assertEquals("22:13", WpmFormat.clock(1_700_000_000_000L, utc))
        assertEquals("22:13:20", WpmFormat.clockWithSeconds(1_700_000_000_000L, utc))
    }

    @Test fun chartCaptionNamesThePlotInTheSelectedUnit() {
        assertEquals("Power / mW", chartCaptionTitle(PowerUnit.MILLIWATTS))
        assertEquals("Power / W", chartCaptionTitle(PowerUnit.WATTS))
        assertEquals("Power / \u00b5W", chartCaptionTitle(PowerUnit.MICROWATTS))
    }

    @Test fun estimatedCapacityIsRoundedAndGrouped() {
        assertEquals("4,380 mAh", WpmFormat.milliampHours(4_380.4))
        assertEquals("4,380 mAh", WpmFormat.milliampHours(4_379.6))
        assertEquals("999 mAh", WpmFormat.milliampHours(999.4))
        assertEquals("12,000 mAh", WpmFormat.milliampHours(12_000.2))
        // Missing or nonpositive stays the em dash instead of "0 mAh".
        assertEquals(WpmFormat.DASH, WpmFormat.milliampHours(null))
        assertEquals(WpmFormat.DASH, WpmFormat.milliampHours(0.0))
        assertEquals(WpmFormat.DASH, WpmFormat.milliampHours(-1_200.0))
        assertEquals(WpmFormat.DASH, WpmFormat.milliampHours(0.2))
        assertEquals(WpmFormat.DASH, WpmFormat.milliampHours(Double.NaN))
    }

    @Test fun cycleCountIsReportedOrExplained() {
        assertEquals("418", WpmFormat.cycleCount(418))
        assertEquals("0", WpmFormat.cycleCount(0))
        assertEquals("1,204", WpmFormat.cycleCount(1_204))
        assertEquals("Not reported by this device", WpmFormat.cycleCount(null))
        assertEquals("Not reported by this device", WpmFormat.cycleCount(-1))
    }

    @Test fun energyTotalsStayNonnegativeAndScaleBelowOneWattHour() {
        assertEquals(UnitQuantity("2.35", "mWh"), WpmFormat.energy(0.00235))
        assertEquals(UnitQuantity("840", "\u00b5Wh"), WpmFormat.energy(0.00084))
        assertEquals(UnitQuantity("1.500", "Wh"), WpmFormat.energy(1.5))
        // Missing or invalid totals keep the dash instead of inventing energy.
        assertEquals(WpmFormat.DASH, WpmFormat.energy(-2.8).text)
        assertEquals(WpmFormat.DASH, WpmFormat.energy(Double.NaN).text)
        assertEquals(WpmFormat.DASH, WpmFormat.energy(null).text)
        // A recorded zero is a real zero, not a missing value.
        assertTrue(WpmFormat.energy(0.0).available)
        assertEquals("Wh", WpmFormat.energy(0.0).unit)
    }
}
