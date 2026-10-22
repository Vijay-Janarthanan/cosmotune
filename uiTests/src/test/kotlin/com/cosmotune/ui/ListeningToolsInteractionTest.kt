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

    private fun show(width: Dp = 960.dp, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            MaterialTheme {
                ListeningTools(true, ListeningToolsHost(
                    file = file,
                    library = ListeningLibrary(listOf(song), likedIds = setOf(song.videoId)),
                    queue = { ListeningQueue(listOf(song), 0, 56000, 2, true) },
                    replay = { ReplayTracks("Replay · October 2026", listOf(song)) },
                    createPlaylist = { title, songs -> playlist = title to songs },
                    restore = { restored = it }, play = {}, openRelease = { _, _ -> },
                    findMusic = {}, importMusic = {}, applySetup = { _, _ -> },
                    exportFile = { _, _ -> true }, importFile = { null }, existingUser = true,
                    connectionCheck = { ListeningDiagnostics(0, listOf(DiagnosticCheck("Music catalogue", true, "Search request succeeded"), DiagnosticCheck("Media stream", false, "Request timed out"))) },
                ), {}, maxWidth = width)
            }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("listening:session:name").fetchSemanticsNodes().isNotEmpty() }
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

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target = File("build/reports/listening-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("listening:panel").captureToImage().toAwtImage(), "png", target)
    }
}
