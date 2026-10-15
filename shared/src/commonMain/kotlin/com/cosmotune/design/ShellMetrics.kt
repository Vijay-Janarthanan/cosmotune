package com.cosmotune.design

import kotlin.math.max

/** The floating chrome's measurements (design formulas section 2), in dp. */
object ShellMetrics {
    const val SIDE_MARGIN_DP = 16f
    const val TAB_BAR_HEIGHT_DP = 64f
    const val CAPSULE_HEIGHT_DP = 64f
    const val CAPSULE_RADIUS_DP = 22f
    const val TAB_BAR_RADIUS_DP = 32f        // the tab bar is a full pill
    const val SHEET_INSET_DP = 8f            // bottom sheets and dialogs are detached: inset 8 dp, radius 36 on every corner
    const val SHEET_RADIUS_DP = 36f
    const val CAPSULE_GAP_DP = 10f
    const val MIN_BOTTOM_DP = 16f
    const val INSET_GAP_DP = 8f
    const val DESKTOP_BAR_HEIGHT_DP = 80f
    const val DESKTOP_BAR_MARGIN_DP = 14f
    const val DESKTOP_SIDEBAR_DP = 220f      // DesktopSidebar's width, DesktopApp.kt:4484
    const val DESKTOP_SIDE_PANEL_DP = 360f   // DesktopSidePanel.kt:120
    const val DESKTOP_COMPACT_DP = 980f      // DesktopFrame's compact threshold, DesktopApp.kt:4842

    /** How far the tab capsule floats above the bottom of the window: 16 dp, or 8 dp clear of a taller navigation bar. */
    fun bottomOffsetDp(navigationInsetDp: Float) = max(MIN_BOTTOM_DP, navigationInsetDp + INSET_GAP_DP)

    /** The height the phone's floating chrome takes from the bottom of the window. */
    fun phoneChromeHeightDp(navigationInsetDp: Float, withMiniPlayer: Boolean) =
        bottomOffsetDp(navigationInsetDp) + TAB_BAR_HEIGHT_DP + if (withMiniPlayer) CAPSULE_GAP_DP + CAPSULE_HEIGHT_DP else 0f

    fun desktopBarFootprintDp() = DESKTOP_BAR_HEIGHT_DP + 2 * DESKTOP_BAR_MARGIN_DP
}
