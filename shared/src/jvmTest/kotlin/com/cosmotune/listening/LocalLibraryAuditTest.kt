package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.*

class LocalLibraryAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun song(id: String, file: File) = Song(id, "தமிழ் Song", "Singer", null, "4:00", albumName = "Album", localPath = file.path)

    @Test fun identicalBytesSharedLocationsAndMetadataCandidatesStaySeparate() = runBlocking<Unit> {
        val first = temporary.newFile("original.mp3").apply { writeBytes("fLaC".toByteArray() + ByteArray(2048) { 1 }) }
        val copy = temporary.newFile("copy.flac").apply { writeBytes(first.readBytes()) }
        val other = temporary.newFile("other.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(2048) { 2 }) }
        val report = auditLocalLibrary(listOf(song("a", first), song("alias", first), song("copy", copy),
            song("different-recording", other), song("live", other).copy(title = "தமிழ் Song (Live)")), verifyBytes = true)
        assertEquals(5, report.entries.size)
        assertEquals(listOf(0, 1), report.duplicates.single { it.kind == LibraryDuplicateKind.SAME_FILE && it.indices.contains(0) }.indices)
        assertEquals(listOf(0, 2), report.duplicates.single { it.kind == LibraryDuplicateKind.IDENTICAL_BYTES }.indices)
        assertEquals(listOf(0, 2, 3), report.duplicates.single { it.kind == LibraryDuplicateKind.METADATA_CANDIDATES }.indices)
        assertTrue(report.entries.all { it.format == "FLAC" }) // Evidence beats the misleading .mp3 name.
        assertEquals(first.length() * 3, report.hashedBytes) // Shared paths are read only once.
        assertContentEquals(first.readBytes(), copy.readBytes())
        assertTrue(report.export().contains("IDENTICAL_BYTES"))
        assertFalse(report.export().contains("losslessMaster"))
    }

    @Test fun scanBudgetsChangingFilesAndPermissionFailuresCannotBecomeConfirmedDuplicates() = runBlocking<Unit> {
        val one = temporary.newFile("one.flac").apply { writeBytes(ByteArray(4096) { 7 }) }
        val two = temporary.newFile("two.flac").apply { writeBytes(one.readBytes()) }
        val songs = listOf(song("one", one), song("two", two))
        val bounded = auditLocalLibrary(songs, verifyBytes = true, hashBudget = one.length())
        assertTrue(bounded.hashBudgetReached); assertEquals(one.length(), bounded.hashedBytes)
        assertTrue(bounded.duplicates.none { it.kind == LibraryDuplicateKind.IDENTICAL_BYTES })
        var inspected = 0
        val changing = object : LocalLibraryAccess {
            override fun inspect(song: Song): LocalLibraryFile = FileLibraryAccess.inspect(song).let { file ->
                file.copy(modified = if (++inspected > 2) 999 else 1)
            }
        }
        val changed = auditLocalLibrary(songs, changing, verifyBytes = true)
        assertTrue(changed.entries.all { it.state == LibraryFileState.CHANGED && it.sha256 == null })
        val blocked = auditLocalLibrary(songs, object : LocalLibraryAccess {
            override fun inspect(song: Song): LocalLibraryFile = throw SecurityException("private permission token")
        })
        assertTrue(blocked.entries.all { it.state == LibraryFileState.ACCESS_REQUIRED })
        assertFalse(blocked.export().contains("private permission token"))
        val remote = auditLocalLibrary(listOf(songs[0].copy(localPath = null, localUri = "https://example.invalid/stream?secret=token")))
        assertEquals(LibraryFileState.UNSUPPORTED, remote.entries.single().state)
        assertFalse(remote.export().contains("secret=token"))
        val empty = temporary.newFile("empty.mp3")
        assertEquals(LibraryFileState.EMPTY, auditLocalLibrary(listOf(song("empty", empty))).entries.single().state)
        val removed = File(temporary.root, "moved-away.flac")
        val missing = auditLocalLibrary(listOf(song("removed", removed)))
        assertEquals(LibraryFileState.MISSING, missing.entries.single().state)
        assertEquals(removed.path, missing.entries.single().identity)
        assertTrue(missing.export().contains("moved-away.flac"))
        val content = auditLocalLibrary(listOf(songs[0].copy(localPath = null,
            localUri = "content://owned.library/audio/42?token=private#credential")))
        assertEquals("content://owned.library/audio/42", content.entries.single().identity)
        assertFalse(content.export().contains("private")); assertFalse(content.export().contains("credential"))
    }

    @Test fun cancellationClosesTheComparisonAndPublishesNoPartialReport() = runBlocking<Unit> {
        var closed = 0
        var reads = 0
        var cancelScan: () -> Unit = {}
        val files = object : LocalLibraryAccess {
            override fun inspect(song: Song) = LocalLibraryFile(song.videoId, 256L * 1024, 1) {
                object : ByteArrayInputStream(ByteArray(256 * 1024)) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        reads++
                        if (reads == 4) cancelScan()
                        return super.read(bytes, offset, length)
                    }
                    override fun close() { closed++; super.close() }
                }
            }
        }
        // Cancellation runs in a child so the surrounding assertion can finish.
        val child = launch {
            val scanJob = currentCoroutineContext().job
            cancelScan = { scanJob.cancel() }
            auditLocalLibrary(listOf(Song("a", "a", "Artist", null, localPath = "/a"),
                Song("b", "b", "Artist", null, localPath = "/b")), files, verifyBytes = true)
            fail("A cancelled scan must not publish a report")
        }
        child.join()
        assertTrue(child.isCancelled); assertEquals(3, closed)
    }

    @Test fun reviewedRepairsKeepDuplicateEntriesAndRejectStaleOrUnavailableReplacements() {
        val old = temporary.newFile("removed.mp3").also { it.delete() }
        val moved = temporary.newFile("moved.mp3").apply { writeBytes(byteArrayOf(1)) }
        val original = ListeningTrack.of(song("source:one", old)).copy(entryId = "first", sourceTitle = "Queue", explicit = true)
        val session = ListeningSession(id = "session", name = "Evening", tracks = listOf(original, original.copy(entryId = "second")),
            index = 1, positionMs = 42_000, repeat = 2, shuffle = true)
        val state = ListeningState(sessions = listOf(session))
        val available: (ListeningTrack) -> Boolean = { it.path?.let(::File)?.isFile == true }
        assertTrue(previewSessionFileRepairs(state, listOf(song("other-source:one", moved)), available).isEmpty())
        val preview = previewSessionFileRepairs(state, listOf(song("source:one", moved)), available)
        assertEquals(2, preview.size)
        val repaired = applySessionFileRepairs(state, preview, available).sessions.single()
        assertEquals(listOf("first", "second"), repaired.tracks.map { it.entryId })
        assertEquals(session.copy(tracks = repaired.tracks), repaired)
        assertEquals(listOf(moved.path, moved.path), repaired.tracks.map { it.path })
        assertFailsWith<IllegalArgumentException> { applySessionFileRepairs(state.copy(sessions = listOf(session.copy(tracks = session.tracks.reversed()))), preview, available) }
        assertFailsWith<IllegalArgumentException> { applySessionFileRepairs(state, preview.map { it.copy(after = it.after.copy(title = "Wrong")) }, available) }
        moved.delete()
        assertFailsWith<IllegalArgumentException> { applySessionFileRepairs(state, preview, available) }
        assertEquals(old.path, state.sessions.single().tracks.first().path)
    }

    @Test fun applyingAPreviewRechecksChangesMadeByAnotherOpenWindow() {
        val old = File(temporary.root, "old-location.flac")
        val moved = temporary.newFile("moved.flac").apply { writeBytes(byteArrayOf(1)) }
        val file = File(temporary.root, "listening.json")
        val first = ListeningStore(file)
        first.saveSession("Original", listOf(song("source:one", old)), 0, 1000, 0, false)
        val available: (ListeningTrack) -> Boolean = { it.path?.let(::File)?.isFile == true }
        val preview = previewSessionFileRepairs(first.state.value, listOf(song("source:one", moved)), available)
        val other = ListeningStore(file)
        other.update { it.copy(sessions = it.sessions.map { session -> session.copy(tracks = session.tracks.map { track -> track.copy(title = "An external correction") }) }) }
        assertFailsWith<IllegalArgumentException> { first.update { applySessionFileRepairs(it, preview, available) } }
        assertEquals("An external correction", ListeningStore(file).state.value.sessions.single().tracks.single().title)
    }
}
