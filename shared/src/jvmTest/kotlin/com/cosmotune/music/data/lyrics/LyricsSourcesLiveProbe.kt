package com.cosmotune.music.data.lyrics

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.durationMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test

/**
 * A live check of every lyrics source against a fixed set of songs.
 *
 * Off by default: it reaches real third-party services, so it only runs when
 * `COSMOTUNE_LYRICS_PROBE=1` is set. It writes a table of counts, timings and
 * flags to `build/lyrics-probe/report.tsv` and never records a word of the
 * lyrics themselves.
 */
class LyricsSourcesLiveProbe {

    private data class Probe(val group: String, val query: String, val videoId: String? = null, val video: Boolean = false)

    private val songs = listOf(
        Probe("english", "Blinding Lights The Weeknd"),
        Probe("english", "Bohemian Rhapsody Queen"),
        Probe("english", "Shape of You Ed Sheeran"),
        Probe("english", "Holland 1945 Neutral Milk Hotel"),
        Probe("tamil", "Ilaya Nila Ilaiyaraaja"),
        Probe("tamil", "Raja Raja Chozhan Ilaiyaraaja"),
        Probe("tamil", "Kichchali Samba", videoId = "GLcMugzOjbw"),
        Probe("tamil", "Arabic Kuthu Anirudh"),
        Probe("tamil", "Hosanna A.R. Rahman"),
        Probe("indian", "Kesariya Arijit Singh"),
        Probe("indian", "Naatu Naatu"),
        Probe("indian", "Jimikki Kammal"),
        Probe("kpop", "Dynamite BTS"),
        Probe("jpop", "Idol YOASOBI"),
        Probe("instrumental", "Sandstorm Darude"),
        // Music-video uploads rather than catalogue tracks: the caption and transcript sources only ever answer these.
        Probe("video", "Blinding Lights The Weeknd official video", video = true),
        Probe("video", "Arabic Kuthu official video Beast", video = true),
        Probe("video", "Kesariya official video Brahmastra", video = true),
    )

    private data class Track(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    @Test
    fun probe() {
        if (System.getenv("COSMOTUNE_LYRICS_PROBE") != "1") return
        val only = System.getenv("COSMOTUNE_LYRICS_PROBE_SOURCES")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        val out = File("build/lyrics-probe").apply { mkdirs() }
        val rows = mutableListOf(
            "group\tsong\tsource\toutcome\tms\tlines\ttiming\tlastStartS\tdurationS\tinsideTrack",
        )
        runBlocking {
            for ((index, probe) in songs.withIndex()) {
                val track = resolve(probe)
                val label = "s${index + 1}"
                if (track == null) {
                    rows += "${probe.group}\t$label\t-\tunresolved\t0\t0\t-\t-\t-\t-"
                    continue
                }
                val title = track.title.forLyricsSearch()
                val artist = track.artist.artistForLyricsSearch()
                val sources = LyricsSource.entries.filter { only == null || it.name in only }
                for (source in sources) {
                    val started = System.nanoTime()
                    val outcome = runCatching {
                        withTimeoutOrNull(20_000) {
                            fetch(source, track, title, artist)
                        }
                    }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    val lines = outcome.getOrNull()
                    val status = when {
                        outcome.isFailure -> "error:" + outcome.exceptionOrNull()!!::class.simpleName
                        lines == null -> "miss"
                        else -> "hit"
                    }
                    val timing = when {
                        lines == null -> "-"
                        lines.any { it.isWordSynced } -> "word"
                        lines.any { it.timeMs > 0 } -> "line"
                        else -> "none"
                    }
                    val last = lines?.maxOfOrNull { it.timeMs }
                    val inside = if (lines == null || timing == "none" || track.durationMs <= 0) "-" else
                        (last!! <= track.durationMs).toString()
                    rows += listOf(
                        probe.group, label, source.name, status, ms.toString(), (lines?.size ?: 0).toString(), timing,
                        last?.let { (it / 1000).toString() } ?: "-", (track.durationMs / 1000).toString(), inside,
                    ).joinToString("\t")
                    println("probe ${probe.group} $label ${source.name} $status ${ms}ms lines=${lines?.size ?: 0} $timing")
                }
                if (only == null) {
                    // What the player would actually show: the whole race, with the default order.
                    val started = System.nanoTime()
                    val won = runCatching {
                        LyricsRepository.lyrics(track.videoId, track.title, track.artist, track.durationMs, track.album)
                    }.getOrNull()
                    val ms = (System.nanoTime() - started) / 1_000_000
                    val timing = won?.let { LyricsRepository.timing(it.lines, track.durationMs).name.lowercase() } ?: "-"
                    rows += listOf(
                        probe.group, label, "PICK:" + (won?.source?.name ?: "none"), if (won == null) "miss" else "hit",
                        ms.toString(), (won?.lines?.size ?: 0).toString(), timing, "-", (track.durationMs / 1000).toString(), "-",
                    ).joinToString("\t")
                }
            }
        }
        File(out, System.getenv("COSMOTUNE_LYRICS_PROBE_OUT") ?: "report.tsv").writeText(rows.joinToString("\n"))
    }

    private suspend fun resolve(probe: Probe): Track? {
        val filter = if (probe.video) SearchFilter.VIDEOS else SearchFilter.SONGS
        val results = YtMusicRepository.search(probe.query, filter).getOrNull().orEmpty()
        val songs = results.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }
        val song = probe.videoId?.let { id -> songs.firstOrNull { it.videoId == id } } ?: songs.firstOrNull()
            ?: return null
        val durationMs = song.durationMillis().takeIf { it > 0 } ?: if (probe.videoId != null) 303_000L else 0L
        return Track(probe.videoId ?: song.videoId, song.title, song.artist, durationMs, song.albumName)
    }

    private suspend fun fetch(source: LyricsSource, track: Track, title: String, artist: String): List<LyricLine>? =
        LyricsRepository.fetch(source, track.videoId, title, artist, track.durationMs, track.album, isrc = null)
}
