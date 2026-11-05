package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.CurrentCalibration

/**
 * Result of one sampler-discovered calibration publish.
 *
 * The sampler compares the returned snapshot with what it decoded: a
 * [CalibrationPublishResult.STALE_REVISION] answer means its sample-local evidence
 * lost to an explicit reset/save and must be abandoned rather than used.
 */
internal data class CalibrationPublish(
    val result: CalibrationPublishResult,
    val calibration: CurrentCalibration,
    val revision: Long,
)

/** How one calibration publish attempt was resolved. */
internal enum class CalibrationPublishResult {
    /** Adopted and durable: use [CalibrationPublish.calibration]. */
    ACCEPTED,

    /** Already the published value: use [CalibrationPublish.calibration]. */
    ALREADY_CURRENT,

    /** An explicit user reset/save won the race: abandon the stale evidence. */
    STALE_REVISION,

    /** The durable write failed: the value is not published yet and is retried. */
    NOT_SAVED,
}

/**
 * Pure publication state for the established current calibration.
 *
 * The calibration is *evidence*, not a setting: the sampler discovers it while
 * recording, the repository persists it, and the user can reset it. That gives two
 * writers and one dangerous race: a sampler whose evidence was gathered *before* a
 * reset can publish it *after* the reset and silently resurrect the cleared value.
 *
 * ## The revision handshake
 *
 * A single monotonic [revision] serialises the two writers:
 *
 * - Every **explicit user write** (reset or save) advances [revision] — always,
 *   even when the value was already empty. That is what invalidates evidence that
 *   is still in flight; a reset that merely "did nothing because there was nothing
 *   saved" would leave an in-flight sample free to fill the value back in.
 * - A **discovered** publish carries the revision the sampler observed and is only
 *   applied when it still matches ([Precheck.WRITE]). A sampler that lost the race
 *   gets [PublishResult.STALE_REVISION] and must abandon its sample-local evidence
 *   instead of overriding the user's reset.
 *
 * ## Persistence ordering
 *
 * The durable preference write is the caller's job because it is Android-bound and
 * must run off the main thread. This class only records its outcome: [pendingSave]
 * means "the value exists in memory but not on disk". Keeping memory dirty on
 * failure is what lets the caller retry later; if the value were applied as success
 * first, the next publish would compare equal and be deduplicated out, so the value
 * would never be persisted at all.
 *
 * Pure and Android-free, so every rule above is unit tested on the JVM.
 */
internal class CalibrationPublishState {

    /** The calibration that must be published (and interpreted) right now. */
    var value: CurrentCalibration = CurrentCalibration()
        private set

    /** Monotonic revision of the explicit user writes. Not persisted: see the class doc. */
    var revision: Long = 0L
        private set

    /** True when [value] is in memory but its durable write failed. */
    var pendingSave: Boolean = false
        private set

    /** Restores the persisted value at the start of a process. */
    fun restore(stored: CurrentCalibration) {
        value = stored
        revision = 0L
        pendingSave = false
    }

    /** What a discovered publish may do. */
    enum class Precheck {
        /** The durable write may be attempted, then [commitDiscovered]. */
        WRITE,

        /** Nothing to change (any pending retry has already been attempted). */
        ALREADY_CURRENT,

        /** An explicit user write landed first: never override it. */
        STALE_REVISION,
    }

    /** Outcome of one discovered publish. */
    enum class PublishResult {
        /** Adopted; the sampler may decode with it. */
        ACCEPTED,

        /** Already the published value; nothing changed. */
        ALREADY_CURRENT,

        /** A user reset/save won the race; the sample's evidence is stale. */
        STALE_REVISION,

        /** The durable write failed; the value is *not* published and is retried. */
        NOT_SAVED,
    }

    /** Whether the sampler must adopt the published state instead of its own. */
    fun needsAdoption(adoptedRevision: Long): Boolean =
        needsAdoption(publishedRevision = revision, adoptedRevision = adoptedRevision)

    /**
     * Decides what a discovered publish may do for evidence gathered under
     * [expectedRevision]. Checked inside the repository lock, so a reset that
     * completes first is always seen.
     */
    fun precheck(expectedRevision: Long, candidate: CurrentCalibration): Precheck = when {
        expectedRevision != revision -> Precheck.STALE_REVISION
        // Startup interpretations must never enter automatic durable calibration.
        !candidate.calibrated || candidate.evidenceWindows < CurrentCalibrator.MIN_WINDOWS ->
            Precheck.ALREADY_CURRENT
        candidate == value -> Precheck.ALREADY_CURRENT
        else -> Precheck.WRITE
    }

    /**
     * Applies the outcome of the durable write attempted after
     * [Precheck.WRITE]. A failed write leaves [value] and [revision] untouched, so
     * the next attempt compares unequal and retries; [pendingSave] is about explicit
     * user writes only, because a discarded discovered value is not in memory at all.
     */
    fun commitDiscovered(
        candidate: CurrentCalibration,
        durableWriteSucceeded: Boolean,
    ): PublishResult = when {
        !candidate.calibrated || candidate.evidenceWindows < CurrentCalibrator.MIN_WINDOWS ->
            PublishResult.ALREADY_CURRENT
        candidate == value -> PublishResult.ALREADY_CURRENT
        !durableWriteSucceeded -> PublishResult.NOT_SAVED
        else -> {
            value = candidate
            // The durable write of this value succeeded, so any earlier pending
            // save is superseded and the value is truthful on disk.
            pendingSave = false
            PublishResult.ACCEPTED
        }
    }

    /**
     * Applies an explicit user write. [revision] always advances, even when the new
     * value equals the old one, so no in-flight sample can be published against it.
     * A failed durable write only sets [pendingSave]: the user's intent takes effect
     * in memory at once and is retried later.
     */
    fun commitExplicit(candidate: CurrentCalibration, durableWriteSucceeded: Boolean) {
        value = candidate
        revision = nextRevision(revision)
        pendingSave = !durableWriteSucceeded
    }

    /** Records that a retried durable write of [value] succeeded. */
    fun markSaved() {
        pendingSave = false
    }

    private fun nextRevision(current: Long): Long =
        // Saturation rather than wrap: a revision that went backwards would let
        // stale evidence pass the equality check.
        if (current == Long.MAX_VALUE) current else current + 1

    companion object {
        /**
         * The revision handshake rule itself, for callers (the sampler) that only
         * have the published revision rather than this in-memory state.
         */
        fun needsAdoption(publishedRevision: Long, adoptedRevision: Long): Boolean =
            publishedRevision != adoptedRevision
    }
}
