package com.cosmotune.video.ui

import com.cosmotune.video.account.*
import com.cosmotune.video.model.*
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.blocks.badgeTextOf
import com.cosmotune.video.ui.home.AccountHomeFeed
import com.cosmotune.video.ui.shell.KeptPages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VideoListStabilityTest {
    private val v = FakeVideoSource.videos
    private fun mix() = VideoItem(
        "list:RDx", "Mix - Quiet Evenings", null, "Maple Choir and more · Playlist", null, v[0].thumbnailUrl, null, null, null, null,
        playlist = PlaylistRef("RDx", "Mix", v[0].id),
    )

    private class Account(val pages: MutableMap<String?, Page<VideoItem>>) : VideoAccount by FakeVideoAccount() {
        override suspend fun home(continuation: PageToken?): Result<Page<VideoItem>> = Result.success(pages.getValue(continuation?.value))
    }

    // ---- 3: coming back -----------------------------------------------------------------------------------------------

    @Test fun aPageGetsTheSameListBackWhenItIsDrawnAgain() {
        var now = 0L
        val kept = KeptPages(clock = { now })
        val first = kept.get("home#1", "stream") { mutableListOf("a") }
        now += 5 * 60_000
        assertSame(first, kept.get("home#1", "stream") { mutableListOf("b") })
        assertNotSame(first, kept.get("home#2", "stream") { mutableListOf("c") }, "another page entry keeps its own")
    }

    @Test fun aLongTimeAwayIsAFreshStartAndALeftPageIsForgotten() {
        var now = 0L
        val kept = KeptPages(ttlMs = 60_000, clock = { now })
        val first = kept.get("home#1", "stream") { Any() }
        now += 61_000
        assertNotSame(first, kept.get("home#1", "stream") { Any() })
        val second = kept.get("home#1", "stream") { Any() }
        kept.drop("home#1")
        assertNotSame(second, kept.get("home#1", "stream") { Any() })
    }

    // ---- 2: loading more only appends ---------------------------------------------------------------------------------

    @Test fun moreCardsAreAppendedAndRepeatsAreDroppedWithoutMovingAnything() = runTest {
        val first = listOf(v[0], v[1], v[2])
        val account = Account(mutableMapOf(null to Page(first, PageToken("n")), "n" to Page(listOf(v[2], v[3], v[1], v[4]), null)))
        val feed = AccountHomeFeed(account, backgroundScope)
        feed.start(); runCurrent(); feed.loadMore(); runCurrent()
        assertEquals(listOf(v[0], v[1], v[2], v[3], v[4]).map { it.id }, feed.items.map { it.id })
    }

    // ---- the mix --------------------------------------------------------------------------------------------------------

    @Test fun aMixStaysWhereTheSiteSentItInHomesFeed() = runTest {
        val sent = listOf(v[0], v[1], mix(), v[2])
        val feed = AccountHomeFeed(Account(mutableMapOf(null to Page(sent, null))), backgroundScope)
        feed.start(); runCurrent()
        assertEquals(listOf(v[0].id, v[1].id, "list:RDx", v[2].id), feed.items.map { it.id })
        assertTrue(feed.items[2].isPlaylistCard)
    }

    // ---- 1: the length on the picture ---------------------------------------------------------------------------------

    @Test fun theBadgeReadsLikeTheSiteDoes() {
        val base = v[0].copy(durationSec = 724, isLive = false, isUpcoming = false, isShort = false)
        assertEquals("12:04", badgeTextOf(base, "LIVE"))
        assertEquals("1:02:33", badgeTextOf(base.copy(durationSec = 3753), "LIVE"))
        assertEquals("0:45", badgeTextOf(base.copy(durationSec = 45), "LIVE"))
        assertEquals("LIVE", badgeTextOf(base.copy(durationSec = null, isLive = true), "LIVE"))
        assertNull(badgeTextOf(base.copy(durationSec = null), "LIVE"))
        assertNull(badgeTextOf(base.copy(durationSec = 0), "LIVE"))
        assertNull(badgeTextOf(base.copy(isUpcoming = true), "LIVE"))
        assertEquals("Mix", badgeTextOf(mix(), "LIVE"))
    }
}
