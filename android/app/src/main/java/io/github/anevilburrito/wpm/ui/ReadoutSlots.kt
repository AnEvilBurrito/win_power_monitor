package io.github.anevilburrito.wpm.ui

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Layout arithmetic for the numeric readouts.
 *
 * The readout jitter a reviewer reported is **not** proven to have one cause; the
 * *candidate* explanation this file addresses is structural. The readout row used
 * to measure its number, its unit suffix and its battery percentage as three
 * intrinsic children, so `2.21` becoming `10.00`, `mW` becoming `W`, or the unit
 * vanishing during a warm-up could re-measure the row and move everything after
 * the number. A `widthIn(min = …)` does not prevent that: a minimum is only a
 * floor, so a wider child still makes the *slot* wider and pushes its neighbours.
 * `-99.99` is six characters, not the five a "reserved" width was computed from,
 * and `Calibrating` is eleven.
 *
 * So the row is budgeted instead of padded, and this file is the budget:
 *
 *  - the unit slot uses the actual measured suffix width when available, not just
 *    a guessed monospace advance (which clipped W on a real device);
 *  - the number + unit occupy one bounded group. Inside it, the unit follows the
 *    actual number, not unused space reserved for a longer signed value. The
 *    group's width, battery position and line height remain independent of values;
 *  - the number and word font sizes are derived from that field and the font
 *    scale, never from the value being displayed. The size is therefore stable
 *    while readings change and only responds to the window width and the font
 *    scale;
 *  - the headline line box is a fixed height for the chosen size, so a word state
 *    cannot shrink the row.
 *
 * Sizes are in `sp` and widths in `dp` **after** the font scale, because a big
 * readout at a large accessibility font scale is wider than the same readout at
 * the default scale; treating `sp` as `dp` was the earlier arithmetic mistake.
 *
 * Everything here is Compose-free and pure so the geometry is unit tested rather
 * than discovered on a phone. The composables in `MonitorScreen.kt` only consume
 * [NumberRowPlan]; there is no second copy of these numbers on the drawing side.
 */
object ReadoutSlots {
    /** Sign, three integer digits, separator and two decimals: `−200.00`. */
    const val WIDEST_NUMBER_CHARS = 7

    /** Longest word headline: `Calibrating`. */
    const val WORD_HEADLINE_CHARS = 11

    /**
     * Advance width of the monospace telemetry face as a fraction of the font
     * size. Roboto Mono advances at 0.6em; 0.62 leaves a little slack so a
     * slightly wider device font cannot make a bounded slot overflow.
     */
    const val MONO_ADVANCE_EM = 0.62f

    /** Characters and gap of the unit slot (`mW`, `µA`, `Wh`). */
    const val UNIT_CHARS = 2
    const val UNIT_GAP_DP = 4
    /** Extra room for glyph ink/rounding beyond the measured text advance. */
    const val UNIT_PADDING_DP = 2

    /** Characters of the battery slot: up to `100` plus `%`, and its caption. */
    const val PERCENT_DIGIT_CHARS = 3
    const val PERCENT_CAPTION_CHARS = 7
    const val PERCENT_GAP_DP = 2

    /** Telemetry sizes, before the font scale is applied by Compose. */
    const val PORTRAIT_MAX_NUMBER_SP = 48f
    const val LANDSCAPE_MAX_NUMBER_SP = 34f
    const val PORTRAIT_MAX_WORD_SP = 26f
    const val LANDSCAPE_MAX_WORD_SP = 20f
    const val PORTRAIT_UNIT_SP = 20f
    const val LANDSCAPE_UNIT_SP = 14f
    const val PORTRAIT_PERCENT_SP = 24f
    const val LANDSCAPE_PERCENT_SP = 18f
    const val PORTRAIT_PERCENT_MARK_SP = 13f
    const val LANDSCAPE_PERCENT_MARK_SP = 10f

    /**
     * Legibility floors. A bounded slot can always be filled by shrinking the
     * text; below these sizes the number stops being readable, so the plan keeps
     * the floor and re-arranges instead (see [NumberRowPlan.percentBelow]).
     */
    const val PORTRAIT_MIN_NUMBER_SP = 22f
    const val LANDSCAPE_MIN_NUMBER_SP = 20f
    const val MIN_WORD_SP = 18f

    /**
     * Below these sizes a single-line readout would be too small to be worth the
     * saved line, so the battery slot moves under the number instead (a stable
     * arrangement, chosen from width/font scale, never from the value).
     */
    const val PORTRAIT_SINGLE_LINE_MIN_NUMBER_SP = 36f
    const val LANDSCAPE_SINGLE_LINE_MIN_NUMBER_SP = 26f

    /** Word used by the stable status line when the headline word cannot fit. */
    const val WORD_FALLBACK_SP = 15f

    /** Reserved single-line ready-state line (`captionText`: 10sp on a 15sp line). */
    const val READINESS_LINE_SP = 10f
    const val READINESS_LINE_RATIO = 1.5f

    /**
     * Reading-age text under the readout: 10sp telemetry, and the longest variant
     * the screen can produce (`Sample 2m 30s ago`). It owns half a narrow row, so
     * the budget is checked rather than assumed.
     */
    const val STATUS_TEXT_SP = 10f
    const val STATUS_TEXT_CHARS = 18

    /** Gap between the recording-state label and the reading-age text. */
    const val STATUS_ROW_SPACING_DP = 12

    /** Window-statistic value and unit sizes. */
    const val STAT_VALUE_SP = 14f
    const val STAT_UNIT_SP = 9f
    const val STAT_UNIT_GAP_DP = 4

    /** Above this font scale the three statistics go one per line. */
    const val STACKED_STATS_ABOVE_FONT_SCALE = 1.4f

    /**
     * Baseline drop of a line box, as a fraction of its font size: a smaller run
     * needs this much bottom padding to sit on the same baseline as the number.
     */
    const val DESCENT_EM = 0.28f

    /** Layout chrome the width budgets below assume. */
    const val PORTRAIT_SIDE_PADDING_DP = 18
    const val LANDSCAPE_STRIP_PADDING_DP = 12
    const val LANDSCAPE_STRIP_SPACING_DP = 12
    const val LANDSCAPE_READOUT_FRACTION = 1.2
    const val LANDSCAPE_FLOW_FRACTION = 1.0
    const val LANDSCAPE_STATS_FRACTION = 1.4

    /** Narrowest content width the portrait layout is designed for (320dp phone). */
    const val PORTRAIT_MIN_CONTENT_WIDTH_DP = 284

    /** Narrowest landscape width the strip is designed for. */
    const val LANDSCAPE_MIN_CONTENT_WIDTH_DP = 456

    /**
     * Fixed set of widths and sizes for one readout row.
     *
     * [numberSlotDp] is all the space the fixed slots leave over; [numberFieldDp] is
     * maximum width for a numeric reading. Rendering packs digits and their suffix
     * into [powerGroupWidthDp]; unused capacity stays after the pair. The group's
     * footprint and the battery position do not change with the displayed value.
     */
    data class NumberRowPlan(
        val landscape: Boolean,
        val fontScale: Float,
        val availableWidthDp: Int,
        /** Space left for the number or word after the fixed slots. */
        val numberSlotDp: Int,
        /** Width the number/word is drawn in: seven character cells, or the slot. */
        val numberFieldDp: Int,
        /** Slack between the unit and the battery column; absorbs all the rounding. */
        val spareWidthDp: Int,
        val unitSlotDp: Int,
        val percentSlotDp: Int,
        val numberSizeSp: Float,
        val wordSizeSp: Float,
        val unitSizeSp: Float,
        val percentSizeSp: Float,
        val percentMarkSizeSp: Float,
        /**
         * False when even the fixed slot cannot show the word at
         * [MIN_WORD_SP]; the screen then keeps the word in a separate always-
         * present status line so it stays fully readable.
         */
        val wordFits: Boolean,
        /** True when the battery slot sits under the number instead of beside it. */
        val percentBelow: Boolean,
        /** Fixed height of the big line box, in dp, for this size and font scale. */
        val headlineHeightDp: Int,
        /** Bottom padding that puts the `%` mark on the percent value baseline. */
        val percentMarkBaselineOffsetDp: Int,
    ) {
        /** Stable outer footprint; the suffix is packed beside the digits inside. */
        val powerGroupWidthDp: Int get() = numberFieldDp + unitSlotDp
        val unitTextWidthDp: Int get() = (unitSlotDp - UNIT_GAP_DP).coerceAtLeast(0)
    }

    /** Compose `fontScale` guarded against a zero or nonsense value. */
    fun sanitizedFontScale(fontScale: Float): Float =
        if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f

    /** dp width a run of [chars] monospace characters needs at [sizeSp]. */
    fun textWidthDp(chars: Int, sizeSp: Float, fontScale: Float): Float =
        chars * sizeSp * MONO_ADVANCE_EM * sanitizedFontScale(fontScale)

    /**
     * Font size at which [chars] characters fit [widthDp] at [fontScale].
     *
     * This is the bounded-sizing rule the whole fix rests on: the size is a
     * function of the space, so the text always fits the slot it was given.
     */
    fun sizeForWidthSp(widthDp: Int, chars: Int, fontScale: Float): Float {
        val denominator = chars * MONO_ADVANCE_EM * sanitizedFontScale(fontScale)
        if (denominator <= 0f) return 0f
        return widthDp / denominator
    }

    fun unitSizeSp(landscape: Boolean): Float =
        if (landscape) LANDSCAPE_UNIT_SP else PORTRAIT_UNIT_SP

    fun percentSizeSp(landscape: Boolean): Float =
        if (landscape) LANDSCAPE_PERCENT_SP else PORTRAIT_PERCENT_SP

    fun percentMarkSizeSp(landscape: Boolean): Float =
        if (landscape) LANDSCAPE_PERCENT_MARK_SP else PORTRAIT_PERCENT_MARK_SP

    /** Prefer actual Compose font metrics; the approximation is a test/fallback. */
    fun unitSlotWidthDp(
        fontScale: Float,
        landscape: Boolean,
        measuredUnitTextWidthDp: Float? = null,
    ): Int {
        val textWidth = measuredUnitTextWidthDp?.takeIf { it.isFinite() && it > 0f }
            ?: textWidthDp(UNIT_CHARS, unitSizeSp(landscape), fontScale)
        return ceil(textWidth + UNIT_GAP_DP + UNIT_PADDING_DP).toInt()
    }

    /** Longest battery caption is `battery`; `low` is shorter, so it cannot grow it. */
    fun percentCaptionWidthDp(fontScale: Float): Float =
        textWidthDp(PERCENT_CAPTION_CHARS, READINESS_LINE_SP, fontScale)

    /** Fixed battery slot width: the wider of `100 %` and the caption below it. */
    fun percentSlotWidthDp(fontScale: Float, landscape: Boolean): Int {
        val value = textWidthDp(PERCENT_DIGIT_CHARS, percentSizeSp(landscape), fontScale) +
            textWidthDp(1, percentMarkSizeSp(landscape), fontScale) +
            PERCENT_GAP_DP
        return ceil(maxOf(value, percentCaptionWidthDp(fontScale))).toInt()
    }

    /** Fixed height of the big line box for a number of [sizeSp]. */
    fun headlineHeightDp(sizeSp: Float, fontScale: Float): Int =
        ceil(sizeSp * TELEMETRY_LINE_HEIGHT_RATIO * sanitizedFontScale(fontScale)).toInt()

    /** Baseline compensation for a smaller run drawn on the same bottom edge. */
    fun baselineOffsetDp(biggerSp: Float, smallerSp: Float, fontScale: Float): Int {
        val delta = (biggerSp - smallerSp) * DESCENT_EM * sanitizedFontScale(fontScale)
        return ceil(delta).toInt().coerceAtLeast(0)
    }

    /** Height reserved for the single ready-state line under the readout. */
    fun readinessLineHeightDp(fontScale: Float): Int =
        ceil(READINESS_LINE_SP * READINESS_LINE_RATIO * sanitizedFontScale(fontScale)).toInt()

    /** Width the longest reading-age text needs at [fontScale]. */
    fun statusTextWidthDp(fontScale: Float): Int =
        ceil(textWidthDp(STATUS_TEXT_CHARS, STATUS_TEXT_SP, fontScale)).toInt()

    /**
     * Width each half of the status row gets: the row splits evenly, so the age text
     * is budgeted from this and not from the whole row.
     */
    fun statusHalfWidthDp(contentWidthDp: Int = PORTRAIT_MIN_CONTENT_WIDTH_DP): Int =
        ((contentWidthDp - STATUS_ROW_SPACING_DP) / 2).coerceAtLeast(0)

    /**
     * The readout row for [availableWidthDp] of real space.
     *
     * A single line is used while the number keeps at least
     * [PORTRAIT_SINGLE_LINE_MIN_NUMBER_SP]/[LANDSCAPE_SINGLE_LINE_MIN_NUMBER_SP]
     * and the word headline still fits; otherwise the battery slot moves below the
     * number, which gives the number and the word the battery slot's width back.
     * The decision depends on width and font scale only, so it never flips while
     * readings change.
     */
    fun numberRowPlan(
        availableWidthDp: Int,
        fontScale: Float,
        landscape: Boolean,
        measuredUnitTextWidthDp: Float? = null,
    ): NumberRowPlan {
        val scale = sanitizedFontScale(fontScale)
        val width = availableWidthDp.coerceAtLeast(0)
        val unit = unitSlotWidthDp(scale, landscape, measuredUnitTextWidthDp)
        val percent = percentSlotWidthDp(scale, landscape)
        val single = planFor(
            slotDp = (width - unit - percent).coerceAtLeast(0),
            availableWidthDp = width,
            fontScale = scale,
            landscape = landscape,
            percentBelow = false,
            unitSlotDp = unit,
            percentSlotDp = percent,
        )
        val singleLineMin =
            if (landscape) LANDSCAPE_SINGLE_LINE_MIN_NUMBER_SP else PORTRAIT_SINGLE_LINE_MIN_NUMBER_SP
        if (single.numberSizeSp >= singleLineMin && single.wordFits) return single
        return planFor(
            slotDp = (width - unit).coerceAtLeast(0),
            availableWidthDp = width,
            fontScale = scale,
            landscape = landscape,
            percentBelow = true,
            unitSlotDp = unit,
            percentSlotDp = percent,
        )
    }

    private fun planFor(
        slotDp: Int,
        availableWidthDp: Int,
        fontScale: Float,
        landscape: Boolean,
        percentBelow: Boolean,
        unitSlotDp: Int,
        percentSlotDp: Int,
    ): NumberRowPlan {
        val maxNumber = if (landscape) LANDSCAPE_MAX_NUMBER_SP else PORTRAIT_MAX_NUMBER_SP
        val minNumber = if (landscape) LANDSCAPE_MIN_NUMBER_SP else PORTRAIT_MIN_NUMBER_SP
        val maxWord = if (landscape) LANDSCAPE_MAX_WORD_SP else PORTRAIT_MAX_WORD_SP
        val rawNumber = sizeForWidthSp(slotDp, WIDEST_NUMBER_CHARS, fontScale)
        val numberSize = rawNumber.coerceIn(minNumber, maxNumber)
        val fieldDp = minOf(
            slotDp,
            ceil(textWidthDp(WIDEST_NUMBER_CHARS, numberSize, fontScale)).toInt(),
        )
        // A word state has no suffix, so it can use the whole group rather than
        // squeezing into numeric space while the unused unit column sits empty.
        val rawWord = sizeForWidthSp(fieldDp + unitSlotDp, WORD_HEADLINE_CHARS, fontScale)
        val wordSize = rawWord.coerceIn(MIN_WORD_SP, maxWord)
        val unitSize = unitSizeSp(landscape)
        val percentSize = percentSizeSp(landscape)
        val markSize = percentMarkSizeSp(landscape)
        val spare = (availableWidthDp - fieldDp - unitSlotDp - if (percentBelow) 0 else percentSlotDp)
            .coerceAtLeast(0)
        return NumberRowPlan(
            landscape = landscape,
            fontScale = fontScale,
            availableWidthDp = availableWidthDp,
            numberSlotDp = slotDp,
            numberFieldDp = fieldDp,
            spareWidthDp = spare,
            unitSlotDp = unitSlotDp,
            percentSlotDp = percentSlotDp,
            numberSizeSp = numberSize,
            wordSizeSp = wordSize,
            unitSizeSp = unitSize,
            percentSizeSp = percentSize,
            percentMarkSizeSp = markSize,
            wordFits = rawWord >= MIN_WORD_SP,
            percentBelow = percentBelow,
            headlineHeightDp = headlineHeightDp(numberSize, fontScale),
            percentMarkBaselineOffsetDp = baselineOffsetDp(percentSize, markSize, fontScale),
        )
    }

    /** Readout row width inside the portrait content padding. */
    fun portraitNumberRowWidthDp(windowWidthDp: Int): Int =
        (windowWidthDp - 2 * PORTRAIT_SIDE_PADDING_DP).coerceAtLeast(0)

    /**
     * Readout row width inside the landscape readings strip.
     *
     * The strip splits its width between the readout cell and the flow cell, so the
     * row must fit the smaller cell, not the whole strip.
     */
    fun landscapeNumberRowWidthDp(windowWidthDp: Int): Int = splitCellWidthDp(
        contentWidthDp = windowWidthDp - 2 * LANDSCAPE_STRIP_PADDING_DP,
        fraction = LANDSCAPE_READOUT_FRACTION,
        totalFractions = LANDSCAPE_READOUT_FRACTION + LANDSCAPE_FLOW_FRACTION,
        spacingDp = LANDSCAPE_STRIP_SPACING_DP,
    )

    /** Share of a strip a [fraction] of the row gets. */
    fun splitCellWidthDp(
        contentWidthDp: Int,
        fraction: Double,
        totalFractions: Double,
        spacingDp: Int = LANDSCAPE_STRIP_SPACING_DP,
    ): Int {
        if (totalFractions <= 0.0) return 0
        val usable = (contentWidthDp - spacingDp).coerceAtLeast(0)
        return floor(usable * fraction / totalFractions).toInt()
    }

    /** The narrowest supported portrait readout, used by the geometry tests. */
    fun narrowestPortraitPlan(fontScale: Float = 1f): NumberRowPlan =
        numberRowPlan(PORTRAIT_MIN_CONTENT_WIDTH_DP, fontScale, landscape = false)

    /** The narrowest supported landscape readout, used by the geometry tests. */
    fun narrowestLandscapePlan(fontScale: Float = 1f): NumberRowPlan =
        numberRowPlan(landscapeNumberRowWidthDp(LANDSCAPE_MIN_CONTENT_WIDTH_DP), fontScale, landscape = true)

    /** Fixed width of the statistic unit slot, so a value cannot move the suffix. */
    fun statUnitSlotWidthDp(fontScale: Float): Int =
        ceil(textWidthDp(UNIT_CHARS, STAT_UNIT_SP, fontScale) + STAT_UNIT_GAP_DP).toInt()

    /** Height of a statistic line box, so a missing unit cannot shrink the row. */
    fun statLineHeightDp(fontScale: Float): Int =
        ceil(STAT_VALUE_SP * TELEMETRY_LINE_HEIGHT_RATIO * sanitizedFontScale(fontScale)).toInt()

    /** True when the statistics need one line each to stay readable. */
    fun statsStackVertically(fontScale: Float): Boolean =
        sanitizedFontScale(fontScale) > STACKED_STATS_ABOVE_FONT_SCALE
}
