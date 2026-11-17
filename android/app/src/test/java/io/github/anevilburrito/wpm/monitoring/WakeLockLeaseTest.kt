package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wake-lock renewal must keep at least half a lease in hand while recording, and must
 * ask for a fresh lease immediately after any release.
 */
class WakeLockLeaseTest {

    private val lease = 10L * 60 * 1000

    @Test
    fun a_lease_is_required_when_none_is_held() {
        assertTrue(WakeLockLease(lease).isRenewalDue(nowElapsedMillis = 0L))
    }

    @Test
    fun renewal_is_not_due_before_half_the_lease_has_elapsed() {
        val policy = WakeLockLease(lease)
        policy.onArmed(1_000L)
        assertFalse(policy.isRenewalDue(1_000L))
        assertFalse(policy.isRenewalDue(1_000L + lease / 2 - 1))
    }

    @Test
    fun renewal_is_due_exactly_at_half_the_lease() {
        val policy = WakeLockLease(lease)
        policy.onArmed(1_000L)
        assertTrue(policy.isRenewalDue(1_000L + lease / 2))
    }

    @Test
    fun an_expired_lease_is_always_due() {
        val policy = WakeLockLease(lease)
        policy.onArmed(1_000L)
        assertTrue(policy.isRenewalDue(1_000L + lease))
        assertTrue(policy.isRenewalDue(1_000L + lease * 10))
    }

    @Test
    fun release_requires_an_immediate_renewal() {
        val policy = WakeLockLease(lease)
        policy.onArmed(1_000L)
        policy.onReleased()
        assertTrue(policy.isRenewalDue(1_001L))
    }

    @Test
    fun a_backwards_clock_cannot_postpone_renewal() {
        val policy = WakeLockLease(lease)
        policy.onArmed(1_000_000L)
        // Not evidence that the lease is fresh: renew rather than hold a lock that may
        // already have timed out.
        assertTrue(policy.isRenewalDue(500L))
    }

    @Test
    fun a_lease_much_shorter_than_the_poll_interval_still_renews_every_poll() {
        // Defensive: half of 1 ms is 0, so every check is due. Renewing more often than
        // needed is the safe failure mode, never holding a lock that has expired.
        val policy = WakeLockLease(1L)
        policy.onArmed(10L)
        assertTrue(policy.isRenewalDue(10L))
    }
}
