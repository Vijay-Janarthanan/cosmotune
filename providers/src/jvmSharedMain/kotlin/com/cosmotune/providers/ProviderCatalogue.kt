package com.cosmotune.providers

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import kotlinx.serialization.json.*

/** Provider metadata remains source-qualified; none of these IDs are sent to YouTube as video IDs. */
data class ProviderTrack(val provider: String, val id: String, val title: String, val artist: String,
    val artists: List<ArtistRef>, val album: String?, val albumId: String?, val artwork: String?,
    val durationMs: Long?, val quality: String?, val raw: JsonObject, val namespace: String = "provider",
    val artworkReference: String? = null) {
    fun song(sourceId: String = "sflx-$provider"): Song = Song(
        videoId = "src:$sourceId::$id", title = title, artist = artist, thumbnailUrl = artworkReference,
        artists = artists, albumName = album, albumId = albumId?.let { ProviderBrowseKey(provider, "album", it, namespace).encode() },
        artistId = artists.firstOrNull()?.browseId,
        durationText = durationMs?.takeIf { it > 0 }?.let { val seconds = it / 1000; "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" },
        sourceQuality = quality,
        isExplicit = ((raw["isExplicit"] ?: raw["is_explicit"] ?: raw["explicit"]) as? JsonPrimitive)?.booleanOrNull,
    )
}
data class ProviderBrowseKey(val provider: String, val kind: String, val id: String, val namespace: String = "provider") {
    init {
        require(namespace in setOf("provider", "addon"))
        require(provider.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}")))
        require(kind in setOf("album", "artist", "playlist") && id.isNotBlank() && id.length <= 8_192)
    }
    fun encode(): String = "$namespace:$provider:$kind:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
    companion object {
        fun parse(value: String): ProviderBrowseKey? = runCatching {
            require(value.length <= 48_000)
            val fields = value.split(':', limit = 4)
            require(fields.size == 4 && fields[0] in setOf("provider", "addon") && fields[1].matches(Regex("[a-z0-9][a-z0-9_-]{0,99}")) && fields[2] in setOf("album", "artist", "playlist"))
            ProviderBrowseKey(fields[1], fields[2], ProviderPackages.utf8(java.util.Base64.getUrlDecoder().decode(fields[3])), fields[0])
        }.getOrNull()
    }
}
data class ProviderCollection(val key: ProviderBrowseKey, val title: String, val subtitle: String = "", val artwork: String? = null,
    val tracks: List<ProviderTrack> = emptyList(), val collections: List<ProviderCollection> = emptyList())
data class ProviderHomeSection(val id: String, val title: String, val provider: String,
    val tracks: List<ProviderTrack> = emptyList(), val collections: List<ProviderCollection> = emptyList())

/** Bounded wire metadata normalization shared by package and HTTP add-on catalogue adapters. */
object ProviderCatalogue {
    private fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8_192 && it !in setOf("null", "undefined") }
    }
    fun track(provider: String, raw: JsonObject, namespace: String = "provider"): ProviderTrack? {
        val kind = raw.text("item_type", "itemType", "type")
        if (kind != null && kind !in setOf("track", "song")) return null
        val id = raw.text("id", "track_id")?.takeIf { it.length <= 8_192 } ?: return null
        val title = raw.text("name", "title")?.take(2_000) ?: return null
        val refs = (raw["artists"] as? JsonArray)?.mapNotNull { item -> when (item) {
            is JsonPrimitive -> item.contentOrNull?.let { ArtistRef(it) }
            is JsonObject -> item.text("name", "title")?.let { name -> ArtistRef(name,
                item.text("id", "artist_id")?.let { ProviderBrowseKey(provider, "artist", it, namespace).encode() }) }
            else -> null
        } }.orEmpty().take(100)
        val credit = refs.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }
            ?: raw.text("artist", "artists").orEmpty()
        val artists = refs.ifEmpty { listOfNotNull(credit.takeIf { it.isNotBlank() }?.let { ArtistRef(it,
            raw.text("artist_id", "artistId")?.let { id -> ProviderBrowseKey(provider, "artist", id, namespace).encode() }) }) }
        val album = raw["album"] as? JsonObject
        val duration = raw.text("duration_ms", "durationMs")?.toDoubleOrNull()?.toLong()
            ?: raw.text("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val qualityText = raw.text("audio_quality", "audioQuality", "quality", "format").orEmpty()
        val quality = when {
            (raw["atmos"] as? JsonPrimitive)?.booleanOrNull == true || Regex("atmos|e-?ac-?3[_ -]?joc|ec-?3[_ -]?joc", RegexOption.IGNORE_CASE)
                .containsMatchIn(qualityText + " " + raw.text("audioMode").orEmpty()) -> "DOLBY"
            Regex("lossless|flac|alac|hi[-_ ]?res", RegexOption.IGNORE_CASE).containsMatchIn(qualityText) -> "LOSSLESS"
            qualityText.isNotBlank() -> "HIGH"
            else -> null
        }
        return ProviderTrack(provider, id, title, credit, artists,
            raw.text("album_name", "albumName", "album_title", "albumTitle") ?: album?.text("name", "title") ?: raw.text("album"),
            raw.text("album_id", "albumId") ?: album?.text("id", "album_id"),
            raw.text("cover_url", "coverUrl", "artworkURL", "thumbnail", "images"), duration?.takeIf { it in 1..86_400_000 }, quality, raw, namespace)
    }

    fun tracks(provider: String, result: JsonElement, namespace: String = "provider"): List<ProviderTrack> {
        val values = if (result is JsonArray) result else (result as? JsonObject)?.let { root ->
            root["tracks"] as? JsonArray ?: (root["album"] as? JsonObject)?.get("tracks") as? JsonArray
                ?: root["top_tracks"] as? JsonArray ?: root["topTracks"] as? JsonArray
                ?: (root["artist"] as? JsonObject)?.get("top_tracks") as? JsonArray
                ?: (root["track"] as? JsonObject)?.let { JsonArray(listOf(it)) }
        } ?: JsonArray(emptyList())
        return values.take(1_000).mapNotNull { (it as? JsonObject)?.let { raw -> track(provider, raw, namespace) } }.distinctBy { it.id }
    }

    fun collection(provider: String, kind: String, raw: JsonObject, fallbackId: String? = null, namespace: String = "provider"): ProviderCollection? {
        val id = raw.text("id", "album_id", "artist_id", "playlist_id") ?: fallbackId ?: return null
        val title = raw.text("name", "title") ?: return null
        val songs = tracks(provider, raw, namespace).ifEmpty { (raw["top_tracks"] as? JsonArray)?.let { tracks(provider, it, namespace) }.orEmpty() }
            .map { track -> if (kind == "album") track.copy(album = track.album ?: title, albumId = track.albumId ?: id) else track }
        val related = if (kind == "artist") (raw["albums"] as? JsonArray).orEmpty().take(100).mapNotNull { album ->
            (album as? JsonObject)?.let { collection(provider, "album", it, namespace = namespace) }
        }.distinctBy { it.key } else emptyList()
        return ProviderCollection(ProviderBrowseKey(provider, kind, id, namespace), title,
            raw.text("artist", "artists", "description").orEmpty(), raw.text("cover_url", "coverUrl", "artworkURL", "images"), songs, related)
    }

    fun home(provider: String, result: JsonElement, namespace: String = "provider"): List<ProviderHomeSection> {
        val sections = (result as? JsonObject)?.get("sections") as? JsonArray ?: result as? JsonArray ?: return emptyList()
        return sections.take(20).mapIndexedNotNull { index, value ->
            val section = value as? JsonObject ?: return@mapIndexedNotNull null
            val items = section["items"] as? JsonArray ?: section["tracks"] as? JsonArray ?: JsonArray(emptyList())
            val songs = tracks(provider, items, namespace)
            val albums = items.take(100).mapNotNull { item ->
                val raw = item as? JsonObject ?: return@mapNotNull null
                val kind = raw.text("item_type", "itemType", "type")
                if (kind in setOf("album", "artist", "playlist")) collection(provider, requireNotNull(kind), raw, namespace = namespace) else null
            }
            if (songs.isEmpty() && albums.isEmpty()) return@mapIndexedNotNull null
            ProviderHomeSection(section.text("id") ?: "section-$index", section.text("title", "name") ?: "From $provider", provider, songs, albums)
        }
    }
}
