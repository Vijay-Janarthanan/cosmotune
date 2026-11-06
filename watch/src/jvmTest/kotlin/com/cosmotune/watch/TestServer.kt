package com.cosmotune.watch

import com.cosmotune.watch.http.WatchHttp
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/** A loopback server answering by path prefix; every request is recorded. Nothing here reaches the network. */
class TestServer : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = LinkedHashMap<String, (RecordedRequest) -> MockResponse>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path ?: return MockResponse().setResponseCode(404)
                val route = routes.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length } ?: return MockResponse().setResponseCode(404)
                return route.value(request)
            }
        }
        server.start()
    }

    fun json(prefix: String, body: String) { routes[prefix] = { MockResponse().setHeader("Content-Type", "application/json").setBody(body) } }
    fun text(prefix: String, body: String) { routes[prefix] = { MockResponse().setBody(body) } }
    fun on(prefix: String, answer: (RecordedRequest) -> MockResponse) { routes[prefix] = answer }

    fun url(path: String = ""): String = server.url(path).toString().removeSuffix("/").let { if (path.isEmpty()) it else server.url(path).toString() }
    val base: String get() = server.url("/").toString().removeSuffix("/")
    fun http() = WatchHttp(OkHttpClient())
    fun paths() = requests.mapNotNull { it.path }

    override fun close() = server.shutdown()
}
