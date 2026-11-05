package com.cosmotune.music.desktop

import com.cosmotune.music.data.downloads.ValidatedMusicTransfer
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Cancellation closes blocked reads before the queue can retry or reuse the destination. */
internal object DesktopAudioFileTransfer {
    suspend fun save(url: String, headers: Map<String, String>, target: File, userAgent: String,
        client: OkHttpClient = DesktopDownloadHttp.client,
        progress: (Long, Long?) -> Unit = { _, _ -> }): File = supervisorScope {
        val parent = currentCoroutineContext()
        val active = AtomicReference<Call?>()
        val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val worker = async(Dispatchers.IO) {
            ValidatedMusicTransfer(exchange = { range, read ->
                var address = url.toHttpUrl()
                var credentials = headers.filterKeys { key -> key.lowercase() !in setOf("range", "if-range", "accept-encoding", "host", "connection", "content-length") }
                var complete = false
                for (hop in 0..5) {
                    parent.ensureActive()
                    require(address.username.isEmpty() && address.password.isEmpty()) { "Download address contains user credentials" }
                    val request = DesktopDownloadHttp.request(address.toString(), userAgent, credentials + range)
                    val call = transport.newCall(request)
                    active.set(call)
                    try {
                        parent.ensureActive()
                        call.execute().use { response ->
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                require(hop < 5) { "Too many download redirects" }
                                val next = requireNotNull(address.resolve(response.header("Location").orEmpty())) { "Invalid download redirect" }
                                require(!address.isHttps || next.isHttps) { "Download redirect cannot downgrade HTTPS" }
                                if (address.scheme != next.scheme || address.host != next.host || address.port != next.port) credentials = emptyMap()
                                address = next
                            } else { read(response); complete = true }
                        }
                    } finally { active.compareAndSet(call, null) }
                    if (complete) break
                }
                check(complete) { "Download did not return audio" }
            }, checkCancelled = { parent.ensureActive() }, maxBytes = 4L * 1024 * 1024 * 1024).download(url, target, progress)
        }
        try { worker.await() }
        finally {
            if (!parent.isActive) withContext(NonCancellable) { active.get()?.cancel(); worker.cancelAndJoin() }
        }
    }
}
