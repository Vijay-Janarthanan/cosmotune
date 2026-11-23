package com.cosmotune.music.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeLinksTest {

    @Test
    fun songLinksInEveryShape() {
        val song = YouTubeLink.Song("dQw4w9WgXcQ")
        assertEquals(song, YouTubeLinks.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"))
        assertEquals(song, YouTubeLinks.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals(song, YouTubeLinks.parse("  youtu.be/dQw4w9WgXcQ?si=abc "))
        assertEquals(song, YouTubeLinks.parse("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(song, YouTubeLinks.parse("m.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun pagesForPlaylistsAlbumsAndArtists() {
        assertEquals(YouTubeLink.Page("VLPL123abc"), YouTubeLinks.parse("https://music.youtube.com/playlist?list=PL123abc"))
        assertEquals(YouTubeLink.Page("VLOLAK5uy_x"), YouTubeLinks.parse("https://music.youtube.com/playlist?list=OLAK5uy_x"))
        val artist = YouTubeLinks.parse("https://music.youtube.com/channel/UCabcdef")
        assertEquals(YouTubeLink.Page("UCabcdef"), artist)
        assertTrue((artist as YouTubeLink.Page).isArtist)
        assertEquals(YouTubeLink.Page("MPREb_xyz"), YouTubeLinks.parse("https://music.youtube.com/browse/MPREb_xyz"))
    }

    @Test
    fun wordsAndOtherSitesAreNotLinks() {
        assertNull(YouTubeLinks.parse("never gonna give you up"))
        assertNull(YouTubeLinks.parse("https://open.spotify.com/track/abc"))
        assertNull(YouTubeLinks.parse("youtube"))
        assertNull(YouTubeLinks.parse("https://music.youtube.com/"))
        assertNull(YouTubeLinks.parse("https://youtube.com/watch?v=short"))
    }
}
