package io.github.anevilburrito.wpm.monitoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The battery-change trigger buys presentation latency only, so it must not be
 * consumed while nobody can see the display.
 */
class DisplayTriggerGateTest {

    @Test
    fun the_trigger_is_listened_to_while_the_display_is_interactive() {
        assertTrue(DisplayTriggerGate.shouldListenBatteryTrigger(isDisplayInteractive = true))
    }

    @Test
    fun the_trigger_is_released_while_the_display_is_off() {
        assertFalse(DisplayTriggerGate.shouldListenBatteryTrigger(isDisplayInteractive = false))
    }

    @Test
    fun an_unknown_display_state_keeps_listening() {
        // A missing PowerManager answer must not silently disable a documented
        // behaviour; listening costs latency at worst.
        assertTrue(DisplayTriggerGate.shouldListenBatteryTrigger(isDisplayInteractive = null))
    }
}
