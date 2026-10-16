package com.cosmotune.video.ui

import com.cosmotune.video.feed.FakeSubscriptionFeed
import com.cosmotune.video.feed.FeedGroup
import com.cosmotune.video.feed.FeedState
import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.subscriptions.SubscriptionsFeedPlan
import com.cosmotune.video.ui.subscriptions.refreshQuietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Subscriptions tab (plan task Y-21, step 2): what no render can show is read from the source (the refresh on open and on pull,
 * the keys of every lazy item, the branch line, the calls the brief names, where the colours come from), and the decisions the page
 * makes about the feed are run (the channel filter, the Shorts shelf, the order of the strip, the day groups, the quiet refresh).
 */
class SubscriptionsStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/subscriptions"
    private val pageSrc = VideoSourceContract.read("$dir/SubscriptionsPage.kt")
    private val statusSrc = VideoSourceContract.read("$dir/FeedStatusLine.kt")
    private val page = VideoSourceContract.code(pageSrc)
    private val status = VideoSourceContract.code(statusSrc)
    private val navHost = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt"))

    // ---- The files, the signatures and the branch -------------------------------------------------------------------------

    @Test fun theDirectoryHoldsTheBriefsTwoFiles() {
        val names = File(VideoSourceContract.root, dir).listFiles { f -> f.extension == "kt" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(setOf("SubscriptionsPage", "FeedStatusLine"), names)
    }

    @Test fun thePageKeepsTheBriefsSignature() {
        assertEquals(listOf("contentPadding: PaddingValues", "wide: Boolean"), VideoSourceContract.parameters(pageSrc, "SubscriptionsPage"))
        assertTrue(Regex("""@Composable\s+fun SubscriptionsPage\(""").containsMatchIn(page), "a public composable")
    }

    @Test fun theStatusLineTakesWhatTheSpecShowsAndNothingMore() {
        assertEquals(
            listOf(
                "updatedAtMs: Long?", "failedCount: Int", "onRetry: () -> Unit", "modifier: Modifier = Modifier", "onRefresh: (() -> Unit)? = null",
                "onDownloadAll: (() -> Unit)? = null",
            ),
            VideoSourceContract.parameters(statusSrc, "FeedStatusLine"),
        )
    }

    @Test fun theNavHostBranchIsOneLineThatCallsThePageAndNothingElseChanged() {
        val branch = "is VideoRoute.Subscriptions -> com.cosmotune.video.ui.subscriptions.SubscriptionsPage(contentPadding, wide)"
        assertEquals(1, navHost.lines().count { it.trim() == branch }, "the Subscriptions branch calls the page, on its own line")
        assertFalse("is VideoRoute.Subscriptions -> VideoPlaceholder" in navHost)
    }

    // ---- The refresh -------------------------------------------------------------------------------------------------------

    @Test fun theFeedRefreshesOnOpenNotForcedAndOnPullForced() {
        assertEquals(1, Regex("""LaunchedEffect\(feed\)\s*\{\s*feed\.refreshQuietly\(force = false\)\s*\}""").findAll(page).count(), "on open: what is due")
        assertEquals(1, Regex("""feed\.refreshQuietly\(force = true\)""").findAll(page).count(), "one forced refresh, shared by the pull, Try again and Refresh")
        assertEquals(3, Regex("""\brefreshQuietly\(""").findAll(page).count(), "the two calls and the declaration: nothing else reads the feed")
        assertTrue("PullToRefresh(refreshing = refreshing, onRefresh = refresh, state = pullState)" in page, "the pull reads every channel")
        assertTrue("onRetry = refresh" in page, "Try again reads every channel")
        assertTrue("onRefresh = if (pullEnabled) null else refresh" in page, "a window that cannot pull has a Refresh button")
        assertTrue("LocalPullToRefreshEnabled.current && !wide" in page, "a wide page is not pulled (the desktop frame turns the pull off for Music's pages only)")
        assertTrue("CompositionLocalProvider(LocalPullToRefreshEnabled provides pullEnabled)" in page)
        assertTrue("if (!forced)" in page, "a second pull waits for the first")
    }

    @Test fun aRefreshThatFailsEndsQuietlyAndACancelledOneIsNotSwallowed() = runBlocking {
        val fake = FakeSubscriptionFeed()
        fake.refreshQuietly(force = false)
        assertEquals(false, fake.lastForce)
        fake.refreshQuietly(force = true)
        assertEquals(true, fake.lastForce)
        assertEquals(2, fake.refreshCount)

        val failing = object : SubscriptionFeed {
            override val state: StateFlow<FeedState> = MutableStateFlow(FakeSubscriptionFeed.SAMPLE)
            override suspend fun refresh(force: Boolean) = throw IllegalStateException("the source changed")
        }
        failing.refreshQuietly(force = true)
        val cancelling = object : SubscriptionFeed {
            override val state: StateFlow<FeedState> = MutableStateFlow(FakeSubscriptionFeed.SAMPLE)
            override suspend fun refresh(force: Boolean) = throw CancellationException("the page left")
        }
        assertFailsWith<CancellationException> { cancelling.refreshQuietly(force = false) }
        Unit
    }

    @Test fun theBlobIsDrawnUnderTheTopBarFromThePullAndTheRefresh() {
        assertTrue("CosmoPullIndicator(" in page)
        assertTrue("fraction = { pullState.distanceFraction }" in page, "the pull is read in a lambda, so a drag recomposes nothing")
        assertTrue("refreshing = refreshing" in page)
        assertTrue("val refreshing = feedState.refreshing || forced" in page, "a refresh that is running, or one that was asked for")
        assertTrue("contentPadding.calculateTopPadding()" in page, "it starts at the bottom of the bar")
    }

    // ---- The lists ---------------------------------------------------------------------------------------------------------

    @Test fun everyLazyItemHasAKey() {
        val calls = Regex("""\bitems?\(""").findAll(page).map { page.substring(it.range.first, page.indexOf('\n', it.range.first)) }.toList()
        assertTrue(calls.size >= 7, "the page has its items: ${calls.size}")
        for (call in calls) assertTrue("key = " in call, "an item with no key: $call")
        // the keys of the head and the groups cannot meet the id of a video (an id never holds a colon)
        for (name in listOf("KEY_STRIP", "KEY_SHORTS", "KEY_STATUS", "KEY_OPEN_CHANNEL")) assertTrue(Regex("const val " + name + " = \"feed:[A-Za-z-]+\"").containsMatchIn(page), name)
        assertTrue("key = \"feed:group:\${group.name}\"" in page && "key = { it.id }" in page, "a card is keyed by its video id")
        assertTrue("\"feed:row:\${it.first().id}\"" in page, "a grid line by its first card")
    }

    @Test fun theFeedIsGroupedUnderTheDayOverlinesThroughTheFeedsOwnHelpers() {
        assertTrue("FeedMerge.groups(items, FeedDay.startOfToday(nowMs, zone))" in page)
        for (group in listOf("TODAY" to "video_today", "YESTERDAY" to "video_yesterday", "THIS_WEEK" to "video_this_week", "THIS_MONTH" to "video_this_month", "OLDER" to "video_older")) {
            assertTrue("FeedGroup.${group.first} -> Res.string.${group.second}" in page, group.first)
        }
        assertTrue("CosmoOverline(" in page)
    }

    @Test fun theStripIsTheBlocksAndItsChoiceIsKeptWithThePage() {
        assertTrue("ChannelStrip(channels = model.strip, selectedId = model.selectedId, onSelect = onSelect" in page)
        assertTrue("var picked by rememberSaveable { mutableStateOf<String?>(null) }" in page)
        assertTrue("picked?.takeIf { id -> channels.any { it.channelId == id } }" in page, "a channel you left is no choice")
        assertTrue("onSelect = { picked = it }" in page)
        // the picked channel brings its button, which opens the channel
        assertTrue("video_open_channel_chip" in page && "VideoRoute.Channel(id)" in page)
        assertTrue("CosmoChip(" in page && "role = Role.Button" in page && "clearAndSetSemantics" in page, "a chip to look at, a button to a screen reader")
    }

    @Test fun theShortsShelfOpensThePagerAndAShortInTheFeedKeepsItsCard() {
        assertTrue("ShortsShelf(" in page)
        assertTrue("navigator.openShort(it, shortsOrigin, shelf)" in page, "a shelf card opens the pager with the shelf's Shorts")
        assertTrue("navigator.push(VideoRoute.Shorts(shortsOrigin))" in page, "See all")
        assertTrue("if (selectedId == null) ShortsOrigin.Subscriptions else ShortsOrigin.Channel(selectedId)" in page)
        assertTrue("if (item.isShort) navigator.openShort(item, shortsOrigin, shown) else actions.play(item, shown)" in page, "a Short card opens the pager, a video its page")
        assertTrue("if (model.shelf.isNotEmpty())" in page, "the shelf is left out when there is nothing for it")
        // the cards below are every item of the feed: Shorts are not removed from the groups
        assertFalse(Regex("""groups\([^)]*isShort""").containsMatchIn(page))
    }

    // ---- The empty state, the sheet, the downloads ---------------------------------------------------------------------------

    @Test fun withoutSubscriptionsThePageIsTheEmptyStateWithImportAndFindChannels() {
        assertTrue("if (channels.isEmpty())" in page)
        assertTrue("CosmoEmptyState(" in page)
        for (key in listOf("video_no_subscriptions", "video_no_subscriptions_text", "video_import", "video_find_channels")) assertTrue("Res.string.$key" in page, key)
        assertTrue("onImport = { navigator.push(VideoRoute.Import) }" in page)
        assertTrue("onFind = { navigator.push(VideoRoute.Search(\"\")) }" in page)
    }

    @Test fun theFeedsHeaderDownloadsEverythingShownThroughTheSharedHandler() {
        assertTrue(
            "actions.downloadBatch(DownloadBatchInfo(\"feed\", BatchKind.FEED, title), shown, latestPicker = false)" in page,
            "the brief's call, with the videos shown",
        )
        assertTrue("val title = stringResource(Res.string.video_tab_subscriptions)" in page)
        assertTrue("onDownloadAll = downloadAll" in page && "if (shown.isEmpty()) null" in page, "nothing to download, no button")
        assertTrue("Res.string.video_dl_all" in status)
        assertTrue("rememberVideoActions()" in page)
    }

    @Test fun aCardsMoreButtonOpensTheVideoSheetThatTheHandlerAnswers() {
        assertTrue("VideoActionsSheet(" in page)
        assertTrue("actions.handle(item, action, VideoSheetContext.DEFAULT, list = shown)" in page, "the list is passed, so a Short opens the pager with its list")
        assertTrue("downloaded = item.id in downloaded" in page && "rememberDownloadedIds()" in page)
        assertTrue("rememberVideoTitle(item)" in page && "rememberVideoThumbnail(item)" in page, "DeArrow through the shared helpers, never a client")
        assertFalse("graph.branding" in page || "DeArrowClient" in page)
    }

    // ---- The wide page ------------------------------------------------------------------------------------------------------

    @Test fun theWidePageIsAGridPerDayFromTheMetrics() {
        assertTrue("VideoMetrics.gridColumns(maxWidth.value)" in page)
        assertTrue("VideoMetrics.GRID_COL_GAP_DP.dp" in page && "VideoMetrics.GRID_ROW_GAP_DP.dp" in page && "VideoMetrics.DESKTOP_MARGIN_DP.dp" in page)
        assertTrue("(VideoMetrics.DESKTOP_MARGIN_DP - VideoMetrics.MARGIN_DP).dp" in page, "the phone blocks' 16 dp plus 12 stand on the 28 dp line")
        assertTrue("list.chunked(columns)" in page)
        assertTrue("VideoCardBody(shown, onClick, onMore, modifier, watchedFraction, title, downloaded)" in page, "the card without its margin in a cell")
        assertTrue("VideoCard(shown, onClick, onMore, modifier, watchedFraction, title, downloaded)" in page, "the full card on the phone")
    }

    // ---- The status line ---------------------------------------------------------------------------------------------------

    @Test fun theStatusLineSaysWhenTheFeedWasUpdatedAndWhatFailed() {
        for (key in listOf("video_updated", "video_feed_updated_now", "video_channels_failed", "video_retry", "video_feed_refresh", "video_dl_all")) assertTrue("Res.string.$key" in status || "Res.plurals.$key" in status, key)
        assertTrue("private const val STATUS_SP = 12.5f" in status, "12.5 sp")
        assertTrue("Color(palette.textTertiary)" in status, "textTertiary")
        assertTrue("pluralStringResource(Res.plurals.video_channels_failed, failedCount, failedCount)" in status)
        assertTrue("onClick = onRetry" in status)
        assertTrue("private val STATUS_MIN_HEIGHT = 48.dp" in status, "a 48 dp row for the small text buttons")
        assertTrue("private const val TICK_MS = 60_000L" in status, "the age is worded again every minute, not every frame")
        assertTrue("LocalVideoNowMs.current" in status, "a render may fix the clock")
    }

    // ---- The look and the rules ---------------------------------------------------------------------------------------------

    @Test fun noColourIsPickedByHandAndTheLivePaletteAndThePlayerPositionAreNotUsedHere() {
        for ((name, src) in listOf("SubscriptionsPage" to page, "FeedStatusLine" to status)) {
            assertFalse(Regex("""Color\(0x""").containsMatchIn(src), "$name picks no colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Black|White|Gray|Yellow)""").containsMatchIn(src), "$name uses no named colour")
            assertFalse(".live(" in src, "$name reads no live colour: it draws nothing itself")
            assertFalse("positionMs()" in src, "$name does not read the player")
            assertFalse(Regex("(?i)youtube|\\byt\\b").containsMatchIn(src), "$name says nothing of the source")
            assertFalse(("org.schabi" + ".newpipe") in src, "$name does not name the extractor")
        }
        // colours that are read come from the palette in composition
        assertTrue(Regex("""Color\(palette\.\w+\)""").containsMatchIn(status))
    }

    @Test fun everyTextIsAStringResourceAndTheTypeAndMotionComeFromTheShell() {
        for ((name, src) in listOf("SubscriptionsPage" to pageSrc, "FeedStatusLine" to statusSrc)) {
            assertFalse(Regex("""Text\(\s*(text = )?""" + "\"").containsMatchIn(src), "$name has no literal text")
            assertFalse(Regex("""(title|message|label|text|actionLabel) = "[A-Za-z]""").containsMatchIn(VideoSourceContract.code(src)), "$name has no literal label")
        }
        assertTrue("LocalCosmoType.current" in status)
        assertFalse(Regex("""FontFamily\.""").containsMatchIn(status))
        assertFalse("LaunchedEffect(Unit)" in page, "a keyed effect only")
    }

    @Test fun theMusicAccountAndTheNetworkAreNotTouched() {
        for (src in listOf(page, status)) {
            assertFalse("cookie" in src.lowercase())
            assertFalse("OkHttp" in src)
            assertFalse(Regex("""graph\.(source|music)\b""").containsMatchIn(src), "the page reads the feed, the library, the settings and the handler")
        }
    }

    // ---- The decisions, run -----------------------------------------------------------------------------------------------

    private fun video(id: String, channel: String?, at: Long?, short: Boolean = false, upcoming: Boolean = false) = VideoItem(
        id = id, title = id, channelId = channel, channelName = channel, channelAvatarUrl = null, thumbnailUrl = null, durationSec = null, viewCount = null,
        uploadedAtMs = at, uploadedText = null, isUpcoming = upcoming, isShort = short,
    )

    private fun sub(id: String, name: String = id, last: Long? = null) = Subscription(id, name, null, 0L, last)

    private val now = FakeVideoSource.NOW_MS

    @Test fun aPickedChannelLeavesItsVideosAndTheListHasEachVideoOnce() {
        val items = listOf(video("a1", "A", 3), video("b1", "B", 2), video("a2", "A", 1), video("a1", "A", 3), video("n1", null, 0))
        assertEquals(listOf("a1", "b1", "a2", "n1"), SubscriptionsFeedPlan.forChannel(items, null).map { it.id }, "all: each video once, the feed's order")
        assertEquals(listOf("a1", "a2"), SubscriptionsFeedPlan.forChannel(items, "A").map { it.id })
        assertEquals(emptyList(), SubscriptionsFeedPlan.forChannel(items, "C"))
        assertEquals(emptyList(), SubscriptionsFeedPlan.forChannel(emptyList(), null))
    }

    @Test fun theShelfShowsFromThreeShortsNewestFirstAndNotWithHideShorts() {
        assertEquals(3, SubscriptionsFeedPlan.SHELF_MIN_SHORTS)
        val s1 = video("s1", "A", 10, short = true)
        val s2 = video("s2", "A", 30, short = true)
        val s3 = video("s3", "B", 20, short = true)
        val s4 = video("s4", "B", null, short = true)
        val v = video("v1", "A", 40)
        assertEquals(emptyList(), SubscriptionsFeedPlan.shelf(listOf(s1, s2, v), hideShorts = false), "two Shorts are no shelf")
        assertEquals(listOf("s2", "s3", "s1"), SubscriptionsFeedPlan.shelf(listOf(s1, v, s2, s3), hideShorts = false).map { it.id }, "newest first, videos left out")
        assertEquals(listOf("s2", "s3", "s1", "s4"), SubscriptionsFeedPlan.shelf(listOf(s4, s1, s2, s3), hideShorts = false).map { it.id }, "no time: last")
        assertEquals(emptyList(), SubscriptionsFeedPlan.shelf(listOf(s1, s2, s3), hideShorts = true), "Hide Shorts hides it")
        assertEquals(emptyList(), SubscriptionsFeedPlan.shelf(listOf(v), hideShorts = false))
    }

    @Test fun theSampleFeedOfTheFakesGivesAShelfAndAFullPage() {
        val feed = FakeSubscriptionFeed.SAMPLE
        assertEquals(4, SubscriptionsFeedPlan.shelf(feed.items, hideShorts = false).size)
        assertEquals(feed.items.size, SubscriptionsFeedPlan.forChannel(feed.items, null).size)
        val groups = SubscriptionsFeedPlan.groups(feed.items, now, ZoneOffset.UTC)
        assertEquals(feed.items.size, groups.sumOf { it.second.size }, "no item is lost under the overlines, Shorts included")
        assertTrue(groups.first().first == FeedGroup.TODAY || groups.first().first == FeedGroup.YESTERDAY)
    }

    @Test fun theStripPutsTheNewestUploadFirstFromTheFeedAndTheRecordedTime() {
        val items = listOf(video("c1", "C", 300), video("a1", "A", 100), video("b1", "B", 200), video("b0", "B", 50))
        val channels = listOf(sub("A"), sub("B"), sub("C"), sub("D", "dee"), sub("E", "Eve"))
        assertEquals(listOf("C", "B", "A", "D", "E"), SubscriptionsFeedPlan.stripOrder(channels, items).map { it.channelId }, "newest first; none known: by name")
        // a recorded time counts when it is later than the feed's, and alone when the feed has nothing
        val recorded = listOf(sub("A", last = 500), sub("B"), sub("D", "dee", last = 250))
        assertEquals(listOf("A", "D", "B"), SubscriptionsFeedPlan.stripOrder(recorded, items.filter { it.channelId != "C" }).map { it.channelId })
        // a premiere still to come does not make a channel the newest
        val premiere = listOf(video("p1", "A", 10_000, upcoming = true), video("b1", "B", 200), video("a1", "A", 100))
        assertEquals(listOf("B", "A"), SubscriptionsFeedPlan.stripOrder(listOf(sub("A"), sub("B")), premiere).map { it.channelId })
        assertEquals(emptyList(), SubscriptionsFeedPlan.stripOrder(emptyList(), items))
        // ties and unknowns keep a stable order by name, case ignored
        assertEquals(listOf("x", "Y", "z"), SubscriptionsFeedPlan.stripOrder(listOf(sub("2", "z"), sub("1", "Y"), sub("3", "x")), emptyList()).map { it.name })
    }

    @Test fun theDayGroupsAreTheFeedsFromTheLocalMidnight() {
        val midnight = now - now % 86_400_000L   // the fakes' clock is noon UTC on the day
        val day = 86_400_000L
        val items = listOf(
            video("t", "A", midnight + 1_000), video("y", "A", midnight - 1_000), video("w", "A", midnight - 3 * day),
            video("m", "A", midnight - 20 * day), video("o", "A", midnight - 90 * day), video("n", "A", null),
        )
        val groups = SubscriptionsFeedPlan.groups(items, now, ZoneOffset.UTC)
        assertEquals(listOf(FeedGroup.TODAY, FeedGroup.YESTERDAY, FeedGroup.THIS_WEEK, FeedGroup.THIS_MONTH, FeedGroup.OLDER), groups.map { it.first })
        assertEquals(listOf("o", "n"), groups.last().second.map { it.id }, "older holds the old and the undated, in feed order")
        assertEquals(emptyList(), SubscriptionsFeedPlan.groups(emptyList(), now, ZoneOffset.UTC))
    }

    @Test fun theFeedKeepsItsOrderWhenNothingIsFiltered() {
        val items = FakeSubscriptionFeed.SAMPLE.items
        assertEquals(items, SubscriptionsFeedPlan.forChannel(items, null))
    }
}
