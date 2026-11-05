package com.opencanvas.core.stream

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * An unbadged label's channel must link a verified publisher that features it back.
 * Channel names, subscriber counts, self-written descriptions and one-way links are insufficient.
 * Only public YouTube pages are fetched; motion and audio verification remain mandatory afterwards.
 */
internal class PublisherChannelProof(private val http: OkHttpClient) {
    private data class Cached(val proved: Boolean, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun proves(channelId: String): Boolean {
        if (!channelId.matches(Regex("UC[A-Za-z0-9_-]{22}"))) return false
        val now = System.currentTimeMillis()
        cache[channelId]?.takeIf { now - it.at < if (it.proved) 86_400_000L else 60_000L }?.let { return it.proved }
        val child = page("https://www.youtube.com/channel/$channelId/about")
        val proved = child != null && metadata(child)?.get("externalId")?.text() == channelId &&
            channelLinks(child).take(2).any { parentUrl ->
                page(parentUrl)?.let { parent -> corroborates(parent, channelId) } == true
            }
        if (cache.size > 128) cache.clear()
        cache[channelId] = Cached(proved, now)
        return proved
    }

    private suspend fun page(url: String): JsonObject? = tryOrNull {
        val answer = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Language", "en-US,en;q=0.9").build()).within(2_000).fetch()
        if (!answer.isSuccessful) null else parsePage(answer.text())
    }

    companion object {
        fun parsePage(html: String): JsonObject? {
            val marker = "var ytInitialData = "
            val start = html.indexOf(marker).takeIf { it >= 0 } ?: return null
            val end = html.indexOf(";</script>", start).takeIf { it >= 0 } ?: return null
            return runCatching { Json.parseToJsonElement(html.substring(start + marker.length, end)) as? JsonObject }.getOrNull()
        }
        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun objects(value: JsonElement?): Sequence<JsonObject> = sequence {
            when (value) {
                is JsonObject -> { yield(value); value.values.forEach { yieldAll(objects(it)) } }
                is JsonArray -> value.forEach { yieldAll(objects(it)) }
                else -> Unit
            }
        }
        private fun metadata(root: JsonObject): JsonObject? = objects(root["metadata"]).firstNotNullOfOrNull { it["channelMetadataRenderer"] as? JsonObject }

        fun channelLinks(root: JsonObject): List<String> = objects(root).flatMap { node ->
            val link = node["channelExternalLinkViewModel"] ?: return@flatMap emptySequence()
            objects(link).mapNotNull { (it["urlEndpoint"] as? JsonObject)?.get("url")?.text() }
        }.mapNotNull { raw ->
            val url = raw.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.scheme != "https" || url.host !in setOf("www.youtube.com", "youtube.com") || url.encodedQuery != null) return@mapNotNull null
            val path = url.encodedPath.trimEnd('/')
            if (!path.matches(Regex("/@[A-Za-z0-9_.-]+|/channel/UC[A-Za-z0-9_-]{22}"))) return@mapNotNull null
            "https://www.youtube.com$path"
        }.distinct().toList()

        fun corroborates(parent: JsonObject, childId: String): Boolean {
            val name = metadata(parent)?.get("title")?.text().orEmpty().lowercase()
            if (listOf("music", "records", "label", "film", "movie", "vevo", "entertainment").none { name.contains(it) }) return false
            val header = parent["header"] as? JsonObject ?: return false
            val modernTitle = objects(header).firstNotNullOfOrNull { it["pageHeaderViewModel"] as? JsonObject }?.get("title")
            val legacyBadges = objects(header).firstNotNullOfOrNull { it["c4TabbedHeaderRenderer"] as? JsonObject }?.get("badges")
            val verified = objects(modernTitle).any { it["imageName"]?.text() == "CHECK_CIRCLE_FILLED" } ||
                objects(legacyBadges).any { it["style"]?.text() in setOf("BADGE_STYLE_TYPE_VERIFIED", "BADGE_STYLE_TYPE_VERIFIED_ARTIST") }
            if (!verified) return false
            // Only the channel page's own featured-channel shelves, never watch recommendations or badges on a child.
            return objects(parent["contents"]).any { node ->
                val shelf = node["shelfRenderer"] as? JsonObject ?: return@any false
                objects(shelf["content"]).any { entry ->
                    (entry["gridChannelRenderer"] as? JsonObject)?.get("channelId")?.text() == childId
                }
            }
        }
    }
}
