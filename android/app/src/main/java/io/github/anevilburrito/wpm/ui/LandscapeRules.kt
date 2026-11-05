package io.github.anevilburrito.wpm.ui

/**
 * Pure layout arithmetic for the two dashboard compositions.
 *
 * The landscape dashboard once collapsed on a real phone: a single-line label was
 * the only flexible child of a toolbar row, so once the controls asked for more
 * width than the row had, the label was measured a few dp wide and its letters
 * stacked one per line, taking the readings strip and the plot with them.
 *
 * These numbers are the budget that makes that impossible. The composables only
 * use the helpers below to choose between one-row and two-row layouts and between
 * a flexible plot and a scrolling fallback, so the decision is unit tested instead
 * of being an emergent result of Row measurement.
 *
 * Two rules keep the budget honest:
 *
 *  - Heights are derived from the text actually drawn. Telemetry styles use a
 *    proportional line height ([TELEMETRY_LINE_HEIGHT_RATIO]), so a 34sp readout
 *    needs ~41dp of line box, not the 20dp a `bodyMedium` inheritance produced.
 *  - [PLOT_MIN_HEIGHT_DP] is the **canvas** only. The x-axis clock row and the
 *    legend are budgeted on top of it ([AXIS_AND_LEGEND_HEIGHT_DP]) and are never
 *    folded into the canvas figure, so a "120dp plot" really is 120dp of chart.
 */
object LandscapeRules {
    /**
     * Fixed ranges strip above the plot; it is never allowed to shrink away.
     *
     * Budget: one telemetry(34sp) row at a 1.2 line height (41dp), its battery
     * column (18sp value + 9sp caption, 36dp) and the flow cell's three single
     * lines (direction, voltage/live current, ready-state note, ~35dp). The
     * instant caption that used to sit under the big number is gone: the toolbar
     * caption and the chart itself already say what the number is.
     */
    const val READINGS_STRIP_HEIGHT_DP = 56

    /** Narrower than this the strip uses two short rows instead of three columns. */
    const val COMPACT_STRIP_BELOW_WIDTH_DP = 620

    /** Two-row version of the readings strip: readout row plus window statistics. */
    const val COMPACT_READINGS_STRIP_HEIGHT_DP = 92

    /**
     * One toolbar line: caption plus controls. The chips, scale toggle and freeze
     * control are all at least 48dp tall, so the line cannot be shorter than that.
     */
    const val TOOLBAR_MIN_HEIGHT_DP = 48

    /** Two toolbar lines: caption on top, range/scale/freeze controls below. */
    const val TOOLBAR_ROW_HEIGHT_DP = 48

    /** Below this width the controls get their own toolbar line. */
    const val TWO_ROW_TOOLBAR_BELOW_WIDTH_DP = 720

    /** A caption/one-line label must always have at least this much width. */
    const val LABEL_MIN_WIDTH_DP = 132

    /** Fixed chip budget; chips are intrinsic and never stretch. */
    const val CHIP_MIN_WIDTH_DP = 48
    const val CHIP_SPACING_DP = 2
    const val CHIP_COUNT = 3

    /** Measured widths of the two remaining controls, including their padding. */
    const val SCALE_TOGGLE_WIDTH_DP = 126
    const val FREEZE_CONTROL_WIDTH_DP = 82
    const val CONTROL_SPACING_DP = 6

    /** Toolbar chrome, app bar and the slim bottom deck. */
    const val HEADER_HEIGHT_DP = 60
    const val DECK_HEIGHT_DP = 60

    /**
     * x-axis clock row and the chart legend under the **canvas**.
     *
     * 16dp clock row + 6dp legend gap + a 16sp legend line. Budgeted separately so
     * the canvas minimum is never silently reduced by the decoration below it.
     */
    const val AXIS_AND_LEGEND_HEIGHT_DP = 40

    /** The chart canvas itself never gets less than this in landscape. */
    const val PLOT_MIN_HEIGHT_DP = 120

    /** Height the ChartArea composable needs: the canvas plus axis and legend. */
    const val CHART_MIN_HEIGHT_DP = PLOT_MIN_HEIGHT_DP + AXIS_AND_LEGEND_HEIGHT_DP

    /**
     * A notice under the strip. [AttentionNote] draws two bodyText lines (17sp line
     * height each) inside 6dp vertical padding; the landscape notice is capped at
     * those two lines, so this is its real maximum height.
     */
    const val NOTICE_HEIGHT_DP = 2 * 17 + 2 * 6

    /** Portrait keeps the approved stacked chart height. */
    const val PORTRAIT_PLOT_HEIGHT_DP = 184

    /**
     * Total ChartArea height in the scrolling fallback; the canvas is this minus
     * [AXIS_AND_LEGEND_HEIGHT_DP], which still leaves more than [PLOT_MIN_HEIGHT_DP].
     */
    const val SCROLL_FALLBACK_CHART_HEIGHT_DP = 240

    /**
     * At any font scale above the default the dp budgets above no longer describe
     * the text, so the landscape detail scrolls instead of compressing the chart.
     * Conservative by design: chart-first landscape is a default-scale layout.
     */
    const val MAX_LAYOUT_FONT_SCALE = 1.0f

    fun usesTwoRowToolbar(widthDp: Int): Boolean = widthDp < TWO_ROW_TOOLBAR_BELOW_WIDTH_DP

    fun usesCompactStrip(widthDp: Int): Boolean = widthDp < COMPACT_STRIP_BELOW_WIDTH_DP

    fun readingsStripHeightDp(widthDp: Int): Int =
        if (usesCompactStrip(widthDp)) COMPACT_READINGS_STRIP_HEIGHT_DP else READINGS_STRIP_HEIGHT_DP

    fun toolbarHeightDp(widthDp: Int): Int =
        if (usesTwoRowToolbar(widthDp)) TOOLBAR_ROW_HEIGHT_DP * 2 else TOOLBAR_MIN_HEIGHT_DP

    /** Width of the three range chips: three intrinsic chips and two gaps. */
    fun rangeChipsWidthDp(): Int = CHIP_COUNT * CHIP_MIN_WIDTH_DP + (CHIP_COUNT - 1) * CHIP_SPACING_DP

    /** Width of the bounded control group (chips, scale toggle, freeze). */
    fun controlsWidthDp(): Int =
        rangeChipsWidthDp() + SCALE_TOGGLE_WIDTH_DP + FREEZE_CONTROL_WIDTH_DP + 3 * CONTROL_SPACING_DP

    /** Smallest width that still keeps the label at [LABEL_MIN_WIDTH_DP] on one line. */
    fun oneLineToolbarMinWidthDp(): Int = LABEL_MIN_WIDTH_DP + controlsWidthDp()

    fun toolbarFitsOnOneLine(widthDp: Int): Boolean = widthDp >= oneLineToolbarMinWidthDp()

    /** Width left for the chart caption inside the toolbar. */
    fun labelWidthDp(widthDp: Int): Int =
        (widthDp - if (usesTwoRowToolbar(widthDp)) 0 else controlsWidthDp()).coerceAtLeast(0)

    /** True when the caption keeps a readable single line at [widthDp]. */
    fun labelCannotCollapse(widthDp: Int): Boolean = labelWidthDp(widthDp) >= LABEL_MIN_WIDTH_DP

    /**
     * Everything that is not the chart: header, readings strip, toolbar, deck.
     * The chart's own axis and legend are deliberately excluded; see
     * [chartHeightDp] and [plotHeightDp].
     */
    fun fixedChromeHeightDp(widthDp: Int): Int =
        HEADER_HEIGHT_DP +
            readingsStripHeightDp(widthDp) +
            toolbarHeightDp(widthDp) +
            DECK_HEIGHT_DP

    /** Height the whole ChartArea gets, including its axis row and legend. */
    fun chartHeightDp(widthDp: Int, heightDp: Int, hasNotice: Boolean = false): Int =
        heightDp - fixedChromeHeightDp(widthDp) - if (hasNotice) NOTICE_HEIGHT_DP else 0

    /** Height of the drawable canvas, i.e. the chart minus axis and legend. */
    fun plotHeightDp(widthDp: Int, heightDp: Int, hasNotice: Boolean = false): Int =
        chartHeightDp(widthDp, heightDp, hasNotice) - AXIS_AND_LEGEND_HEIGHT_DP

    /**
     * True when the canvas cannot keep [PLOT_MIN_HEIGHT_DP] inside a fixed layout,
     * so the landscape detail scrolls at the fixed fallback height instead.
     */
    fun needsScrolling(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
        hasNotice: Boolean = false,
    ): Boolean =
        fontScale > MAX_LAYOUT_FONT_SCALE ||
            chartHeightDp(widthDp, heightDp, hasNotice) < CHART_MIN_HEIGHT_DP

    /** Landscape is decided on the already inset-padded size, without a bias factor. */
    fun isLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp >= heightDp
}
