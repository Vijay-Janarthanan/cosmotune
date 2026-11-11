package com.cosmotune.video.signed

import com.cosmotune.video.account.YoutubeFeedParser
import com.cosmotune.video.account.arr
import com.cosmotune.video.account.obj
import com.cosmotune.video.account.str
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Storyboard
import com.cosmotune.video.model.StreamKind
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.source.newpipe.NewPipeMapper
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A signed `player` answer (and its `next` answer) as Video's own models. No format is trusted to be playable: one with no address, a
 * link that cannot be solved, DRM, or the OTF delivery the player's data sources do not read is left out, as NewPipe's mapping leaves
 * out what it cannot give as an address. A client whose every format was left out has answered "no streams".
 */
internal object SignedPlayerParser {

    /**
     * The streams of [player]. [solveAll] gets every format's `url` and `signatureCipher` (in the answer's order, formats first) and gives
     * each one's playable address, or null for one that cannot be played.
     */
    suspend fun streams(
        player: JsonObject,
        client: SignedPlayerClient,
        streamingToken: String?,
        solveAll: suspend (links: List<Pair<String?, String?>>) -> List<String?>,
    ): StreamSet {
        val data = player.obj("streamingData")
        val candidates = ArrayList<Pair<JsonObject, Boolean>>()
        for ((formats, isMuxed) in listOf(data.arr("formats") to true, data.arr("adaptiveFormats") to false)) {
            for (element in formats.orEmpty()) {
                val format = element as? JsonObject ?: continue
                if (format.str("type") == "FORMAT_STREAM_TYPE_OTF" || format["drmFamilies"] != null) continue
                if (format.str("url") == null && cipherOf(format) == null) continue
                candidates += format to isMuxed
            }
        }
        val addresses = try {
            solveAll(candidates.map { (format, _) -> format.str("url") to cipherOf(format) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val videoOnly = ArrayList<VideoStream>()
        val muxed = ArrayList<VideoStream>()
        val audio = ArrayList<AudioStream>()
        candidates.forEachIndexed { i, (format, isMuxed) ->
            val solved = addresses.getOrNull(i) ?: return@forEachIndexed
            val address = if (streamingToken == null) solved else solved + (if ('?' in solved) "&" else "?") + "pot=" + java.net.URLEncoder.encode(streamingToken, "UTF-8")
            val mime = format.str("mimeType").orEmpty()
            when {
                mime.startsWith("video/") -> videoStream(format, mime, address, isMuxed)?.let { if (isMuxed) muxed += it else videoOnly += it }
                mime.startsWith("audio/") -> audioStream(format, mime, address)?.let { audio += it }
            }
        }
        val hls = data.str("hlsManifestUrl")?.takeIf { it.isNotBlank() }
        val dash = data.str("dashManifestUrl")?.takeIf { it.isNotBlank() }
        val all = videoOnly.map { it.url } + muxed.map { it.url } + audio.map { it.url }
        return StreamSet(
            videoOnly = videoOnly,
            muxed = muxed,
            audio = audio,
            hlsUrl = hls,
            dashMpdUrl = dash,
            expiresAtMs = NewPipeMapper.expiresAtMsOf(all) ?: NewPipeMapper.expiresAtMsOf(listOfNotNull(hls, dash)),
            requestHeaders = mediaHeaders(client),
            durationMs = player.obj("videoDetails").long("lengthSeconds")?.takeIf { it > 0 }?.times(1000),
        )
    }

    private fun cipherOf(format: JsonObject): String? = format.str("signatureCipher") ?: format.str("cipher")

    /**
     * What the media fetches must say for a link this client minted. Never a cookie: a stream address carries its own authority, and the
     * data sources' headers go to whatever host a redirect names.
     */
    fun mediaHeaders(client: SignedPlayerClient): Map<String, String> = buildMap {
        put("User-Agent", client.userAgent)
        if (client.browser) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
        }
    }

    private fun videoStream(format: JsonObject, mime: String, address: String, isMuxed: Boolean): VideoStream? {
        val itag = format.int("itag") ?: return null
        val codecs = codecsOf(mime)
        val height = format.int("height") ?: 0
        return VideoStream(
            url = address,
            itag = itag,
            mimeType = mime.substringBefore(';').trim(),
            codec = NewPipeMapper.videoCodecOf(codecs),
            codecs = codecs,
            width = format.int("width")?.takeIf { it > 0 } ?: (height * 16 / 9),
            height = height,
            fps = format.int("fps")?.coerceAtLeast(0) ?: 0,
            bitrate = format.int("bitrate")?.coerceAtLeast(0) ?: 0,
            hdr = NewPipeMapper.isHdr(format.str("qualityLabel"), codecs),
            contentLength = format.long("contentLength")?.takeIf { it > 0 },
            init = rangeOf(format.obj("initRange")),
            index = rangeOf(format.obj("indexRange")),
            delivery = Delivery.PROGRESSIVE,
            hasAudio = isMuxed,
        )
    }

    private fun audioStream(format: JsonObject, mime: String, address: String): AudioStream? {
        val itag = format.int("itag") ?: return null
        val codecs = codecsOf(mime)
        val track = format.obj("audioTrack")
        val trackId = track.str("id")?.takeIf { it.isNotBlank() }
        val name = track.str("displayName")?.takeIf { it.isNotBlank() }
        return AudioStream(
            url = address,
            itag = itag,
            mimeType = mime.substringBefore(';').trim(),
            codec = NewPipeMapper.audioCodecOf(codecs),
            codecs = codecs,
            bitrate = format.int("bitrate")?.coerceAtLeast(0) ?: 0,
            sampleRate = format.int("audioSampleRate")?.takeIf { it > 0 },
            channels = format.int("audioChannels")?.takeIf { it > 0 },
            contentLength = format.long("contentLength")?.takeIf { it > 0 },
            init = rangeOf(format.obj("initRange")),
            index = rangeOf(format.obj("indexRange")),
            delivery = Delivery.PROGRESSIVE,
            trackId = trackId,
            trackName = name,
            language = trackId?.substringBefore('.')?.takeIf { it.isNotBlank() },
            trackKind = when {
                track == null -> AudioTrackKind.UNKNOWN
                name?.contains("original", ignoreCase = true) == true -> AudioTrackKind.ORIGINAL
                name?.contains("descriptive", ignoreCase = true) == true -> AudioTrackKind.DESCRIPTIVE
                track.bool("audioIsDefault") -> AudioTrackKind.ORIGINAL
                else -> AudioTrackKind.DUBBED
            },
        )
    }

    private val CODECS = Regex("""codecs="([^"]*)"""")

    private fun codecsOf(mime: String): String = CODECS.find(mime)?.groupValues?.get(1).orEmpty()

    private fun rangeOf(range: JsonObject?): ByteRange? {
        val start = range.long("start") ?: return null
        val end = range.long("end") ?: return null
        return if (end <= start) null else ByteRange(start, end)
    }

    // ---- The watch page ------------------------------------------------------------------------------------------------

    /** The watch page's data from a signed `player` answer and, when it could be read, its `next` answer. */
    fun details(videoId: String, playback: SignedPlayback, next: JsonObject?, nowMs: Long): VideoDetails {
        val player = playback.player
        val info = player.obj("videoDetails")
        val micro = player.obj("microformat").obj("playerMicroformatRenderer")
        val owner = next?.let { firstObject(it, "videoOwnerRenderer") }
        val text = info.str("shortDescription").orEmpty()
        val isLive = info.bool("isLive") || (info.bool("isLiveContent") && info.long("lengthSeconds") == 0L)
        val related = next?.let { YoutubeFeedParser.parseRelated(it, videoId).videos }.orEmpty()
        return VideoDetails(
            item = VideoItem(
                id = videoId,
                title = info.str("title").orEmpty(),
                channelId = info.str("channelId"),
                channelName = info.str("author"),
                channelAvatarUrl = YoutubeFeedParser.bestUrl(owner.obj("thumbnail").arr("thumbnails")),
                thumbnailUrl = YoutubeFeedParser.bestUrl(info.obj("thumbnail").arr("thumbnails")),
                durationSec = info.long("lengthSeconds")?.takeIf { it > 0 },
                viewCount = info.long("viewCount"),
                uploadedAtMs = null,
                uploadedText = micro.str("publishDate"),
                isLive = isLive,
                isUpcoming = info.bool("isUpcoming"),
            ),
            description = NewPipeMapper.plainDescription(text, videoId),
            likeCount = next?.let(::likesOf),
            category = micro.str("category"),
            tags = info.arr("keywords").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            chapters = next?.let(::chaptersOf).orEmpty(),
            related = related.map { ListItem.Video(it) },
            subtitles = subtitlesOf(player),
            storyboard = storyboardOf(player),
            kind = when {
                isLive -> StreamKind.LIVE
                info.bool("isPostLiveDvr") || info.bool("isLiveContent") -> StreamKind.POST_LIVE
                else -> StreamKind.VIDEO
            },
            streams = playback.streams,
            subscriberCount = YoutubeFeedParser.parseCount(YoutubeFeedParser.text(owner?.get("subscriberCountText"))),
            fetchedAtMs = nowMs,
        )
    }

    private fun subtitlesOf(player: JsonObject): List<SubtitleTrack> =
        player.obj("captions").obj("playerCaptionsTracklistRenderer").arr("captionTracks").orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val url = track.str("baseUrl") ?: return@mapNotNull null
            SubtitleTrack(
                url = url + (if ('?' in url) "&" else "?") + "fmt=vtt",
                mimeType = "text/vtt",
                language = track.str("languageCode").orEmpty(),
                name = YoutubeFeedParser.text(track["name"]).orEmpty(),
                autoGenerated = track.str("kind") == "asr",
            )
        }

    /** The largest frames of `base|w#h#count#cols#rows#interval#name#sig|...` (`$L` the level's index, `$N` its name, `$M` the sheet). */
    fun storyboardOf(player: JsonObject): Storyboard? {
        val spec = player.obj("storyboards").obj("playerStoryboardSpecRenderer").str("spec") ?: return null
        val parts = spec.split('|')
        if (parts.size < 2) return null
        val base = parts[0]
        val level = parts.lastIndex - 1
        val fields = parts.last().split('#')
        if (fields.size < 8) return null
        val width = fields[0].toIntOrNull() ?: return null
        val height = fields[1].toIntOrNull() ?: return null
        val total = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val columns = fields[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val rows = fields[4].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val interval = fields[5].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val perSheet = columns * rows
        val sheets = (total + perSheet - 1) / perSheet
        val urls = (0 until sheets).map { sheet ->
            val address = base.replace("\$L", level.toString()).replace("\$N", fields[6]).replace("\$M", sheet.toString())
            address + (if ('?' in address) "&" else "?") + "sigh=" + fields[7]
        }
        return Storyboard(urls, width, height, columns, rows, interval, total)
    }

    private fun chaptersOf(next: JsonObject): List<Chapter> {
        val markers = firstObject(next, "multiMarkersPlayerBarRenderer").arr("markersMap").orEmpty()
        val entry = markers.firstOrNull { (it as? JsonObject)?.str("key")?.contains("CHAPTERS") == true } ?: markers.firstOrNull()
        return entry.obj("value").arr("chapters").orEmpty().mapNotNull { element ->
            val chapter = element.obj("chapterRenderer") ?: return@mapNotNull null
            val title = YoutubeFeedParser.text(chapter["title"]) ?: return@mapNotNull null
            val start = chapter.long("timeRangeStartMillis") ?: return@mapNotNull null
            Chapter(title, start, YoutubeFeedParser.bestUrl(chapter.obj("thumbnail").arr("thumbnails")))
        }
    }

    /** The like count, from the like button's words: its label holds the count in the site's own number format. */
    private fun likesOf(next: JsonObject): Long? {
        val button = firstObject(next, "segmentedLikeDislikeButtonViewModel") ?: return null
        return YoutubeFeedParser.parseCount(firstObject(button, "buttonViewModel").str("title"))
    }

    /** The first object held under [key], anywhere under [node], depth first. */
    fun firstObject(node: JsonElement?, key: String): JsonObject? = when (node) {
        is JsonObject -> (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { firstObject(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { firstObject(it, key) }
        else -> null
    }

    private fun JsonElement?.primitive(key: String): JsonPrimitive? = (this as? JsonObject)?.get(key) as? JsonPrimitive

    private fun JsonElement?.int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonElement?.long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    private fun JsonElement?.bool(key: String): Boolean = primitive(key)?.booleanOrNull == true
}
