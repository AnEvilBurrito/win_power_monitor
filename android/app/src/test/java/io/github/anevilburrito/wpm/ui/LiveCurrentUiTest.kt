package io.github.anevilburrito.wpm.ui

import io.github.anevilburrito.wpm.model.BatterySample
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentSource
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.FlowState
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.forLiveDisplay
import io.github.anevilburrito.wpm.model.summarizeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The calibrated-live UI contract, tested where it can be tested purely: which
 * number the chart/window statistics use, how much room the numeric slots reserve,
 * and what the developer-mode toggle is allowed to change.
 *
 * Device-only gaps are reported in the agent hand-off, not simulated here.
 */
class LiveCurrentUiTest {

    /**
     * Raw sample with calibrated `CURRENT_NOW` provenance. The auxiliary counter
     * average is deliberately far away, so any test that passes proves which metric
     * the projection and the readout used.
     */
    private fun raw(
        seconds: Long,
        instantWatts: Double?,
        derivedAmps: Double?,
        volts: Double? = 4.0,
        chargeCounter: Long? = 3_000_000L,
        calibrated: Boolean = true,
        percentage: Int? = 68,
    ) = BatterySample(
        timestampMillis = 1_700_000_000_000L + seconds * 1000L,
        elapsedRealtimeMillis = seconds * 1000L,
        watts = instantWatts,
        amps = if (instantWatts == null || volts == null) null else instantWatts / volts,
        volts = volts,
        percentage = percentage,
        chargeCounterMicroampHours = chargeCounter,
        currentCalibrated = calibrated && instantWatts != null,
        currentSource = if (instantWatts == null) CurrentSource.NONE else CurrentSource.INSTANT,
        derivedAmps = derivedAmps,
        derivedWindowMillis = if (derivedAmps == null) null else 60_000L,
    )

    @Test fun windowStatisticsAreComputedOverLiveSamplesNotCounterAverages() {
        // Live ~1.26 mW; counter average 4 W. The window summary must describe the
        // live readings, because that is what the chart and readout show.
        val samples = listOf(
            raw(0, instantWatts = -0.00126, derivedAmps = -1.0),
            raw(2, instantWatts = -0.00126, derivedAmps = -1.0),
            raw(4, instantWatts = -0.00126, derivedAmps = -1.0),
        )
        val liveSamples = samples.map { it.forLiveDisplay() }
        val summary = summarizeWindow(liveSamples, windowSeconds = 300, intervalSeconds = 2)
        assertEquals(0.00126, summary.magnitudeAverageWatts!!, 1e-9)
        assertEquals(0.00126, summary.maximumMagnitudeWatts!!, 1e-9)
        // The raw provenance is untouched on the raw samples.
        assertEquals(-0.00126, samples.last().watts!!, 1e-9)
        assertEquals(-1.0, samples.last().derivedAmps!!, 0.0)
    }

    @Test fun theChartPlotsTheLiveReadingAndKeepsTheCounterAverageOutOfTheTrace() {
        val samples = listOf(
            raw(0, instantWatts = -0.00126, derivedAmps = -1.0),
            raw(2, instantWatts = -0.00126, derivedAmps = -1.0),
        ).map { it.forLiveDisplay() }
        val series = buildChartSeries(samples, 300, 2, absolute = true, maxPoints = 200)
        assertEquals(1, series.segments.size)
        assertEquals(2, series.segments[0].points.size)
        series.segments[0].points.forEach { point ->
            assertEquals(0.00126, point.value, 1e-9)
        }
        // Counter average (4 W) would need a 5 W axis; the live window is mW.
        assertEquals(0.005, series.upper, 1e-12)
    }

    @Test fun aCalibratedReadingIsPlottedWithoutAnyCounterAverage() {
        // The old average-first projection left a warm-up window empty. A saved
        // calibration is enough: the live trace exists on the first poll.
        val samples = listOf(
            raw(0, instantWatts = -0.00126, derivedAmps = null),
            raw(2, instantWatts = -0.0013, derivedAmps = null),
        ).map { it.forLiveDisplay() }
        val series = buildChartSeries(samples, 300, 2, absolute = true, maxPoints = 200)
        assertEquals(1, series.segments.size)
        assertEquals(2, series.plottedPointCount)
        assertEquals(0, series.missingValueCount)
    }

    @Test fun anUncalibratedInstantReadingHasNoLiveTrace() {
        // Unconfirmed now: nothing is invented, and the missing values split the trace.
        val samples = listOf(
            raw(0, instantWatts = -0.00126, derivedAmps = -1.0, calibrated = false),
            raw(2, instantWatts = -0.00126, derivedAmps = -1.0, calibrated = false),
        ).map { it.forLiveDisplay() }
        val series = buildChartSeries(samples, 300, 2, absolute = true, maxPoints = 200)
        assertTrue(series.isEmpty)
        assertEquals(2, series.missingValueCount)
        assertEquals(0, series.plottedPointCount)
    }

    @Test fun numericSlotsAreBoundedAndNeverOverlap() {
        // The outer power group and battery stay fixed. Inside the power group,
        // the suffix follows the digits instead of a mostly-empty numeric column.
        val portrait = ReadoutSlots.narrowestPortraitPlan()
        assertFalse(portrait.percentBelow)
        assertEquals(
            portrait.availableWidthDp,
            portrait.numberFieldDp + portrait.unitSlotDp + portrait.percentSlotDp + portrait.spareWidthDp,
        )
        assertTrue(portrait.numberFieldDp <= portrait.numberSlotDp)
        val landscape = ReadoutSlots.narrowestLandscapePlan()
        assertFalse(landscape.percentBelow)
        assertEquals(
            landscape.availableWidthDp,
            landscape.numberFieldDp + landscape.unitSlotDp + landscape.percentSlotDp + landscape.spareWidthDp,
        )
        // The widest ordinary reading (sign, three digits, separator, decimal: seven
        // characters) fits the field it is drawn in instead of overflowing it.
        listOf(portrait, landscape).forEach { plan ->
            assertTrue(
                "widest reading must fit the ${plan.numberFieldDp}dp field",
                ReadoutSlots.textWidthDp(
                    ReadoutSlots.WIDEST_NUMBER_CHARS,
                    plan.numberSizeSp,
                    plan.fontScale,
                ) <= plan.numberFieldDp + 1f,
            )
        }
        // The numeric budget covers the widest reading. It is not a spacer drawn
        // between the actual number and its suffix.
        assertEquals(
            kotlin.math.ceil(
                ReadoutSlots.textWidthDp(
                    ReadoutSlots.WIDEST_NUMBER_CHARS,
                    portrait.numberSizeSp,
                    portrait.fontScale,
                ),
            ).toInt(),
            portrait.numberFieldDp,
        )
        // The battery slot is budgeted for `100 %` and for its whole caption, so the
        // low-battery caption can never widen the column.
        assertTrue(portrait.percentSlotDp >= ReadoutSlots.percentCaptionWidthDp(1f))
        assertTrue(portrait.percentSlotDp > landscape.percentSlotDp)
    }

    @Test fun theNarrowestPortraitKeepsALegibleNumberAndAFullyReadableWord() {
        // 320dp-wide phone minus the 18dp side padding the portrait column uses.
        assertEquals(284, ReadoutSlots.portraitNumberRowWidthDp(320))
        val plan = ReadoutSlots.narrowestPortraitPlan()
        assertEquals(284, plan.availableWidthDp)
        // Budget for the full signed range, not just an unsigned 2.21 W value.
        assertTrue(plan.numberSizeSp >= ReadoutSlots.PORTRAIT_SINGLE_LINE_MIN_NUMBER_SP)
        assertTrue(plan.numberSizeSp <= ReadoutSlots.PORTRAIT_MAX_NUMBER_SP)
        assertFalse(plan.percentBelow)
        // `Calibrating` (eleven characters) is drawn whole, not clipped: this is the
        // state the earlier slot arithmetic got wrong.
        assertTrue(plan.wordFits)
        assertTrue(
            ReadoutSlots.textWidthDp(
                ReadoutSlots.WORD_HEADLINE_CHARS,
                plan.wordSizeSp,
                plan.fontScale,
            ) <= plan.powerGroupWidthDp + 1f,
        )
        assertTrue(plan.wordSizeSp >= ReadoutSlots.MIN_WORD_SP)
    }

    @Test fun theNarrowestLandscapeKeepsItsWordHeadlineToo() {
        // 456dp landscape window: 24dp strip padding, 12dp gutter, 1.2/2.2 split.
        assertEquals(229, ReadoutSlots.landscapeNumberRowWidthDp(456))
        val plan = ReadoutSlots.narrowestLandscapePlan()
        assertFalse(plan.percentBelow)
        assertEquals(ReadoutSlots.LANDSCAPE_MAX_NUMBER_SP, plan.numberSizeSp)
        assertTrue(plan.wordFits)
        // The three-column strip just above the compact threshold is the tightest
        // landscape case; the word still fits there at the default scale.
        val threeColumn = ReadoutSlots.splitCellWidthDp(620 - 24, 1.2, 3.6, spacingDp = 28)
        val narrow = ReadoutSlots.numberRowPlan(threeColumn, 1f, landscape = true)
        assertTrue(narrow.wordFits)
        assertTrue(
            ReadoutSlots.textWidthDp(
                ReadoutSlots.WORD_HEADLINE_CHARS,
                narrow.wordSizeSp,
                narrow.fontScale,
            ) <= narrow.powerGroupWidthDp + 1f,
        )
    }

    @Test fun everyWidthAndFontScaleKeepsTextInsideItsBoundedSlot() {
        // The sweep is the point: the row must not depend on a lucky width. Text is
        // measured with the same rule the composable uses ([ReadoutSlots.textWidthDp])
        // and must fit the slot the plan hands it.
        val scales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f)
        for (width in 240..900 step 7) {
            for (scale in scales) {
                for (landscape in listOf(false, true)) {
                    val plan = ReadoutSlots.numberRowPlan(width, scale, landscape)
                    val fixed = plan.unitSlotDp + if (plan.percentBelow) 0 else plan.percentSlotDp
                    assertEquals(
                        "slots and slack must add up to $width dp at $scale x",
                        width,
                        plan.numberFieldDp + fixed + plan.spareWidthDp,
                    )
                    assertTrue(
                        "${ReadoutSlots.WIDEST_NUMBER_CHARS} characters must fit ${plan.numberFieldDp}dp at ${plan.numberSizeSp}sp x $scale",
                        ReadoutSlots.textWidthDp(
                            ReadoutSlots.WIDEST_NUMBER_CHARS,
                            plan.numberSizeSp,
                            scale,
                        ) <= plan.numberFieldDp + 1f,
                    )
                    if (plan.wordFits) {
                        assertTrue(
                            "11 characters must fit ${plan.numberFieldDp}dp at ${plan.wordSizeSp}sp x $scale",
                            ReadoutSlots.textWidthDp(
                                ReadoutSlots.WORD_HEADLINE_CHARS,
                                plan.wordSizeSp,
                                scale,
                            ) <= plan.powerGroupWidthDp + 1f,
                        )
                    }
                }
            }
        }
    }

    @Test fun aLargerFontScaleChangesThePlanNotTheRowHeightRule() {
        val base = ReadoutSlots.narrowestPortraitPlan(1f)
        val large = ReadoutSlots.narrowestPortraitPlan(1.5f)
        // The rendered width budget is respected at both scales, because the size is
        // derived from the space and the font scale instead of assuming sp == dp.
        assertTrue(
            ReadoutSlots.textWidthDp(ReadoutSlots.WIDEST_NUMBER_CHARS, large.numberSizeSp, 1.5f) <=
                large.numberFieldDp + 1f,
        )
        // A single line would make the number unreadably small, so the battery slot
        // moves below it; that gives the number its width back instead of shrinking it.
        assertTrue(large.percentBelow)
        assertTrue(large.percentSlotDp >= base.percentSlotDp)
        assertTrue(large.numberSizeSp >= ReadoutSlots.PORTRAIT_MIN_NUMBER_SP)
        // The line box is a function of the size and the real scale, not of `dp`.
        assertEquals(
            kotlin.math.ceil(large.numberSizeSp * TELEMETRY_LINE_HEIGHT_RATIO * 1.5f).toInt(),
            large.headlineHeightDp,
        )
        // Word states now reuse the otherwise-empty suffix space and fit even at
        // 2x font scale. Extreme scales still have the separate fallback line.
        assertTrue(ReadoutSlots.narrowestPortraitPlan(2f).wordFits)
        assertFalse(ReadoutSlots.narrowestPortraitPlan(3f).wordFits)
    }

    @Test fun theHeadlineLineBoxIsFixedForBothNumberAndWordStates() {
        val plan = ReadoutSlots.narrowestPortraitPlan()
        assertEquals(
            kotlin.math.ceil(plan.numberSizeSp * TELEMETRY_LINE_HEIGHT_RATIO).toInt(),
            plan.headlineHeightDp,
        )
        // A word is drawn smaller than the number but in the number's line box, so a
        // state change cannot resize the row.
        assertTrue(plan.wordSizeSp < plan.numberSizeSp)
        assertTrue(plan.headlineHeightDp >=
            ReadoutSlots.headlineHeightDp(ReadoutSlots.PORTRAIT_SINGLE_LINE_MIN_NUMBER_SP, 1f))
        // Power/unit use native Text baselines; the percent mark retains its
        // separately sized baseline compensation.
        assertTrue(plan.percentMarkBaselineOffsetDp > 0)
        assertTrue(ReadoutSlots.readinessLineHeightDp(1f) >= 15)
        // The age text owns half the narrowest portrait row.
        assertTrue(
            ReadoutSlots.statusTextWidthDp(1f) <= ReadoutSlots.statusHalfWidthDp(),
        )
        assertEquals(136, ReadoutSlots.statusHalfWidthDp())
        assertTrue(ReadoutSlots.statUnitSlotWidthDp(1f) >= 15)
        assertTrue(ReadoutSlots.statLineHeightDp(1f) >= 16)
    }

    @Test fun developerModeIsOffByDefaultAndSurvivesNormalization() {
        assertFalse(MonitorSettings().developerMode)
        val enabled = MonitorSettings(developerMode = true).normalized()
        assertTrue(enabled.developerMode)

        // Turning it off again must not clear the advanced values stored with it.
        val configured = MonitorSettings(
            developerMode = true,
            currentUnit = CurrentUnitOverride.MILLIAMPS,
            currentPolarity = CurrentPolarityOverride.INVERTED,
            designCapacityMilliampHours = 4_500.0,
        )
        val hidden = configured.copy(developerMode = false).normalized()
        assertFalse(hidden.developerMode)
        assertEquals(CurrentUnitOverride.MILLIAMPS, hidden.currentUnit)
        assertEquals(CurrentPolarityOverride.INVERTED, hidden.currentPolarity)
        assertEquals(4_500.0, hidden.designCapacityMilliampHours!!, 1e-9)
    }

    @Test fun theBoundedFieldIncludesTheFullSignedLiveRange() {
        listOf(-200.0, -100.0, -99.99, -10.0, -0.9999, 0.0, 9.99, 100.0, 200.0).forEach { watts ->
            val text = WpmFormat.power(watts, absolute = false).text
            assertTrue("$text must fit the reserved field", text.length <= ReadoutSlots.WIDEST_NUMBER_CHARS)
        }
    }

    @Test fun aLargeLiveReadingIsFormattedWithoutInventingPrecision() {
        // The readout keeps magnitude-aware units: a 10 W live reading keeps two
        // decimals and the same unit tier as a 2 W reading, so the unit does not
        // flicker at a small threshold crossing.
        val small = liveReadingView(
            raw(0, instantWatts = -2.0, derivedAmps = -0.000315),
            running = true,
            stale = false,
            absolute = true,
        )
        val large = liveReadingView(
            raw(0, instantWatts = -10.0, derivedAmps = -0.000315),
            running = true,
            stale = false,
            absolute = true,
        )
        assertEquals("W", small.power.unit)
        assertEquals("W", large.power.unit)
        assertEquals("2.00", small.headline)
        assertEquals("10.00", large.headline)
        // The one inserted digit is absorbed by the bounded seven-character field, so
        // the unit suffix and the percentage column do not move when the reading
        // crosses 10 W.
        assertTrue(large.headline.length <= ReadoutSlots.WIDEST_NUMBER_CHARS)
        assertTrue(abs(small.power.text.length - large.power.text.length) <= 1)
        // Both describe the live reading, not the 60 s counter average.
        assertEquals(FlowState.DISCHARGING, large.flow)
    }
}
