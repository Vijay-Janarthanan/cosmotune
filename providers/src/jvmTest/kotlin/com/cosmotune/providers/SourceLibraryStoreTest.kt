package com.cosmotune.providers

import com.cosmotune.music.data.model.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class SourceLibraryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun song(id: String = "recording") = Song("src:sflx-fixture::$id", "ஒரு பாடல்", "Tyler, the Creator", null,
        durationText = "3:20", artists = listOf(ArtistRef("Tyler, the Creator", ProviderBrowseKey("fixture", "artist", "one").encode()), ArtistRef("AC/DC")),
        albumId = ProviderBrowseKey("fixture", "album", "album").encode(), albumName = "A release", sourceQuality = "LOSSLESS", sourceHiRes = true)
    private fun store(file: File) = SourceLibraryStore(file, UnsealedProviderFiles)

    @Test fun sourceRatingsRetainFullMetadataAcrossRestartWithoutAnAccount() = runBlocking<Unit> {
        val file = File(temporary.newFolder(), "library.bin")
        val first = store(file)
        first.rate(song(), LikeStatus.LIKE)
        val restored = store(file)
        assertEquals(LikeStatus.LIKE, restored.state.value.rating(song().videoId))
        val liked = restored.state.value.liked.single()
        assertEquals(song().artists, liked.artists); assertEquals("3:20", liked.durationText)
        assertEquals(song().albumId, liked.albumId); assertEquals("LOSSLESS", liked.sourceQuality); assertTrue(liked.sourceHiRes)
        restored.rate(liked, LikeStatus.DISLIKE)
        assertTrue(restored.state.value.liked.isEmpty())
        assertEquals(LikeStatus.DISLIKE, store(file).state.value.rating(liked.videoId))
        restored.rate(liked, LikeStatus.INDIFFERENT)
        assertTrue(store(file).state.value.ratings.isEmpty())
        assertFailsWith<IllegalArgumentException> { restored.rate(liked.copy(videoId = "a-youtube-id"), LikeStatus.LIKE) }
    }

    @Test fun savedPagesAndFollowsReopenWithSourceLinksAndNoRawCoverSecrets() = runBlocking<Unit> {
        val file = File(temporary.newFolder(), "library.bin")
        val first = store(file)
        val album = SourceCataloguePage(ProviderCollection(ProviderBrowseKey("fixture", "album", "album"), "A release", artwork = "https://example.com/cover?private-token"), listOf(song()))
        first.save(album, true)
        val artist = SourceCataloguePage(ProviderCollection(ProviderBrowseKey("fixture", "artist", "one"), "Tyler, the Creator", collections = listOf(album.collection)), listOf(song()))
        first.follow(artist, true)
        val reopened = store(file)
        assertEquals(song().artists, reopened.savedPage(album.collection.key.encode())?.songs?.single()?.artists)
        assertEquals(album.collection.key, reopened.savedPage(artist.collection.key.encode())?.collection?.collections?.single()?.key)
        assertEquals(1, reopened.state.value.followed.size)
        assertFalse(file.readText().contains("private-token"))
        assertNull(reopened.state.value.collections.single().artwork)
        reopened.save(album, false); reopened.follow(artist, false)
        assertTrue(store(file).state.value.collections.isEmpty()); assertTrue(store(file).state.value.followed.isEmpty())
    }

    @Test fun independentStoreWritersCannotLoseAnotherRating() = runBlocking<Unit> {
        val file = File(temporary.newFolder(), "library.bin")
        val one = store(file); val two = store(file)
        coroutineScope { (1..20).map { at -> launch(Dispatchers.IO) { (if (at % 2 == 0) one else two).rate(song("$at"), LikeStatus.LIKE) } }.joinAll() }
        assertEquals(20, store(file).state.value.liked.size)
    }

    @Test fun unreadableOrUnsupportedStateIsPreservedAndCannotBeReplacedByALike() = runBlocking<Unit> {
        for (original in listOf("damaged", """{"version":99}""")) {
            val file = File(temporary.newFolder(), "library.bin").apply { writeText(original) }
            val broken = store(file)
            assertNotNull(broken.recoveryMessage)
            assertFailsWith<IllegalStateException> { broken.rate(song(), LikeStatus.LIKE) }
            assertEquals(original, file.readText())
        }
    }
}
