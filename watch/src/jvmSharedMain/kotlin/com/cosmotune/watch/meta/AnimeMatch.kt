package com.cosmotune.watch.meta

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.at
import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.model.EpisodeKind
import kotlinx.serialization.json.JsonElement

/** The MyAnimeList entry a series' season is, and how many of that entry's episodes come before the season's first. */
data class AnimeSeason(val malId: Int, val offset: Int = 0)

/** One MyAnimeList entry a search returned. */
data class AnimeCandidate(val malId: Int, val titles: List<String>, val year: Int?, val episodes: Int?, val airing: Boolean)

/**
 * Anime seasons on MyAnimeList, through the free Jikan API (api.jikan.moe, no key): which MAL entry a TMDB season is (for
 * AniSkip, filler marks and anime trackers) and which of its episodes MyAnimeList marks as filler or recap.
 *
 * A season is the entry whose name matches and that began airing the year the season did. A long show that MyAnimeList keeps
 * as one entry (One Piece, Bleach) but TMDB splits into seasons gets that entry, counted on by the episodes of the seasons
 * before it.
 */
object AnimeMatch {
    private const val BASE = "https://api.jikan.moe/v4"

    suspend fun season(http: WatchHttp, name: String, season: Int, seasonYear: Int?, earlierEpisodes: Int, firstYear: Int?): AnimeSeason? {
        if (name.isBlank()) return null
        val json = http.getJson("$BASE/anime?q=${name.take(100).urlEncode()}&type=tv&limit=15&sfw=true", ttlMs = 7 * WatchHttp.DAY)
        return pick(candidates(json), name, season, seasonYear, earlierEpisodes, firstYear)
    }

    /** MyAnimeList's filler and recap marks for an entry's episodes, by episode number (at most 20 pages of 100). */
    suspend fun kinds(http: WatchHttp, malId: Int): Map<Int, EpisodeKind> {
        val out = HashMap<Int, EpisodeKind>()
        for (page in 1..20) {
            val json = http.getJson("$BASE/anime/$malId/episodes?page=$page", ttlMs = 3 * WatchHttp.DAY)
            out += episodeKinds(json)
            if (json.at("pagination")?.bool("has_next_page") != true) break
        }
        return out
    }

    fun candidates(json: JsonElement): List<AnimeCandidate> = json.list("data").mapNotNull { e ->
        val id = e.int("mal_id") ?: return@mapNotNull null
        val titles = (listOfNotNull(e.str("title"), e.str("title_english")) + e.list("titles").mapNotNull { it.str("title") }).distinct()
        val year = e.int("year") ?: e.at("aired")?.str("from")?.take(4)?.toIntOrNull()
        AnimeCandidate(id, titles, year, e.int("episodes"), e.bool("airing") == true)
    }

    fun episodeKinds(json: JsonElement): Map<Int, EpisodeKind> = json.list("data").mapNotNull { e ->
        val number = e.int("mal_id") ?: return@mapNotNull null
        when {
            e.bool("filler") == true -> number to EpisodeKind.FILLER
            e.bool("recap") == true -> number to EpisodeKind.RECAP
            else -> null
        }
    }.toMap()

    /**
     * The entry for [season] among [candidates]: one named like [name] that began in [seasonYear] (the first season takes the
     * earliest such entry); else, for a later season, the first season's entry when it runs long enough to hold this season too.
     */
    fun pick(candidates: List<AnimeCandidate>, name: String, season: Int, seasonYear: Int?, earlierEpisodes: Int, firstYear: Int?): AnimeSeason? {
        val named = candidates.filter { c -> c.titles.any { NameMatch.same(it, name) || NameMatch.normalise(it).startsWith(NameMatch.normalise(name)) } }
        if (named.isEmpty()) return null
        val exact = named.filter { c -> c.titles.any { NameMatch.same(it, name) } }
        if (season <= 1) {
            val pool = exact.ifEmpty { named }
            val hit = pool.filter { seasonYear == null || it.year == seasonYear }.minByOrNull { it.year ?: Int.MAX_VALUE } ?: pool.firstOrNull()
            return hit?.let { AnimeSeason(it.malId) }
        }
        named.filter { seasonYear != null && it.year == seasonYear && it.titles.none { t -> NameMatch.same(t, name) } }
            .minByOrNull { it.malId }?.let { return AnimeSeason(it.malId) }
        val first = exact.filter { firstYear == null || it.year == firstYear }.minByOrNull { it.year ?: Int.MAX_VALUE } ?: return null
        val holds = first.airing || (first.episodes ?: 0) > earlierEpisodes
        return if (holds && earlierEpisodes > 0) AnimeSeason(first.malId, earlierEpisodes) else null
    }
}
