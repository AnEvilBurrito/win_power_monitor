package io.github.anevilburrito.wpm.monitoring

/**
 * Stable tokens for the gaps and caveats recorded alongside a sample.
 *
 * Tokens (not prose) are stored and exported so retention/CSV stay unambiguous;
 * [label] supplies a short human description for CSV and diagnostics, and
 * [SampleText.reason] maps the same token to a localised UI string.
 */
object SampleReasons {
    const val GAP = "gap"
    const val INTERVAL_CHANGED = "interval_changed"
    const val MODE_CHANGED = "mode_changed"
    const val INCONSISTENT_POLARITY = "inconsistent_polarity"

    /** The effective current scale or sign changed, so watts before and after differ. */
    const val CURRENT_CALIBRATION_CHANGED = "current_calibration_changed"

    /** Short English description used in CSV export and diagnostics. */
    fun label(reason: String?): String? = when (reason) {
        null, "" -> null
        GAP -> "sampling gap"
        INTERVAL_CHANGED -> "sampling interval changed"
        MODE_CHANGED -> "monitoring mode changed"
        INCONSISTENT_POLARITY -> "current sign conflicts with the reported power state"
        CURRENT_CALIBRATION_CHANGED -> "current scale or polarity interpretation changed"
        else -> reason
    }
}
