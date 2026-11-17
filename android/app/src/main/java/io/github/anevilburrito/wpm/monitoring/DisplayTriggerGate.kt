package io.github.anevilburrito.wpm.monitoring

/**
 * Whether the `ACTION_BATTERY_CHANGED` trigger is worth listening to right now.
 *
 * That trigger exists for *presentation* latency only: a plug, an unplug or a charge
 * step is the moment somebody is looking at the screen, and waiting up to a whole
 * interval makes the dashboard look asleep. It cannot add samples: the sampler
 * still waits out the configured interval between two polls, so a delivery that
 * arrives mid-interval only ends one wait early and is then held by the spacing
 * floor.
 *
 * With the display off nobody is looking, so a delivery can only cost a broadcast
 * dispatch, a main-thread callback and a coroutine resume for a sample that was not
 * allowed to happen sooner. The registration is therefore tied to the display being
 * interactive, which the platform reports with the transition-only
 * `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` broadcasts (tens per day) rather than a
 * poll.
 *
 * Exported as a pure rule so the display gating is unit tested rather than inferred
 * from the service's intent handling.
 */
internal object DisplayTriggerGate {

    /**
     * The trigger may be registered only while the display is interactive.
     *
     * When the platform cannot answer, listening is the safe default: it costs
     * latency at worst, and an unreadable display state must not silently disable a
     * documented behaviour.
     */
    fun shouldListenBatteryTrigger(isDisplayInteractive: Boolean?): Boolean =
        isDisplayInteractive ?: true
}
