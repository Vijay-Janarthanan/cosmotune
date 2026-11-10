package com.cosmotune.watch.plugins.stremio

import com.cosmotune.watch.WatchPlaybackLease
import com.cosmotune.watch.http.str
import com.cosmotune.watch.model.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import kotlin.concurrent.thread

internal class BundledTorrentPlayback(private val executable: () -> File?) {
    suspend fun open(stream: Stream): WatchPlaybackLease {
        val hash = stream.url.removePrefix(MAGNET_PREFIX)
        require(HASH.matches(hash))
        // Keep a reference outside withContext so cancellation during its return also closes the child.
        var child: Process? = null
        try {
            val result = CompletableDeferred<String>()
            withContext(Dispatchers.IO) {
                val binary = executable()?.takeIf(File::isFile)
                    ?: error("The torrent engine is missing from this Cosmotune build.")
                val process = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
                child = process
                thread(name = "Cosmotune torrent output", isDaemon = true) {
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (!result.isCompleted && line.startsWith("{")) result.complete(line)
                            }
                        }
                    }
                    result.complete("")
                }
                val request = buildJsonObject {
                    put("Hash", hash)
                    stream.torrentFileIndex?.let { put("Index", it) }
                    stream.filename?.take(512)?.let { put("Filename", it) }
                    put("Trackers", buildJsonArray { stream.torrentTrackers.take(8).forEach { add(it.take(256)) } })
                }
                process.outputStream.write("$request\n".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            val response = withTimeoutOrNull(42_000) { result.await() }
                ?: error("The torrent did not start in time. Try another copy.")
            val message = response.takeIf(String::isNotBlank)?.let(Json::parseToJsonElement)
                ?: error("The built-in torrent engine stopped unexpectedly.")
            val url = message.str("url")?.toHttpUrlOrNull()?.takeIf {
                it.scheme == "http" && it.host == "127.0.0.1" && it.username.isEmpty() &&
                    it.password.isEmpty() && it.encodedPath.matches(ROUTE) && it.query == null
            } ?: error(message.str("error") ?: "The torrent engine did not return a playable video.")
            val process = checkNotNull(child)
            return WatchPlaybackLease(url.toString()) { stop(process) }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { child?.let(::stop) }
            throw error
        }
    }

    private fun stop(process: Process) {
        process.destroy()
    }

    companion object {
        private val HASH = Regex("[a-fA-F0-9]{40}")
        private val ROUTE = Regex("/[a-f0-9]{48}/video\\.[a-z0-9]+")
        private const val MAGNET_PREFIX = "magnet:?xt=urn:btih:"
        const val DISABLED = "An add-on found a torrent. Enable Torrent playback in Plugins to use Cosmotune’s built-in engine."

        fun magnet(hash: String?): String? = hash?.takeIf(HASH::matches)?.lowercase()?.let { MAGNET_PREFIX + it }
    }
}
