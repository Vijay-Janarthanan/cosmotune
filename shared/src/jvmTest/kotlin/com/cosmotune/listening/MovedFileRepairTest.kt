package com.cosmotune.listening

import com.cosmotune.audio.LibraryAudioAnalysis
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class MovedFileRepairTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aMovedFileIsFoundByItsFingerprintAndOnlyWhenUnique() {
        val old = File(temporary.root, "old/song.flac")
        val moved = temporary.newFile("song (moved).flac").apply { writeBytes(ByteArray(100)) }
        val twin = temporary.newFile("twin.flac").apply { writeBytes(ByteArray(100)) }
        val other = temporary.newFile("other.flac").apply { writeBytes(ByteArray(100)) }
        fun a(file: File, lufs: Double, seconds: Double = 200.0) = LibraryAudioAnalysis("rec", file.path, bytes = 100, integratedLufs = lufs, durationSeconds = seconds)
        val track = ListeningTrack("rec:1", "Song", "Artist", path = old.path, entryId = "e")
        val state = ListeningState(sessions = listOf(ListeningSession(id = "s", name = "S", tracks = listOf(track), index = 0, positionMs = 3)))
        val available: (ListeningTrack) -> Boolean = { t -> t.path?.let(::File)?.isFile == true }
        val analysis = listOf(a(old, -11.2), a(moved, -11.2), a(other, -7.0), a(twin, -11.2, seconds = 31.0)).associateBy { it.location }
        val repairs = previewMovedFileRepairs(state, analysis, available)
        assertEquals(listOf(moved.path), repairs.map { it.after.path })
        val repaired = applySessionFileRepairs(state, repairs, available)
        assertEquals("rec:1" to "e", repaired.sessions.single().tracks.single().let { it.id to it.entryId })
        // Two equally good candidates: nothing is guessed.
        val ambiguous = analysis + (twin.path to a(twin, -11.2))
        assertTrue(previewMovedFileRepairs(state, ambiguous, available).isEmpty())
        // Without an earlier fingerprint there is nothing to reconcile.
        assertTrue(previewMovedFileRepairs(state, analysis - old.path, available).isEmpty())
    }
}
