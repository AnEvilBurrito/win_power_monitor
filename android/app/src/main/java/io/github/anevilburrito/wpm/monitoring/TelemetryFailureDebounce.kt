package io.github.anevilburrito.wpm.monitoring

/**
 * Consecutive-failure debounce for the recoverable "*sampling* failed" notice.
 *
 * Why: a poll can fail for a single tick without anything being wrong — an OEM
 * `BatteryManager` property throws once, the fuel gauge is momentarily busy, a
 * broadcast races the read. Publishing on the first failure made the UI warning
 * flash on and off at every poll, which read as a broken app even though the next
 * sample succeeded. So a notice is only published once
 * [CONSECUTIVE_FAILURES_BEFORE_NOTICE] polls in a row failed, and a single success
 * clears it.
 *
 * Deliberately *not* applied to durable-write failures: those lose recording and
 * must surface immediately (the repository publishes them itself).
 *
 * Pure and Android-free so the rule is JVM tested; the caller owns the message.
 */
internal object TelemetryFailureDebounce {
    /**
     * Two, not one: the first failure is indistinguishable from a transient
     * property hiccup, while two consecutive misses at the sample cadence already
     * span an interval, so the reading really is stale.
     */
    const val CONSECUTIVE_FAILURES_BEFORE_NOTICE = 2

    /** Decision for one poll attempt. */
    enum class Decision {
        /** Failure below the threshold: publish nothing, keep the UI unchanged. */
        SILENT,

        /** Threshold reached: (re)publish the notice. */
        NOTICE,

        /** First success after a notice: clear it. */
        CLEAR,

        /** Success with no notice outstanding: nothing to do. */
        NONE,
    }

    /**
     * @param consecutiveFailures count *including* this failure (so 1 = first).
     * @param alreadyNotified whether a notice for this outage is already published,
     * which keeps a long outage from republishing the same message every poll.
     */
    fun onFailure(consecutiveFailures: Int, alreadyNotified: Boolean): Decision = when {
        consecutiveFailures < CONSECUTIVE_FAILURES_BEFORE_NOTICE -> Decision.SILENT
        alreadyNotified -> Decision.NONE
        else -> Decision.NOTICE
    }

    /** @param hadNotice whether a notice was published for the outage ending now. */
    fun onSuccess(hadNotice: Boolean): Decision = if (hadNotice) Decision.CLEAR else Decision.NONE
}
