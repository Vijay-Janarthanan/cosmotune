package com.cosmotune.providers

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.subsonic.SubsonicClient
import com.cosmotune.music.data.subsonic.SubsonicCredential
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** What a Subsonic server says about one file before it is opened. The [url] carries credentials: never store or log it. */
class SubsonicStream(val url: String, val codec: String?, val kbps: Int?, val sampleRateHz: Int?, val bitDepth: Int?,
    val durationSec: Int?) {
    val lossless: Boolean get() = codec in SubsonicCatalogue.LOSSLESS_SUFFIXES
    override fun toString() = "SubsonicStream(codec=$codec, kbps=$kbps)"
}

/**
 * A configured Subsonic/OpenSubsonic server seen through the same catalogue shape the HTTP add-ons use
 * (`manifest`, `search`, `album`, `artist`), so both hosts reuse the shared catalogue browser, album
 * links and source-qualified track IDs (`src:<sourceId>::<subsonic id>`). Artwork is left out on
 * purpose: a Subsonic cover address carries the listener's credentials.
 */
class SubsonicCatalogue(val sourceId: String, val client: SubsonicClient) {

    suspend fun load(resource: String, id: String): JsonObject = when (resource) {
        "manifest" -> buildJsonObject {
            put("name", "Subsonic")
            putJsonArray("resources") { add("album"); add("artist") }
        }
        "search" -> {
            val result = client.searchAll(id)
            buildJsonObject {
                put("tracks", JsonArray(objects(result["song"]).filterNot(::isVideo).map(::track)))
                put("albums", JsonArray(objects(result["album"]).map(::album)))
                put("artists", JsonArray(objects(result["artist"]).map { artist(it, includeAlbums = false) }))
            }
        }
        "album" -> album(client.albumObject(id))
        "artist" -> artist(client.artistObject(id), includeAlbums = true)
        else -> throw IllegalArgumentException("Unsupported catalogue resource")
    }

    /** Songs matching [query], already source-qualified and playable through the host's registry. */
    suspend fun searchSongs(query: String, limit: Int = 25): List<Song> {
        if (query.isBlank()) return emptyList()
        val result = client.searchAll(query, songs = limit.coerceIn(1, 500), albums = 0, artists = 0)
        return ProviderCatalogue.tracks(sourceId, JsonArray(objects(result["song"]).filterNot(::isVideo).map(::track)), NAMESPACE)
            .take(limit).map(::song)
    }

    /** One album or artist page, in the shape the hosts' album/artist screens draw. */
    suspend fun page(key: ProviderBrowseKey): SourceCataloguePage {
        require(key.namespace == NAMESPACE && key.provider == sourceId) { "This page belongs to another source" }
        val raw = load(key.kind, key.id)
        val collection = requireNotNull(ProviderCatalogue.collection(sourceId, key.kind, raw, key.id, NAMESPACE)) { "Incomplete source catalogue page" }
        return SourceCataloguePage(collection, collection.tracks.map(::song))
    }

    fun song(track: ProviderTrack): Song = track.song(sourceId).copy(
        sourceQuality = if (track.quality == "LOSSLESS") "LOSSLESS" else track.quality,
        downloadFormat = (track.raw["suffix"] as? JsonPrimitive)?.contentOrNull,
    )

    /** The track's own server ID inside a source-qualified key, or null for anything else. */
    fun owns(videoId: String): String? = com.cosmotune.music.data.sources.SourceTrackKeys.parse(videoId)
        ?.takeIf { it.first == sourceId }?.second

    /** Asks the server what the file is, then hands back the address to open. */
    suspend fun stream(trackId: String): SubsonicStream {
        val info = client.songObject(trackId)
        fun int(name: String) = (info[name] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        return SubsonicStream(client.streamUrl(trackId), (info["suffix"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.take(12),
            int("bitRate"), int("samplingRate"), int("bitDepth"), int("duration"))
    }

    private fun isVideo(o: JsonObject) = (o["isVideo"] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun track(o: JsonObject): JsonObject = buildJsonObject {
        o.text("id")?.let { put("id", it) }
        o.text("title")?.let { put("title", it) }
        o.text("artist")?.let { put("artist", it) }
        o.text("artistId")?.let { put("artist_id", it) }
        o.text("album")?.let { put("album_name", it) }
        o.text("albumId")?.let { put("album_id", it) }
        (o["duration"] as? JsonPrimitive)?.intOrNull?.let { put("duration", it) }
        val suffix = o.text("suffix")?.lowercase()
        suffix?.let { put("suffix", it) }
        if (suffix != null) put("audio_quality", if (suffix in LOSSLESS_SUFFIXES) "LOSSLESS" else suffix.uppercase())
        put("item_type", "track")
    }

    private fun album(o: JsonObject): JsonObject = buildJsonObject {
        o.text("id")?.let { put("id", it) }
        (o.text("name") ?: o.text("title"))?.let { put("name", it) }
        o.text("artist")?.let { put("artist", it) }
        put("item_type", "album")
        val songs = objects(o["song"])
        if (songs.isNotEmpty()) put("tracks", JsonArray(songs.filterNot(::isVideo).map(::track)))
    }

    private fun artist(o: JsonObject, includeAlbums: Boolean): JsonObject = buildJsonObject {
        o.text("id")?.let { put("id", it) }
        o.text("name")?.let { put("name", it) }
        put("item_type", "artist")
        if (includeAlbums) put("albums", JsonArray(objects(o["album"]).map(::album)))
    }

    companion object {
        /** Subsonic sources share the HTTP add-on browse namespace; the host routes by the configured kind. */
        const val NAMESPACE = "addon"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_API_KEY = "apiKey"
        val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")

        private fun objects(element: JsonElement?): List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }.take(5_000)

        /** The credential the stored settings describe: an API key when one is saved, else user name and password. */
        fun credential(settings: Map<String, String>): SubsonicCredential? =
            settings[KEY_API_KEY]?.takeIf { it.isNotBlank() }?.let(SubsonicCredential::ApiKey)
                ?: settings[KEY_PASSWORD]?.takeIf { it.isNotEmpty() }?.let(SubsonicCredential::Password)

        /** A catalogue for a stored source, or null while its address or credential is missing. */
        fun forSource(sourceId: String, baseUrl: String, settings: Map<String, String>, http: OkHttpClient): SubsonicCatalogue? {
            val credential = credential(settings) ?: return null
            return runCatching { SubsonicCatalogue(sourceId, SubsonicClient(baseUrl, settings[KEY_USER].orEmpty(), credential, http)) }.getOrNull()
        }

        /** Whether a stored source has what it needs to be contacted. */
        fun isComplete(baseUrl: String, settings: Map<String, String>): Boolean =
            SubsonicClient.normalize(baseUrl) != null && credential(settings).let { it is SubsonicCredential.ApiKey ||
                (it is SubsonicCredential.Password && !settings[KEY_USER].isNullOrBlank()) }
    }
}
