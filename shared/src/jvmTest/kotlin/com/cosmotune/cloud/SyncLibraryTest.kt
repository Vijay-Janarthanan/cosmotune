package com.cosmotune.cloud

import com.cosmotune.listening.ListeningState
import com.cosmotune.listening.ReleaseNotice
import com.cosmotune.listening.WatchedArtist
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Likes and followed artists as cloud entries, the opt-in gate on automatic syncs, and a refused cloud entry never turning into a delete. */
class SyncLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val empty = JsonObject(emptyMap())

    @Test fun aLikeCarriesWhatARowNeedsAndLeavesOutWhatIsUnknown() {
        val data = SyncLikes.data(Song("abc", "Song", "Band", "https://img/abc.jpg", localPath = "C:/x.flac"))
        assertEquals("Song", SyncLikes.title(data))
        assertEquals("Band", SyncLikes.artist(data))
        assertEquals("https://img/abc.jpg", SyncLikes.thumbnailUrl(data))
        assertFalse(data.toString().contains("x.flac"))
        assertEquals(empty, SyncLikes.data(null, " ", null))
    }

    @Test fun onlyLikesThatMeanTheSameElsewhereTravel() {
        val before = SyncLikes.sourceTrack
        try {
            SyncLikes.sourceTrack = { it.startsWith("src:") }
            assertTrue(SyncLikes.travels("dQw4w9WgXcQ"))
            assertFalse(SyncLikes.travels("local:42"))
            assertFalse(SyncLikes.travels("src:one"))
            assertFalse(SyncLikes.travels(""))
            assertFalse(SyncLikes.travels("x".repeat(201)))
            val known = mapOf("a" to SyncLikes.data("A", null, null))
            assertEquals(mapOf("a" to known.getValue("a"), "b" to empty), SyncLikes.snapshot(listOf("a", "b", "local:1", "src:2"), known))
            // a cloud change never touches a source rating or a file's like, even if one arrived under such an id
            val next = SyncLikes.applyToIds(setOf("a", "src:2", "local:1"), mapOf("c" to empty, "src:9" to empty), setOf("a", "src:2", "local:1"))
            assertEquals(setOf("src:2", "local:1", "c"), next)
        } finally {
            SyncLikes.sourceTrack = before
        }
    }

    @Test fun theLikeFileKeepsWhatWasKnownAndReadsBackWhatTheCloudWrote() {
        val file = File(temporary.root, "cloud/likes.json")
        val likes = LikeFile(file)
        val full = SyncLikes.data("Song", "Band", null)
        likes.like("a", full)
        likes.like("a", empty)
        assertEquals(full, likes.likes.value["a"], "liking again knowing less keeps what was known")
        likes.like("b", empty)
        likes.unlike("b")
        assertEquals(setOf("a"), likes.likes.value.keys)
        val cloud = mapOf("c" to SyncLikes.data("From phone", null, null))
        val written = likes.applyFromCloud(cloud, setOf("a"))
        assertTrue(written === likes.lastCloudWrite)
        assertTrue(likes.likes.value === likes.lastCloudWrite, "a watcher can tell the sync's own write")
        assertEquals(cloud, LikeFile(file).likes.value, "survives a restart, exactly as the cloud sent it")
    }

    @Test fun artistsTravelWithTheirNameOnly() {
        val snapshot = SyncArtists.snapshot(listOf(WatchedArtist("UC1", "Ilaiyaraaja", known = setOf("r1"), checkedAt = 5)))
        assertEquals(setOf("UC1"), snapshot.keys)
        assertEquals("{\"name\":\"Ilaiyaraaja\"}", snapshot.getValue("UC1").toString())
    }

    @Test fun cloudArtistsAreFollowedRenamedAndUnfollowedLikeTheStoreWould() {
        val state = ListeningState(
            artists = listOf(WatchedArtist("UC1", "Old name", known = setOf("r1"), checkedAt = 5), WatchedArtist("UC2", "Gone")),
            inbox = listOf(ReleaseNotice("r9", "UC2", "Gone", "Album"), ReleaseNotice("r1", "UC1", "Old name", "Single")),
        )
        val next = SyncArtists.apply(state, mapOf("UC1" to name("New name"), "UC3" to name("  Added  "), "UC4" to name(" ")), setOf("UC2"))
        assertEquals(listOf("UC1", "UC3"), next.artists.map { it.id }, "a blank name is not applied")
        assertEquals("New name", next.artists[0].name)
        assertEquals(setOf("r1"), next.artists[0].known, "this device's release history stays")
        assertEquals(5L, next.artists[0].checkedAt)
        assertEquals("Added", next.artists[1].name)
        assertNull(next.artists[1].checkedAt)
        assertEquals(listOf("r1"), next.inbox.map { it.id }, "an unfollowed artist's notices go with it")
        // the result reads back as exactly what came in, so the journal does not take it for a local edit
        assertEquals(name("New name"), SyncArtists.snapshot(next.artists).getValue("UC1"))
    }

    @Test fun aFullListTakesNoMoreArtists() {
        val full = ListeningState(artists = (1..50).map { WatchedArtist("UC$it", "Artist $it") })
        assertEquals(50, SyncArtists.apply(full, mapOf("UCnew" to name("New")), emptySet()).artists.size)
    }

    @Test fun aCloudEntryTheStoreRefusedIsNotSentBackAsADelete() = runBlocking<Unit> {
        val store = object : JournalSyncSource(SnapshotJournal(null)) {
            val data = mutableMapOf<String, JsonObject>()
            override suspend fun snapshot(kind: String): Map<String, JsonObject> = data.toMap()
            // takes everything but "full"
            override suspend fun write(kind: String, upserts: Map<String, JsonObject>, deletes: Set<String>) {
                data.putAll(upserts.filterKeys { it != "full" }); deletes.forEach(data::remove)
            }
        }
        store.apply("artists", listOf(SyncEntry("ok", 3_000, false, name("A")), SyncEntry("full", 3_000, false, name("B"))))
        val entries = store.entries("artists")
        assertEquals(listOf("ok"), entries.map { it.id })
        assertTrue(entries.none { it.deleted })
    }

    @Test fun nothingIsScheduledWhileCloudSyncIsOff() {
        var on = false
        var online = true
        val calls = mutableListOf<Long?>()
        val auto = CloudAutoSync(enabled = { on }, online = { online }, sync = { calls += it })
        auto.due(); auto.changed(); auto.networkBack(lastSyncFailed = true)
        online = false
        auto.due(); auto.changed()
        assertTrue(calls.isEmpty(), "not on start, not on change, not on reconnect")
        assertFalse(auto.waitingForNetwork, "an edit made while off is not owed to the cloud")

        on = true
        auto.changed()
        assertTrue(calls.isEmpty() && auto.waitingForNetwork, "offline: the edit waits for the network")
        online = true
        auto.networkBack(lastSyncFailed = false)
        assertEquals(listOf<Long?>(CloudAutoSync.NETWORK_SETTLE_MS), calls)
        auto.networkBack(lastSyncFailed = false)
        assertEquals(1, calls.size, "a reconnect with nothing owed sends nothing")
        auto.due(); auto.changed()
        assertEquals(listOf(CloudAutoSync.NETWORK_SETTLE_MS, null, CloudAutoSync.CHANGE_QUIET_MS), calls)

        online = false
        auto.changed()
        on = false
        auto.stopped()
        online = true
        auto.networkBack(lastSyncFailed = true)
        assertEquals(3, calls.size, "turned off while an edit waited: nothing goes when the network returns")
    }

    private fun name(value: String) = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(value)) }
}
