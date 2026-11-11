package com.cosmotune.watch.meta

import com.cosmotune.watch.HomeRow
import com.cosmotune.watch.MetadataSource
import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.http.yearOf
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.Person
import com.cosmotune.watch.model.Season
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.Trailer
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

/**
 * TMDB (themoviedb.org) as the metadata authority. [apiKey] is a v3 API key or a v4 read access token (starting "eyJ"),
 * from `local.properties` / the environment (`TMDB_API_KEY`); without one the repository uses [CinemetaMetadata] instead.
 * "This product uses the TMDB API but is not endorsed or certified by TMDB."
 */
class TmdbMetadata(
    private val http: WatchHttp,
    private val apiKey: String,
    private val language: String = "en-US",
    private val region: String = "IN",
    private val baseUrl: String = "https://api.themoviedb.org/3",
    private val imageBase: String = "https://image.tmdb.org/t/p",
    /** Original languages that get their own rows ("ta" gives "Tamil films"). */
    private val regionalLanguages: List<String> = listOf("ta", "hi"),
) : MetadataProvider {
    override val source = MetadataSource.TMDB
    private val bearer = apiKey.startsWith("eyJ")
    private val headers = if (bearer) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()

    override fun owns(key: WatchKey) = key.namespace == "tmdb" || key.namespace == "imdb"

    private suspend fun get(path: String, params: Map<String, String> = emptyMap(), ttl: Long = 12 * WatchHttp.HOUR): JsonElement {
        val all = buildMap {
            put("language", language)
            if (!bearer) put("api_key", apiKey)
            putAll(params)
        }
        val query = all.entries.joinToString("&") { (k, v) -> "$k=${v.urlEncode()}" }
        return http.getJson("$baseUrl$path?$query", headers, ttl)
    }

    private fun img(path: String?, size: String) = path?.let { "$imageBase/$size$it" }

    fun summaryOf(e: JsonElement, forced: TitleType? = null): TitleSummary? {
        val type = forced ?: when (e.str("media_type")) { "movie" -> TitleType.FILM; "tv" -> TitleType.SERIES; else -> return null }
        val id = e.int("id") ?: return null
        val name = (if (type == TitleType.FILM) e.str("title") else e.str("name")) ?: e.str("title") ?: e.str("name") ?: return null
        val genres = e.list("genres").mapNotNull { it.str("name") }.ifEmpty { e.list("genre_ids").mapNotNull { g -> (g as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { GENRES[it] } } }
        return TitleSummary(
            key = if (type == TitleType.FILM) WatchKey.tmdbFilm(id) else WatchKey.tmdbSeries(id),
            type = type, name = name, year = yearOf(e.str("release_date") ?: e.str("first_air_date")),
            posterUrl = img(e.str("poster_path"), "w500"), backdropUrl = img(e.str("backdrop_path"), "w1280"),
            rating = e.double("vote_average")?.takeIf { it > 0 }, overview = e.str("overview"), genres = genres,
            runtimeMin = e.int("runtime") ?: e.list("episode_run_time").firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() },
            ids = ExternalIds(tmdb = id, imdb = e.str("imdb_id") ?: e.child("external_ids").str("imdb_id"), tvdb = e.child("external_ids").int("tvdb_id")),
            origin = "tmdb",
        )
    }

    override suspend fun title(key: WatchKey): Title? {
        val type = if (key.kind == WatchKey.Kind.SERIES) TitleType.SERIES else if (key.kind == WatchKey.Kind.FILM) TitleType.FILM else return null
        val tmdbId = when (key.namespace) {
            "tmdb" -> key.id.toIntOrNull()
            "imdb" -> findByImdb(key.id, type)?.ids?.tmdb
            else -> null
        } ?: return null
        val path = if (type == TitleType.FILM) "/movie/$tmdbId" else "/tv/$tmdbId"
        val appends = if (type == TitleType.FILM) "credits,videos,recommendations,release_dates,external_ids,watch/providers,images"
            else "aggregate_credits,credits,videos,recommendations,content_ratings,external_ids,watch/providers,images"
        val e = get(path, mapOf("append_to_response" to appends, "include_image_language" to "${language.take(2)},en,null"))
        val summary = summaryOf(e, type)?.copy(logoUrl = img(e.child("images").list("logos").firstOrNull()?.str("file_path"), "w500")) ?: return null
        val credits = e.child("aggregate_credits") ?: e.child("credits")
        val cast = credits.list("cast").take(20).mapNotNull { c ->
            Person(c.str("name") ?: return@mapNotNull null, c.str("character") ?: c.list("roles").firstOrNull()?.str("character"), img(c.str("profile_path"), "w185"), c.int("id"))
        }
        val crew = e.child("credits").list("crew").filter { it.str("job") in setOf("Director", "Screenplay", "Writer", "Creator") }.take(6)
            .mapNotNull { c -> Person(c.str("name") ?: return@mapNotNull null, c.str("job"), img(c.str("profile_path"), "w185"), c.int("id")) } +
            e.list("created_by").mapNotNull { c -> c.str("name")?.let { Person(it, "Creator", img(c.str("profile_path"), "w185"), c.int("id")) } }
        val trailers = e.child("videos").list("results").filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
            .sortedByDescending { if (it.str("type") == "Trailer") 1 else 0 }
            .mapNotNull { v -> v.str("key")?.let { Trailer(v.str("name") ?: "Trailer", it, v.child("official")?.toString() != "false") } }
        val seasons = e.list("seasons").mapNotNull { s ->
            val n = s.int("season_number") ?: return@mapNotNull null
            Season(summary.key, n, s.str("name") ?: "Season $n", s.int("episode_count") ?: 0, s.str("overview"), img(s.str("poster_path"), "w342"), s.str("air_date"))
        }.sortedWith(compareBy({ it.number == 0 }, { it.number }))
        val certification = if (type == TitleType.FILM) {
            val countries = e.child("release_dates").list("results")
            (countries.firstOrNull { it.str("iso_3166_1") == region } ?: countries.firstOrNull { it.str("iso_3166_1") == "US" })
                ?.list("release_dates")?.firstNotNullOfOrNull { it.str("certification") }
        } else {
            val ratings = e.child("content_ratings").list("results")
            (ratings.firstOrNull { it.str("iso_3166_1") == region } ?: ratings.firstOrNull { it.str("iso_3166_1") == "US" })?.str("rating")
        }
        val providers = e.child("watch/providers").child("results").child(region)
        val where = listOf("flatrate", "free", "ads", "rent", "buy").flatMap { kind ->
            providers.list(kind).mapNotNull { p -> p.str("provider_name")?.let { WatchProvider(it, img(p.str("logo_path"), "w92"), kind, providers.str("link")) } }
        }.distinctBy { it.name }
        return Title(
            summary, originalName = e.str("original_title") ?: e.str("original_name"), tagline = e.str("tagline"), certification = certification,
            status = e.str("status"), firstYear = summary.year, lastYear = yearOf(e.str("last_air_date")),
            originalLanguage = e.str("original_language"), countries = e.list("production_countries").mapNotNull { it.str("iso_3166_1") }.ifEmpty { e.list("origin_country").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } },
            seasons = seasons, cast = cast, crew = crew, trailers = trailers,
            moreLikeThis = e.child("recommendations").list("results").mapNotNull { summaryOf(it, type) }.take(20), whereToWatch = where,
        )
    }

    override suspend fun episodes(series: WatchKey, season: Int): List<Episode> {
        val id = (if (series.namespace == "tmdb") series.id.toIntOrNull() else findByImdb(series.id, TitleType.SERIES)?.ids?.tmdb) ?: return emptyList()
        val seriesKey = WatchKey.tmdbSeries(id)
        return get("/tv/$id/season/$season", ttl = 6 * WatchHttp.HOUR).list("episodes").mapNotNull { ep ->
            val n = ep.int("episode_number") ?: return@mapNotNull null
            Episode(WatchKey.tmdbEpisode(id, season, n), seriesKey, season, n, ep.str("name") ?: "Episode $n", ep.str("overview"),
                img(ep.str("still_path"), "w500"), ep.str("air_date"), ep.int("runtime"), ExternalIds(tmdb = ep.int("id")))
        }
    }

    override suspend fun search(query: String): List<TitleSummary> =
        get("/search/multi", mapOf("query" to query, "include_adult" to "false"), WatchHttp.HOUR).list("results").mapNotNull { summaryOf(it) }

    override suspend fun rows(type: TitleType?): List<HomeRow> = coroutineScope {
        val defs = buildList {
            if (type != TitleType.SERIES) {
                add(RowDef("tmdb:today:film", "Top 10 · trending today", topTen = true))
                add(RowDef("tmdb:trending:film", "Trending films"))
                add(RowDef("tmdb:popular:film", "Popular films"))
                regionalLanguages.forEach {
                    add(RowDef("tmdb:regional10:film:$it", "Top 10 · ${LANGUAGES[it] ?: it}", topTen = true))
                    add(RowDef("tmdb:lang:film:$it", "${LANGUAGES[it] ?: it} films"))
                }
                add(RowDef("tmdb:top:film", "Top rated films"))
                listOf(18 to "Drama", 35 to "Comedy", 28 to "Action", 53 to "Thriller").forEach { (g, n) -> add(RowDef("tmdb:genre:film:$g", n)) }
            }
            if (type != TitleType.FILM) {
                add(RowDef("tmdb:today:series", "Top 10 series · today", topTen = true))
                add(RowDef("tmdb:trending:series", "Trending series"))
                add(RowDef("tmdb:popular:series", "Popular series"))
                add(RowDef("tmdb:top:series", "Top rated series"))
                add(RowDef("tmdb:anime:series", "Anime"))
                add(RowDef("tmdb:soap:series", "Daily soaps"))
                add(RowDef("tmdb:scripted:series", "Scripted series"))
            }
        }
        defs.map { d -> async { runCatching { HomeRow(d.id, d.title, rowPage(d.id, 0).let { if (d.topTen) it.take(10) else it },
            subtitle = if (d.id.contains(":regional10:")) "TMDB popularity · original language" else if (d.topTen) "TMDB daily trending" else null, pageable = !d.topTen) }.getOrNull() } }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    private data class RowDef(val id: String, val title: String, val topTen: Boolean = false)

    override suspend fun rowPage(rowId: String, skip: Int): List<TitleSummary> {
        val parts = rowId.split(':')
        if (parts.firstOrNull() != "tmdb" || parts.size < 3) return emptyList()
        val type = if (parts[2] == "series") TitleType.SERIES else TitleType.FILM
        val t = if (type == TitleType.FILM) "movie" else "tv"
        val page = (skip / 20 + 1).toString()
        val e = when (parts[1]) {
            "today" -> get("/trending/$t/day", mapOf("page" to page), 3 * WatchHttp.HOUR)
            "trending" -> get("/trending/$t/week", mapOf("page" to page), 3 * WatchHttp.HOUR)
            "popular" -> get("/$t/popular", mapOf("page" to page, "region" to region), 6 * WatchHttp.HOUR)
            "top" -> get("/$t/top_rated", mapOf("page" to page), WatchHttp.DAY)
            "genre" -> get("/discover/$t", mapOf("page" to page, "with_genres" to parts.getOrElse(3) { "18" }, "sort_by" to "popularity.desc"), 6 * WatchHttp.HOUR)
            "lang", "regional10" -> get("/discover/$t", mapOf("page" to page, "with_original_language" to parts.getOrElse(3) { "ta" }, "sort_by" to "popularity.desc", "vote_count.gte" to "20"), 6 * WatchHttp.HOUR)
            "anime" -> get("/discover/tv", mapOf("page" to page, "with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), 6 * WatchHttp.HOUR)
            "soap" -> get("/discover/tv", mapOf("page" to page, "with_genres" to "10766", "sort_by" to "popularity.desc"), 6 * WatchHttp.HOUR)
            "scripted" -> get("/discover/tv", mapOf("page" to page, "with_type" to "0", "without_genres" to "10766", "sort_by" to "popularity.desc"), 6 * WatchHttp.HOUR)
            else -> return emptyList()
        }
        return e.list("results").mapNotNull { summaryOf(it, type) }
    }

    override suspend fun findByImdb(imdb: String, type: TitleType): TitleSummary? {
        val e = get("/find/$imdb", mapOf("external_source" to "imdb_id"), 7 * WatchHttp.DAY)
        val hit = (if (type == TitleType.FILM) e.list("movie_results") else e.list("tv_results")).firstOrNull() ?: return null
        return summaryOf(hit, type)?.let { it.copy(ids = it.ids.copy(imdb = imdb)) }
    }

    override suspend fun findByName(name: String, year: Int?, type: TitleType): TitleSummary? {
        val path = if (type == TitleType.FILM) "/search/movie" else "/search/tv"
        val params = buildMap { put("query", name); year?.let { put(if (type == TitleType.FILM) "year" else "first_air_date_year", it.toString()) } }
        val results = get(path, params, 7 * WatchHttp.DAY).list("results").mapNotNull { summaryOf(it, type) }
        return results.firstOrNull { r -> NameMatch.same(r.name, name) && (year == null || r.year == null || kotlin.math.abs(r.year - year) <= 1) }
    }

    companion object {
        val GENRES = mapOf(28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
            10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
            878 to "Science Fiction", 53 to "Thriller", 10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids",
            10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics")
        val LANGUAGES = mapOf("ta" to "Tamil", "hi" to "Hindi", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada", "bn" to "Bengali",
            "ko" to "Korean", "ja" to "Japanese", "es" to "Spanish", "fr" to "French")
    }
}

/** Title comparison that ignores case, punctuation, articles and accents. */
object NameMatch {
    fun normalise(name: String): String = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace("&", "and")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("^(the|a|an) "), "").replace(Regex("\\s+"), " ").trim()

    fun same(a: String, b: String) = normalise(a) == normalise(b)
}
