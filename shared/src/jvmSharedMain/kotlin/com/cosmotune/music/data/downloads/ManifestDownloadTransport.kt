package com.cosmotune.music.data.downloads

import com.cosmotune.music.data.Http
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Bounded, cancellable raw manifest/segment IO shared by phone and desktop downloads. */
object ManifestDownloadTransport {
    const val MAX_SEGMENTS = 65_535
    const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    const val MAX_SEGMENT_BYTES = 32 * 1024 * 1024
    const val MAX_DOWNLOAD_BYTES = 4L * 1024 * 1024 * 1024

    suspend fun <T> run(source: String, headers: Map<String, String>, userAgent: String? = null,
        client: OkHttpClient = Http.client, block: (Session) -> T): T = supervisorScope {
        val parent = currentCoroutineContext()
        val active = AtomicReference<Call?>()
        val worker = async(Dispatchers.IO) {
            block(Session(source.toHttpUrl(), headers, userAgent,
                client.newBuilder().followRedirects(false).followSslRedirects(false).build(), active) { parent.ensureActive() })
        }
        try { worker.await() }
        finally { if (!parent.isActive) withContext(NonCancellable) { active.get()?.cancel(); worker.cancelAndJoin() } }
    }

    class Session internal constructor(private val source: HttpUrl, private val sourceHeaders: Map<String, String>,
        private val userAgent: String?, private val client: OkHttpClient, private val active: AtomicReference<Call?>,
        val checkCancelled: () -> Unit) {
        private var received = 0L
        private var requests = 0
        fun resolve(reference: String): String = requireNotNull(source.resolve(reference)) { "Invalid segment address" }.toString()
        fun read(target: String, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            copy(target, output, limit)
            return output.toByteArray()
        }
        fun copy(target: String, output: OutputStream, limit: Int = MAX_SEGMENT_BYTES): Long {
            require(limit in 1..MAX_SEGMENT_BYTES)
            require(++requests <= MAX_SEGMENTS + 2) { "Too many manifest requests" }
            var address = target.toHttpUrl()
            var headers = if (sameOrigin(source, address)) sourceHeaders.filterKeys {
                it.lowercase() !in setOf("range", "if-range", "accept-encoding", "host", "connection", "content-length")
            } else emptyMap()
            for (hop in 0..5) {
                checkCancelled()
                require(address.username.isEmpty() && address.password.isEmpty()) { "Segment address contains user credentials" }
                require(!source.isHttps || address.isHttps) { "Segments cannot downgrade HTTPS" }
                val request = Request.Builder().url(address).apply {
                    userAgent?.let { header("User-Agent", it) }
                    headers.forEach { (key, value) -> header(key, value) }
                    header("Accept-Encoding", "identity")
                }.build()
                val call = client.newCall(request)
                active.set(call)
                try {
                    checkCancelled()
                    call.execute().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            require(hop < 5) { "Too many segment redirects" }
                            val next = requireNotNull(address.resolve(response.header("Location").orEmpty())) { "Invalid segment redirect" }
                            require(!address.isHttps || next.isHttps) { "Segments cannot downgrade HTTPS" }
                            if (!sameOrigin(address, next)) headers = emptyMap()
                            address = next
                        } else {
                            check(response.code == 200) { "Segment failed (HTTP ${response.code})" }
                            require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) }) { "Unexpected segment encoding" }
                            val length = response.body.contentLength()
                            require(length <= limit) { "Segment is too large" }
                            var written = 0L
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    checkCancelled()
                                    val count = input.read(buffer); if (count < 0) break
                                    written += count; received += count
                                    require(written <= limit && received <= MAX_DOWNLOAD_BYTES) { "Manifest download is too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            check(length < 0 || written == length) { "Segment is incomplete" }
                            return written
                        }
                    }
                } finally { active.compareAndSet(call, null) }
            }
            error("Segment did not return audio")
        }
        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
