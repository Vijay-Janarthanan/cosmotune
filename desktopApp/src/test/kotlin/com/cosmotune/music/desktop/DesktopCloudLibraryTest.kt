package com.cosmotune.music.desktop

import com.cosmotune.cloud.SyncLikes
import com.cosmotune.providers.SourceLibraryStore
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The desktop's liked set as cloud entries and back, on plain sets: nothing here touches DesktopPersistence. */
class DesktopCloudLibraryTest {
    private val empty = JsonObject(emptyMap())
    private val sourceTrack = "src:navidrome::track-1"
    private var before: (String) -> Boolean = { false }

    @BeforeTest fun useTheDesktopsSourceCheck() {
        before = SyncLikes.sourceTrack
        SyncLikes.sourceTrack = SourceLibraryStore::ownsTrack
    }

    @AfterTest fun restore() {
        SyncLikes.sourceTrack = before
    }

    @Test fun likedIdsTravelWithWhatIsKnownOfThemAndNothingElse() {
        val known = mapOf("abc" to SyncLikes.data("Song", "Band", "https://img/abc.jpg"))
        val snapshot = SyncLikes.snapshot(setOf("abc", "fromAccount", "local:one", sourceTrack), known)
        assertEquals(mapOf("abc" to known.getValue("abc"), "fromAccount" to empty), snapshot)
    }

    @Test fun cloudLikesJoinTheSetAndAnUnlikeLeavesSourceRatingsAlone() {
        val current = setOf("keep", "unliked", sourceTrack, "local:one")
        val next = SyncLikes.applyToIds(current, mapOf("fromPhone" to SyncLikes.data("T", null, null)), setOf("unliked", sourceTrack, "local:one"))
        assertEquals(setOf("keep", sourceTrack, "local:one", "fromPhone"), next)
        // read back, the sync's write is exactly what came in for every id that travels
        assertEquals(setOf("keep", "fromPhone"), SyncLikes.snapshot(next, emptyMap()).keys)
    }
}
