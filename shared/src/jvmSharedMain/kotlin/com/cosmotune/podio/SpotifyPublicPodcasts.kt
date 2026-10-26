package com.cosmotune.podio

import java.net.URI
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anonymous podcast metadata, adapted from Votify, Copyright (c) 2024 Glomatico (MIT).
 * Full permission notice: resources/podio/Votify-LICENSE.txt. Audio is matched to publisher RSS;
 * direct Spotify streams, licence requests and decryption are not implemented.
 */
internal class SpotifyPublicPodcasts(private val fetch: suspend (Request) -> String) {
    private val lock = Mutex()
    private var token = ""
    private var clientToken = ""
    private var expiresAt = 0L
    private suspend fun request(url: String, body: JsonObject? = null, authorized: Boolean = false): JsonObject {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json")
            .header("Accept-Language", "en-US").header("Content-Type", "application/json")
            .header("sec-ch-ua", "\"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"127\", \"Chromium\";v=\"127\"")
            .header("sec-ch-ua-mobile", "?0").header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty").header("sec-fetch-mode", "cors").header("sec-fetch-site", "same-site")
            .header("Origin", "https://open.spotify.com/").header("Referer", "https://open.spotify.com/").header("Priority", "u=1, i")
            .header("spotify-app-version", CLIENT_VERSION).header("app-platform", "WebPlayer")
        if (authorized) { builder.header("Authorization", "Bearer $token"); builder.header("client-token", clientToken) }
        if (body != null) builder.post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
        return Json.parseToJsonElement(fetch(builder.build())).jsonObject
    }
    private suspend fun authorize() = lock.withLock {
        if (expiresAt > System.currentTimeMillis() + 30_000 && token.isNotEmpty()) return@withLock
        val secrets = request("https://git.gay/thereallo/totp-secrets/raw/branch/main/secrets/secretDict.json")
        val version = secrets.keys.filter { it.toIntOrNull() != null }.maxBy { it.toInt() }
        val values = secrets.getValue(version).jsonArray
        val key = values.mapIndexed { index, value -> (value.jsonPrimitive.int xor ((index % 33) + 9)).toString() }.joinToString("").toByteArray(Charsets.US_ASCII)
        val serverTime = request("https://open.spotify.com/api/server-time").getValue("serverTime").jsonPrimitive.long
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(serverTime / 30).array())
        val offset = hash.last().toInt() and 15
        val binary = ((hash[offset].toInt() and 127) shl 24) or ((hash[offset+1].toInt() and 255) shl 16) or
            ((hash[offset+2].toInt() and 255) shl 8) or (hash[offset+3].toInt() and 255)
        val totp = (binary % 1_000_000).toString().padStart(6, '0')
        val address = "https://open.spotify.com/api/token".toHttpUrl().newBuilder().addQueryParameter("reason", "init")
            .addQueryParameter("productType", "web-player").addQueryParameter("totp", totp).addQueryParameter("totpServer", totp).addQueryParameter("totpVer", version).build()
        val session = request(address.toString())
        require(session["isAnonymous"] != JsonPrimitive(false)) { "An anonymous podcast session was not available" }
        val client = request("https://clienttoken.spotify.com/v1/clienttoken", buildJsonObject { putJsonObject("client_data") {
            put("client_version", CLIENT_VERSION); put("client_id", session.getValue("clientId")); putJsonObject("js_sdk_data") { }
        } })
        val newToken = session.getValue("accessToken").jsonPrimitive.content
        val newClientToken = client.getValue("granted_token").jsonObject.getValue("token").jsonPrimitive.content
        token = newToken; clientToken = newClientToken
        expiresAt = session.getValue("accessTokenExpirationTimestampMs").jsonPrimitive.long
    }
    private suspend fun operation(name: String, hash: String, variables: JsonObject): JsonObject {
        authorize()
        val response = request("https://api-partner.spotify.com/pathfinder/v2/query", buildJsonObject {
            put("operationName", name); put("variables", variables)
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) } }
        }, authorized = true)
        require(response["errors"] == null) { "This public podcast is unavailable from the source" }
        return response.getValue("data").jsonObject
    }
    private suspend fun episode(id: String): JsonObject = operation("getEpisodeOrChapter", EPISODE_HASH,
        buildJsonObject { put("uri", "spotify:episode:$id") }).getValue("episodeUnionV2").jsonObject
    suspend fun feed(url: String): PodcastFeed {
        val (kind, id) = requireNotNull(link(url))
        if (kind == "episode") {
            val data = episode(id)
            val show = data["podcastV2"]?.jsonObject?.get("data") as? JsonObject
            val name = show?.text("name").orEmpty().ifBlank { "Spotify podcast" }
            val info = PodcastShow("spotify:$id", name, "", url, image(data), url, "Spotify public podcasts")
            return PodcastFeed(info, listOf(toEpisode(data, id, name)))
        }
        val data = operation("queryPodcastEpisodes", SHOW_HASH, buildJsonObject { put("uri", "spotify:show:$id"); put("offset", 0); put("limit", 100) })
            .getValue("podcastUnionV2").jsonObject
        val title = data.text("name").ifBlank { "Spotify podcast" }
        val show = PodcastShow("spotify:$id", title, (data["publisher"] as? JsonObject)?.text("name").orEmpty(), url, image(data), url, "Spotify public podcasts")
        val entries = (data["episodesV2"] as? JsonObject)?.get("items") as? JsonArray
        val episodes = entries.orEmpty().mapNotNull { entry ->
            val entity = entry.jsonObject["entity"] as? JsonObject ?: return@mapNotNull null
            val item = entity["data"] as? JsonObject ?: entity
            val uri = item.text("uri").ifBlank { entity.text("_uri") }
            val episodeId = uri.removePrefix("spotify:episode:").takeIf { uri.startsWith("spotify:episode:") && it.matches(Regex("[A-Za-z0-9]{22}")) } ?: return@mapNotNull null
            toEpisode(item, episodeId, title)
        }.distinctBy { it.id }
        return PodcastFeed(show, episodes)
    }
    private fun toEpisode(data: JsonObject, id: String, show: String): PodcastEpisode = PodcastEpisode(
        "spotify:$id", data.text("name").ifBlank { "Podcast episode" }, show, "https://open.spotify.com/episode/$id", image(data),
        ((data["duration"] as? JsonObject)?.get("totalMilliseconds") as? JsonPrimitive)?.longOrNull?.div(1000) ?: 0,
        (data["releaseDate"] as? JsonObject)?.text("isoString").orEmpty(), data.text("description").take(4000))
    private fun image(data: JsonObject): String {
        val art = data["coverArt"] as? JsonObject ?: return ""
        return (art["sources"] as? JsonArray).orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.text("url")?.let(PodioCatalogue::httpUrl) }.orEmpty()
    }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    companion object {
        private const val CLIENT_VERSION = "1.2.87.27.ga2033a72"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        private const val SHOW_HASH = "8e2826c5993383566cc08bf9f5d3301b69513c3f6acb8d706286855e57bf44b2"
        private const val EPISODE_HASH = "8a62dbdeb7bd79605d7d68b01bcdf83f08bc6c6287ee1665ba012c748a4cf1f3"
        fun link(raw: String): Pair<String, String>? = runCatching {
            val uri = URI(raw)
            if (uri.host?.lowercase() != "open.spotify.com" || uri.userInfo != null || uri.scheme != "https") return@runCatching null
            val match = Regex("/(?:intl-[^/]+/)?(show|episode)/([A-Za-z0-9]{22})/?").matchEntire(uri.path) ?: return@runCatching null
            match.groupValues[1] to match.groupValues[2]
        }.getOrNull()
    }
}
