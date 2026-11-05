package io.github.anevilburrito.wpm.monitoring

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CapacityEstimate
import io.github.anevilburrito.wpm.model.CurrentCalibration
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class CsvExporterTest {

    private fun sample(
        elapsed: Long = 0,
        watts: Double? = -5.5,
        gapBefore: Boolean = false,
        reason: String? = null,
        rawCurrentMicroamps: Long? = null,
        rawAverageMicroamps: Long? = null,
        chargeCounterMicroampHours: Long? = null,
        derivedAmps: Double? = null,
        derivedWindowMillis: Long? = null,
        currentUnitAdjusted: Boolean = false,
        currentCalibrated: Boolean = false,
        currentPolarityAdjusted: Boolean = false,
        currentSource: CurrentSource = CurrentSource.NONE,
        cycleCount: Int? = null,
    ) = BatterySample(
        timestampMillis = 1_000_000L + elapsed * 1000,
        elapsedRealtimeMillis = elapsed,
        watts = watts,
        amps = watts?.div(4.0),
        volts = 4.0,
        percentage = 80,
        temperatureC = 29.1,
        condition = BatteryCondition.GOOD.name,
        plugged = false,
        flow = FlowState.DISCHARGING,
        currentMicroamps = -1_375_000,
        voltageMillivolts = 4000,
        rawCurrentMicroamps = rawCurrentMicroamps,
        rawAverageMicroamps = rawAverageMicroamps,
        chargeCounterMicroampHours = chargeCounterMicroampHours,
        derivedAmps = derivedAmps,
        derivedWindowMillis = derivedWindowMillis,
        currentUnitAdjusted = currentUnitAdjusted,
        currentCalibrated = currentCalibrated,
        currentPolarityAdjusted = currentPolarityAdjusted,
        currentSource = currentSource,
        cycleCount = cycleCount,
        gapBefore = gapBefore,
        reason = reason,
    )

    private fun summary(samples: Int = 1) = SessionSummary(
        id = 7,
        startedAtMillis = 1_000_000L,
        endedAtMillis = 1_060_000L,
        sampleCount = samples,
    )

    private fun export(
        samples: List<BatterySample>,
        truncated: Boolean = false,
        capacity: CapacityEstimate = CapacityEstimate(),
        designCapacityMilliampHours: Double? = null,
        calibration: CurrentCalibration = CurrentCalibration(),
        currentUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
        currentPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
        summaryMetric: CsvExporter.SummaryMetric = CsvExporter.SummaryMetric.INSTANT_CALIBRATED,
    ): List<String> {
        val output = ByteArrayOutputStream()
        CsvExporter.write(
            output = output,
            summary = summary(samples.size),
            samples = samples,
            historyTruncated = truncated,
            capacity = capacity,
            designCapacityMilliampHours = designCapacityMilliampHours,
            calibration = calibration,
            currentUnit = currentUnit,
            currentPolarity = currentPolarity,
            summaryMetric = summaryMetric,
        )
        return String(output.toByteArray(), StandardCharsets.UTF_8).split("\n")
    }

    @Test
    fun headerListsEveryColumn() {
        val lines = export(emptyList())
        val header = lines.first { it.startsWith("timestamp_iso") }
        assertEquals(CsvExporter.COLUMNS.joinToString(","), header)
        assertTrue(header.contains("current_microamps"))
        assertTrue(header.contains("gap_before"))
        assertTrue(header.contains("reason"))
        // Provenance sits directly after voltage_millivolts: raw properties, the
        // counter that produced any derived rate, and the calibration flags.
        assertTrue(
            header.contains(
                "voltage_millivolts,raw_current_microamps,raw_average_microamps," +
                    "charge_counter_microamp_hours,derived_amps,derived_window_millis," +
                    "current_unit_adjusted,current_calibrated,current_polarity_adjusted," +
                    "current_source,cycle_count,percent",
            ),
        )
        // The platform average is kept for exports only, next to the instant reading.
        assertTrue(header.contains("raw_average_microamps"))
        assertTrue(header.contains("cycle_count"))
        // The auxiliary counter-average metric is appended, so no existing index moves.
        assertTrue(
            header.endsWith("reason,average_current_A,estimated_average_power_W,average_window_ms"),
        )
    }

    @Test
    fun sampleRowCarriesSignedValuesTimestampsAndGapMarkers() {
        val row = export(
            listOf(
                sample(
                    gapBefore = true,
                    reason = SampleReasons.GAP,
                    rawCurrentMicroamps = -42,
                    rawAverageMicroamps = -1_375_000,
                    chargeCounterMicroampHours = 5_000_000,
                    derivedAmps = -0.042,
                    derivedWindowMillis = 600_000,
                    currentUnitAdjusted = true,
                    currentCalibrated = true,
                    currentPolarityAdjusted = true,
                    currentSource = CurrentSource.CHARGE_COUNTER,
                    cycleCount = 412,
                ),
            ),
        ).first { it.startsWith("1970-01-01T00:16:40Z") }
        val cells = row.split(",")
        assertEquals("1970-01-01T00:16:40Z", cells[0])
        assertEquals("1000000", cells[1])
        assertEquals("0", cells[2])
        assertEquals("-5.500000", cells[3])
        assertEquals("-1.375000", cells[4])
        assertEquals("4.0000", cells[5])
        assertEquals("-1375000", cells[6])
        assertEquals("4000", cells[7])
        assertEquals("-42", cells[8])
        assertEquals("-1375000", cells[9])
        assertEquals("5000000", cells[10])
        assertEquals("-0.042000", cells[11])
        assertEquals("600000", cells[12])
        assertEquals("1", cells[13])
        assertEquals("1", cells[14])
        assertEquals("1", cells[15])
        assertEquals("CHARGE_COUNTER", cells[16])
        assertEquals("412", cells[17])
        assertEquals("80", cells[18])
        assertEquals("29.10", cells[19])
        assertEquals("GOOD", cells[20])
        assertEquals("0", cells[21])
        assertEquals("DISCHARGING", cells[22])
        assertEquals("1", cells[23])
        assertEquals("sampling gap", cells[24])
        // Appended average-first columns: counter-derived current, estimated power
        // from it and the measured window.
        assertEquals("-0.042000", cells[25])
        assertEquals("-0.168000", cells[26])
        assertEquals("600000", cells[27])
        assertEquals(CsvExporter.COLUMNS.size, cells.size)
    }

    @Test
    fun missingPropertiesAreBlankAndFlagsAreExplicit() {
        val bare = BatterySample(timestampMillis = 0L, elapsedRealtimeMillis = 0L, flow = FlowState.UNKNOWN)
        val cells = CsvExporter.row(bare).split(",")
        assertEquals("1970-01-01T00:00:00Z", cells[0])
        assertEquals("", cells[3])
        assertEquals("", cells[4])
        assertEquals("", cells[5])
        assertEquals("", cells[6])
        assertEquals("", cells[7])
        // Provenance columns keep blanks for missing values ...
        assertEquals("", cells[8])
        assertEquals("", cells[9])
        assertEquals("", cells[10])
        assertEquals("", cells[11])
        assertEquals("", cells[12])
        // ... while the interpretation flags are a real false and the source is explicit.
        assertEquals("0", cells[13])
        assertEquals("0", cells[14])
        assertEquals("0", cells[15])
        assertEquals("NONE", cells[16])
        // An unreported cycle count is empty, never a made-up number.
        assertEquals("", cells[17])
        assertEquals("", cells[18])
        assertEquals("", cells[19])
        assertEquals("", cells[20])
        assertEquals("", cells[21])
        assertEquals("UNKNOWN", cells[22])
        assertEquals("0", cells[23])
        assertEquals("", cells[24])
        // No average and no window: blank, never a substituted instant value.
        assertEquals("", cells[25])
        assertEquals("", cells[26])
        assertEquals("", cells[27])
        assertEquals(CsvExporter.COLUMNS.size, cells.size)
    }

    @Test
    fun negativeAndPositiveWattsKeepTheirSign() {
        assertTrue(CsvExporter.row(sample(watts = -12.25)).contains(",-12.250000,"))
        assertTrue(CsvExporter.row(sample(watts = 12.25)).contains(",12.250000,"))
        assertTrue(CsvExporter.row(sample(watts = 0.0)).contains(",0.000000,"))
    }

    @Test
    fun derivedRateAndSourceSurviveAnExportRoundTrip() {
        val cells = CsvExporter.row(
            sample(
                derivedAmps = -3.15e-4,
                derivedWindowMillis = 120_000,
                currentSource = CurrentSource.CHARGE_COUNTER,
            ),
        ).split(",")
        assertEquals("-0.000315", cells[11])
        assertEquals("120000", cells[12])
        assertEquals("CHARGE_COUNTER", cells[16])
        // SI values stay SI: no unit scaling happens in the export.
        assertTrue(CsvExporter.row(sample(watts = -1.26e-3)).contains(",-0.001260,"))
    }

    @Test
    fun calibrationFlagsDistinguishAdjustedFromEstablished() {
        // A rescaled-but-uncalibrated reading: unit fixed, polarity not proven.
        val rescaled = CsvExporter.row(
            sample(currentUnitAdjusted = true, currentCalibrated = false, currentPolarityAdjusted = false),
        ).split(",")
        assertEquals("1", rescaled[13])
        assertEquals("0", rescaled[14])
        assertEquals("0", rescaled[15])

        // A fully calibrated reading with the sign flipped by the saved calibration.
        val calibrated = CsvExporter.row(
            sample(currentCalibrated = true, currentPolarityAdjusted = true),
        ).split(",")
        assertEquals("0", calibrated[13])
        assertEquals("1", calibrated[14])
        assertEquals("1", calibrated[15])
    }

    @Test
    fun escapingProtectsCommasQuotesAndNewlines() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvExporter.escape("line1\nline2"))
        assertEquals("\"line1\rline2\"", CsvExporter.escape("line1\rline2"))
        val row = CsvExporter.row(sample(reason = "a,b"))
        // New average columns follow the existing reason field; its quoting and
        // original column order must remain intact.
        assertTrue(row.contains(",\"a,b\","))
    }

    @Test
    fun exportIncludesSessionMetadataAndTruncationFlag() {
        val lines = export(listOf(sample()), truncated = true)
        assertTrue(lines[0].startsWith("# win_power_monitor"))
        assertTrue(lines[1].contains("session=7"))
        assertTrue(lines[1].contains("retained_rows=1"))
        assertTrue(lines[1].contains("history_truncated=true"))
        assertTrue(lines[1].contains("started_at=1970-01-01T00:16:40Z"))
        assertTrue(lines[1].contains("ended_at=1970-01-01T00:17:40Z"))
        assertFalse(export(listOf(sample()))[1].contains("history_truncated=true"))
    }

    @Test
    fun capacityMetadataLineStatesTheObservedValueBasisAndWindowProgress() {
        val lines = export(
            listOf(sample()),
            capacity = CapacityEstimate(
                estimatedMilliampHours = 4321.25,
                estimateCount = 3,
                observedPercent = 14,
                lastUpdatedMillis = 1_700_000_000_000L,
            ),
        )
        assertEquals(
            "# observed_full_capacity_mAh=4321.3 observations=3 window_percent=14" +
                " basis=observed_charge_counter_swing",
            lines[2],
        )
        // The single-point gauge helper is never exported as observed capacity.
        assertFalse(lines[2].contains("charge_counter_vs_percentage"))
    }

    @Test
    fun capacityMetadataLineSaysUnknownWhenNoWindowHasCompleted() {
        val lines = export(emptyList())
        assertEquals(
            "# observed_full_capacity_mAh=unknown observations=0 window_percent=0" +
                " basis=observed_charge_counter_swing",
            lines[2],
        )
        // A non-finite value is equally unmeasurable and must not print NaN.
        assertTrue(
            CsvExporter.capacityMetadataLine(CapacityEstimate(estimatedMilliampHours = Double.NaN))
                .contains("observed_full_capacity_mAh=unknown"),
        )
        // The metadata always precedes the column header.
        assertTrue(lines[2].startsWith("# observed_full_capacity_mAh="))
        assertTrue(lines[6].startsWith("# power_method="))
        assertEquals(CsvExporter.COLUMNS.joinToString(","), lines[7])
    }

    @Test
    fun designCapacityMetadataDistinguishesUserSuppliedFromUnknown() {
        assertEquals("# design_capacity_mAh=unknown source=none", export(emptyList())[3])
        // The user's value is exported as a stated fact, never as a measurement.
        assertEquals(
            "# design_capacity_mAh=4380.5 source=user",
            export(listOf(sample()), designCapacityMilliampHours = 4380.5)[3],
        )
        // Nonsense is refused rather than printed.
        assertEquals(
            "# design_capacity_mAh=unknown source=none",
            CsvExporter.designCapacityMetadataLine(Double.NaN),
        )
        assertEquals(
            "# design_capacity_mAh=unknown source=none",
            CsvExporter.designCapacityMetadataLine(null),
        )
    }

    @Test
    fun calibrationMetadataStatesEvidenceAndManualCorrections() {
        assertEquals(
            "# current_calibration unit=AUTO inverted=unknown evidence_windows=0" +
                " established=false manual_unit=AUTO manual_polarity=AUTO",
            export(emptyList())[4],
        )
        val established = export(
            listOf(sample()),
            calibration = CurrentCalibration(
                unit = CurrentUnitOverride.MILLIAMPS,
                inverted = true,
                evidenceWindows = 4,
            ),
            currentUnit = CurrentUnitOverride.AUTO,
            currentPolarity = CurrentPolarityOverride.AUTO,
        )[4]
        assertEquals(
            "# current_calibration unit=MILLIAMPS inverted=true evidence_windows=4" +
                " established=true manual_unit=AUTO manual_polarity=AUTO",
            established,
        )
        // Manual corrections are separate from (and take precedence over) evidence.
        val manual = export(
            listOf(sample()),
            currentUnit = CurrentUnitOverride.MICROAMPS,
            currentPolarity = CurrentPolarityOverride.INVERTED,
        )[4]
        assertTrue(manual.contains("manual_unit=MICROAMPS"))
        assertTrue(manual.contains("manual_polarity=INVERTED"))
        assertTrue(manual.contains("established=false"))
    }

    @Test
    fun rawPropertyNoteExplainsThatUnitsAreAsReported() {
        val note = export(emptyList())[5]
        assertTrue(note.startsWith("# raw_current_microamps,raw_average_microamps=as_reported_by_android_api"))
        assertTrue(note.contains("some OEMs report milliamps"))
    }

    @Test
    fun powerMethodMetadataNamesTheLivePrimaryAndAuxiliaryAverage() {
        val line = export(emptyList())[6]
        assertEquals(
            "# power_method=normalized_calibrated_current_now_x_voltage" +
                " summary_metric=instant_calibrated" +
                " auxiliary_power_method=average_counter_current_x_latest_voltage" +
                " auxiliary_estimate=true" +
                " limitation=single_point_calibrated_reading_not_window_integrated",
            line,
        )
        // The live method is primary; the counter average is labelled auxiliary.
        assertEquals(
            "normalized_calibrated_current_now_x_voltage",
            CsvExporter.POWER_METHOD,
        )
        assertEquals(
            "average_counter_current_x_latest_voltage",
            CsvExporter.AUXILIARY_POWER_METHOD,
        )
        // The single-point limitation is stated, not implied.
        assertTrue(line.contains("limitation=single_point_calibrated_reading_not_window_integrated"))
        assertFalse(line.contains("summary_note="))
    }

    @Test
    fun powerMethodNeverClaimsTheLiveMetricForAStoredSummary() {
        // A summary read back from storage carries no metric marker: sessions recorded
        // by an earlier build summed raw instantaneous watts or the counter average.
        // The export must say so rather than label history with the current metric.
        val line = export(
            emptyList(),
            summaryMetric = CsvExporter.SummaryMetric.STORED_UNKNOWN,
        )[6]
        assertEquals(
            "# power_method=normalized_interpreted_current_now_x_voltage" +
                " summary_metric=stored_unknown" +
                " auxiliary_power_method=average_counter_current_x_latest_voltage" +
                " auxiliary_estimate=true" +
                " limitation=single_point_reading_not_window_integrated" +
                " summary_note=${CsvExporter.STORED_SUMMARY_NOTE}",
            line,
        )
        assertFalse(line.contains("summary_metric=instant_calibrated"))
        // The default is the unclaimed case, so a caller that forgets cannot overstate.
        assertEquals(
            "# power_method=normalized_interpreted_current_now_x_voltage" +
                " summary_metric=stored_unknown" +
                " auxiliary_power_method=average_counter_current_x_latest_voltage" +
                " auxiliary_estimate=true" +
                " limitation=single_point_reading_not_window_integrated" +
                " summary_note=${CsvExporter.STORED_SUMMARY_NOTE}",
            CsvExporter.powerMethodMetadataLine(),
        )
    }

    @Test
    fun aLegacyAverageSummaryIsNamedButNotPresentedAsLive() {
        val line = export(
            emptyList(),
            summaryMetric = CsvExporter.SummaryMetric.AVERAGE,
        )[6]
        assertTrue(line.contains("summary_metric=average"))
        assertTrue(line.contains("summary_note=summary_integrated_legacy_counter_average_power"))
        assertFalse(line.contains("summary_metric=instant_calibrated"))
    }

    @Test
    fun averageColumnsFollowTheCounterRateNotTheInstantReading() {
        // Instant reading exists but no average yet: the average columns stay blank
        // while the instant columns keep their values.
        val noAverage = CsvExporter.row(sample(watts = -2.2, derivedAmps = null)).split(",")
        assertEquals("-2.200000", noAverage[3])
        assertEquals("", noAverage[25])
        assertEquals("", noAverage[26])
        assertEquals("", noAverage[27])

        // A rate without a measured window is not an average either.
        val windowless = CsvExporter.row(
            sample(derivedAmps = -0.2, derivedWindowMillis = null),
        ).split(",")
        assertEquals("", windowless[25])
        assertEquals("", windowless[26])
        assertEquals("", windowless[27])

        // A valid average is signed, and the estimated power uses this voltage.
        val averaged = CsvExporter.row(
            sample(derivedAmps = 0.5, derivedWindowMillis = 45_000L, watts = 99.0),
        ).split(",")
        assertEquals("0.500000", averaged[25])
        assertEquals("2.000000", averaged[26])
        assertEquals("45000", averaged[27])
    }

    @Test
    fun missingVoltageBlankOnlyTheEstimatedPower() {
        val noVolts = BatterySample(
            timestampMillis = 0L,
            elapsedRealtimeMillis = 0L,
            volts = null,
            derivedAmps = -0.315,
            derivedWindowMillis = 60_000L,
        )
        val cells = CsvExporter.row(noVolts).split(",")
        // The counter rate is still a measurement of current ...
        assertEquals("-0.315000", cells[25])
        // ... but power cannot be estimated without a voltage.
        assertEquals("", cells[26])
        assertEquals("60000", cells[27])
    }

    @Test
    fun decimalsRespectLocaleIndependenceAndNonFiniteValues() {
        assertEquals("-1.500000", CsvExporter.decimal(-1.5, 6))
        assertEquals("", CsvExporter.decimal(null, 3))
        assertEquals("", CsvExporter.decimal(Double.NaN, 3))
        assertEquals("", CsvExporter.decimal(Double.POSITIVE_INFINITY, 3))
    }

    @Test
    fun unknownReasonTokensFallBackToTheirRawValue() {
        assertEquals("custom_reason", SampleReasons.label("custom_reason"))
        assertEquals("monitoring mode changed", SampleReasons.label(SampleReasons.MODE_CHANGED))
        assertEquals("sampling interval changed", SampleReasons.label(SampleReasons.INTERVAL_CHANGED))
        assertEquals(
            "current scale or polarity interpretation changed",
            SampleReasons.label(SampleReasons.CURRENT_CALIBRATION_CHANGED),
        )
        assertEquals(null, SampleReasons.label(null))
        assertEquals(null, SampleReasons.label(""))
    }

    @Test
    fun rowsAreNewlineTerminatedAndUtf8Encoded() {
        val output = ByteArrayOutputStream()
        CsvExporter.write(output, summary(1), listOf(sample()), false)
        val text = String(output.toByteArray(), StandardCharsets.UTF_8)
        assertTrue(text.endsWith("\n"))
        // Title, session, capacity, design capacity, calibration, raw note, power
        // method, header and one row.
        assertEquals(9, text.split("\n").dropLastWhile { it.isEmpty() }.size)
    }
}
