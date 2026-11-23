package com.cosmotune.ui

import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.music.data.model.Song
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.pages.KeepDownloaded
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Liked songs kept downloaded (upstream 1.10): with Liked Music's "Keep downloaded" switch on, a new like brings a quick pass over
 * its newest page instead of waiting for the daily one. The switch stays the listener's (off until turned on), as every playlist's.
 */
class KeepLikedDownloadedTest {
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }

    private fun song(id: String) = Song(id, "Title $id", "Artist", null)

    @Test fun aNewLikeAsksForAPassOnlyWhileLikedMusicIsKept() {
        val before = mapOf("a" to LikeStatus.LIKE, "b" to LikeStatus.INDIFFERENT)
        val now = before + ("b" to LikeStatus.LIKE)
        assertTrue(KeepDownloaded.likedPassWanted(before, now, kept = setOf(KeepDownloaded.LIKED_MUSIC)))
        assertFalse(KeepDownloaded.likedPassWanted(before, now, kept = setOf("VLother")), "the switch is off: nothing is fetched")
        assertFalse(KeepDownloaded.likedPassWanted(before, now, kept = emptySet()))
    }

    @Test fun anUnlikeADislikeOrALikeAlreadyKnownAsksForNothing() {
        val kept = setOf(KeepDownloaded.LIKED_MUSIC)
        val liked = mapOf("a" to LikeStatus.LIKE)
        assertFalse(KeepDownloaded.likedPassWanted(liked, liked, kept))
        assertFalse(KeepDownloaded.likedPassWanted(liked, mapOf("a" to LikeStatus.INDIFFERENT), kept), "an unlike keeps the file and fetches nothing")
        assertFalse(KeepDownloaded.likedPassWanted(emptyMap(), mapOf("c" to LikeStatus.DISLIKE), kept))
    }

    @Test fun theQuickPassHandsOverTheNewLikeButLeavesTheDailyGateAlone() = runBlocking {
        val look = CosmoLookSettings(MapStore())
        look.setKeepDownloaded(KeepDownloaded.LIKED_MUSIC, true)
        look.setKeepDownloadedCheckedMs(500)
        val handed = mutableListOf<String>()
        val count = KeepDownloaded.run(
            look, { listOf(song("new"), song("old")) }, onDevice = { it == "old" }, download = { _, list -> handed += list.map { it.videoId } },
            playlists = listOf(KeepDownloaded.LIKED_MUSIC), nowMs = 9_000, stamp = false,
        )
        assertEquals(1, count)
        assertEquals(listOf("new"), handed)
        assertEquals(500, look.keepDownloadedCheckedMs(), "a quick pass reads one page, so the whole daily pass is still due")
    }
}
