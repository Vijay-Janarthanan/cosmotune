package com.cosmotune.watch.meta

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.WatchSkip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Intro, recap and credits times for films and series from the two free community databases (the idea behind CloudStream's skip
 * database): TheIntroDB by TMDB id (intro, recap, credits, preview; an open start is the beginning, an open end the end) and
 * IntroDB by IMDb id (the intro of an episode). Both are read without a key. A time that does not fit this copy is left out.
 */
object IntroSkips {
    const val THE_INTRO_DB = "https://api.theintrodb.org/v2/media"
    const val INTRO_DB = "https://api.introdb.app/intro"
    private const val MAX_INTRO_MS = 300_000L
    private val LABELS = linkedMapOf("recap" to "Skip recap", "intro" to "Skip intro", "credits" to "Skip credits", "preview" to "Skip preview")

    fun theIntroDbUrl(tmdbId: Int, season: Int?, episode: Int?): String =
        "$THE_INTRO_DB?tmdb_id=$tmdbId" + if (season != null && episode != null) "&season=$season&episode=$episode" else ""

    fun introDbUrl(imdbId: String, season: Int, episode: Int): String? =
        if (Regex("tt[0-9]{5,10}").matches(imdbId)) "$INTRO_DB?imdb_id=$imdbId&season=$season&episode=$episode" else null

    /** Every time this copy can use: films by TMDB id; episodes by TMDB id, else IMDb id. */
    suspend fun fetch(http: WatchHttp, tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?, durationMs: Long): List<WatchSkip> {
        if (durationMs <= 0) return emptyList()
        val fromTmdb = tmdbId?.takeIf { it > 0 }?.let { id ->
            runCatching { parseTheIntroDb(http.getJson(theIntroDbUrl(id, season, episode)).jsonObject, durationMs) }.getOrNull()
        }.orEmpty()
        if (fromTmdb.isNotEmpty() || season == null || episode == null) return fromTmdb
        val url = imdbId?.let { introDbUrl(it, season, episode) } ?: return emptyList()
        return runCatching { parseIntroDb(http.getJson(url).jsonObject, durationMs) }.getOrDefault(emptyList())
    }

    fun parseTheIntroDb(data: JsonObject, durationMs: Long): List<WatchSkip> = LABELS.flatMap { (kind, label) ->
        (data[kind] as? JsonArray).orEmpty().mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val start = value.long("start_ms") ?: 0L
            val end = value.long("end_ms") ?: durationMs
            fit(start, end, durationMs, label, endsAtCredits = kind == "credits" || kind == "preview")
        }
    }.sortedBy { it.startMs }

    fun parseIntroDb(data: JsonObject, durationMs: Long): List<WatchSkip> {
        val start = data.long("start_ms") ?: return emptyList()
        val end = data.long("end_ms") ?: return emptyList()
        return listOfNotNull(fit(start, end, durationMs, "Skip intro", endsAtCredits = false))
    }

    /** [first] wins where two sources mark the same stretch (AniSkip before the databases, the server before both). */
    fun merge(first: List<WatchSkip>, second: List<WatchSkip>): List<WatchSkip> =
        (first + second.filter { s -> first.none { it.startMs < s.endMs && s.startMs < it.endMs } }).sortedBy { it.startMs }

    private fun fit(start: Long, end: Long, durationMs: Long, label: String, endsAtCredits: Boolean): WatchSkip? {
        if (start < 0 || end <= start || start >= durationMs) return null
        // The credits may run past a slightly shorter copy's end; an intro or recap must sit inside it and be short.
        if (!endsAtCredits && (end > durationMs || end - start > MAX_INTRO_MS)) return null
        return WatchSkip(start, end.coerceAtMost(durationMs), label)
    }

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
