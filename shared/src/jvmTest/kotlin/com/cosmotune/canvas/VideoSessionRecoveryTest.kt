package com.cosmotune.canvas

import com.opencanvas.core.OpenCanvas
import com.opencanvas.core.OpenCanvasConfig
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.stream.SongIdentity
import com.opencanvas.core.stream.SyncOffset
import com.opencanvas.core.stream.SyncStore
import com.opencanvas.core.sync.SyncSegment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whole lookups through [OpenCanvas], with recorded-style search answers, synthetic media and no network. */
class VideoSessionRecoveryTest {
    private val directory: File = Files.createTempDirectory("canvas-session-").toFile()
    private val searches = AtomicInteger()

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
            communityMapsUrl = null, httpClient = http))
    }

    private suspend fun resolve(title: String, durationSec: Long, track: String): OpenCanvasTrack? = withTimeout(30_000) {
        OpenCanvas.resolve(title, ARTIST, durationSec, mode = OpenCanvasMode.FULL_SYNCED_VIDEO, trackVideoId = track, album = "Film")
    }

    @Test fun stillPictureUploadsAreSkippedWithoutEndingTheSearch() = runBlocking {
        val audio = SyntheticMedia.recording()
        configure(mapOf(
            "TRACK000001" to SyntheticMedia.file(directory, "song.mp4", audio),
            "ARTTRACK001" to SyntheticMedia.file(directory, "art.mp4", audio, moving = false),
            "SUNTVVIDEO1" to SyntheticMedia.file(directory, "video.mp4", audio),
        )) { query ->
            // Only the expanded search's film query finds the TV channel's video; every other query only the art track.
            if (query.contains("movie songs")) page(Result("SUNTVVIDEO1", "Song Name - Music Video | Film | Some Singer", "Sun TV", verified = true))
            else page(Result("ARTTRACK001", "Song Name (From \"Film\")", ARTIST, artist = true))
        }
        val result = resolve("Song Name (From \"Film\")", 140, "TRACK000001")
        assertEquals(SyncStage.MEASURED, result?.syncStage)
        assertEquals("SUNTVVIDEO1", result?.videoId)
    }

    @Test fun aTimeoutIsNotRememberedAsNoVideo() = runBlocking {
        configure(mapOf("TRACK000002" to SyntheticMedia.file(directory, "song.mp4", SyntheticMedia.recording()))) { query ->
            // The one candidate cannot be measured, and the expanded search times out.
            if (query.contains("-audio") || query.contains("movie songs")) throw InterruptedIOException("timeout")
            page(Result("UNREADABLE1", "Song Name - Official Video", ARTIST, artist = true))
        }
        assertEquals(SyncStage.NONE, resolve("Song Name", 140, "TRACK000002")?.syncStage)
        delay(300)
        val before = searches.get()
        resolve("Song Name", 140, "TRACK000002")
        assertTrue(searches.get() > before, "a lookup that timed out is asked again, not answered with \"no video\"")
    }

    @Test fun anAnsweredSearchWithNothingThatLinesUpIsBelievedForAWhile() = runBlocking {
        configure(mapOf("TRACK000003" to SyntheticMedia.file(directory, "song.mp4", SyntheticMedia.recording()))) { query ->
            if (query.contains("-audio") || query.contains("movie songs")) RecordedVideoSearch.EMPTY
            else page(Result("UNREADABLE2", "Song Name - Official Video", ARTIST, artist = true))
        }
        assertEquals(SyncStage.NONE, resolve("Song Name", 140, "TRACK000003")?.syncStage)
        delay(300)
        val before = searches.get()
        resolve("Song Name", 140, "TRACK000003")
        assertEquals(before, searches.get(), "every query answered: the miss stands for its short while")
    }

    @Test fun aMapIsReusedWhenTheSameSongArrivesUnderAnotherTrackId() = runBlocking {
        val store = SyncStore(File(directory, "maps"))
        val map = SyncOffset(-889, 0.75, listOf(SyncSegment(0, 107_500, 12_632), SyncSegment(107_500, 140_000, -889)), 0.95)
        store.put("TRACKAAAAAA", "VIDEO000001", map, verifiedSelection = true)
        store.putSong(SongIdentity.of("Song Name", ARTIST, 140)!!,
            SyncStore.SongVideo("TRACKAAAAAA", "VIDEO000001", 140, "Song Name - Music Video | Film", "Sun TV", false, true))
        val audio = SyntheticMedia.recording()
        configure(mapOf(
            "TRACKCCCCCC" to SyntheticMedia.file(directory, "longer-copy.mp4", audio),
            "VIDEO000001" to SyntheticMedia.file(directory, "video.mp4", audio),
        )) { RecordedVideoSearch.EMPTY }

        // Another upload of the same song, the same length: the known map at once, nothing searched.
        val same = resolve("Song Name (From \"Film\")", 141, "TRACKBBBBBB")
        assertEquals(SyncStage.MEASURED, same?.syncStage)
        assertEquals("VIDEO000001", same?.videoId)
        assertEquals(listOf(12_632L, -889L), same?.syncSegments?.map { it.offsetMs })
        // Another length (a different copy): the known video is lined up again with this one, still without a search.
        val other = resolve("Song Name", 200, "TRACKCCCCCC")
        assertEquals(SyncStage.MEASURED, other?.syncStage)
        assertEquals("VIDEO000001", other?.videoId)
        assertEquals(0, searches.get(), "the song's video was known: no search")
    }

    @Test fun aCachedReactionMapIsDroppedOnLoad() {
        val maps = File(directory, "owner-like").apply { mkdirs() }
        // The map measured for "One Name" on a reaction upload: its two parts are 14 s apart in the video (talk).
        File(maps, "MUIzn5TAkXE_VzQCnPnTDbM.json").writeText("""{"trackId":"MUIzn5TAkXE","videoId":"VzQCnPnTDbM","offsetMs":50454,""" +
            """"confidence":0.6442183690133331,"coverage":0.9306426332288401,"segments":[{"songStartMs":0,"songEndMs":162500,"offsetMs":50454},""" +
            """{"songStartMs":162500,"songEndMs":204160,"offsetMs":64791}],"selectionPolicy":3}""")
        File(maps, "kynWdUG1NvY_wc8XNJ2x1Xs.json").writeText("""{"trackId":"kynWdUG1NvY","videoId":"wc8XNJ2x1Xs","offsetMs":-889,""" +
            """"confidence":0.7461680065218338,"coverage":0.9481158718631486,"segments":[{"songStartMs":0,"songEndMs":107500,"offsetMs":12632},""" +
            """{"songStartMs":107500,"songEndMs":247860,"offsetMs":-889}],"selectionPolicy":3}""")
        val store = SyncStore(maps)
        assertNull(store.verifiedVideoFor("MUIzn5TAkXE"), "the reaction is not the song's video any more")
        assertNull(store["MUIzn5TAkXE", "VzQCnPnTDbM"])
        assertEquals("wc8XNJ2x1Xs", store.verifiedVideoFor("kynWdUG1NvY"), "a real music video's map stays")
        // A publisher's video song with a 20 s dialogue break inside the song keeps its map; the same from anyone else does not.
        val dialogue = SyncOffset(5_000, 0.8, listOf(SyncSegment(0, 100_000, 5_000), SyncSegment(100_000, 205_000, 25_000)), 0.92)
        store.put("TRACKSUNTV1", "SUNTVDIALOG", dialogue, verifiedSelection = true,
            video = SyncStore.ChosenVideo("One Name - Video Song | Jailer 2", "Sun TV", publisher = true))
        store.put("TRACKOTHER1", "OTHERDIALOG", dialogue, verifiedSelection = true,
            video = SyncStore.ChosenVideo("One Name - Video Song | Jailer 2", "Kollywood Clips 24", publisher = false))
        store.put("TRACKREACT1", "REACTDIALOG", dialogue, verifiedSelection = true,
            video = SyncStore.ChosenVideo("One Name - Video Song | Jailer 2", "Producer Reacts", publisher = true))
        val reloaded = SyncStore(maps)
        assertEquals("SUNTVDIALOG", reloaded.verifiedVideoFor("TRACKSUNTV1"))
        assertNotNull(reloaded["TRACKSUNTV1", "SUNTVDIALOG"])
        assertNull(reloaded.verifiedVideoFor("TRACKOTHER1"))
        assertNull(reloaded.verifiedVideoFor("TRACKREACT1"))
        // A song entry naming a reaction channel is ignored the same way.
        val song = SongIdentity.of("One Name", "Anirudh Ravichander & Super Subu", 205)!!
        store.putSong(song, SyncStore.SongVideo("MUIzn5TAkXE", "OTHERVIDEO1", 205, "One Name - Jailer 2", "Producer Reacts"))
        assertNull(SyncStore(maps).songVideo(song))
        assertNotNull(SyncStore(maps).songVideo(SongIdentity.of("Hangova", "Anirudh Ravichander, Heisenberg", 248)!!.also {
            SyncStore(maps).putSong(it, SyncStore.SongVideo("kynWdUG1NvY", "wc8XNJ2x1Xs", 248, "Hangova - Music Video | DC", "Sun TV", false, true))
        }))
    }

    private class Result(val id: String, val title: String, val channel: String, val verified: Boolean = false, val artist: Boolean = false)

    private fun page(vararg results: Result): String = """{"contents":[""" + results.joinToString(",") { result ->
        val badge = when {
            result.artist -> "BADGE_STYLE_TYPE_VERIFIED_ARTIST"
            result.verified -> "BADGE_STYLE_TYPE_VERIFIED"
            else -> "BADGE_STYLE_TYPE_UNKNOWN"
        }
        """{"videoRenderer":{"videoId":"${result.id}","title":{"runs":[{"text":"${result.title.replace("\"", "\\\"")}"}]},""" +
            """"ownerText":{"runs":[{"text":"${result.channel}"}]},"lengthText":{"simpleText":"2:20"},""" +
            """"ownerBadges":[{"metadataBadgeRenderer":{"style":"$badge"}}]}}"""
    } + "]}"

    private companion object {
        const val ARTIST = "Some Singer"
    }
}
