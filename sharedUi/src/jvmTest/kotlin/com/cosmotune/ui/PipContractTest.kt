package com.cosmotune.ui

import com.cosmotune.ui.watch.channelAfter
import com.cosmotune.ui.watch.watchPipReady
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.WatchKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picture-in-picture wherever a video plays: Video, Watch (films, series, live TV) and Now Playing's music video share one
 * picture-in-picture per window, decided in one place (`PipDecision`), reached by Music only through sharedUi's hook. Nothing of it
 * can be seen without a window and a system, so the sources are read, and the pure parts are run.
 */
class PipContractTest {
    private fun read(path: String) = SourceContract.read(path)
    private fun code(path: String) = SourceContract.code(read(path))
    private fun flat(path: String) = code(path).replace(Regex("\\s+"), " ")

    private val pipDir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/pip/"
    private val android = "sharedUi/src/androidMain/kotlin/com/cosmotune/ui/pip/Pip.android.kt"
    private val desktop = "sharedUi/src/jvmMain/kotlin/com/cosmotune/ui/pip/Pip.jvm.kt"
    private val nowPlaying = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/CosmoNowPlaying.kt"
    private val nowPlayingPip = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingPip.kt"
    private val watchPage = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchPlayerPage.kt"
    private val watchPip = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchPip.kt"
    private val tvPlayer = "tv/src/main/java/com/cosmotune/music/ui/tv/player/TvWatchPlayer.kt"
    private val videoAndroid = "video/src/androidMain/kotlin/com/cosmotune/video/player/Pip.android.kt"
    private val videoFullscreen = "video/src/androidMain/kotlin/com/cosmotune/video/ui/watch/FullscreenController.android.kt"
    private val videoWindow = "video/src/jvmMain/kotlin/com/cosmotune/video/player/DesktopPip.kt"
    private val videoHost = "video/src/jvmMain/kotlin/com/cosmotune/video/player/DesktopPipHost.kt"
    private val desktopHost = "desktopApp/src/main/kotlin/com/cosmotune/video/host/DesktopModeHost.kt"

    /** Video's package, spelled in two parts so this test is not one more file that names it (docs/MAINTAINING.md's removal list). */
    private val videoPackage = "com.cosmotune." + "video"

    /** Every Kotlin file of the main code (no tests, no build output), path from the root with forward slashes. */
    private val mainSources: List<String> by lazy {
        SourceContract.root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(".") && it.name != "node_modules" }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(SourceContract.root).invariantSeparatorsPath }
            // the main source sets only: no `test`, `jvmTest`, `androidTest` or other test set
            .filter { "/src/" in it && "test" !in it.substringAfter("/src/").substringBefore("/").lowercase() }
            .toList()
    }

    // ---- the hook rule ---------------------------------------------------------------------------------------------------------

    @Test fun musicReachesPictureInPictureOnlyThroughSharedUisHookAndNeverNamesVideo() {
        val files = File(SourceContract.root, pipDir).listFiles()!!.map { pipDir + it.name } +
            listOf(android, desktop, nowPlaying, nowPlayingPip, watchPip, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingVideo.kt")
        for (path in files) assertFalse(videoPackage in code(path), "$path names Video")
        assertTrue("val pip = rememberPip(PipOwner.MUSIC_VIDEO)" in flat(nowPlaying), "Now Playing asks the shared hook as the music video")
        assertTrue("import com.cosmotune.ui.pip.rememberPip" in read(nowPlaying))
    }

    @Test fun theDesktopWindowIsInstalledByTheHostLayerWhichMayKnowBoth() {
        val links = SourceContract.functionBody(read(desktopHost), "rememberDesktopModeLinks")
        assertTrue("$videoPackage.player.InstallDesktopPip()" in links, "the root's provider list installs Video's window for every mode")
        assertTrue("DesktopPipBoard.host = DesktopPipWindows" in flat(videoHost))
        val handle = flat(desktop)
        assertTrue("val opened = host.open(owner) { board.wish(owner) }" in handle, "Watch and Music open the window through the host")
        assertTrue("override val supported: Boolean get() = DesktopPipBoard.host != null && !DesktopPipBoard.headless" in handle)
    }

    // ---- one place for the decision --------------------------------------------------------------------------------------------

    @Test fun onlyTheSharedPictureInPictureTalksToTheSystemAndOnlyTheBoardDecides() {
        for (call in listOf("enterPictureInPictureMode(", "setPictureInPictureParams(", "addOnUserLeaveHintListener(")) {
            val callers = mainSources.filter { call in code(it) }
            assertEquals(listOf(android), callers, "$call is made in one place")
        }
        val deciders = mainSources.filter { "PipDecision.owner(" in code(it) }
        assertEquals(listOf(pipDir + "PipCoordinator.kt"), deciders, "the board alone asks the decision")
    }

    @Test fun everyPlayerGoesThroughTheBoardUnderItsOwnName() {
        assertTrue("ActivityPip.handle(activity, PipOwner.VIDEO)" in flat(videoAndroid), "Video's phone handle is the shared one")
        assertTrue("ActivityPip.of(activity).enter(PipOwner.VIDEO)" in flat(videoFullscreen), "Video's button asks the board")
        assertTrue("DesktopPipBoard.board.ask(PipOwner.VIDEO)" in flat(videoWindow) && "DesktopPipBoard.opened(PipOwner.VIDEO)" in flat(videoWindow))
        assertTrue("DesktopPipBoard.closed(returnToApp)" in flat(videoWindow), "the window tells the board when it goes")
        assertTrue("val pip = com.cosmotune.ui.pip.rememberPip(com.cosmotune.ui.pip.PipOwner.WATCH)" in flat(watchPage))
        assertTrue("val pip = rememberPip(PipOwner.WATCH)" in flat(tvPlayer), "the TV's own Watch player")
    }

    // ---- the phone's and the TV's window ---------------------------------------------------------------------------------------

    @Test fun theActivitysWindowFollowsTheHolderAutoEntersAndCarriesItsButtons() {
        val text = flat(android)
        assertTrue("val wish = board.current()" in text, "the parameters are the holder's")
        assertTrue("builder.setAutoEnterEnabled(wish?.ready == true)" in text, "Android 12 and later enter by themselves while the holder is ready")
        assertTrue("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || inPip) return@Runnable" in text && "if (wish.ready) params?.let" in text,
            "Android 8 to 11 enter from the leave hint")
        assertTrue("Rational(239, 100)" in text && "Rational(100, 239)" in text, "the shape clamped to between 1:2.39 and 2.39:1")
        assertTrue("ContextCompat.RECEIVER_NOT_EXPORTED" in text && ".setPackage(activity.packageName)" in text && "PendingIntent.FLAG_IMMUTABLE" in text,
            "the buttons are broadcasts to this app alone")
        assertTrue("activity.maxNumPictureInPictureActions" in text && "add(CONTROL_PLAY_PAUSE)" in text, "play and pause first when the window takes few")
        assertTrue("removeOnPictureInPictureModeChangedListener(modeListener)" in text && "runCatching { activity.unregisterReceiver(receiver) }" in text)
        assertTrue("Lifecycle.Event.ON_RESUME -> settleLeaving(closed = false)" in text && "Lifecycle.Event.ON_STOP -> settleLeaving(closed = true)" in text,
            "expanded when the activity resumes, closed when it stops")
        assertTrue("if (closed) wish?.onClosed?.invoke() else wish?.onExpanded?.invoke()" in text)
    }

    @Test fun inPictureInPictureThePagesShowThePictureAlone() {
        assertTrue(
            "inPip && pipHandle?.manual != true -> Box(Modifier.fillMaxSize().background(Color.Black)) { picture(Modifier.fillMaxSize(), 0.dp) }" in flat(watchPage),
            "Watch: the picture alone, no card, no rows",
        )
        val np = flat(nowPlaying)
        val alone = np.indexOf("if (pip.active && !pip.manual && musicVideo && canvas != null) { NowPlayingPipPicture(")
        assertTrue(alone > 0 && alone < np.indexOf("CompositionLocalProvider(LocalCosmoColors provides colors"), "Now Playing: the video alone, before the page")
        assertTrue("glow = false" in flat(nowPlayingPip) && "view = VideoView.WHOLE" in flat(nowPlayingPip), "whole, nothing round it")
        assertTrue("if (pip.active) { Box(modifier.fillMaxSize().background(Color.Black)) { playback.surface(scale, Modifier.fillMaxSize()) } return }" in flat(tvPlayer),
            "the TV: the picture alone")
        // the desktop's page stands aside while its small window holds the picture
        assertTrue("val wantVideo = musicVideo && !videoHidden && settled && !videoAway" in np, "the record while the window has the video")
        assertTrue("if (inPip && pipHandle?.manual == true) { WatchPipStandIn(" in flat(watchPage), "Watch's stand-in while the window has the film")
    }

    @Test fun theButtonsAndWhatClosingDoes() {
        val watch = flat(watchPip)
        assertTrue("state.isLive -> nextChannel" in watch && "state.nextEpisode != null -> player::playNextEpisode" in watch, "next: the channel or the episode")
        assertTrue("onClosed = { player.engine.pause() }" in watch, "closing pauses the film")
        val song = flat(nowPlayingPip)
        assertTrue("controls = PipControls(playPause = onPlayPause, previous = onPrevious, next = onNext)" in song)
        assertFalse("onClosed" in song || "pause" in song.lowercase().replace("playpause", ""), "closing leaves the music playing, as audio only")
        assertTrue("onClose = { if (playback.state.value.playing) playback.togglePlayPause() }" in flat(videoWindow), "Video pauses when its window is closed")
        assertTrue("WatchRoundButton(CosmoIcons.Pip, \"Picture in picture\"" in flat(watchPage), "Watch's player controls have the button")
        assertTrue("TvWatchRound(CosmoIcons.Pip, \"Picture in picture\", ink, it)" in flat(tvPlayer), "and the TV's card")
        assertTrue("pictureInPicture = if (pip.supported && castRenderer == null)" in flat(watchPage), "not while the film plays on a cast receiver")
    }

    // ---- the manifests ---------------------------------------------------------------------------------------------------------

    @Test fun theTvActivitySupportsPictureInPictureAndThePhoneStillDoes() {
        val tv = read("tv/src/main/AndroidManifest.xml")
        val activity = tv.substringAfter("android:name=\".TvActivity\"").substringBefore("/>")
        assertTrue("android:supportsPictureInPicture=\"true\"" in activity, "the TV activity")
        assertTrue("android:resizeableActivity=\"true\"" in activity)
        assertEquals(1, Regex(Regex.escape("android:supportsPictureInPicture=\"true\"")).findAll(tv).count(), "on the activity alone, not the alias")
        val phone = read("app/src/main/AndroidManifest.xml")
        assertTrue("android:supportsPictureInPicture=\"true\"" in phone.substringAfter("android:name=\".MainActivity\"").substringBefore(">"))
    }

    // ---- the pure parts --------------------------------------------------------------------------------------------------------

    private fun channel(id: String) = Channel(key = WatchKey("c:p:$id"), pluginId = "p", name = id)

    @Test fun theNextChannelIsTheOneAfterInItsPlaylistRoundToTheFirst() {
        val list = listOf(channel("a"), channel("b"), channel("c"))
        assertEquals("b", channelAfter(list, WatchKey("c:p:a"))?.name)
        assertEquals("a", channelAfter(list, WatchKey("c:p:c"))?.name, "round from the last to the first")
        assertEquals("a", channelAfter(list, WatchKey("c:p:z"))?.name, "a channel not in the list: the first")
        assertNull(channelAfter(listOf(channel("a")), WatchKey("c:p:a")), "no other channel")
        assertNull(channelAfter(emptyList(), WatchKey("c:p:a")))
    }

    @Test fun watchIsReadyWhileItsFilmPlaysHere() {
        assertTrue(watchPipReady(local = true, playing = true, status = WatchPlayerStatus.PLAYING))
        assertTrue(watchPipReady(true, true, WatchPlayerStatus.BUFFERING))
        assertFalse(watchPipReady(true, false, WatchPlayerStatus.PAUSED), "not while paused")
        assertFalse(watchPipReady(false, true, WatchPlayerStatus.PLAYING), "not while it plays on a cast receiver")
        assertFalse(watchPipReady(true, true, WatchPlayerStatus.ERROR))
        assertFalse(watchPipReady(true, true, WatchPlayerStatus.ENDED))
    }
}
