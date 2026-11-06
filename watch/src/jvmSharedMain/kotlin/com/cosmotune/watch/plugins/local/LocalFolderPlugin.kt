package com.cosmotune.watch.plugins.local

import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.StreamGuess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What a file name says: "Harbour.Town.S02E04.1080p.mkv" or "Ember Road (2021).mp4". */
data class ParsedName(val name: String, val year: Int?, val season: Int?, val episode: Int?) {
    val isEpisode: Boolean get() = season != null && episode != null

    companion object {
        private val episode = Regex("(?i)^(.*?)[ ._-]+s(\\d{1,2})[ ._-]?e(\\d{1,3})")
        private val episodeX = Regex("(?i)^(.*?)[ ._-]+(\\d{1,2})x(\\d{2,3})\\b")
        private val year = Regex("^(.*?)[ ._(\\[-]+((?:19|20)\\d{2})(?:[)\\] ._-]|$)")

        fun of(fileName: String): ParsedName {
            val base = fileName.substringBeforeLast('.')
            (episode.find(base) ?: episodeX.find(base))?.let { m ->
                val show = clean(m.groupValues[1])
                val y = year.find(show)?.groupValues?.get(2)?.toIntOrNull()
                return ParsedName(if (y != null) clean(year.find(show)!!.groupValues[1]) else show, y, m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
            year.find(base)?.let { m -> return ParsedName(clean(m.groupValues[1]), m.groupValues[2].toInt(), null, null) }
            return ParsedName(clean(base.replace(Regex("(?i)[ ._-](2160p|1080p|720p|480p|bluray|web-?dl|webrip|x264|x265|hevc).*$"), "")), null, null, null)
        }

        private fun clean(s: String) = s.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * A folder on this device (and its subfolders): films and episodes found by their file names, sidecar subtitles beside them
 * (`.srt`, `.ass`, `.vtt` with the same base name). Matched to TMDB titles by name and year, or season and episode.
 */
class LocalFolderPlugin(override val id: String, override val displayName: String, private val root: File) : WatchPlugin {
    override val kind = PluginKind.LOCAL_FOLDER
    override val badge = "DIR"
    override val capabilities = setOf(Capability.CATALOG, Capability.STREAMS, Capability.SEARCH)
    private val source = StreamSource(id, displayName, badge)
    private val lock = Mutex()
    private var scanned: Pair<Long, List<Pair<File, ParsedName>>>? = null

    private suspend fun files(): List<Pair<File, ParsedName>> = lock.withLock {
        scanned?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val list = withContext(Dispatchers.IO) {
            root.walkTopDown().maxDepth(6).filter { it.isFile && it.extension.lowercase() in VIDEO }.take(20_000).map { it to ParsedName.of(it.name) }.toList()
        }
        scanned = System.currentTimeMillis() to list
        list
    }

    override suspend fun health(): String? = if (root.isDirectory) null else "The folder is not there any more"

    override suspend fun catalogs() = listOf(CatalogDef(id, "films", "Films in $displayName", TitleType.FILM, pageSize = 40))

    override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> =
        files().filter { !it.second.isEpisode }.sortedByDescending { it.first.lastModified() }.drop(skip).take(catalog.pageSize).map { summary(it) }

    override suspend fun search(query: String, type: TitleType?): List<TitleSummary> {
        val q = NameMatch.normalise(query)
        return files().filter { NameMatch.normalise(it.second.name).contains(q) && !it.second.isEpisode && type != TitleType.SERIES }.take(20).map { summary(it) }
    }

    private fun summary(entry: Pair<File, ParsedName>) = TitleSummary(WatchKey.film(id, Integer.toHexString(entry.first.path.hashCode())), TitleType.FILM,
        entry.second.name, entry.second.year, origin = id)

    override suspend fun streams(target: PlayTarget): List<Stream> {
        val all = files()
        val hits = when (target) {
            is PlayTarget.Film -> if (target.key.namespace == id) all.filter { Integer.toHexString(it.first.path.hashCode()) == target.key.id }
                else all.filter { (f, p) -> !p.isEpisode && NameMatch.same(p.name, target.name) && (target.year == null || p.year == null || kotlin.math.abs(p.year - target.year) <= 1) }
            is PlayTarget.EpisodeOf -> all.filter { (_, p) -> p.isEpisode && p.season == target.season && p.episode == target.episode && NameMatch.same(p.name, target.seriesName) }
            else -> emptyList()
        }
        return hits.map { (file, _) ->
            val url = file.toURI().toString()
            val subs = file.parentFile?.listFiles()?.filter { s -> s.extension.lowercase() in SUBS && s.name.startsWith(file.nameWithoutExtension) }.orEmpty().map { s ->
                val lang = s.nameWithoutExtension.removePrefix(file.nameWithoutExtension).trim('.', '_', '-', ' ').ifBlank { "und" }
                Subtitle("$id:${s.name}", s.toURI().toString(), lang, if (lang == "und") s.name else lang, s.extension.lowercase(), SubtitleOrigin.LOCAL_FILE)
            }
            Stream(source, "On this device", file.name, url, StreamProtocol.LOCAL_FILE, StreamGuess.container(file.name),
                height = StreamGuess.height(file.name), hdr = StreamGuess.hdr(file.name), videoCodec = StreamGuess.codec(file.name),
                sizeBytes = file.length(), filename = file.name, subtitles = subs)
        }
    }

    companion object {
        val VIDEO = setOf("mp4", "mkv", "m4v", "mov", "avi", "webm", "ts", "m2ts", "wmv")
        val SUBS = setOf("srt", "ass", "ssa", "vtt")
    }
}
