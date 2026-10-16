package com.cosmotune.design

/**
 * The room inside the profile drawer's panel (`.drawer` of the boards). The panel itself is as tall as the whole window, bars
 * included (the window that draws it is edge to edge); what it keeps clear of the bars is its content, by these paddings. Dp as
 * floats, like [BlockMetrics].
 */
object DrawerMetrics {
    /** The content starts this far under the status bar. */
    const val PANEL_TOP_DP = 8f

    /** The content ends at least this far over the bottom edge, and over the navigation bar when that is taller. */
    const val PANEL_BOTTOM_DP = 28f

    /** The top padding of the panel's content under a status bar [statusBarDp] tall. */
    fun panelTopPaddingDp(statusBarDp: Float): Float = statusBarDp + PANEL_TOP_DP

    /** The bottom padding of the panel's content over a navigation bar [navBarDp] tall: 28 dp, or the bar's own height when that is more. */
    fun panelBottomPaddingDp(navBarDp: Float): Float = maxOf(PANEL_BOTTOM_DP, navBarDp)

    /** The height left for the content in a window [windowHeightDp] tall (bars included); never negative. */
    fun panelContentHeightDp(windowHeightDp: Float, statusBarDp: Float, navBarDp: Float): Float =
        (windowHeightDp - panelTopPaddingDp(statusBarDp) - panelBottomPaddingDp(navBarDp)).coerceAtLeast(0f)
}
