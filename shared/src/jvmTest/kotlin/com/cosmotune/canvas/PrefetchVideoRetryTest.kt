package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The early look for the queue's next song (a prefetch) and the look when it starts. A "no video" found early, while the song
 * was only queued and its searches yielded to the playing song's, is asked once more when the song starts; that answer then
 * stands for its short while. Recorded-style answers, synthetic media, no network.
 */
class PrefetchVideoRetryTest {
    private val directory: File = Files.createTempDirectory("canvas-prefetch-").toFile()
    private val searches = AtomicInteger()
    private val lines = ArrayList<String>()

    @AfterTest fun reset() {
        OpenCanvas.configure(OpenCanvasConfig(SyntheticMedia.backend(emptyMap()), instantBackend = null, cacheDirectory = null, communityMapsUrl = null))
        directory.deleteRecursively()
    }

    private fun configure(files: Map<String, com.opencanvas.core.stream.AudioStream>, answer: (String) -> String) {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            searches.incrementAndGet()
            RecordedVideoSearch.respond(chain.request(), answer(RecordedVideoSearch.queryOf(chain.request())))
        }).build()
        OpenCanvas.configure(OpenCanvasConfig(SyntheticMedia.backend(files), instantBackend = null, cacheDirectory = File(directory, "maps"),
            communityMapsUrl = null, httpClient = http, log = { synchronized(lines) { lines += it } }))
    }

    /** The queue's next song, looked up to the end. */
    private suspend fun early(title: String, track: String): List<OpenCanvasTrack> = withTimeout(30_000) {
        OpenCanvas.resolveFlow(title, ARTIST, 140, mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = track, album = "Film",
            background = true).toList()
    }

    private suspend fun playing(title: String, track: String): OpenCanvasTrack? = withTimeout(30_000) {
        OpenCanvas.resolve(title, ARTIST, 140, mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = track, album = "Film")
    }

    @Test fun anEarlyNoVideoIsAskedAgainWhenTheSongStarts() = runBlocking {
        val audio = SyntheticMedia.recording()
        var published = false
        configure(mapOf(
            "TRACKPREF01" to SyntheticMedia.file(directory, "song.mp4", audio),
            "FILMVIDEO01" to SyntheticMedia.file(directory, "video.mp4", audio),
        )) { if (published) page("FILMVIDEO01", "Song Name Video Song | Film | Some Singer", "Film Music", verified = true) else RecordedVideoSearch.EMPTY }
        assertTrue(early("Song Name", "TRACKPREF01").isEmpty(), "the early look found no video")
        published = true
        val result = playing("Song Name", "TRACKPREF01")
        assertEquals(SyncStage.MEASURED, result?.syncStage)
        assertEquals("FILMVIDEO01", result?.videoId)
    }

    @Test fun theStartedSongsOwnMissStandsForAWhile() = runBlocking {
        // A candidate that cannot be read: answered searches, a "no map" that is believed.
        configure(mapOf("TRACKPREF02" to SyntheticMedia.file(directory, "song.mp4", SyntheticMedia.recording()))) { query ->
            if (query.contains("-audio") || query.contains("movie songs")) RecordedVideoSearch.EMPTY
            else page("UNREADABLE3", "Song Name - Official Video", ARTIST, artist = true)
        }
        assertEquals(SyncStage.NONE, early("Song Name", "TRACKPREF02").lastOrNull()?.syncStage)
        val afterEarly = searches.get()
        assertEquals(SyncStage.NONE, playing("Song Name", "TRACKPREF02")?.syncStage)
        assertTrue(searches.get() > afterEarly, "the started song looks again")
        assertTrue(synchronized(lines) { lines.any { "the song has started: looking for its video again" in it } })
        delay(300)
        val afterPlaying = searches.get()
        playing("Song Name", "TRACKPREF02")
        assertEquals(afterPlaying, searches.get(), "the playing song's own miss is believed: no loop")
    }

    @Test fun nothingInTheFirstSearchTriesTheWiderOnes() = runBlocking {
        val audio = SyntheticMedia.recording()
        configure(mapOf(
            "TRACKPREF03" to SyntheticMedia.file(directory, "song.mp4", audio),
            "FILMVIDEO03" to SyntheticMedia.file(directory, "video.mp4", audio),
        )) { query ->
            // Only the film's songs query finds the video; the first search finds nothing eligible at all.
            if (query.contains("movie songs")) page("FILMVIDEO03", "Song Name - Video Song | Film | Some Singer", "Sun TV", verified = true)
            else RecordedVideoSearch.EMPTY
        }
        val result = playing("Song Name", "TRACKPREF03")
        assertEquals(SyncStage.MEASURED, result?.syncStage)
        assertEquals("FILMVIDEO03", result?.videoId)
    }

    @Test fun aSongWhoseOwnAudioCannotBeReadIsNotRememberedAsOneWithoutAVideo() = runBlocking {
        val audio = SyntheticMedia.recording()
        // The song's own file is missing (its lookup failed); the candidate is readable.
        configure(mapOf("FILMVIDEO04" to SyntheticMedia.file(directory, "video.mp4", audio))) {
            page("FILMVIDEO04", "Song Name Video Song | Film | Some Singer", "Sun TV", verified = true)
        }
        assertEquals(SyncStage.NONE, playing("Song Name", "TRACKPREF04")?.syncStage)
        assertTrue(synchronized(lines) { lines.any { "the song's own audio could not be read" in it } }, "said why")
        delay(300)
        val before = searches.get()
        playing("Song Name", "TRACKPREF04")
        assertTrue(searches.get() > before, "looked up again, not answered with \"no video\"")
    }

    private fun page(id: String, title: String, channel: String, verified: Boolean = false, artist: Boolean = false): String {
        val badge = when {
            artist -> "BADGE_STYLE_TYPE_VERIFIED_ARTIST"
            verified -> "BADGE_STYLE_TYPE_VERIFIED"
            else -> "BADGE_STYLE_TYPE_UNKNOWN"
        }
        return """{"contents":[{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},""" +
            """"ownerText":{"runs":[{"text":"$channel"}]},"lengthText":{"simpleText":"2:20"},""" +
            """"ownerBadges":[{"metadataBadgeRenderer":{"style":"$badge"}}]}}]}"""
    }

    private companion object {
        const val ARTIST = "Some Singer"
    }
}
