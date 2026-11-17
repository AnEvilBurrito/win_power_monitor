package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample

/**
 * Immutable, chunked list of the samples the UI may see.
 *
 * The published snapshot has to be immutable (the dashboard projects it into a chart
 * on its own thread) and bounded (an open-ended recording must not grow the process),
 * but copying the whole buffer on every poll made the sampler pay O(n) twice per
 * sample: once to evict the oldest reading and once to publish a new list. At the
 * 8192-sample cap that was ~64 KiB copied plus ~64 KiB shifted, per poll, on the
 * service's main dispatcher — even when nothing was collecting.
 *
 * This structure makes both operations cheap:
 *
 * - appending copies only the tail chunk and the (short) list of chunk references,
 *   so the cost is independent of the number of samples held;
 * - evicting the oldest reading only advances an offset, or drops one head chunk
 *   reference when the offset reaches the end of that chunk;
 * - publishing hands out the same instance, because it never changes.
 *
 * A published instance stays valid forever: later appends build a new instance and
 * never write into an array a reader already holds. Chunks are never mutated in
 * place once they are part of a series — the tail chunk is copied on write.
 *
 * Random access is O(1) (index arithmetic plus one array read), iteration is O(n)
 * through this List's own iterator, and equality is structural like any other list,
 * so a caller can treat it exactly like the `ArrayList` this replaced.
 */
internal class SampleSeries private constructor(
    /** Chunk arrays, oldest first. Never empty when [size] is not zero. */
    private val chunks: List<Array<BatterySample>>,
    /** Index of the oldest live element inside `chunks[0]`; always below [CHUNK]. */
    private val headOffset: Int,
    override val size: Int,
) : AbstractList<BatterySample>() {

    override fun get(index: Int): BatterySample {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("index $index, size $size")
        }
        // The head chunk may have been partially evicted, so it holds fewer elements
        // than a full chunk. Every chunk between it and the tail is full, which makes
        // the remaining index arithmetic a plain division.
        val headLive = minOf(size, CHUNK - headOffset)
        if (index < headLive) return chunks[0][headOffset + index]
        val rest = index - headLive
        return chunks[1 + rest / CHUNK][rest % CHUNK]
    }

    /**
     * Returns a new series with [sample] at the end, evicting the oldest reading when
     * the result would exceed [capacity].
     *
     * [capacity] is the published bound, so it is enforced here rather than by the
     * caller: a series can never grow past it, and one append can drop at most one
     * reading (the same rule the previous `while (size > MAX) removeAt(0)` applied).
     */
    fun appended(sample: BatterySample, capacity: Int): SampleSeries {
        require(capacity > 0) { "capacity must be positive" }
        var head = headOffset
        var live = size
        var liveChunks = chunks
        if (live >= capacity) {
            head += 1
            live -= 1
            if (head >= CHUNK) {
                // The head chunk is exhausted: drop the reference instead of
                // shifting the remaining elements.
                head = 0
                liveChunks = liveChunks.drop(1)
            }
        }
        if (live == 0) {
            return SampleSeries(listOf(chunkStartingWith(sample)), 0, 1)
        }
        // Where the new reading goes: the *used slots* of the tail chunk. The head
        // chunk keeps its evicted prefix, so its used slot count includes that
        // offset; every later chunk is full up to the tail.
        val tailWriteIndex = if (liveChunks.size == 1) {
            head + live
        } else {
            live - ((CHUNK - head) + (liveChunks.size - 2) * CHUNK)
        }
        val written: List<Array<BatterySample>> = if (tailWriteIndex >= CHUNK) {
            // Tail chunk is full: start a new one. The old arrays are untouched.
            ArrayList<Array<BatterySample>>(liveChunks.size + 1).apply {
                addAll(liveChunks)
                add(chunkStartingWith(sample))
            }
        } else {
            val tail = liveChunks.last().copyOf()
            tail[tailWriteIndex] = sample
            ArrayList<Array<BatterySample>>(liveChunks.size).apply {
                addAll(liveChunks.subList(0, liveChunks.size - 1))
                add(tail)
            }
        }
        return SampleSeries(written, head, live + 1)
    }

    companion object {
        /**
         * Samples per chunk. Large enough that the per-append array copy is small
         * (256 references, 2 KiB on a 64-bit build) and small enough that eviction
         * reclaims memory promptly.
         */
        const val CHUNK = 256

        val EMPTY = SampleSeries(emptyList(), 0, 0)

        /**
         * A chunk with [CHUNK] slots.
         *
         * Every chunk must have its full length from the start, because appends write
         * into a slot by index and a short chunk would throw instead of growing. The
         * unused slots stay null and are never read: [get] only reaches indices below
         * `size`, and a slot is written before it becomes reachable.
         */
        @Suppress("UNCHECKED_CAST")
        private fun newChunk(): Array<BatterySample> =
            arrayOfNulls<BatterySample>(CHUNK) as Array<BatterySample>

        /** A full-length chunk whose first slot is [sample]. */
        private fun chunkStartingWith(sample: BatterySample): Array<BatterySample> =
            newChunk().also { it[0] = sample }

        /** Builds a series from existing rows, e.g. a restored session. */
        fun of(samples: List<BatterySample>): SampleSeries {
            if (samples.isEmpty()) return EMPTY
            var series = EMPTY
            for (sample in samples) series = series.appended(sample, samples.size)
            return series
        }
    }
}
