package com.cosmotune.video.ui

import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.video.ui.home.HomeCache
import com.cosmotune.video.ui.home.HomeModel
import com.cosmotune.video.ui.home.KioskPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home of plan task Y-17: what the screen is made of, read from the source (there is no window and no Compose test harness here),
 * and what its data holder does, run against the fakes. The planner has its own test in `:shared`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStructureTest {
    private val dir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/home"
    private val names = listOf("VideoHome", "HomeShelves", "HomeKiosk", "HomeAccountFeed")
    private fun code(name: String) = VideoSourceContract.code(VideoSourceContract.read("$dir/$name.kt"))
    private val all get() = names.associateWith { code(it) }
    private val screen get() = code("VideoHome")
    private val shelves get() = code("HomeShelves")
    private val navHost get() = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt")

    // ---- The seams --------------------------------------------------------------------------------------------------------

    @Test fun theNavHostBranchIsTheOneLineOfThisTask() {
        val lines = navHost.lines()
        val branch = "                is VideoRoute.Home -> com.cosmotune.video.ui.home.VideoHome(contentPadding, wide)"
        assertEquals(1, lines.count { it == branch }, "the Home branch calls VideoHome with the host's padding and width")
        assertFalse("import com.cosmotune.video.ui.home" in navHost, "a fully qualified name, no new import line")
        assertFalse(lines.any { "VideoRoute.Home" in it && "VideoPlaceholder" in it }, "Home is no placeholder any more")
        // the other branches stay on their own lines with a blank line between (their owners replace them, not this task)
        val branches = lines.indices.filter { lines[it].trim().startsWith("is VideoRoute.") }
        assertEquals(19, branches.size)
        for (i in branches.dropLast(1)) assertTrue(lines[i + 1].isBlank(), "a blank line follows ${lines[i].trim()}")
        assertTrue("com.cosmotune.video.ui.actions.VideoActionsHost(contentPadding)" in navHost, "Y-16's action host stays")
    }

    @Test fun theScreenHasThePlansSignature() {
        val params = VideoSourceContract.parameters(VideoSourceContract.read("$dir/VideoHome.kt"), "VideoHome")
        assertEquals(listOf("contentPadding: PaddingValues", "wide: Boolean"), params)
        assertTrue(Regex("""(?m)^@Composable\s*\nfun VideoHome\(""").containsMatchIn(screen), "a public composable")
    }

    @Test fun thePlannerHasThePlansSignatures() {
        val src = VideoSourceContract.read("shared/src/commonMain/kotlin/com/cosmotune/video/home/HomePlanner.kt")
        assertEquals(listOf("history: List<HistoryEntry>"), VideoSourceContract.parameters(src, "continueWatching"))
        assertEquals(
            listOf("lastWatched: List<HistoryEntry>", "related: Map<String, List<ListItem>>", "history: List<HistoryEntry>"),   // Y-34: Shorts always left out, no switch
            VideoSourceContract.parameters(src, "forYou"),
        )
        assertEquals(
            listOf("feedShorts: List<VideoItem>", "relatedShorts: List<VideoItem>", "kioskShorts: List<VideoItem>"),
            VideoSourceContract.parameters(src, "shorts"),
        )
    }

    // ---- The screen -------------------------------------------------------------------------------------------------------

    @Test fun theTrendingKioskIsNeverNamedOrRequestedById() {
        for ((name, text) in all) assertFalse("trending" in text.lowercase(), "$name names a kiosk: the source offers the kiosks, by its own ids")
        assertTrue("source.kiosks()" in code("HomeKiosk") && "source.kiosk(id, null)" in code("HomeKiosk") && "source.kiosk(id, token)" in code("HomeKiosk"))
        assertTrue("graph.settings.update { it.copy(homeKiosk = kiosk.id) }" in screen, "the choice is remembered in the settings")
    }

    @Test fun everyListAndEveryRowOfTheListHasAKey() {
        var seen = 0
        for ((name, text) in all) {
            for (match in Regex("""\b(items|itemsIndexed|item)\(""").findAll(text)) {
                if (match.range.first > 0 && (text[match.range.first - 1].isLetterOrDigit() || text[match.range.first - 1] == '_')) continue
                seen++
                assertTrue("key =" in argumentsOf(text, match.range.last), "$name: ${match.value} has no key")
            }
        }
        assertTrue(seen >= 9, "found the lazy items (found $seen)")
        assertTrue("uniqueKeys(feed.items)" in screen && "uniqueKeys(items)" in shelves, "a repeat of a video in a list cannot crash it")
    }

    @Test fun homeIsOneFeedWithTheContinueShelfAndTheShortsShelfStandingInIt() {
        assertTrue("HomePlanner.stream(accountItems, subscriptionsShelf, forYou, kioskVideos)" in screen, "All is the planner's mixed feed")
        assertTrue("private fun HomeFeedPhone(" in screen && "private fun HomeFeedWide(" in screen, "a lazy column on a phone, a lazy grid on a wide page")
        assertTrue("LazyColumn(" in screen && "LazyVerticalGrid(" in screen && "GridCells.Fixed(columns)" in screen)
        assertTrue("stickyHeader(key = \"chips\"" in screen, "the chip row sticks to the top of the phone's list")
        assertTrue("Res.string.video_shelf_continue" in screen && "Res.string.video_shorts" in screen)
        assertTrue("HomePlanner.continueWatching(history)" in screen && "HomePlanner.subscriptionsShelf(feedItems)" in screen)
        assertTrue("HomePlanner.forYou(recent, related, history)" in screen && "HomePlanner.shorts(feedItems, fromRelated + accountItems.filter(ShortsFilter::isShort), model.items)" in screen)
        assertTrue("source.related(id)" in code("HomeKiosk"), "the feed's related videos are the related lists of the videos watched (with the source's second way to them)")
        assertTrue("watchedFraction(entry.positionMs, entry.durationMs)" in screen && "watched = continueWatched" in screen, "Continue watching has the watched bars")
        assertTrue("minOf(HomePlanner.SHORTS_AFTER - 1, feed.items.lastIndex)" in screen, "the Shorts shelf stands after the fourth card on a phone")
        assertTrue("ceil(HomePlanner.SHORTS_AFTER / columns.toDouble()).toInt() * columns" in screen, "and after the first whole row of the grid")
        assertTrue("hasContinue = all && continueItems.isNotEmpty()" in screen, "Continue watching only while some video is part-watched")
        assertFalse("HomeChannelsShelf" in screen || "video_home_channels" in screen, "the channels you follow are the Subscriptions page's")
    }

    @Test fun theAccountsHomeIsTheFeedWhileSignedInAndTheLocalFeedOtherwise() {
        assertTrue("val signedIn = graph.account.state.collectAsState().value is AccountState.SignedIn" in screen)
        assertTrue("AccountHomeFeed(graph.account, scope)" in screen)
        val feed = VideoSourceContract.code(VideoSourceContract.read("$dir/HomeAccountFeed.kt"))
        assertTrue("account.home(null).getOrNull()" in feed && "account.home(token).getOrNull()" in feed, "the first page, then the next at the end of the list")
        assertTrue("if (!loaded || loadingMore) return" in feed, "one page at a time")
        assertTrue("val loadMore: () -> Unit = { if (accountIsFeed) accountFeed?.loadMore() else model.loadMore() }" in screen)
    }

    @Test fun theShelvesNeverWaitForTheKiosk() {
        val phase = screen.substringAfter("val phase = when {").substringBefore("val loadingMore")
        assertTrue("stream.isNotEmpty() -> KioskPhase.READY" in phase, "videos from the device or the account are shown while the kiosk is still coming")
        assertTrue("HomeModel(graph.source, scope).also { it.restore(regionKey, settings.homeKiosk, recentIds) }" in screen, "the first frame holds what the cache has")
        assertTrue("LaunchedEffect(graph, followsChannels) { if (followsChannels) refreshFeed(graph.feed) }" in screen)
        assertTrue("feed.refresh(force = false)" in screen, "Home asks the feed to catch up, not forced: the feed limits it itself")
    }

    @Test fun theShortsChipAndShelfOpenThePagerAndHideWithTheSwitch() {
        assertTrue("val openShorts = { navigator.push(VideoRoute.Shorts(ShortsOrigin.Home)) }" in screen)
        assertTrue("onShorts = openShorts" in screen && "onSeeAll = openShorts" in screen, "the chip and See all open the same route")
        assertTrue("navigator.openShort(it, ShortsOrigin.Home, shorts)" in screen, "a card opens the pager at it with the shelf's Shorts")
        assertTrue("showShorts = !hideShorts" in screen && "if (hideShorts) {\n            emptyList()" in screen, "the chip and the shelf go with Hide Shorts")
        assertTrue("model.items.filterNot(ShortsFilter::isShort)" in screen, "the feed holds no Shorts: they stand in their shelf")
        assertTrue("Res.string.video_shorts" in shelves && "if (showShorts) add(shortsLabel)" in shelves, "the Shorts chip stands after All, before the kiosks")
    }

    @Test fun theChipRowIsAllShortsAndTheKiosksInPillChips() {
        val chips = shelves.substringAfter("internal fun HomeChips(").substringBefore("/** Lays the content out")
        assertTrue("Res.string.video_all" in chips && "VideoChips(" in chips, "All first, in the pill chips of the feeds (48 dp target, focus ring, inverted when chosen)")
        assertTrue("index == 0 -> onAll()" in chips && "showShorts && index == 1 -> onShorts()" in chips, "All and Shorts are not kiosks")
        assertTrue("trailing = if (kiosks == null)" in chips && "CosmoShimmer(" in chips, "placeholders while the kiosks are on the way")
        val pill = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/blocks/PillChip.kt"))
        assertTrue(".minimumInteractiveComponentSize()" in pill && "role = Role.Tab" in pill && ".cosmoFocusRing(interaction, PILL_HEIGHT / 2)" in pill)
    }

    @Test fun theKioskStatesAreThreeSkeletonsTheEmptyStateAndTheCards() {
        assertTrue("private const val SKELETON_CARDS = 3" in screen)
        assertTrue("KioskPhase.LOADING ->" in screen && "KioskPhase.FAILED ->" in screen && "KioskPhase.READY ->" in screen)
        assertTrue("CosmoEmptyState(message = errorText, actionLabel = retryText, onAction = onRetry)" in screen)
        assertTrue("Res.string.video_list_error" in screen && "Res.string.video_retry" in screen)
        assertTrue("titleError, titleRetry, model::retry" in screen, "Try again asks the model again")
    }

    @Test fun theKioskLoadsMoreThreeCardsBeforeTheEnd() {
        assertTrue("private const val LOAD_AHEAD_CARDS = 3" in screen)
        assertTrue("lastVisible >= total - LOAD_AHEAD_CARDS" in screen && "onLoadMore()" in screen)
        assertTrue("lastVisible >= total - columns * 2" in screen, "a wide page counts rows: two rows ahead")
        assertTrue("model.loadMore()" in screen)
    }

    @Test fun theWidePageIsAGridThatFillsItsWidth() {
        assertTrue("homeColumns(maxWidth)" in screen && "GridCells.Fixed(columns)" in screen)
        assertTrue("private const val CARD_MIN_DP = 320f" in screen && "private const val MIN_COLUMNS = 2" in screen && "private const val MAX_COLUMNS = 5" in screen)
        assertTrue("VideoMetrics.DESKTOP_MARGIN_DP" in screen && "VideoMetrics.GRID_COL_GAP_DP" in screen)
        assertTrue("VideoCardBody(" in screen, "the cards of the grid: a 12 dp picture, the channel's picture, the title in two lines")
        assertTrue("private val SHELF_CARD_WIDTH = 200.dp" in shelves, "the compact Continue watching shelf")
    }

    @Test fun thereIsNoGreetingAndNoHeadingAboveTheChips() {
        for ((name, text) in all) {
            assertFalse("cosmo_home_" in text && "SharedRes.string" in text, "$name draws no greeting")
            assertFalse("CosmoPageHeader" in text || "HomeHeader" in text || "video_home_overline" in text, "$name draws no heading")
        }
    }

    @Test fun homeColumnsFillTheWidthFromTwoToFive() {
        fun columns(width: Float) = com.cosmotune.video.ui.home.homeColumns(androidx.compose.ui.unit.Dp(width))
        assertEquals(2, columns(840f))
        assertEquals(3, columns(1060f))
        assertEquals(4, columns(1420f))
        assertEquals(5, columns(1800f))
        assertEquals(5, columns(3000f), "never more than five")
    }

    @Test fun theNearBlackGroundIsVideosOwn() {
        val ground = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/blocks/VideoGround.kt"))
        assertTrue("Color(0xFF0F0F0F)" in ground && "if (colors.dark) VIDEO_DARK_GROUND else colors.live(lightField)" in ground, "near-black in the dark theme, the palette's ground in the light one")
        assertTrue("videoSolidGround()" in screen, "the chip row that sticks has the ground under it")
    }

    @Test fun titlesAndPicturesGoThroughDeArrowInsideEveryCard() {
        assertTrue("rememberVideoTitle(item)" in shelves && "rememberVideoThumbnail(item)" in shelves)
        assertEquals(1, Regex("""item = rememberBranded\(item\)""").findAll(shelves).count(), "the shelf's cards")
        assertEquals(1, Regex("""item = rememberBranded\(item\)""").findAll(screen).count(), "the phone's cards")
        assertEquals(1, Regex("""val shown = rememberBranded\(item\)""").findAll(screen).count(), "the wide page's cells")
        for ((name, text) in all) assertFalse("graph.branding" in text || "DeArrow" in text.replace("DeArrow's", ""), "$name: the screens never call the DeArrow client")
    }

    @Test fun aTapPlaysThroughTheHandlerAndMoreOpensTheSheet() {
        assertTrue("actions.play(" in screen && "actions.handle(held.item, action, VideoSheetContext.DEFAULT, list = held.list)" in screen)
        assertTrue("VideoActionsSheet(" in screen && "sheet = HomeSheet(" in screen)
        assertFalse("playback.play(" in screen, "no page plays a video by itself")
        assertTrue("downloaded = held.item.id in downloaded" in screen && "rememberDownloadedIds()" in screen)
    }

    @Test fun theLivePaletteIsReadOnlyInDrawLambdas() {
        var seen = 0
        for ((name, text) in all) {
            var from = 0
            while (true) {
                val at = text.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) continue
                seen++
                val inDraw = enclosingHeads(text, at).any { drawHead.containsMatchIn(it) }
                assertTrue(inDraw, "$name: live( at line ${text.take(at).count { it == '\n' } + 1} is not in a draw lambda")
            }
        }
        assertTrue(seen >= 0)
    }

    @Test fun nothingAllocatesInADrawLambdaAndNothingRemembersABrush() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\()""")
        for ((name, text) in all) {
            assertNull(remembering.find(text), "$name remembers a brush or a live colour")
            var from = 0
            while (true) {
                val at = text.indexOf("onDrawBehind {", from)
                if (at < 0) break
                from = at + 1
                var depth = 0
                var end = at
                for (i in at until text.length) {
                    if (text[i] == '{') depth++
                    if (text[i] == '}' && --depth == 0) { end = i; break }
                }
                val body = text.substring(at, end)
                assertFalse(Regex("""Brush\.|Path\(|Stroke\(|listOf\(|mutableListOf|\.toList\(\)|\.map \{""").containsMatchIn(body), "$name: a draw lambda builds something per frame:\n$body")
            }
        }
    }

    @Test fun noColourIsPickedByHandNoPositionIsReadAndNoSourceNameAppears() {
        for ((name, text) in all) {
            assertFalse(Regex("""Color\(0x|Color\.(White|Black|Red|Green|Blue|Gray|Transparent|Yellow)|Color\([0-9]""").containsMatchIn(text), "$name picks a colour by hand")
            assertFalse("positionMs()" in text, "$name reads the player's position")
            assertFalse(Regex("""(?i)youtube|\bYT\b|schabi|newpipe|cookie""").containsMatchIn(text), "$name names the source, the extractor or a cookie")
            assertFalse("LocalVideoGraph.current.playback" in text || "graph.playback" in text, "$name has no business with the player")
        }
    }

    @Test fun noTextIsALiteralAndEveryImportFromMusicIsAllowed() {
        for ((name, text) in all) {
            for (match in Regex("""\bText\(""").findAll(text)) {
                val args = argumentsOf(text, match.range.last)
                assertTrue(args.trimStart().startsWith("text = "), "$name: Text( takes its text by name")
                assertFalse(Regex("""text\s*=\s*"""").containsMatchIn(args), "$name: a literal text")
            }
            assertFalse(Regex("""contentDescription\s*=\s*"""").containsMatchIn(text), "$name: a literal description")
            for (import in Regex("""import com\.cosmotune\.music\.[\w.]+""").findAll(text).map { it.value }) {
                assertTrue(
                    import in listOf(
                        "import com.cosmotune.music.ui.components.LocalShelfRowChrome",
                        "import com.cosmotune.music.ui.haptics.Haptic",
                        "import com.cosmotune.music.ui.haptics.rememberHaptics",
                    ),
                    "$name imports $import from Music",
                )
            }
        }
        val strings = VideoSourceContract.read("video/src/commonMain/composeResources/values/strings.xml")
        for ((name, text) in all) {
            for (key in Regex("""Res\.string\.(video_\w+)""").findAll(text).map { it.groupValues[1] }.toSet()) {
                assertTrue("<string name=\"$key\">" in strings, "$name uses $key, which strings.xml declares")
            }
        }
    }

    // ---- HomeModel, run against the fakes ---------------------------------------------------------------------------------

    /** The fake source with the kiosk and the details calls under the test's hand. */
    private class ScriptedSource(val inner: FakeVideoSource = FakeVideoSource()) : VideoSource by inner {
        var kioskScript: (suspend (String, PageToken?) -> Page<VideoItem>)? = null
        val kioskCalls = mutableListOf<String>()

        override suspend fun kiosk(id: String, page: PageToken?): Page<VideoItem> {
            kioskCalls += "$id@${page?.value}"
            return kioskScript?.invoke(id, page) ?: inner.kiosk(id, page)
        }
    }

    private fun clockOf(now: LongArray): () -> Long = { now[0] }

    @Test fun startOffersTheSourcesKiosksAndShowsThePreferredOne() = runTest {
        val source = FakeVideoSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        assertEquals(KioskPhase.LOADING, model.phase)
        assertNull(model.kiosks)
        model.start("|", "gaming")
        runCurrent()
        assertEquals(FakeVideoSource.KIOSKS, model.kiosks)
        assertEquals("gaming", model.selectedId)
        assertEquals(KioskPhase.READY, model.phase)
        assertEquals(FakeVideoSource.KIOSK_PAGE_SIZE, model.items.size)
        assertEquals(listOf("kiosks()", "kiosk(gaming)"), source.calls)
    }

    @Test fun anUnknownChoiceFallsBackToTheFirstKioskNeverToTrending() = runTest {
        val source = FakeVideoSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        model.start("|", "trending")
        runCurrent()
        assertEquals("music", model.selectedId)
        assertEquals(listOf("kiosks()", "kiosk(music)"), source.calls)
        for (kiosk in FakeVideoSource.KIOSKS) {
            model.select(kiosk.id)
            runCurrent()
        }
        model.loadMore()
        runCurrent()
        assertTrue(source.calls.filter { it.startsWith("kiosk(") }.none { "trending" in it.lowercase() }, "a kiosk is only ever requested by an id the source offered")
        assertTrue(source.calls.filter { it.startsWith("kiosk(") }.all { call -> FakeVideoSource.KIOSKS.any { call == "kiosk(${it.id})" } })
    }

    @Test fun loadMoreAppendsThePagesWithoutRepeatsAndStopsAtTheEnd() = runTest {
        val source = ScriptedSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        val video = FakeVideoSource.videos
        source.kioskScript = { _, page ->
            when (page?.value) {
                null -> Page(video.subList(0, 3), PageToken("p2"))
                "p2" -> Page(video.subList(2, 6), PageToken("p3"))   // video 3 comes again
                else -> Page(video.subList(6, 8), null)
            }
        }
        model.start("|", "music")
        runCurrent()
        assertEquals(video.subList(0, 3).map { it.id }, model.items.map { it.id })
        model.loadMore()
        assertTrue(model.loadingMore, "a card skeleton stands at the end while the page is on its way")
        model.loadMore()   // asked again while loading: no second request
        runCurrent()
        assertFalse(model.loadingMore)
        assertEquals(video.subList(0, 6).map { it.id }, model.items.map { it.id }, "the repeated video is shown once, in place")
        model.loadMore()
        runCurrent()
        assertEquals(video.subList(0, 8).map { it.id }, model.items.map { it.id })
        model.loadMore()
        runCurrent()
        assertEquals(listOf("music@null", "music@p2", "music@p3"), source.kioskCalls, "nothing is asked for after the last page")
    }

    @Test fun aFailedPageKeepsTheTokenForTheNextTry() = runTest {
        val source = ScriptedSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        var fail = false
        source.kioskScript = { _, page ->
            if (page != null && fail) throw VideoSourceError.Network("down", null)
            if (page == null) Page(FakeVideoSource.videos.take(3), PageToken("p2")) else Page(FakeVideoSource.videos.drop(3).take(2), null)
        }
        model.start("|", "music")
        runCurrent()
        fail = true
        model.loadMore()
        runCurrent()
        assertEquals(3, model.items.size, "the cards stay")
        assertEquals(KioskPhase.READY, model.phase)
        assertFalse(model.loadingMore)
        fail = false
        model.loadMore()
        runCurrent()
        assertEquals(5, model.items.size, "the next scroll tries the same page again")
    }

    @Test fun anAnswerForAKioskTheUserLeftIsDropped() = runTest {
        val source = ScriptedSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        val gate = CompletableDeferred<Unit>()
        source.kioskScript = { id, page ->
            if (id == "music" && page == null && !gate.isCompleted) gate.await()
            Page(listOf(FakeVideoSource.videos[if (id == "music") 0 else 1]), null)
        }
        model.start("|", "music")
        runCurrent()
        assertEquals(KioskPhase.LOADING, model.phase)
        model.select("gaming")
        runCurrent()
        assertEquals(listOf("fakevideo02"), model.items.map { it.id })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("gaming", model.selectedId)
        assertEquals(listOf("fakevideo02"), model.items.map { it.id }, "the late answer of the first kiosk does not replace the cards")
    }

    @Test fun aFailureShowsTheErrorStateAndTryAgainRecovers() = runTest {
        val source = FakeVideoSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        source.failNext(VideoSourceError.Network("down", null))
        model.start("|", "music")
        runCurrent()
        assertEquals(KioskPhase.FAILED, model.phase, "the kiosks did not come")
        assertNull(model.kiosks)
        model.retry()
        runCurrent()
        assertEquals(KioskPhase.READY, model.phase)
        assertEquals("music", model.selectedId)
        // a kiosk page that fails: the chips are there, the cards are not
        val scripted = ScriptedSource()
        val second = HomeModel(scripted, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        var failures = 1
        scripted.kioskScript = { id, page -> if (failures-- > 0) throw VideoSourceError.Parsing("changed", null) else scripted.inner.kiosk(id, page) }
        second.start("|", "movies")
        runCurrent()
        assertEquals(KioskPhase.FAILED, second.phase)
        assertEquals(FakeVideoSource.KIOSKS, second.kiosks, "the chips stay")
        second.retry()
        runCurrent()
        assertEquals(KioskPhase.READY, second.phase)
        assertEquals("movies", second.selectedId)
    }

    @Test fun anEmptyFirstPageIsTheEmptyStateToo() = runTest {
        val source = ScriptedSource()
        source.kioskScript = { _, _ -> Page(emptyList(), null) }
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        model.start("|", "live")
        runCurrent()
        assertEquals(KioskPhase.FAILED, model.phase)
    }

    @Test fun theCacheShowsAKioskAtOnceAndForgetsItAfterTenMinutesOrARegionChange() = runTest {
        val now = longArrayOf(1_000)
        val cache = HomeCache()
        val source = FakeVideoSource()
        val first = HomeModel(source, backgroundScope, cache, clockOf(now))
        first.start("IN|ta", "music")
        runCurrent()
        first.loadMore()
        runCurrent()
        val calls = source.calls.size
        assertEquals(12, first.items.size)

        // coming back from a video: a new model over the same cache shows the list at once, without a call
        val back = HomeModel(source, backgroundScope, cache, clockOf(now))
        back.start("IN|ta", "music")
        assertEquals(KioskPhase.READY, back.phase)
        assertEquals(12, back.items.size, "every page that was loaded")
        assertEquals(FakeVideoSource.KIOSKS, back.kiosks)
        assertEquals(calls, source.calls.size)

        // the other kiosk, and back: the first one is still there
        back.select("gaming")
        runCurrent()
        val afterGaming = source.calls.size
        back.select("music")
        assertEquals(12, back.items.size)
        assertEquals(afterGaming, source.calls.size)

        // ten minutes on, the first page is asked for again
        now[0] += HomeCache.PAGES_TTL_MS
        back.select("gaming")
        assertEquals(KioskPhase.LOADING, back.phase)
        runCurrent()
        assertEquals(afterGaming + 1, source.calls.size)

        // another region: everything is forgotten, the kiosks too
        back.start("DE|de", "music")
        assertEquals(KioskPhase.LOADING, back.phase)
        assertNull(back.kiosks)
        runCurrent()
        assertEquals(KioskPhase.READY, back.phase)
        assertEquals(2, source.calls.count { it == "kiosks()" }, "once for the launch, once more for the other region")
    }

    @Test fun restoreFillsTheStateFromTheCacheInTheFirstFrameWithoutStartingAnything() = runTest {
        val now = longArrayOf(1_000)
        val cache = HomeCache()
        val source = FakeVideoSource()
        val ids = listOf("fakevideo01", "fakevideo02")
        val first = HomeModel(source, backgroundScope, cache, clockOf(now))
        first.start("IN|ta", "gaming")
        first.loadRelated(ids)
        runCurrent()
        val calls = source.calls.size

        val back = HomeModel(source, backgroundScope, cache, clockOf(now))
        back.restore("IN|ta", "gaming", ids)
        assertEquals(KioskPhase.READY, back.phase)
        assertEquals("gaming", back.selectedId)
        assertEquals(first.items, back.items)
        assertEquals(FakeVideoSource.KIOSKS, back.kiosks)
        assertEquals(ids, back.related.keys.toList())
        runCurrent()
        assertEquals(calls, source.calls.size, "restoring asks for nothing")

        // nothing cached, another region, or pages older than ten minutes: restore leaves the placeholders
        val empty = HomeModel(source, backgroundScope, HomeCache(), clockOf(now))
        empty.restore("IN|ta", "gaming", ids)
        assertEquals(KioskPhase.LOADING, empty.phase)
        assertNull(empty.kiosks)
        assertTrue(empty.related.isEmpty())
        val later = longArrayOf(1_000 + HomeCache.PAGES_TTL_MS)
        val stale = HomeModel(source, backgroundScope, cache, clockOf(later))
        stale.restore("IN|ta", "gaming", ids)
        assertEquals(KioskPhase.LOADING, stale.phase, "pages are shown again for ten minutes")
        val region = HomeModel(source, backgroundScope, cache, clockOf(now))
        region.restore("DE|de", "gaming", ids)
        assertEquals(KioskPhase.LOADING, region.phase, "another region has other charts")
        assertTrue(region.related.isEmpty())
    }

    @Test fun relatedListsAreFetchedOncePerLaunchAndAFailureWaitsTenMinutes() = runTest {
        val now = longArrayOf(1_000)
        val cache = HomeCache()
        val source = FakeVideoSource()
        val model = HomeModel(source, backgroundScope, cache, clockOf(now))
        val ids = listOf("fakevideo01", "fakevideo02", "fakevideo03")
        model.loadRelated(ids)
        assertTrue(model.related.isEmpty(), "nothing is known before the answers")
        runCurrent()
        assertEquals(ids, model.related.keys.toList())
        assertTrue(model.related.values.all { list -> list.isNotEmpty() && list.all { it is ListItem.Video } })
        assertEquals(ids.map { "details($it)" }, source.calls, "one call for each video, details only")

        // again, in a new model over the same cache: known at once, no call
        val again = HomeModel(source, backgroundScope, cache, clockOf(now))
        again.loadRelated(ids)
        assertEquals(ids, again.related.keys.toList())
        runCurrent()
        assertEquals(3, source.calls.size)

        // a failure: that video has no list, and is not asked for again at once
        val fresh = HomeModel(source, backgroundScope, HomeCache(), clockOf(now))
        source.failNext(VideoSourceError.Network("down", null))
        fresh.loadRelated(listOf("fakevideo04", "fakevideo05"))
        runCurrent()
        assertEquals(listOf("fakevideo05"), fresh.related.keys.toList())
        val before = source.calls.size
        fresh.loadRelated(listOf("fakevideo04", "fakevideo05"))
        runCurrent()
        assertEquals(before, source.calls.size, "the failed video waits")
        now[0] += HomeCache.RELATED_RETRY_MS
        fresh.loadRelated(listOf("fakevideo04", "fakevideo05"))
        runCurrent()
        assertEquals(listOf("fakevideo04", "fakevideo05"), fresh.related.keys.toList())
        assertEquals(before + 1, source.calls.size, "only the failed one was asked for")
    }

    @Test fun theKioskIsTheOnlyThingTheSourceIsAskedForAtStart() = runTest {
        val source = FakeVideoSource()
        val model = HomeModel(source, backgroundScope, HomeCache(), clockOf(longArrayOf(1_000)))
        model.start("|", "podcasts")
        runCurrent()
        assertEquals(setOf("kiosks", "kiosk"), source.calls.map { it.substringBefore("(") }.toSet())
        assertEquals(Kiosk("podcasts", "Podcasts"), model.kiosks?.first { it.id == model.selectedId })
    }

    // ---- Reading the source -----------------------------------------------------------------------------------------------

    /** The text before the `{` of every block that encloses [index], innermost first. */
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

    /** The text inside the parentheses that open at [open] in [code]. */
    private fun argumentsOf(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        error("the parenthesis at $open is never closed")
    }
}
