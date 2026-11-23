package com.cosmotune.reading.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A finished mark and when it was set or taken off, so two devices settle on the later one. */
@Serializable data class ReadingFlag(val on: Boolean, val atMs: Long)

/**
 * What travels between devices for books: where each book is, its bookmarks, highlights and notes, and its Finished mark, keyed by
 * the book's identity (the SHA-256 of its file, the same on every device), with removed marks kept as [removed] so they stay removed.
 */
@Serializable data class ReadingSyncDocument(val version: Int = 1, val progress: Map<String, ReadingProgress> = emptyMap(),
    val bookmarks: Map<String, ReadingBookmark> = emptyMap(), val highlights: Map<String, ReadingHighlight> = emptyMap(),
    val finished: Map<String, ReadingFlag> = emptyMap(), val removed: Map<String, Long> = emptyMap())

/** The reader's own WebDAV folder (Nextcloud, ownCloud, a NAS, Koofr…) that books sync through. */
@Serializable data class ReadingWebDav(val url: String, val username: String = "", val password: String = "",
    val lastSyncMs: Long = 0, val lastResult: String = "")

internal object ReadingSyncMerge {
    private const val KEEP_REMOVED_MS = 180L * 24 * 60 * 60 * 1000

    fun of(state: ReadingState): ReadingSyncDocument = ReadingSyncDocument(
        progress = state.progress,
        bookmarks = state.bookmarks.associateBy { it.id },
        highlights = state.highlights.associateBy { it.id },
        finished = state.books.associate { book -> book.id to ReadingFlag(book.id in state.finished, state.finishedAt[book.id] ?: 0L) }
            .filterValues { it.on || it.atMs > 0 },
        removed = state.removed,
    )

    /** [a] and [b] as one: the later of each position, mark and flag; anything removed on either side stays removed. */
    fun merge(a: ReadingSyncDocument, b: ReadingSyncDocument, nowMs: Long = System.currentTimeMillis()): ReadingSyncDocument {
        val removed = (a.removed.keys + b.removed.keys).associateWith { maxOf(a.removed[it] ?: 0L, b.removed[it] ?: 0L) }
            .filterValues { nowMs - it < KEEP_REMOVED_MS }
        fun <T> later(x: Map<String, T>, y: Map<String, T>, time: (T) -> Long) = (x.keys + y.keys).associateWith { key ->
            val left = x[key]; val right = y[key]
            if (left == null) right!! else if (right == null) left else if (time(right) > time(left)) right else left
        }
        return ReadingSyncDocument(
            progress = later(a.progress, b.progress) { it.updatedAtMs },
            bookmarks = later(a.bookmarks, b.bookmarks) { it.createdAtMs }.filter { (id, mark) -> (removed[id] ?: -1L) < mark.createdAtMs },
            highlights = later(a.highlights, b.highlights) { it.updatedAtMs }.filter { (id, mark) -> (removed[id] ?: -1L) < mark.updatedAtMs },
            finished = later(a.finished, b.finished) { it.atMs },
            removed = removed,
        )
    }

    /** [state] with [document] laid over it, for the books on this device. */
    fun apply(state: ReadingState, document: ReadingSyncDocument): ReadingState {
        val ids = state.books.map { it.id }.toSet()
        val progress = state.progress + document.progress.filter { (id, remote) -> id in ids && remote.updatedAtMs > (state.progress[id]?.updatedAtMs ?: -1L) }
        val flags = document.finished.filterKeys { it in ids }
        val finished = (state.finished.filterNot { id -> flags[id]?.let { !it.on && it.atMs >= (state.finishedAt[id] ?: 0L) } == true } +
            flags.filter { (id, flag) -> flag.on && flag.atMs >= (state.finishedAt[id] ?: 0L) }.keys).toSet()
        return state.copy(
            progress = progress,
            bookmarks = document.bookmarks.values.filter { it.bookId in ids }.sortedBy { it.createdAtMs },
            highlights = document.highlights.values.filter { it.bookId in ids }.sortedBy { it.createdAtMs },
            finished = finished,
            finishedAt = state.finishedAt + flags.mapValues { (id, flag) -> maxOf(flag.atMs, state.finishedAt[id] ?: 0L) },
            removed = document.removed,
        )
    }
}

/** Reads and writes the sync file in the reader's WebDAV folder, only when the folder's version is still the one read. */
internal class ReadingWebDavClient(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val FILE = "cosmotune-reading.json"
        /** The folder's address, checked: HTTPS, or HTTP only on this network (a NAS at home). */
        fun folder(address: String): HttpUrl {
            val url = runCatching { address.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrl() }.getOrNull()
                ?: throw IllegalArgumentException("Enter the folder's full address, starting with https://")
            val local = url.host == "localhost" || url.host.endsWith(".local") || Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|127\.)""").containsMatchIn(url.host)
            require(url.isHttps || local) { "Use an https:// address (plain http only works for a server on this network)." }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Put the user name and password in their own fields." }
            return url
        }
    }

    private fun Request.Builder.auth(config: ReadingWebDav) = apply {
        if (config.username.isNotEmpty()) header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
    }

    /** The folder's sync file and its version tag; an empty document when there is none yet. */
    suspend fun read(config: ReadingWebDav): Pair<ReadingSyncDocument, String?> = withContext(Dispatchers.IO) {
        val url = folder(config.url).newBuilder().addPathSegment(FILE).build()
        http.newCall(Request.Builder().url(url).auth(config).build()).awaitReading { response ->
            when {
                response.code == 404 -> ReadingSyncDocument() to null
                response.code == 401 || response.code == 403 -> throw IllegalStateException("The server did not accept the user name and password.")
                !response.isSuccessful -> throw IllegalStateException("The server answered ${response.code}.")
                else -> json.decodeFromString<ReadingSyncDocument>(response.catalogueText(32 * 1024 * 1024)) to response.header("ETag")
            }
        }
    }

    /** Writes [document]; false when another device wrote first (read again and merge). */
    suspend fun write(config: ReadingWebDav, document: ReadingSyncDocument, tag: String?): Boolean = withContext(Dispatchers.IO) {
        val url = folder(config.url).newBuilder().addPathSegment(FILE).build()
        val body = json.encodeToString(document).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).auth(config).put(body).apply {
            if (tag != null) header("If-Match", tag) else header("If-None-Match", "*")
        }.build()
        http.newCall(request).awaitReading { response ->
            when {
                response.code == 412 -> false
                response.code == 401 || response.code == 403 -> throw IllegalStateException("The server did not accept the user name and password.")
                response.code == 409 -> throw IllegalStateException("That folder does not exist on the server.")
                !response.isSuccessful -> throw IllegalStateException("The server answered ${response.code}.")
                else -> true
            }
        }
    }
}
