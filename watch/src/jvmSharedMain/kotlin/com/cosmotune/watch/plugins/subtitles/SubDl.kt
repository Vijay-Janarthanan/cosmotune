package com.cosmotune.watch.plugins.subtitles

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.model.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Uses SubDL's individual-file endpoint so subtitle ZIPs are never handed to a media decoder. */
object SubDl {
    suspend fun search(http: WatchHttp, target: PlayTarget, languages: List<String>, apiKey: String): List<Subtitle> {
        val ids = when (target) { is PlayTarget.Film -> target.ids; is PlayTarget.EpisodeOf -> target.seriesIds; else -> return emptyList() }
        if (ids.imdb == null && ids.tmdb == null) return emptyList()
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey).addQueryParameter("unpack", "1")
            .addQueryParameter("client", "custom_integration").addQueryParameter("subs_per_page", "20")
            .addQueryParameter("type", if (target is PlayTarget.EpisodeOf) "tv" else "movie")
        ids.imdb?.let { url.addQueryParameter("imdb_id", it) } ?: ids.tmdb?.let { url.addQueryParameter("tmdb_id", it.toString()) }
        if (languages.isNotEmpty()) url.addQueryParameter("languages", languages.take(5).joinToString(",") { it.substringBefore('-').uppercase() })
        if (target is PlayTarget.EpisodeOf) {
            url.addQueryParameter("season_number", target.season.toString()).addQueryParameter("episode_number", target.episode.toString())
        }
        return parse(http.getJson(url.build().toString()).jsonObject, target)
    }

    fun parse(data: JsonObject, target: PlayTarget): List<Subtitle> = (data["subtitles"] as? JsonArray).orEmpty().flatMap { row ->
        val group = row as? JsonObject ?: return@flatMap emptyList()
        (group["unpack_files"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
            if (target is PlayTarget.EpisodeOf && ((item["season"] as? JsonPrimitive)?.intOrNull != target.season ||
                    (item["episode"] as? JsonPrimitive)?.intOrNull != target.episode)) return@mapNotNull null
            val path = text("url")?.takeIf { it.matches(Regex("/subtitle/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+")) } ?: return@mapNotNull null
            val format = text("format")?.lowercase()?.takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: return@mapNotNull null
            val language = text("language")?.lowercase() ?: "und"
            Subtitle("subdl:$path", "https://dl.subdl.com$path", language, "SubDL · ${text("release_name") ?: text("name") ?: language}",
                format = format, origin = SubtitleOrigin.ADDON, hearingImpaired = (item["hi"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }.distinctBy { it.url }
}
