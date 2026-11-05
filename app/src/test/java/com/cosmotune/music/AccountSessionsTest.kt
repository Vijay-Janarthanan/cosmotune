package com.cosmotune.music

import com.cosmotune.music.auth.GoogleAccountSession
import com.cosmotune.music.auth.YouTubeProfile
import com.cosmotune.music.auth.adjacentProfile
import com.cosmotune.music.auth.flattenedProfiles
import com.cosmotune.music.auth.mergedYouTubeCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountSessionsTest {
    private val a = GoogleAccountSession("a", "cookie-a", profiles = listOf(
        YouTubeProfile("a-personal", "Personal"), YouTubeProfile("a-brand", "Brand"),
    ))
    private val b = GoogleAccountSession("b", "cookie-b", profiles = listOf(
        YouTubeProfile("b-personal", "Personal"), YouTubeProfile("b-brand", "Brand"),
    ))

    @Test fun `profiles retain account then profile ordering`() {
        assertEquals(listOf("a" to "a-personal", "a" to "a-brand", "b" to "b-personal", "b" to "b-brand"),
            flattenedProfiles(listOf(a, b)))
    }

    @Test fun `next previous and boundaries do not wrap`() {
        assertEquals("a" to "a-brand", adjacentProfile(listOf(a, b), "a", "a-personal", true))
        assertEquals("b" to "b-personal", adjacentProfile(listOf(a, b), "b", "b-brand", false))
        assertNull(adjacentProfile(listOf(a, b), "a", "a-personal", false))
        assertNull(adjacentProfile(listOf(a, b), "b", "b-brand", true))
    }

    @Test fun `one profile has no adjacent profile`() {
        val only = GoogleAccountSession("a", "cookie", profiles = listOf(YouTubeProfile("p", "Personal")))
        assertNull(adjacentProfile(listOf(only), "a", "p", true))
        assertNull(adjacentProfile(listOf(only), "a", "p", false))
    }

    @Test fun `the captured jar merges both origins with Music winning`() {
        assertEquals(
            "SID=1; PREF=music; YSC=www; SAPISID=s",
            mergedYouTubeCookies("SID=1; PREF=www; YSC=www", "SID=1; PREF=music; SAPISID=s"),
        )
        assertEquals("SAPISID=s", mergedYouTubeCookies(null, "SAPISID=s"))
        assertNull(mergedYouTubeCookies(null, null))
    }

    @Test fun `a session saved before the accounts jar existed has none`() {
        assertNull(GoogleAccountSession("a", "cookie").googleCookie)
    }
}
