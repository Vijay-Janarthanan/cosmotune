package com.cosmotune.music.desktop

import com.cosmotune.podio.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Test
import kotlin.test.*

class PodioDownloadTest {
    @Test fun publisherEpisodeDownloadsDecodeOfflineAndNeverBecomeAnotherCatalogueRecording() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("podio-download-fixture").toFile()
        try {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                val bytes = requireNotNull(javaClass.getResourceAsStream("/provider-media/clear.m4a")).use { it.readBytes() }
                val requests = CopyOnWriteArrayList<String>()
                server.createContext("/episode") { exchange ->
                    requests += exchange.requestMethod
                    exchange.responseHeaders.add("Content-Type", "audio/mp4")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
                val episode = PodcastEpisode("publisher-fixture", "A public conversation", "Show", "http://127.0.0.1:${server.address.port}/episode").item()
                val saved = DesktopDownloadManager.download(episode.song(), "HIGH", directoryOverride = folder.toPath()).getOrThrow()
                assertEquals(episode.id, saved.videoId)
                assertTrue(requireNotNull(saved.localPath).endsWith(".m4a"))
                assertTrue(DesktopDownloadManager.isSaved(saved))
                val offline = assertNotNull(saved.offlinePodioItem()).song()
                assertEquals(saved.localPath, offline.localPath)
                val decoder = DesktopAudioDecoder()
                try {
                    decoder.open(requireNotNull(offline.localPath), requested = DesktopPcmFormat(44_100, 2, 4, true)).getOrThrow()
                    assertTrue(decoder.readSamples()?.isNotEmpty() == true)
                } finally { decoder.close() }
                assertTrue(requests.size >= 2)
                assertTrue(requests.all { it == "GET" })
            } finally { server.stop(0) }
        } finally { folder.deleteRecursively() }
    }
}
