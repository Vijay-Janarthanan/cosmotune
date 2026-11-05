package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.listening.*
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.listening.*
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class MusicPlaylistImportInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val directory = Files.createTempDirectory("cosmotune-playlist-ui-").toFile()
    private var saved: List<Song>? = null
    private var calls = 0

    private fun show(file: MusicPlaylistFile, library: List<Song> = emptyList()) {
        compose.setContent {
            MaterialTheme {
                ListeningTools(true, ListeningToolsHost(
                    file = File(directory, "listening.json"), library = ListeningLibrary(library),
                    queue = { ListeningQueue(emptyList(), 0, 0, 0, false) }, replay = { ReplayTracks("", emptyList()) },
                    createPlaylist = { _, songs -> saved = songs }, restore = {}, play = {}, openRelease = { _, _ -> },
                    findMusic = {}, importMusic = {}, applySetup = { _, _ -> }, exportFile = { _, _ -> false }, importFile = { null },
                    importPlaylistFile = { file }, matchPlaylistTrack = { row -> calls++; Song("matched", row.title, row.artist, null) },
                    existingUser = true,
                ), {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:tab:BACKUP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:tab:BACKUP").performScrollTo().performClick()
        compose.onNodeWithTag("listening:playlist:import").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:playlist:preview").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun previewSkipsUntrustedLinksAndSavesOnlyCheckedOccurrencesThroughTheHost() {
        val local = File(directory, "song.flac").apply { writeText("audio") }
        val track = Song("local", "Local song", "Artist", null, localPath = local.path)
        val text = "#EXTM3U\n${local.toURI()}\nhttps://youtu.be/dQw4w9WgXcQ\nhttps://youtu.be/dQw4w9WgXcQ\nhttps://untrusted.invalid/list.m3u8"
        show(MusicPlaylistFile("Mixed.m3u8", text), listOf(track))
        assertEquals(null, saved, "opening the file does not save or play it")
        assertEquals(0, calls, "opening a document never starts catalogue searches")
        compose.onNodeWithTag("listening:playlist:status").assertTextEquals("3 ready · 0 need matching · 1 skipped")
        compose.onNodeWithTag("listening:playlist:preview").performScrollTo()
        compose.onNodeWithTag("listening:playlist:preview").performScrollToNode(hasTestTag("listening:playlist:include:2"))
        compose.onNodeWithTag("listening:playlist:include:2").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 ready · 0 need matching · 1 skipped").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("listening:playlist:save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(listOf("local", "dQw4w9WgXcQ"), saved!!.map { it.videoId })
        compose.onNodeWithTag("listening:playlist:save").assertIsNotEnabled()
    }

    @Test fun csvRequiresAnExplicitMatchAndKeepsRepeatedRowsInTheSavedPlaylist() {
        show(MusicPlaylistFile("Songs.csv", "title,artist\nNew song,Singer\nNew song,Singer"))
        assertEquals(0, calls)
        compose.onNodeWithTag("listening:playlist:save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("listening:playlist:match").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 ready · 0 need matching · 0 skipped").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, calls)
        compose.onNodeWithTag("listening:playlist:save").performScrollTo().performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals(listOf("matched", "matched"), saved!!.map { it.videoId })
    }
}
