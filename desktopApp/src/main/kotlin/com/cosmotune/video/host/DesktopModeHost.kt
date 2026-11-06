package com.cosmotune.video.host

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.desktop.LocalCosmoPlayPause
import com.cosmotune.desktop.LocalCosmoPlaying
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.modes.ModeLinks
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.graph.stopVideoForMusic
import com.cosmotune.video.ui.PendingVideo
import com.cosmotune.video.ui.VideoHostLinks
import com.cosmotune.video.ui.desktop.VideoDesktopPage
import com.cosmotune.music.data.model.Song

/**
 * The desktop's side of Video mode (hook table rows 8 and 9). The window keeps Music's frame; while the stored mode is Video
 * ([desktopVideoShown]) the page, the sidebar, the top bar's back button and drawer entries and the keys follow it, and the music
 * player bar and side panel stand aside. [active] is the same fact for code that is not composable (the window's key handler), kept up
 * to date by [desktopVideoShown].
 */
object DesktopModeHost {
    /** True while Video is the mode that is drawn. Written by [desktopVideoShown] from a side effect; read by the key handler. */
    @Volatile
    var active: Boolean = false
    @Volatile var mangaActive: Boolean = false
    @Volatile var podioActive: Boolean = false
    @Volatile var watchActive: Boolean = false

    /**
     * A video waiting to be opened, set by the host when Music asks for one ("Watch the music video", Y-26). It is snapshot state, so a
     * request that arrives while Video is open is noticed; [DesktopVideoPage]'s links read it and clear it when the page is opened.
     */
    var askedVideo: PendingVideo? by mutableStateOf(null)
}

/** "Play this song" in Music, provided by the desktop root ([rememberStableSongAction], Y-26); null where nothing provides it. */
val LocalDesktopPlaySong = staticCompositionLocalOf<((Song) -> Unit)?> { null }

/**
 * Whether Video is the mode that is drawn: the new look is on (there is a shell), the stored mode is Video and Video is available
 * ([AppModes.available]); the phone's host uses the same rule. It reads the stored mode, so a composable that calls it recomposes when
 * the mode changes, and it keeps [DesktopModeHost.active] equal to the answer.
 */
@Composable
fun desktopVideoShown(): Boolean {
    val shell = LocalCosmoShell.current
    val mode = shell?.settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC
    val shown = shell != null && mode == AppMode.VIDEO && AppModes.available(AppMode.VIDEO)
    // A stored mode that does not work (Video, after Video was switched off again) becomes Music: Music's own chrome draws only for the
    // stored mode, so without this the Music window would stand without its bottom bar.
    if (shell != null && !AppModes.available(mode)) LaunchedEffect(mode) { shell.settings.setMode(AppMode.MUSIC) }
    SideEffect { DesktopModeHost.active = shown }
    return shown
}

/** Manga and Read leave the music service running but own page navigation and keyboard input. */
@Composable
fun desktopMangaShown(): Boolean {
    val shell = LocalCosmoShell.current
    val mode = shell?.settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC
    val shown = shell != null && (mode == AppMode.MANGA || mode == AppMode.READ) && AppModes.available(mode)
    SideEffect { DesktopModeHost.mangaActive = shown }
    return shown
}

@Composable fun desktopPodioShown(): Boolean {
    val mode = LocalCosmoShell.current?.settings?.mode?.collectAsState()?.value
    val shown = mode == AppMode.PODIO
    SideEffect { DesktopModeHost.podioActive = shown }
    return shown
}

/** Watch (films, series, live TV) owns the page, the sidebar and the keys; the music player bar and side panel stand aside. */
@Composable fun desktopWatchShown(): Boolean {
    val shell = LocalCosmoShell.current
    val mode = shell?.settings?.mode?.collectAsState()?.value
    val shown = shell != null && mode == AppMode.WATCH && AppModes.available(AppMode.WATCH)
    SideEffect { DesktopModeHost.watchActive = shown }
    return shown
}

/**
 * Watch's page in the window's content area. The sidebar carries its tabs ([com.cosmotune.ui.watch.WatchNav]); the repository is
 * the desktop's real one ([com.cosmotune.desktop.DesktopWatch.repository], also installed as [com.cosmotune.ui.watch.WatchBackend]'s),
 * and QR codes are drawn with the desktop's zxing.
 */
@Composable
fun DesktopWatchPage(contentPadding: PaddingValues) {
    val repository = remember { com.cosmotune.desktop.DesktopWatch.repository.also(com.cosmotune.ui.watch.WatchBackend::install) }
    val playback = rememberDesktopWatchPlayback()
    val downloads = remember { com.cosmotune.desktop.DesktopWatch.downloads }
    // In a room, the Plugins page offers this member's add-ons in its chat and lists the packs the others offered.
    val joined by com.cosmotune.music.desktop.DesktopRooms.joined.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val roomPacks = if (joined) remember(scope) {
        com.cosmotune.ui.watch.WatchRoomPacks.of(com.cosmotune.music.desktop.DesktopRooms.room, { com.cosmotune.music.desktop.DesktopRooms.selfUid() },
            { text -> com.cosmotune.music.desktop.DesktopRooms.sendChat(text) }, scope)
    } else null
    val linkHandler = remember {
        if (com.cosmotune.desktop.DesktopWatchLinks.available) com.cosmotune.ui.watch.WatchLinkHandler(com.cosmotune.desktop.DesktopWatchLinks::isSet, com.cosmotune.desktop.DesktopWatchLinks::set) else null
    }
    com.cosmotune.ui.watch.ProvideWatch(repository = repository, playback = playback, downloads = downloads, roomPacks = roomPacks, linkHandler = linkHandler,
        qrCode = { payload, size -> com.cosmotune.music.desktop.DesktopQrCode(payload, size) }) {
        // A compact window has no sidebar: the page then carries its own tabs, as on the phone.
        androidx.compose.foundation.layout.BoxWithConstraints {
            val compact = maxWidth < com.cosmotune.design.ShellMetrics.DESKTOP_COMPACT_DP.dp
            com.cosmotune.ui.watch.WatchApp(contentPadding = contentPadding, showNavigation = compact)
        }
    }
}

/**
 * Watch's player on the desktop: [com.cosmotune.desktop.DesktopWatch.player] on Video mode's libmpv engine, built the first time a
 * player page opens (so browsing Watch never starts Video's player), and its picture drawn with the engine's own surface, as Video's
 * watch page draws it. Audio stays one at a time: Watch starting pauses Music, and Music starting pauses Watch.
 */
@Composable
private fun rememberDesktopWatchPlayback(): com.cosmotune.ui.watch.WatchPlayback? {
    val stack by com.cosmotune.ui.watch.WatchNav.stack.collectAsState()
    val wanted = stack.any { it is com.cosmotune.ui.watch.WatchRoute.Play }
    val player = remember(wanted) { if (wanted || com.cosmotune.desktop.DesktopWatch.playerBuilt) com.cosmotune.desktop.DesktopWatch.playerOrNull() else null }
    val musicPlaying = rememberUpdatedState(LocalCosmoPlaying.current)
    val playPause = rememberUpdatedState(LocalCosmoPlayPause.current)
    if (player != null) {
        LaunchedEffect(player) {
            player.engine.snapshot.collect { if (it.playing && musicPlaying.value) playPause.value?.invoke() }
        }
        val music = LocalCosmoPlaying.current
        LaunchedEffect(music) { if (music && player.engine.snapshot.value.playing) player.engine.pause() }
    }
    return remember(player) {
        player?.let { p ->
            com.cosmotune.ui.watch.WatchPlayback(p, roomHolds = { key -> com.cosmotune.music.desktop.DesktopRoomWatch.holds(key.value) }) { scale, modifier ->
                com.cosmotune.video.graph.desktopVideoGraph(build = false)?.surface?.Surface(modifier,
                    if (scale == com.cosmotune.ui.watch.WatchSurfaceScale.CROP) com.cosmotune.video.player.VideoScale.FILL else com.cosmotune.video.player.VideoScale.FIT)
            }
        }
    }
}

@Composable
fun DesktopMangaPage(contentPadding: PaddingValues) {
    val shell = LocalCosmoShell.current ?: return
    val mode = shell.settings.mode.collectAsState().value
    // The sidebar carries the tabs on the desktop, so the pages draw no dock or rail of their own.
    if (mode == AppMode.READ) com.cosmotune.reading.ui.ReadApp(contentPadding = contentPadding, onMusic = { shell.settings.setMode(AppMode.MUSIC) }, showNavigation = false)
    else com.cosmotune.manga.ui.MangaApp(contentPadding, onMusic = { shell.settings.setMode(AppMode.MUSIC) }, showNavigation = false)
}

/**
 * Video's page in the window's content area, with the desktop's links: leave for Music, "play as a song" (the mode becomes Music, then
 * Music plays the song if the root has provided [LocalDesktopPlaySong]), pause Music when a video starts and the video that Music asked
 * for. The playing flag and the actions are read in composition and kept current, so a link used later sees the player as it is then.
 *
 * When the page opens the video Music asked for ("Watch the music video"), it pauses Music at once through [LocalCosmoPlayPause],
 * before the video has loaded: the links of [rememberDesktopModeLinks] are built in the root's provider list, where that local is not
 * provided yet, so the pause happens here, as the request is handled.
 */
@Composable
fun DesktopVideoPage(contentPadding: PaddingValues) {
    val shell = LocalCosmoShell.current ?: return
    val playing = rememberUpdatedState(LocalCosmoPlaying.current)
    val playPause = rememberUpdatedState(LocalCosmoPlayPause.current)
    val playSong = rememberUpdatedState(LocalDesktopPlaySong.current)
    val links = remember(shell) {
        VideoHostLinks(
            switchToMusic = { shell.settings.setMode(AppMode.MUSIC) },
            playAsSong = { id, title, channel, thumbnailUrl ->
                shell.settings.setMode(AppMode.MUSIC)
                playSong.value?.invoke(Song(videoId = id, title = title, artist = channel, thumbnailUrl = thumbnailUrl, isVideo = true))
            },
            pauseMusic = { if (playing.value) playPause.value?.invoke() },
            pendingVideo = { DesktopModeHost.askedVideo },
            onPendingHandled = {
                DesktopModeHost.askedVideo = null
                // Only Music's "Watch the music video" sets the asked video: Music pauses as its video's page opens.
                if (playing.value) playPause.value?.invoke()
            },
        )
    }
    // Audio is exclusive across the modes: Music starting while a video plays (the system's media controls can start it) stops the
    // video; a video starting pauses Music through `pauseMusic` above.
    val musicPlaying = LocalCosmoPlaying.current
    LaunchedEffect(musicPlaying) { if (musicPlaying) stopVideoForMusic() }
    VideoDesktopPage(contentPadding, links)
}

/**
 * Music's way into Video on the desktop ("Watch the music video", spec section 9), for the root's provider list (hook table row 10):
 * null while the new look is off (no shell) or Video is not available, so Music draws no such tool. One identity per shell, so the
 * static local changes only when the look does.
 */
@Composable
fun rememberDesktopModeLinks(): ModeLinks? {
    val shell = LocalCosmoShell.current
    val links = remember(shell) { shell?.let { desktopModeLinks(it.settings) } }
    return links?.takeIf { AppModes.available(AppMode.VIDEO) }
}

/**
 * "Watch the music video" keeps the video and its moment in [DesktopModeHost.askedVideo], then makes Video the mode; Video's page opens
 * it as it appears and pauses Music then ([DesktopVideoPage]).
 */
internal fun desktopModeLinks(settings: CosmoLookSettings): ModeLinks = object : ModeLinks {
    override fun watchMusicVideo(videoId: String, title: String, artist: String, atVideoMs: Long?) {
        DesktopModeHost.askedVideo = PendingVideo(videoId, atVideoMs)
        settings.setMode(AppMode.VIDEO)
    }
}

/**
 * The root's "play this song" for [LocalDesktopPlaySong] (hook table row 10): one identity for as long as it stays in the composition,
 * always calling the latest [action], so the static local never invalidates its readers (the song version of the desktop's
 * `rememberStableAction`).
 */
@Composable
fun rememberStableSongAction(action: (Song) -> Unit): (Song) -> Unit {
    val latest = rememberUpdatedState(action)
    return remember { { song: Song -> latest.value(song) } }
}
