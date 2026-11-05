package com.cosmotune.remote

import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class LibrarySyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(limits: SyncStorageLimits = SyncStorageLimits()) = LibrarySyncStore(temporary.newFolder(), limits)
    private fun playlist(id: String = "mix", title: String = "Evening", vararg ids: String) =
        SyncPlaylist(id, title, ids.map { SyncTrack(it, "Song $it", "Singer") })

    @Test fun conflictingEditsConvergeAndDeletionCannotBeResurrectedByAnOldPage() {
        val a = store(); val b = store()
        a.publish(playlist(ids = arrayOf("one", "two")))
        b.merge(a.records())
        a.publish(playlist(title = "A rename", ids = arrayOf("two", "one")))
        b.publish(playlist(title = "B rename", ids = arrayOf("one", "two", "three")))
        val left = a.records(); val right = b.records()
        a.merge(right); b.merge(left)
        assertEquals(a.records().single().copy(sequence = 0), b.records().single().copy(sequence = 0))
        val stale = a.records()
        b.remove("playlist:mix"); a.merge(b.records())
        val reopened = LibrarySyncStore(a.directory)
        assertTrue(reopened.records().single().deleted)
        assertEquals(0, reopened.merge(stale))
        assertTrue(reopened.records().single().deleted)
    }

    @Test fun completeNativeSnapshotsJournalRenameOrderAndDeleteWithoutRepublishingReceivedData() {
        val a = store(); val b = store()
        val original = playlist(ids = arrayOf("one", "two"))
        a.publish(original); b.merge(a.records()); b.bindReceived(a.records())
        val before = b.sequence()
        b.reconcilePublished(listOf(original))
        assertEquals(before, b.sequence())
        val edited = original.copy(title = "Renamed", tracks = original.tracks.reversed())
        b.reconcilePublished(listOf(edited))
        assertEquals(edited, b.records().single().playlist)
        b.reconcilePublished(emptyList())
        assertTrue(b.records().single().deleted)
        assertTrue(LibrarySyncStore(b.directory).records().single().deleted)
    }

    @Test fun pagesHaveStableIncreasingCursorsAndOnlyLaterChangesAppearIncrementally() {
        val a = store()
        repeat(100) { a.publish(playlist(id = "mix$it", ids = arrayOf("track$it"))) }
        val ids = mutableSetOf<String>(); var after = 0L
        while (true) {
            val page = a.page(after, 7)
            assertTrue(page.records.size <= 7)
            ids += page.records.map { it.id }
            after = page.through
            if (page.next == null) break
        }
        assertEquals(100, ids.size)
        a.publish(playlist(id = "mix0", title = "Changed", ids = arrayOf("track0")))
        assertEquals(listOf("playlist:mix0"), a.page(after).records.map { it.id })
    }

    @Test fun pairedExchangeMovesPlaylistsAndVerifiedFilesInBothDirectionsThenBecomesIncremental() = runBlocking<Unit> {
        val a = store(); val b = store()
        val leftBytes = ByteArray(110_000) { (it % 251).toByte() }
        val rightBytes = ByteArray(70_000) { (it % 127).toByte() }
        val left = a.offerFile("Left.flac", { leftBytes.inputStream() })
        val right = b.offerFile("Right.flac", { rightBytes.inputStream() })
        a.publish(SyncPlaylist("left", "Left", listOf(SyncTrack("file:${left.hash}", "Left song", "Singer", fileHash = left.hash))))
        b.publish(SyncPlaylist("right", "Right", listOf(SyncTrack("file:${right.hash}", "Right song", "Singer", fileHash = right.hash))))
        val host = object : LanRemoteHost {
            override val librarySync = b
            override fun playback() = RemotePlayback()
            override suspend fun command(command: RemoteCommand) = Unit
        }
        LanRemoteServer.start(host).use { server ->
            val failures = java.util.concurrent.CopyOnWriteArrayList<Exception>()
            server.failureObserver = { failures += it }
            val client = LibrarySyncClient(server.session.pairingUrl)
            val preview = client.preview(a)
            assertTrue(preview.incoming.isNotEmpty()); assertTrue(preview.outgoing.isNotEmpty())
            try { client.apply(a, preview, files = true) }
            catch (failure: Exception) { throw AssertionError("Paired file fixture failed", failures.lastOrNull() ?: failure) }
            assertContentEquals(leftBytes, b.file(left.hash)!!.readBytes())
            assertContentEquals(rightBytes, a.file(right.hash)!!.readBytes())
            assertEquals(setOf("left", "right"), b.records().mapNotNull { it.playlist?.id }.toSet())
            client.apply(a, client.preview(a), files = false) // acknowledge idempotent echoes of the first exchange
            val unchanged = client.preview(a)
            assertTrue(unchanged.incoming.isEmpty()); assertTrue(unchanged.outgoing.isEmpty())
            val wrong = server.session.pairingUrl.dropLast(1) + if (server.session.pairingUrl.last() == 'A') "B" else "A"
            assertFailsWith<IllegalArgumentException> { LibrarySyncClient(wrong).preview(a) }
            assertFalse(File(a.directory, "journal.json").readText().contains("Bearer"))
            assertFalse(client.toString().contains(server.session.pairingUrl.substringAfter('#')))
        }
    }

    @Test fun interruptedReceiveResumesAndCorruptBytesNeverBecomeAPlayableFile() {
        val source = store(); val destination = store()
        val bytes = ByteArray(100_000) { (it % 101).toByte() }
        val descriptor = source.offerFile("Recording.flac", { bytes.inputStream() })
        destination.merge(source.records())
        var calls = 0
        assertFailsWith<IllegalStateException> { destination.receive(descriptor) { offset ->
            if (++calls == 2) error("Interrupted") else source.chunk(descriptor.hash, offset)
        } }
        assertNull(destination.file(descriptor.hash))
        var resumedAt = -1L
        val received = destination.receive(descriptor) { offset ->
            if (resumedAt < 0) resumedAt = offset
            source.chunk(descriptor.hash, offset)
        }
        assertEquals(LibrarySyncStore.CHUNK_BYTES.toLong(), resumedAt)
        assertContentEquals(bytes, received.readBytes())
        val bad = store(); bad.merge(source.records())
        assertFailsWith<IllegalStateException> { bad.receive(descriptor) { offset ->
            val real = source.chunk(descriptor.hash, offset)
            real.copy(bytes = Base64.getEncoder().encodeToString(ByteArray(Base64.getDecoder().decode(real.bytes).size)))
        } }
        assertNull(bad.file(descriptor.hash))
    }

    @Test fun aFreshUploadUsesALongZeroOffsetAndOnlyAcceptsItsCurrentResumePosition() {
        val source = store(); val destination = store()
        val bytes = ByteArray(70_000) { (it % 127).toByte() }
        val descriptor = source.offerFile("Recording.flac", { bytes.inputStream() })
        destination.merge(source.records())
        assertEquals(0L, destination.uploadOffset(descriptor.hash))
        val first = source.chunk(descriptor.hash, 0L)
        var offset = destination.acceptChunk(first)
        assertEquals(LibrarySyncStore.CHUNK_BYTES.toLong(), offset)
        assertFailsWith<IllegalArgumentException> { destination.acceptChunk(first) }
        while (offset < descriptor.size) offset = destination.acceptChunk(source.chunk(descriptor.hash, offset))
        assertEquals(descriptor.size, destination.uploadOffset(descriptor.hash))
        assertContentEquals(bytes, destination.file(descriptor.hash)!!.readBytes())
    }

    @Test fun fileLimitsTotalQuotaFreeSpaceAndArbitraryPathsAreRejectedBeforeTransfers() {
        val limits = SyncStorageLimits(fileBytes = 100, totalBytes = 150, reserveBytes = 0)
        val a = store(limits)
        assertFailsWith<IllegalArgumentException> { a.offerFile("large", { ByteArray(101).inputStream() }) }
        a.offerFile("first", { ByteArray(90) { 1 }.inputStream() })
        assertFailsWith<IllegalArgumentException> { a.offerFile("second", { ByteArray(90) { 2 }.inputStream() }) }
        val noSpace = store(SyncStorageLimits(fileBytes = 100, totalBytes = 150, reserveBytes = Long.MAX_VALUE))
        assertFailsWith<IllegalStateException> { noSpace.offerFile("file", { byteArrayOf(1).inputStream() }) }
        assertFailsWith<IllegalArgumentException> { a.chunk("../../private", 0) }
        assertFailsWith<IllegalArgumentException> { a.publish(SyncPlaylist("unsafe", "Unsafe", listOf(SyncTrack("content://private/token", "Track", "Singer")))) }
        assertFailsWith<IllegalArgumentException> { LibrarySyncClient("https://example.com/#key=private") }
        assertFailsWith<IllegalArgumentException> { LibrarySyncClient("http://8.8.8.8:80/#key=" + "A".repeat(43)) }
    }

    @Test fun aDisabledSharingHostCannotExposeItsCatalogueAndAStalePreviewCannotOverwriteEdits() = runBlocking<Unit> {
        val a = store(); val b = store()
        val host = object : LanRemoteHost {
            var shared: LibrarySyncStore? = null
            override val librarySync get() = shared
            override fun playback() = RemotePlayback()
            override suspend fun command(command: RemoteCommand) = Unit
        }
        LanRemoteServer.start(host).use { server ->
            val client = LibrarySyncClient(server.session.pairingUrl)
            assertFailsWith<IllegalArgumentException> { client.preview(a) }
            host.shared = b
            val preview = client.preview(a)
            a.publish(playlist(ids = arrayOf("new")))
            assertFailsWith<IllegalArgumentException> { client.apply(a, preview, files = false) }
            assertTrue(b.records().isEmpty())
        }
    }
}
