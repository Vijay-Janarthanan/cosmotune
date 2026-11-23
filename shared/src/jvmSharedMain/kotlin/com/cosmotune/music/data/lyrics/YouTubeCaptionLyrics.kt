package com.cosmotune.music.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One of a video's caption tracks: where its timed text is, its language, and whether YouTube made it automatically. */
data class CaptionTrack(val url: String, val language: String, val automatic: Boolean)

/**
 * Lyrics from a music video's captions: the timed lines its uploader added (never YouTube's automatic transcript, which
 * guesses at sung words), as line-timed lyrics. Asked after the lyrics libraries, for the songs only the video has words for.
 */
object YouTubeCaptionLyrics {
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val ANDROID_VERSION = "21.26.364"
    private const val ANDROID_AGENT = "com.google.android.youtube/$ANDROID_VERSION (Linux; U; Android 15; en_US) gzip"
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val CUE = Regex("""<p\s+t="(\d+)"(?:\s+d="(\d+)")?[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")
    /** Lines that are a caption's stage directions rather than words: "[Music]", "(applause)", a lone note. */
    private val DIRECTION = Regex("""^[\[(][^\])]*[\])]$|^[♪♫#\s]*$""")

    suspend fun lyrics(videoId: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (!VIDEO_ID.matches(videoId)) return@withContext null
        val body = """{"context":{"client":{"clientName":"ANDROID","clientVersion":"$ANDROID_VERSION","androidSdkVersion":35,"hl":"en","gl":"US"}},""" +
            """"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
        val player = lyricsPostJson(PLAYER_URL, body, mapOf("User-Agent" to ANDROID_AGENT, "Content-Type" to "application/json")) ?: return@withContext null
        val track = pick(tracks(player)) ?: return@withContext null
        val captions = lyricsGet(track.url.replace(Regex("&fmt=[^&]*"), "") + "&fmt=srv3") ?: return@withContext null
        parse(captions)
    }

    /** The caption tracks a `player` answer lists; only YouTube's own timed-text addresses are taken. */
    fun tracks(player: String): List<CaptionTrack> = runCatching {
        val root = lyricsJson.parseToJsonElement(player) as? JsonObject
        val list = ((root?.get("captions") as? JsonObject)?.get("playerCaptionsTracklistRenderer") as? JsonObject)
            ?.get("captionTracks") as? JsonArray
        list.orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (track[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val url = text("baseUrl")?.takeIf { it.startsWith("https://www.youtube.com/api/timedtext") } ?: return@mapNotNull null
            CaptionTrack(url, text("languageCode").orEmpty(), automatic = text("kind") == "asr")
        }
    }.getOrDefault(emptyList())

    /** The uploader's own track, the first one listed (YouTube lists the original language first); none when all are automatic. */
    fun pick(tracks: List<CaptionTrack>): CaptionTrack? = tracks.firstOrNull { !it.automatic }

    /** Timed lines from a `srv3` caption file; null when it has too few lines of words to be a song's lyrics. */
    fun parse(srv3: String): List<LyricLine>? {
        val lines = CUE.findAll(srv3).mapNotNull { cue ->
            val start = cue.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val length = cue.groupValues[2].toLongOrNull()
            val text = unescape(TAG.replace(cue.groupValues[3], ""))
                .replace('\n', ' ').replace(Regex("[♪♫]"), "").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty() || DIRECTION.matches(text)) null
            else LyricLine(start, text, sungUntilMs = length?.let { start + it })
        }.toList()
        return lines.takeIf { it.size >= MIN_LINES }
    }

    private fun unescape(text: String) = text
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private const val MIN_LINES = 4
}
