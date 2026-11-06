package com.cosmotune.watch

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.PartyWatch
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugins.StreamGuess
import kotlin.math.abs

/**
 * A Watch film, episode or channel as a room item, and back. The room carries the key (`m:tmdb:603`, `e:tmdb:1399:1:2`,
 * `c:iptv:BBCOne.uk`), the titles and artwork, a [PartyWatch] with the ids and the host's release (length, size, file name,
 * binge group, the kind of plugin) and an https address only when the host's copy is a public stream (Internet Archive, an
 * iptv-org or free-service channel). Never a server, a playlist, a token or an address with a secret in it. Each member's
 * device finds its own copy from its own plugins, preferring the one that matches the host's release.
 */
object WatchRoom {
    /** Copies that differ by more than this are a different cut, and the member is told. */
    const val LENGTH_TOLERANCE_MS = 60_000L

    fun trackOf(target: PlayTarget, summary: TitleSummary?, stream: Stream?, durationMs: Long? = null): PartyTrack {
        val key = target.key.value.take(64)
        val length = (durationMs ?: stream?.durationMs)?.takeIf { it > 0 }
        val publicUrl = stream?.takeIf { it.isPublic && it.isPlayable && it.protocol != StreamProtocol.YOUTUBE && it.headers.isEmpty() }?.url
            ?.takeIf(::shareable)
        val (title, artist) = when (target) {
            is PlayTarget.Film -> target.name to listOfNotNull(target.year?.toString(), target.runtimeMin?.let(::runtime)).joinToString(" · ")
            is PlayTarget.EpisodeOf -> target.seriesName to listOfNotNull("S${target.season} · E${target.episode}", target.episodeName).joinToString(" · ")
            is PlayTarget.Live -> target.channel.name to "Live TV"
            is PlayTarget.Item -> target.name to ""
        }
        val ids = when (target) { is PlayTarget.Film -> target.ids; is PlayTarget.EpisodeOf -> target.seriesIds; else -> summary?.ids ?: ExternalIds() }
        val watch = PartyWatch(
            tmdb = ids.tmdb, imdb = ids.imdb?.take(16),
            season = (target as? PlayTarget.EpisodeOf)?.season, episode = (target as? PlayTarget.EpisodeOf)?.episode,
            year = (target as? PlayTarget.Film)?.year ?: (target as? PlayTarget.EpisodeOf)?.year ?: summary?.year,
            runtimeMin = (target as? PlayTarget.Film)?.runtimeMin ?: summary?.runtimeMin,
            size = stream?.sizeBytes, name = stream?.filename?.take(200), durationMs = length, binge = stream?.bingeGroup?.take(100),
            plugin = stream?.let { pluginKindOf(it) },
            tvgId = (target as? PlayTarget.Live)?.channel?.tvgId?.take(64), channel = (target as? PlayTarget.Live)?.channel?.name?.take(80),
        )
        return PartyTrack(
            videoId = key, title = title.take(200), artist = artist.take(200),
            thumbnailUrl = (summary?.posterUrl ?: (target as? PlayTarget.Live)?.channel?.logoUrl)?.takeIf { it.length <= 500 && it.startsWith("http") },
            durationMs = length.takeIf { target !is PlayTarget.Live }, kind = PartyTrack.KIND_WATCH, url = publicUrl, watch = watch,
        )
    }

    /** "jellyfin", "stremio", "archive": the kind of plugin, never which one or where. */
    private fun pluginKindOf(stream: Stream): String = when {
        stream.source.pluginId.startsWith("jf-") -> "jellyfin"
        stream.source.pluginId.startsWith("em-") -> "emby"
        stream.source.pluginId.startsWith("px-") -> "plex-server"
        stream.source.pluginId.startsWith("st-") -> "stremio"
        stream.source.pluginId.startsWith("m3u-") || stream.source.pluginId.startsWith("xt-") -> "playlist"
        stream.source.pluginId.startsWith("dir-") -> "folder"
        else -> stream.source.pluginId.take(32)
    }

    private fun runtime(min: Int) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

    /** Whether [track] is a Watch item this version can try (a key it can read). */
    fun canPlay(track: PartyTrack): Boolean = track.kind == PartyTrack.KIND_WATCH &&
        WatchKey(track.videoId).kind in setOf(WatchKey.Kind.FILM, WatchKey.Kind.EPISODE, WatchKey.Kind.CHANNEL, WatchKey.Kind.SERIES, WatchKey.Kind.ITEM) &&
        track.videoId.count { it == ':' } >= 2

    /**
     * What a member's device asks its own plugins for. A channel is looked up in this member's channels by its key (an
     * iptv-org key is the same in every playlist); one this member does not have is still tried through the room's public
     * address when there is one.
     */
    suspend fun targetOf(track: PartyTrack, repository: WatchRepository): PlayTarget? {
        if (!canPlay(track)) return null
        val key = WatchKey(track.videoId)
        val w = track.watch ?: PartyWatch()
        val ids = ExternalIds(tmdb = w.tmdb ?: key.id.toIntOrNull()?.takeIf { key.namespace == "tmdb" }, imdb = w.imdb ?: key.id.takeIf { key.namespace == "imdb" })
        return when (key.kind) {
            WatchKey.Kind.FILM, WatchKey.Kind.ITEM -> PlayTarget.Film(key, ids, track.title, w.year, w.runtimeMin)
            WatchKey.Kind.SERIES -> PlayTarget.EpisodeOf(WatchKey.episode(key.namespace, key.id, 1, 1), ids, track.title, 1, 1, year = w.year)
            WatchKey.Kind.EPISODE -> PlayTarget.EpisodeOf(key, ids, track.title, key.season ?: w.season ?: 1, key.episode ?: w.episode ?: 1,
                episodeName = track.artist.substringAfter(" · E").substringAfter(" · ", "").ifBlank { null }, year = w.year)
            WatchKey.Kind.CHANNEL -> PlayTarget.Live(runCatching { repository.channel(key) }.getOrNull()
                ?: Channel(key, key.namespace, w.channel ?: track.title, logoUrl = track.thumbnailUrl, tvgId = w.tvgId, epgId = w.tvgId))
        }
    }

    /** The card a member's player shows while it finds its copy. */
    fun summaryOf(track: PartyTrack): TitleSummary? {
        val key = WatchKey(track.videoId)
        val type = when (key.kind) { WatchKey.Kind.FILM, WatchKey.Kind.ITEM -> TitleType.FILM; WatchKey.Kind.SERIES, WatchKey.Kind.EPISODE -> TitleType.SERIES; else -> return null }
        val w = track.watch
        val seriesKey = key.seriesKey ?: key
        return TitleSummary(seriesKey, type, track.title, w?.year, posterUrl = track.thumbnailUrl, runtimeMin = w?.runtimeMin,
            ids = ExternalIds(tmdb = w?.tmdb, imdb = w?.imdb), origin = "room")
    }

    /** The host's release, for ranking this member's copies. */
    fun hintOf(track: PartyTrack): StreamHint? = track.watch?.let { StreamHint(null, null, it.name, it.size, it.durationMs ?: track.durationMs, it.binge) }

    /** The host's public https stream, played when this member's own plugins have nothing. */
    fun fallbackOf(track: PartyTrack): Stream? {
        val url = track.url?.takeIf { it.startsWith("https://") } ?: return null
        return Stream(StreamSource("room", "From the room", "ROOM"), if (track.isLive) "Live" else "The host's copy", null, url,
            StreamGuess.protocol(url), StreamGuess.container(url), isLive = track.isLive, isPublic = true, durationMs = track.watch?.durationMs)
    }

    /**
     * "Your copy is 4 min longer: a different cut, so positions may not line up", when this member's copy and the host's
     * differ by more than [LENGTH_TOLERANCE_MS]; null when they match, or a length is unknown, or it is live.
     */
    fun lengthNote(hostMs: Long?, mineMs: Long?): String? {
        if (hostMs == null || mineMs == null || hostMs <= 0 || mineMs <= 0) return null
        val diff = mineMs - hostMs
        if (abs(diff) <= LENGTH_TOLERANCE_MS) return null
        val minutes = ((abs(diff) + 30_000) / 60_000).coerceAtLeast(1)
        return "Your copy is $minutes min ${if (diff > 0) "longer" else "shorter"}: a different cut, so positions may not line up"
    }

    /**
     * A room chat message offering this member's add-on pack ([WatchRepository.exportPluginPack]) to the others, or null when
     * the link does not fit in a chat message (500 characters). Packs hold manifest URLs only.
     */
    fun packOffer(packLink: String, senderName: String): String? =
        "$senderName shares their Watch add-ons: $packLink".takeIf { InstallLinks.isPackLink(packLink) && it.length <= 500 }

    /** The pack link inside a chat message, for the "Import these add-ons" button; null when there is none. */
    fun packIn(chatText: String): String? = Regex("cosmotune://watch/pack[?]d=[A-Za-z0-9_-]+").find(chatText)?.value

    /** An https address with a host, no user info and no query that could hold a session or a key, at most 500 long. */
    fun shareable(url: String): Boolean {
        if (!url.startsWith("https://") || url.length > 500) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return false
        val query = uri.rawQuery ?: return true
        return !Regex("(?i)(token|jwt|key|auth|sig|session|pass|user)").containsMatchIn(query)
    }
}
