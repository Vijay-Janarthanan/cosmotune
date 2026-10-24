package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.readAddonJsonBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Account-driven Last.fm radio candidates (LK-06).
 *
 * Built only by [configured], which requires the listener's opt-in setting, a stored Last.fm session
 * and an API key: without all three nothing is constructed, so nothing is sent. When it runs it asks
 * Last.fm for tracks similar to the radio seed (`track.getSimilar`, the seed's artist and title) and,
 * once per six hours, the account's top artists (`user.getTopArtists`, the username); candidates by
 * those artists rank first. Results are only candidates: [LocalRecommendations] resolves each through
 * the normal catalogue search and exact-recording matcher before anything can play. No session key,
 * listening history or device data is sent, and request addresses are never logged.
 */
class LastFmDiscovery internal constructor(
    private val endpoint: String,
    private val apiKey: String,
    private val username: String,
    private val client: OkHttpClient = defaultClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var topArtists: Pair<Long, Set<String>>? = null

    internal suspend fun candidates(seed: Song, limit: Int = 5): List<RecommendationModel.Candidate> = withContext(Dispatchers.IO) {
        if (seed.title.isBlank() || seed.artist.isBlank()) return@withContext emptyList()
        val favourites = favouriteArtists()
        val seedKey = RecommendationModel.normalize(seed.artist) + "\u001f" + RecommendationModel.titleKey(seed.title)
        val similar = call("track.getsimilar", "artist" to seed.artist.take(300), "track" to seed.title.take(300),
            "autocorrect" to "1", "limit" to "30")
        val tracks = ((similar?.obj("similartracks"))?.get("track") as? JsonArray).orEmpty()
        tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val title = track.text("name")?.take(300) ?: return@mapNotNull null
            val artist = (track["artist"] as? JsonObject)?.text("name")?.take(300) ?: return@mapNotNull null
            val match = (track["match"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0
            val normalized = RecommendationModel.normalize(artist)
            if (normalized + "\u001f" + RecommendationModel.titleKey(title) == seedKey) return@mapNotNull null
            val favourite = normalized in favourites
            // Last.fm states duration in seconds here; zero means unknown and leaves the matcher to the title.
            val seconds = (track["duration"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }?.takeIf { it in 1..86_400 } ?: 0
            RecommendationModel.Candidate("lastfm:$normalized\u001f${RecommendationModel.titleKey(title)}", title, artist, "", seconds,
                match.coerceIn(0.0, 1.0).toFloat(), if (favourite) 2 else 1)
        }.distinctBy { it.key }
            .sortedWith(compareByDescending<RecommendationModel.Candidate> { it.evidence }.thenByDescending { it.score })
            .take(limit.coerceIn(0, 10))
    }

    private suspend fun favouriteArtists(): Set<String> {
        topArtists?.takeIf { clock() - it.first < TOP_ARTISTS_TTL_MS }?.let { return it.second }
        val answer = call("user.gettopartists", "user" to username, "period" to "3month", "limit" to "50")
        val names = ((answer?.obj("topartists"))?.get("artist") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.text("name")?.let(RecommendationModel::normalize) }.toSet()
        // A failed lookup is retried at the next TTL, not on every radio batch.
        topArtists = clock() to names
        return names
    }

    private fun call(method: String, vararg parameters: Pair<String, String>): JsonObject? = runCatching {
        val url = endpoint.toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("method", method)
            parameters.forEach { (name, value) -> addQueryParameter(name, value) }
            addQueryParameter("api_key", apiKey)
            addQueryParameter("format", "json")
        }?.build() ?: return null
        client.newCall(Request.Builder().url(url).header("User-Agent", "Cosmotune").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            Json.parseToJsonElement(response.body.readAddonJsonBody()) as? JsonObject
        }?.takeIf { it["error"] == null }
    }.getOrNull()

    private fun JsonObject.obj(name: String) = this[name] as? JsonObject
    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        const val DEFAULT_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
        private const val TOP_ARTISTS_TTL_MS = 6 * 60 * 60 * 1000L
        private val defaultClient by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

        /**
         * The gate. Returns null (and so nothing is ever sent) unless the listener switched the
         * feature on and is signed in with a stored session, and an API key and HTTPS endpoint exist.
         */
        fun configured(optedIn: Boolean, sessionKey: String?, username: String?, apiKey: String?, endpoint: String? = null,
                       client: OkHttpClient? = null): LastFmDiscovery? {
            if (!optedIn || sessionKey.isNullOrBlank() || username.isNullOrBlank() || apiKey.isNullOrBlank()) return null
            val address = endpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
            val parsed = address.toHttpUrlOrNull() ?: return null
            if (!parsed.isHttps && parsed.host !in setOf("127.0.0.1", "localhost")) return null
            return if (client == null) LastFmDiscovery(address, apiKey.trim(), username.trim())
            else LastFmDiscovery(address, apiKey.trim(), username.trim(), client)
        }
    }
}

