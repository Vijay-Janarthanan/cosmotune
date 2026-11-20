package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.matcher.VideoCandidate
import com.opencanvas.core.matcher.VideoMatcher
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.MusicVideoSearch
import com.opencanvas.core.stream.PreviewFrames
import com.opencanvas.core.stream.PreviewImage
import com.opencanvas.core.stream.StreamBackend
import com.opencanvas.core.stream.SyncOffset
import com.opencanvas.core.stream.SyncStore
import com.opencanvas.core.sync.SyncSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A film song whose label has no verified badge, and a fan's re-upload that starts a verse in and is cut shorter, lined up
 * first. The label's upload is found by what else of the film it shows, and a map of part of the
 * song is shown only when nothing matches more of it.
 */
class FilmChannelVideoChoiceTest {
    private val directory: File = Files.createTempDirectory("film-channel-").toFile()
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    @AfterTest fun reset() {
        PreviewFrames.decoder = null
        OpenCanvas.configure(OpenCanvasConfig(SyntheticMedia.backend(emptyMap()), instantBackend = null, cacheDirectory = null, communityMapsUrl = null))
        directory.deleteRecursively()
    }

    private fun result(id: String, title: String, channel: String) = VideoCandidate(id, title, channel, 361L)

    @Test fun theChannelThatShowsTheFilmsOtherSongsIsTheFilmsChannel() {
        val results = listOf(
            result("LABELSONG01", "\"Song Name\" Film  Movie Songs | 2k Video | Some Actor", "Film Audio"),
            result("LABELOTHER1", "Other Song  |  Film | 2k Video | Some Actor", "Film Audio"),
            result("FANCUT00001", "Song Name || video songs||Film|| 1080p HD Video Song", "Clips Corner"),
            result("FANSONG0001", "Song Name |Film |Some Actor", "Actor Songs"),
        )
        assertEquals(setOf("filmaudio"), VideoMatcher.filmChannels(results, "Song Name", "Film"),
            "only the channel with another song of the film; a channel with this song alone is anyone's")
        assertTrue(VideoMatcher.filmChannels(results, "Song Name", null).isEmpty(), "no film, no film channel")
        val titleTrack = listOf(result("A1", "Picture Title Song | Picture", "Film Audio"), result("A2", "Picture Theme | Picture", "Film Audio"))
        assertTrue(VideoMatcher.filmChannels(titleTrack, "Picture", "Picture").isEmpty(), "a title track's name is every song's")
    }

    @Test fun anEmptyAnswerIsAskedOnceMoreTheOtherWay() = runBlocking {
        val asked = Collections.synchronizedList(ArrayList<String>())
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            asked += request.method
            // The results page comes back with no videos at all; the search endpoint has them.
            RecordedVideoSearch.respond(request, if (request.method == "GET") RecordedVideoSearch.EMPTY else page(
                Upload("FILMLABEL01", "Song Name | Film | 2k Video", "Film Audio")))
        }).build()
        val outcome = MusicVideoSearch(http).findOutcome("Song Name", ARTIST, 140, album = "Film", foreground = MutableStateFlow(false))
        assertTrue(asked.take(2) == listOf("GET", "POST"), "the empty page was asked again through the search endpoint: $asked")
        assertTrue(outcome.candidates.any { it.videoId == "FILMLABEL01" }, outcome.candidates.joinToString { it.videoId })
    }

    @Test fun theFilmsChannelsWholeSongBeatsAFansCutThatSaysVideoSong() = runBlocking {
        val audio = SyntheticMedia.recording()
        configure(mapOf(
            "TRACK000001" to SyntheticMedia.file(directory, "song.mp4", audio),
            // The song from 35 % in: the first verse is cut, as in a fan's re-upload.
            "FANCUT00001" to SyntheticMedia.file(directory, "cut.mp4", audio.copyOfRange(2100, audio.size)),
            "FILMLABEL01" to SyntheticMedia.file(directory, "label.mp4", audio),
        )) {
            page(Upload("FANCUT00001", "Song Name || Video Song || Film || 1080p HD Video Song", "Clips Corner"),
                Upload("FILMLABEL01", "Song Name | Film | 2k Video", "Film Audio"),
                Upload("FILMLABEL02", "Other Song | Film | 2k Video", "Film Audio"))
        }
        val track = resolve()
        assertEquals(SyncStage.MEASURED, track?.syncStage, lines.joinToString("\n"))
        assertEquals("FILMLABEL01", track?.videoId, lines.joinToString("\n"))
    }

    @Test fun aCutIsShownWhenNoUploadMatchesMoreOfTheSong() = runBlocking {
        val audio = SyntheticMedia.recording()
        configure(mapOf(
            "TRACK000001" to SyntheticMedia.file(directory, "song.mp4", audio),
            "FANCUT00001" to SyntheticMedia.file(directory, "cut.mp4", audio.copyOfRange(2100, audio.size)),
        )) { page(Upload("FANCUT00001", "Song Name || Video Song || Film || 1080p HD Video Song", "Clips Corner")) }
        val track = resolve()
        assertEquals("FANCUT00001", track?.videoId, lines.joinToString("\n"))
        assertTrue(lines.any { "lines up, with" in it && "FANCUT00001" in it }, "the log says only a part of the song lines up")
    }

    @Test fun anOlderChoiceOfAFansCutIsSearchedAgainButNotOneMadeUnderTodaysRules() {
        val maps = File(directory, "maps").apply { mkdirs() }
        // As 1.0.29 stored a fan's cut: no publisher, 67 % of the song.
        File(maps, "TRACKCUTOLD_FANCUTOLD01.json").writeText("""{"trackId":"TRACKCUTOLD","videoId":"FANCUTOLD01","offsetMs":-42655,""" +
            """"confidence":0.7355691818446329,"coverage":0.6692210791517951,"segments":[{"songStartMs":37500,"songEndMs":342500,""" +
            """"offsetMs":-18120}],"videoTitle":"One Name || video songs||Film","channelTitle":"Clips Corner","publisher":false,"selectionPolicy":3}""")
        File(maps, "TRACKWHOLE1_FANWHOLE001.json").writeText("""{"trackId":"TRACKWHOLE1","videoId":"FANWHOLE001","offsetMs":0,""" +
            """"confidence":0.8,"coverage":0.93,"segments":[{"songStartMs":0,"songEndMs":200000,"offsetMs":0}],""" +
            """"videoTitle":"One Name Video Song","channelTitle":"Clips Corner","publisher":false,"selectionPolicy":3}""")
        val store = SyncStore(maps)
        assertNull(store.verifiedVideoFor("TRACKCUTOLD"), "a fan's cut chosen before other uploads were tried is looked for again")
        assertEquals("FANWHOLE001", store.verifiedVideoFor("TRACKWHOLE1"), "anyone's upload of the whole song stays")
        store.put("TRACKCUTNEW", "FANCUTNEW01", SyncOffset(-18_000, 0.74, listOf(SyncSegment(37_500, 342_500, -18_000)), 0.67),
            verifiedSelection = true, video = SyncStore.ChosenVideo("One Name Video Song", "Clips Corner", publisher = false))
        assertEquals("FANCUTNEW01", SyncStore(maps).verifiedVideoFor("TRACKCUTNEW"), "a cut chosen after everything else was tried stays")
    }

    /**
     * Every upload has preview frames that keep changing, as a video song's do: the synthetic files' even frame sizes would
     * otherwise be read as a poster's.
     */
    private fun configure(files: Map<String, com.opencanvas.core.stream.AudioStream>, answer: (String) -> String) {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            RecordedVideoSearch.respond(request, if ("/sb/" in request.url.encodedPath) "sheet" else answer(RecordedVideoSearch.queryOf(request)))
        }).build()
        val media = SyntheticMedia.backend(files)
        val backend = object : StreamBackend by media {
            override suspend fun storyboardSpec(videoId: String) = "https://example.invalid/sb/${'$'}L/${'$'}N.webp?v=$videoId|160#90#10#5#2#1000#M${'$'}M#sig"
        }
        PreviewFrames.decoder = { _ ->
            // Ten 160 x 90 frames, each a different brightness from the one before.
            PreviewImage(800, 180, IntArray(800 * 180) { pixel ->
                val frame = (pixel % 800) / 160 + 5 * ((pixel / 800) / 90)
                val level = if (frame % 2 == 0) 40 else 200
                (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            })
        }
        OpenCanvas.configure(OpenCanvasConfig(backend, instantBackend = null, cacheDirectory = File(directory, "maps"),
            communityMapsUrl = null, httpClient = http, log = { lines += it }))
    }

    private suspend fun resolve() = withTimeout(60_000) {
        OpenCanvas.resolve("Song Name", ARTIST, 140, mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = "TRACK000001", album = "Film")
    }

    private class Upload(val id: String, val title: String, val channel: String)

    /** A search answer of unverified uploads, each as long as the song. */
    private fun page(vararg uploads: Upload): String = """{"contents":[""" + uploads.joinToString(",") { upload ->
        """{"videoRenderer":{"videoId":"${upload.id}","title":{"runs":[{"text":"${upload.title}"}]},""" +
            """"ownerText":{"runs":[{"text":"${upload.channel}"}]},"lengthText":{"simpleText":"2:20"},""" +
            """"ownerBadges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_UNKNOWN"}}]}}"""
    } + "]}"

    private companion object {
        const val ARTIST = "Some Singer"
    }
}
