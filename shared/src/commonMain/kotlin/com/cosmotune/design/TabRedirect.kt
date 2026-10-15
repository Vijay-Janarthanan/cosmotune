package com.cosmotune.design

/**
 * The phone's tab that is shown for the tab that was saved. The new look has three tabs (Home, Search, Library) and Explore lives
 * inside Search, so Explore without a mood page open is Search; with a mood page open it is still Explore (the mood page is filed
 * under it and keeps Search lit). The old look keeps its four tabs. This is read wherever the tab is read, so no frame composes
 * Explore on its way to Search.
 */
object TabRedirect {
    fun shown(stored: Int, newLook: Boolean, moodPageOpen: Boolean, exploreTab: Int, searchTab: Int): Int =
        if (newLook && stored == exploreTab && !moodPageOpen) searchTab else stored
}
