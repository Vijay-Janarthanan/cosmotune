package com.cosmotune.ui.orbit

import com.cosmotune.ui.theme.CosmoSpacing

/**
 * Orbit's spacing, by what the space is for. The steps are the 4 dp scale of [CosmoSpacing] (4, 8, 12, 16, 24, 32); the named
 * gaps say which step a place takes, so a text and the control under it never touch:
 *
 * - a label to its control (a field's name over the field, a switch's line beside it): [labelToControl], 8;
 * - a block of text to the action it leads to (an empty line and its button, a notice and its Retry): [textToAction], 12, or
 *   [textToActionLoose] 16 when the text is a paragraph or the action is a full button;
 * - one group of a page or a card to the next: [betweenGroups], 24;
 * - the inside of a card: [cardPadding], 16 ([cardPaddingLoose], 20, for a card with a heading of its own);
 * - an icon to its label: [iconToLabel], 8 (12 for a 24 dp icon in a row, which the rows already draw).
 *
 * A text and the control under it share one start edge: the control is not padded further in or out than its text.
 */
object OrbitSpace {
    val xs = CosmoSpacing.s1
    val sm = CosmoSpacing.s2
    val md = CosmoSpacing.s3
    val lg = CosmoSpacing.s4
    val xl = CosmoSpacing.s6
    val xxl = CosmoSpacing.s7

    val labelToControl = sm
    val iconToLabel = sm
    val textToAction = md
    val textToActionLoose = lg
    val betweenGroups = xl
    val cardPadding = lg
    val cardPaddingLoose = CosmoSpacing.s5
    /** Chips in a row or a wrap, and buttons side by side. */
    val chipGap = sm
}
