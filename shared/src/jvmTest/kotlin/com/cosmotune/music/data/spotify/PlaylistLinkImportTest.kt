package com.cosmotune.music.data.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlaylistLinkImportTest {

    @Test
    fun `links are told apart`() {
        assertEquals(PlaylistLinkImport.Service.APPLE_MUSIC, PlaylistLinkImport.serviceOf("https://music.apple.com/in/playlist/tamil-hits/pl.u-abc123"))
        assertEquals(PlaylistLinkImport.Service.APPLE_MUSIC, PlaylistLinkImport.serviceOf("https://music.apple.com/us/album/bombay/1443871811"))
        assertEquals(PlaylistLinkImport.Service.DEEZER, PlaylistLinkImport.serviceOf("Listen! https://www.deezer.com/en/playlist/908622995"))
        assertEquals(PlaylistLinkImport.Service.DEEZER, PlaylistLinkImport.serviceOf("https://link.deezer.com/s/30abc"))
        assertNull(PlaylistLinkImport.serviceOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(PlaylistLinkImport.serviceOf("hello"))
    }

    @Test
    fun `an Apple Music page is read from its server data`() {
        val html = """<html><head></head><body><script type="application/json" id="serialized-server-data">
            {"data":[{"data":{"sections":[
              {"itemKind":"containerDetailHeaderLockup","items":[{"title":"Tamil Hits","trackCount":3,
                "subtitleLinks":[{"title":"Apple Music Tamil"}]}]},
              {"itemKind":"trackLockup","items":[
                {"title":"Kannalane","artistName":"A. R. Rahman & K. S. Chithra","duration":335000,
                  "tertiaryLinks":[{"title":"Bombay"}]},
                {"title":"Munbe Vaa","subtitleLinks":[{"title":"Shreya Ghoshal"},{"title":"Naresh Iyer"}],"duration":360000}
              ]}]}}]}
            </script></body></html>"""
        val playlist = PlaylistLinkImport.parseApplePage(html, "playlist")
        assertEquals("Tamil Hits", playlist.name)
        assertEquals(3, playlist.declaredCount)
        assertEquals(2, playlist.tracks.size)
        assertEquals(PlaylistLinkImport.LinkTrack("Kannalane", "A. R. Rahman & K. S. Chithra", "Bombay", 335_000), playlist.tracks[0])
        assertEquals("Shreya Ghoshal, Naresh Iyer", playlist.tracks[1].artist)
    }

    @Test
    fun `a changed Apple page says so`() {
        assertFailsWith<PlaylistLinkImport.ImportException> { PlaylistLinkImport.parseApplePage("<html></html>", "playlist") }
    }

    @Test
    fun `a Deezer page of tracks is read, and its errors are explained`() {
        val page = PlaylistLinkImport.deezerJson(
            """{"data":[{"title":"Roja Kadhal","duration":312,"artist":{"name":"A. R. Rahman"},"album":{"title":"Roja"}}],"total":1}""",
        )
        val tracks = PlaylistLinkImport.parseDeezerTracks(page)
        assertEquals(listOf(PlaylistLinkImport.LinkTrack("Roja Kadhal", "A. R. Rahman", "Roja", 312_000)), tracks)
        val failure = assertFailsWith<PlaylistLinkImport.ImportException> {
            PlaylistLinkImport.deezerJson("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        }
        assertEquals("That Deezer playlist is private or doesn't exist.", failure.message)
    }
}
