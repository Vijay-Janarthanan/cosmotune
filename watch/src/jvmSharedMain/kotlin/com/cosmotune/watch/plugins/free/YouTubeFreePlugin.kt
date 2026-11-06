package com.cosmotune.watch.plugins.free

import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSource
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin

/**
 * Full films and episodes that their rights holders publish free on YouTube, through Video mode's own source (InnerTube with
 * NewPipe behind it). Only uploads from [officialChannels] count, and only when the length fits a film or an episode, so a
 * trailer, a clip or somebody's re-upload is never offered. A stream is a YouTube watch address that the player resolves
 * through the same source.
 */
class YouTubeFreePlugin(
    private val video: () -> VideoSource?,
    /** Channel names (as YouTube shows them, compared ignoring case) of studios and distributors that post full titles. */
    private val officialChannels: Set<String> = OFFICIAL,
) : WatchPlugin {
    override val id = BuiltInPlugins.YOUTUBE
    override val kind = PluginKind.YOUTUBE_FREE
    override val displayName = "Free on YouTube"
    override val badge = "YT"
    override val capabilities = setOf(Capability.CATALOG, Capability.STREAMS)
    private val source = StreamSource(id, displayName, badge)

    private fun official(item: VideoItem) = item.channelName?.let { n -> officialChannels.any { it.equals(n.trim(), ignoreCase = true) } } == true

    private suspend fun videos(query: String): List<VideoItem> {
        val src = video() ?: return emptyList()
        return src.search(query, SearchFilter.VIDEOS).items.mapNotNull { (it as? ListItem.Video)?.item }.filter { !it.isLive && !it.isShort && !it.isUpcoming }
    }

    override suspend fun catalogs(): List<CatalogDef> = if (video() == null) emptyList()
        else listOf(CatalogDef(id, "films", "Free films on YouTube", TitleType.FILM, pageSize = 20))

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> {
        if (skip > 0) return emptyList()
        return videos("full movie free").filter { official(it) && (it.durationSec ?: 0) >= 60 * 60 }.map { v ->
            TitleSummary(WatchKey.film(id, v.id), TitleType.FILM, cleanTitle(v.title), posterUrl = v.thumbnailUrl, backdropUrl = v.thumbnailUrl,
                runtimeMin = v.durationSec?.let { (it / 60).toInt() }, origin = id, badge = "FREE · YOUTUBE")
        }
    }

    override suspend fun streams(target: PlayTarget): List<Stream> {
        val (query, name, minSec, maxSec) = when (target) {
            is PlayTarget.Film -> {
                if (target.key.namespace == id) return listOf(stream(target.key.id, target.name, null))
                val runtime = (target.runtimeMin ?: 90) * 60L
                Quad("${target.name} ${target.year ?: ""} full movie", target.name, maxOf(55 * 60L, runtime * 8 / 10), runtime * 13 / 10 + 600)
            }
            is PlayTarget.EpisodeOf -> Quad("${target.seriesName} season ${target.season} episode ${target.episode} full episode", target.seriesName, 15 * 60L, 120 * 60L)
            else -> return emptyList()
        }
        val wanted = NameMatch.normalise(name)
        return videos(query).filter { v ->
            val d = v.durationSec ?: return@filter false
            official(v) && d in minSec..maxSec && NameMatch.normalise(v.title).contains(wanted) &&
                (target !is PlayTarget.EpisodeOf || episodeMatches(v.title, target.season, target.episode))
        }.take(2).map { stream(it.id, it.title, it.durationSec) }
    }

    private fun stream(videoId: String, title: String, durationSec: Long?) = Stream(source, "Free on YouTube", title, "https://www.youtube.com/watch?v=$videoId",
        StreamProtocol.YOUTUBE, durationMs = durationSec?.times(1000), hasAds = true, isPublic = true)

    private fun episodeMatches(title: String, season: Int, episode: Int): Boolean {
        val t = title.lowercase()
        return Regex("s0?${season}\\s*e0?${episode}\\b").containsMatchIn(t) || Regex("season\\s*$season\\D.*episode\\s*$episode\\b").containsMatchIn(t) ||
            (season == 1 && Regex("\\bep(isode)?\\.?\\s*0?$episode\\b").containsMatchIn(t))
    }

    private data class Quad(val query: String, val name: String, val minSec: Long, val maxSec: Long)

    private fun cleanTitle(t: String) = t.replace(Regex("(?i)\\s*[|(\\[-]?\\s*(full (hd )?movie|free movie|official).*$"), "").trim().ifBlank { t }

    companion object {
        /** Distributors that post their own films and series in full on YouTube. Owners can extend it; none is a re-uploader. */
        val OFFICIAL = setOf(
            "Popcornflix", "FilmRise Movies", "FilmRise", "Maverick Movies", "Movie Central", "Moviedome", "Kings of Horror",
            "Grizzly Imagination", "Hoopla Doopla", "Paramount Movies", "Rajshri", "Rajshri Tamil", "Shemaroo", "Shemaroo Movies",
            "Goldmines", "Ultra Movie Parlour", "Pyramid Glitz", "Ayngaran", "Sun NXT", "Saregama Tamil", "Lahari Films",
        )
    }
}
