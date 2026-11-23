package com.cosmotune.watch.meta

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.str
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

/**
 * More like this from Simkl (with the listener's own Simkl app): its "users who liked this also liked" for a title, each
 * found again in Watch's catalogue by name and year so it opens like any other title.
 */
class SimklPicks(private val http: WatchHttp, private val clientId: String, private val base: String = "https://api.simkl.com") {
    private val headers = mapOf("simkl-api-key" to clientId)

    suspend fun picks(ids: ExternalIds, type: TitleType, find: suspend (name: String, year: Int?, type: TitleType) -> TitleSummary?): List<TitleSummary> {
        val path = if (type == TitleType.SERIES) "tv" else "movies"
        val query = when {
            ids.imdb != null -> "imdb=${ids.imdb}"
            ids.tmdb != null -> "tmdb=${ids.tmdb}&type=${if (type == TitleType.SERIES) "show" else "movie"}"
            else -> return emptyList()
        }
        val found = (http.getJson("$base/search/id?$query&client_id=$clientId", headers, 7 * WatchHttp.DAY) as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.child("ids")?.int("simkl") ?: return emptyList()
        val full = http.getJson("$base/$path/$found?extended=full&client_id=$clientId", headers, 3 * WatchHttp.DAY)
        val wanted = recommended(full).take(12)
        return coroutineScope { wanted.map { (name, year) -> async { runCatching { find(name, year, type) }.getOrNull() } }.awaitAll() }
            .filterNotNull().distinctBy { it.key }
    }

    companion object {
        /** The names and years Simkl lists under `users_recommendations`. */
        fun recommended(full: JsonElement): List<Pair<String, Int?>> =
            full.list("users_recommendations").mapNotNull { r -> r.str("title")?.let { it to r.int("year") } }
    }
}
