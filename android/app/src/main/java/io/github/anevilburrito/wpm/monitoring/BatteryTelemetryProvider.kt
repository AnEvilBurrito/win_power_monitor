package io.github.anevilburrito.wpm.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Public-API battery telemetry only: `BatteryManager` properties plus the sticky
 * `ACTION_BATTERY_CHANGED` broadcast. No root, sysfs or synthesised fallbacks.
 *
 * Three current-related properties are read, and each is kept exactly as the
 * platform returned it — this class interprets nothing:
 *
 * - `CURRENT_NOW` (documented µA) is the only *instantaneous* property, so it is
 *   what the sample's current/energy fields describe. It is a single fuel-gauge
 *   reading between hardware refreshes and some OEMs report it in milliamps;
 *   [BatteryTelemetryDecoder] applies the documented scale, the user's override or
 *   the calibration established by [CurrentCalibrator].
 * - `CHARGE_COUNTER` (µAh) is a cumulative total, so a delta over a measured
 *   window is a *gauge-reported average*. Its units are unambiguous (only the
 *   difference is used) but the counter itself can be coarse, interpolated,
 *   clamped or reset by the gauge, so it is evidence — not ground truth.
 *   [CurrentEstimator] turns deltas into a trailing average.
 * - `CURRENT_AVERAGE` (µA) is already smoothed by the platform and is retained
 *   verbatim for export only; nothing infers a unit from it.
 *
 * The sampling timestamp is the query time; it is not proof that the fuel gauge
 * value changed since the previous poll.
 */
internal class BatteryTelemetryProvider(private val context: Context) {

    /** Returns the raw reading; individual properties stay null when unsupported. */
    fun read(): RawBatteryReading {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val sticky = readStickyBroadcast()
        val manager = batteryManager()
        return RawBatteryReading(
            timestampMillis = nowWall,
            elapsedRealtimeMillis = nowElapsed,
            currentMicroamps = readProperty(
                manager = manager,
                property = BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
                maxMagnitude = MAX_PLAUSIBLE_CURRENT_MICROAMPS,
                allowNegative = true,
            ),
            averageMicroamps = readProperty(
                manager = manager,
                property = BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
                maxMagnitude = MAX_PLAUSIBLE_CURRENT_MICROAMPS,
                allowNegative = true,
            ),
            chargeCounterMicroampHours = readProperty(
                manager = manager,
                property = BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
                maxMagnitude = MAX_PLAUSIBLE_CHARGE_COUNTER_MICROAMP_HOURS,
                allowNegative = false,
            ),
            voltageMillivolts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            temperatureTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            // `EXTRA_CYCLE_COUNT` only exists on API 34+, but reading it is safe on
            // every version: an absent extra keeps the default sentinel, and an
            // older platform can never report a count it does not track.
            cycleCount = BatteryTelemetryDecoder.supportedCycleCountOf(
                sticky?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, Int.MIN_VALUE),
            ),
        )
    }

    private fun batteryManager(): BatteryManager? = try {
        context.getSystemService(BatteryManager::class.java)
    } catch (error: RuntimeException) {
        null
    } catch (error: LinkageError) {
        null
    }

    /**
     * One `Long` property, or null when it is unsupported, a sentinel, or an
     * implausible magnitude for a phone battery. Zero stays a real reading.
     */
    private fun readProperty(
        manager: BatteryManager?,
        property: Int,
        maxMagnitude: Long,
        allowNegative: Boolean,
    ): Long? {
        if (manager == null) return null
        val value = try {
            manager.getLongProperty(property)
        } catch (error: RuntimeException) {
            return null
        } catch (error: LinkageError) {
            return null
        }
        if (!allowNegative && value < 0L) return null
        return when {
            // Both the 64-bit sentinel and its sign-extended 32-bit form.
            value == Long.MIN_VALUE -> null
            value == BatteryTelemetryDecoder.CURRENT_UNSUPPORTED_SENTINEL -> null
            value > maxMagnitude || value < -maxMagnitude -> null
            else -> value
        }
    }

    private fun readStickyBroadcast(): Intent? = try {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Null receiver queries the sticky cache; it registers nothing.
        context.registerReceiver(null, filter)
    } catch (error: RuntimeException) {
        null
    }

    private companion object {
        /** 100 A: far beyond any phone battery, so it signals a bogus property. */
        const val MAX_PLAUSIBLE_CURRENT_MICROAMPS =
            BatteryTelemetryDecoder.MAX_PLAUSIBLE_CURRENT_MICROAMPS

        /**
         * 100 Ah in µAh. Rounding aside, a phone battery is a few thousand mAh, so
         * anything larger is a bogus or byte-swapped counter reading.
         */
        const val MAX_PLAUSIBLE_CHARGE_COUNTER_MICROAMP_HOURS = 100_000_000L
    }
}
