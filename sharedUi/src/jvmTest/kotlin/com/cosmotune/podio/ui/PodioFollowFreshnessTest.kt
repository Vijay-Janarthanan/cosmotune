package com.cosmotune.podio.ui

import androidx.compose.runtime.mutableStateOf
import com.cosmotune.podio.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.*
import kotlin.test.*

class PodioFollowFreshnessTest {
    @Test fun followingAFeedRefreshesAnOtherwiseFreshCachedInbox() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("podio-follow-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var title = "Old episode"
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/feed") { exchange ->
            requests.incrementAndGet()
            val bytes = """<rss version="2.0"><channel><title>Show</title><item><guid>$title</guid><title>$title</title><enclosure url="https://publisher.example/audio.mp3" type="audio/mpeg"/></item></channel></rss>""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val show = PodcastShow("show", "Show", "Publisher", "http://127.0.0.1:${server.address.port}/feed")
            val library = PodioLibrary(java.io.File(directory, "library.json"))
            val model = PodioModel(PodioCatalogue(), library, this,
                PodioHost({ _, _ -> }, null, null, null, null, null, false, {}),
                mutableStateOf(""), mutableStateOf(""), mutableStateOf(""), mutableStateOf(""),
                mutableStateOf(false), mutableStateOf(false), mutableStateOf(false), mutableStateOf(false))
            model.loadInbox(listOf(show), force = false)
            assertEquals("Old episode", model.inbox.single().episode.title)
            title = "Fresh episode"
            model.toggleFollow(show)
            withTimeout(5000) { while (model.inbox.singleOrNull()?.episode?.title != "Fresh episode") delay(10) }
            assertEquals(listOf(show), library.state.value.shows)
            assertEquals(2, requests.get())
        } finally { server.stop(0); directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete() }
    }
}
