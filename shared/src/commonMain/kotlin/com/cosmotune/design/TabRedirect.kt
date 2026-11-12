package com.cosmotune.design

/**
 * The phone's tab that is shown for the tab that was saved. The saved numbers keep their meaning (Home 0, Explore 1, Library 2, Search
 * 3, as the host's constants say) so a state saved by an older build still lands on the same page, and Releases is appended as the
 * last number. Music's dock has Home, Explore, Releases and Library (DESIGN_SYSTEM.md 5.2) and Search is a page that the top bar opens.
 * For a saved number that is no tab at all the page is Home. This is read wherever the tab is read, so no frame composes a page that
 * cannot be reached.
 */
object TabRedirect {
    fun shown(stored: Int, homeTab: Int, releasesTab: Int): Int = if (stored in 0..releasesTab) stored else homeTab
}
