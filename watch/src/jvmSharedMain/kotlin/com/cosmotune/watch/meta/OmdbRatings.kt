package com.cosmotune.watch.meta

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.WatchRating
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Preserve the source's actual scale and attribution. Missing/invalid ratings are omitted. */
object OmdbRatings {
    suspend fun fetch(http: WatchHttp, imdb: String, apiKey: String): List<WatchRating> {
        if (!imdb.matches(Regex("tt[0-9]+")) || apiKey.isBlank()) return emptyList()
        val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder().addQueryParameter("apikey", apiKey).addQueryParameter("i", imdb).build()
        return parse(http.getJson(url.toString()).jsonObject)
    }
    fun parse(data: JsonObject): List<WatchRating> = (data["Ratings"] as? JsonArray).orEmpty().mapNotNull { row ->
        val rating = row as? JsonObject ?: return@mapNotNull null
        val source = (rating["Source"] as? JsonPrimitive)?.contentOrNull
        val value = (rating["Value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        when (source) {
            "Internet Movie Database" -> value.takeIf { Regex("(?:[0-9](?:\\.[0-9])?|10(?:\\.0)?)/10").matches(it) }?.let { WatchRating("IMDb", it) }
            "Rotten Tomatoes" -> value.removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 && value.endsWith('%') }?.let { WatchRating("Rotten Tomatoes", value) }
            "Metacritic" -> value.substringBefore('/').toIntOrNull()?.takeIf { it in 0..100 && value.endsWith("/100") }?.let { WatchRating("Metacritic", value) }
            else -> null
        }
    }.distinctBy { it.source }
}
