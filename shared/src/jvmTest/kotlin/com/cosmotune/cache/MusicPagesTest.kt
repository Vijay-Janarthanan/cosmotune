package com.cosmotune.cache

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** Music's pages survive a restart through the app's cache folder, per listener, and a sign-out leaves nothing behind. */
class MusicPagesTest {
    private val folder = Files.createTempDirectory("music-pages").toFile()

    @BeforeTest fun disk() = PageCache.configure(FileCacheDisk(folder))
    @AfterTest fun noDisk() { PageCache.configure(null as CacheDisk?); folder.deleteRecursively() }

    private val song = Song("vid00000001", "Kannaana Kanne", "Sid Sriram", "https://i.ytimg.com/a.jpg", "4:12",
        artists = listOf(ArtistRef("Sid Sriram", "UCsid")), albumId = "MPREalbum", albumName = "Viswasam", isExplicit = false)
    private val shelves = listOf(
        HomeShelf("Quick picks", listOf(ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null, song))),
        HomeShelf("Albums for you", listOf(ShelfItem("Viswasam", "Album · D. Imman", null, null, "MPREalbum")), moreBrowseId = "FEmore", sourceId = "discovery:albums"),
    )

    private fun <T> eventually(read: suspend () -> T?): T? = runBlocking {
        repeat(100) { read()?.let { return@runBlocking it }; Thread.sleep(20) }
        null
    }

    @Test fun homeLibraryAndMoodsComeBackAsTheyWereSaved() {
        MusicPages.saveHome("acc:profile", shelves)
        assertEquals(shelves, eventually { MusicPages.savedHome("acc:profile") })
        assertNull(runBlocking { MusicPages.savedHome("someone:else") }, "another listener has their own Home")

        val library = LibraryPage(listOf(song), emptyList(), shelves.take(1), likedContinuation = "token")
        MusicPages.saveLibrary("acc:profile", library)
        assertEquals(library.copy(likedContinuation = null), eventually { MusicPages.savedLibrary("acc:profile") })

        val moods = listOf(MoodGenreSection("Moods", listOf(MoodGenre("Chill", "FEmusic_moods", "params", stripeColor = 0xFF3355AA))))
        MusicPages.saveExplore(moods)
        assertEquals(moods, eventually { MusicPages.savedExplore() })
    }

    @Test fun signingOutForgetsTheListenersPages() {
        MusicPages.saveHome("leaving", shelves)
        eventually { MusicPages.savedHome("leaving") }
        MusicPages.forget("leaving")
        val gone = runBlocking {
            repeat(100) { if (MusicPages.savedHome("leaving") == null) return@runBlocking true; Thread.sleep(20) }
            false
        }
        assertEquals(true, gone)
    }
}
