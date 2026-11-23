package com.cosmotune.podio

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** One reply in an episode's thread: who wrote it, what they wrote, when, and where it is on the web. */
data class PodioComment(val author: String, val handle: String, val text: String, val published: String, val url: String, val depth: Int = 0)

/**
 * Comments on an episode (Podcasting 2.0's "podcast:socialInteract", as AntennaPod 3.8 shows them): the replies to the post the
 * show made for the episode on the fediverse. A Mastodon-style server answers its public thread with no sign-in; any other kind of
 * thread is opened in the browser instead.
 */
object PodioComments {
    const val MAX = 60

    /** The public API address of a post's thread, or null when [uri] is not a Mastodon-style post ("https://host/@user/123"). */
    fun contextUrl(uri: String): String? = runCatching {
        val parsed = URI(uri.trim())
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null) return null
        val parts = parsed.path.orEmpty().split('/').filter(String::isNotBlank)
        val id = when {
            parts.size == 2 && parts[0].startsWith("@") -> parts[1]
            parts.size == 4 && parts[0] == "users" && parts[2] == "statuses" -> parts[3]
            else -> null
        }?.takeIf { it.matches(Regex("[0-9A-Za-z]{1,40}")) } ?: return null
        "https://${parsed.host}${if (parsed.port > 0) ":${parsed.port}" else ""}/api/v1/statuses/$id/context"
    }.getOrNull()

    /** The replies in a Mastodon thread answer ([json]), oldest first, each indented under the one it answers. */
    fun parseContext(json: String): List<PodioComment> {
        val descendants = (Json.parseToJsonElement(json).jsonObject["descendants"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val depth = HashMap<String, Int>()
        return descendants.take(MAX).mapNotNull { post ->
            val id = post.text("id")
            val parent = post.text("in_reply_to_id")
            val level = (depth[parent]?.plus(1) ?: 0).coerceAtMost(4)
            depth[id] = level
            if (post.text("visibility").let { it.isNotEmpty() && it !in setOf("public", "unlisted") }) return@mapNotNull null
            val account = post["account"] as? JsonObject
            val text = Jsoup.parse(post.text("content").replace(Regex("(?i)<br\\s*/?>|</p>\\s*<p>"), "\n")).wholeText().trim().take(2_000)
            if (text.isBlank()) return@mapNotNull null
            PodioComment(account?.text("display_name")?.ifBlank { null } ?: account?.text("username").orEmpty(),
                account?.text("acct").orEmpty().let { if (it.isBlank()) "" else "@$it" }, text, post.text("created_at"),
                PodioCatalogue.httpUrl(post.text("url")).orEmpty(), level)
        }
    }

    /** The replies to [uri]'s post; throws when the server cannot be reached or does not answer as a Mastodon server. */
    suspend fun fetch(uri: String, client: OkHttpClient = defaultClient): List<PodioComment> = withContext(Dispatchers.IO) {
        val address = contextUrl(uri) ?: error("This thread opens in the browser.")
        client.newCall(Request.Builder().url(address).header("Accept", "application/json")
            .header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build()).execute().use { reply ->
            check(reply.isSuccessful) { "The server answered ${reply.code}." }
            val body = reply.body?.string().orEmpty()
            check(body.length <= 2_000_000) { "The thread is too long to show." }
            parseContext(body)
        }
    }

    private val defaultClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
