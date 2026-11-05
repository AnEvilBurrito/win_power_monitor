package io.github.anevilburrito.wpm.monitoring

import android.content.Context
import io.github.anevilburrito.wpm.R

/** Localised user-facing text for the values the monitoring layer records. */
object SampleText {
    /** Human text for a stored [SampleReasons] token; unknown tokens pass through. */
    fun reason(context: Context, reason: String?): String? = when (reason) {
        null, "" -> null
        SampleReasons.GAP -> context.getString(R.string.reason_sampling_gap)
        SampleReasons.INTERVAL_CHANGED -> context.getString(R.string.reason_interval_changed)
        SampleReasons.MODE_CHANGED -> context.getString(R.string.reason_mode_changed)
        SampleReasons.INCONSISTENT_POLARITY -> context.getString(R.string.reason_inconsistent_polarity)
        SampleReasons.CURRENT_CALIBRATION_CHANGED ->
            context.getString(R.string.reason_current_calibration_changed)
        else -> reason
    }

    /** Reported battery condition; never a retained-capacity or wear estimate. */
    fun condition(context: Context, condition: String?): String? = when (condition) {
        null, "" -> null
        BatteryCondition.GOOD.name -> context.getString(R.string.condition_good)
        BatteryCondition.OVERHEAT.name -> context.getString(R.string.condition_overheat)
        BatteryCondition.DEAD.name -> context.getString(R.string.condition_dead)
        BatteryCondition.OVER_VOLTAGE.name -> context.getString(R.string.condition_over_voltage)
        BatteryCondition.UNSPECIFIED_FAILURE.name -> context.getString(R.string.condition_unspecified_failure)
        BatteryCondition.COLD.name -> context.getString(R.string.condition_cold)
        else -> context.getString(R.string.condition_unknown)
    }
}
