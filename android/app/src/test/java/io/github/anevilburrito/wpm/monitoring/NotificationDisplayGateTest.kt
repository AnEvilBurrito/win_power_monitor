package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification freshness is a presentation choice, so it must not be paid for while
 * no display is interactive — but it must never become arbitrarily stale either.
 */
class NotificationDisplayGateTest {

    private val minute = NotificationDisplayGate.SCREEN_OFF_FLOOR_MILLIS

    @Test
    fun the_screen_off_floor_is_the_documented_default_rate() {
        // A screen-off notification may never be staler than a default-rate user
        // already accepts; only the explicitly faster rates are suppressed.
        assertEquals(60_000L, minute)
    }

    @Test
    fun every_post_is_allowed_while_the_display_is_interactive() {
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = true,
                nowElapsedMillis = 1_000L,
                lastPostElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun an_interactive_display_ignores_the_floor() {
        // The selected rate, not this gate, decides freshness while someone is looking.
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = true,
                nowElapsedMillis = 61_000L,
                lastPostElapsedMillis = 60_000L,
            ),
        )
    }

    @Test
    fun the_screen_off_rate_is_floored_to_one_post_per_minute() {
        assertFalse(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = false,
                nowElapsedMillis = 2_000L,
                lastPostElapsedMillis = 1_000L,
            ),
        )
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = false,
                nowElapsedMillis = 1_000L + minute,
                lastPostElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun an_unknown_display_state_posts() {
        // An unreadable display state must not silently freeze a documented
        // behaviour; posting costs one update at worst.
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = null,
                nowElapsedMillis = 1_000L,
                lastPostElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun a_backwards_clock_does_not_stall_the_notification() {
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = false,
                nowElapsedMillis = 500L,
                lastPostElapsedMillis = 90_000L,
            ),
        )
    }

    @Test
    fun the_first_post_after_start_is_allowed_with_the_screen_off() {
        // `elapsedRealtime` since boot may be smaller than the floor on a device that
        // rebooted moments ago; "never posted" must not delay the first update.
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = false,
                nowElapsedMillis = 5_000L,
                lastPostElapsedMillis = 0L,
            ),
        )
    }

    @Test
    fun instant_polling_with_the_screen_off_posts_at_most_once_per_minute() {
        // 1 s polls for ten minutes of screen-off recording: the selected "Instant"
        // rate would have posted 600 times, the floor allows 10.
        var posts = 0
        var lastPosted = 0L
        for (second in 1..600) {
            val now = second * 1_000L
            if (NotificationDisplayGate.shouldPost(
                    isDisplayInteractive = false,
                    nowElapsedMillis = now,
                    lastPostElapsedMillis = lastPosted,
                )
            ) {
                posts += 1
                lastPosted = now
            }
        }
        // One immediately (nothing posted yet), then one per floor: 10 in the minute
        // the loop starts plus every full floor after it.
        assertEquals(10, posts)
    }

    @Test
    fun the_screen_on_replay_is_not_blocked_by_the_floor() {
        // The transition replays a suppressed update immediately, so the gate must
        // not be what stops it: the caller passes the fresh display state.
        assertTrue(
            NotificationDisplayGate.shouldPost(
                isDisplayInteractive = true,
                nowElapsedMillis = 11_000L,
                lastPostElapsedMillis = 10_500L,
            ),
        )
    }
}
