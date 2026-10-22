package com.music.bitchord.data.lyrics

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.cosmotune.lyrics.YouTubeLyricsRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** YouTube Music plain lyrics, with catalogue identity verified for music-video uploads. */
object YouTubeMusicLyrics {
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
        val params = (endpoint["params"] as? JsonPrimitive)?.contentOrNull
        val page = runCatching { Innertube.browse(browseId, params) }.getOrNull() ?: return@withContext null
        parsePage(page)
    }

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

/** Timed YouTube transcript/captions for the exact playing video. */
object YouTubeTranscriptLyrics {
    suspend fun lyrics(videoId: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (!YOUTUBE_ID.matches(videoId)) return@withContext null
        val response = runCatching { Innertube.transcript(videoId) }.getOrNull()
            ?: return@withContext null
        response.objectsNamed("transcriptCueRenderer").mapNotNull { cue ->
            val start = (cue["startOffsetMs"] as? JsonPrimitive)?.longOrNull
                ?: return@mapNotNull null
            val text = cue["cue"]?.youtubeStrings()?.joinToString("").orEmpty()
                .trim(' ', '\n', '♪')
            text.takeIf { it.isNotEmpty() }?.let { LyricLine(start, it) }
        }.sortedBy { it.timeMs }.toList().takeIf { it.isNotEmpty() }
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
