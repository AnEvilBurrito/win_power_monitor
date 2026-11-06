package io.github.anevilburrito.wpm.monitoring

/**
 * When the in-memory observed-capacity aggregate must be written to
 * `capacity_state`.
 *
 * Since the estimator only reports a value when a charge or discharge window has
 * completed a 20-percentage-point swing, an observation is rare by construction: it cannot
 * happen more than once per such window. The throttle therefore no longer exists to
 * suppress per-poll writes; it coalesces a burst (two windows finishing inside the
 * same minute, e.g. a resumed session) and nothing more. `endRecording()` still
 * flushes a dirty aggregate so a stopped session is never left behind.
 *
 * Pure and Android-free so the rule is JVM tested.
 */
internal object CapacityPersistence {
    /**
     * At most one durable write per this interval. Short, because a completed
     * observation is the most valuable slow-moving datum the app stores and the
     * in-memory value is what the UI shows: durability should follow it closely.
     */
    const val INTERVAL_MILLIS = 60_000L

    /**
     * @param dirty the in-memory aggregate is newer than the stored one.
     * @param nowElapsedMillis monotonic clock now (`SystemClock.elapsedRealtime`).
     * @param lastPersistElapsedMillis monotonic time of the last durable write, `0`
     * for "never in this process", which makes the first observation durable at
     * once even when the device booted less than [INTERVAL_MILLIS] ago.
     */
    fun isDue(dirty: Boolean, nowElapsedMillis: Long, lastPersistElapsedMillis: Long): Boolean =
        dirty && (
            lastPersistElapsedMillis <= 0L ||
                nowElapsedMillis - lastPersistElapsedMillis >= INTERVAL_MILLIS
            )

    /** Whether a stop/stop-of-session flush has anything to write. */
    fun isDueAtStop(dirty: Boolean): Boolean = dirty
}
