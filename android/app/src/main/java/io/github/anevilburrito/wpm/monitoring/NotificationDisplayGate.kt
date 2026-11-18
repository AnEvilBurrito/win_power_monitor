package io.github.anevilburrito.wpm.monitoring

/**
 * Whether a *routine* notification post is worth doing right now.
 *
 * The update rate is a user choice (Instant/15 s/60 s) about how fresh the shade
 * looks. With the display off, or on the always-on display behind a closed shade,
 * that freshness buys nothing: the post still costs a `notify()` binder call, a
 * `buildNotification` allocation and a SystemUI row relayout, and at "Instant" that
 * happens 1800-3600 times an hour while nobody can see the result. It is the
 * notification sibling of [DisplayTriggerGate]: presentation work gated on the
 * presentation actually being visible.
 *
 * A hard gate was rejected because it would let the notification age without bound
 * while the screen is off, which is a usability change: a lockscreen or AOD glance
 * could show a value from hours ago. Instead the screen-off rate is floored at
 * [SCREEN_OFF_FLOOR_MILLIS], the documented default rate. The worst case with the
 * screen off is therefore exactly what a default-rate user already sees, while the
 * "Instant"/15 s over-rides stop being paid for while invisible. The service also
 * reposts once when a display becomes interactive, so the shade is current the
 * moment it is visible.
 *
 * Nothing here decides *content*: [NotificationUpdatePolicy] still owns the routine
 * throttle and its force/bypass transitions, and it is consulted only for posts this
 * gate allows. Suppressed posts therefore also leave that policy's "what the user
 * last saw" bookkeeping untouched, so the first visible post compares against what
 * is really on screen.
 *
 * Exported as a pure rule so the gating is unit tested rather than inferred from the
 * service's intent handling.
 */
internal object NotificationDisplayGate {

    /**
     * Maximum staleness of the notification while no display is interactive.
     *
     * Equal to the documented default update rate, so a screen-off notification is
     * never staler than the default choice already allows. Only the faster,
     * explicitly-selected rates are suppressed.
     */
    const val SCREEN_OFF_FLOOR_MILLIS = 60_000L

    /**
     * Whether a post may happen now.
     *
     * @param isDisplayInteractive `null` when the platform cannot answer; posting is
     *   the safe default then, because an unreadable display state must not silently
     *   freeze a documented behaviour.
     * @param lastPostElapsedMillis when the last post that actually happened was
     *   made, on the same monotonic clock; `0` means "nothing posted yet" and never
     *   floors the next post.
     */
    fun shouldPost(
        isDisplayInteractive: Boolean?,
        nowElapsedMillis: Long,
        lastPostElapsedMillis: Long,
    ): Boolean {
        val interactive = isDisplayInteractive ?: return true
        if (interactive) return true
        // Nothing has been posted yet (fresh service, or a device that booted less than
        // a floor ago): a floor measured from zero would delay the first update.
        if (lastPostElapsedMillis <= 0L) return true
        // A clock that moved backwards must not stall the notification until it
        // catches up; allow the post and let the caller re-stamp it.
        if (nowElapsedMillis < lastPostElapsedMillis) return true
        return nowElapsedMillis - lastPostElapsedMillis >= SCREEN_OFF_FLOOR_MILLIS
    }
}
