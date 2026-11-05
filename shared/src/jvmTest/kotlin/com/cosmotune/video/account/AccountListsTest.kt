package com.cosmotune.video.account

import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountListsTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun video(id: String, at: Long? = null, text: String? = null) = VideoItem(
        id = id, title = id, channelId = "UC$id", channelName = id, channelAvatarUrl = null, thumbnailUrl = null, durationSec = null,
        viewCount = null, uploadedAtMs = at, uploadedText = text,
    )

    @Test fun theAccountComesFirstAndEachVideoOnce() {
        val merged = AccountLists.merge(listOf(video("a"), video("b"), video("a")), listOf(video("c"), video("b"), video("d")))
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test fun signedOutTheLocalListIsUnchanged() {
        val local = listOf(video("c"), video("d"))
        assertEquals(local, AccountLists.merge(emptyList(), local))
        assertEquals(local, AccountLists.mergeFeed(emptyList(), local, now))
    }

    @Test fun playlistsMergeTheSameWay() {
        fun p(id: String) = PlaylistItem(id, id, null, null, null, null)
        assertEquals(listOf("PL1", "PL2", "PL3"), AccountLists.mergePlaylists(listOf(p("PL1"), p("PL2")), listOf(p("PL2"), p("PL3"))).map { it.id })
    }

    @Test fun theFeedIsNewestFirstWithLocalTimesWinningAndEstimatesForTheAccount() {
        val local = listOf(video("l1", at = now - 1 * hour), video("both", at = now - 5 * hour), video("l2", at = now - 48 * hour))
        val account = listOf(video("a1", text = "3 hours ago"), video("both", text = "5 hours ago"), video("odd", text = "Premieres tomorrow"))
        val merged = AccountLists.mergeFeed(account, local, now)
        assertEquals(listOf("l1", "a1", "both", "l2", "odd"), merged.map { it.id })
        assertEquals(now - 5 * hour, merged.first { it.id == "both" }.uploadedAtMs, "the local copy, with its exact time")
        assertEquals(now - 3 * hour, merged.first { it.id == "a1" }.uploadedAtMs)
        assertNull(merged.last().uploadedAtMs, "no time: after the dated ones")
    }

    @Test fun relativeTimesAreRead() {
        assertEquals(now - 2 * 86_400_000L, AccountLists.estimateUploadedAt("Streamed 2 days ago", now))
        assertEquals(now - 60_000L, AccountLists.estimateUploadedAt("1 minute ago", now))
        assertEquals(now - 365 * 86_400_000L, AccountLists.estimateUploadedAt("1 year ago", now))
        assertNull(AccountLists.estimateUploadedAt("vor 2 Tagen", now))
        assertNull(AccountLists.estimateUploadedAt(null, now))
    }
}
