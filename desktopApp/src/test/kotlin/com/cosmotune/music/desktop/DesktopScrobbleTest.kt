package com.cosmotune.music.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scrobbling arithmetic that is invisible when wrong.
 *
 * A listen submitted under the wrong artist, or at the wrong moment, still
 * looks like a working feature — it just quietly writes the wrong thing to
 * somebody's listening history. Ported alongside Android's `PrimaryArtist`.
 */
class DesktopScrobbleTest {

    @Test
    fun aJointCreditIsCutBackToTheFirstArtist() {
        assertEquals("Kendrick Lamar", "Kendrick Lamar, SZA".primaryArtist())
        assertEquals("Calvin Harris", "Calvin Harris & Dua Lipa".primaryArtist())
        assertEquals("Yoasobi", "Yoasobi ＆ Ikura".primaryArtist())
    }

    @Test
    fun aNameWithNoSeparatorIsLeftAlone() {
        // A slash is not a separator, which is what keeps "AC/DC" whole.
        assertEquals("AC/DC", "AC/DC".primaryArtist())
        assertEquals("Florence + The Machine", "Florence + The Machine".primaryArtist())
    }

    @Test
    fun aBandWhoseOwnNameHoldsASeparatorIsKeptWhole() {
        // A known band whose own name has a comma or an ampersand is one artist, alone or with a guest, on Android and
        // desktop alike; a joint credit of two people is still cut to the first.
        assertEquals("Simon & Garfunkel", "Simon & Garfunkel".primaryArtist())
        assertEquals("Earth, Wind & Fire", "Earth, Wind & Fire".primaryArtist())
        assertEquals("Earth, Wind & Fire", "Earth, Wind & Fire & The Emotions".primaryArtist())
        assertEquals("Anitta", "Anitta & Shakira".primaryArtist())
    }

    @Test
    fun anEmptyLeadingCreditFallsBackToTheWholeName() {
        assertEquals(", SZA", ", SZA".primaryArtist())
    }

    @Test
    fun theDefaultsAreAndroidsOwn() {
        assertEquals(30, DesktopScrobbleSettings.DEFAULT_MIN_DURATION)
        assertEquals(0.5f, DesktopScrobbleSettings.DEFAULT_DELAY_PERCENT)
        assertEquals(180, DesktopScrobbleSettings.DEFAULT_DELAY_SECONDS)
    }
}
