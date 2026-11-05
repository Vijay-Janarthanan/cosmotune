package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.listening.*
import com.cosmotune.listening.RemoteTrack
import com.cosmotune.music.data.model.Song
import com.cosmotune.remote.*
import com.cosmotune.ui.listening.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class SynchronizationInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val queued = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var applied: List<SyncRecord>? = null
    private fun show(): File {
        val file = File(temporary.newFolder(), "listening.json")
        val original = Song("original", "Original song", "Singer", null)
        val linked = newLinkedPlaylist("youtube", "PL123", RemotePlaylist("Evening", listOf(RemoteTrack(original))))
        ListeningStore(file).update { it.copy(linkedPlaylists = listOf(linked)) }
        val host = ListeningToolsHost(file, ListeningLibrary(listOf(original)), queue = { ListeningQueue(emptyList(), 0, 0, 0, false) },
            replay = { ReplayTracks("Replay", emptyList()) }, createPlaylist = { _, _ -> }, restore = {}, play = {}, openRelease = { _, _ -> },
            findMusic = {}, importMusic = {}, applySetup = { _, _ -> }, exportFile = { _, _ -> false }, importFile = { null }, existingUser = true,
            syncLibraryReady = true, syncPlaylists = listOf(SyncPlaylist("mix", "Evening mix", listOf(SyncTrack("original", "Original song", "Singer")))),
            applySyncedPlaylists = { records, _ -> applied = records }, queuePlaylistAdditions = { rows, _ -> queued += rows.map { it.videoId } },
            fetchPlaylist = { _, _ -> RemotePlaylist("Evening", listOf(RemoteTrack(original), RemoteTrack(original.copy(videoId = "new", title = "New song")))) })
        compose.setContent { MaterialTheme { ListeningTools(true, host, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:tab:DEVICE_SYNC").fetchSemanticsNodes().isNotEmpty() }
        return file
    }

    @Test fun publishingRequiresSelectionAndNativeApplyUsesTheReviewedPortableRecords() {
        val file = show()
        compose.onNodeWithTag("listening:tab:DEVICE_SYNC").performScrollTo().performClick()
        compose.onNodeWithText("Publish selected playlists").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Evening mix").performScrollTo().performClick()
        compose.onNodeWithText("Publish selected playlists").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { File(file.parentFile, "device-sync/journal.json").isFile }
        compose.onNodeWithTag("listening:device-sync").performScrollToNode(hasText("Apply shared playlists to this library"))
        compose.onNodeWithText("Apply shared playlists to this library").performClick()
        compose.waitUntil(10_000) { applied != null }
        assertEquals("Evening mix", applied!!.single().playlist?.title)
        assertEquals(listOf("original"), applied!!.single().playlist?.tracks?.map { it.id })
        assertTrue(queued.isEmpty())
    }

    @Test fun futurePlaylistDownloadsAreOptInAndCanBeStoppedWithoutRemovingFiles() {
        val file = show()
        compose.onNodeWithTag("listening:tab:DOWNLOAD_UPDATES").performScrollTo().performClick()
        compose.onNodeWithText("Automatic additions are off").assertExists()
        compose.onNodeWithText("Download future additions").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { PlaylistDownloadUpdates.open(File(file.parentFile, "playlist-download-updates.json")).watches.value.size == 1 }.getOrDefault(false) }
        compose.onNodeWithText("Check now").performScrollTo().performClick()
        compose.waitUntil(10_000) { queued == listOf("new") }
        compose.onNodeWithText("Stop automatic additions").performScrollTo().performClick()
        compose.waitUntil(10_000) { PlaylistDownloadUpdates.open(File(file.parentFile, "playlist-download-updates.json")).watches.value.none { it.enabled } }
        assertEquals(listOf("new"), queued)
    }
}
