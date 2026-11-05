package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CapacityEstimate
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.SessionSummary
import io.github.anevilburrito.wpm.model.averageAmps
import io.github.anevilburrito.wpm.model.averageWatts
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

/**
 * UTF-8 CSV of the *retained* sample history for one session.
 *
 * The export is the one place where provenance belongs: raw platform properties,
 * how the instant reading was interpreted (unit rescaled, calibration established,
 * polarity inverted), how the observed capacity was obtained, and which manual
 * corrections or user-supplied design capacity were in force. The UI deliberately
 * shows none of it.
 *
 * The primary, user-facing metric is the normalized/calibrated instantaneous
 * `CURRENT_NOW` reading. The existing instant columns (`watts`, `amps`,
 * `current_calibrated`, `current_source`) already carry it, so they keep their
 * positions and meaning. The counter average is retained as an explicitly
 * **auxiliary** metric in the appended columns (`average_current_A`,
 * `estimated_average_power_W`, `average_window_ms`).
 * `estimated_average_power_W` is `average_current_A * volts`: an estimate from
 * counter-derived average current times the latest voltage, *not* an exact
 * window-integrated V·I (the [powerMethodMetadataLine] states this).
 *
 * Android-free so the format (signs, blanks for missing properties, gap markers,
 * escaping) is unit tested on the JVM.
 */
object CsvExporter {
    val COLUMNS = listOf(
        "timestamp_iso",
        "timestamp_millis",
        "elapsed_realtime_millis",
        "watts",
        "amps",
        "volts",
        "current_microamps",
        "voltage_millivolts",
        "raw_current_microamps",
        "raw_average_microamps",
        "charge_counter_microamp_hours",
        "derived_amps",
        "derived_window_millis",
        "current_unit_adjusted",
        "current_calibrated",
        "current_polarity_adjusted",
        "current_source",
        "cycle_count",
        "percent",
        "temperature_c",
        "condition",
        "plugged",
        "flow",
        "gap_before",
        "reason",
        // Appended auxiliary metric: the counter-derived average kept for export
        // comparison. Kept at the end so every pre-existing column keeps its index
        // and meaning; the primary calibrated instant values are the columns above.
        "average_current_A",
        "estimated_average_power_W",
        "average_window_ms",
    )

    const val NEWLINE = "\n"

    /**
     * Header line describing the accumulated *observed* capacity.
     *
     * Says `unknown` when no charging window has completed yet, because there is no
     * design-capacity property to fall back on and a zero would be a fabrication.
     * [BASIS] states how the number was obtained; `window_percent` is the progress
     * of the window still open at export time, never a capacity.
     */
    fun capacityMetadataLine(capacity: CapacityEstimate): String {
        val milliampHours = decimal(capacity.estimatedMilliampHours, 1).ifEmpty { "unknown" }
        return "# observed_full_capacity_mAh=$milliampHours" +
            " observations=${capacity.estimateCount}" +
            " window_percent=${capacity.observedPercent}" +
            " basis=$BASIS"
    }

    /**
     * How observed capacity is obtained: the charge-counter change across a monotonic
     * charging window that spanned at least six tenths of the reported gauge range,
     * accumulated as a swing-weighted average. A gauge observation, not a laboratory
     * measurement, and not the single-point gauge helper.
     */
    const val BASIS = "observed_charge_counter_swing"

    /**
     * States the user-supplied design capacity exactly as stored. `source=none` when
     * the user never supplied one: the platform does not report design capacity, so
     * nothing is guessed and no model lookup table is used.
     */
    fun designCapacityMetadataLine(designCapacityMilliampHours: Double?): String {
        val value = decimal(designCapacityMilliampHours, 1)
        return if (value.isEmpty()) {
            "# design_capacity_mAh=unknown source=none"
        } else {
            "# design_capacity_mAh=$value source=user"
        }
    }

    /**
     * States the calibration used to interpret instantaneous readings, plus the
     * manual corrections. Written as explicit key=value pairs so "not established"
     * and "manually forced" cannot be confused.
     */
    fun calibrationMetadataLine(
        calibration: CurrentCalibration,
        manualUnit: CurrentUnitOverride,
        manualPolarity: CurrentPolarityOverride,
    ): String {
        val inverted = when (calibration.inverted) {
            true -> "true"
            false -> "false"
            null -> "unknown"
        }
        return "# current_calibration" +
            " unit=${calibration.unit.name}" +
            " inverted=$inverted" +
            " evidence_windows=${calibration.evidenceWindows}" +
            " established=${calibration.calibrated}" +
            " manual_unit=${manualUnit.name}" +
            " manual_polarity=${manualPolarity.name}"
    }

    /**
     * The raw columns are the property values exactly as the Android API returned
     * them. The API documents microamps, but some OEM implementations return
     * milliamps, so a raw value must be read as "as reported", not as a calibrated
     * SI quantity; [COLUMNS] names keep the documented unit only as a hint.
     */
    fun rawPropertyNoteLine(): String =
        "# raw_current_microamps,raw_average_microamps=as_reported_by_android_api" +
            " (documented as microamps; some OEMs report milliamps)"

    /**
     * Names the primary power method (normalized/calibrated `CURRENT_NOW` × voltage),
     * the auxiliary counter-average method, the metric of the exported session
     * summary, and the primary method's limitation.
     *
     * The primary method is a **single-point** calibrated reading: it follows load
     * changes immediately but says nothing about time-integrated energy by itself.
     * The auxiliary average is an estimate too (counter-derived average current times
     * the latest voltage), so it is named separately rather than presented as the
     * same quantity.
     *
     * [summaryMetric] states what the session *summary* in this file integrated, and
     * is not assumed: a summary stored by an earlier build summed raw instantaneous
     * `watts` or the former counter average, and those stored numbers are deliberately
     * never rewritten, so claiming the current live metric for them would misdescribe
     * history. New live summaries use [SummaryMetric.INSTANT_INTERPRETED], which
     * includes clearly marked startup-estimated segments. INSTANT_CALIBRATED remains
     * available only for explicitly known legacy confirmed-only summaries.
     */
    fun powerMethodMetadataLine(
        summaryMetric: SummaryMetric = SummaryMetric.STORED_UNKNOWN,
    ): String {
        // Only an explicitly known legacy confirmed-only summary may claim that
        // stricter method. Restored/unknown history can contain startup estimates.
        val interpreted = summaryMetric != SummaryMetric.INSTANT_CALIBRATED
        val method = if (interpreted) "normalized_interpreted_current_now_x_voltage" else POWER_METHOD
        val limitation = if (interpreted) "single_point_reading_not_window_integrated" else
            "single_point_calibrated_reading_not_window_integrated"
        val line = "# power_method=$method summary_metric=${summaryMetric.token}" +
            " auxiliary_power_method=$AUXILIARY_POWER_METHOD" +
            " auxiliary_estimate=true" +
            " limitation=$limitation"
        return when (summaryMetric) {
            SummaryMetric.INSTANT_CALIBRATED -> line
            SummaryMetric.INSTANT_INTERPRETED -> "$line startup_estimates_possible=true" +
                " estimated_sample_source=INSTANT_ESTIMATED"
            SummaryMetric.AVERAGE -> "$line summary_note=$AVERAGE_SUMMARY_NOTE"
            SummaryMetric.STORED_UNKNOWN -> "$line summary_note=$STORED_SUMMARY_NOTE"
        }
    }

    /**
     * What the exported [SessionSummary] integrated.
     *
     * The sample rows always carry both metrics side by side (`watts` is the
     * calibrated instant reading, `estimated_average_power_W` the auxiliary average
     * estimate), so this only describes the *summary* block — the one number that
     * cannot be recomputed from the file when history was pruned.
     */
    enum class SummaryMetric(val token: String) {
        /** New live sessions: calibrated instantaneous power
         * ([io.github.anevilburrito.wpm.model.BatterySample.liveWatts]). */
        INSTANT_CALIBRATED("instant_calibrated"),

        /** Live CURRENT_NOW including explicitly marked, unconfirmed startup estimates. */
        INSTANT_INTERPRETED("instant_interpreted"),

        /** Legacy sessions this build accumulated from the auxiliary counter average. */
        AVERAGE("average"),

        /**
         * Read back from storage, which records no metric marker. It may have been
         * accumulated from raw instantaneous watts, the counter average, or the live
         * metric, so the file states the uncertainty instead of guessing.
         */
        STORED_UNKNOWN("stored_unknown"),
    }

    /**
     * Explains [SummaryMetric.AVERAGE]: the stored/legacy summary integrated the
     * auxiliary counter-average power, not the live calibrated reading.
     */
    private const val AVERAGE_SUMMARY_NOTE = "summary_integrated_legacy_counter_average_power"

    /**
     * Explains [SummaryMetric.STORED_UNKNOWN]: the stored summary predates the live
     * metric marker (or was written by a build that did not record one), so its
     * power/energy fields may describe raw instantaneous watts, the counter average,
     * or the calibrated live metric.
     */
    const val STORED_SUMMARY_NOTE =
        "stored_summary_metric_unknown_may_sum_instant_average_or_live_watts"

    /**
     * `normalized_calibrated_current_now_x_voltage`: power from the calibrated
     * instantaneous current and the latest voltage for the same poll.
     *
     * A single-point reading, not a time-integrated average; the counter-average
     * method below remains available as an auxiliary comparison only.
     */
    const val POWER_METHOD = "normalized_calibrated_current_now_x_voltage"

    /**
     * `average_counter_current_x_latest_voltage`: the auxiliary average columns are
     * the counter-derived trailing average current and that current times the latest
     * reported voltage. Not a window-integrated V·I and not the primary metric.
     */
    const val AUXILIARY_POWER_METHOD = "average_counter_current_x_latest_voltage"

    fun write(
        output: OutputStream,
        summary: SessionSummary,
        samples: List<BatterySample>,
        historyTruncated: Boolean,
        capacity: CapacityEstimate = CapacityEstimate(),
        designCapacityMilliampHours: Double? = null,
        calibration: CurrentCalibration = CurrentCalibration(),
        currentUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        currentPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
        summaryMetric: SummaryMetric = SummaryMetric.STORED_UNKNOWN,
    ) {
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.write("# win_power_monitor android session export")
        writer.write(NEWLINE)
        writer.write(
            "# session=${summary.id} started_at=${isoTimestamp(summary.startedAtMillis)}" +
                " ended_at=${isoTimestampOrEmpty(summary.endedAtMillis)}" +
                " retained_rows=${samples.size} recorded_samples=${summary.sampleCount}" +
                " history_truncated=$historyTruncated",
        )
        writer.write(NEWLINE)
        writer.write(capacityMetadataLine(capacity))
        writer.write(NEWLINE)
        writer.write(designCapacityMetadataLine(designCapacityMilliampHours))
        writer.write(NEWLINE)
        writer.write(calibrationMetadataLine(calibration, currentUnit, currentPolarity))
        writer.write(NEWLINE)
        writer.write(rawPropertyNoteLine())
        writer.write(NEWLINE)
        writer.write(powerMethodMetadataLine(summaryMetric))
        writer.write(NEWLINE)
        writer.write(COLUMNS.joinToString(","))
        writer.write(NEWLINE)
        for (sample in samples) {
            writer.write(row(sample))
            writer.write(NEWLINE)
        }
        writer.flush()
    }

    fun row(sample: BatterySample): String {
        val averageAmps = sample.averageAmps
        val averageWatts = sample.averageWatts
        val averageWindowMillis = if (averageAmps == null) null else sample.derivedWindowMillis
        val values = listOf(
            isoTimestamp(sample.timestampMillis),
            sample.timestampMillis.toString(),
            sample.elapsedRealtimeMillis.toString(),
            decimal(sample.watts, 6),
            decimal(sample.amps, 6),
            decimal(sample.volts, 4),
            sample.currentMicroamps?.toString() ?: "",
            sample.voltageMillivolts?.toString() ?: "",
            // Raw property values before unit normalisation, then the counter that
            // produced any derived rate and which source fed amps/watts.
            sample.rawCurrentMicroamps?.toString() ?: "",
            sample.rawAverageMicroamps?.toString() ?: "",
            sample.chargeCounterMicroampHours?.toString() ?: "",
            decimal(sample.derivedAmps, 6),
            sample.derivedWindowMillis?.toString() ?: "",
            if (sample.currentUnitAdjusted) "1" else "0",
            // Whether the instant reading used established/manual calibration, and
            // whether the saved calibration flipped its sign. Both are provenance,
            // never a claim that the hardware was correct.
            if (sample.currentCalibrated) "1" else "0",
            if (sample.currentPolarityAdjusted) "1" else "0",
            sample.currentSource.name,
            // Platform cycle count (API 34+); blank when the device keeps none.
            sample.cycleCount?.toString() ?: "",
            sample.percentage?.toString() ?: "",
            decimal(sample.temperatureC, 2),
            sample.condition ?: "",
            sample.plugged?.let { if (it) "1" else "0" } ?: "",
            sample.flow.name,
            if (sample.gapBefore) "1" else "0",
            SampleReasons.label(sample.reason) ?: "",
            // The auxiliary metric, appended: counter-derived average current and the
            // estimated average power it implies. Blank when no usable average exists
            // (never 0, and never the instant reading substituted in).
            decimal(averageAmps, 6),
            decimal(averageWatts, 6),
            averageWindowMillis?.toString() ?: "",
        )
        return values.joinToString(",") { escape(it) }
    }

    /** RFC 4180 style quoting so commas, quotes and newlines survive round-trip. */
    fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }

    fun decimal(value: Double?, decimals: Int): String {
        if (value == null || !value.isFinite()) return ""
        return String.format(Locale.ROOT, "%.${decimals}f", value)
    }

    fun isoTimestamp(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun isoTimestampOrEmpty(millis: Long?): String = millis?.let { isoTimestamp(it) } ?: ""
}
