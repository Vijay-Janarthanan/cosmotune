package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import org.junit.Test
import kotlin.test.*

class LibraryFirstTest {
    private val wanted = Song("yt:abc", "Ninaivo Oru Paravai", "Ilaiyaraaja", null, "4:32", albumName = "Sigappu Rojakkal", isExplicit = false)
    private fun file(id: String, title: String = wanted.title, artist: String = wanted.artist, duration: String = "4:31", album: String? = "Sigappu Rojakkal", explicit: Boolean? = false) =
        Song(id, title, artist, null, duration, albumName = album, isExplicit = explicit, localPath = "/music/$id.flac")
    private fun resolve(vararg library: Song, available: (Song) -> Boolean = { true }) = resolveLibraryFirst(listOf(wanted), library.toList(), available).single()

    @Test fun sameRecordingWinsThenOneUnambiguousFileKeepsTheRequestedIdentity() {
        val same = wanted.copy(localPath = "/music/downloaded.opus")
        assertEquals(LibraryChoice.SAME_RECORDING, resolve(same, file("local:1")).choice)
        val matched = resolve(file("local:1"))
        assertEquals(LibraryChoice.MATCHED_LOCAL_FILE, matched.choice)
        assertEquals("yt:abc", matched.play.videoId); assertEquals("/music/local:1.flac", matched.play.localPath)
    }

    @Test fun coversRemixesLiveTakesAndOtherEditionsStreamInstead() {
        assertEquals(LibraryChoice.ONLINE, resolve(file("cover", artist = "A Tribute Band")).choice)
        assertEquals(LibraryChoice.ONLINE, resolve(file("live", title = "Ninaivo Oru Paravai (Live)")).choice)
        assertEquals(LibraryChoice.ONLINE, resolve(file("remix", title = "Ninaivo Oru Paravai (Remix)")).choice)
        assertEquals(LibraryChoice.ONLINE, resolve(file("long", duration = "6:10")).choice)
        assertEquals(LibraryChoice.ONLINE, resolveLibraryFirst(listOf(wanted.copy(isExplicit = true)), listOf(file("clean", explicit = false))) { true }.single().choice)
        assertEquals(LibraryChoice.ONLINE, resolve().choice)
    }

    @Test fun conflictingReleasesAndUnavailableFilesAreReportedNotGuessed() {
        val unlabelled = wanted.copy(albumName = null)
        val two = resolveLibraryFirst(listOf(unlabelled), listOf(file("a", album = "Sigappu Rojakkal"), file("b", album = "Best of 1978"))) { true }.single()
        assertEquals(LibraryChoice.AMBIGUOUS, two.choice); assertNull(two.play.localPath)
        assertEquals(LibraryChoice.LOCAL_UNAVAILABLE, resolve(file("gone")) { false }.choice)
        assertEquals(LibraryChoice.LOCAL_UNAVAILABLE, resolve(wanted.copy(localPath = "/gone.flac")) { false }.choice)
        val summary = resolveLibraryFirst(listOf(wanted, wanted.copy(videoId = "yt:other", title = "Other song")), listOf(file("local:1"))) { true }.summary()
        assertEquals("1 from your library, 1 from their sources", summary)
    }
}
