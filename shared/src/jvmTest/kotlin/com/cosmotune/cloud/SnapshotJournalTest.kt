package com.cosmotune.cloud

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A store with no change times, standing in for a platform's playlists. */
private class MapStore : JournalSyncSource(SnapshotJournal(null)) {
    val data = mutableMapOf<String, JsonObject>()
    override suspend fun snapshot(kind: String): Map<String, JsonObject> = data.toMap()
    override suspend fun write(kind: String, upserts: Map<String, JsonObject>, deletes: Set<String>) {
        data.putAll(upserts); deletes.forEach(data::remove)
    }
}

class SnapshotJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private var clock = 1_000L
    private fun song(id: String) = Song(id, "Title $id", "Artist", "https://img/$id", "3:20", albumName = "Album")
    private fun playlist(title: String, vararg ids: String) = SyncPlaylists.data(title, ids.map(::song))

    @Test fun anUntouchedStoreKeepsItsTimesAndAnEditGetsANewOne() {
        val journal = SnapshotJournal(null) { clock }
        val first = journal.reconcile("playlists", mapOf("p1" to playlist("Mix", "a"), "p2" to playlist("Gym", "b")))
        assertEquals(setOf(1_000L), first.map { it.updatedAt }.toSet())
        clock = 5_000
        val second = journal.reconcile("playlists", mapOf("p1" to playlist("Mix", "a"), "p2" to playlist("Gym", "b", "c")))
        assertEquals(1_000L, second.first { it.id == "p1" }.updatedAt, "unchanged")
        assertEquals(5_000L, second.first { it.id == "p2" }.updatedAt, "edited")
    }

    @Test fun aRemovedPlaylistBecomesADeleteOnce() {
        val journal = SnapshotJournal(null) { clock }
        journal.reconcile("playlists", mapOf("p1" to playlist("Mix", "a")))
        clock = 7_000
        val gone = journal.reconcile("playlists", emptyMap()).single()
        assertTrue(gone.deleted)
        assertEquals(7_000L, gone.updatedAt)
        clock = 9_000
        assertEquals(7_000L, journal.reconcile("playlists", emptyMap()).single().updatedAt, "the delete keeps the time it happened")
    }

    @Test fun whatTheCloudWroteIsNotMistakenForALocalEdit() = runBlocking<Unit> {
        val store = MapStore()
        val remote = SyncEntry("p9", 3_000, false, playlist("From cloud", "z"))
        store.apply("playlists", listOf(remote))
        assertEquals(setOf("p9"), store.data.keys)
        assertEquals(3_000L, store.entries("playlists").single().updatedAt)
    }

    @Test fun aCloudDeleteRemovesItLocallyAndStaysDeleted() = runBlocking<Unit> {
        val store = MapStore()
        store.data["p1"] = playlist("Mix", "a")
        store.entries("playlists") // remembered
        val deletedAt = System.currentTimeMillis() - 1_000
        store.apply("playlists", listOf(SyncEntry("p1", deletedAt, deleted = true)))
        assertFalse("p1" in store.data)
        val after = store.entries("playlists").single()
        assertTrue(after.deleted)
        assertEquals(deletedAt, after.updatedAt)
    }

    @Test fun aRemovedItemIsRememberedAsDeletedUntilNinetyDaysHavePassed() {
        val day = 24L * 60 * 60 * 1000
        val journal = SnapshotJournal(null) { clock }
        journal.reconcile("watch", mapOf("a" to playlist("Mix", "a")))
        clock = 5_000
        assertTrue(journal.reconcile("watch", emptyMap()).single().deleted)
        clock = 5_000 + 89 * day
        assertEquals(1, journal.reconcile("watch", emptyMap()).size)
        clock = 5_000 + 91 * day
        assertTrue(journal.reconcile("watch", emptyMap()).isEmpty(), "an old delete is no longer remembered or sent")
    }

    @Test fun anItemAddedBackAfterBeingRemovedGetsANewerTimeThanTheDelete() {
        val journal = SnapshotJournal(null) { clock }
        journal.reconcile("watch", mapOf("a" to playlist("Mix", "a")))
        clock = 5_000
        val deletedAt = journal.reconcile("watch", emptyMap()).single().updatedAt
        clock = 9_000
        val back = journal.reconcile("watch", mapOf("a" to playlist("Mix", "a"))).single()
        assertFalse(back.deleted)
        assertTrue(back.updatedAt > deletedAt)
    }

    @Test fun theJournalSurvivesARestart() {
        val file = File(temporary.root, "journal.json")
        SnapshotJournal(file) { 1_000 }.reconcile("playlists", mapOf("p1" to playlist("Mix", "a")))
        val again = SnapshotJournal(file) { 9_000 }.reconcile("playlists", mapOf("p1" to playlist("Mix", "a")))
        assertEquals(1_000L, again.single().updatedAt)
    }

    @Test fun aPlaylistKeepsItsSongsButNotAnythingTiedToThisDevice() {
        val back = SyncPlaylists.songs(playlist("Mix", "a", "b"))
        assertEquals(listOf("a", "b"), back.map { it.videoId })
        assertEquals("Album", back.first().albumName)
        assertEquals("p1", SyncPlaylists.entryId("synced:p1"))
    }
}

class CloudAccountPartsTest {
    @org.junit.Test fun errorsReadAsSentences() {
        assertEquals("Sign in again to keep syncing", readableCloudError(CloudSignInRequired()))
        assertEquals("Sign in again to keep syncing", readableCloudError(CloudException("INVALID_REFRESH_TOKEN", "x", 400)))
        assertEquals("No connection. It will try again later", readableCloudError(java.net.UnknownHostException("firestore.googleapis.com")))
        assertTrue(readableCloudError(CloudException("PERMISSION_DENIED", "denied", 403)).startsWith("The cloud refused"))
        assertEquals("odd", readableCloudError(IllegalStateException("odd")))
    }

    @org.junit.Test fun marksRememberWhichAccountTheyBelongTo() {
        val file = java.io.File.createTempFile("marks", ".json").also { it.deleteOnExit() }
        FileSyncMarks(file).also { it.uid = "user-1"; it.pulledMs = 5 }
        val again = FileSyncMarks(file)
        assertEquals("user-1", again.uid)
        assertEquals(5L, again.pulledMs)
    }
}
