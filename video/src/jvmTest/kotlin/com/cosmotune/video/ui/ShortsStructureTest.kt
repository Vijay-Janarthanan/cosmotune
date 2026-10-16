package com.cosmotune.video.ui

import androidx.compose.ui.unit.IntSize
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.shorts.ShortsFeed
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.shorts.ShortsPage
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.shorts.HeartBurstCurve
import com.cosmotune.video.ui.shorts.ShortsController
import com.cosmotune.video.ui.shorts.ShortsGridLayout
import com.cosmotune.video.ui.shorts.ShortsQueue
import com.cosmotune.video.ui.shorts.ShortsSession
import com.cosmotune.video.ui.shorts.ShortsStepGate
import com.cosmotune.video.ui.shorts.shortsScale
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Shorts screens of plan task Y-31 (spec 5.0, 5.15): what cannot be seen without a window is pinned by reading the source (the
 * pager's keys and settled page, the surface, the playhead read only while drawing, solid glass, the stubs' signatures, the branch line
 * and the graph lines), and the logic is run on the fakes of `:shared` (the queue, the controller, the scale, the step gate, the heart's
 * curve, the grid's numbers).
 */
class ShortsStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shorts"
    private fun shorts(name: String) = read("$dir/$name.kt")
    private val files = listOf("ShortsStubs", "ShortsCard", "ShortsPager", "ShortsOverlay", "ShortsProgressLine", "HeartBurst", "ShortsDesktopPage")
    private val code: List<Pair<String, String>> get() = files.map { it to VideoSourceContract.code(shorts(it)) }

    private fun s(n: Int): VideoItem = FakeVideoSource.shorts[n - 1]

    @BeforeTest @AfterTest fun forget() = ShortsSession.reset()

    // ---- The source rules ----------------------------------------------------------------------------------------------------

    @Test fun thePagersUseStableKeysAndComposeTheNextPageAhead() {
        for (name in listOf("ShortsPager", "ShortsDesktopPage")) {
            val src = VideoSourceContract.code(shorts(name))
            assertTrue("VerticalPager(" in src, "$name: a vertical pager")
            assertTrue("key = { page -> queue.keyAt(page) }" in src, "$name: keyed by the Short's id")
            assertTrue("beyondViewportPageCount = 1" in src, "$name: the next page is composed ahead (its thumbnail loads)")
            assertTrue("active = page == pagerState.settledPage" in src, "$name: only the settled page is active")
            assertTrue("DriveShorts(queue, pagerState, controller)" in src, "$name: plays the settled page and loads more")
        }
    }

    @Test fun onlyTheSettledPageDrawsTheSurfaceAndPlays() {
        val pager = VideoSourceContract.code(shorts("ShortsPager"))
        assertEquals(1, Regex("""\.Surface\(""").findAll(pager).count(), "one place draws the player's picture")
        assertTrue("val loaded = active && state.item?.id == item.id" in pager)
        assertTrue(Regex("""val showSurface = loaded &&""").containsMatchIn(pager))
        assertTrue("if (showSurface) graph.surface?.Surface(Modifier.fillMaxSize(), shortsScale(state.aspect, box))" in pager)
        val drive = VideoSourceContract.functionBody(pager, "DriveShorts")
        assertTrue("pagerState.settledPage" in drive && "controller.onSettled(it.id, it.next)" in drive, "the settled page, never the current one")
        assertTrue("queue.wantsMore(pagerState.currentPage)" in drive && "queue.more()" in drive)
        val settled = VideoSourceContract.functionBody(pager, "onSettled")
        assertTrue("playback.play(id, short = true)" in settled && "playback.prefetch(next)" in settled)
        for ((name, src) in code) assertFalse("short = false" in src, "$name: a Short is never played as a video")
    }

    @Test fun thePlayheadIsReadOnlyInDrawPhaseCode() {
        var reads = 0
        for ((name, src) in code) {
            for (match in Regex("""positionMs\(\)""").findAll(src)) {
                reads++
                val heads = enclosingHeads(src, match.range.first)
                assertTrue(heads.any { drawHead.containsMatchIn(it) }, "$name: positionMs() outside a draw lambda: ${heads.firstOrNull()?.takeLast(80)}")
            }
        }
        assertEquals(1, reads, "the progress line reads the playhead, in its draw block")
        val line = VideoSourceContract.code(shorts("ShortsProgressLine"))
        assertTrue("withFrameNanos(onFrame)" in line && "if (active && playing) while (true)" in line, "a frame ticker only while the settled Short plays")
        assertTrue(Regex("""\.drawBehind \{\s+frame\.longValue\s""").containsMatchIn(line), "the draw block reads the tick")
    }

    @Test fun liveColoursOnlyInsideDrawLambdasAndNoColourPickedByHand() {
        for ((name, src) in code) {
            for (match in Regex("""\.live\(""").findAll(src)) {
                assertTrue(enclosingHeads(src, match.range.first).any { drawHead.containsMatchIn(it) }, "$name: live( outside a draw lambda")
            }
            assertFalse(Regex("""Color\(0x|Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)""").containsMatchIn(src), "$name: a colour by hand")
        }
        // white and black are the media exception: never in the shelf and grid file, which draws on the page
        assertFalse(Regex("""Color\.(Black|White)""").containsMatchIn(VideoSourceContract.code(shorts("ShortsStubs"))))
    }

    @Test fun everyCSurfaceIsSolidGlassWithNoBlurOverTheVideo() {
        var glass = 0
        for ((name, src) in code) {
            for (match in Regex("""cosmoGlass\(([^)]*)\)""").findAll(src)) {
                glass++
                assertTrue(match.groupValues[1].startsWith("hazeState = null"), "$name: ${match.value}")
            }
            assertFalse("hazeSource" in src || "hazeEffect" in src || "HazeState(" in src, "$name: no blur of its own")
        }
        assertEquals(3, glass, "the Subscribe chip, the pause glyph and the 2x pill")
        // the icon buttons are the C glass style
        val overlay = VideoSourceContract.code(shorts("ShortsOverlay"))
        assertEquals(Regex("""CosmoIconButton\(""").findAll(overlay).count(), Regex("""style = CosmoIconButtonStyle\.GLASS""").findAll(overlay).count())
        val desk = VideoSourceContract.code(shorts("ShortsDesktopPage"))
        assertEquals(Regex("""CosmoIconButton\(""").findAll(desk).count(), Regex("""style = CosmoIconButtonStyle\.GLASS""").findAll(desk).count())
    }

    @Test fun theStubsKeepTheirSignaturesAndTheNewPublicNamesHaveTheBriefs() {
        val stubs = shorts("ShortsStubs")
        assertEquals(
            listOf("title: String", "items: List<VideoItem>", "onOpen: (VideoItem) -> Unit", "onSeeAll: (() -> Unit)?", "modifier: Modifier = Modifier"),
            VideoSourceContract.parameters(stubs, "ShortsShelf"),
        )
        assertEquals(
            listOf("items: List<VideoItem>", "onOpen: (VideoItem) -> Unit", "onLoadMore: () -> Unit", "contentPadding: PaddingValues", "wide: Boolean", "modifier: Modifier = Modifier"),
            VideoSourceContract.parameters(stubs, "ShortsGrid"),
        )
        assertTrue("fun VideoNavigator.openShort(item: VideoItem, origin: ShortsOrigin, list: List<VideoItem>)" in stubs)
        assertTrue("CosmoSectionHeader(title = title, onShowAll = onSeeAll)" in VideoSourceContract.functionBody(stubs, "ShortsShelf"))
        assertEquals(
            listOf("item: VideoItem", "width: Dp", "onClick: () -> Unit", "onMore: () -> Unit", "modifier: Modifier = Modifier", "downloaded: Boolean = false"),
            VideoSourceContract.parameters(shorts("ShortsCard"), "ShortsCard"),
        )
        assertEquals(
            listOf("route: VideoRoute.Shorts", "contentPadding: PaddingValues", "wide: Boolean"),
            VideoSourceContract.parameters(shorts("ShortsPager"), "ShortsScreen"),
        )
        val engine = read("shared/src/commonMain/kotlin/com/cosmotune/video/shorts/ShortsQueueEngine.kt")
        assertEquals(
            listOf("private val source: VideoSource", "private val feed: SubscriptionFeed", "private val library: VideoLibrary", "private val settings: VideoSettingsRepository"),
            Regex("""class ShortsQueueEngine\(([^)]*)\)""").find(engine)!!.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertTrue(") : ShortsFeed {" in engine)
    }

    @Test fun theBranchLineAndTheGraphLines() {
        val nav = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt").lines()
        val branch = nav.single { it.trim().startsWith("is VideoRoute.Shorts ->") }
        assertEquals("is VideoRoute.Shorts -> com.cosmotune.video.ui.shorts.ShortsScreen(r, contentPadding, wide)", branch.trim())
        val at = nav.indexOf(branch)
        assertTrue(nav[at - 1].isBlank() && nav[at + 1].isBlank(), "the blank lines around the branch stay")
        for (path in listOf(
            "video/src/androidMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.android.kt",
            "video/src/jvmMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.jvm.kt",
        )) {
            val lines = read(path).lines()
            val line = lines.single { it.trim().startsWith("val shorts = ") }
            assertEquals("val shorts = ShortsQueueEngine(source, feed, library, settings)", line.trim(), path)
            assertTrue(lines[lines.indexOf(line) + 1].isBlank(), "$path: a blank line follows the slot")
            assertTrue("import com.cosmotune.video.shorts.ShortsQueueEngine" in lines, path)
            assertFalse(lines.any { "FakeShortsFeed" in it }, "$path: the fake is gone")
        }
    }

    @Test fun theScreensUseTheNumbersOfVideoMetrics() {
        val pager = shorts("ShortsPager")
        for (n in listOf("PAGER_BLOCK_START_DP", "PAGER_BLOCK_END_DP", "PAGER_BLOCK_BOTTOM_DP", "PAGER_RAIL_END_DP", "PAGER_RAIL_BOTTOM_DP", "PAGER_TOP_BUTTON_DP")) {
            assertTrue("VideoMetrics.$n" in pager, n)
        }
        val overlay = shorts("ShortsOverlay")
        for (n in listOf(
            "PAGER_TOP_SCRIM_DP", "PAGER_TOP_SCRIM_ALPHA", "PAGER_BOTTOM_SCRIM_DP", "PAGER_BOTTOM_SCRIM_ALPHA", "PAGER_TOP_BUTTON_DP", "PAGER_TITLE_BAR_SP",
            "PAGER_RAIL_BUTTON_DP", "PAGER_RAIL_GAP_DP", "PAGER_AVATAR_DP", "PAGER_CHIP_DP", "PAGER_SHORT_TITLE_SP", "PAGER_PAUSE_GLYPH_DP", "PAGER_PAUSE_GLYPH_MS",
        )) {
            assertTrue("VideoMetrics.$n" in overlay, n)
        }
        assertTrue("CosmoIcons.ArrowLeft" in overlay && "VideoIcons.VolumeOff" in overlay && "CosmoIcons.Volume" in overlay)
        assertTrue("CosmoIcons.HeartFill" in overlay && "VideoIcons.Comment" in overlay && "CosmoIcons.Share" in overlay && "CosmoIcons.More" in overlay)
        val line = shorts("ShortsProgressLine")
        assertTrue("VideoMetrics.PAGER_PROGRESS_DP" in line && "VideoMetrics.PAGER_PROGRESS_DRAG_DP" in line)
        assertTrue("Color.White.copy(alpha = 0.90f)" in line && "Color.White.copy(alpha = 0.30f)" in line, "played white 90 %, the rest white 30 %")
        val heart = shorts("HeartBurst")
        assertTrue("VideoMetrics.HEART_BURST_DP" in heart && "VideoMetrics.HEART_BURST_MS" in heart && "Springs.REDUCED_MS" in heart && "CosmoIcons.HeartFill" in heart)
        val card = VideoSourceContract.code(shorts("ShortsCard"))
        assertTrue("VideoMetrics.shortsCardHeightDp(width.value)" in card && "VideoMetrics.SHORTS_TITLE_SP" in card && "VideoMetrics.SHORTS_VIEWS_SP" in card)
        assertTrue("rememberVideoTitle(item)" in card && "rememberVideoThumbnail(item)" in card, "DeArrow, limited and batched")
        assertTrue(Regex("""fun Modifier\.shortsCardScrim\(\): Modifier = drawWithCache""").containsMatchIn(card), "the scrim is drawn in drawWithCache")
        val stubs = VideoSourceContract.code(shorts("ShortsStubs"))
        assertTrue("VideoMetrics.SHORTS_SHELF_W_DP" in stubs && "VideoMetrics.SHORTS_SHELF_GAP_DP" in stubs && "items(count = items.size, key = { keys[it] })" in stubs)
        val desk = shorts("ShortsDesktopPage")
        assertTrue("VideoMetrics.DESKTOP_SHORTS_VMARGIN_DP" in desk && "VideoMetrics.PLAYER_RADIUS_DP" in desk && "VideoMetrics.SIDE_COLUMN_DP" in desk)
        for (k in listOf("Key.DirectionUp", "Key.DirectionDown", "Key.Spacebar", "Key.M", "PointerEventType.Scroll")) assertTrue(k in desk, k)
        assertTrue("const val QUIET_MS = 250L" in desk)
    }

    @Test fun noSourceNameInTheShortsFiles() {
        for ((name, src) in code) assertFalse(Regex("""(?i)youtube|org\.schabi""").containsMatchIn(src), name)
    }

    // ---- The queue -----------------------------------------------------------------------------------------------------------

    @Test fun theQueueShowsTheSeedAtOnceThenItsPagesAndEndsWithALastPage() = runTest {
        val feed = FakeShortsFeed()
        val queue = ShortsQueue(feed, ShortsOrigin.Home, listOf(s(7), s(3)))
        assertEquals(listOf(s(7).id, s(3).id), queue.items.map { it.id }, "the seed before any load")
        assertEquals(1, queue.indexOf(s(3).id))
        assertEquals(0, queue.indexOf("unknown"))
        queue.start()
        assertEquals(FakeShortsFeed.PAGE_SIZE, queue.items.size)
        assertNotNull(queue.next)
        assertEquals(queue.items.size, queue.pageCount, "no last page while more can come")
        assertFalse(queue.wantsMore(0))
        assertTrue(queue.wantsMore(queue.items.size - 1 - VideoMetrics.SHORTS_LOAD_MORE_BEFORE), "three pages before the end")
        queue.more()
        assertEquals(12, queue.items.size)
        assertEquals(queue.items.map { it.id }.distinct(), queue.items.map { it.id })
        assertNull(queue.next)
        assertTrue(queue.ended)
        assertEquals(13, queue.pageCount, "the end page")
        assertEquals(s(7).id, queue.keyAt(0))
        assertEquals(ShortsQueue.END_KEY, queue.keyAt(12))
        assertEquals(listOf("start(Home)", "more(Home)"), feed.calls)
    }

    @Test fun aFailedLoadOffersTryAgainAndRetries() = runTest {
        var fail = true
        val inner = FakeShortsFeed()
        val feed = object : ShortsFeed {
            override suspend fun start(origin: ShortsOrigin, seed: List<VideoItem>): ShortsPage =
                if (fail) throw IllegalStateException("offline") else inner.start(origin, seed)
            override suspend fun more(origin: ShortsOrigin, token: PageToken): ShortsPage = inner.more(origin, token)
        }
        val queue = ShortsQueue(feed, ShortsOrigin.Subscriptions, emptyList())
        queue.start()
        assertTrue(queue.failed)
        assertEquals(1, queue.pageCount, "the failure page with Try again")
        assertFalse(queue.wantsMore(0), "no automatic retry")
        fail = false
        queue.more()
        assertFalse(queue.failed)
        assertEquals(FakeShortsFeed.PAGE_SIZE, queue.items.size)
    }

    // ---- The controller ------------------------------------------------------------------------------------------------------

    private class Fixture {
        val playback = FakeVideoPlayback()
        val library = FakeVideoLibrary()
        val shared = mutableListOf<String>()
        val channels = mutableListOf<String>()
        var backs = 0
        val controller = ShortsController(playback, library, onShare = { shared += it.id }, onOpenChannel = { channels += it.id }, onBack = { backs++ })
    }

    @Test fun aSettledPagePlaysAsAShortAndPrefetchesTheNext() {
        val f = Fixture()
        f.controller.onSettled(s(1).id, s(2).id)
        assertEquals(listOf("play(fakeshort01)", "prefetch(fakeshort02)"), f.playback.calls)
        assertTrue(f.playback.state.value.short, "played as a Short")
        assertEquals(s(1).id, f.controller.settledId)
        f.controller.onSettled(s(1).id, null)
        assertEquals("play(fakeshort01)", f.playback.calls.last(), "the same Short again: the controller keeps it going")
        f.controller.onSettled(null, null)
        assertFalse(f.playback.state.value.playing, "the last page pauses the Short")
    }

    @Test fun comingBackFromTheLastPagePlaysTheShortTheControllerKeptPaused() {
        val fake = FakeVideoPlayback()
        // as the real controller does: a play of the video that is loaded keeps it as it is
        val keeping = object : VideoPlayback by fake {
            override fun play(videoId: String, startMs: Long?, queue: List<VideoItem>, short: Boolean) {
                if (fake.state.value.item?.id != videoId) fake.play(videoId, startMs, queue, short)
            }
        }
        val controller = ShortsController(keeping, FakeVideoLibrary(), {}, {}, {})
        controller.onSettled(s(12).id, null)
        controller.onSettled(null, null)
        assertFalse(fake.state.value.playing, "the last page paused it")
        controller.onSettled(s(12).id, null)
        assertTrue(fake.state.value.playing, "back on the Short: it plays again")
        controller.togglePlay()
        controller.onSettled(s(12).id, s(1).id)
        assertFalse(fake.state.value.playing, "more pages arriving never undo the user's pause")
    }

    @Test fun aTapPausesWithTheGlyphAndPlaysAgain() {
        val f = Fixture()
        f.controller.onSettled(s(1).id, null)
        f.controller.togglePlay()
        assertFalse(f.playback.state.value.playing)
        assertTrue(f.controller.userPaused)
        assertEquals(1, f.controller.glyphSerial, "the glyph comes when pausing")
        f.controller.togglePlay()
        assertTrue(f.playback.state.value.playing)
        assertFalse(f.controller.userPaused)
        assertEquals(1, f.controller.glyphSerial, "not when playing")
        f.controller.togglePlay()
        f.controller.onSettled(s(2).id, null)
        assertFalse(f.controller.userPaused, "a new Short is not paused by the user")
    }

    @Test fun aDoubleTapLikesWithABurstAndTheToastOnceASession() {
        val f = Fixture()
        f.controller.doubleTap(s(4), 120f, 300f)
        assertTrue(f.library.liked.value.any { it.id == s(4).id }, "in Liked videos")
        assertEquals(120f, f.controller.burst?.x)
        assertEquals(1, f.controller.toastSerial)
        f.controller.doubleTap(s(4), 10f, 10f)
        assertTrue(f.library.liked.value.any { it.id == s(4).id }, "a second double tap never unlikes")
        assertEquals(2, f.controller.burst?.serial)
        assertEquals(1, f.controller.toastSerial, "the toast once a session")
        f.controller.burstDone(1)
        assertNotNull(f.controller.burst, "an older burst's end does not clear the newer")
        f.controller.burstDone(2)
        assertNull(f.controller.burst)
        val other = Fixture()
        other.controller.doubleTap(s(5), 0f, 0f)
        assertEquals(0, other.controller.toastSerial, "another pager of the same session shows no toast")
        f.controller.toggleLike(s(4), liked = true)
        assertFalse(f.library.liked.value.any { it.id == s(4).id }, "the like button takes it back")
    }

    @Test fun theMuteIsKeptForTheSessionAndLiftedWhenThePagerLeaves() {
        val f = Fixture()
        f.controller.enter()
        f.controller.onSettled(s(1).id, null)
        f.controller.toggleMute()
        assertTrue(f.playback.state.value.muted)
        assertTrue(ShortsSession.muted)
        f.controller.release()
        assertTrue("stop()" in f.playback.calls, "the Short stops when the pager leaves")
        assertEquals("setMuted(false)", f.playback.calls.last(), "the rest of Video is not muted")
        assertTrue(ShortsSession.muted, "the choice is kept for the session")
        val next = Fixture()
        next.controller.enter()
        assertEquals(listOf("setMuted(true)"), next.playback.calls, "the next pager starts muted")
    }

    @Test fun onlyThePagerThatPlayedLastStopsTheShort() {
        val first = Fixture()
        val second = ShortsController(first.playback, first.library, {}, {}, {})
        first.controller.onSettled(s(1).id, null)
        second.onSettled(s(2).id, null)
        first.controller.release()
        assertFalse("stop()" in first.playback.calls, "a newer pager keeps its Short")
        second.release()
        assertTrue("stop()" in first.playback.calls)
    }

    @Test fun aLongPressHoldsTwiceTheSpeedAndPutsItBack() {
        val f = Fixture()
        f.controller.onSettled(s(1).id, null)
        f.playback.setSpeed(1.25f)
        f.controller.holdFast(true)
        assertEquals(2f, f.playback.state.value.speed)
        assertTrue(f.controller.fast)
        f.controller.holdFast(true)
        f.controller.holdFast(false)
        assertEquals(1.25f, f.playback.state.value.speed)
        f.controller.holdFast(false)
        assertEquals(listOf("setSpeed(1.25)", "setSpeed(2.0)", "setSpeed(1.25)"), f.playback.calls.filter { it.startsWith("setSpeed") }, "a release with no hold does nothing")
    }

    @Test fun theShortPausesInTheBackgroundAndPlaysAgainUnlessPausedByTheUser() {
        val f = Fixture()
        f.controller.onSettled(s(1).id, null)
        f.controller.onForeground(false)
        assertEquals("onUiVisible(false)", f.playback.calls.last())
        f.playback.update { it.copy(playing = false) }       // the controller pauses a Short whose UI left
        f.controller.onForeground(true)
        assertTrue(f.playback.state.value.playing, "played again when the pager comes back")
        f.controller.togglePlay()
        f.controller.onForeground(false)
        f.controller.onForeground(true)
        assertFalse(f.playback.state.value.playing, "a Short the user paused stays paused")
    }

    @Test fun theChipFollowsTheChannelAndTheOtherButtonsGoToTheirHandlers() {
        val f = Fixture()
        f.controller.toggleSubscribe(s(1), subscribed = false)
        assertEquals(listOf(FakeVideoSource.CHANNEL_1), f.library.subscriptions.value.map { it.channelId })
        f.controller.toggleSubscribe(s(1), subscribed = true)
        assertTrue(f.library.subscriptions.value.isEmpty())
        f.controller.share(s(2))
        f.controller.openChannel(s(3))
        f.controller.back()
        assertEquals(listOf(s(2).id), f.shared)
        assertEquals(listOf(s(3).id), f.channels)
        assertEquals(1, f.backs)
        f.controller.toggleExpanded(s(1).id)
        assertEquals(s(1).id, f.controller.expandedId)
        f.controller.toggleExpanded(s(1).id)
        assertNull(f.controller.expandedId)
    }

    // ---- Small pure pieces ----------------------------------------------------------------------------------------------------

    @Test fun aVideoWithinFivePercentOfTheScreensShapeFillsItElseFits() {
        val phone = IntSize(1080, 2340)                                   // 9:19.5
        assertEquals(VideoScale.FIT, shortsScale(9f / 16f, phone), "a 9:16 Short on a tall phone: whole, with bars")
        assertEquals(VideoScale.FILL, shortsScale(1080f / 2340f * 1.04f, phone), "within 5 %")
        assertEquals(VideoScale.FILL, shortsScale(9f / 16f, IntSize(900, 1600)))
        assertEquals(VideoScale.FIT, shortsScale(16f / 9f, phone), "a landscape video in the pager")
        assertEquals(VideoScale.FIT, shortsScale(9f / 16f, IntSize.Zero))
    }

    @Test fun theWheelStepsOncePerGestureAndTheKeysAtMostEvery250Ms() {
        val gate = ShortsStepGate()
        assertTrue(gate.wheel(1_000))
        assertFalse(gate.wheel(1_100))
        assertFalse(gate.wheel(1_300), "the same gesture goes on (events less than 250 ms apart)")
        assertTrue(gate.wheel(1_600), "a pause of more than 250 ms: a new gesture")
        assertTrue(gate.key(5_000))
        assertFalse(gate.key(5_100), "a key's repeat is held back")
        assertTrue(gate.key(5_250))
        assertEquals(250L, ShortsStepGate.QUIET_MS)
    }

    @Test fun theHeartGrowsSettlesAndFades() {
        assertEquals(0.6f, HeartBurstCurve.scale(0f, reduced = false), 1e-4f)
        assertEquals(1.2f, HeartBurstCurve.scale(0.4f, reduced = false), 1e-4f)
        assertEquals(1f, HeartBurstCurve.scale(0.6f, reduced = false), 1e-4f)
        assertEquals(1f, HeartBurstCurve.scale(1f, reduced = false), 1e-4f)
        assertEquals(0f, HeartBurstCurve.alpha(0f, reduced = false), 1e-4f)
        assertEquals(1f, HeartBurstCurve.alpha(0.3f, reduced = false), 1e-4f)
        assertEquals(0f, HeartBurstCurve.alpha(1f, reduced = false), 1e-4f)
        assertEquals(1f, HeartBurstCurve.scale(0.2f, reduced = true), "reduced motion: no scale")
        assertEquals(0.5f, HeartBurstCurve.alpha(0.5f, reduced = true), 1e-4f)
    }

    @Test fun theGridHasTheSpecsColumnsAndCards() {
        val phone = ShortsGridLayout.of(360f, wide = false)
        assertEquals(3, phone.columns)
        assertTrue(abs(phone.cardWidthDp(360f - 2 * 16f) - 106.67f) < 0.01f, "106 x 188 at 360 dp")
        assertEquals(16f, VideoMetrics.thumbRadiusDp(phone.cardWidthDp(328f)), "radius 16 on the phone's grid")
        assertEquals(18f, VideoMetrics.thumbRadiusDp(VideoMetrics.SHORTS_SHELF_W_DP), "radius 18 on a shelf")
        val desk = ShortsGridLayout.of(1280f, wide = true)
        assertEquals(VideoMetrics.shortsGridColumns(1280f), desk.columns)
        assertEquals(12f, desk.gapDp)
        assertEquals(28f, desk.sideDp)
        assertEquals(3, ShortsGridLayout.of(600f, wide = true).columns, "a narrow wide page keeps three columns")
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first (the shell test's reading). */
    private fun enclosingHeads(code: String, index: Int): List<String> {
        val heads = ArrayList<String>()
        var depth = 0
        var i = index - 1
        while (i >= 0) {
            when (code[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else heads += code.substring(maxOf(0, i - 120), i)
            }
            i--
        }
        return heads
    }

    private val drawHead = Regex("""(drawBehind|drawWithCache|drawWithContent|onDrawBehind|onDrawWithContent|graphicsLayer|Canvas)\s*(\([^)]*\))?\s*$""")
}
