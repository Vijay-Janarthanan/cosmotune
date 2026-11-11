package com.cosmotune.music.data.lyrics

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.InnertubeParser
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.lyrics.YouTubeLyricsRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * YouTube Music's own lyrics, with catalogue identity verified for music-video uploads.
 *
 * The same Lyrics tab answers differently depending on who asks. The web
 * player gets the words as one block of plain text; the Android app gets them
 * as timed lines (`timedLyricsModel`, cue ranges in milliseconds), wherever
 * YouTube's lyrics partner has timed the song. So the tab is asked for as the
 * Android app first, and as the web player only when that has no timing.
 */
object YouTubeMusicLyrics {
    private const val ANDROID_MUSIC_VERSION = "7.27.52"
    private const val ANDROID_MUSIC_AGENT =
        "com.google.android.apps.youtube.music/$ANDROID_MUSIC_VERSION (Linux; U; Android 14) gzip"
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    suspend fun lyrics(videoId: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (!YOUTUBE_ID.matches(videoId)) return@withContext null
        val next = runCatching { Innertube.next(videoId) }.getOrNull() ?: return@withContext null
        val playing = InnertubeParser.parseWatchQueue(next).firstOrNull { it.videoId == videoId }
            ?: return@withContext null
        val lyricContext = if (!playing.isVideo && playing.albumId?.startsWith("MPRE") == true) next else {
            // YouTube can attach a dubbed song's Lyrics tab to a Tamil video. Even the correct
            // MPLY endpoint is not proof of identity for an upload. Find a catalogue track whose
            // release explicitly links this video; never guess language from the lyric script.
            val catalogue = withTimeoutOrNull(6_000) {
                val query = "${playing.title.forLyricsSearch()} ${playing.artist.artistForLyricsSearch()}"
                val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                    .orEmpty().filterIsInstance<SearchResult.Track>().map { it.song }
                YouTubeLyricsRecording.counterpart(videoId, candidates) { albumId ->
                    YouTubeLyricsRecording.albumTracks(Innertube.browse(albumId))
                }
            } ?: return@withContext null
            runCatching { Innertube.next(catalogue.videoId) }.getOrNull() ?: return@withContext null
        }
        val endpoint = lyricsEndpoint(lyricContext) ?: return@withContext null
        val browseId = (endpoint["browseId"] as? JsonPrimitive)?.contentOrNull
            ?: return@withContext null
        timedPage(browseId)?.let { return@withContext it }
        val params = (endpoint["params"] as? JsonPrimitive)?.contentOrNull
        val page = runCatching { Innertube.browse(browseId, params) }.getOrNull() ?: return@withContext null
        parsePage(page)
    }

    /**
     * The Lyrics tab as the Android app sees it: timed lines, or null when the
     * song has no timing there (or the request failed). Anonymous on purpose:
     * a lyrics page is the same for everyone, and the listener's web session
     * cookie means nothing to the Android client.
     */
    private fun timedPage(browseId: String): List<LyricLine>? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", ANDROID_MUSIC_VERSION)
                    put("androidSdkVersion", 34)
                    put("hl", Innertube.currentLanguage)
                    put("gl", Innertube.currentRegion)
                }
            }
            put("browseId", browseId)
        }.toString()
        val answer = lyricsPostJson(
            BROWSE_URL,
            body,
            mapOf(
                "User-Agent" to ANDROID_MUSIC_AGENT,
                "X-Goog-Api-Format-Version" to "1",
                "Accept" to "application/json",
            ),
        ) ?: return null
        val page = runCatching { lyricsJson.parseToJsonElement(answer) }.getOrNull() ?: return null
        return parseTimedPage(page)
    }

    /**
     * Timed lines from an Android lyrics page: each `timedLyricsData` entry is
     * one line with its cue range. Blank and note-only cues are left out:
     * each line's end is known, so [withInstrumentalGaps] marks the real
     * breaks where the singing stops, as it does for every source with ends.
     * Null unless at least two lines carry a real timestamp: a page of zeroes
     * is plain text dressed as timing.
     */
    internal fun parseTimedPage(page: JsonElement): List<LyricLine>? {
        val data = page.objectsNamed("timedLyricsModel").firstNotNullOfOrNull { model ->
            (model["lyricsData"] as? JsonObject)?.get("timedLyricsData") as? JsonArray
        } ?: return null
        val lines = data.mapNotNull { entry ->
            val cue = (entry as? JsonObject) ?: return@mapNotNull null
            val text = (cue["lyricLine"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty() || text.all { it == '♪' || it.isWhitespace() }) return@mapNotNull null
            val range = cue["cueRange"] as? JsonObject ?: return@mapNotNull null
            val start = range["startTimeMilliseconds"].millis() ?: return@mapNotNull null
            val end = range["endTimeMilliseconds"].millis()?.takeIf { it > start }
            LyricLine(start, text, sungUntilMs = end)
        }.sortedBy { it.timeMs }
        if (lines.size < 2 || lines.count { it.timeMs > 0 } < 2) return null
        return lines.withInstrumentalGaps()
    }

    /** Innertube writes these as strings ("12345"); read a number either way. */
    private fun JsonElement?.millis(): Long? =
        (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }?.takeIf { it >= 0 }

    internal fun lyricsEndpoint(next: JsonElement): JsonObject? =
        next.objectsNamed("tabRenderer").filter { tab ->
            (tab["unselectable"] as? JsonPrimitive)?.contentOrNull != "true"
        }.firstNotNullOfOrNull { tab ->
            val labelledLyrics = tab["title"]?.youtubeStrings()
                ?.any { it.equals("Lyrics", ignoreCase = true) } == true
            tab.objectsNamed("browseEndpoint").firstOrNull { endpoint ->
                val browseId = (endpoint["browseId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val typedLyrics = endpoint.objectsNamed("browseEndpointContextMusicConfig")
                    .any { (it["pageType"] as? JsonPrimitive)?.contentOrNull == "MUSIC_PAGE_TYPE_TRACK_LYRICS" }
                // The Lyrics label is localized. Its endpoint id/type remains stable.
                // An arbitrary second tab can instead be Related, with an unrelated description.
                typedLyrics || browseId.startsWith("MPLYt") || labelledLyrics
            }
        }

    internal fun parsePage(page: JsonElement): List<LyricLine>? {
        val shelf = page.objectsNamed("musicDescriptionShelfRenderer").firstOrNull()
            ?: return null
        val text = shelf["description"]?.youtubeStrings()?.joinToString("").orEmpty().trim()
        return text.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .map { LyricLine(0L, it) }.toList().takeIf { it.isNotEmpty() }
    }
}

private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")

private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> = sequence {
    when (this@objectsNamed) {
        is JsonObject -> for ((key, value) in this@objectsNamed) {
            if (key == name && value is JsonObject) yield(value)
            yieldAll(value.objectsNamed(name))
        }
        is JsonArray -> for (value in this@objectsNamed) yieldAll(value.objectsNamed(name))
        else -> Unit
    }
}

fun JsonElement.youtubeStrings(): List<String> = when (this) {
    is JsonPrimitive -> contentOrNull?.let(::listOf).orEmpty()
    is JsonArray -> flatMap { it.youtubeStrings() }
    is JsonObject -> {
        val direct = (this["text"] as? JsonPrimitive)?.contentOrNull
            ?: (this["simpleText"] as? JsonPrimitive)?.contentOrNull
        direct?.let(::listOf) ?: values.flatMap { it.youtubeStrings() }
    }
}
