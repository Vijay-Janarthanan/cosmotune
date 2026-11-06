package com.cosmotune.watch

import com.cosmotune.watch.model.Container
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.WatchKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okio.Buffer

class WatchDownloadsTest {
    private val source = StreamSource("ia", "Internet Archive", "IA")
    private val film = PlayTarget.Film(WatchKey.tmdbFilm(7), ExternalIds(tmdb = 7), "Night of the Living Dead", 1968)

    @Test fun onlyDirectFilesAreOffered() {
        val dir = Files.createTempDirectory("watch-dl").toFile()
        try {
            val downloads = WatchDownloads(dir, OkHttpClient())
            val file = Stream(source, "720p", url = "https://archive.org/download/x/film.mp4", protocol = StreamProtocol.PROGRESSIVE)
            assertTrue(downloads.canDownload(file))
            assertEquals(Container.MKV, WatchDownloads.containerOf(file.copy(url = "https://s/x.mkv?api_key=1")))
            assertNotNull(downloads.whyNot(file.copy(protocol = StreamProtocol.HLS, url = "https://s/x.m3u8")))
            assertNotNull(downloads.whyNot(file.copy(isLive = true)))
            assertNotNull(downloads.whyNot(file.copy(url = "https://s/film.avi")))
            assertNull(downloads.start(PlayTarget.Film(WatchKey.tmdbFilm(8), ExternalIds(), "x"), null, file.copy(protocol = StreamProtocol.DASH)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aFileIsKeptAndTheListSurvivesWithoutItsAddress() = runBlocking {
        val dir = Files.createTempDirectory("watch-dl").toFile()
        TestServer().use { server ->
            val body = ByteArray(5_000) { (it % 251).toByte() }
            server.on("/film.mp4") { MockResponse().setBody(Buffer().write(body)) }
            try {
                val downloads = WatchDownloads(dir, OkHttpClient())
                val stream = Stream(source, "720p", url = server.url("/film.mp4"), protocol = StreamProtocol.PROGRESSIVE, sizeBytes = body.size.toLong())
                val started = assertNotNull(downloads.start(film, null, stream, "Internet Archive · 720p"))
                val done = withTimeout(10_000) { downloads.items.first { list -> list.any { it.id == started.id && it.status == WatchDownload.Status.DONE } } }.first()
                val file = downloads.fileOf(done)
                assertTrue(file.readBytes().contentEquals(body))
                assertEquals(StreamProtocol.LOCAL_FILE, downloads.streamOf(done).protocol)
                // The list is written just after the state says done; wait for that write.
                var text = ""
                withTimeout(5_000) {
                    while ("DONE" !in text) { text = runCatching { dir.resolve("downloads.json").readText() }.getOrDefault(""); kotlinx.coroutines.delay(20) }
                }
                assertFalse(server.url("/film.mp4") in text, "the address is never written down")
                val again = WatchDownloads(dir, OkHttpClient())
                assertEquals(done.id, again.doneFor(film.key)?.id)
                again.remove(done.id)
                assertFalse(file.exists())
                assertTrue(again.items.value.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }
}
