package com.cosmotune.music.data.musicbrainz

import com.cosmotune.listening.LocalTagEditor
import com.cosmotune.listening.TrackIdentifiers
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** What MusicBrainz knows about a recording: when it first came out, its ISRCs and its genres; and how it was found. */
data class RecordingFacts(
    val recordingId: String,
    val firstRelease: String?,
    val isrcs: List<String>,
    val genres: List<String>,
    /** True when the song file's own tags named the recording, so this is surely the song. */
    val fromTags: Boolean,
)

/**
 * Song info's MusicBrainz part (the idea from Kreate, see NOTICE.md): the song's first release, ISRC and genres from the
 * MusicBrainz web service (keyless, one request a second). A local file's own ids ([TrackIdentifiers], read from its tags) are
 * used first: the recording id is looked up directly and an ISRC through MusicBrainz's ISRC index. Without them the recording is
 * searched by title and artist, and only a close match on both (and on the length, when known) is taken.
 */
object RecordingFactsLookup {
    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val AGENT = "${com.cosmotune.CosmoConfig.APP_NAME} (${com.cosmotune.CosmoConfig.PROJECT_URL})"
    private const val INC = "isrcs+genres+tags"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Result<RecordingFacts?>>()
    private val pace = Mutex()
    private var lastAsked = 0L

    /** Swapped by tests: a GET that answers the body or null. */
    internal var fetch: suspend (String) -> String? = ::get

    suspend fun forSong(song: Song): RecordingFacts? {
        val key = song.videoId.ifBlank { "${song.title}|${song.artist}" }
        cache[key]?.getOrNull()?.let { return it }
        val found = try {
            withContext(Dispatchers.IO) {
                val ids = song.localPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.canRead() }
                    ?.let(LocalTagEditor::identifiers) ?: TrackIdentifiers()
                lookup(song.title, song.artist, durationMs(song.durationText), ids)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        cache[key] = Result.success(found)
        return found
    }

    /** The facts for a recording named by [ids] when the file had them, else the best search hit for [title] and [artist]. */
    internal suspend fun lookup(title: String, artist: String, lengthMs: Long?, ids: TrackIdentifiers): RecordingFacts? {
        ids.recordingId?.let { id -> recording(id)?.let { return it.copy(fromTags = true) } }
        ids.isrc?.let { code ->
            val body = ask("$BASE/isrc/$code?fmt=json")
            val id = body?.let(::parseIsrc)?.let { hits -> pick(hits, title, artist, lengthMs, strict = false) }
            if (id != null) recording(id)?.let { return it.copy(fromTags = true) }
        }
        if (title.isBlank() || artist.isBlank()) return null
        val query = "recording:\"${clean(title).escaped()}\" AND artist:\"${artist.escaped()}\""
        val url = "$BASE/recording".toHttpUrl().newBuilder().addQueryParameter("query", query).addQueryParameter("limit", "8")
            .addQueryParameter("fmt", "json").build().toString()
        val hits = ask(url)?.let(::parseSearch).orEmpty()
        val id = pick(hits, title, artist, lengthMs, strict = true) ?: return null
        return recording(id)
    }

    private suspend fun recording(id: String): RecordingFacts? =
        ask("$BASE/recording/$id?inc=$INC&fmt=json")?.let(::parseRecording)

    /** One hit of a search or the ISRC index. */
    internal data class Hit(val id: String, val title: String, val artists: List<String>, val lengthMs: Long?, val score: Int)

    internal fun parseRecording(body: String): RecordingFacts? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val id = root.text("id") ?: return null
        val genres = (root["genres"] as? JsonArray).orEmpty().names().ifEmpty {
            // Few recordings carry genres of their own; their most-voted tags say the same thing.
            (root["tags"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
                .sortedByDescending { it["count"]?.jsonPrimitive?.intOrNull ?: 0 }.mapNotNull { it.text("name") }
        }.take(5)
        val isrcs = (root["isrcs"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return RecordingFacts(id, root.text("first-release-date"), isrcs.distinct(), genres.distinct().map { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }, fromTags = false)
    }

    internal fun parseSearch(body: String): List<Hit> =
        ((json.parseToJsonElement(body) as? JsonObject)?.get("recordings") as? JsonArray).orEmpty().mapNotNull(::hit)

    internal fun parseIsrc(body: String): List<Hit> =
        ((json.parseToJsonElement(body) as? JsonObject)?.get("recordings") as? JsonArray).orEmpty().mapNotNull(::hit)

    private fun hit(element: kotlinx.serialization.json.JsonElement): Hit? {
        val o = element as? JsonObject ?: return null
        val credits = (o["artist-credit"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }
        return Hit(o.text("id") ?: return null, o.text("title").orEmpty(), credits, o["length"]?.jsonPrimitive?.longOrNull,
            o["score"]?.jsonPrimitive?.intOrNull ?: 100)
    }

    /**
     * The hit that is this song. [strict] (a search by name): a score of 85 or more, the same title once both are tidied, one of
     * the credited artists named in [artist], and a length within 8 s when both are known. Not strict (the file's ISRC already names
     * the recording): the first hit whose title matches, else the first.
     */
    internal fun pick(hits: List<Hit>, title: String, artist: String, lengthMs: Long?, strict: Boolean): String? {
        val wanted = clean(title).folded()
        val by = artist.folded()
        fun titled(hit: Hit) = hit.title.folded().let { it == wanted || (wanted.length >= 4 && (it.startsWith(wanted) || wanted.startsWith(it))) }
        if (!strict) return (hits.firstOrNull(::titled) ?: hits.firstOrNull())?.id
        return hits.filter { hit ->
            hit.score >= 85 && titled(hit) &&
                hit.artists.any { name -> name.folded().let { it.isNotEmpty() && (it in by || by in it) } } &&
                (lengthMs == null || hit.lengthMs == null || abs(hit.lengthMs - lengthMs) <= 8_000)
        }.maxByOrNull { it.score }?.id
    }

    /** A title without what YouTube adds to it: "(Official Video)", "[Lyrics]", "| Audio", "- Topic". */
    internal fun clean(title: String): String = title
        .replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|hd|4k|from)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .substringBefore(" | ").trim()

    private fun String.folded(): String = java.text.Normalizer.normalize(lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    internal fun durationMs(text: String?): Long? {
        val parts = text?.trim()?.split(':')?.map { it.toLongOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1000
    }

    private fun JsonObject.text(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    private fun JsonArray.names(): List<String> = mapNotNull { (it as? JsonObject)?.text("name") }

    /** MusicBrainz asks for at most one request a second from each client. */
    private suspend fun ask(url: String): String? = pace.withLock {
        val wait = 1_100 - (System.currentTimeMillis() - lastAsked)
        if (wait > 0) delay(wait)
        try { fetch(url) } finally { lastAsked = System.currentTimeMillis() }
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/json").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    internal fun clearForTests() { cache.clear(); lastAsked = 0L }
}
