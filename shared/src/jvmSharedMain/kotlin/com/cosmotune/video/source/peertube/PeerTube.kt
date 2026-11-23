package com.cosmotune.video.source.peertube

import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.segments.WebServicesHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.time.Instant

/** A channel on a PeerTube site: the site's host and the channel's name there. */
data class PeerChannelRef(val host: String, val name: String) {
    /** The channel's own address, as the listener follows it. */
    val url: String get() = "https://$host/c/$name"
}

/**
 * PeerTube, the many federated video sites (each its own address), read through their public API with no key: a channel's name and
 * picture (`/api/v1/video-channels/{name}`), its newest videos (`.../videos?sort=-publishedAt`) and, to play one, the file the site
 * serves (`/api/v1/videos/{uuid}`). The videos are Video items whose id starts with [ID_PREFIX], played as files from the site.
 */
class PeerTubeClient(http: OkHttpClient = WebServicesHttp.client, private val userAgent: String = WebServicesHttp.userAgent()) {
    private val http = WebServicesHttp.tuned(http)

    suspend fun channelName(ref: PeerChannelRef): String? = get(api(ref.host, "video-channels", ref.name))?.let(::displayNameOf)

    suspend fun videos(ref: PeerChannelRef, count: Int = 15): List<VideoItem> {
        val url = api(ref.host, "video-channels", ref.name, "videos")?.newBuilder()
            ?.addQueryParameter("count", count.toString())?.addQueryParameter("sort", "-publishedAt")?.build()
        return get(url)?.let { parseVideos(it, ref) }.orEmpty()
    }

    /**
     * The PeerTube channels of the creator called [name], found through Sepia Search (the index of every PeerTube site): only those
     * whose shown name is the same name (case, spaces and marks aside). Empty when there is none or the index does not answer.
     */
    suspend fun sameCreator(name: String, index: String = SEPIA): List<PeerChannelRef> {
        val key = sameName(name)
        if (key.length < 3) return emptyList()
        val url = "$index/api/v1/search/video-channels".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("search", name)?.addQueryParameter("count", "10")?.build()
        return get(url)?.let { parseSameCreator(it, key) }.orEmpty()
    }

    /** The address of the file that plays [videoId] (one of this class's ids), or null. */
    suspend fun fileUrl(videoId: String): String? {
        val (host, uuid) = parseId(videoId) ?: return null
        return get(api(host, "videos", uuid))?.let(::bestFile)
    }

    private fun api(host: String, vararg path: String): HttpUrl? =
        "https://$host/api/v1".toHttpUrlOrNull()?.newBuilder()?.apply { path.forEach { addPathSegment(it) } }?.build()

    private suspend fun get(url: HttpUrl?): JsonObject? {
        url ?: return null
        val answer = runCatching { WebServicesHttp.get(http, url, userAgent) }.getOrNull() ?: return null
        if (!answer.isSuccessful) return null
        return runCatching { Json.parseToJsonElement(answer.body) as? JsonObject }.getOrNull()
    }

    companion object {
        /** What the ids of these videos start with (a local id, so the file player plays them). */
        const val ID_PREFIX = LocalVideo.ID_PREFIX + "peertube/"

        /** Sepia Search, run by the makers of PeerTube. */
        const val SEPIA = "https://sepiasearch.org"

        /** A name as the same-creator match reads it: letters and digits only, lower case. */
        fun sameName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

        internal fun parseSameCreator(page: JsonObject, key: String): List<PeerChannelRef> =
            (page["data"] as? JsonArray).orEmpty().mapNotNull { element ->
                val c = element as? JsonObject ?: return@mapNotNull null
                val shown = displayNameOf(c) ?: return@mapNotNull null
                if (sameName(shown) != key) return@mapNotNull null
                val host = c.str("host") ?: c.str("url")?.toHttpUrlOrNull()?.host ?: return@mapNotNull null
                val handle = c.str("name") ?: return@mapNotNull null
                PeerChannelRef(host.lowercase(), handle)
            }.distinct()

        fun isPeerTube(videoId: String?): Boolean = videoId != null && videoId.startsWith(ID_PREFIX)

        /** The channel of an address: `https://site/c/name`, `/video-channels/name` (with or without a page after it), or `name@site`. */
        fun parseChannel(text: String): PeerChannelRef? {
            val t = text.trim().removePrefix("@")
            if ("://" !in t && '@' in t && '/' !in t) {
                val (name, host) = t.split('@', limit = 2)
                return if (name.isNotBlank() && '.' in host) PeerChannelRef(host.lowercase(), name) else null
            }
            val url = (if ("://" in t) t else "https://$t").toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val at = segments.indexOfFirst { it == "c" || it == "video-channels" }
            val name = segments.getOrNull(at + 1)?.takeIf { at >= 0 && it.isNotBlank() } ?: return null
            // a channel of another site, followed through this one, keeps its own handle ("name@other.site")
            return if ('@' in name) parseChannel(name) else PeerChannelRef(url.host.lowercase(), name)
        }

        fun videoId(host: String, uuid: String): String = "$ID_PREFIX$host/$uuid"

        /** The site and the video of one of these ids. */
        fun parseId(videoId: String): Pair<String, String>? {
            if (!isPeerTube(videoId)) return null
            val rest = videoId.removePrefix(ID_PREFIX)
            val host = rest.substringBefore('/')
            val uuid = rest.substringAfter('/', "")
            return if (host.isNotBlank() && uuid.isNotBlank()) host to uuid else null
        }

        internal fun displayNameOf(channel: JsonObject): String? = channel.str("displayName") ?: channel.str("name")

        internal fun parseVideos(page: JsonObject, ref: PeerChannelRef): List<VideoItem> =
            (page["data"] as? JsonArray).orEmpty().mapNotNull { element ->
                val v = element as? JsonObject ?: return@mapNotNull null
                val uuid = v.str("uuid") ?: return@mapNotNull null
                val channel = v["channel"] as? JsonObject
                val live = (v["isLive"] as? JsonPrimitive)?.contentOrNull == "true"
                VideoItem(
                    id = videoId(ref.host, uuid),
                    title = v.str("name").orEmpty().ifBlank { uuid },
                    channelId = null,
                    channelName = channel?.let(::displayNameOf),
                    channelAvatarUrl = (channel?.get("avatars") as? JsonArray)?.let { absolute(ref.host, largestPath(it)) },
                    thumbnailUrl = absolute(ref.host, v.str("thumbnailPath") ?: v.str("previewPath")),
                    durationSec = v.num("duration")?.toLong()?.takeIf { it > 0 && !live },
                    viewCount = v.num("views")?.toLong(),
                    uploadedAtMs = v.str("publishedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                    uploadedText = null,
                    isLive = live,
                )
            }

        /** The file to play: the sharpest one up to 1080p (web videos first, then the files of the HLS playlist). */
        internal fun bestFile(video: JsonObject): String? {
            val files = (video["files"] as? JsonArray).orEmpty() +
                (video["streamingPlaylists"] as? JsonArray).orEmpty().flatMap { ((it as? JsonObject)?.get("files") as? JsonArray).orEmpty() }
            val playable = files.mapNotNull { f ->
                val o = f as? JsonObject ?: return@mapNotNull null
                val url = o.str("fileUrl") ?: return@mapNotNull null
                val height = ((o["resolution"] as? JsonObject)?.num("id"))?.toInt() ?: 0
                url to height
            }.filter { it.second > 0 }
            return (playable.filter { it.second <= 1080 }.maxByOrNull { it.second } ?: playable.minByOrNull { it.second })?.first
        }

        private fun largestPath(avatars: JsonArray): String? =
            avatars.mapNotNull { it as? JsonObject }.maxByOrNull { it.num("width") ?: 0.0 }?.str("path")

        private fun absolute(host: String, path: String?): String? = when {
            path.isNullOrBlank() -> null
            path.startsWith("http") -> path
            else -> "https://$host$path"
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }
    }
}
