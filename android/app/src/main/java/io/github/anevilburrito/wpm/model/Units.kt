package io.github.anevilburrito.wpm.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A formatted value plus the SI unit it was scaled to.
 *
 * The unit is kept separate from the text so screens can render the number large
 * and the suffix small, and so the chart axis can label itself. Null telemetry is
 * never turned into a number: [text] is the em dash placeholder.
 */
data class UnitQuantity(val text: String, val unit: String) {
    val available: Boolean get() = text != Units.DASH
}

/** Display unit for power. Phones idle in the milliwatt range, not the watt range. */
enum class PowerUnit(val suffix: String, val perWatt: Double) {
    WATTS("W", 1.0),
    MILLIWATTS("mW", 1_000.0),
    MICROWATTS("\u00b5W", 1_000_000.0),
    ;

    companion object {
        /** Unit that keeps a reading legible without inventing precision. */
        fun forWatts(value: Double?): PowerUnit {
            val magnitude = abs(value ?: 0.0)
            if (!magnitude.isFinite()) return WATTS
            return when {
                magnitude >= 1.0 -> WATTS
                magnitude >= 1e-3 -> MILLIWATTS
                magnitude > 0.0 -> MICROWATTS
                else -> WATTS
            }
        }
    }
}

/** Display unit for energy totals. */
enum class EnergyUnit(val suffix: String, val perWattHour: Double) {
    WATT_HOURS("Wh", 1.0),
    MILLIWATT_HOURS("mWh", 1_000.0),
    MICROWATT_HOURS("\u00b5Wh", 1_000_000.0),
    ;

    companion object {
        fun forWattHours(value: Double?): EnergyUnit {
            val magnitude = abs(value ?: 0.0)
            if (!magnitude.isFinite()) return WATT_HOURS
            return when {
                magnitude >= 1.0 -> WATT_HOURS
                magnitude >= 1e-3 -> MILLIWATT_HOURS
                magnitude > 0.0 -> MICROWATT_HOURS
                else -> WATT_HOURS
            }
        }
    }
}

/** Display unit for current. */
enum class CurrentUnit(val suffix: String, val perAmp: Double) {
    AMPERES("A", 1.0),
    MILLIAMPS("mA", 1_000.0),
    MICROAMPS("\u00b5A", 1_000_000.0),
    ;

    companion object {
        fun forAmps(value: Double?): CurrentUnit {
            val magnitude = abs(value ?: 0.0)
            if (!magnitude.isFinite()) return AMPERES
            return when {
                magnitude >= 1.0 -> AMPERES
                magnitude >= 1e-3 -> MILLIAMPS
                magnitude > 0.0 -> MICROAMPS
                else -> AMPERES
            }
        }
    }
}

/**
 * Locale-independent, magnitude-aware formatting for phone-scale readings.
 *
 * Shared by the UI, the notification and the tests so one rule set decides units.
 * Values are always formatted from SI inputs (watts, watt-hours, amps); the unit
 * suffix that belongs with the text is returned alongside it.
 */
object Units {
    /** Placeholder for unavailable telemetry. Never a fabricated zero. */
    const val DASH = "\u2014"
    const val MINUS = "\u2212"

    fun power(watts: Double?, absolute: Boolean): UnitQuantity =
        quantity(
            value = watts,
            absolute = absolute,
            base = PowerUnit.WATTS.ordinal,
            perBase = PowerUnit.entries.map { it.perWatt },
            suffixes = PowerUnit.entries.map { it.suffix },
            digitsOf = ::powerDigits,
        )

    fun energy(wattHours: Double?): UnitQuantity =
        quantity(
            value = wattHours,
            absolute = true,
            base = EnergyUnit.WATT_HOURS.ordinal,
            perBase = EnergyUnit.entries.map { it.perWattHour },
            suffixes = EnergyUnit.entries.map { it.suffix },
            digitsOf = ::energyDigits,
        )

    fun current(amps: Double?, absolute: Boolean): UnitQuantity =
        quantity(
            value = amps,
            absolute = absolute,
            base = CurrentUnit.AMPERES.ordinal,
            perBase = CurrentUnit.entries.map { it.perAmp },
            suffixes = CurrentUnit.entries.map { it.suffix },
            digitsOf = ::powerDigits,
        )

    /** Unit used to label a chart axis for [lower]..[upper] watts. */
    fun axisUnit(lower: Double, upper: Double): PowerUnit =
        PowerUnit.forWatts(maxMagnitude(lower, upper))

    /**
     * Axis tick label in [unit]; decimals shrink as the value grows so a 0..4 mW
     * axis reads `0 / 1 / 2` instead of `0.00 / 0.50 / 1.00`.
     */
    fun axisTick(watts: Double, unit: PowerUnit, absolute: Boolean): String {
        if (!watts.isFinite()) return DASH
        val scaled = watts * unit.perWatt
        val digits = when {
            abs(scaled) >= 10.0 -> 0
            else -> 1
        }
        return trimZeros(label(scaled, scaled < 0.0, absolute, digits))
    }

    /** `0.315` -> `315 µA`; keeps the sign rules of [current]. */
    fun currentText(amps: Double?, absolute: Boolean): String {
        val quantity = current(amps, absolute)
        return if (quantity.available) "${quantity.text} ${quantity.unit}" else quantity.text
    }

    fun powerText(watts: Double?, absolute: Boolean): String {
        val quantity = power(watts, absolute)
        return if (quantity.available) "${quantity.text} ${quantity.unit}" else quantity.text
    }

    fun energyText(wattHours: Double?): String {
        val quantity = energy(wattHours)
        return if (quantity.available) "${quantity.text} ${quantity.unit}" else quantity.text
    }

    private fun maxMagnitude(lower: Double, upper: Double): Double {
        val candidates = listOf(abs(lower), abs(upper)).filter { it.isFinite() }
        return candidates.maxOrNull() ?: 0.0
    }

    /**
     * Rounds into the smallest unit that stays under 1000 of that unit, so a
     * reading never renders as `1000 mW`. Zero keeps the base unit.
     */
    private fun quantity(
        value: Double?,
        absolute: Boolean,
        base: Int,
        perBase: List<Double>,
        suffixes: List<String>,
        digitsOf: (Int, Double) -> Int,
    ): UnitQuantity {
        if (value == null || !value.isFinite()) return UnitQuantity(DASH, suffixes[base])
        var index = indexFor(abs(value), perBase, base)
        var scaled = abs(value) * perBase[index]
        var digits = digitsOf(index, scaled)
        // Promote instead of printing "1000 mW" after rounding.
        while (index + 1 < perBase.size && roundedMagnitude(scaled, digits) >= 1000.0) {
            index += 1
            scaled = abs(value) * perBase[index]
            digits = digitsOf(index, scaled)
        }
        return UnitQuantity(label(scaled, value < 0.0, absolute, digits), suffixes[index])
    }

    private fun indexFor(magnitude: Double, perBase: List<Double>, base: Int): Int {
        if (magnitude == 0.0) return base
        var index = base
        while (index + 1 < perBase.size && magnitude * perBase[index] < 1.0) {
            index += 1
        }
        return index
    }

    /** Watt/amp tiers: base 2, middle 2 (1 above 10), smallest 0. */
    private fun powerDigits(index: Int, scaledMagnitude: Double): Int = when (index) {
        0 -> 2
        1 -> if (scaledMagnitude >= 10.0) 1 else 2
        else -> 0
    }

    /** Energy keeps three decimals in watt-hours so small totals stay readable. */
    private fun energyDigits(index: Int, scaledMagnitude: Double): Int = when (index) {
        0 -> 3
        1 -> if (scaledMagnitude >= 100.0) 1 else 2
        else -> 0
    }

    private fun roundedMagnitude(scaled: Double, digits: Int): Double {
        val factor = POWERS[digits.coerceIn(0, POWERS.lastIndex)]
        return (scaled * factor).roundToLong() / factor
    }

    private fun label(scaledValue: Double, negative: Boolean, absolute: Boolean, digits: Int): String {
        val formatted = fixed(abs(scaledValue), digits)
        if (formatted.isZeroDigits()) return formatted
        if (absolute) return formatted
        return if (negative) MINUS + formatted else "+$formatted"
    }

    private fun fixed(value: Double, digits: Int): String =
        String.format(Locale.ROOT, "%.${digits.coerceIn(0, 6)}f", abs(value))

    private fun String.isZeroDigits(): Boolean = all { it == '0' || it == '.' }

    /** `1.0` -> `1`, `2.50` -> `2.5`; axis ticks stay compact. */
    private fun trimZeros(text: String): String {
        if (!text.contains('.')) return text
        return text.trimEnd('0').trimEnd('.')
    }
}

private val POWERS = doubleArrayOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0)
