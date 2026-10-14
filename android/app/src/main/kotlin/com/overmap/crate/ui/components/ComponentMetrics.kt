package com.overmap.crate.ui.components

import androidx.compose.ui.unit.dp

/**
 * Component-level sizes the design boards specify but `tokens.json` does not (yet) export. Same values as the iOS
 * `Metric` enum (CrateUI/Components/ComponentMetrics.swift), taken from `Crate Update v2.dc.html` and
 * `Crate Design System.dc.html`.
 */
internal object Metric {
    // Chips
    /** Horizontal padding inside a 32 dp chip. */
    val chipPaddingH = 10.dp
    /** Gap between symbol, label and count inside a chip. */
    val chipInnerGap = 5.dp
    /** Hue dot inside an off style chip. */
    val styleDot = 8.dp
    /** Hue dot in picker rows. */
    val familyDot = 10.dp
    /** FamilyMiniChip height (radius = Radius.badge). */
    val miniChipHeight = 20.dp
    val miniChipPaddingH = 6.dp

    // Confidence
    val confidenceDot = 7.dp
    val confidenceDash = 6.dp
    val confidenceDotGap = 3.dp
    val confidenceLineHeight = 28.dp
    val voteSourceColumn = 74.dp
    val listMarker = 11.dp

    // Energy
    val microTickWidth = 2.dp
    val microTickHeight = 9.dp
    val microTickGap = 1.dp
    val displayBlockHeight = 18.dp
    val displayBlockRadius = 2.dp
    val displayBlockGap = 3.dp
    val inputBlockGap = 4.dp

    // Range slider
    val thumbDragging = 34.dp
    val trackHeight = 4.dp
    val halfThumbInnerRadius = 4.dp

    // Camelot
    val badgeTagLine = 18.dp
    val badgeSmall = 20.dp
    val badgeMedium = 28.dp
    val badgeLarge = 48.dp
    /** Wheel geometry on a 320 dp reference (A inner, B outer). */
    const val WHEEL_REFERENCE = 320f
    val wheelReference = 320.dp
    const val WHEEL_INNER_FROM = 64f
    const val WHEEL_INNER_TO = 108f
    const val WHEEL_OUTER_FROM = 110f
    const val WHEEL_OUTER_TO = 156f
    val wheelSelectedRing = 3.dp
    val wheelGap = 2.dp
    const val WHEEL_DIMMED = 0.25f

    // Misc
    /** Opacity of a chip / row whose count is 0. */
    const val DIMMED = 0.45f
    val tickRing = 24.dp
    val presetTile = 32.dp
    val presetPillTile = 18.dp
    val editedDot = 5.dp
    val coverStripe = 4.dp
}
