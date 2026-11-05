package com.cosmotune.podio

import com.cosmotune.music.data.innertube.Innertube
import java.net.URI
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Public podcast surfaces, using the same anonymous InnerTube context as guest music search. */
class YouTubePodcasts(private val request: suspend (String, JsonObject) -> JsonObject = { endpoint, body ->
    when (endpoint) {
        "search" -> Innertube.search(body.getValue("query").jsonPrimitive.content, anonymous = true)
        else -> Innertube.browseAnonymous(body["browseId"]?.jsonPrimitive?.contentOrNull,
            body["continuation"]?.jsonPrimitive?.contentOrNull)
    }
}) {
    suspend fun search(query: String): List<PodcastShow> = withTimeout(15_000) {
        parseShows(request("search", buildJsonObject { put("query", query.trim().take(200)) }))
    }

    suspend fun home(): List<PodcastShow> = withTimeout(15_000) {
        parseShows(request("browse", buildJsonObject { put("browseId", "FEmusic_podcasts") }))
    }

    suspend fun feed(address: String, fallback: PodcastShow? = null): PodcastFeed = withTimeout(15_000) {
        val id = requireNotNull(showId(address)) { "This is not a YouTube Music podcast show." }
        val token = address.toHttpUrlOrNull()?.queryParameter("continuation")
        val body = buildJsonObject { if (token == null) put("browseId", id) else put("continuation", token) }
        val root = request("browse", body)
        if (URI(address).path == "/playlist") require(hasPodcastEvidence(root, id)) {
            "This YouTube playlist is not identified as a podcast show. Open a podcast show link instead."
        }
        parseFeed(root, id, fallback)
    }

    companion object {
        private val videoPattern = Regex("[A-Za-z0-9_-]{11}")
        private val showPattern = Regex("(?:MPSPPL|MPED)[A-Za-z0-9_-]{4,200}")
        fun videoId(itemId: String?): String? = itemId?.removePrefix("podio:episode:youtube:")
            ?.takeIf { itemId.startsWith("podio:episode:youtube:") && videoPattern.matches(it) }
        fun episodeId(videoId: String): String? = videoId.takeIf(videoPattern::matches)?.let { "podio:episode:youtube:$it" }
        fun playbackUri(itemId: String): String? = videoId(itemId)?.let { "cosmotune://podio-youtube?v=$it" }

        fun showId(address: String): String? = runCatching {
            val uri = URI(address)
            if (uri.scheme != "https" || uri.userInfo != null) return@runCatching null
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (host == "music.youtube.com" && uri.path.startsWith("/browse/")) {
                return@runCatching uri.path.removePrefix("/browse/").takeIf(showPattern::matches)
            }
            if (host !in setOf("music.youtube.com", "youtube.com", "www.youtube.com", "m.youtube.com") || uri.path != "/playlist") return@runCatching null
            val playlist = address.toHttpUrlOrNull()?.queryParameterValues("list")?.singleOrNull()
                ?.takeIf { Regex("PL[A-Za-z0-9_-]{8,128}").matches(it) } ?: return@runCatching null
            "MPSP$playlist"
        }.getOrNull()

        /** A shared PL URL can also name music; only the provider's primary show/episode evidence makes it a podcast. */
        private fun hasPodcastEvidence(root: JsonElement, id: String): Boolean {
            fun marked(element: JsonElement) = nodes(element).any { node ->
                listOf("pageType", "videoType", "musicVideoType", "headerType").any { key ->
                    (node[key] as? JsonPrimitive)?.contentOrNull?.contains("PODCAST", true) == true
                }
            }
            val header = nodes(root).mapNotNull { (it["musicResponsiveHeaderRenderer"] ?: it["musicDetailHeaderRenderer"] ?: it["musicImmersiveHeaderRenderer"]) as? JsonObject }.firstOrNull()
            return (header != null && (marked(header) || strings(header, "browseId").any { it == id })) || marked(episodeArea(root))
        }

        private fun episodeArea(root: JsonElement): JsonElement = nodes(root).mapNotNull { (it["musicShelfRenderer"] ?: it["musicPlaylistShelfRenderer"]) as? JsonObject }
            .firstOrNull { shelf -> nodes(shelf).any { "musicMultiRowListItemRenderer" in it || "musicResponsiveListItemRenderer" in it } } ?: root

        internal fun parseShows(root: JsonElement): List<PodcastShow> = nodes(root).mapNotNull { node ->
            val renderer = (node["musicTwoRowItemRenderer"] ?: node["musicResponsiveListItemRenderer"]) as? JsonObject ?: return@mapNotNull null
            val id = strings(renderer, "browseId").firstOrNull { showPattern.matches(it) } ?: return@mapNotNull null
            val title = text(renderer["title"]).ifBlank { column(renderer, 0) }.trim().take(400)
            if (title.isBlank()) return@mapNotNull null
            PodcastShow("youtube:$id", title, text(renderer["subtitle"]).ifBlank { column(renderer, 1) }.take(300),
                "https://music.youtube.com/browse/$id", artwork(renderer), source = "YouTube Music · public podcast")
        }.distinctBy { it.id }.toList()

        internal fun parseFeed(root: JsonElement, showId: String, fallback: PodcastShow? = null): PodcastFeed {
            require(showPattern.matches(showId))
            val header = nodes(root).mapNotNull { (it["musicResponsiveHeaderRenderer"] ?: it["musicDetailHeaderRenderer"] ?: it["musicImmersiveHeaderRenderer"]) as? JsonObject }.firstOrNull()
            val title = header?.let { text(it["title"]) }?.takeIf(String::isNotBlank) ?: fallback?.title ?: "Podcast"
            val address = "https://music.youtube.com/browse/$showId"
            val show = PodcastShow("youtube:$showId", title, header?.let { text(it["subtitle"]) }.orEmpty().ifBlank { fallback?.author.orEmpty() },
                address, header?.let(::artwork).orEmpty().ifBlank { fallback?.artwork.orEmpty() }, address, "YouTube Music · public podcast")
            val episodeShelf = episodeArea(root)
            val multiRows = nodes(episodeShelf).mapNotNull { it["musicMultiRowListItemRenderer"] as? JsonObject }.toList()
            val rows = multiRows.ifEmpty { nodes(episodeShelf).mapNotNull { it["musicResponsiveListItemRenderer"] as? JsonObject }.toList() }
            val episodes = rows.mapNotNull { renderer ->
                fun endpoint(name: String): String? = (((renderer[name] as? JsonObject)?.get("watchEndpoint") as? JsonObject)?.get("videoId") as? JsonPrimitive)?.contentOrNull
                val direct = endpoint("onTap") ?: endpoint("navigationEndpoint") ?: endpoint("doubleTapCommand")
                    ?: ((renderer["playlistItemData"] as? JsonObject)?.get("videoId") as? JsonPrimitive)?.contentOrNull
                    ?: (renderer["flexColumns"] as? JsonArray)?.firstOrNull()?.let { strings(it, "videoId").firstOrNull() }
                val id = direct?.takeIf(videoPattern::matches) ?: return@mapNotNull null
                val name = text(renderer["title"]).ifBlank { column(renderer, 0) }.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val subtitle = text(renderer["subtitle"]).ifBlank { column(renderer, 1) }
                val duration = (strings(renderer, "text") + sequenceOf(text(renderer["lengthText"]))).map(::durationSeconds).firstOrNull { it > 0 } ?: 0
                PodcastEpisode("youtube:$id", name.take(500), title, "https://music.youtube.com/watch?v=$id", artwork(renderer).ifBlank { show.artwork },
                    duration, subtitle.take(100), text(renderer["description"]).take(4000))
            }.distinctBy { it.id }
            val token = nodes(episodeShelf).mapNotNull { node ->
                val next = (node["nextContinuationData"] ?: node["reloadContinuationData"] ?: node["continuationCommand"]) as? JsonObject
                (next?.get("continuation") as? JsonPrimitive)?.contentOrNull ?: (next?.get("token") as? JsonPrimitive)?.contentOrNull
            }.firstOrNull { it.length in 1..16_384 }
            val next = token?.let { "$address?continuation=${java.net.URLEncoder.encode(it, "UTF-8")}" }
            return PodcastFeed(show, episodes, next, listOf(address))
        }

        private fun text(element: JsonElement?): String = when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonObject -> (element["runs"] as? JsonArray)?.joinToString("") { text((it as? JsonObject)?.get("text")) }
                ?: text(element["simpleText"])
            else -> ""
        }
        private fun durationSeconds(text: String): Long {
            if (Regex("\\d{1,3}:\\d{2}(?::\\d{2})?").matches(text.trim())) return PodioCatalogue.duration(text)
            val units = Regex("^(?:(\\d{1,3})\\s*hr(?:s|ours?)?\\s*)?(?:(\\d{1,3})\\s*min(?:s|utes?)?)?$", RegexOption.IGNORE_CASE)
                .matchEntire(text.trim()) ?: return 0
            return ((units.groupValues[1].toLongOrNull() ?: 0) * 3600 + (units.groupValues[2].toLongOrNull() ?: 0) * 60).coerceIn(0, 604800)
        }
        private fun column(renderer: JsonObject, index: Int): String = text(((((renderer["flexColumns"] as? JsonArray)?.getOrNull(index)) as? JsonObject)
            ?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)?.get("text"))
        private fun artwork(root: JsonElement): String = nodes(root).mapNotNull { it["thumbnails"] as? JsonArray }
            .firstOrNull()?.lastOrNull()?.let { (it as? JsonObject)?.get("url") as? JsonPrimitive }?.contentOrNull
            ?.let(PodioCatalogue::httpUrl).orEmpty()
        private fun strings(root: JsonElement, name: String): Sequence<String> = nodes(root).mapNotNull { (it[name] as? JsonPrimitive)?.contentOrNull }
        private fun nodes(root: JsonElement): Sequence<JsonObject> = sequence {
            val pending = ArrayDeque<JsonElement>(); pending.add(root)
            var visited = 0
            while (pending.isNotEmpty() && visited++ < 100_000) when (val next = pending.removeFirst()) {
                is JsonObject -> { yield(next); next.values.forEach(pending::addLast) }
                is JsonArray -> next.forEach(pending::addLast)
                else -> Unit
            }
        }
    }
}
