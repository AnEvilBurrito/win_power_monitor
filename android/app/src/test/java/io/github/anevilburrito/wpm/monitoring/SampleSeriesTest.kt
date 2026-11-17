package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The published sample buffer must behave exactly like the bounded `ArrayList` it
 * replaced, while appending and evicting in time independent of its length.
 */
class SampleSeriesTest {

    private fun sample(elapsedMillis: Long) = BatterySample(
        timestampMillis = elapsedMillis,
        elapsedRealtimeMillis = elapsedMillis,
        amps = -0.4,
        watts = -1.5,
        volts = 3.8,
        percentage = 60,
        currentCalibrated = true,
        chargeCounterMicroampHours = elapsedMillis,
    )

    private fun seriesOf(count: Int, start: Long = 0L): SampleSeries {
        var series = SampleSeries.EMPTY
        repeat(count) { series = series.appended(sample(start + it), Int.MAX_VALUE) }
        return series
    }

    @Test
    fun empty_series_has_no_elements() {
        assertTrue(SampleSeries.EMPTY.isEmpty())
        assertEquals(0, SampleSeries.EMPTY.size)
    }

    @Test
    fun appends_keep_insertion_order_and_random_access() {
        val series = seriesOf(1_000)
        assertEquals(1_000, series.size)
        series.forEachIndexed { index, value ->
            assertEquals(index.toLong(), value.elapsedRealtimeMillis)
            assertEquals(value.elapsedRealtimeMillis, series[index].elapsedRealtimeMillis)
        }
    }

    @Test
    fun structural_equality_matches_a_plain_list() {
        val series = seriesOf(700)
        val reference = (0L until 700L).map { sample(it) }
        assertEquals(reference, series)
        assertEquals(series, reference)
        assertEquals(reference.hashCode(), series.hashCode())
    }

    @Test
    fun appending_never_exceeds_capacity_and_drops_the_oldest_reading_first() {
        val capacity = 600
        var series = seriesOf(capacity)
        val reference = ArrayList<BatterySample>()
        repeat(capacity) { reference.add(sample(it.toLong())) }
        repeat(50) { index ->
            val appended = sample(10_000L + index)
            series = series.appended(appended, capacity)
            reference.add(appended)
            while (reference.size > capacity) reference.removeAt(0)
            assertEquals(capacity, series.size)
            // Exactly one reading left; nothing else moved.
            assertEquals((1L + index), series.first().elapsedRealtimeMillis)
            assertEquals(10_000L + index, series.last().elapsedRealtimeMillis)
            assertEquals(reference, series)
        }
    }

    @Test
    fun a_published_series_is_never_modified_by_later_appends() {
        val published = seriesOf(300)
        val before = published.map { it.elapsedRealtimeMillis }
        var working = published
        repeat(100) { working = working.appended(sample(9_000L + it), Int.MAX_VALUE) }
        assertEquals(before, published.map { it.elapsedRealtimeMillis })
        assertEquals(300, published.size)
        assertNotSame(published, working)
    }

    @Test
    fun capacity_of_one_keeps_only_the_newest_reading() {
        var series = SampleSeries.EMPTY
        repeat(5) { series = series.appended(sample(it.toLong()), 1) }
        assertEquals(1, series.size)
        assertEquals(4L, series.single().elapsedRealtimeMillis)
    }

    @Test
    fun of_builds_the_same_series_as_repeated_appends() {
        val reference = seriesOf(1_234)
        val rows = (0L until 1_234L).map { sample(it) }
        val built = SampleSeries.of(rows)
        assertEquals(rows, built)
        assertEquals(reference.map { it.elapsedRealtimeMillis }, built.map { it.elapsedRealtimeMillis })
        assertSame(SampleSeries.EMPTY, SampleSeries.of(emptyList()))
    }

    @Test
    fun chunk_boundaries_are_exact_for_every_small_length() {
        for (length in 0..(SampleSeries.CHUNK * 2 + 2)) {
            val series = seriesOf(length)
            assertEquals(length, series.size)
            assertEquals((0L until length.toLong()).toList(), series.map { it.elapsedRealtimeMillis })
        }
    }

    /**
     * Randomized comparison against the plain `ArrayList` + `removeAt(0)` rule the
     * service used before, including a capacity that is not a chunk multiple.
     */
    @Test
    fun random_append_and_evict_matches_the_reference_buffer() {
        val random = Random(20_260_517)
        repeat(40) { round ->
            val capacity = random.nextInt(1, SampleSeries.CHUNK * 3)
            var series = SampleSeries.EMPTY
            val reference = ArrayList<BatterySample>()
            var next = 0L
            repeat(300) {
                val value = sample(next++)
                series = series.appended(value, capacity)
                reference.add(value)
                while (reference.size > capacity) reference.removeAt(0)
                assertEquals("round $round size", reference.size, series.size)
                assertEquals(
                    "round $round contents",
                    reference.map { row -> row.elapsedRealtimeMillis },
                    series.map { row -> row.elapsedRealtimeMillis },
                )
            }
        }
    }
}
