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
        assertEquals(PlaylistLinkImport.Service.AMAZON_MUSIC, PlaylistLinkImport.serviceOf("Listen: https://music.amazon.in/playlists/B0FG74YMDQ"))
        assertEquals(PlaylistLinkImport.Service.AMAZON_MUSIC, PlaylistLinkImport.serviceOf("https://music.amazon.co.uk/user-playlists/a0000000-0000-4000-8000-000000000000"))
        assertNull(PlaylistLinkImport.serviceOf("https://example.test/music.amazon.in/playlists/B0FG74YMDQ"))
        assertNull(PlaylistLinkImport.serviceOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(PlaylistLinkImport.serviceOf("hello"))
    }

    @Test
    fun `Amazon public pages keep the name and ordered song metadata`() {
        val playlist = PlaylistLinkImport.parseAmazonPage("""
            <music-detail-header label="playlist" headline="Tamil &amp; more" tertiary-text="3 SONGS"></music-detail-header>
            <music-image-row index="1" primary-text="First &quot;Song&quot;" secondary-text-1="Artist &amp; Singer"
                secondary-text-2="First album" duration="04:12"></music-image-row>
            <music-image-row index="2" primary-text="Second song" secondary-text-1="Another singer" duration="02:05"></music-image-row>
            <music-image-row index="3" primary-text="Suggested album"></music-image-row>
        """.trimIndent())
        assertEquals("Tamil & more", playlist.name)
        assertEquals(3, playlist.declaredCount)
        assertEquals(listOf(
            PlaylistLinkImport.LinkTrack("First \"Song\"", "Artist & Singer", "First album", 252_000),
            PlaylistLinkImport.LinkTrack("Second song", "Another singer", durationMs = 125_000),
        ), playlist.tracks)
        assertFailsWith<PlaylistLinkImport.ImportException> { PlaylistLinkImport.parseAmazonPage("<html></html>") }
    }

    @Test
    fun `a share picks the supported playlist in surrounding text and rejects unrelated links`() {
        val spotify = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(spotify, SharedPlaylistLink.fromText("Cover https://example.test/photo. Try ($spotify)."))
        assertEquals("https://spotify.link/example", SharedPlaylistLink.fromText("Listen https://spotify.link/example"))
        assertEquals("https://music.youtube.com/playlist?list=PL_test_12345", SharedPlaylistLink.fromText("https://music.youtube.com/playlist?list=PL_test_12345"))
        assertNull(SharedPlaylistLink.fromText("https://music.youtube.com/watch?v=abcdefghijk&list=PL_test_12345"))
        assertNull(SharedPlaylistLink.fromText("https://open.spotify.com.example.test/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(SharedPlaylistLink.fromText("https://other@music.amazon.in/playlists/B0FG74YMDQ"))
        val wrapped = java.net.URLEncoder.encode(spotify, "UTF-8")
        assertEquals(spotify, SharedPlaylistLink.fromOpenUri("cosmotune://import/playlist?url=$wrapped"))
        assertNull(SharedPlaylistLink.fromOpenUri("cosmotune://party/playlist?url=$wrapped"))
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
