package com.cosmotune.video.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.cosmotune.video.graph.HostLinksRelay
import com.cosmotune.video.graph.HostMusicBridge
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.shell.TopBarScroll
import com.cosmotune.video.ui.shell.VideoCapsuleModel
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.shell.toAccent
import com.cosmotune.video.ui.shell.toCapsule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shell of Video mode (plan task Y-14, step 6): what no render can show, read from the source, and the small pieces of logic
 * the shell is made of, run.
 *
 * Several tests pin the shape of lines that later tasks replace (the graph's slots, the nav host's branches, the two marker lines of
 * the phone shell). They hold the shape for as long as the line is what Y-14 wrote and let it go once the task that owns it has
 * replaced it: a replaced slot or branch only has to keep its place (its own line, a blank line around it).
 */
class ShellStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private val shellDir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell"
    private val desktopDir = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/desktop"
    private fun shell(name: String) = read("$shellDir/$name.kt")
    private fun desktop(name: String) = read("$desktopDir/$name.kt")
    private val app get() = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/CosmoVideoApp.kt")
    private val androidFactory get() = read("video/src/androidMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.android.kt")
    private val desktopFactory get() = read("video/src/jvmMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.jvm.kt")
    private val routeSource get() = read("shared/src/commonMain/kotlin/com/cosmotune/video/nav/VideoRoute.kt")

    // ---- The graph ------------------------------------------------------------------------------------------------------

    private val slots = listOf(
        "source", "library", "settings", "segments", "branding", "votes", "feed", "downloads", "playback", "surface", "music", "metered", "shorts",
    )

    private val fakes = mapOf(
        "source" to "FakeVideoSource()", "library" to "FakeVideoLibrary()", "settings" to "FakeVideoSettingsRepository()",
        "segments" to "FakeSegmentProvider()", "branding" to "FakeBrandingProvider()", "votes" to "FakeVotesProvider()",
        "feed" to "FakeSubscriptionFeed()", "downloads" to "FakeDownloadController()", "playback" to "FakeVideoPlayback()",
        "surface" to "null", "music" to "HostMusicBridge(relay.links)", "metered" to "{ false }", "shorts" to "FakeShortsFeed()",
    )

    @Test fun theGraphHoldsThirteenSlotsInTheBriefsOrder() {
        val src = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/graph/VideoGraph.kt")
        val header = src.substringAfter("class VideoGraph(").substringBefore("\n)")
        val declared = Regex("""val (\w+):""").findAll(header).map { it.groupValues[1] }.toList()
        assertEquals(slots, declared)
        assertTrue("val LocalVideoGraph = staticCompositionLocalOf<VideoGraph>" in src)
    }

    @Test fun theFactoryIsOneExpectWithAnActualOnEachPlatform() {
        val expect = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.kt")
        assertTrue(Regex("""@Composable\s+expect fun rememberVideoGraph\(links: VideoHostLinks\): VideoGraph""").containsMatchIn(expect))
        assertTrue("actual fun rememberVideoGraph(links: VideoHostLinks): VideoGraph" in androidFactory)
        assertTrue("actual fun rememberVideoGraph(links: VideoHostLinks): VideoGraph" in desktopFactory)
    }

    @Test fun eachFactoryHasOneSlotPerLineSeparatedByBlankLinesAndMarkedForItsTask() {
        for ((name, text) in listOf("android" to androidFactory, "desktop" to desktopFactory)) {
            val lines = text.lines()
            for ((index, slot) in slots.withIndex()) {
                val at = lines.indices.filter { lines[it].trim().startsWith("val $slot = ") }
                assertEquals(1, at.size, "$name: the slot `$slot` is declared once, on its own line")
                val line = lines[at.single()].trim()
                val body = line.substringAfter("val $slot = ")
                // a slot a later task has replaced only has to stay where it is
                if (body.startsWith(fakes.getValue(slot))) {
                    val owner = if (slot == "shorts") "Y-31" else "Y-16"
                    assertTrue("// $owner replaces this line" in line, "$name: `$slot` is marked for $owner: $line")
                }
                if (index < slots.lastIndex) assertTrue(lines[at.single() + 1].isBlank(), "$name: a blank line follows the slot `$slot`")
                else assertTrue(lines[at.single() + 1].isBlank(), "$name: a blank line follows the last slot too")
            }
            assertTrue(Regex("""return VideoGraph\(""").containsMatchIn(text), "$name: the graph is returned from one constructor call")
            for (slot in slots) assertTrue(Regex("""\b$slot = $slot\b""").containsMatchIn(text.substringAfter("return VideoGraph(")), "$name: `$slot = $slot` is passed by name")
        }
    }

    @Test fun theGraphIsBuiltOncePerProcess() {
        assertTrue("val graph: VideoGraph by lazy { build() }" in desktopFactory, "the desktop's graph is a lazy singleton")
        assertTrue("built ?: build(application).also { built = it }" in androidFactory, "the phone's graph is built once from the application context")
        assertTrue("@Synchronized" in androidFactory)
        assertTrue("LocalContext.current.applicationContext" in androidFactory, "built from the application context, never an activity")
        // the host's links reach the graph through the relay, so the graph does not hold an activity after its screen has left
        for (text in listOf(androidFactory, desktopFactory)) assertTrue("AttachHostLinks(" in text && "HostMusicBridge(relay.links)" in text)
    }

    @Test fun theRelayForwardsToTheLinksAttachedNowAndToNothingAfterTheyLeave() {
        val relay = HostLinksRelay()
        val log = mutableListOf<String>()
        val host = VideoHostLinks(
            switchToMusic = { log += "switch" },
            playAsSong = { id, title, channel, thumb -> log += "song $id|$title|$channel|$thumb" },
            pauseMusic = { log += "pause" },
            pendingVideo = { PendingVideo("v1", 5L) },
            onPendingHandled = { log += "handled" },
        )
        relay.links.switchToMusic()
        assertTrue(log.isEmpty(), "nothing is attached: nothing happens")
        assertNull(relay.links.pendingVideo())
        relay.attach(host)
        relay.links.switchToMusic()
        relay.links.pauseMusic()
        relay.links.playAsSong("fakevideo01", "A title", "A channel", null)
        relay.links.onPendingHandled()
        assertEquals(PendingVideo("v1", 5L), relay.links.pendingVideo())
        assertEquals(listOf("switch", "pause", "song fakevideo01|A title|A channel|null", "handled"), log)
        val other = VideoHostLinks({}, { _, _, _, _ -> }, {}, { null }, {})
        relay.detach(other)
        relay.links.pauseMusic()
        assertEquals(5, log.size, "another host's detach does not detach this one")
        relay.detach(host)
        relay.links.pauseMusic()
        assertEquals(5, log.size, "a detached host hears nothing")
    }

    @Test fun theMusicBridgeHandsTheVideoOverAsASong() {
        var received: List<Any?>? = null
        val host = VideoHostLinks({}, { id, title, channel, thumb -> received = listOf(id, title, channel, thumb) }, {}, { null }, {})
        val item = FakeVideoSource.videos[0]
        HostMusicBridge(host).playAsSong(item)
        assertEquals(listOf(item.id, item.title, item.channelName.orEmpty(), item.thumbnailUrl), received)
        HostMusicBridge(host).playAsSong(item.copy(channelName = null))
        assertEquals("", received?.get(2))
    }

    // ---- The navigator --------------------------------------------------------------------------------------------------

    @Test fun theNavigatorIsSnapshotStateSoReadersAreRedrawn() {
        val navigator = VideoNavigator()
        for (read in listOf<(VideoNavigator) -> Any>({ it.top }, { it.tab }, { it.canPop }, { it.topPageKey() }, { it.pageKeys() })) {
            val reads = mutableListOf<Any>()
            val snapshot = Snapshot.takeSnapshot(readObserver = { reads += it })
            snapshot.enter { read(navigator) }
            snapshot.dispose()
            assertTrue(reads.isNotEmpty(), "reading the navigator reads snapshot state")
        }
    }

    @Test fun theNavigatorFollowsTheStackItWraps() {
        val navigator = VideoNavigator()
        assertEquals(VideoRoute.Home, navigator.top)
        navigator.push(VideoRoute.History)
        assertEquals(VideoRoute.History, navigator.top)
        assertTrue(navigator.canPop)
        navigator.selectTab(VideoTab.LIBRARY)
        assertEquals(VideoRoute.Library, navigator.top)
        assertEquals(VideoTab.LIBRARY, navigator.tab)
        navigator.replaceTop(VideoRoute.Liked)
        assertEquals(VideoRoute.Liked, navigator.top)
        assertTrue(navigator.pop())
        assertFalse(navigator.pop())
        assertEquals(VideoRoute.Library, navigator.top)
    }

    @Test fun everyEntryOfTheStacksHasItsOwnKey() {
        val navigator = VideoNavigator()
        navigator.push(VideoRoute.Watch("fakevideo01"))
        navigator.push(VideoRoute.Channel(FakeVideoSource.CHANNEL_1))
        navigator.push(VideoRoute.Watch("fakevideo01", 4_000))
        navigator.push(VideoRoute.Channel(FakeVideoSource.CHANNEL_2))
        navigator.push(VideoRoute.Watch("fakevideo01"))
        val keys = navigator.pageKeys()
        assertEquals(3 + 5, keys.size, "three roots and five pages, no two alike")
        assertTrue(navigator.topPageKey() in keys)
        navigator.pop()
        assertTrue(navigator.topPageKey() in keys, "a page keeps its key while it stays in the stack")
    }

    @Test fun theSaverKeepsTheWholeStackIncludingAShortsPage() {
        val navigator = VideoNavigator()
        navigator.push(VideoRoute.Watch("fakevideo01", 12_000))
        navigator.push(VideoRoute.Shorts(ShortsOrigin.Search("lofi: beats; 100%"), "fakeshort02", "seed-3"))
        navigator.selectTab(VideoTab.SUBSCRIPTIONS)
        navigator.push(VideoRoute.Shorts(ShortsOrigin.Channel(FakeVideoSource.CHANNEL_2), null, null))
        navigator.selectTab(VideoTab.HOME)
        val saved = with(VideoNavigator.Saver) { SaverScope { true }.save(navigator) }
        assertNotNull(saved)
        val restored = VideoNavigator.Saver.restore(saved)!!
        assertEquals(navigator.encode(), restored.encode())
        assertEquals(VideoRoute.Shorts(ShortsOrigin.Search("lofi: beats; 100%"), "fakeshort02", "seed-3"), restored.top)
        restored.selectTab(VideoTab.SUBSCRIPTIONS)
        assertEquals(VideoRoute.Shorts(ShortsOrigin.Channel(FakeVideoSource.CHANNEL_2), null, null), restored.top)
    }

    @Test fun aSavedStateThatCannotBeReadGivesAFreshStack() {
        val restored = VideoNavigator.Saver.restore("this is not a stack")!!
        assertEquals(VideoRoute.Home, restored.top)
        assertFalse(restored.canPop)
    }

    // ---- The nav host ---------------------------------------------------------------------------------------------------

    private val routeNames: List<String> get() = Regex("""(?m)^\s+data (?:object|class) (\w+)""").findAll(routeSource).map { it.groupValues[1] }.toList()

    /** The task that owns the page of each route (the brief's list; Downloads is the page of Y-33 now, LocalPlaylist is Y-20's). */
    private val owners = mapOf(
        "Home" to "Y-17", "Search" to "Y-18", "Watch" to "Y-19", "Channel" to "Y-20", "Playlist" to "Y-20", "LocalPlaylist" to "Y-20",
        "Subscriptions" to "Y-21", "Library" to "Y-22", "History" to "Y-22", "WatchLater" to "Y-22", "Liked" to "Y-22",
        "SavedPlaylists" to "Y-22", "Channels" to "Y-22", "Import" to "Y-22", "Downloads" to "Y-33", "Settings" to "Y-25", "Shorts" to "Y-31",
    )

    @Test fun theRoutesAreTheSeventeenOfTheBrief() {
        assertEquals(
            setOf(
                "Home", "Subscriptions", "Library", "Search", "Watch", "Shorts", "Channel", "Playlist", "LocalPlaylist", "History", "WatchLater",
                "Liked", "SavedPlaylists", "Channels", "Downloads", "Settings", "Import",
            ),
            routeNames.toSet(),
        )
        assertEquals(17, routeNames.size)
        assertEquals(routeNames.toSet(), owners.keys, "every route has an owner")
    }

    @Test fun theNavHostHasOneBranchPerRouteEachOnItsOwnLineAndMarked() {
        val lines = shell("VideoNavHost").lines()
        val whenAt = lines.indexOfFirst { it.trim() == "when (r) {" }
        assertTrue(whenAt > 0, "the branches are the lines of one `when (r)`")
        val branches = lines.indices.filter { lines[it].trim().startsWith("is VideoRoute.") && "->" in lines[it] }
        assertEquals(routeNames.size, branches.size, "one branch line per route")
        val seen = branches.map { Regex("""is VideoRoute\.(\w+) ->""").find(lines[it])!!.groupValues[1] }
        assertEquals(routeNames.toSet(), seen.toSet())
        assertEquals(seen.size, seen.toSet().size, "no route twice")
        for ((n, at) in branches.withIndex()) {
            val line = lines[at].trim()
            val route = seen[n]
            if (n == 0) assertTrue(at == whenAt + 1, "the first branch opens the `when`")
            else assertTrue(lines[at - 1].isBlank(), "a blank line sits before the branch of $route")
            // a branch a later task has replaced only has to keep its place
            if ("VideoPlaceholder(r)" in line) assertTrue(line.endsWith("// ${owners.getValue(route)} replaces this line"), "the branch of $route names ${owners.getValue(route)}: $line")
        }
    }

    @Test fun theNavHostKeepsEachPagesSavedStateAndFadesBetweenPages() {
        val src = shell("VideoNavHost")
        assertTrue("fun VideoNavHost(navigator: VideoNavigator, contentPadding: PaddingValues, wide: Boolean)" in src, "the signature of the brief")
        assertTrue("holder.SaveableStateProvider(shown.key)" in src && "holder.removeState(gone)" in src, "saved state per entry, dropped with the entry")
        assertTrue("LocalReducedMotion.current" in src && "Springs.REDUCED_MS" in src, "150 ms under reduced motion")
        assertTrue("EnterTransition.None togetherWith ExitTransition.None" in src, "a change between two tab roots does not fade")
    }

    @Test fun thePlaceholderIsTheEmptyStateWithThePlaceholderMessage() {
        val src = shell("VideoPlaceholder")
        assertTrue("fun VideoPlaceholder(route: VideoRoute)" in src)
        assertTrue("CosmoEmptyState(message = stringResource(Res.string.video_placeholder))" in src)
    }

    // ---- The phone shell ------------------------------------------------------------------------------------------------

    @Test fun theShellProvidesTheGraphAndTheNavigatorAndKeepsItsSignature() {
        val src = app
        assertEquals(
            listOf("windowWidth: Dp", "windowHeight: Dp", "host: VideoHostLinks", "modifier: Modifier = Modifier"),
            VideoSourceContract.parameters(src, "CosmoVideoApp"),
        )
        val body = VideoSourceContract.functionBody(src, "CosmoVideoApp")
        assertTrue("LocalVideoGraph provides graph" in body && "LocalVideoNavigator provides navigator" in body)
        assertTrue("rememberVideoGraph(host)" in body && "rememberVideoNavigator()" in body)
    }

    @Test fun theShortsPagerGetsNoTopBarNoChromeNoCapsuleAndNoPadding() {
        val src = VideoSourceContract.code(app)
        assertTrue("val fullScreen = route is VideoRoute.Shorts" in src)
        assertTrue(Regex("""fullScreen -> PaddingValues\(\)""").containsMatchIn(src), "no padding on the Shorts route")
        assertTrue(Regex("""if \(!fullScreen && !onWatch\) \{\s+VideoTopBar\(""").containsMatchIn(src), "no top bar on the Shorts route")
        assertTrue(Regex("""if \(!fullScreen\) \{\s+VideoBottomChrome\(""").containsMatchIn(src), "no chrome on the Shorts route")
        assertTrue("val capsuleShown = videoLoaded && !onWatch && !fullScreen" in src, "no capsule on the Shorts route or on the watch page")
        // and the desktop page gives the pager the whole page
        val page = VideoSourceContract.code(desktop("VideoDesktopEntry"))
        assertTrue("fullPage -> PaddingValues()" in page)
    }

    @Test fun theWatchPageHasNoTopBarBecauseItsPlayerStandsUnderTheStatusBar() {
        val src = VideoSourceContract.code(app)
        assertTrue("val onWatch = route is VideoRoute.Watch" in src)
        assertTrue("onWatch -> PaddingValues(bottom = chromeHeight + PAGE_GAP)" in src, "only the chrome's room at the bottom")
    }

    @Test fun theTopBarIsTheCosmoTopBarWithSearchAndTheProfileButton() {
        val src = VideoSourceContract.code(shell("VideoTopBar"))
        assertTrue("CosmoTopBar(" in src)
        assertTrue(Regex("""CosmoIconButton\(\s+icon = CosmoIcons\.Search,[\s\S]*?style = CosmoIconButtonStyle\.GLASS,\s+size = 40\.dp""").containsMatchIn(src), "Search is a 40 dp glass button")
        assertTrue(Regex("""CosmoProfileButton\(\s+avatarUrl = null,\s+name = profileName,\s+subtitle = profileSubtitle,\s+entries = drawerEntries""").containsMatchIn(src))
        assertTrue("Res.string.video_profile_name" in src && "Res.string.video_profile_subtitle" in src)
        assertTrue("route !is VideoRoute.Search" in src, "the Search page has its own field")
        assertTrue(Regex("""scrolled = scrolled\(\)""").containsMatchIn(src), "the scroll answer is read inside the bar")
    }

    @Test fun theDrawerListsHistoryDownloadsSettingsAndImportInThatOrder() {
        val src = VideoSourceContract.code(shell("VideoTopBar"))
        val entries = Regex("""CosmoDrawerEntry\((\S+), (\w+),""").findAll(src.substringAfter("fun videoDrawerEntries")).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            listOf("VideoIcons.History" to "history", "CosmoIcons.Download" to "downloads", "CosmoIcons.Settings" to "settings", "VideoIcons.Upload" to "importLabel"),
            entries,
        )
        for (route in listOf("History", "Downloads", "Settings", "Import")) assertTrue("navigator.push(VideoRoute.$route)" in src)
        // the desktop's drawer shows the same list
        assertTrue("videoDrawerEntries(DesktopVideoState.navigator)" in desktop("VideoDesktopEntry"))
    }

    @Test fun theChromeIsTheCosmoBottomChromeWithTheVideoMode() {
        val tabs = VideoSourceContract.code(shell("VideoTabs"))
        assertEquals(1, Regex("""CosmoBottomChrome\(""").findAll(tabs).count())
        assertTrue("mode = AppMode.VIDEO," in tabs, "CosmoBottomChrome is called with mode = AppMode.VIDEO")
        val labels = Regex("""CosmoTab\((\w+), ([\w.]+), ([\w.]+)\)""").findAll(tabs).map { it.groupValues[1] }.toList()
        assertEquals(listOf("home", "subscriptions", "library"), labels, "Home, Subscriptions and Library, in the order of VideoTab")
        assertEquals(listOf(VideoTab.HOME, VideoTab.SUBSCRIPTIONS, VideoTab.LIBRARY), VideoTab.entries)
        assertTrue("CosmoIcons.Home" in tabs && "CosmoIcons.Library" in tabs && "VideoIcons.Subscriptions" in tabs)
        assertTrue("navigator.push(VideoRoute.Watch(it.videoId))" in tabs, "a tap on the capsule opens the watch page")
        assertTrue("playback.togglePlayPause()" in tabs && "playback.playNext()" in tabs)
        // nothing else in the module draws the chrome
        for (other in listOf(app, shell("VideoAccent"), shell("VideoCapsule"), shell("VideoTopBar"), desktop("VideoDesktopEntry"))) assertFalse("CosmoBottomChrome(" in VideoSourceContract.code(other))
    }

    @Test fun theBackKeyPopsAPageAndAPendingVideoOpensItsWatchPage() {
        val src = VideoSourceContract.code(app)
        assertTrue("VideoBackHandler(enabled = navigator.canPop) { navigator.pop() }" in src)
        val pending = VideoSourceContract.functionBody(app, "OpenPendingVideo")
        assertTrue("snapshotFlow { host.pendingVideo() }" in pending)
        assertTrue("navigator.push(VideoRoute.Watch(pending.videoId, pending.startMs))" in pending)
        assertTrue(pending.indexOf("navigator.push(") < pending.indexOf("host.onPendingHandled()"), "the host is told after the page is pushed")
    }

    @Test fun theAndroidBackHandlerIsTheActivitysAndTheDesktopHasNone() {
        assertTrue("BackHandler(enabled = enabled, onBack = onBack)" in read("video/src/androidMain/kotlin/com/cosmotune/video/ui/shell/VideoBackHandler.android.kt"))
        assertTrue("expect fun VideoBackHandler(enabled: Boolean, onBack: () -> Unit)" in shell("VideoBackHandler"))
        assertFalse("androidx.activity" in read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/shell/VideoBackHandler.jvm.kt"), "the desktop actual does not touch the activity")
    }

    @Test fun theAccentFollowsTheVideoThatIsLoaded() {
        val src = VideoSourceContract.code(shell("VideoAccent"))
        assertTrue("shell.followCover(accent.coverUrl, accent.nextUrl, accent.channelId)" in src)
        assertTrue("SideEffect {" in src, "handed over from a side effect, as Music hands over its cover")
        val item = FakeVideoSource.videos[0]
        val queued = FakeVideoSource.videos[1]
        val target = PlaybackUiState.Idle.copy(item = item, queue = listOf(queued)).toAccent()
        assertEquals(item.thumbnailUrl, target.coverUrl)
        assertEquals(queued.thumbnailUrl, target.nextUrl)
        assertEquals(item.channelId, target.channelId)
        val none = PlaybackUiState.Idle.toAccent()
        assertNull(none.coverUrl); assertNull(none.nextUrl); assertNull(none.channelId)
    }

    @Test fun theMarkerLinesOfTheWaveTwoTasksAreThereOnceAndKeepTheirPlace() {
        val lines = app.lines()
        val pip = lines.filter { it.startsWith("    val pip = ") }
        assertEquals(1, pip.size, "one pip line")
        if ("false" in pip.single()) assertEquals("    val pip = false   // Y-19 replaces this line", pip.single())
        val guard = lines.indexOfFirst { it.trim().startsWith("if (pip) {") }
        val at = lines.indexOf(pip.single())
        assertTrue(guard in at + 1..at + 3, "the `if (pip)` the line guards follows it")
        val notice = lines.indices.filter { lines[it] == "        // Y-27: the first-run notice goes here" }
        if (notice.isNotEmpty()) {
            assertEquals(1, notice.size, "the notice marker once")
            assertTrue(lines[notice.single() + 1].isBlank(), "an empty line after the notice marker")
        } else {
            assertTrue("FirstRunNotice" in app, "the notice marker is gone because Y-27 filled it")
        }
        val entry = desktop("VideoDesktopEntry")
        assertTrue("// Y-27: the first-run notice goes here (the desktop page)" in entry || "FirstRunNotice" in entry, "the desktop page has its own place for the notice")
    }

    // ---- The capsule ----------------------------------------------------------------------------------------------------

    @Test fun theCapsuleShowsTheLoadedVideoAsASong() {
        val item = FakeVideoSource.videos[2]
        val playing = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.READY, playing = true, durationMs = 600_000).toCapsule()
        val song = assertNotNull(playing.song)
        assertEquals(item.id, song.videoId)
        assertEquals(item.title, song.title)
        assertEquals(item.channelName, song.artist, "the channel is the artist")
        assertEquals(item.thumbnailUrl, song.thumbnailUrl)
        assertTrue(playing.playing); assertFalse(playing.loading)
        assertEquals(600_000, playing.durationMs)
        val loading = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.LOADING).toCapsule()
        assertTrue(loading.loading)
        assertTrue(PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.BUFFERING, playing = true).toCapsule().loading)
    }

    @Test fun theCapsuleIsGoneWhenNothingIsLoadedAndHasNoLineForALiveStream() {
        assertSame(VideoCapsuleModel.None, PlaybackUiState.Idle.toCapsule())
        val item = FakeVideoSource.videos[2]
        assertSame(VideoCapsuleModel.None, PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.IDLE).toCapsule(), "stopped")
        assertSame(VideoCapsuleModel.None, PlaybackUiState.Idle.copy(item = null, status = PlayerStatus.READY).toCapsule())
        val live = PlaybackUiState.Idle.copy(item = item, status = PlayerStatus.READY, playing = true, durationMs = 5_000, live = true).toCapsule()
        assertEquals(0, live.durationMs, "no progress line for a live stream")
    }

    @Test fun theCapsuleReadsThePlayheadInATickerAndNowhereElse() {
        val capsule = VideoSourceContract.code(shell("VideoCapsule"))
        val reads = Regex("""positionMs\(\)""").findAll(capsule).map { it.range.first }.toList()
        assertEquals(2, reads.size)
        val ticker = capsule.indexOf("LaunchedEffect(")
        assertTrue(ticker > 0 && reads.all { it > ticker }, "the playhead is read inside the effect that ticks twice a second")
        assertTrue("private const val TICK_MS = 500L" in capsule)
        assertTrue("while (playing)" in capsule, "no tick while paused")
        for (name in listOf("VideoNavHost", "VideoTopBar", "VideoTabs", "VideoAccent", "VideoNavigator", "VideoPlaceholder")) {
            assertFalse("positionMs()" in VideoSourceContract.code(shell(name)), "$name does not read the playhead")
        }
        assertFalse("positionMs()" in VideoSourceContract.code(app))
        // the capsule itself reads the position it is given in its draw block (CosmoBottomChrome), so this file only writes it
        assertTrue("position.report(playback.positionMs())" in capsule)
    }

    // ---- The desktop ----------------------------------------------------------------------------------------------------

    @Test fun theDesktopSidebarHasTheRowsOfTheSpecInOrderWithShortsPushingTheHomePager() {
        val src = VideoSourceContract.code(desktop("VideoDesktopSidebarContent"))
        val rows = Regex("""SidebarNavItem\(([\w.]+), ([\w.]+), (\w+), route""").findAll(src.substringAfter("private fun sidebarNav")).map { it.groupValues[1] to it.groupValues[3] }.toList()
        assertEquals(
            listOf(
                "CosmoIcons.Home" to "home", "VideoIcons.Shorts" to "shorts", "VideoIcons.Subscriptions" to "subscriptions", "VideoIcons.History" to "history",
                "CosmoIcons.Clock" to "watchLater", "CosmoIcons.Queue" to "playlists", "CosmoIcons.Download" to "downloads",
            ),
            rows,
        )
        assertTrue("navigator.push(VideoRoute.Shorts(ShortsOrigin.Home))" in src, "the Shorts row pushes the pager of the Home origin")
        assertTrue("navigator.push(VideoRoute.Library)" in src, "Playlists is the third tab's root")
        for (route in listOf("Home", "Subscriptions", "History", "WatchLater", "Downloads")) assertTrue("navigator.push(VideoRoute.$route)" in src)
        assertTrue("navigator.push(VideoRoute.Settings)" in src && "Res.string.video_drawer_settings" in src, "Settings stands at the bottom")
        val settingsRow = Regex("""SidebarNavRow\(\s+icon = CosmoIcons\.Settings""").find(src)
        assertNotNull(settingsRow)
        assertTrue(settingsRow.range.first > src.indexOf("items(channels"), "Settings stands after the channels, at the bottom")
    }

    @Test fun theDesktopSidebarKeepsTheNumbersOfMusicsOwn() {
        val src = VideoSourceContract.code(desktop("VideoDesktopSidebarContent"))
        assertTrue("Modifier.width(ShellMetrics.DESKTOP_SIDEBAR_DP.dp)" in src, "220 dp")
        assertTrue("private val SEARCH_HEIGHT = 44.dp" in src && "keyHint = if (IS_MAC) null else \"Ctrl K\"" in src)
        assertTrue("private val NAV_HEIGHT = 44.dp" in src && "private val NAV_RADIUS = 22.dp" in src)
        assertTrue("private val AVATAR = 36.dp" in src, "36 dp avatar circles")
        assertTrue("private const val HEADING_SP = 15f" in src && "private const val HEADING_WEIGHT = 700" in src, "the heading: 15 sp, 700")
        assertTrue("private const val ITEM_TITLE_SP = 14f" in src && "private const val ITEM_TITLE_WEIGHT = 650" in src, "the name: 14 sp, 650")
        // the channels: the newest upload first
        assertTrue("compareByDescending<Subscription> { it.lastUploadMs ?: Long.MIN_VALUE }" in src)
        assertTrue("items(channels, key = { it.channelId })" in src, "stable keys")
        // the selected row follows the page on show and is filled with the tonal fill, drawn from the live palette
        assertTrue("tonalBrush(colors)" in src)
        val music = read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopSidebar.kt")
        assertTrue("NAV_HEIGHT = 44.dp" in music && "NAV_RADIUS = 22.dp" in music && "SEARCH_HEIGHT = 44.dp" in music, "Music's sidebar has the numbers this one copies")
    }

    @Test fun theSearchFieldSharesItsStateWithThePageThroughTheDesktopState() {
        val sidebar = VideoSourceContract.code(desktop("VideoDesktopSidebarContent"))
        assertTrue("DesktopVideoState.query" in sidebar && "DesktopVideoState.focusSearch = false" in sidebar)
        // the one function the search task (Y-18) owns: it records the query, then pushes the search page (SearchStructureTest pins its body)
        assertTrue("navigator.push(VideoRoute.Search(q))" in VideoSourceContract.functionBody(desktop("VideoDesktopSidebarContent"), "submitSidebarSearch"), "Enter pushes the search page")
        val page = VideoSourceContract.code(desktop("VideoDesktopEntry"))
        assertTrue("Key.K" in page && "event.isMetaPressed" in page && "event.isCtrlPressed" in page, "Ctrl K, Cmd K on macOS")
        assertTrue("DesktopVideoState.requestSearchFocus()" in page)
        val state = VideoSourceContract.code(desktop("DesktopVideoState"))
        assertTrue("val navigator = VideoNavigator()" in state && "var query by mutableStateOf" in state && "var focusSearch by mutableStateOf" in state)
    }

    @Test fun theDesktopPageDrawsTheWideNavHostAndGivesTheEntryPointsOfTheStubs() {
        val src = desktop("VideoDesktopEntry")
        assertTrue("VideoNavHost(navigator, padding, wide = true)" in src)
        assertTrue("fun VideoDesktopPage(contentPadding: PaddingValues, host: VideoHostLinks)" in src)
        assertTrue("fun VideoDesktopSidebar(focusSearch: Boolean, onSearchFocused: () -> Unit, caption: (@Composable () -> Unit)? = null)" in src)
        assertTrue("fun videoDesktopDrawerEntries(): List<CosmoDrawerEntry>" in src)
        assertTrue("object VideoDesktopBack" in src && "val canGoBack: Boolean get() = DesktopVideoState.navigator.canPop" in src)
        assertTrue("DesktopVideoState.navigator.pop()" in src)
    }

    // ---- Rules of the whole shell ---------------------------------------------------------------------------------------

    private val shellFiles get() = listOf("VideoNavigator", "VideoNavHost", "VideoPlaceholder", "VideoTopBar", "VideoTabs", "VideoCapsule", "VideoAccent", "VideoBackHandler")
        .map { it to shell(it) } + listOf("CosmoVideoApp" to app) +
        listOf("DesktopVideoState", "VideoDesktopEntry", "VideoDesktopSidebarContent").map { it to desktop(it) }

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
    private val topLevelFun = Regex("""(?m)^(?:(?:internal|private|public)\s+)?fun\s+(?:<[^>]*>\s*)?([\w.<>?]+\.)?\w+\s*\(""")

    private fun inDrawScopeFunction(code: String, index: Int): Boolean {
        val header = topLevelFun.findAll(code).lastOrNull { it.range.first <= index } ?: return false
        return header.groupValues[1] == "DrawScope."
    }

    @Test fun theLivePaletteIsReadOnlyWhileDrawing() {
        var seen = 0
        for ((name, source) in shellFiles) {
            val code = VideoSourceContract.code(source)
            var from = 0
            while (true) {
                val at = code.indexOf("live(", from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
                seen++
                val inDraw = enclosingHeads(code, at).any { drawHead.containsMatchIn(it) }
                assertTrue(inDraw || inDrawScopeFunction(code, at), "$name: live( at line ${code.take(at).count { it == '\n' } + 1} is neither in a draw lambda nor in a DrawScope function")
            }
        }
        assertTrue(seen > 0, "the check found the shell's live reads")
    }

    @Test fun noColourIsPickedByHandAndTheOnlyBlackIsTheFullWindowOfPictureInPicture() {
        for ((name, source) in shellFiles) {
            val code = VideoSourceContract.code(source)
            assertFalse(Regex("""Color\(0x""").containsMatchIn(code), "$name picks a colour by hand")
            assertFalse(Regex("""Color\.(Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|White)\b""").containsMatchIn(code), "$name uses a named colour")
            if (name != "CosmoVideoApp") assertFalse("Color.Black" in code, "$name uses black")
        }
        val code = VideoSourceContract.code(app)
        assertEquals(1, Regex("""Color\.Black""").findAll(code).count())
        assertTrue(code.indexOf("Color.Black") > code.indexOf("private fun VideoPipLayer"), "the one black is the picture-in-picture window")
    }

    @Test fun nothingRemembersALiveColourOrABrush() {
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush|SolidColor|\.live\()""")
        for ((name, source) in shellFiles) assertNull(remembering.find(VideoSourceContract.code(source)), "$name remembers a brush or a live colour")
    }

    @Test fun nothingNamesTheSourceOrTheExtractorAndNothingTouchesAnAccount() {
        for ((name, source) in shellFiles + listOf("VideoGraph" to read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/graph/VideoGraph.kt"))) {
            val code = VideoSourceContract.code(source)
            assertFalse("org.schabi" in code, "$name imports the extractor")
            assertFalse(Regex("""(?i)youtube|\bYT\b|cookie|CookieJar""").containsMatchIn(source), "$name names the source or touches cookies")
        }
    }

    @Test fun theShellReadsNoMusicStateOtherThanTheBlocksItIsGiven() {
        // Music never depends on :video, and Video reaches into Music only through VideoHostLinks and the shared building blocks
        for ((name, source) in shellFiles) {
            val imports = Regex("""(?m)^import (com\.music\.bitchord\.[\w.]+)""").findAll(source).map { it.groupValues[1] }.toList()
            for (import in imports) {
                assertTrue(
                    import in setOf(
                        "com.music.bitchord.data.model.Song", "com.music.bitchord.playback.PlaybackPosition", "com.music.bitchord.ui.components.FLOATING_BAR_MAX_WIDTH",
                    ) || import.startsWith("com.music.bitchord.sharedui.resources."),
                    "$name imports $import",
                )
            }
        }
    }

    @Test fun theScrollTrackerCountsWhatThePagesConsumeAndKeepsItPerPage() {
        val tracker = TopBarScroll()
        tracker.show("A")
        assertEquals(0f, tracker.offset)
        assertEquals(Offset.Zero, tracker.connection.onPostScroll(Offset(0f, -120f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(120f, tracker.offset, "a list scrolled forward consumed a negative amount")
        tracker.connection.onPostScroll(Offset(0f, 50f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(70f, tracker.offset)
        tracker.connection.onPostScroll(Offset(0f, 500f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(0f, tracker.offset, "never negative")
        tracker.connection.onPostScroll(Offset(40f, 0f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(0f, tracker.offset, "a sideways scroll does not count")
        tracker.connection.onPostScroll(Offset(0f, -90f), Offset.Zero, NestedScrollSource.UserInput)
        tracker.show("B")
        assertEquals(0f, tracker.offset, "another page starts at the top")
        tracker.connection.onPostScroll(Offset(0f, -10f), Offset.Zero, NestedScrollSource.UserInput)
        tracker.show("A")
        assertEquals(90f, tracker.offset, "the page that was left is as scrolled as it was")
        tracker.show("B")
        assertEquals(10f, tracker.offset)
    }
}
