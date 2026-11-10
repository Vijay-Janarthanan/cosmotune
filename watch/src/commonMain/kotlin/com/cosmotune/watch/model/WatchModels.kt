package com.cosmotune.watch.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * The canonical key of everything Watch shows, the same on every device (progress, My list, rooms):
 * - `m:tmdb:603` a film, `s:tmdb:1399` a series, `e:tmdb:1399:1:1` an episode (series id, season, episode);
 * - `m:imdb:tt0133093` / `s:imdb:tt0944947` / `e:imdb:tt0944947:1:1` when TMDB is not configured (Cinemeta ids);
 * - `m:<plugin>:<id>` / `s:<plugin>:<id>` a film or series only one plugin knows (plugin ids never hold a colon);
 * - `c:<plugin>:<channelId>` a live channel (`c:iptv:BBCOne.uk`, `c:pluto:5ca...`);
 * - `a:<plugin>:<itemId>` an item a plugin holds with no TMDB match (`a:ia:night_of_the_living_dead`).
 * Keys stay under 64 characters, the room's `videoId` limit.
 */
@Serializable
@JvmInline
value class WatchKey(val value: String) {
    val kind: Kind get() = when (value.substringBefore(':')) {
        "m" -> Kind.FILM; "s" -> Kind.SERIES; "e" -> Kind.EPISODE; "c" -> Kind.CHANNEL; else -> Kind.ITEM
    }
    /** The namespace after the kind: `tmdb`, `imdb`, a plugin id. */
    val namespace: String get() = value.split(':').getOrNull(1).orEmpty()
    /** The id inside the namespace (for an episode, the series id). Ids of other kinds may themselves hold colons. */
    val id: String get() = if (kind == Kind.EPISODE) value.split(':').getOrNull(2).orEmpty()
        else value.substringAfter(':', "").substringAfter(':', "")
    val season: Int? get() = if (kind == Kind.EPISODE) value.split(':').getOrNull(3)?.toIntOrNull() else null
    val episode: Int? get() = if (kind == Kind.EPISODE) value.split(':').getOrNull(4)?.toIntOrNull() else null
    /** The series of an episode key (`e:tmdb:1399:1:1` gives `s:tmdb:1399`). */
    val seriesKey: WatchKey? get() = if (kind == Kind.EPISODE) series(namespace, id) else null
    override fun toString() = value

    enum class Kind { FILM, SERIES, EPISODE, CHANNEL, ITEM }

    companion object {
        fun film(namespace: String, id: String) = WatchKey("m:$namespace:$id")
        fun series(namespace: String, id: String) = WatchKey("s:$namespace:$id")
        fun episode(namespace: String, seriesId: String, season: Int, episode: Int) = WatchKey("e:$namespace:$seriesId:$season:$episode")
        fun channel(plugin: String, channelId: String) = WatchKey("c:$plugin:${channelId.take(64 - 3 - plugin.length)}")
        fun item(plugin: String, itemId: String) = WatchKey("a:$plugin:${itemId.take(64 - 3 - plugin.length)}")
        fun tmdbFilm(id: Int) = film("tmdb", id.toString())
        fun tmdbSeries(id: Int) = series("tmdb", id.toString())
        fun tmdbEpisode(seriesId: Int, season: Int, episode: Int) = episode("tmdb", seriesId.toString(), season, episode)
    }
}

/** External ids. TMDB is the primary key; IMDb is what Stremio-protocol add-ons key on. */
@Serializable
data class ExternalIds(val tmdb: Int? = null, val imdb: String? = null, val tvdb: Int? = null) {
    fun merge(other: ExternalIds) = ExternalIds(tmdb ?: other.tmdb, imdb ?: other.imdb, tvdb ?: other.tvdb)
    val isEmpty: Boolean get() = tmdb == null && imdb == null && tvdb == null
}

enum class TitleType { FILM, SERIES }

/** What a poster card, a row or a search result needs. */
@Serializable
data class TitleSummary(
    val key: WatchKey,
    val type: TitleType,
    val name: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    /** TMDB vote average 0..10, or the plugin's rating. */
    val rating: Double? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMin: Int? = null,
    val ids: ExternalIds = ExternalIds(),
    /** Where the card came from: `tmdb`, `cinemeta` or a plugin id. */
    val origin: String = "tmdb",
    /** A short badge such as "FREE · PLUTO", shown on the card. */
    val badge: String? = null,
)

@Serializable data class Person(val name: String, val role: String? = null, val photoUrl: String? = null, val tmdbId: Int? = null)

/** A trailer or clip; [youTubeKey] opens in Video mode. */
@Serializable data class Trailer(val name: String, val youTubeKey: String, val official: Boolean = true)

/** TMDB "where to watch" (JustWatch data), or WatchHub's answer: a place the title streams, no playback. */
@Serializable data class WatchProvider(val name: String, val logoUrl: String? = null, val type: String = "flatrate", val link: String? = null)

/** The film or series page. */
@Serializable
data class Title(
    val summary: TitleSummary,
    val originalName: String? = null,
    val tagline: String? = null,
    val certification: String? = null,
    /** "Returning Series", "Ended", "Released". */
    val status: String? = null,
    val firstYear: Int? = null,
    val lastYear: Int? = null,
    val originalLanguage: String? = null,
    val countries: List<String> = emptyList(),
    /** Empty for a film. Specials (season 0) are last. */
    val seasons: List<Season> = emptyList(),
    val cast: List<Person> = emptyList(),
    val crew: List<Person> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val moreLikeThis: List<TitleSummary> = emptyList(),
    val whereToWatch: List<WatchProvider> = emptyList(),
) {
    val key: WatchKey get() = summary.key
    val type: TitleType get() = summary.type
}

@Serializable
data class Season(
    val seriesKey: WatchKey,
    /** 0 = specials. */
    val number: Int,
    val name: String,
    val episodeCount: Int,
    val overview: String? = null,
    val posterUrl: String? = null,
    val airDate: String? = null,
)

@Serializable
data class Episode(
    val key: WatchKey,
    val seriesKey: WatchKey,
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    /** ISO date "2019-04-14"; an episode whose date is in the future is unaired. */
    val airDate: String? = null,
    val runtimeMin: Int? = null,
    val ids: ExternalIds = ExternalIds(),
)

/** A catch-up template of an M3U or Xtream channel (Kodi pvr.iptvsimple modes: default, append, shift, flussonic, xc). */
@Serializable data class Catchup(val mode: String, val days: Int, val source: String? = null)

@Serializable
data class Channel(
    /** `c:<plugin>:<channelId>`. An iptv-org id (`BBCOne.uk`) is used as the channel id wherever a source knows it. */
    val key: WatchKey,
    /** The plugin (or user source) that lists it. */
    val pluginId: String,
    val name: String,
    val number: Int? = null,
    val logoUrl: String? = null,
    /** Categories: "News", "Movies", "Music", "Kids", "Sports", ... */
    val categories: List<String> = emptyList(),
    /** ISO 3166-1 alpha-2, lower case ("in", "uk", "us"). */
    val countries: List<String> = emptyList(),
    /** ISO 639-3 or the source's names, lower case ("tam", "eng"). */
    val languages: List<String> = emptyList(),
    /** The id inside the plugin's guide (tvg-id, XMLTV `<channel id>`, Pluto/Samsung id); null when there is no guide. */
    val epgId: String? = null,
    val tvgId: String? = null,
    val catchup: Catchup? = null,
    val isRadio: Boolean = false,
    /** iptv-org's "Not 24/7", "Geo-blocked". */
    val labels: List<String> = emptyList(),
)

/** One guide entry (XMLTV `<programme>`, Xtream EPG row, Pluto/Samsung timeline). */
@Serializable
data class EpgProgramme(
    val epgId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val episodeNum: String? = null,
    val iconUrl: String? = null,
    val rating: String? = null,
) {
    fun isOnAt(ms: Long) = ms in startMs until stopMs
    /** 0..1 of the way through at [ms]. */
    fun progressAt(ms: Long): Float = if (stopMs <= startMs) 0f else ((ms - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)
}

enum class StreamProtocol { HLS, DASH, PROGRESSIVE, LOCAL_FILE, YOUTUBE, EXTERNAL }

/** The engine's guess at a progressive container, or the probe's answer. */
enum class Container { MP4, MKV, TS, WEBM, AVI, MOV, UNKNOWN }

enum class SubtitleOrigin { EMBEDDED, STREAM, ADDON, OPENSUBTITLES, SERVER, LOCAL_FILE }

/** A subtitle: a file to load ([url]), or an embedded track the engine selects by [trackId]. */
@Serializable
data class Subtitle(
    val id: String,
    val url: String? = null,
    /** BCP-47 or ISO 639-2/3 as given ("en", "eng", "pt-BR"). */
    val language: String,
    val label: String,
    /** srt, vtt, ass, ssa, ttml; null when unknown. */
    val format: String? = null,
    val origin: SubtitleOrigin = SubtitleOrigin.STREAM,
    val hearingImpaired: Boolean = false,
    val forced: Boolean = false,
    val trackId: String? = null,
)

/** An audio track inside a stream (an MKV's English 5.1 and Hindi stereo), or an HLS rendition. */
@Serializable
data class AudioTrack(val trackId: String, val language: String? = null, val label: String, val codec: String? = null, val channels: Int? = null, val selected: Boolean = false)

/** Who offers a stream, for the "Play from" list. */
@Serializable data class StreamSource(val pluginId: String, val pluginName: String, val badge: String)

/** One playable (or, for [StreamProtocol.EXTERNAL], openable) copy of a film, an episode or a channel. */
@Serializable
data class Stream(
    val source: StreamSource,
    /** "1080p", "Direct play · HEVC", "Free with ads". */
    val label: String,
    /** The release line: size, languages, codec, "MKV · 58 GB". */
    val description: String? = null,
    /** Never written to a room unless public and https. */
    val url: String,
    val protocol: StreamProtocol,
    val container: Container = Container.UNKNOWN,
    val headers: Map<String, String> = emptyMap(),
    val height: Int? = null,
    /** "DV", "HDR10", "HLG". */
    val hdr: String? = null,
    val videoCodec: String? = null,
    val audioChannels: String? = null,
    val audioLanguages: List<String> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val sizeBytes: Long? = null,
    val filename: String? = null,
    val durationMs: Long? = null,
    /** Stremio `behaviorHints.bingeGroup`: picks the same release family for the next episode. */
    val bingeGroup: String? = null,
    val isLive: Boolean = false,
    /** True for a public stream that anyone may open (Internet Archive, an iptv-org https entry): may travel in a room. */
    val isPublic: Boolean = false,
    val hasAds: Boolean = false,
    /** A server's play session (Jellyfin PlaySessionId), for its progress reports. */
    val serverSession: String? = null,
    /** Present on a Stremio torrent offer. Its magnet is resolved locally only when played. */
    val torrentFileIndex: Int? = null,
    val torrentTrackers: List<String> = emptyList(),
) {
    val isPlayable: Boolean get() = protocol != StreamProtocol.EXTERNAL
    val isTorrent: Boolean get() = url.startsWith("magnet:?xt=urn:btih:")
    /** "4K", "1080p", "720p", "SD", from [height]. */
    val qualityBadge: String? get() = height?.let { when { it >= 2000 -> "4K"; it >= 1400 -> "1440p"; it >= 1000 -> "1080p"; it >= 700 -> "720p"; else -> "SD" } }
    fun hint() = StreamHint(source.pluginId, label, filename, sizeBytes, durationMs, bingeGroup)
}

/** What identifies a copy without its address: travels in rooms and is kept with progress to reopen the same release. */
@Serializable
data class StreamHint(
    val pluginId: String? = null,
    val label: String? = null,
    val filename: String? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val bingeGroup: String? = null,
)

@Serializable
data class WatchProgress(
    val key: WatchKey,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    /** The card shown in Continue watching (the episode's series card for an episode). */
    val summary: TitleSummary? = null,
    val episodeName: String? = null,
    val lastHint: StreamHint? = null,
) {
    /** Watched to the credits: 92% or within 3 minutes of the end. */
    val completed: Boolean get() = durationMs > 0 && (positionMs >= durationMs * 92 / 100 || durationMs - positionMs < 180_000)
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

@Serializable data class MyListEntry(val key: WatchKey, val addedAtMs: Long, val summary: TitleSummary)

/** What a plugin is asked to play. */
@Serializable
sealed interface PlayTarget {
    val key: WatchKey

    @Serializable
    data class Film(override val key: WatchKey, val ids: ExternalIds, val name: String, val year: Int? = null, val runtimeMin: Int? = null) : PlayTarget

    @Serializable
    data class EpisodeOf(
        override val key: WatchKey,
        val seriesIds: ExternalIds,
        val seriesName: String,
        val season: Int,
        val episode: Int,
        val episodeIds: ExternalIds = ExternalIds(),
        val episodeName: String? = null,
        val year: Int? = null,
    ) : PlayTarget

    @Serializable
    data class Live(val channel: Channel) : PlayTarget { override val key: WatchKey get() = channel.key }

    /** An item only one plugin knows (`a:ia:...`, a server item with no TMDB id). */
    @Serializable
    data class Item(override val key: WatchKey, val pluginId: String, val itemId: String, val name: String) : PlayTarget
}
