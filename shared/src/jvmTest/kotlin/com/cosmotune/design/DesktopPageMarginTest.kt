package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One margin for every page of the desktop (`.d2-main { padding: 16px 28px 110px }`, `.dh-bento` margin 28): Home, the album and
 * playlist page and the artist page keep it themselves; the pages made of the phone's blocks keep the phone's 16 dp and are moved
 * in by what is missing, in the one box that holds the page.
 */
class DesktopPageMarginTest {

    @Test
    fun theDesktopsPageMarginIsTwentyEightAndThePhonesSixteen() {
        assertEquals(28f, ShellMetrics.DESKTOP_PAGE_MARGIN_DP)
        assertEquals(16f, ShellMetrics.PHONE_PAGE_MARGIN_DP)
    }

    @Test
    fun aPageOfThePhonesBlocksIsMovedInByTheTwelveItLacks() {
        assertEquals(12f, ShellMetrics.desktopPageInsetDp(pageKeepsPhoneMargin = true))
    }

    @Test
    fun aPageThatKeepsTheDesktopMarginItselfIsNotMovedAtAll() {
        assertEquals(0f, ShellMetrics.desktopPageInsetDp(pageKeepsPhoneMargin = false))
    }

    @Test
    fun everyPageEndsUpTwentyEightFromTheSidebar() {
        // a page's content stands at its own margin plus the inset
        assertEquals(ShellMetrics.DESKTOP_PAGE_MARGIN_DP, ShellMetrics.PHONE_PAGE_MARGIN_DP + ShellMetrics.desktopPageInsetDp(pageKeepsPhoneMargin = true))
        assertEquals(ShellMetrics.DESKTOP_PAGE_MARGIN_DP, ShellMetrics.DESKTOP_PAGE_MARGIN_DP + ShellMetrics.desktopPageInsetDp(pageKeepsPhoneMargin = false))
    }

    @Test
    fun theInsetNeverMakesAPageNarrowerThanItsOwnMargin() {
        for (keeps in listOf(true, false)) assertTrue(ShellMetrics.desktopPageInsetDp(keeps) >= 0f)
    }

    @Test
    fun theWideBentoOfHomeStartsAtTheSameMargin() {
        assertEquals(ShellMetrics.DESKTOP_PAGE_MARGIN_DP, Bento.WIDE.marginDp, "Home's grid margin is the desktop's page margin")
    }
}
