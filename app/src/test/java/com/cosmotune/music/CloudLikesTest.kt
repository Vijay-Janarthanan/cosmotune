package com.cosmotune.music

import com.cosmotune.cloud.SyncLikes
import com.cosmotune.music.data.cloud.CloudLikes
import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.providers.SourceLibraryStore
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The phone's likes as cloud entries, and synced likes painted as hearts without touching what YouTube itself says. */
class CloudLikesTest {
    private val empty = JsonObject(emptyMap())
    private val sourceTrack = "src:navidrome::track-1"
    private var before: (String) -> Boolean = { false }

    @Before fun useTheAppsSourceCheck() {
        before = SyncLikes.sourceTrack
        SyncLikes.sourceTrack = SourceLibraryStore::ownsTrack
    }

    @After fun restore() {
        SyncLikes.sourceTrack = before
    }

    @Test fun onlyStreamedLikesBecomeEntries() {
        val liked = mapOf("abc" to SyncLikes.data("Song", "Band", null), "local:42" to empty, sourceTrack to empty)
        assertEquals(mapOf("abc" to liked.getValue("abc")), CloudLikes.snapshot(liked))
    }

    @Test fun aCloudChangeNeverReachesASourceRatingOrAFile() {
        val (upserts, deletes) = CloudLikes.travelling(mapOf("abc" to empty, sourceTrack to empty), setOf("local:1", "gone"))
        assertEquals(setOf("abc"), upserts.keys)
        assertEquals(setOf("gone"), deletes)
    }

    @Test fun syncedLikesShowAsLikedAndAnUnlikeOnlyClearsAnOverride() {
        val current = mapOf("already" to LikeStatus.LIKE, "overridden" to LikeStatus.LIKE, "disliked" to LikeStatus.DISLIKE)
        val painted = CloudLikes.overrides(setOf("new", "already"), setOf("overridden", "fromYouTube", "disliked"), current)
        assertEquals(mapOf("new" to LikeStatus.LIKE, "overridden" to LikeStatus.INDIFFERENT), painted)
        // a song YouTube lists as liked, with no override this session, is not unliked by another device's sync
        assertTrue("fromYouTube" !in painted)
    }
}
