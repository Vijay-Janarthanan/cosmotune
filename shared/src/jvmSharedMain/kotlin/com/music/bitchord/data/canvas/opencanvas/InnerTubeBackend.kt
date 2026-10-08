package com.opencanvas.core.stream

import com.opencanvas.core.models.OpenCanvasResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The app identity [InnerTubeBackend] presents to YouTube's `player` endpoint. */
data class InnerTubeClient(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    /** The client's numeric id, sent as `X-YouTube-Client-Name`; null when the client does not send one. */
    val clientNameId: Int? = null,
    /** Whether the `player` call must carry a visitor token (see [VisitorToken]). */
    val needsVisitorToken: Boolean = false,
) {
    companion object {
        /**
         * The YouTube Android app. It answers with directly playable URLs without any token, but only
         * for the muxed 360p stream (itag 18): a progressive MP4 whose header lists every audio
         * frame, which makes it both the instant first picture and the small file the sync
         * measurement reads.
         */
        val ANDROID = InnerTubeClient(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            userAgent = "com.google.android.youtube/21.26.364 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
            androidSdkVersion = 35,
        )

        /**
         * YouTube's visionOS app. With a visitor token it answers with plain URLs for every adaptive
         * stream - 720p and 1080p H.264, VP9, AV1, audio-only - needing no signature deciphering, no
         * throttling parameter and no proof-of-origin token, which is what makes a sharp canvas
         * possible without a JavaScript engine or a browser.
         */
        val VISIONOS = InnerTubeClient(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            clientNameId = 101,
            needsVisitorToken = true,
        )
    }
}

/** One stream of a `player` response that carries a plain URL. */
internal data class PlayerFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bitrate: Long,
    val contentLength: Long,
    val durationMs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

/**
 * The anonymous visitor token YouTube hands to every client that asks for one (a 700-byte answer in
 * about 0.1 s). Some clients must present it with their `player` call; it is kept for [TTL_MS] and
 * shared by every request.
 */
internal class VisitorToken(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val clock: () -> Long,
) {
    private var token: String? = null
    private var obtainedAt = 0L

    @Synchronized
    private fun cached(): String? = token?.takeIf { clock() - obtainedAt < TTL_MS }

    /** A current token, fetched on first use; null when YouTube would not give one. */
    suspend fun get(): String? {
        cached()?.let { return it }
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody(JSON)).build()
        val fresh = tryOrNull {
            withRetry { timeoutMs ->
                val answer = http.newCall(request).within(timeoutMs).fetch()
                if (!answer.isSuccessful) throw java.io.IOException("visitor id answered HTTP ${answer.code}")
                (((Json.parseToJsonElement(answer.text()) as? JsonObject)?.get("responseContext") as? JsonObject)?.get("visitorData") as? JsonPrimitive)?.contentOrNull
            }
        }
        if (fresh != null) synchronized(this) {
            token = fresh
            obtainedAt = clock()
        }
        return fresh
    }

    /** Drops the token, so the next [get] asks for a new one. */
    @Synchronized
    fun forget() {
        token = null
    }

    companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        private const val WEB_VERSION = "2.20260707.00.00"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Resolves playable URLs straight from YouTube's InnerTube `player` endpoint: no server, no
 * dependency beyond OkHttp, and one request per video (video and audio lookups for the same id share
 * it and its answer is kept for [CACHE_TTL_MS]).
 *
 * The client profile decides what comes back: [android] gives the muxed 360p MP4 at once,
 * [visionOs] gives the sharp adaptive streams (720p and up). `OpenCanvasConfig` uses both so a canvas
 * starts immediately and sharpens a moment later, with nothing to install and no help from the host.
 */
class InnerTubeBackend(
    private val http: OkHttpClient = defaultHttpClient(),
    private val profile: InnerTubeClient = InnerTubeClient.ANDROID,
    private val clock: () -> Long = System::currentTimeMillis,
    private val endpoint: String = PLAYER_URL,
    private val visitorEndpoint: String = VisitorToken.URL,
) : StreamBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<List<PlayerFormat>?>>()
    private val answers = ConcurrentHashMap<String, Pair<Long, List<PlayerFormat>>>()
    private val visitor = VisitorToken(http, visitorEndpoint, clock)

    override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution): StreamInfo? {
        val format = pickVideo(formatsFor(videoId) ?: return null, resolution) ?: return null
        return StreamInfo(
            url = format.url,
            durationMs = format.durationMs,
            width = format.width,
            height = format.height,
            headers = mediaHeaders(),
        )
    }

    override suspend fun resolveAudio(videoId: String): AudioStream? {
        val format = pickAudio(formatsFor(videoId) ?: return null) ?: return null
        return AudioStream(
            url = format.url,
            headers = mediaHeaders(),
            durationMs = format.durationMs,
            contentLength = format.contentLength,
        )
    }

    /** Opens the connection to YouTube (and gets the visitor token) ahead of the first lookup. */
    override fun warmUp() {
        scope.launch {
            tryOrNull { http.newCall(Request.Builder().url("https://www.youtube.com/generate_204").head().build()).fetch() }
            if (profile.needsVisitorToken) visitor.get()
        }
    }

    private fun mediaHeaders(): Map<String, String> = mapOf("User-Agent" to profile.userAgent)

    private suspend fun formatsFor(videoId: String): List<PlayerFormat>? {
        answers[videoId]?.let { (expires, formats) -> if (expires > clock()) return formats }
        val request = inflight.computeIfAbsent(videoId) {
            scope.async {
                try {
                    fetchFormats(videoId)?.also { answers[videoId] = clock() + CACHE_TTL_MS to it }
                } finally {
                    inflight.remove(videoId)
                }
            }
        }
        return request.await()
    }

    private suspend fun fetchFormats(videoId: String): List<PlayerFormat>? {
        // A visitor token that YouTube has stopped honouring shows up as a refusal: try once more with a new one.
        repeat(if (profile.needsVisitorToken) 2 else 1) { attempt ->
            val token = if (profile.needsVisitorToken) visitor.get() ?: return null else null
            val request = playerRequest(videoId, token)
            val formats = tryOrNull {
                withRetry { timeoutMs ->
                    val answer = http.newCall(request).within(timeoutMs).fetch()
                    if (answer.isSuccessful) parsePlayerResponse(answer.text()) else null
                }
            }
            if (formats != null) return formats
            if (attempt == 0 && profile.needsVisitorToken) visitor.forget()
        }
        return null
    }

    private fun playerRequest(videoId: String, visitorToken: String?): Request {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", profile.clientName)
                    put("clientVersion", profile.clientVersion)
                    profile.androidSdkVersion?.let { put("androidSdkVersion", it) }
                    profile.deviceMake?.let { put("deviceMake", it) }
                    profile.deviceModel?.let { put("deviceModel", it) }
                    profile.osName?.let { put("osName", it) }
                    profile.osVersion?.let { put("osVersion", it) }
                    visitorToken?.let { put("visitorData", it) }
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", profile.userAgent)
            .apply {
                profile.clientNameId?.let {
                    header("X-YouTube-Client-Name", it.toString())
                    header("X-YouTube-Client-Version", profile.clientVersion)
                    header("Origin", "https://www.youtube.com")
                }
                visitorToken?.let { header("X-Goog-Visitor-Id", it) }
            }
            .build()
    }

    companion object {
        /** How long a `player` answer is reused: the URLs in it stay valid for hours. */
        const val CACHE_TTL_MS = 20 * 60 * 1000L

        internal const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val parser = Json { ignoreUnknownKeys = true }

        /** The instant tier: the muxed 360p MP4 and the small file the sync measurement reads. */
        fun android(http: OkHttpClient = defaultHttpClient()) = InnerTubeBackend(http, InnerTubeClient.ANDROID)

        /** The sharp tier: adaptive 720p and up, and audio-only streams. */
        fun visionOs(http: OkHttpClient = defaultHttpClient()) = InnerTubeBackend(http, InnerTubeClient.VISIONOS)

        /** A client with short timeouts suited to many small requests. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(15))
            .build()

        /**
         * Reads the playable streams of a `player` response. Returns null when the video is not
         * playable (`playabilityStatus` is not `OK`) or the body is not a response at all; a
         * playable video without any plain-URL stream yields an empty list.
         */
        internal fun parsePlayerResponse(body: String): List<PlayerFormat>? {
            val root = runCatching { parser.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val status = (root["playabilityStatus"] as? JsonObject)?.get("status").text()
            if (status != "OK") return null
            val details = root["videoDetails"] as? JsonObject
            val lengthMs = details?.get("lengthSeconds").text()?.toLongOrNull()?.times(1000L) ?: 0L
            val streaming = root["streamingData"] as? JsonObject ?: return emptyList()
            val all = listOf("formats", "adaptiveFormats").flatMap { (streaming[it] as? JsonArray).orEmpty() }
            return all.mapNotNull { element ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format["url"].text()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val mime = format["mimeType"].text().orEmpty()
                val codecs = mime.substringAfter("codecs=\"", "").substringBefore('"').lowercase()
                val hasVideo = mime.startsWith("video/")
                val hasAudio = mime.startsWith("audio/") ||
                    (hasVideo && ("mp4a" in codecs || "opus" in codecs || "vorbis" in codecs))
                PlayerFormat(
                    itag = (format["itag"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                    url = url,
                    mimeType = mime,
                    codecs = codecs,
                    width = (format["width"] as? JsonPrimitive)?.intOrNull ?: 0,
                    height = (format["height"] as? JsonPrimitive)?.intOrNull ?: 0,
                    bitrate = (format["bitrate"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    contentLength = format["contentLength"].text()?.toLongOrNull() ?: 0L,
                    durationMs = format["approxDurationMs"].text()?.toLongOrNull() ?: lengthMs,
                    hasVideo = hasVideo,
                    hasAudio = hasAudio,
                )
            }
        }

        /**
         * The best video stream within [resolution]: the tallest that fits, H.264 in MP4 first
         * (every phone and every FFmpeg build decodes it) before VP9 and AV1, video-only before muxed
         * because the canvas is silent. When every stream is taller than allowed, the shortest one.
         */
        internal fun pickVideo(formats: List<PlayerFormat>, resolution: OpenCanvasResolution): PlayerFormat? {
            val videos = formats.filter { it.hasVideo }
            if (videos.isEmpty()) return null
            val maxWidth = resolution.maxWidth ?: Int.MAX_VALUE
            val fitting = videos.filter { it.height <= resolution.maxHeight && it.width <= maxWidth }
            val pool = fitting.ifEmpty { listOf(videos.minBy { it.height }) }
            val tallest = pool.maxOf { it.height }
            return pool.filter { it.height == tallest }
                .sortedWith(compareBy<PlayerFormat> { codecRank(it) }.thenBy { it.hasAudio }.thenBy { it.bitrate })
                .first()
        }

        private fun codecRank(format: PlayerFormat): Int = when {
            "avc1" in format.codecs -> 0
            "vp9" in format.codecs || "vp09" in format.codecs -> 1
            "av01" in format.codecs -> 2
            else -> 3
        }

        /**
         * The smallest progressive MP4 with audio: its sample tables sit in the header, so a couple of
         * hundred kilobytes describe the whole song. Falls back to the smallest audio-only stream,
         * which the reader has to download whole.
         */
        internal fun pickAudio(formats: List<PlayerFormat>): PlayerFormat? {
            val progressive = formats.filter { it.hasAudio && it.hasVideo && it.mimeType.startsWith("video/mp4") }.minByOrNull { it.bitrate }
            return progressive ?: formats.filter { it.hasAudio && !it.hasVideo && it.mimeType.startsWith("audio/mp4") }.minByOrNull { it.bitrate }
        }

        private fun kotlinx.serialization.json.JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
