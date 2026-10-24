package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.listening.*
import com.cosmotune.ui.listening.*
import com.cosmotune.music.data.model.Song
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Click real controls, persist their result, and exercise the platform callbacks without network calls. */
@OptIn(ExperimentalTestApi::class)
class ListeningToolsInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val file = Files.createTempDirectory("cosmotune-ui-").resolve("listening.json").toFile()
    private val song = Song("test-track", "Evening light", "The Coast", null)
    private var restored: ListeningSession? = null
    private var playlist: Pair<String, List<Song>>? = null
    private var exported: Pair<String, String>? = null
    private var played: List<Song>? = null
    private var remoteRows = listOf("a", "b", "c")
    private var rescanned: String? = null
    private val lookups = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun show(width: Dp = 960.dp, fontScale: Float = 1f, tracks: List<Song> = listOf(song), libraryTracks: List<Song> = tracks) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            MaterialTheme {
                ListeningTools(true, ListeningToolsHost(
                    file = file,
                    library = ListeningLibrary(libraryTracks, likedIds = setOf(song.videoId)),
                    queue = { ListeningQueue(tracks, 0, 56000, 2, true) },
                    replay = { ReplayTracks("Replay · October 2026", listOf(song)) },
                    createPlaylist = { title, songs -> playlist = title to songs },
                    restore = { restored = it }, play = { played = it }, openRelease = { _, _ -> },
                    findMusic = {}, importMusic = {}, applySetup = { _, _ -> },
                    exportFile = { name, text -> exported = name to text; true }, importFile = { null }, existingUser = true,
                    onLocalFileChanged = { rescanned = it },
                    releases = { artist -> lookups += artist.id; listOf(ReleaseNotice("MPREb_${lookups.size}", artist.id, artist.name, "Single ${lookups.size}")) },
                    fetchPlaylist = { source, id -> assertEquals("youtube" to "PLroad", source to id); RemotePlaylist("", remoteRows.map { RemoteTrack(Song(it, "Track $it", "Band", null)) }) },
                    connectionCheck = { ListeningDiagnostics(0, listOf(DiagnosticCheck("Music catalogue", true, "Search request succeeded"), DiagnosticCheck("Media stream", false, "Request timed out"))) },
                ), {}, maxWidth = width)
            }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("listening:session:name").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun localQueueExportHandsTheRealPlaylistToThePlatformFileChooser() {
        val audio = File(file.parentFile, "local song.mp3").apply { writeBytes(byteArrayOf(1)) }
        show(tracks = listOf(song, song.copy(videoId = "online-only")), libraryTracks = listOf(song.copy(localPath = audio.path)))
        compose.onNodeWithTag("listening:tab:BACKUP").performScrollTo().performClick()
        compose.onNodeWithTag("listening:m3u8:queue").performScrollTo().performClick()
        compose.waitUntil(10_000) { exported != null }
        assertEquals("cosmotune-queue.m3u8", exported?.first)
        assertEquals(1, exported?.second?.lines()?.count { it.startsWith("#EXTINF:") })
        compose.onNodeWithText("Playlist saved: 1 local tracks, 1 skipped").assertExists()
    }

    @Test fun sessionCanBeSavedAndRestoredWithExactTransportState() {
        show()
        compose.onNodeWithTag("listening:session:name").performTextInput("Evening")
        compose.onNodeWithTag("listening:session:save").performClick()
        compose.waitUntil(10000) { file.exists() && runCatching { ListeningStore(file).state.value.sessions.size == 1 }.getOrDefault(false) }
        compose.onNodeWithText("Open session").performScrollTo().performClick()
        compose.waitUntil(10000) { restored != null }
        val session = assertNotNull(restored)
        assertEquals(56000L, session.positionMs)
        assertEquals(2, session.repeat)
        assertEquals(true, session.shuffle)
        assertEquals("Evening", ListeningStore(file).state.value.sessions.single().name)
        screenshot("sessions")
    }

    @Test fun replayButtonCreatesARealPlaylistAndPreventsRepeatedSubmission() {
        show()
        compose.onNodeWithTag("listening:tab:REPLAY").performScrollTo().performClick()
        compose.onNodeWithTag("listening:replay:load").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("listening:replay:create").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:replay:create").performScrollTo().performClick()
        compose.waitUntil(10000) { playlist != null }
        assertEquals(listOf(song), playlist!!.second)
        compose.onNodeWithTag("listening:replay:create").assertIsNotEnabled()
        screenshot("replay")
    }

    @Test fun smartPlaylistRulesPersistFromTheRenderedForm() {
        show()
        compose.onNodeWithTag("listening:tab:SMART").performScrollTo().performClick()
        compose.onNodeWithTag("listening:smart:name").performTextInput("My favourites")
        compose.onNodeWithTag("listening:smart:count").assertTextEquals("1 matching tracks")
        compose.onNodeWithTag("listening:smart:save").performScrollTo().performClick()
        compose.waitUntil(10000) { file.exists() && runCatching { ListeningStore(file).state.value.smartPlaylists.isNotEmpty() }.getOrDefault(false) }
        assertEquals("My favourites", ListeningStore(file).state.value.smartPlaylists.single().name)
        screenshot("smart-playlists")
    }

    @Test fun diagnosticsDisplayIndependentApiAndMediaResults() {
        show()
        compose.onNodeWithTag("listening:tab:CONNECTION").performScrollTo().performClick()
        compose.onNodeWithTag("listening:diagnostics:run").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Media stream · Needs attention").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Music catalogue · Connected").assertExists()
        compose.onNodeWithText("Request timed out").assertExists()
        screenshot("connection")
    }

    @Test fun narrowLargeTextLayoutKeepsActionsReachable() {
        show(420.dp, 1.5f)
        compose.onNodeWithTag("listening:session:name").performScrollTo().performTextInput("Large text")
        compose.onNodeWithTag("listening:session:save").performScrollTo().performClick()
        compose.waitUntil(10000) { file.exists() }
        compose.onNodeWithText("Open session").performScrollTo().assertIsDisplayed()
        screenshot("sessions-narrow-large-text")
    }

    @Test fun libraryReviewScansActualFilesExportsAndAppliesOnlyPreviewedSessionLocations() {
        val directory = file.parentFile
        val local = File(directory, "original.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(128)) }
        val copy = File(directory, "copy.flac").apply { writeBytes(local.readBytes()) }
        val missing = File(directory, "old-location.flac")
        val recording = song.copy(videoId = "source:recording", localPath = local.path)
        val saved = ListeningTrack.of(recording).copy(path = missing.path, entryId = "keep-entry")
        ListeningStore(file).update { it.copy(sessions = listOf(ListeningSession(id = "saved", name = "Keep", tracks = listOf(saved), index = 0, positionMs = 1234))) }
        show(libraryTracks = listOf(recording, recording.copy(videoId = "source:copy", localPath = copy.path)))
        compose.onNodeWithTag("listening:tab:LIBRARY").performScrollTo().performClick()
        compose.onNodeWithTag("listening:audit:verify").performClick()
        compose.onNodeWithTag("listening:audit:scan").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("listening:audit:export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:audit:export").performScrollTo().performClick()
        compose.waitUntil(10000) { exported != null }
        assertEquals("cosmotune-library-review.json", exported!!.first)
        kotlin.test.assertTrue(exported!!.second.contains("IDENTICAL_BYTES"))
        kotlin.test.assertTrue(exported!!.second.contains("MISSING"))
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:audit:repair-preview"))
        compose.onNodeWithTag("listening:audit:repair-preview").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Review 1 location changes below").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:audit:repair-apply"))
        compose.onNodeWithTag("listening:audit:repair-apply").performClick()
        compose.waitUntil(10000) { ListeningStore(file).state.value.sessions.single().tracks.single().path == local.path }
        val repaired = ListeningStore(file).state.value.sessions.single()
        assertEquals(1234L, repaired.positionMs)
        assertEquals("keep-entry", repaired.tracks.single().entryId)
        screenshot("library-review")
    }

    @Test fun audioAnalysisMeasuresARealWavFileAndFiltersByMeasuredQuality() {
        val rate = 44_100
        val samples = ShortArray(rate * 4 * 2) { i -> (0.0708 * 32767 * kotlin.math.sin(2 * Math.PI * 1000.0 * (i / 2) / rate)).toInt().toShort() }
        val data = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + data.size)
            .put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4)
            .putShort(4).putShort(16).put("data".toByteArray()).putInt(data.size).array()
        val wav = File(file.parentFile, "measured.wav").apply { writeBytes(header + data) }
        val other = File(file.parentFile, "other.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(64)) }
        show(libraryTracks = listOf(song.copy(videoId = "local:wav", localPath = wav.path), song.copy(videoId = "local:flac", title = "Unmeasured", localPath = other.path)))
        compose.onNodeWithTag("listening:tab:LIBRARY").performScrollTo().performClick()
        compose.onNodeWithTag("listening:analysis:run").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("listening:analysis:summary").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:analysis:summary").assertTextEquals("1 measured, 0 unchanged, 1 not decodable on this device")
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:analysis:entry"))
        compose.onNodeWithTag("listening:analysis:entry").assertTextContains("-23.0 LUFS", substring = true)
            .assertTextContains("ReplayGain +5.0 dB", substring = true).assertTextContains("PCM 44.1 kHz 16-bit", substring = true)
        assertEquals(-9.0, com.cosmotune.audio.LibraryLoudness.loudnessDbFor("local:wav")!!, 0.15)
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:quality:UNMEASURED"))
        compose.onNodeWithTag("listening:quality:UNMEASURED").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("listening:analysis:entry").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasText("Unmeasured", substring = true))
        compose.onNodeWithTag("listening:quality:LOSSLESS").performScrollTo().performClick()
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:analysis:entry"))
        screenshot("library-analysis")
    }

    @Test fun linkedPlaylistPreviewsSourceChangesKeepsPlaceholdersAndSavesPlayableTracks() {
        show()
        compose.onNodeWithTag("listening:tab:LINKED").performScrollTo().performClick()
        compose.onNodeWithTag("listening:linked:link").performTextInput("https://music.youtube.com/playlist?list=PLroad&si=x")
        compose.onNodeWithTag("listening:linked:name").performTextInput("Road")
        compose.onNodeWithTag("listening:linked:add").performClick()
        compose.waitUntil(10_000) { runCatching { ListeningStore(file).state.value.linkedPlaylists.size == 1 }.getOrDefault(false) }
        remoteRows = listOf("a", "c", "d")
        compose.onNodeWithTag("listening:linked:check").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:linked:preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 new · 1 become placeholders · 0 restored · 0 of your removals kept").assertExists()
        // Nothing is saved before the preview is applied.
        assertEquals(listOf("a", "b", "c"), ListeningStore(file).state.value.linkedPlaylists.single().entries.map { it.track.id })
        compose.onNodeWithTag("listening:linked:apply").performScrollTo().performClick()
        compose.waitUntil(10_000) { ListeningStore(file).state.value.linkedPlaylists.single().entries.size == 4 }
        val saved = ListeningStore(file).state.value.linkedPlaylists.single()
        assertEquals(listOf("a", "b", "c", "d"), saved.entries.map { it.track.id })
        assertEquals(listOf(false, true, false, false), saved.entries.map { it.ghost })
        compose.onNodeWithTag("listening:linked:counts").assertTextEquals("3 playable · 1 placeholders")
        compose.onNodeWithText("Save as playlist").performScrollTo().performClick()
        compose.waitUntil(10_000) { playlist != null }
        assertEquals("Road" to listOf("a", "c", "d"), playlist!!.first to playlist!!.second.map { it.videoId })
        compose.onNodeWithTag("listening:linked:play").performScrollTo().performClick()
        compose.waitUntil(10_000) { played != null }
        assertEquals(listOf("a", "c", "d"), played!!.map { it.videoId })
        compose.onNodeWithText("Playing: 0 from your library, 3 from their sources").assertExists()
        screenshot("linked-playlists")
    }

    @Test fun identicalCopiesMergeAndTagsAreEditedFromTheReviewList() {
        val directory = file.parentFile
        val comment = java.nio.ByteBuffer.allocate(8 + 3 + 4 + 9).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(3).put("enc".toByteArray()).putInt(1).putInt(9).put("TITLE=Old".toByteArray()).array()
        val flac = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + ByteArray(34) { 1 } +
            byteArrayOf(0x84.toByte(), 0, 0, comment.size.toByte()) + comment + ByteArray(256) { 7 }
        val one = File(directory, "one.flac").apply { writeBytes(flac) }
        val two = File(directory, "two.flac").apply { writeBytes(flac) }
        val a = song.copy(videoId = "rec:a", localPath = one.path)
        val b = song.copy(videoId = "rec:b", localPath = two.path)
        ListeningStore(file).update { it.copy(sessions = listOf(ListeningSession(id = "s", name = "Keep", tracks = listOf(ListeningTrack.of(b).copy(entryId = "e1")), index = 0, positionMs = 9))) }
        show(libraryTracks = listOf(a, b))
        compose.onNodeWithTag("listening:tab:LIBRARY").performScrollTo().performClick()
        compose.onNodeWithTag("listening:audit:verify").performClick()
        compose.onNodeWithTag("listening:audit:scan").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:audit:export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:merge:keep:0"))
        compose.onNodeWithTag("listening:merge:keep:0").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:merge:apply").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:merge:apply"))
        compose.onNodeWithTag("listening:merge:apply").performClick()
        compose.waitUntil(10_000) { ListeningStore(file).state.value.sessions.single().tracks.single().path == one.path }
        assertEquals("rec:b", ListeningStore(file).state.value.sessions.single().tracks.single().id)
        kotlin.test.assertTrue(two.isFile)

        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:tags:edit"))
        compose.onAllNodesWithTag("listening:tags:edit")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:tags:TITLE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:tags:TITLE"))
        compose.onNodeWithTag("listening:tags:TITLE").performTextClearance()
        compose.onNodeWithTag("listening:tags:TITLE").performTextInput("Edited title")
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:tags:save"))
        compose.onNodeWithTag("listening:tags:save").performClick()
        compose.waitUntil(10_000) { rescanned != null }
        assertEquals(one.path, rescanned)
        assertEquals("Edited title", LocalTagEditor.read(one)["TITLE"])
        kotlin.test.assertContentEquals(ByteArray(256) { 7 }, one.readBytes().takeLast(256).toByteArray())
    }

    @Test fun smartPlaylistTextAndLengthRulesUpdateTheLiveCount() {
        show()
        compose.onNodeWithTag("listening:tab:SMART").performScrollTo().performClick()
        compose.onNodeWithTag("listening:smart:name").performTextInput("Coast only")
        compose.onNodeWithTag("listening:smart:artist").performScrollTo().performTextInput("coast")
        compose.onNodeWithTag("listening:smart:count").performScrollTo().assertTextEquals("1 matching tracks")
        compose.onNodeWithTag("listening:smart:duration:LONG").performScrollTo().performClick()
        compose.onNodeWithTag("listening:smart:count").performScrollTo().assertTextEquals("0 matching tracks")
        compose.onNodeWithTag("listening:smart:duration:ANY").performScrollTo().performClick()
        compose.onNodeWithTag("listening:smart:limit:25").performScrollTo().performClick()
        compose.onNodeWithTag("listening:smart:save").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { ListeningStore(file).state.value.smartPlaylists.isNotEmpty() }.getOrDefault(false) }
        val saved = ListeningStore(file).state.value.smartPlaylists.single()
        assertEquals("coast" to 25, saved.artistContains to saved.limit)
    }

    @Test fun libraryArtistsCanBeFollowedAndLaterChecksFillTheInbox() {
        show(libraryTracks = listOf(song.copy(artistId = "UCthecoast00001")))
        compose.onNodeWithTag("listening:tab:RELEASES").performScrollTo().performClick()
        compose.onNodeWithTag("listening:releases:library:UCthecoast00001").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { ListeningStore(file).state.value.artists.singleOrNull()?.checkedAt != null }.getOrDefault(false) }
        // The first check is a baseline, so nothing old appears as new.
        assertEquals(0, ListeningStore(file).state.value.inbox.size)
        compose.onNodeWithTag("listening:releases:check").performScrollTo().performClick()
        compose.waitUntil(10_000) { ListeningStore(file).state.value.inbox.isNotEmpty() }
        assertEquals("Single 2", ListeningStore(file).state.value.inbox.single().title)
        compose.onNodeWithText("New · Single 2").performScrollTo().assertExists()
    }

    @Test fun artistExceptionAddedInTheReviewPersistsAndAppliesToMatching() {
        show()
        compose.onNodeWithTag("listening:tab:LIBRARY").performScrollTo().performClick()
        compose.onNodeWithTag("listening:audit:list").performScrollToNode(hasTestTag("listening:artist:exception"))
        compose.onNodeWithTag("listening:artist:exception").performTextInput("Shankar, Ehsaan & Loy")
        compose.onNodeWithTag("listening:artist:add").performClick()
        compose.waitUntil(10_000) { runCatching { ListeningStore(file).state.value.preferences.artistExceptions.isNotEmpty() }.getOrDefault(false) }
        compose.waitUntil(10_000) { com.cosmotune.music.data.model.ArtistIdentity.userExceptions.isNotEmpty() }
        try {
            assertEquals(listOf("Shankar, Ehsaan & Loy"), com.cosmotune.music.data.model.ArtistIdentity.names("Shankar, Ehsaan & Loy"))
        } finally { com.cosmotune.music.data.model.ArtistIdentity.setUserExceptions(emptyList()) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target = File("build/reports/listening-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("listening:panel").captureToImage().toAwtImage(), "png", target)
    }
}
