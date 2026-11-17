package com.cosmotune.desktop.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import okhttp3.Dns
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads an update package. GitHub serves releases from several addresses, and a network can fail to reach one of them; the
 * download tries each address in turn (OkHttp's routes), a few seconds each, so one unreachable address no longer fails it. The
 * earlier client connected to the first address only, and on a network where that one was unreachable every try failed the same way.
 */
internal object UpdateDownload {
    fun client(proxyUrl: String?, dns: Dns = Dns.SYSTEM, connectTimeoutMillis: Long = 8_000): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                dns(dns)
                retryOnConnectionFailure(true)
            }
            proxyUrl?.let { proxy = ProxyBuilder.http(Url(it)) }
        }
        install(HttpTimeout) {
            this.connectTimeoutMillis = connectTimeoutMillis
            socketTimeoutMillis = 60_000
        }
    }

    suspend fun fetch(http: HttpClient, url: String, into: File, onProgress: (Float) -> Unit) {
        try {
            http.prepareGet(url).execute { response ->
                check(response.status.isSuccess()) { "The download stopped (HTTP ${response.status.value}). Try again in a moment." }
                val total = response.contentLength()?.takeIf { it > 0 }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                into.outputStream().use { out ->
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) { if (channel.isClosedForRead) break else continue }
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(total?.let { (done.toFloat() / it).coerceIn(0f, 1f) } ?: -1f)
                    }
                }
                if (total != null) check(done == total) { "The connection dropped. Check your internet and try again." }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: IOException) {
            // Timeouts and refused connections carry the server's long address; the card shows a plain sentence instead.
            throw IllegalStateException("Couldn't reach the download server. Check your internet and try again.", failure)
        }
    }
}
