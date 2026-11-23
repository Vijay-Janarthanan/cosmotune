package com.cosmotune.watch

import com.cosmotune.watch.model.*
import com.cosmotune.watch.store.WatchState
import kotlin.test.*
import kotlinx.serialization.json.*

class SyncWatchLibraryTest {
    @Test fun completionTriggersSyncInsideTheSameFiveMinuteBucket() {
        val p = WatchProgress(title.key, 800_000, 1_000_000, 1, title)
        val state = WatchState(progress = mapOf(title.key.value to p))
        val completed = state.copy(progress = mapOf(title.key.value to p.copy(positionMs = 821_000, updatedAtMs = 2)))
        assertFalse(p.completed)
        assertTrue(completed.progress.getValue(title.key.value).completed)
        assertNotEquals(SyncWatchLibrary.changeSignal(state), SyncWatchLibrary.changeSignal(completed))
        assertFalse(SyncWatchLibrary.snapshot(completed).values.any { "completed" in it }, "trigger-only flags never change the cloud schema")
    }
    @Test fun progressSignalsAreCoalescedButTheSnapshotKeepsTheExactResumePoint() {
        val first = WatchState(progress = mapOf(title.key.value to WatchProgress(title.key, 80_000, 9_000_000, 1, title)))
        val next = first.copy(progress = mapOf(title.key.value to WatchProgress(title.key, 90_000, 9_000_000, 2, title)))
        assertEquals(SyncWatchLibrary.changeSignal(first), SyncWatchLibrary.changeSignal(next))
        assertNotEquals(SyncWatchLibrary.snapshot(first), SyncWatchLibrary.snapshot(next))
    }
    @Test fun localAndAuthenticatedArtworkCannotTravelWithAnAccountSnapshot() {
        val private = title.copy(posterUrl = "https://server.test/image?api_key=private", backdropUrl = "file:///private/movie.jpg")
        val state = WatchState(myList = listOf(MyListEntry(private.key, 1, private)), settings = mapOf("profile.pin.default" to "private-hash"))
        val text = SyncWatchLibrary.snapshot(state).toString()
        assertFalse(text.contains("private"))
        assertFalse(text.contains("file://"))
    }
    private val title = TitleSummary(WatchKey.tmdbFilm(603), TitleType.FILM, "The Matrix")
    @Test fun progressListAndSafePreferencesRoundTripAndDelete() {
        val original = WatchState(progress = mapOf(title.key.value to WatchProgress(title.key, 80_000, 9_000_000, 5, title)),
            myList = listOf(MyListEntry(title.key, 3, title)), settings = mapOf("watch.density" to "COMPACT", "secret" to "private"))
        val snapshot = SyncWatchLibrary.snapshot(original)
        assertFalse(snapshot.toString().contains("private"))
        val restored = SyncWatchLibrary.apply(WatchState(), snapshot, emptySet())
        assertEquals(original.progress, restored.progress)
        assertEquals(original.myList, restored.myList)
        assertEquals(mapOf("watch.density" to "COMPACT"), restored.settings)
        val removed = SyncWatchLibrary.apply(restored, emptyMap(), snapshot.keys)
        assertTrue(removed.progress.isEmpty() && removed.myList.isEmpty() && removed.settings.isEmpty())
    }
    @Test fun removingFromMyListAndContinueWatchingTravelsAsDeletesAndStaysRemovedOnTheOtherDevice() {
        var clock = 1_000L
        val journal = com.cosmotune.cloud.SnapshotJournal(null) { clock }
        val both = WatchState(progress = mapOf(title.key.value to WatchProgress(title.key, 80_000, 9_000_000, 5, title)),
            myList = listOf(MyListEntry(title.key, 3, title)))
        journal.reconcile(SyncWatchLibrary.KIND, SyncWatchLibrary.snapshot(both))
        clock = 5_000
        val removed = both.copy(progress = emptyMap(), myList = emptyList())
        val entries = journal.reconcile(SyncWatchLibrary.KIND, SyncWatchLibrary.snapshot(removed))
        val deletes = entries.filter { it.deleted }
        assertEquals(setOf("list:default:${title.key.value}", "progress:default:${title.key.value}"), deletes.map { it.id }.toSet())
        assertTrue(deletes.all { it.updatedAt == 5_000L })
        val other = SyncWatchLibrary.apply(both, emptyMap(), deletes.map { it.id }.toSet())
        assertTrue(other.progress.isEmpty() && other.myList.isEmpty())
    }
    @Test fun aTitleAddedBackWhileADeleteArrivesIsKept() {
        val entry = MyListEntry(title.key, 9, title)
        val snapshot = SyncWatchLibrary.snapshot(WatchState(myList = listOf(entry)))
        val id = "list:default:${title.key.value}"
        val state = SyncWatchLibrary.apply(WatchState(), snapshot, setOf(id))
        assertEquals(listOf(entry), state.myList)
    }
    @Test fun malformedRemoteItemsCannotReplaceLocalProgressOrInjectSettings() {
        val p = WatchProgress(title.key, 40_000, 500_000, 5, title)
        val original = WatchState(progress = mapOf(title.key.value to p))
        val invalid = mapOf("progress:default:${title.key.value}" to buildJsonObject { put("positionMs", -1) },
            "setting:server.password" to buildJsonObject { put("value", "injected") })
        val restored = SyncWatchLibrary.apply(original, invalid, emptySet())
        assertEquals(original.progress, restored.progress)
        assertEquals(original.settings, restored.settings)
    }
}
