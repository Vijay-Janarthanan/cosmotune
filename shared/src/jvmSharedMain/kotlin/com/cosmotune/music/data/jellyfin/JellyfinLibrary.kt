package com.cosmotune.music.data.jellyfin

import com.cosmotune.music.data.Http
import com.cosmotune.music.data.model.PlaybackSourceType
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** A signed-in Jellyfin server: its address, the user, and the access token the server gave (never the password). */
data class JellyfinSession(val url: String, val userId: String, val token: String) {
    fun encode(): String = listOf(url, userId, token).joinToString("\n")
    override fun toString() = "JellyfinSession(url=$url)"

    companion object {
        fun decode(raw: String): JellyfinSession? {
            val parts = raw.split('\n')
            if (parts.size != 3 || parts.any { it.isBlank() }) return null
            return JellyfinLibrary.normalizeUrl(parts[0])?.let { JellyfinSession(it, parts[1], parts[2]) }
        }
    }
}

/**
 * The music on the listener's own Jellyfin server, as a library beside WebDAV and SMB (Namida plays Jellyfin too): sign in once
 * with the server's user name and password, which are sent to that server only and not kept; the token it answers with lists
 * the songs and streams the original files. Uses Jellyfin's public REST API.
 */
object JellyfinLibrary {
    const val BROWSE_ID = "local:jellyfin"
    const val SOURCE = "Jellyfin"
    private const val PREFIX = "jellyfin:"
    private const val MAX_SONGS = 20_000
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun owns(videoId: String) = videoId.startsWith(PREFIX)

    fun normalizeUrl(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if ("://" !in text) text = "http://$text"
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val path = uri.rawPath.orEmpty().substringBefore("/web/").trimEnd('/')
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://${uri.host}$port$path"
    }

    private fun authorization(token: String? = null) = buildString {
        append("MediaBrowser Client=\"Cosmotune\", Device=\"Cosmotune\", DeviceId=\"cosmotune-library\", Version=\"1\"")
        if (token != null) append(", Token=\"$token\"")
    }

    /** Signs in and returns the session; a wrong password or an address that is not Jellyfin fails with a plain message. */
    suspend fun signIn(url: String, user: String, password: String): Result<JellyfinSession> = withContext(Dispatchers.IO) {
        runCatching {
            val base = requireNotNull(normalizeUrl(url)) { "Check the server address." }
            val body = buildJsonObject { put("Username", user.trim()); put("Pw", password) }.toString()
            val request = Request.Builder().url("$base/Users/AuthenticateByName")
                .header("Authorization", authorization()).header("X-Emby-Authorization", authorization())
                .post(body.toRequestBody("application/json".toMediaType())).build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 401) throw IOException("That user name and password were not accepted.")
                if (!response.isSuccessful) throw IOException("The server said no (${response.code}). Is it a Jellyfin server?")
                parseSignIn(base, response.body?.string().orEmpty()) ?: throw IOException("That address did not answer like a Jellyfin server.")
            }
        }
    }

    internal fun parseSignIn(base: String, body: String): JellyfinSession? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val token = root["AccessToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val user = root["User"]?.jsonObject?.get("Id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        JellyfinSession(base, user, token)
    }.getOrNull()

    /** Every song in the user's libraries, album by album. */
    suspend fun songs(session: JellyfinSession): Result<List<Song>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${session.url}/Users/${enc(session.userId)}/Items?IncludeItemTypes=Audio&Recursive=true" +
                "&SortBy=AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName&Fields=ProductionYear&Limit=$MAX_SONGS"
            val request = Request.Builder().url(url).header("Authorization", authorization(session.token))
                .header("X-Emby-Token", session.token).build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 401) throw IOException("Sign in to your Jellyfin server again.")
                if (!response.isSuccessful) throw IOException("The server said no (${response.code}).")
                parseSongs(session, response.body?.string().orEmpty())
            }
        }
    }

    internal fun parseSongs(session: JellyfinSession, body: String): List<Song> {
        val items = runCatching { json.parseToJsonElement(body).jsonObject["Items"] as? JsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = item[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
            val id = text("Id") ?: return@mapNotNull null
            val artists = runCatching { item["Artists"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) } }.getOrNull().orEmpty()
            val seconds = item["RunTimeTicks"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }?.div(10_000_000L)?.takeIf { it > 0 }
            val albumId = text("AlbumId")
            val cover = when {
                albumId != null && text("AlbumPrimaryImageTag") != null -> "${session.url}/Items/${enc(albumId)}/Images/Primary?maxHeight=512"
                item["ImageTags"]?.let { runCatching { it.jsonObject["Primary"] }.getOrNull() } != null -> "${session.url}/Items/${enc(id)}/Images/Primary?maxHeight=512"
                else -> null
            }
            Song(
                videoId = PREFIX + id,
                title = text("Name") ?: "Untitled",
                artist = artists.joinToString(", ").ifBlank { text("AlbumArtist") ?: "Unknown Artist" },
                thumbnailUrl = cover,
                durationText = seconds?.let { "%d:%02d".format(it / 60, it % 60) },
                albumName = text("Album"),
                localUri = streamUrl(session, PREFIX + id),
                playbackSource = SOURCE,
                playbackSourceType = PlaybackSourceType.BROWSE,
                playbackSourceId = BROWSE_ID,
                trackNumber = item["IndexNumber"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
            )
        }
    }

    /** The original file, as the server keeps it; the token rides in the address so every player can open it. */
    fun streamUrl(session: JellyfinSession, videoId: String): String? =
        videoId.takeIf(::owns)?.removePrefix(PREFIX)?.let { "${session.url}/Audio/${enc(it)}/stream?static=true&api_key=${enc(session.token)}" }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
