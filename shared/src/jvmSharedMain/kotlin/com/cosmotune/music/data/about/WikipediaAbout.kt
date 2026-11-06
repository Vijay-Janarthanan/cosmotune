package com.cosmotune.music.data.about

import com.cosmotune.music.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * "About this album" from Wikipedia's public REST API (`/api/rest_v1/page/summary/{title}`), no key and no account.
 *
 * Wikipedia names an album page after its title with a qualifier only when the plain title is taken, so the likely names
 * are tried from the most precise to the plainest, as Mochy Music's `Wikipedia.fetchAlbumInfo` does (GPL-3.0): "Title
 * (Artist album)", "Title (album)", then "Title (soundtrack)" and "Title (Artist soundtrack)" for film soundtracks, which is
 * how most Indian film albums are filed, and the plain title last. A disambiguation page is never an answer, and a page
 * found by a loose name must mention the artist or say it is an album or soundtrack, so a film or a town of the same name
 * is not shown as the album.
 */
object WikipediaAbout {

    data class Summary(
        val title: String,
        val description: String?,
        val extract: String,
        val pageUrl: String,
        val thumbnailUrl: String?,
    )

    suspend fun album(album: String, artist: String?, language: String = "en"): Summary? = withContext(Dispatchers.IO) {
        val name = album.trim().takeIf { it.isNotEmpty() } ?: return@withContext null
        val lead = artist?.split(',', '&', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        for (candidate in candidates(name, lead)) {
            val summary = fetch(candidate.title, language) ?: continue
            if (accept(summary, candidate.precise, lead)) return@withContext summary
        }
        null
    }

    internal data class Candidate(val title: String, val precise: Boolean)

    internal fun candidates(album: String, artist: String?): List<Candidate> = buildList {
        if (artist != null) add(Candidate("$album ($artist album)", precise = true))
        add(Candidate("$album (album)", precise = false))
        add(Candidate("$album (soundtrack)", precise = false))
        if (artist != null) add(Candidate("$album ($artist soundtrack)", precise = true))
        add(Candidate(album, precise = false))
    }.distinctBy { it.title }

    internal fun accept(summary: Summary, precise: Boolean, artist: String?): Boolean {
        if (summary.extract.isBlank() || summary.extract.contains("may refer to", ignoreCase = true)) return false
        if (precise) return true
        val text = "${summary.description.orEmpty()} ${summary.extract}"
        if (artist != null && text.contains(artist, ignoreCase = true)) return true
        return ALBUM_WORDS.containsMatchIn(text)
    }

    /** One page summary, or null for a missing page, a disambiguation page or any failure. */
    internal fun parse(body: String): Summary? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        if (root.string("type") == "disambiguation") return null
        val title = root.string("title") ?: return null
        val extract = root.string("extract")?.trim().orEmpty()
        val page = ((root["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)?.string("page")
            ?: "https://en.wikipedia.org/wiki/${title.replace(' ', '_')}"
        val thumbnail = (root["thumbnail"] as? JsonObject)?.string("source")
        return Summary(title, root.string("description"), extract, page, thumbnail)
    }

    private fun fetch(title: String, language: String): Summary? = runCatching {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20").replace("%28", "(").replace("%29", ")")
        val request = Request.Builder()
            .url("https://$language.wikipedia.org/api/rest_v1/page/summary/$path?redirect=true")
            .header("User-Agent", "${com.cosmotune.CosmoConfig.APP_NAME} (${com.cosmotune.CosmoConfig.PROJECT_URL})")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let(::parse)
        }
    }.getOrNull()

    private val client by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ALBUM_WORDS = Regex("""\b(album|soundtrack|EP|mixtape|compilation)\b""", RegexOption.IGNORE_CASE)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
