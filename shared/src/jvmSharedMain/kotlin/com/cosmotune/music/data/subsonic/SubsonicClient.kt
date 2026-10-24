package com.cosmotune.music.data.subsonic

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/** A listener's own server credential. Kept in memory or the platform secret store; never written to listening data. */
sealed interface SubsonicCredential {
    /** Classic Subsonic token authentication: md5(password + salt), with a fresh salt per request. */
    class Password(val value: String) : SubsonicCredential { override fun toString() = "Password(****)" }
    /** OpenSubsonic `apiKeyAuthentication` extension. */
    class ApiKey(val value: String) : SubsonicCredential { override fun toString() = "ApiKey(****)" }
}

class SubsonicException(val code: Int, message: String) : Exception(message)

data class SubsonicServerInfo(val version: String, val type: String?, val openSubsonic: Boolean, val extensions: List<String>)

/**
 * Minimal Subsonic / OpenSubsonic REST client (ping, search, random/newest albums, album tracks,
 * stream address). Plain HTTP and JSON over the app's OkHttp transport; no third-party SDK.
 * Redirects are not followed, so credentials in a query string never reach another host, and
 * responses are bounded.
 */
class SubsonicClient(
    baseUrl: String,
    private val username: String,
    private val credential: SubsonicCredential,
    http: OkHttpClient,
    private val random: SecureRandom = SecureRandom(),
) {
    private val base: HttpUrl = normalize(baseUrl) ?: throw IllegalArgumentException("Enter the server address, starting with http:// or https://")
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init { require(username.isNotBlank() || credential is SubsonicCredential.ApiKey) { "Enter your user name." } }

    /** A stable, credential-free key for this server/account, used in source-qualified track IDs. */
    val serverKey: String = sha256("${base}|${username.lowercase()}").take(12)

    suspend fun ping(): SubsonicServerInfo {
        val body = call("ping")
        val extensions = if (body["openSubsonic"]?.jsonPrimitive?.booleanOrNull == true)
            runCatching { call("getOpenSubsonicExtensions")["openSubsonicExtensions"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } }.getOrNull().orEmpty()
        else emptyList()
        return SubsonicServerInfo(body["version"]?.jsonPrimitive?.contentOrNull.orEmpty(), body["type"]?.jsonPrimitive?.contentOrNull,
            body["openSubsonic"]?.jsonPrimitive?.booleanOrNull == true, extensions)
    }

    suspend fun search(query: String, limit: Int = 50): List<Song> {
        require(query.isNotBlank()) { "Enter something to search for." }
        val body = call("search3", "query" to query.trim().take(200), "songCount" to limit.coerceIn(1, 500).toString(), "artistCount" to "0", "albumCount" to "0")
        return body["searchResult3"]?.jsonObject?.get("song").songs()
    }

    suspend fun randomSongs(size: Int = 50): List<Song> =
        call("getRandomSongs", "size" to size.coerceIn(1, 500).toString())["randomSongs"]?.jsonObject?.get("song").songs()

    suspend fun album(id: String): List<Song> =
        call("getAlbum", "id" to id)["album"]?.jsonObject?.get("song").songs()

    /** The address a player opens for [songId]. Contains credentials: hand it to the decoder, never persist it. */
    fun streamUrl(songId: String): String = authenticated("stream", "id" to songId, "format" to "raw").toString()

    fun ownsTrack(videoId: String): String? = videoId.takeIf { it.startsWith("subsonic:$serverKey:") }?.substringAfter("subsonic:$serverKey:")

    private fun JsonElement?.songs(): List<Song> {
        val items = when (this) { is JsonArray -> this; is JsonObject -> JsonArray(listOf(this)); else -> JsonArray(emptyList()) }
        return items.take(5_000).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return@mapNotNull null
            if (o["isVideo"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val seconds = o["duration"]?.jsonPrimitive?.intOrNull
            Song(
                videoId = "subsonic:$serverKey:$id",
                title = o["title"]?.jsonPrimitive?.contentOrNull?.take(500) ?: return@mapNotNull null,
                artist = o["artist"]?.jsonPrimitive?.contentOrNull?.take(500).orEmpty(),
                thumbnailUrl = null,
                durationText = seconds?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
                albumName = o["album"]?.jsonPrimitive?.contentOrNull?.take(500),
                sourceQuality = listOfNotNull(o["suffix"]?.jsonPrimitive?.contentOrNull?.uppercase()?.take(12),
                    o["bitRate"]?.jsonPrimitive?.intOrNull?.let { "$it kbps" }).joinToString(" ").ifBlank { null },
                downloadFormat = o["suffix"]?.jsonPrimitive?.contentOrNull?.lowercase()?.take(12),
            )
        }
    }

    private fun authenticated(method: String, vararg params: Pair<String, String>): HttpUrl = base.newBuilder().apply {
        addPathSegment("rest"); addPathSegment("$method.view")
        params.forEach { (k, v) -> addQueryParameter(k, v) }
        when (credential) {
            is SubsonicCredential.Password -> {
                val salt = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                addQueryParameter("u", username); addQueryParameter("t", md5(credential.value + salt)); addQueryParameter("s", salt)
            }
            is SubsonicCredential.ApiKey -> addQueryParameter("apiKey", credential.value)
        }
        addQueryParameter("v", "1.16.1"); addQueryParameter("c", "Cosmotune"); addQueryParameter("f", "json")
    }.build()

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(authenticated(method, *params)).build()).execute().use { response ->
            if (response.isRedirect) throw SubsonicException(0, "The server redirected the request. Enter its final address.")
            if (!response.isSuccessful) throw SubsonicException(response.code, "The server answered HTTP ${response.code}.")
            val source = response.body.source()
            if (!source.request(MAX_BYTES + 1)) Unit else throw SubsonicException(0, "The server response is too large.")
            val text = source.buffer.readUtf8()
            val root = runCatching { Json.parseToJsonElement(text).jsonObject["subsonic-response"]?.jsonObject }.getOrNull()
                ?: throw SubsonicException(0, "This does not look like a Subsonic server.")
            if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
                val error = root["error"]?.jsonObject
                val code = error?.get("code")?.jsonPrimitive?.intOrNull ?: 0
                throw SubsonicException(code, when (code) {
                    40 -> "Wrong user name or password."
                    41 -> "This server does not accept token sign-in. Use an API key if it offers one."
                    42, 43, 44 -> "The server does not support this sign-in method."
                    50 -> "This account may not do that on the server."
                    70 -> "The server could not find that item."
                    else -> "The server reported an error (code $code)."
                })
            }
            root
        }
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024

        fun normalize(raw: String): HttpUrl? {
            val url = raw.trim().removeSuffix("/").toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            // Accept either the server root or an address already ending in /rest.
            val segments = url.pathSegments.filter { it.isNotEmpty() }.let { if (it.lastOrNull() == "rest") it.dropLast(1) else it }
            return url.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build()
        }

        internal fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
