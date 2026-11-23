package com.cosmotune.music.data.sources

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, keyless: asks YouTube Music's anonymous search for another copy of a song whose upload is gone, as the
 * player does, and writes what [StandInMatcher] would play to `build/stand-in-live.txt`. The song is given in the
 * environment, never in the source:
 *
 * `COSMOTUNE_STANDIN_LIVE=1 STANDIN_TITLE=… STANDIN_ARTIST=… STANDIN_SECONDS=… [STANDIN_ALBUM=…] [STANDIN_VIDEO_ID=…]`
 */
class StandInLiveProbeTest {
    @Test(timeout = 90_000) fun anotherCopyIsFoundForAGoneUpload() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_STANDIN_LIVE") == "1")
        val target = TrackMatcher.Target(
            title = System.getenv("STANDIN_TITLE").orEmpty(),
            artist = System.getenv("STANDIN_ARTIST").orEmpty(),
            durationSec = System.getenv("STANDIN_SECONDS")?.toIntOrNull(),
            album = System.getenv("STANDIN_ALBUM"),
            videoId = System.getenv("STANDIN_VIDEO_ID"),
        )
        assumeTrue(target.title.isNotBlank() && target.durationSec != null)
        val lines = mutableListOf<String>()
        for (query in TrackMatcher.queries(target)) {
            val rows = withTimeout(20_000) { YtMusicRepository.search(query, SearchFilter.SONGS).getOrDefault(emptyList()) }
                .mapNotNull { (it as? SearchResult.Track)?.song }
            lines += "songs '$query': ${rows.size} rows; stand-ins " +
                StandInMatcher.rankedCatalogue(rows, target).joinToString { "${it.videoId} '${it.title}' by '${it.artist}' ${it.durationText}" }
        }
        val uploads = mutableListOf<String>()
        for (query in StandInMatcher.uploadQueries(target)) {
            val rows = withTimeout(20_000) { YtMusicRepository.search(query, SearchFilter.VIDEOS).getOrDefault(emptyList()) }
                .mapNotNull { (it as? SearchResult.Track)?.song }
            val ranked = StandInMatcher.rankedUploads(rows, target)
            uploads += ranked.map { it.videoId }
            lines += "videos '$query': ${rows.size} rows; stand-ins " +
                ranked.joinToString { "${it.videoId} '${it.title}' by '${it.artist}' ${it.durationText}" }
        }
        File("../build/stand-in-live.txt").apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n"))
        println(lines.joinToString("\n"))
    }
}
