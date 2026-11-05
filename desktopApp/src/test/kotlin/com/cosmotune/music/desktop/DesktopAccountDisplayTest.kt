package com.cosmotune.music.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** BC-21 (upstream #614): the account button follows the selected YouTube profile, not the Google login. Pure; no registry. */
class DesktopAccountDisplayTest {
    private val personal = DesktopYouTubeProfile(profileId = "p", name = "Personal", avatar = "personal.png")
    private val brand = DesktopYouTubeProfile(profileId = "b", name = "Brand", avatar = "brand.png", isBrandAccount = true)
    private val login = DesktopGoogleAccount(accountId = "a", name = "Login", avatar = "login.png", profiles = listOf(personal, brand))

    @Test fun theSelectedProfileWins() {
        assertEquals("brand.png", login.shownAvatar("b"))
        assertEquals("Brand", login.shownName("b"))
    }

    @Test fun withoutASelectionTheLoginThenItsFirstProfile() {
        assertEquals("login.png", login.shownAvatar(null))
        assertEquals("Login", login.shownName(null))
        assertEquals("personal.png", login.copy(avatar = " ").shownAvatar("gone"))
    }

    @Test fun blankValuesFallThroughAndNoAccountShowsNothing() {
        val blankBrand = login.copy(profiles = listOf(personal, brand.copy(name = "", avatar = "")))
        assertEquals("login.png", blankBrand.shownAvatar("b"))
        assertEquals("Login", blankBrand.shownName("b"))
        assertNull(login.copy(name = "").shownName(null))
        val none: DesktopGoogleAccount? = null
        assertNull(none.shownAvatar("b"))
        assertNull(none.shownName("b"))
    }
}
