package io.github.anevilburrito.wpm.monitoring

/**
 * When the high-resolution partial wake lock must actually be re-armed.
 *
 * The lease is deliberately timed ([leaseMillis]): a crash or a wedged sampler must
 * let the system release the lock on its own instead of draining the battery
 * forever, so the timeout cannot be replaced by an untimed `acquire()`. But
 * re-acquiring on every sample meant one IPC to `PowerManagerService` per poll
 * (1800/h at the 2 s default, 3600/h at 1 s) to extend a lease that still had
 * minutes left on it.
 *
 * Re-arming only once half the lease is spent keeps the guarantee (the lock is
 * always held with at least half a lease of headroom while recording) and cuts the
 * IPC rate to `2 / leaseMinutes` per minute — about twelve an hour for a ten-minute
 * lease.
 *
 * Kept Android-free so the arithmetic is unit tested rather than inferred from the
 * service's private state.
 */
internal class WakeLockLease(private val leaseMillis: Long) {

    /** When the current lease was acquired, or null when no lease is held. */
    private var armedAtElapsedMillis: Long? = null

    /**
     * Whether a new lease should be acquired now: always when none is held, never
     * before half of the current one has been spent.
     */
    fun isRenewalDue(nowElapsedMillis: Long): Boolean {
        val armedAt = armedAtElapsedMillis ?: return true
        // A clock that went backwards (or an absurd lease) must not postpone the
        // renewal forever: an elapsed reading below the arming stamp is not evidence
        // that the lease is still fresh.
        if (nowElapsedMillis < armedAt) return true
        return nowElapsedMillis - armedAt >= leaseMillis / 2
    }

    /** Records a successful acquire. */
    fun onArmed(nowElapsedMillis: Long) {
        armedAtElapsedMillis = nowElapsedMillis
    }

    /** Forget the lease: the next request acquires immediately. */
    fun onReleased() {
        armedAtElapsedMillis = null
    }

    companion object {
        /** Fraction of the lease that may elapse before a renewal is needed. */
        const val RENEW_AT_FRACTION = 0.5
    }
}
