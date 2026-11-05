package io.github.anevilburrito.wpm.ui

/**
 * One current-boot clock snapshot for all freshness decisions in a composition.
 *
 * The slow ticker requests redraws; it is NOT the time of the current frame. A
 * sample can be published between ticks. Using the cached tick as "now" then
 * gives that new sample a negative age and makes it look stale for one frame,
 * until a LaunchedEffect catches up. That blanked the monitor and runtime in the
 * supplied recordings on every refresh.
 *
 * Always read the monotonic clock synchronously after receiving the sample
 * snapshot. Keep the ticker read as a Compose invalidation dependency, without
 * allowing time to go backwards within this process. Neither input is a sample
 * timestamp: do not clamp to the sample, which would hide genuinely future-dated
 * history (for example after reboot). The normal stale/future checks still apply.
 */
internal fun freshnessClockMillis(tickerMillis: Long, clockMillis: Long): Long =
    maxOf(tickerMillis, clockMillis)
