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
                    restore = { restored = it }, play = {}, openRelease = { _, _ -> },
                    findMusic = {}, importMusic = {}, applySetup = { _, _ -> },
                    exportFile = { name, text -> exported = name to text; true }, importFile = { null }, existingUser = true,
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

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target = File("build/reports/listening-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("listening:panel").captureToImage().toAwtImage(), "png", target)
    }
}
