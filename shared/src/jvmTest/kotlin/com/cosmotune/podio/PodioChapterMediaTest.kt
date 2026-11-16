package com.cosmotune.podio

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class PodioChapterMediaTest {
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private fun song(id: String) = Song("podio:episode:$id", "Episode", "Show", "https://publisher.example/cover.jpg",
        podcastChaptersUrl = "https://publisher.example/$id.json")

    @Test fun chapterMetadataLoadsOnceAndFailedImagesRestoreTheEpisodeCoverWithoutRepeatingRequests() = runBlocking {
        val folder = kotlin.io.path.createTempDirectory("podio-chapter-").toFile()
        var metadata = 0; var images = 0
        val failed = CompletableDeferred<Unit>()
        try { PodioChapterMedia(folder, fetchChapters = { metadata++; listOf(PodcastChapter(0, "Intro", "https://publisher.example/intro.png"),
            PodcastChapter(1000, "Next", "https://publisher.example/broken.png")) }, fetchImage = { url ->
            images++; if (url.endsWith("broken.png")) { failed.complete(Unit); null } else png
        }).use { owner ->
            owner.observe(song("one"), 0)
            val first = withTimeout(5000) { owner.state.first { it != null } }!!
            assertEquals("Intro", first.chapter.title); assertTrue(first.artworkUri.startsWith("file:"))
            repeat(5) { owner.observe(song("one"), 500) }
            owner.observe(song("one"), 1500)
            withTimeout(5000) { failed.await() }
            assertNull(owner.state.value)
            repeat(5) { owner.observe(song("one"), 1600) }
            assertEquals(1, metadata); assertEquals(2, images)
            owner.observe(song("one"), 0)
            withTimeout(5000) { owner.state.first { it != null } }
            assertEquals(2, images, "seeking back reuses the cached chapter picture")
            owner.observe(Song("music", "Song", "Artist", null), 0)
            assertNull(owner.state.value, "chapter artwork never escapes Podio ownership")
        } } finally { folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }

    @Test fun lateArtworkFromAnOldEpisodeCannotReplaceTheCurrentEpisode() = runBlocking {
        val folder = kotlin.io.path.createTempDirectory("podio-chapter-race-").toFile()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try { PodioChapterMedia(folder, fetchChapters = { url -> listOf(PodcastChapter(0, url, url.replace(".json", ".png"))) }, fetchImage = { url ->
            if (url.endsWith("old.png")) withContext(NonCancellable) { started.complete(Unit); release.await() }
            png
        }).use { owner ->
            owner.observe(song("old"), 0); withTimeout(5000) { started.await() }
            owner.observe(song("new"), 0)
            withTimeout(5000) { owner.state.first { it?.episodeId == "podio:episode:new" } }
            release.complete(Unit)
            delay(50)
            assertEquals("podio:episode:new", owner.state.value?.episodeId)
        } } finally { release.complete(Unit); folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }
}
