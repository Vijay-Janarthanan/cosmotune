package com.cosmotune.watch.plugins.archive

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Internet Archive's public-domain and classic films (collections `feature_films`, `classic_tv`, `silent_films`...):
 * `advancedsearch.php` for rows and search, `/metadata/{id}` for the files. Every file is https and public, so it may be
 * shared in a room. Items are keyed `m:ia:<identifier>`; a TMDB film is matched by title and year.
 */
class InternetArchivePlugin(
    private val http: WatchHttp,
    private val base: String = "https://archive.org",
) : WatchPlugin {
    override val id = BuiltInPlugins.ARCHIVE
    override val kind = PluginKind.INTERNET_ARCHIVE
    override val displayName = "Internet Archive"
    override val badge = "ARC"
    override val capabilities = setOf(Capability.CATALOG, Capability.SEARCH, Capability.STREAMS, Capability.META)
    private val source = StreamSource(id, displayName, badge)

    private val rows = listOf(
        Triple("feature_films", "Free classic films", "collection:(feature_films) AND mediatype:(movies)"),
        Triple("silent_films", "Silent era", "collection:(silent_films) AND mediatype:(movies)"),
        Triple("film_noir", "Film noir", "collection:(film_noir) AND mediatype:(movies)"),
        Triple("sci-fi_horror", "Sci-fi and horror classics", "collection:(SciFi_Horror) AND mediatype:(movies)"),
    )

    override suspend fun catalogs(): List<CatalogDef> = rows.map { CatalogDef(id, it.first, it.second, TitleType.FILM, pageSize = 30) }

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> {
        val query = rows.firstOrNull { it.first == catalog.id }?.third ?: return emptyList()
        return searchRaw(query, rows = catalog.pageSize, page = skip / catalog.pageSize + 1)
    }

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        if (type == TitleType.SERIES) return emptyList()
        val q = query.replace("\"", "")
        return searchRaw("title:(\"$q\") AND mediatype:(movies) AND collection:(feature_films OR silent_films OR film_noir OR SciFi_Horror OR classic_cartoons)", 20, 1)
    }

    private suspend fun searchRaw(query: String, rows: Int, page: Int): List<TitleSummary> {
        val url = "$base/advancedsearch.php?q=${query.urlEncode()}&fl[]=identifier&fl[]=title&fl[]=year&fl[]=description&fl[]=runtime&fl[]=subject" +
            "&sort[]=downloads+desc&rows=$rows&page=$page&output=json"
        return http.getJson(url, ttlMs = 12 * WatchHttp.HOUR).child("response").list("docs").mapNotNull(::summaryOf)
    }

    private fun summaryOf(doc: JsonElement): TitleSummary? {
        val identifier = doc.str("identifier") ?: return null
        val title = text(doc, "title") ?: return null
        return TitleSummary(WatchKey.film(id, identifier), TitleType.FILM, title, text(doc, "year")?.take(4)?.toIntOrNull(),
            posterUrl = "$base/services/img/$identifier", overview = text(doc, "description")?.replace(Regex("<[^>]+>"), "")?.take(600),
            runtimeMin = text(doc, "runtime")?.let(::minutes), origin = id, badge = "FREE · ARCHIVE")
    }

    override suspend fun title(key: WatchKey): Title? {
        if (key.namespace != id) return null
        val meta = http.getJson("$base/metadata/${key.id.urlEncode()}", ttlMs = WatchHttp.DAY).child("metadata") ?: return null
        val summary = TitleSummary(key, TitleType.FILM, text(meta, "title") ?: key.id, text(meta, "year")?.take(4)?.toIntOrNull()
            ?: text(meta, "date")?.take(4)?.toIntOrNull(), posterUrl = "$base/services/img/${key.id}",
            overview = text(meta, "description")?.replace(Regex("<[^>]+>"), "")?.take(1200), runtimeMin = text(meta, "runtime")?.let(::minutes),
            genres = (meta.child("subject") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.take(3).orEmpty(),
            ids = ExternalIds(imdb = text(meta, "imdb")?.takeIf { it.startsWith("tt") }), origin = id, badge = "FREE · ARCHIVE")
        return Title(summary, status = "Public domain")
    }

    override suspend fun streams(target: PlayTarget): List<Stream> {
        val identifier = when (target) {
            is PlayTarget.Film -> if (target.key.namespace == id) target.key.id else match(target.name, target.year)
            is PlayTarget.Item -> target.itemId.takeIf { target.pluginId == id }
            else -> null
        } ?: return emptyList()
        return files(identifier)
    }

    /** The best-known item for a film by title and year (one year either way), or null. */
    private suspend fun match(name: String, year: Int?): String? {
        if (year != null && year > 1980) return null // the public-domain collections are older films
        return search(name, TitleType.FILM).firstOrNull { NameMatch.same(it.name, name) && (year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1) }
            ?.key?.id
    }

    suspend fun files(identifier: String): List<Stream> {
        val meta = http.getJson("$base/metadata/${identifier.urlEncode()}", ttlMs = WatchHttp.DAY)
        val server = "$base/download/${identifier.urlEncode()}"
        val files = meta.list("files")
        val subtitles = files.filter { (it.str("name") ?: "").lowercase().let { n -> n.endsWith(".srt") || n.endsWith(".vtt") } }.map { f ->
            val name = f.str("name")!!
            Subtitle("ia:$identifier:$name", "$server/${name.split('/').joinToString("/") { it.urlEncode() }}", "en", name.substringBeforeLast('.'),
                name.substringAfterLast('.').lowercase(), SubtitleOrigin.STREAM)
        }
        return files.filter { f ->
            val name = f.str("name").orEmpty().lowercase()
            val format = f.str("format").orEmpty().lowercase()
            (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".ogv") || name.endsWith(".avi")) &&
                "thumb" !in format && f.str("source") != "metadata"
        }.sortedWith(compareBy({ if (it.str("source") == "derivative" && it.str("name").orEmpty().endsWith(".mp4")) 0 else 1 }, { -(it.long("size") ?: 0) }))
            .take(4).map { f ->
                val name = f.str("name")!!
                val url = "$server/${name.split('/').joinToString("/") { it.urlEncode() }}"
                val height = f.str("height")?.toIntOrNull() ?: StreamGuess.height(name, f.str("format"))
                Stream(source, f.str("format") ?: name.substringAfterLast('.').uppercase(), name, url, StreamProtocol.PROGRESSIVE,
                    StreamGuess.container(url), height = height, sizeBytes = f.long("size"), filename = name,
                    durationMs = f.str("length")?.let(::lengthMs), subtitles = subtitles, isPublic = true)
            }
    }

    private fun text(e: JsonElement?, name: String): String? = e.str(name) ?: (e.child(name) as? kotlinx.serialization.json.JsonArray)
        ?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotBlank() }

    private fun minutes(text: String): Int? {
        lengthMs(text)?.let { return (it / 60_000).toInt().takeIf { m -> m > 0 } }
        return Regex("(\\d+)").find(text)?.value?.toIntOrNull()
    }

    /** "5634.12" seconds or "01:33:54" to ms. */
    private fun lengthMs(text: String): Long? {
        if (':' in text) {
            val parts = text.split(':').mapNotNull { it.trim().toDoubleOrNull() }
            return parts.fold(0.0) { acc, p -> acc * 60 + p }.times(1000).toLong().takeIf { it > 0 }
        }
        return text.trim().toDoubleOrNull()?.times(1000)?.toLong()
    }
}
