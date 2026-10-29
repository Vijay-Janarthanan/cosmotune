package com.cosmotune.video.ui

import com.cosmotune.design.AppMode
import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.feed.FakeSubscriptionFeed
import com.cosmotune.video.graph.ModeAudio
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.graph.followRegion
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.music.VideoMusicBridge
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.desktop.DesktopCapabilities
import com.cosmotune.video.segments.Branding
import com.cosmotune.video.segments.BrandingProvider
import com.cosmotune.video.segments.FakeBrandingProvider
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.segments.FakeVotesProvider
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.TitleCase
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.shorts.ShortsSeedCache
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.actions.ActionToastKind
import com.cosmotune.video.ui.actions.DownloadAsk
import com.cosmotune.video.ui.actions.ShareVideo
import com.cosmotune.video.ui.actions.VideoActionHandler
import com.cosmotune.video.ui.actions.VideoActionsState
import com.cosmotune.video.ui.actions.downloadedIds
import com.cosmotune.video.ui.blocks.VideoAction
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.branding.BrandingLookups
import com.cosmotune.video.ui.branding.shownTitle
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.shorts.openShort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wiring of plan task Y-16: the real pieces in the graph's slots (no fake left but the Shorts slot), what the graph needs besides
 * them (the store flush, the version, the region, the notification's queue buttons, the modes' audio, the feed's limit, the engines'
 * first 5 s of bandwidth), and the shared actions the pages build on: the action handler, the download ask and its host, the
 * branding helpers, the Shorts filter's callers, the comment and Shorts stubs and [openShort]. Source rules are read from the files;
 * the logic is run against the fakes of `:shared`.
 */
class WiringStructureTest {
    private fun read(path: String) = VideoSourceContract.read(path)
    private val androidFactory get() = read("video/src/androidMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.android.kt")
    private val desktopFactory get() = read("video/src/jvmMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.jvm.kt")
    private val actions = "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/actions"
    private fun action(name: String) = read("$actions/$name.kt")

    @AfterTest fun forget() {
        ShortsSeedCache.clear()
        BrandingLookups.clear()
    }

    // ---- The factories ----------------------------------------------------------------------------------------------------

    private val slots = listOf(
        "source", "library", "settings", "segments", "branding", "votes", "feed", "downloads", "playback", "surface", "music", "metered", "shorts",
    )

    private fun slotLine(text: String, slot: String): String {
        val lines = text.lines()
        val at = lines.indices.filter { lines[it].trim().startsWith("val $slot = ") }
        assertEquals(1, at.size, "the slot `$slot` is declared once, on its own line")
        assertTrue(lines[at.single() + 1].isBlank(), "a blank line follows the slot `$slot`")
        return lines[at.single()].trim().substringAfter("val $slot = ")
    }

    @Test fun noFakeIsLeftInEitherFactoryButTheShortsSlotOfY31() {
        // Y-31 replaced the Shorts slot with its queue engine: no fake is left in either factory.
        for ((name, text) in listOf("android" to androidFactory, "desktop" to desktopFactory)) {
            val fakes = VideoSourceContract.code(text).lines().filter { "Fake" in it }.map { it.trim() }
            assertEquals(emptyList(), fakes, "$name: no slot holds a fake")
            assertTrue("val shorts = ShortsQueueEngine(source, feed, library, settings)" in text, "$name: the Shorts slot is Y-31's engine")
        }
    }

    @Test fun eachSlotHoldsTheRealPieceOnItsOwnLine() {
        val common = mapOf(
            // in two pieces, so this file does not build the source itself (see nothingButTheFactoriesBuildsTheNewPipeSource)
            "source" to "NewPipe" + "VideoSource(clock)",
            "library" to "VideoLibraryStore(files, scope, clock)",
            "settings" to "VideoSettingsStore(files, scope)",
            "segments" to "SponsorBlockClient(clock = clock, userAgent = agent)",
            "branding" to "DeArrowClient(clock = clock, userAgent = agent)",
            "votes" to "RydClient(clock = clock, userAgent = agent)",
            "feed" to "RefreshLimitedFeed(SubscriptionFeedEngine(library, source, OkHttpFeedFetcher(), files, settings, scope, clock), library, clock)",
            "playback" to "VideoPlayerController(source, engine, library, settings, segments, StreamSelector(), SegmentPolicy, metered, scope, clock)",
            "surface" to "engine",
            "music" to "HostMusicBridge(relay.links)",
            "shorts" to "ShortsQueueEngine(source, feed, library, settings)",
        )
        val android = common + mapOf(
            "downloads" to "AndroidDownloadController(application, source, settings, scope, segments)",
            "metered" to "{ connectivity?.isActiveNetworkMetered ?: false }",
        )
        val desktop = common + mapOf(
            "downloads" to "DesktopDownloadController(source, settings, scope, segments)",
            "metered" to "{ false }",
        )
        for ((name, text, expected) in listOf(Triple("android", androidFactory, android), Triple("desktop", desktopFactory, desktop))) {
            for (slot in slots) assertTrue(slotLine(text, slot).startsWith(expected.getValue(slot)), "$name: `$slot` is ${expected.getValue(slot)}")
            assertTrue(Regex("""return VideoGraph\(""").containsMatchIn(text))
        }
        assertTrue("val engine = AndroidVideoEngine(application)" in androidFactory)
        assertTrue("val engine = DesktopVideoEngine.shared" in desktopFactory)
        assertTrue("val connectivity = application.getSystemService(ConnectivityManager::class.java)" in androidFactory)
    }

    @Test fun thePiecesShareOneProcessScopeAndTheLibraryFolder() {
        for (text in listOf(androidFactory, desktopFactory)) {
            assertTrue("private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)" in text, "one process-wide supervisor scope on Default")
            assertTrue("private val clock: () -> Long = System::currentTimeMillis" in text)
        }
        assertTrue("val files = FileLibraryFiles(videoLibraryDir(LibraryPlatform.Android(application.filesDir)))" in androidFactory)
        assertTrue("val files = FileLibraryFiles(videoLibraryDir(LibraryPlatform.Desktop))" in desktopFactory)
    }

    @Test fun theThreeWebServicesSayTheAppsVersion() {
        assertTrue("val agent = WebServicesHttp.userAgent(appVersion(application))" in androidFactory)
        assertTrue("application.packageManager.getPackageInfo(application.packageName, 0).versionName" in androidFactory)
        assertTrue("val agent = WebServicesHttp.userAgent(System.getProperty(\"cosmotune.version\") ?: FALLBACK_VERSION)" in desktopFactory)
        for (text in listOf(androidFactory, desktopFactory)) assertEquals(3, Regex("""userAgent = agent""").findAll(text).count(), "SponsorBlock, DeArrow and RYD")
    }

    @Test fun theStoresAreWrittenOutWhenTheAppStops() {
        val android = VideoSourceContract.code(androidFactory)
        assertTrue("application.registerComponentCallbacks(" in android && "level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN" in android)
        val desktop = VideoSourceContract.code(desktopFactory)
        assertTrue("Runtime.getRuntime().addShutdownHook(" in desktop)
        for (text in listOf(android, desktop)) assertTrue("library.flush()" in text && "settings.flush()" in text)
    }

    @Test fun theNotificationsPreviousAndNextDriveTheControllersQueue() {
        val service = VideoSourceContract.code(read("video/src/androidMain/kotlin/com/cosmotune/video/player/android/VideoPlaybackService.kt"))
        assertTrue(".setCallback(QueueButtonsCallback)" in service && ".setMediaButtonPreferences(queueButtonPreferences())" in service)
        assertTrue("CommandButton.ICON_PREVIOUS" in service && "CommandButton.SLOT_BACK" in service)
        assertTrue("CommandButton.ICON_NEXT" in service && "CommandButton.SLOT_FORWARD" in service)
        assertTrue("session.isMediaNotificationController(controller)" in service, "only the notification's controller gets the two commands")
        assertTrue("ACTION_PREVIOUS -> queueButtons?.previous()" in service && "ACTION_NEXT -> queueButtons?.next()" in service)
        assertTrue("KeyEvent.KEYCODE_MEDIA_NEXT -> true" in service && "KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false" in service, "a headset's keys too")
        val factory = VideoSourceContract.code(androidFactory).replace(Regex("\\s+"), " ")
        assertTrue("VideoPlaybackService.queueButtons = object : VideoPlaybackService.QueueButtons {" in factory)
        assertTrue("override fun previous() = playback.playPrevious()" in factory && "override fun next() = playback.playNext()" in factory)
        val strings = read("video/src/androidMain/res/values/strings.xml")
        assertTrue("<string name=\"video_notification_previous\">Previous</string>" in strings && "<string name=\"video_notification_next\">Next</string>" in strings)
    }

    @Test fun theRegionFollowsTheSettingsWithTheDevicesAsTheDefault() = runTest {
        for (text in listOf(androidFactory, desktopFactory)) assertTrue("scope.followRegion(settings, source, device.country, device.language)" in text)
        val source = FakeVideoSource()
        val settings = FakeVideoSettingsRepository()
        backgroundScope.followRegion(settings, source, "IN", "ta")
        runCurrent()
        assertEquals("IN" to "ta", source.region)
        settings.update { it.copy(region = "DE") }
        runCurrent()
        assertEquals("DE" to "ta", source.region)
        settings.update { it.copy(contentLanguage = "fr", region = " ") }
        runCurrent()
        assertEquals("IN" to "fr", source.region, "a blank choice is the device's")
    }

    @Test fun theFeedsRefreshesAreLimited() {
        for (text in listOf(androidFactory, desktopFactory)) assertTrue("RefreshLimitedFeed(SubscriptionFeedEngine(" in text)
    }

    @Test fun audioIsExclusiveAcrossTheModes() = runTest {
        for (text in listOf(androidFactory, desktopFactory)) {
            assertTrue("SideEffect { " in text && ".audio?.watch(modes) }" in text, "the graph follows the stored mode")
            assertTrue("val modes = LocalCosmoShell.current?.settings?.mode" in text)
            assertTrue("PauseMusicWhileVideoPlays(graph.playback, links)" in text, "a video starting pauses Music")
            assertTrue("audio = ModeAudio(playback, scope)" in text)
        }
        assertTrue("fun stopVideoForMusic() {" in desktopFactory && "DesktopVideoProcess.audio?.musicStarted()" in desktopFactory)
        val host = read("desktopApp/src/main/kotlin/com/cosmotune/video/host/DesktopModeHost.kt")
        assertTrue("LaunchedEffect(musicPlaying) { if (musicPlaying) stopVideoForMusic() }" in host, "Music starting on the desktop stops a video")

        val playback = FakeVideoPlayback()
        val audio = ModeAudio(playback, backgroundScope)
        val modes = MutableStateFlow(AppMode.VIDEO)
        audio.watch(modes)
        runCurrent()
        assertTrue(playback.calls.isEmpty(), "nothing is loaded: nothing to stop")
        playback.set(PlaybackUiState.Idle.copy(item = FakeVideoSource.videos[0], status = PlayerStatus.READY, playing = false))
        modes.value = AppMode.MUSIC
        runCurrent()
        assertEquals(listOf("stop()"), playback.calls, "leaving Video stops a loaded video, paused or not")
        audio.watch(modes)
        modes.value = AppMode.VIDEO
        runCurrent()
        assertEquals(1, playback.calls.size, "watching the same flow twice starts no second collector")
        audio.musicStarted()
        assertEquals(1, playback.calls.size, "Music starting stops only a video that plays")
        playback.set(PlaybackUiState.Idle.copy(item = FakeVideoSource.videos[1], status = PlayerStatus.READY, playing = true))
        audio.musicStarted()
        assertEquals(listOf("stop()", "stop()"), playback.calls)
    }

    /** MG-01: Manga is a third mode. Leaving Video for it unloads the hidden video's decoder; Manga itself never touches Music. */
    @Test fun leavingVideoForMangaUnloadsTheHiddenVideoAndMangaLeavesMusicPlaying() = runTest {
        val playback = FakeVideoPlayback()
        val audio = ModeAudio(playback, backgroundScope)
        val modes = MutableStateFlow(AppMode.VIDEO)
        audio.watch(modes)
        runCurrent()
        playback.set(PlaybackUiState.Idle.copy(item = FakeVideoSource.videos[0], status = PlayerStatus.READY, playing = true))
        modes.value = AppMode.MANGA
        runCurrent()
        assertEquals(listOf("stop()"), playback.calls, "a video playing when Manga opens is stopped")
        assertEquals(1, playback.engine.unloadCount, "its engine is unloaded, which releases the platform decoder")
        assertNull(playback.state.value.item, "nothing stays loaded behind the reader")
        modes.value = AppMode.MUSIC
        runCurrent()
        modes.value = AppMode.MANGA
        runCurrent()
        assertEquals(1, playback.calls.size, "Manga ↔ Music with nothing loaded stops nothing twice")
        // A paused video behind Manga is also unloaded, not only a playing one.
        modes.value = AppMode.VIDEO
        runCurrent()
        playback.set(PlaybackUiState.Idle.copy(item = FakeVideoSource.videos[1], status = PlayerStatus.READY, playing = false))
        modes.value = AppMode.MANGA
        runCurrent()
        assertEquals(2, playback.engine.unloadCount)

        // The real engines' unload releases what they hold: the Android controller is released, the desktop frames are freed.
        val androidEngine = VideoSourceContract.code(read("video/src/androidMain/kotlin/com/cosmotune/video/player/android/AndroidVideoEngine.kt"))
        assertTrue("disconnect()" in androidEngine.substringAfter("override fun unload()").take(900))
        assertTrue("c.release()" in androidEngine.substringAfter("private fun disconnect()").take(600))
        val desktopEngine = VideoSourceContract.code(read("video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop/DesktopVideoEngine.kt"))
        assertTrue("exchange.clear(freeBuffers = true)" in desktopEngine.substringAfter("override fun unload()").take(400))

        // Neither host's Manga branch pauses, stops or rebinds Music: the music service keeps playing while the reader is open.
        val phone = VideoSourceContract.code(read("app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt"))
        assertTrue("mode == AppMode.MANGA) holder.SaveableStateProvider(\"manga\") {" in phone && "else if (videoShell == null)" in phone)
        val phoneManga = phone.substringAfter("mode == AppMode.MANGA) holder.SaveableStateProvider(\"manga\") {").substringBefore("else if (videoShell == null)")
        assertTrue("MangaApp(onMusic = { shell.settings.setMode(AppMode.MUSIC) }, showLeave = true)" in phoneManga)
        assertTrue("if (request.value != null) shell.settings.setMode(AppMode.MUSIC)" in phoneManga, "a music link opened while reading returns to Music")
        val desktop = VideoSourceContract.code(read("desktopApp/src/main/kotlin/com/cosmotune/video/host/DesktopModeHost.kt"))
        assertTrue("fun desktopMangaShown()" in desktop && "fun DesktopVideoPage(" in desktop)
        val desktopManga = desktop.substringAfter("fun desktopMangaShown()").substringBefore("fun DesktopVideoPage(")
        for (branch in listOf(phoneManga, desktopManga)) for (call in listOf("pause", "PlayPause", "stopVideoForMusic", "playSong", ".stop(")) {
            assertFalse(call in branch, "the Manga branch never calls $call")
        }
        assertTrue("DesktopModeHost.mangaActive = shown" in desktopManga, "the desktop's music keys stand aside while Manga owns the keyboard")
    }

    @Test fun theControllerPreparesTheSegmentsItIsGiven() {
        val controller = read("shared/src/commonMain/kotlin/com/cosmotune/video/player/controller/VideoPlayerController.kt")
        assertTrue("l.segments = SegmentMath.prepare(found, durationNow(l), settings.settings.value.segmentBehaviours)" in controller)
    }

    @Test fun theEnginesReportNoBandwidthForTheFirstFiveSecondsAndTheLandscapeHeight() {
        val android = VideoSourceContract.code(read("video/src/androidMain/kotlin/com/cosmotune/video/player/android/AndroidVideoEngine.kt"))
        assertTrue("bandwidthKbps = bandwidthWarmUp.gate(" in android)
        assertTrue("if (meter !== measuredMeter) {" in android && "bandwidthWarmUp.restart()" in android, "a new meter measures from zero")
        assertTrue("displayHeightPx = shorterSide(display)" in read("video/src/androidMain/kotlin/com/cosmotune/video/player/android/AndroidCapabilities.kt"))
        val desktop = VideoSourceContract.code(read("video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop/DesktopVideoEngine.kt"))
        assertTrue("val kbps = bandwidthWarmUp.gate(measured) ?: return" in desktop && "bandwidthWarmUp.begin()" in desktop)
        assertEquals(1080, DesktopCapabilities.landscapeHeight(1920, 1080))
        assertEquals(1080, DesktopCapabilities.landscapeHeight(1080, 1920), "a monitor turned upright")
        assertEquals(1440, DesktopCapabilities.landscapeHeight(2560, 1440))
    }

    @Test fun nothingButTheFactoriesBuildsTheNewPipeSource() {
        val needle = "NewPipe" + "VideoSource("
        val root = VideoSourceContract.root
        val factories = setOf(
            "video/src/androidMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.android.kt",
            "video/src/jvmMain/kotlin/com/cosmotune/video/graph/VideoGraphFactory.jvm.kt",
        )
        val builders = File(root, "video/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { "/jvmTest/" in it.invariantSeparatorsPath }
            .filter { needle in VideoSourceContract.code(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals(factories, builders)
    }

    // ---- The action handler --------------------------------------------------------------------------------------------------

    private class Bridge : VideoMusicBridge {
        val songs = mutableListOf<String>()
        override fun playAsSong(item: VideoItem) {
            songs += item.id
        }
    }

    private class Fixture {
        val playback = FakeVideoPlayback()
        val library = FakeVideoLibrary()
        val bridge = Bridge()
        val navigator = VideoNavigator()
        val state = VideoActionsState()
        val shared = mutableListOf<Pair<String, String>>()
        val graph = VideoGraph(
            source = FakeVideoSource(), library = library, settings = FakeVideoSettingsRepository(), segments = FakeSegmentProvider(),
            branding = FakeBrandingProvider(), votes = FakeVotesProvider(), feed = FakeSubscriptionFeed(), downloads = FakeDownloadController(),
            playback = playback, surface = null, music = bridge, metered = { false }, shorts = FakeShortsFeed(),
        )
        val handler = VideoActionHandler(graph, navigator, state, ShareVideo { title, url -> shared += title to url })
    }

    @Test fun theHandlerAndItsEntryPointsHaveTheirFixedSignatures() {
        val src = action("VideoActionHandler")
        assertEquals(
            listOf(
                "item: VideoItem", "action: VideoAction", "context: VideoSheetContext", "details: VideoDetails? = null",
                "playlistId: String? = null", "list: List<VideoItem> = emptyList()",
            ),
            VideoSourceContract.parameters(src, "handle"),
        )
        assertTrue(Regex("""@Composable\s+fun rememberVideoActions\(\): VideoActionHandler""").containsMatchIn(src))
        assertEquals(listOf("item: VideoItem"), VideoSourceContract.parameters(src, "download"))
        assertEquals(listOf("info: DownloadBatchInfo", "items: List<VideoItem>", "latestPicker: Boolean"), VideoSourceContract.parameters(src, "downloadBatch"))
        assertEquals(listOf("item: VideoItem", "list: List<VideoItem> = emptyList()"), VideoSourceContract.parameters(src, "play"))
    }

    @Test fun playOnAShortPushesTheShortsRouteWithTheListsShortsAsItsSeed() {
        val f = Fixture()
        val short = FakeVideoSource.shorts[2]
        val list = listOf(FakeVideoSource.videos[0], FakeVideoSource.shorts[0], short, FakeVideoSource.videos[1], FakeVideoSource.shorts[5])
        f.handler.handle(short, VideoAction.Play, VideoSheetContext.DEFAULT, list = list)
        val route = assertIs<VideoRoute.Shorts>(f.navigator.top)
        assertEquals(ShortsOrigin.Related(short.id), route.origin)
        assertEquals(short.id, route.startVideoId)
        val seed = ShortsSeedCache.take(route.seedKey!!)
        assertEquals(listOf(FakeVideoSource.shorts[0], short, FakeVideoSource.shorts[5]), seed, "the list's Shorts, in order, videos left out")
        assertTrue(f.playback.calls.isEmpty(), "the pager plays it, not the handler")
    }

    @Test fun openShortPutsTheShortFirstWhenTheListDoesNotHoldIt() {
        val navigator = VideoNavigator()
        val short = FakeVideoSource.shorts[7]
        navigator.openShort(short, ShortsOrigin.Home, listOf(FakeVideoSource.shorts[1]))
        val route = assertIs<VideoRoute.Shorts>(navigator.top)
        assertEquals(ShortsOrigin.Home, route.origin)
        assertEquals(listOf(short, FakeVideoSource.shorts[1]), ShortsSeedCache.take(route.seedKey!!))
        navigator.openShort(short, ShortsOrigin.Subscriptions, emptyList())
        val again = assertIs<VideoRoute.Shorts>(navigator.top)
        assertEquals(listOf(short), ShortsSeedCache.take(again.seedKey!!), "a fresh key each time")
    }

    @Test fun playOnAVideoOpensItsWatchPageAndPlaysIt() {
        val f = Fixture()
        val video = FakeVideoSource.videos[3]
        f.handler.handle(video, VideoAction.Play, VideoSheetContext.DEFAULT)
        assertEquals(VideoRoute.Watch(video.id), f.navigator.top)
        assertEquals(listOf("play(${video.id})"), f.playback.calls)
        f.handler.play(FakeVideoSource.shorts[0])
        assertIs<VideoRoute.Shorts>(f.navigator.top, "a card's tap on a Short opens the pager too")
    }

    @Test fun playHandsTheCardsItemToThePlaybackNotOnlyItsId() {
        // Y-34: a premiere tapped on a card shows "Upcoming" because the playback knows the item the card showed
        val f = Fixture()
        val card = FakeVideoSource.videos[3].copy(title = "As the card showed it", isUpcoming = true, uploadedAtMs = FakeVideoSource.NOW_MS + 86_400_000L)
        f.handler.play(card)
        assertEquals(VideoRoute.Watch(card.id), f.navigator.top)
        assertEquals(card, f.playback.state.value.item, "the fake shows the item it was handed; by id it would show its own sample")
    }

    @Test fun theOtherActionsDoWhatTheSheetSays() {
        val f = Fixture()
        val video = FakeVideoSource.videos[4]
        f.handler.handle(video, VideoAction.AddToQueue, VideoSheetContext.DEFAULT)
        assertEquals("addToQueue(${video.id})", f.playback.calls.last())

        f.handler.handle(video, VideoAction.PlayAsSong, VideoSheetContext.DEFAULT)
        assertEquals("stop()", f.playback.calls.last(), "the video stops first")
        assertEquals(listOf(video.id), f.bridge.songs)

        f.handler.handle(video, VideoAction.WatchLater, VideoSheetContext.DEFAULT)
        assertEquals(listOf(video.id), f.library.watchLater.value.map { it.id })
        f.handler.handle(video, VideoAction.WatchLater, VideoSheetContext.DEFAULT)
        assertTrue(f.library.watchLater.value.isEmpty(), "a second tap takes it out")

        f.handler.handle(video, VideoAction.SaveToPlaylist, VideoSheetContext.DEFAULT)
        assertEquals(video, f.state.saveTarget)

        f.handler.handle(video, VideoAction.Share, VideoSheetContext.DEFAULT)
        assertEquals(listOf(video.title to "https://youtu.be/${video.id}"), f.shared)

        f.handler.handle(video, VideoAction.OpenChannel, VideoSheetContext.DEFAULT)
        assertEquals(VideoRoute.Channel(video.channelId!!), f.navigator.top)

        f.library.recordWatch(video, 30_000, 600_000)
        f.handler.handle(video, VideoAction.RemoveFromHistory, VideoSheetContext.HISTORY)
        assertTrue(f.library.history.value.none { it.item.id == video.id })

        val id = f.library.createPlaylist("Mine")
        f.library.addToPlaylist(id, video)
        f.handler.handle(video, VideoAction.RemoveFromPlaylist, VideoSheetContext.DEFAULT, playlistId = id)
        assertEquals(1, f.library.playlists.value.single().items.size, "only a row of your playlist removes")
        f.handler.handle(video, VideoAction.RemoveFromPlaylist, VideoSheetContext.YOUR_PLAYLIST, playlistId = id)
        assertTrue(f.library.playlists.value.single().items.isEmpty())
    }

    @Test fun aLiveStreamOrAPremiereHasNoSongAndNoDownload() {
        val f = Fixture()
        val live = FakeVideoSource.videos[10]
        val upcoming = FakeVideoSource.videos[11]
        assertTrue(live.isLive && upcoming.isUpcoming)
        for (item in listOf(live, upcoming)) {
            f.handler.handle(item, VideoAction.PlayAsSong, VideoSheetContext.DEFAULT)
            f.handler.handle(item, VideoAction.Download, VideoSheetContext.DEFAULT)
        }
        assertTrue(f.bridge.songs.isEmpty() && f.playback.calls.isEmpty())
        assertNull(f.state.downloadAsk)
    }

    @Test fun downloadsSetTheAskThatTheHostShows() {
        val f = Fixture()
        val video = FakeVideoSource.videos[0]
        f.handler.handle(video, VideoAction.Download, VideoSheetContext.DEFAULT)
        assertEquals(DownloadAsk.One(video), f.state.downloadAsk)
        val info = DownloadBatchInfo("PLfakeplaylist0000000001", BatchKind.PLAYLIST, "A playlist")
        f.handler.downloadBatch(info, FakeVideoSource.videos, latestPicker = false)
        val batch = assertIs<DownloadAsk.Batch>(f.state.downloadAsk)
        assertEquals(info, batch.info)
        assertFalse(batch.latestPicker)
        assertEquals(FakeVideoSource.videos.filter { !it.isLive && !it.isUpcoming }, batch.items, "live streams and premieres are left out")
        f.state.downloadAsk = null
        f.handler.downloadBatch(info, listOf(FakeVideoSource.videos[10]), latestPicker = true)
        assertNull(f.state.downloadAsk, "a batch with nothing to download asks nothing")
    }

    @Test fun aNewPlaylistIsMadeWithTheVideoInIt() {
        val library = FakeVideoLibrary()
        val video = FakeVideoSource.videos[2]
        val id = VideoActionHandler.createPlaylistWith(library, "  Road trip  ", video)
        val made = library.playlists.value.single()
        assertEquals(made.id, id)
        assertEquals("Road trip", made.name)
        assertEquals(listOf(video.id), made.items.map { it.id })
        assertNull(VideoActionHandler.createPlaylistWith(library, "   ", video), "a blank name makes nothing")
        assertEquals(1, library.playlists.value.size)
    }

    @Test fun aToastShowsAgainForASecondCopyAndOnlyItsOwnTimerClearsIt() {
        val state = VideoActionsState()
        state.showToast(ActionToastKind.LINK_COPIED)
        val first = state.toast!!
        state.showToast(ActionToastKind.LINK_COPIED)
        val second = state.toast!!
        assertTrue(first != second, "a second copy is a new toast")
        state.clearToast(first)
        assertEquals(second, state.toast, "the first toast's timer does not clear the second")
        state.clearToast(second)
        assertNull(state.toast)
    }

    @Test fun theHostIsComposedOnceBesideThePageAndShowsWhatTheHandlerAsks() {
        val nav = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shell/VideoNavHost.kt")
        val lines = nav.lines()
        val host = lines.indices.filter { "com.cosmotune.video.ui.actions.VideoActionsHost(contentPadding)" in lines[it] }
        assertEquals(1, host.size, "one action host")
        val lastBranch = lines.indices.last { lines[it].trim().startsWith("is VideoRoute.") }
        assertTrue(host.single() > lastBranch + 3, "outside the `when` and the page, so no branch line of a page task is touched")
        assertTrue(lines[host.single() - 2].isBlank(), "a blank line before it")
        val body = VideoSourceContract.functionBody(action("VideoActionHandler"), "VideoActionsHost")
        assertTrue("DownloadOptionsHost(ask = state.downloadAsk, onDismiss = { state.downloadAsk = null })" in body)
        assertTrue("SaveToPlaylistSheet(" in body && "NewPlaylistDialog(" in body && "ActionToastHost(state, contentPadding)" in body)
    }

    @Test fun theDownloadDeclarationsKeepTheShapesY32BuildsOn() {
        val ask = VideoSourceContract.code(action("DownloadAsk"))
        assertTrue("sealed interface DownloadAsk {" in ask)
        assertTrue("data class One(val item: VideoItem) : DownloadAsk" in ask)
        assertTrue("data class Batch(val info: DownloadBatchInfo, val items: List<VideoItem>, val latestPicker: Boolean) : DownloadAsk" in ask)
        val host = action("DownloadOptionsHost")
        assertEquals(listOf("ask: DownloadAsk?", "onDismiss: () -> Unit"), VideoSourceContract.parameters(host, "DownloadOptionsHost"))
        assertTrue(Regex("""@Composable\s+fun DownloadOptionsHost""").containsMatchIn(host))
        assertTrue(Regex("""@Composable\s+fun rememberDownloadedIds\(\): State<Set<String>>""").containsMatchIn(action("DownloadedIds")))
        assertTrue("downloads.downloads" in action("DownloadedIds"))
    }

    @Test fun theDownloadedIdsAreTheFinishedOnes() {
        val downloads = FakeDownloadController()
        val request = DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null))
        downloads.enqueue(FakeVideoSource.videos[0], request)
        downloads.enqueue(FakeVideoSource.videos[1], request)
        downloads.runUntilIdle()
        downloads.enqueue(FakeVideoSource.videos[2], request)
        assertEquals(setOf(FakeVideoSource.videos[0].id, FakeVideoSource.videos[1].id), downloadedIds(downloads.downloads.value))
        assertEquals(emptySet(), downloadedIds(emptyList()))
    }

    @Test fun shareGoesToTheChooserOnAndroidAndToTheClipboardOnTheDesktop() {
        assertTrue(Regex("""@Composable\s+internal expect fun rememberShareVideo\(onCopied: \(\) -> Unit\): ShareVideo""").containsMatchIn(action("ShareVideo")))
        val android = read("video/src/androidMain/kotlin/com/cosmotune/video/ui/actions/ShareVideo.android.kt")
        assertTrue("Intent(Intent.ACTION_SEND)" in android && "Intent.createChooser(send, null)" in android && "Intent.EXTRA_TEXT, url" in android)
        val desktop = read("video/src/jvmMain/kotlin/com/cosmotune/video/ui/actions/ShareVideo.jvm.kt")
        assertTrue("systemClipboard.setContents(StringSelection(text), null)" in desktop && "if (copyToClipboard(url)) copied()" in desktop)
        assertTrue("onCopied = { state.showToast(ActionToastKind.LINK_COPIED) }" in action("VideoActionHandler"), "the desktop's copy says so in a toast")
    }

    @Test fun theSaveSheetAndTheDialogAreTheBriefs() {
        val sheet = VideoSourceContract.code(action("SaveToPlaylistSheet"))
        assertTrue("SurfaceSheetSwitch(" in sheet && "CosmoPickRow(" in sheet && "items(playlists, key = { it.id })" in sheet)
        assertTrue("CosmoActionRow(icon = CosmoIcons.Plus, label = stringResource(Res.string.video_new_playlist), onClick = onNewPlaylist)" in sheet)
        val dialog = VideoSourceContract.code(action("NewPlaylistDialog"))
        assertTrue("CosmoDialogCard(onDismiss = onDismiss, maxWidth = cardWidth)" in dialog && "BasicTextField(" in dialog)
        assertTrue("Res.string.video_create" in dialog && "enabled = name.isNotBlank()" in dialog)
    }

    // ---- The stubs ---------------------------------------------------------------------------------------------------------

    @Test fun theCommentStubsKeepTheirSignatures() {
        val src = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/comments/CommentsStubs.kt")
        assertEquals(
            listOf("videoId: String", "sheet: Boolean", "onDismiss: () -> Unit", "modifier: Modifier = Modifier"),
            VideoSourceContract.parameters(src, "CommentsPanel"),
        )
        assertEquals(listOf("videoId: String", "onOpen: () -> Unit", "modifier: Modifier = Modifier"), VideoSourceContract.parameters(src, "CommentsPreviewTile"))
        assertTrue("Res.string.video_comments" in VideoSourceContract.functionBody(src, "CommentsPreviewTile"), "the tile says Comments")
    }

    @Test fun theShortsStubsKeepTheirSignaturesAndOpenShortIsFinal() {
        val src = read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shorts/ShortsStubs.kt")
        assertEquals(
            listOf("title: String", "items: List<VideoItem>", "onOpen: (VideoItem) -> Unit", "onSeeAll: (() -> Unit)?", "modifier: Modifier = Modifier"),
            VideoSourceContract.parameters(src, "ShortsShelf"),
        )
        assertEquals(
            listOf(
                "items: List<VideoItem>", "onOpen: (VideoItem) -> Unit", "onLoadMore: () -> Unit", "contentPadding: PaddingValues", "wide: Boolean",
                "modifier: Modifier = Modifier",
            ),
            VideoSourceContract.parameters(src, "ShortsGrid"),
        )
        assertTrue("fun VideoNavigator.openShort(item: VideoItem, origin: ShortsOrigin, list: List<VideoItem>)" in src)
        assertTrue("CosmoSectionHeader(title = title, onShowAll = onSeeAll)" in VideoSourceContract.functionBody(src, "ShortsShelf"))
        val open = VideoSourceContract.functionBody(src, "openShort")
        assertTrue("ShortsSeedCache.put(seed)" in open && "push(VideoRoute.Shorts(origin, item.id, key))" in open)
    }

    // ---- Branding ----------------------------------------------------------------------------------------------------------

    private class GatedProvider(private val branding: Branding?) : BrandingProvider {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var most = 0
        val asked = mutableListOf<String>()
        override suspend fun branding(videoId: String, durationMs: Long?): Branding? {
            asked += videoId
            inFlight += 1
            most = maxOf(most, inFlight)
            gate.await()
            inFlight -= 1
            return branding
        }
    }

    @Test fun brandingAsksAtMostFourAtOnceAndEachVideoOnce() = runTest {
        val provider = GatedProvider(Branding("A better title", 12_000, "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=x&time=12"))
        val ids = (1..10).map { "fakevideo%02d".format(it) }
        val jobs = ids.map { id -> launch { BrandingLookups.lookup(provider, id, null) { true } } }
        runCurrent()
        assertEquals(BrandingLookups.MAX_IN_FLIGHT, provider.inFlight, "four lookups run, the others wait")
        assertEquals(4, BrandingLookups.MAX_IN_FLIGHT)
        provider.gate.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(4, provider.most)
        assertEquals(ids, provider.asked)
        BrandingLookups.lookup(provider, ids[0], null) { error("the picture was tried already") }
        assertEquals(10, provider.asked.size, "a cached answer asks nothing")
        val known = BrandingLookups.state(ids[0])
        assertTrue(known.known && known.pictureOk == true && known.branding?.title == "A better title")
        assertTrue(known.complete(checkPicture = true))
        assertFalse(BrandingLookups.state("fakevideo99").known)
    }

    @Test fun aFailedLookupIsNoneAndAFailedPictureFallsBack() = runTest {
        val failing = object : BrandingProvider {
            override suspend fun branding(videoId: String, durationMs: Long?): Branding? = throw IllegalStateException("offline")
        }
        BrandingLookups.lookup(failing, "fakevideo01", null) { true }
        assertTrue(BrandingLookups.state("fakevideo01").known)
        assertNull(BrandingLookups.state("fakevideo01").branding)
        val provider = FakeBrandingProvider(Branding(null, 3_000, "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=y&time=3"))
        BrandingLookups.lookup(provider, "fakevideo02", null) { false }
        assertEquals(false, BrandingLookups.state("fakevideo02").pictureOk, "a 204 or an error: the original picture stays")
    }

    @Test fun brandingIsLookedUpOnlyWhileTheItemIsComposed() {
        val src = VideoSourceContract.code(read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/branding/RememberBranding.kt"))
        assertTrue("LaunchedEffect(item.id, provider, checkPicture)" in src, "keyed on the id: started with the card, cancelled with it")
        assertTrue("delay(BrandingLookups.SETTLE_MS)" in src, "an item that only flashes by asks nothing")
        assertTrue("private val semaphore = Semaphore(MAX_IN_FLIGHT)" in src)
        assertTrue(Regex("""@Composable\s+fun rememberVideoTitle\(item: VideoItem\): String""").containsMatchIn(src))
        assertTrue(Regex("""@Composable\s+fun rememberVideoThumbnail\(item: VideoItem\): String\?""").containsMatchIn(src))
        assertTrue(".memoryCachePolicy(CachePolicy.DISABLED)" in src && "!is ErrorResult" in src, "the picture is tried through the image loader")
    }

    @Test fun theTitleShownIsDeArrowsCasedOrTheOriginal() {
        assertEquals("THE ORIGINAL", shownTitle("THE ORIGINAL", "a better one", on = false, TitleCase.SENTENCE), "off: the original as it is")
        assertEquals("A better one", shownTitle("x", ">A better one", on = true, TitleCase.AS_GIVEN), "the marker goes")
        assertEquals("The original title", shownTitle("THE ORIGINAL TITLE", null, on = true, TitleCase.SENTENCE), "on: the original is cased too")
        assertEquals("Odd >title", shownTitle("Odd >title", null, on = true, TitleCase.AS_GIVEN), "as given: the original is left alone")
    }

    // ---- The look of the new files --------------------------------------------------------------------------------------------

    private val uiFiles: List<Pair<String, String>>
        get() = listOf(
            "$actions/VideoActionHandler.kt", "$actions/SaveToPlaylistSheet.kt", "$actions/NewPlaylistDialog.kt", "$actions/DownloadOptionsHost.kt",
            "$actions/DownloadedIds.kt", "$actions/DownloadAsk.kt", "$actions/ShareVideo.kt",
            "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/branding/RememberBranding.kt",
            "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/comments/CommentsStubs.kt",
            "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shorts/ShortsStubs.kt",
        ).map { it to VideoSourceContract.code(read(it)) }

    @Test fun theNewFilesPickNoColourByHandAndReadLiveColoursOnlyWhileDrawing() {
        for ((path, code) in uiFiles) {
            assertFalse(Regex("""Color\.(Black|White)|Color\(0x""").containsMatchIn(code), "$path: colours come from the palette")
            for (line in code.lines().filter { "live(" in it }) assertTrue("drawBehind" in line || "drawWithCache" in line || "drawWithContent" in line, "$path: live colours only while drawing: $line")
            assertFalse("positionMs()" in code, "$path: no playhead")
            assertFalse(Regex("""(?i)youtube\b""").containsMatchIn(code.replace("returnyoutubedislike", "")), "$path: no source name")
        }
    }

    @Test fun theOnlyNewStringIsTheCopiedToast() {
        val strings = read("video/src/commonMain/composeResources/values/strings.xml")
        assertTrue("<string name=\"video_link_copied\">Link copied</string>" in strings)
    }
}
