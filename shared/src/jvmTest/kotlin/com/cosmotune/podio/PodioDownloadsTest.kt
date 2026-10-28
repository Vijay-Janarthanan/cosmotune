package com.cosmotune.podio

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class PodioDownloadsTest {
    @Test fun enclosureFormatsUseAudioTypesAndDoNotDownloadAWebPageAsAnMp3() {
        assertEquals("mp3", PodioDownloads.format("https://cdn.example/redirect", "audio/mpeg; charset=binary")?.extension)
        assertEquals("m4a", PodioDownloads.format("https://cdn.example/episode.mp3", "audio/mp4")?.extension)
        assertEquals("mp3", PodioDownloads.format("https://cdn.example/episode.mp3?token=public", "application/octet-stream")?.extension)
        assertNull(PodioDownloads.format("https://cdn.example/episode.mp3", "text/html"))
        assertNull(PodioDownloads.format("https://cdn.example/unknown", "application/octet-stream"))
        assertFalse(PodioDownloads.isEpisode("podio:radio:live"))
        assertTrue(PodioDownloads.isEpisode("podio:episode:one"))
    }

    @Test fun aPublisherCanBeInspectedWithABoundedCancellableRangeRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Type", "audio/mpeg").setBody("x"))
            assertEquals("mp3", PodioDownloads.inspect(server.url("/episode").toString()).extension)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("bytes=0-65535", request.getHeader("Range"))
        }
    }

    @Test fun receivedContainerSignaturesOverrideAMislabeledTypeAndRejectHtml() {
        val mp4 = byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray() + byteArrayOf(0, 0, 0, 0)
        assertEquals("m4a", PodioDownloads.format("https://cdn.example/episode.mp3", "audio/mpeg", mp4)?.extension)
        assertNull(PodioDownloads.format("https://cdn.example/episode.mp3", "audio/mpeg", "<html>Verification page</html>".toByteArray()))
    }

    @Test fun offlineRowsKeepEpisodeIdentityAndResumeInsteadOfResolvingAnotherSong() {
        val song = Song("podio:episode:youtube:abcdefghijk", "A conversation", "Show", null, durationText = "12:34",
            localUri = "file:///podcasts/conversation.m4a", localPath = "/podcasts/conversation.m4a")
        val item = assertNotNull(song.offlinePodioItem())
        val restored = item.song()
        assertEquals(song.videoId, restored.videoId)
        assertEquals(song.localUri, restored.localUri)
        assertEquals(song.localPath, restored.localPath)
        assertTrue(restored.isPodio)
        assertEquals(754, item.durationSeconds)
        assertNull(song.copy(videoId = "music").offlinePodioItem())
        assertNull(song.copy(localUri = "https://publisher.example/audio.mp3").offlinePodioItem())
        val publisher = song.copy(videoId = "podio:episode:publisher", localUri = "https://publisher.example/audio.mp3", localPath = null)
        val saved = publisher.copy(localUri = java.io.File("offline-episode.m4a").absoluteFile.toURI().toString(), localPath = java.io.File("offline-episode.m4a").absolutePath)
        val preferred = PodioDownloads.withOfflinePath(publisher, saved)
        assertEquals(publisher.localUri, preferred.localUri, "Keep the publisher URL available if the file has been moved")
        assertEquals(saved.localPath, preferred.localPath)
        assertEquals(publisher, PodioDownloads.withOfflinePath(publisher, saved.copy(videoId = "another-episode")))
    }

    @Test fun nativeOpmlFilesAreBoundedAndYouTubeBookmarksAreNotFakeRss() {
        val show = PodcastShow("youtube:MPSPPLpublic_show", "A public show", "Host", "https://music.youtube.com/browse/MPSPPLpublic_show")
        val xml = PodioOpml.encode(listOf(show))
        assertTrue(xml.contains("type=\"link\""))
        assertFalse(xml.contains("xmlUrl="))
        assertEquals(show.id, PodioOpml.decode(PodioOpml.read(xml.byteInputStream())).single().id)
        assertFails { PodioOpml.read(ByteArray(1024 * 1024 + 1).inputStream()) }
    }
}
