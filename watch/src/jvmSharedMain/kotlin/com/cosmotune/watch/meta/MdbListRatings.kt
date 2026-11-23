package com.cosmotune.watch.meta

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.WatchRating
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * MDBList (with the listener's own free key): one call gives a title's IMDb, TMDB, Trakt, Letterboxd, Rotten Tomatoes (critics
 * and audience), Metacritic, Roger Ebert and MyAnimeList scores, each on its own scale and named for its source.
 */
object MdbListRatings {
    suspend fun fetch(http: WatchHttp, imdb: String?, tmdb: Int?, series: Boolean, apiKey: String): List<WatchRating> {
        if (apiKey.isBlank()) return emptyList()
        val url = "https://mdblist.com/api/".toHttpUrl().newBuilder().addQueryParameter("apikey", apiKey).apply {
            when {
                imdb != null && imdb.matches(Regex("tt[0-9]+")) -> addQueryParameter("i", imdb)
                tmdb != null -> addQueryParameter("tm", tmdb.toString()).addQueryParameter("m", if (series) "show" else "movie")
                else -> return emptyList()
            }
        }.build()
        return parse(http.getJson(url.toString(), ttlMs = 24 * WatchHttp.HOUR).jsonObject)
    }

    fun parse(data: JsonObject): List<WatchRating> = (data["ratings"] as? JsonArray).orEmpty().mapNotNull { row ->
        val rating = row as? JsonObject ?: return@mapNotNull null
        val source = (rating["source"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val value = (rating["value"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        fun tenth(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
        when (source) {
            "imdb" -> value.takeIf { it <= 10 }?.let { WatchRating("IMDb", "${tenth(it)}/10") }
            "tmdb" -> value.takeIf { it <= 100 }?.let { WatchRating("TMDB", "${it.toInt()}%") }
            "trakt" -> value.takeIf { it <= 100 }?.let { WatchRating("Trakt", "${it.toInt()}%") }
            "letterboxd" -> value.takeIf { it <= 5 }?.let { WatchRating("Letterboxd", "${tenth(it)}/5") }
            "tomatoes" -> value.takeIf { it <= 100 }?.let { WatchRating("Rotten Tomatoes", "${it.toInt()}%") }
            "popcorn" -> value.takeIf { it <= 100 }?.let { WatchRating("Audience", "${it.toInt()}%") }
            "metacritic" -> value.takeIf { it <= 100 }?.let { WatchRating("Metacritic", "${it.toInt()}/100") }
            "rogerebert" -> value.takeIf { it <= 4 }?.let { WatchRating("Roger Ebert", "${tenth(it)}/4") }
            "myanimelist" -> value.takeIf { it <= 10 }?.let { WatchRating("MyAnimeList", "${tenth(it)}/10") }
            else -> null
        }
    }.distinctBy { it.source }

    /** Every source a title page can show, in the order it shows them; the settings let the listener hide any. */
    val sources = listOf("IMDb", "TMDB", "Rotten Tomatoes", "Audience", "Metacritic", "Letterboxd", "Trakt", "Roger Ebert", "MyAnimeList")
}
