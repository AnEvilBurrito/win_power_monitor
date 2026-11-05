package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.EnergyUnit
import io.github.anevilburrito.wpm.model.UnitQuantity
import io.github.anevilburrito.wpm.model.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-independent presentation formatting.
 *
 * Pure Kotlin with no Android or Compose dependency so the exact rendering rules
 * stay JVM-testable. Power, current, energy and chart ticks delegate to
 * [Units] so one magnitude-aware rule set decides which unit a phone-scale
 * reading is shown in: a 1.26 mW idle load reads `1.26` `mW`, never `0.00` `W`.
 * The unit is returned next to the number so screens render the value large and
 * the suffix small, and so a missing value can drop the suffix entirely.
 * Negative numbers use the typographic minus U+2212 in signed mode; absolute mode
 * always shows a non-negative value.
 */
object WpmFormat {
    /** Placeholder for unavailable telemetry. Never a fabricated zero. */
    const val DASH = Units.DASH
    const val MINUS = Units.MINUS
    const val SEPARATOR = "/"

    /** Plain-language absence for a property this platform or OEM does not report. */
    const val CYCLE_COUNT_UNAVAILABLE = "Not reported by this device"

    private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** Power with an auto-selected unit (W / mW / µW); [absolute] drops the sign. */
    fun power(value: Double?, absolute: Boolean): UnitQuantity = Units.power(value, absolute)

    /** Current with an auto-selected unit (A / mA / µA). */
    fun current(value: Double?, absolute: Boolean): UnitQuantity = Units.current(value, absolute)

    /**
     * Energy with an auto-selected unit (Wh / mWh / µWh). Out/in totals are
     * separate nonnegative quantities, so an invalid total stays a dash instead
     * of being turned into invented positive energy.
     */
    fun energy(wattHours: Double?): UnitQuantity {
        if (wattHours == null || !wattHours.isFinite() || wattHours < 0.0) {
            return UnitQuantity(DASH, EnergyUnit.WATT_HOURS.suffix)
        }
        return Units.energy(wattHours)
    }

    /** `12.6 mW`; a missing value keeps the em dash and drops the suffix. */
    fun withUnit(quantity: UnitQuantity): String =
        if (quantity.available) "${quantity.text} ${quantity.unit}" else DASH

    /** Volts, or a dash when the device reported a nonpositive value. */
    fun voltage(value: Double?, digits: Int = 2): String =
        if (value == null || !value.isFinite() || value <= 0.0) DASH else fixed(abs(value), digits)

    fun percent(value: Int?): String =
        if (value == null || value !in 0..100) DASH else value.toString()

    /**
     * Temperature with one decimal; a cold battery below freezing keeps its sign
     * instead of being printed as a positive number. Missing stays the em dash.
     */
    fun temperature(celsius: Double?): String {
        if (celsius == null || !celsius.isFinite()) return DASH
        val magnitude = fixed(celsius, 1)
        if (magnitude.all { it == '0' || it == '.' }) return magnitude
        return if (celsius < 0.0) MINUS + magnitude else magnitude
    }

    /** `00:41:06`; invalid input renders as a zeroed clock. */
    fun durationHms(seconds: Double): String {
        val total = positiveSeconds(seconds)
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            total / 3600L,
            (total % 3600L) / 60L,
            total % 60L,
        )
    }

    /** `38m 36s`, `2h 05m`, `12s`. */
    fun durationCompact(seconds: Double): String {
        val total = positiveSeconds(seconds)
        return when {
            total < 60L -> "${total}s"
            total < 3600L -> "${total / 60L}m ${twoDigits(total % 60L)}s"
            else -> "${total / 3600L}h ${twoDigits((total % 3600L) / 60L)}m"
        }
    }

    /**
     * Capacity in mAh, e.g. `4,380 mAh`.
     *
     * Used for design capacity, the single-point gauge estimate and the observed
     * charging-session capacity; the caller's row label says which one it is. A
     * fraction of a milliamp hour is noise, so the value is rounded to a whole mAh
     * and grouped rather than shown with invented decimals. A missing or
     * nonpositive value stays the em dash instead of becoming `0 mAh`.
     */
    fun milliampHours(value: Double?): String {
        if (value == null || !value.isFinite() || value <= 0.0) return DASH
        val rounded = value.roundToLong()
        return if (rounded <= 0L) DASH else "${group(rounded)} mAh"
    }

    /**
     * Reported cycle count, or the plain-language absence. Android only exposes
     * this from API 34 and not every OEM implements the property.
     */
    fun cycleCount(value: Int?): String =
        if (value == null || value < 0) CYCLE_COUNT_UNAVAILABLE else group(value.toLong())

    /** Age of a reading, e.g. `2s`, `2m 30s`, `1h 04m`. */
    fun age(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite() || seconds < 0.0) return DASH
        return durationCompact(seconds)
    }

    /** `09:41` wall-clock label; the caller supplies the zone for testability. */
    fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** `09:41:06` wall-clock label used for frozen/stopped timestamps. */
    fun clockWithSeconds(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Observed coverage percentage, `null` when there is no elapsed time. */
    fun coveragePercent(observedSeconds: Double, elapsedSeconds: Double): Int? {
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0.0) return null
        val ratio = (observedSeconds / elapsedSeconds).coerceIn(0.0, 1.0)
        return (ratio * 100.0).roundToLong().toInt()
    }

    private fun fixed(value: Double, digits: Int): String =
        String.format(Locale.ROOT, "%.${digits.coerceIn(0, 6)}f", abs(value))

    private fun positiveSeconds(seconds: Double): Long =
        if (!seconds.isFinite() || seconds <= 0.0) 0L else seconds.roundToLong()

    private fun twoDigits(value: Long): String = String.format(Locale.ROOT, "%02d", value)

    private fun group(value: Long): String {
        val digits = abs(value).toString()
        val out = StringBuilder(digits.length + digits.length / 3)
        digits.forEachIndexed { index, char ->
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
            out.append(char)
        }
        return out.toString()
    }
}
