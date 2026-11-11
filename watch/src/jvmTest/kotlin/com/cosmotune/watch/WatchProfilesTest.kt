package com.cosmotune.watch

import com.cosmotune.watch.model.*
import com.cosmotune.watch.store.*
import kotlin.test.*

class WatchProfilesTest {
    @Test fun switchingAndCloudRestoreKeepEachViewersLibrarySeparate() {
        val id = "1ba12971-5d29-4fd6-9090-2a186ef335dc"
        val title = TitleSummary(WatchKey.tmdbFilm(603), TitleType.FILM, "The Matrix")
        val everyone = WatchState(myList = listOf(MyListEntry(title.key, 1, title)), profiles = mapOf(
            "default" to WatchProfile("default", "Everyone"), id to WatchProfile(id, "Vijay")))
        val second = everyone.selectProfile(id)
        assertTrue(second.myList.isEmpty())
        assertEquals(everyone.myList, second.selectProfile("default").myList)
        val cloud = SyncWatchLibrary.snapshot(second)
        val restored = SyncWatchLibrary.apply(WatchState(), cloud, emptySet())
        assertEquals("default", restored.activeProfile, "A phone changing profile must not switch the desktop viewer")
        assertEquals(everyone.myList, restored.myList)
        assertTrue(restored.selectProfile(id).myList.isEmpty())
        val removed = SyncWatchLibrary.apply(second, emptyMap(), setOf("profile:$id"))
        assertEquals("default", removed.activeProfile)
        assertEquals(everyone.myList, removed.myList)
    }
}
