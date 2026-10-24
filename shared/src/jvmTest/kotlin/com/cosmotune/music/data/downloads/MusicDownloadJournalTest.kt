package com.cosmotune.music.data.downloads

import com.cosmotune.music.data.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MusicDownloadJournalTest {
    @Test fun restartRetainsOrderQualityAndFailuresAndOldCompletionCannotRemoveARetry() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("music-queue-").toFile()
        val file = File(folder, "queue.json")
        val song = Song("src:provider::id", "Song", "Artist", null, durationText = "3:00")
        try {
            MusicDownloadJournal(file).use { journal ->
                journal.enqueue(song, 1, "LOSSLESS", "Album")
                journal.phase(song.videoId, 1, MusicDownloadPhase.RUNNING)
                journal.enqueue(song.copy(videoId = "other"), 2, "HIGH")
                journal.phase("other", 2, MusicDownloadPhase.FAILED, "Network unavailable")
                journal.flush()
            }
            MusicDownloadJournal(file).use { restored ->
                assertEquals(listOf(1L, 2L), restored.entries.value.map { it.sequence })
                assertEquals("LOSSLESS", restored.entries.value.first().quality)
                assertEquals("Network unavailable", restored.entries.value.last().error)
                restored.enqueue(song, 3, "LOW")
                restored.remove(song.videoId, 1)
                assertEquals(3L, restored.entries.value.last().sequence)
                restored.remove(song.videoId, 3)
                restored.flush()
            }
            MusicDownloadJournal(file).use { assertEquals(listOf("other"), it.entries.value.map { entry -> entry.track.id }) }
        } finally { folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
    }

    @Test fun damagedStateIsPreservedAndCannotBeReplacedByAnOrdinaryEnqueue() = runBlocking {
        val file = kotlin.io.path.createTempFile("music-queue-", ".json").toFile()
        try {
            file.writeText("{ interrupted")
            MusicDownloadJournal(file).use { journal ->
                assertNotNull(journal.error.value)
                journal.enqueue(Song("song", "Song", "Artist", null), 1, "HIGH")
                journal.flush()
                assertEquals("{ interrupted", file.readText())
            }
        } finally { file.delete() }
    }
}
