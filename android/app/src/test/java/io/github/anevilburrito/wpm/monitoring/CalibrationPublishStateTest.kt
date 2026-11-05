package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the calibration revision handshake: a user reset must always beat evidence
 * that a sampler gathered before it, and a failed durable write must stay retryable
 * instead of being deduplicated into an in-memory-only value.
 */
class CalibrationPublishStateTest {

    private val empty = CurrentCalibration()
    private val established = CurrentCalibration(
        unit = CurrentUnitOverride.MICROAMPS,
        inverted = false,
        evidenceWindows = 3,
    )
    private val discovered = CurrentCalibration(
        unit = CurrentUnitOverride.MILLIAMPS,
        inverted = true,
        evidenceWindows = 3,
    )

    @Test
    fun aResetAlwaysAdvancesTheRevisionEvenWhenNothingWasStored() {
        val state = CalibrationPublishState()
        state.restore(empty)
        assertEquals(0L, state.revision)

        state.commitExplicit(empty, durableWriteSucceeded = true)
        assertEquals(1L, state.revision)
        assertFalse(state.pendingSave)

        // A second reset is not a no-op: it must invalidate evidence in flight again.
        state.commitExplicit(empty, durableWriteSucceeded = true)
        assertEquals(2L, state.revision)
    }

    @Test
    fun staleEvidenceCannotOverrideAReset() {
        val state = CalibrationPublishState()
        state.restore(established)
        // The sampler observed revision 0 before gathering its own evidence.
        val observed = state.revision
        assertEquals(
            CalibrationPublishState.Precheck.WRITE,
            state.precheck(observed, discovered),
        )

        // The user resets while that publish is in flight.
        state.commitExplicit(empty, durableWriteSucceeded = true)

        // The stale publish is refused, and the cleared value wins.
        assertEquals(
            CalibrationPublishState.Precheck.STALE_REVISION,
            state.precheck(observed, discovered),
        )
        assertEquals(empty, state.value)
    }

    @Test
    fun aFailedDurableWriteIsNotPublishedAndIsRetried() {
        val state = CalibrationPublishState()
        state.restore(empty)

        val result = state.commitDiscovered(discovered, durableWriteSucceeded = false)
        assertEquals(CalibrationPublishState.PublishResult.NOT_SAVED, result)
        // Not published, and still unequal to the candidate, so the next publish is
        // not deduplicated away. A discovered value is not in memory at all, so it
        // does not become a pending save.
        assertEquals(empty, state.value)
        assertFalse(state.pendingSave)
        assertEquals(
            CalibrationPublishState.Precheck.WRITE,
            state.precheck(state.revision, discovered),
        )

        assertEquals(
            CalibrationPublishState.PublishResult.ACCEPTED,
            state.commitDiscovered(discovered, durableWriteSucceeded = true),
        )
        assertEquals(discovered, state.value)
        assertFalse(state.pendingSave)
    }

    @Test
    fun aFailedExplicitWriteStillInvalidatesInFlightEvidence() {
        val state = CalibrationPublishState()
        state.restore(established)
        state.commitExplicit(empty, durableWriteSucceeded = false)

        // The user's intent takes effect at once, even though durability failed.
        assertEquals(empty, state.value)
        assertEquals(1L, state.revision)
        assertTrue(state.pendingSave)
        assertEquals(
            CalibrationPublishState.Precheck.STALE_REVISION,
            state.precheck(0L, discovered),
        )

        // The retry only has to record the success.
        state.markSaved()
        assertFalse(state.pendingSave)
    }

    @Test
    fun anAlreadyPublishedValueIsNotActedOnAgain() {
        val state = CalibrationPublishState()
        state.restore(established)
        assertEquals(
            CalibrationPublishState.Precheck.ALREADY_CURRENT,
            state.precheck(state.revision, established),
        )
        assertEquals(
            CalibrationPublishState.PublishResult.ALREADY_CURRENT,
            state.commitDiscovered(established, durableWriteSucceeded = true),
        )
    }

    @Test
    fun discoveredEvidenceDoesNotAdvanceTheRevision() {
        val state = CalibrationPublishState()
        state.restore(empty)
        state.commitDiscovered(discovered, durableWriteSucceeded = true)
        assertEquals(0L, state.revision)
        assertFalse(state.needsAdoption(adoptedRevision = 0L))
    }

    @Test
    fun aSamplerMustAdoptThePublishedStateOnAnyRevisionChange() {
        // Reset with nothing stored still forces adoption.
        assertTrue(
            CalibrationPublishState.needsAdoption(publishedRevision = 1L, adoptedRevision = 0L),
        )
        assertFalse(
            CalibrationPublishState.needsAdoption(publishedRevision = 3L, adoptedRevision = 3L),
        )

        val state = CalibrationPublishState()
        state.restore(established)
        state.commitExplicit(empty, durableWriteSucceeded = true)
        assertTrue(state.needsAdoption(adoptedRevision = 0L))
        assertFalse(state.needsAdoption(adoptedRevision = 1L))
    }
}
