package com.cosmotune.watch.plugins.nuvio

import com.cosmotune.providers.WatchScriptRuntime
import com.cosmotune.watch.model.*
import com.cosmotune.watch.plugin.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI

/** Executable scripts are separate from HTTP manifest add-ons. All execution is isolated and bounded. */
class NuvioScriptPlugin(override val id: String, override val displayName: String,
    private val code: suspend () -> String, private val domains: List<String>, private val root: File, private val settings: JsonObject = JsonObject(emptyMap())) : WatchPlugin {
    override val kind = PluginKind.NUVIO_SCRIPT
    override val badge = "JS"
    override val capabilities = setOf(Capability.STREAMS)

    override suspend fun streams(target: PlayTarget): List<Stream> {
        val args = buildJsonObject {
            when (target) {
                is PlayTarget.Film -> {
                    put("tmdbId", target.ids.tmdb?.toString() ?: target.key.id)
                    put("imdbId", target.ids.imdb); put("mediaType", "movie"); put("title", target.name); put("year", target.year)
                }
                is PlayTarget.EpisodeOf -> {
                    put("tmdbId", target.seriesIds.tmdb?.toString() ?: target.key.id)
                    put("imdbId", target.seriesIds.imdb); put("mediaType", "tv"); put("title", target.seriesName)
                    put("season", target.season); put("episode", target.episode); put("year", target.year)
                }
                else -> return emptyList()
            }
        }
        val answer = WatchScriptRuntime.streams(code(), args, domains, root, settings = settings)
        val items = (answer as? JsonArray) ?: (answer as? JsonObject)?.get("streams") as? JsonArray ?: return emptyList()
        return items.take(100).mapNotNull { element ->
            val data = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
            val url = text("url") ?: return@mapNotNull null
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme !in setOf("https", "http") || uri.host == null || uri.userInfo != null) return@mapNotNull null
            val headers = (data["headers"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (key.length <= 100 && text.length <= 8192 && '\n' !in text && '\r' !in text) key to text else null
            }.take(50).toMap()
            val path = uri.path.orEmpty().lowercase()
            Stream(StreamSource(id, displayName, badge), (text("quality") ?: text("name") ?: "Play").take(120),
                description = text("title")?.take(500), url = url, protocol = when {
                    path.endsWith(".m3u8") -> StreamProtocol.HLS
                    path.endsWith(".mpd") -> StreamProtocol.DASH
                    else -> StreamProtocol.PROGRESSIVE
                }, headers = headers, sizeBytes = (data["size"] as? JsonPrimitive)?.longOrNull,
                container = when { path.endsWith(".mkv") -> Container.MKV; path.endsWith(".mp4") -> Container.MP4; else -> Container.UNKNOWN })
        }.distinctBy { it.url }
    }
}
