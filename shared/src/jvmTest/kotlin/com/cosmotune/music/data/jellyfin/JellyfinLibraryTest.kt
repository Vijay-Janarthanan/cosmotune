package com.cosmotune.music.data.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinLibraryTest {
    private val session = JellyfinSession("https://media.example.com", "u1", "tok")

    @Test fun addressesAreTidied() {
        assertEquals("https://media.example.com", JellyfinLibrary.normalizeUrl(" https://media.example.com/web/index.html "))
        assertEquals("http://192.168.1.4:8096/jf", JellyfinLibrary.normalizeUrl("192.168.1.4:8096/jf/"))
        assertNull(JellyfinLibrary.normalizeUrl("ftp://x"))
        assertNull(JellyfinLibrary.normalizeUrl(""))
    }

    @Test fun signInReadsTheTokenAndUser() {
        val s = JellyfinLibrary.parseSignIn("https://media.example.com", """{"User":{"Id":"abc","Name":"Me"},"AccessToken":"t0k"}""")
        assertEquals(JellyfinSession("https://media.example.com", "abc", "t0k"), s)
        assertNull(JellyfinLibrary.parseSignIn("https://media.example.com", """{"User":{}}"""))
        // The session survives being kept as one line, and a broken one reads as none.
        assertEquals(s, JellyfinSession.decode(s!!.encode()))
        assertNull(JellyfinSession.decode("nonsense"))
    }

    @Test fun songsCarryTheirAlbumArtistLengthAndCover() {
        val body = """{"Items":[
            {"Id":"s1","Name":"First","Album":"Record","AlbumId":"al1","AlbumArtist":"Band","Artists":["Singer"],"RunTimeTicks":2150000000,
             "AlbumPrimaryImageTag":"x","IndexNumber":3,"ProductionYear":2001},
            {"Id":"s2","Name":"","Artists":[],"RunTimeTicks":0},
            {"Name":"no id"}
        ],"TotalRecordCount":3}"""
        val songs = JellyfinLibrary.parseSongs(session, body)
        assertEquals(2, songs.size)
        val first = songs[0]
        assertEquals("jellyfin:s1", first.videoId)
        assertEquals("First", first.title)
        assertEquals("Singer", first.artist)
        assertEquals("Record", first.albumName)
        assertEquals("3:35", first.durationText)
        assertEquals("https://media.example.com/Items/al1/Images/Primary?maxHeight=512", first.thumbnailUrl)
        assertEquals("https://media.example.com/Audio/s1/stream?static=true&api_key=tok", first.localUri)
        assertEquals("Jellyfin", first.playbackSource)
        assertEquals("Unknown Artist", songs[1].artist)
        assertTrue(songs[1].title.isNotBlank())
        assertTrue(JellyfinLibrary.owns(first.videoId))
    }

    @Test fun theStreamFollowsTheSavedTokenNotTheOldOne() {
        assertEquals("https://media.example.com/Audio/s9/stream?static=true&api_key=tok", JellyfinLibrary.streamUrl(session, "jellyfin:s9"))
        assertNull(JellyfinLibrary.streamUrl(session, "webdav:x"))
    }
}
