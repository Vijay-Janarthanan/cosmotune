package com.cosmotune.listening

import com.cosmotune.music.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The song playing, as other apps are told of it: Tasker, an OBS text source, a home server's webhook. */
data class NowPlayingEvent(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val playing: Boolean,
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
) {
    /** A link others can open: the song on YouTube Music, or nothing for a local file. */
    val link: String? get() = id.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }?.let { "https://music.youtube.com/watch?v=$it" }
}

/**
 * Now playing for other apps (the idea from Namida's webhook and broadcast, and th-ch's now-playing file for OBS; see NOTICE.md):
 * on each song change and each play or pause, a webhook of the listener's own gets the song as JSON, and on the desktop a text
 * file holds "Artist – Title" for streaming software to show. The Android broadcast is sent by the playback service. Nothing
 * leaves the device unless the listener sets it up; a failing webhook is ignored.
 */
object NowPlayingOut {
    // One at a time, in the order they were published: a pause sent just after a song starts must not reach the webhook or the
    // file before it.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var last: NowPlayingEvent? = null

    /** Swapped by tests: sends [body] to [url]. */
    internal var post: (String, String) -> Unit = ::send

    /** Only an http or https address with a host counts as a webhook. */
    fun validWebhook(url: String): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    fun json(event: NowPlayingEvent): String = buildJsonObject {
        put("app", "Cosmotune")
        put("state", if (event.playing) "playing" else "paused")
        put("id", event.id)
        put("title", event.title)
        put("artist", event.artist)
        event.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
        if (event.durationMs > 0) put("duration_ms", event.durationMs)
        event.artworkUrl?.takeIf { it.startsWith("http") }?.let { put("artwork", it) }
        event.link?.let { put("link", it) }
    }.toString()

    /** What the now-playing file says: "Artist – Title" while a song plays, empty while paused or stopped. */
    fun fileText(event: NowPlayingEvent?): String {
        if (event == null || !event.playing || event.title.isBlank()) return ""
        return if (event.artist.isBlank()) event.title else "${event.artist} – ${event.title}"
    }

    /**
     * Tells the webhook (when [webhook] is set) and writes [file] (when given) if [event] differs from the last one by song or by
     * playing. Returns whether anything was sent. Cheap on the calling thread.
     */
    fun publish(event: NowPlayingEvent?, webhook: String, file: File?): Boolean {
        val previous = last
        if (event?.id == previous?.id && event?.playing == previous?.playing) return false
        last = event
        if (file != null) scope.launch { runCatching { writeAtomically(file, fileText(event)) } }
        if (event != null && validWebhook(webhook)) {
            val body = json(event)
            scope.launch { runCatching { post(webhook.trim(), body) } }
        }
        return true
    }

    /** Sends a sample to [webhook] now, for Settings' Test; true when it answered with success. */
    suspend fun test(webhook: String): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!validWebhook(webhook)) return@withContext false
        runCatching {
            post(webhook.trim(), json(NowPlayingEvent("test", "Test from Cosmotune", "Cosmotune", playing = true)))
            true
        }.getOrDefault(false)
    }

    /** Forgets what was last sent, so the next [publish] goes out even for the same song (a setting changed). */
    fun reset() { last = null }

    private fun writeAtomically(file: File, text: String) {
        val parent = file.absoluteFile.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        val temp = File.createTempFile("now-playing-", ".tmp", parent)
        try {
            temp.writeText(text, Charsets.UTF_8)
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }

    private fun send(url: String, body: String) {
        val request = Request.Builder().url(url).header("User-Agent", "Cosmotune").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        Http.client.newCall(request).execute().use { response -> check(response.isSuccessful) { "HTTP ${response.code}" } }
    }
}
