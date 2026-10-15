package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.NowPlayingLayout
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.player.AudioOutputSheet
import com.music.bitchord.ui.player.AudioPipelineDialog
import com.music.bitchord.ui.player.CanvasSyncSource
import com.music.bitchord.ui.player.ListenTogetherMembersSheet
import com.music.bitchord.ui.player.KeepScreenOn
import com.music.bitchord.ui.player.LyricsOffsetSheet
import com.music.bitchord.ui.player.NEXT_CANVAS_PREFETCH_DELAY_MS
import com.music.bitchord.ui.player.PlayerBackHandler
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.player.PlayerSettings
import com.music.bitchord.ui.player.rememberCanvasArtwork
import com.music.bitchord.ui.player.rememberCanvasBlockedByMobileData
import com.music.bitchord.ui.player.rememberControlsLocked
import com.music.bitchord.ui.player.rememberOutputPicker
import com.music.bitchord.ui.player.rememberPlayerScrub
import com.music.bitchord.ui.player.rememberRemoteArtworkUrl
import com.music.bitchord.ui.theme.StatusBarIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The new Now Playing (portrait first): the cover on the ambient ground, which gives way to the song's video when it has one;
 * the title, the artist, the wavy scrubber, the transport and the four tools over it. It takes [NowPlayingScreen]'s parameters,
 * name for name, so [NowPlayingSwitch] can hand every one over; the ones only the lyrics and queue panels (Task 10) and the
 * landscape and wide layouts (Tasks 10 and 11) use are accepted here and not yet read.
 *
 * What it keeps from the old screen, in the old screen's order: the song's artwork address, the clip's clock
 * ([CanvasSyncSource]), the clip and its mobile-data guard, the prefetch of the next song's clip, the scrub state, the party
 * lock, the output picker and the panels' flags, restored from and written back to the last-screen setting. What it adds:
 * [reveal], the one number (0 = cover, 1 = video) the cover's trip, the scrims, the clip's alpha, the chip slot and the status
 * bar icons all follow.
 */
@Composable
fun CosmoNowPlaying(
    song: Song,
    playedBy: String? = null,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    audioVersionSwitching: Boolean,
    qualityUpgraded: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    accountName: String?,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBlockedControl: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit = {},
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    onOpenPlaybackSource: () -> Unit,
    onListenTogether: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    windowWidth: Dp,
    windowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val shell = LocalCosmoShell.current
    val reduced = LocalReducedMotion.current

    // ---- what the old screen sets up first, in its order ----------------------------------------------------------------
    // Remote tracks whose art lives inside the file resolve it here, once; every surface reads the same value.
    val remoteArt = rememberRemoteArtworkUrl(song)
    // A canvas that follows the song (an OpenCanvas music video) reads the same playhead; set while composing, not in an
    // effect, so a clip mounted in this pass already sees it.
    remember(position) { CanvasSyncSource.attach(position) }
    DisposableEffect(position) { onDispose { CanvasSyncSource.detach(position) } }
    val canvas = rememberCanvasArtwork(song)
    // True only while the mobile-data guard is the reason there is no video; the top bar then carries the host's line that says so.
    val blockedByMobileData = rememberCanvasBlockedByMobileData(song)
    // The next song's clip is looked up while this one plays, so skipping to it shows the video at once. Same guards as the
    // current song's lookup; the answer lands in the host's cache and is picked up from there.
    val prefetchCanvasEnabled by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val prefetchOverCellular by PlayerSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val prefetchMetered by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    LaunchedEffect(song.videoId, queueIndex, queue.size, prefetchCanvasEnabled) {
        if (!prefetchCanvasEnabled || (prefetchMetered == true && !prefetchOverCellular)) return@LaunchedEffect
        val upNext = queue.getOrNull(queueIndex + 1)?.takeIf { it.videoId != song.videoId } ?: return@LaunchedEffect
        // Behind the current song's own lookup, which the hosts serialise anyway.
        delay(NEXT_CANVAS_PREFETCH_DELAY_MS)
        PlayerPlatform.host.canvasFor(upNext)
    }
    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    val controlsLocked = rememberControlsLocked()
    val inParty = rememberInParty()
    val sheets = remember { NowPlayingSheetFlags() }
    val openAudioOutput = rememberOutputPicker { sheets.output = true }
    val panels = remember { NowPlayingPanels(PlayerSettings.lastPlayerScreen.value) }
    LaunchedEffect(panels.lyricsOpen, panels.queueOpen) { PlayerSettings.setLastPlayerScreen(panels.screen) }
    KeepScreenOn(enabled = panels.lyricsOpen)
    PlayerBackHandler(enabled = panels.lyricsOpen) { panels.closeLyrics() }
    PlayerBackHandler(enabled = panels.queueOpen) { panels.closeQueue() }

    // ---- the video, and the one number that follows it -------------------------------------------------------------------
    // Reduced motion: the video does not start by itself; the person turns it on.
    var videoHidden by rememberSaveable { mutableStateOf(reduced) }
    // The video is wanted when a canvas exists, the person has not hidden it, and the player's morph has settled. Read in
    // a derived state: the progress moves every frame of the morph, the answer changes once.
    val settled by remember(shell) { derivedStateOf { shell?.playerSettled != false } }
    val wantVideo = canvas != null && !videoHidden && settled
    // Keyed on the clip (a version switch that keeps it carries this through) and on whether it is wanted at all: a clip that
    // is mounted again has to show a frame again before the cover gives way.
    var rendered by remember(canvas?.url, wantVideo) { mutableStateOf(false) }
    val videoShown = wantVideo && rendered
    // 0 = the cover, 1 = the video all the way in. A State, read only in layer, draw and layout lambdas.
    val reveal = animateFloatAsState(if (videoShown) 1f else 0f, CosmoMotion.slow(reduced), label = "reveal")

    // Dark over a video in both themes: see [rememberPlayerColors].
    val colors = rememberPlayerColors(shell, hasVideo = canvas != null, videoShown = videoShown, reduced = reduced)
    val lightTheme = shell != null && !LocalCosmoColors.current.dark

    // ---- what the page is made of ----------------------------------------------------------------------------------------
    val playerHaze = remember { HazeState() }
    val stackHeightPx = remember { mutableIntStateOf(0) }
    val chipOrigin = remember { mutableStateOf<Offset?>(null) }
    val openOrigin: () -> Unit = {
        // The caption's way back, as the old one's: the party for a song someone else queued, the queue for a queue-built
        // session, and otherwise the album or playlist the session was started from.
        when {
            playedBy != null -> onListenTogether()
            song.playbackSourceType == PlaybackSourceType.QUEUE -> panels.openQueue()
            else -> onOpenPlaybackSource()
        }
    }
    val page: @Composable BoxScope.() -> Unit = {
        NowPlayingBackdrop(
            canvas = canvas, wantVideo = wantVideo, isPlaying = isPlaying, reveal = reveal, lyricsOpen = panels.lyricsOpen,
            onRenderedChanged = { rendered = it }, hazeState = playerHaze, modifier = Modifier.fillMaxSize(),
        )
        NowPlayingStack(
            song = song, coverUrl = remoteArt, hasVideo = canvas != null, reveal = reveal,
            onChipPlaced = { chipOrigin.value = it },
            isPlaying = isPlaying, isLoading = isLoading, position = position, durationMs = durationMs, scrub = scrub,
            onSeekFraction = onSeekFraction, liked = likeStatus == LikeStatus.LIKE, onToggleLike = onToggleLike,
            onOpenMenu = onOpenMenu, onOpenArtist = onOpenArtist, controlsLocked = controlsLocked,
            hasPrevious = hasPrevious, hasNext = hasNext, onPrevious = onPrevious, onNext = onNext,
            onBlockedControl = onBlockedControl, onPlayPause = onPlayPause, shuffleEnabled = shuffleEnabled,
            onToggleShuffle = onToggleShuffle, repeatMode = repeatMode, onCycleRepeat = onCycleRepeat,
            lyricsOpen = panels.lyricsOpen, onToggleLyrics = panels::toggleLyrics, onOpenOutput = openAudioOutput,
            onTogether = { if (inParty) sheets.members = true else onListenTogether() },
            queueOpen = panels.queueOpen, onToggleQueue = panels::toggleQueue,
            // Measured with its own padding, so the cover knows how much of the window the stack takes.
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { stackHeightPx.intValue = it.height },
        )
        // Above the stack: on its way into the chip it passes over the title. It takes no touches.
        NowPlayingCover(
            url = remoteArt, reveal = reveal, chipOrigin = chipOrigin, stackHeightPx = stackHeightPx, shell = shell,
        )
        NowPlayingTopBar(
            song = song, playedBy = playedBy, hasVideo = canvas != null, videoOn = canvas != null && !videoHidden,
            blockedByMobileData = blockedByMobileData, onToggleVideo = { videoHidden = !videoHidden },
            onOpenOrigin = openOrigin, onOpenMenu = onOpenMenu, modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    CompositionLocalProvider(LocalCosmoColors provides colors) {
        PlayerStatusBarIcons(reveal = reveal, lightTheme = lightTheme)
        Box(modifier.fillMaxSize()) {
            // The portrait page fits any window. Task 10 gives the LANDSCAPE branch its own arrangement (the panels and the two
            // columns), Task 11 the WIDE one; until then every window is drawn as portrait.
            when (NowPlayingLayout.choose(windowWidth.value, windowHeight.value)) {
                NowPlayingLayout.PORTRAIT -> page()
                NowPlayingLayout.LANDSCAPE -> page()
                NowPlayingLayout.WIDE -> page()
            }
            // Lyrics and queue panels, the provider and share sheets: Task 10. Until then the lyrics and queue tools flip
            // their flags and nothing opens.
            NowPlayingSheets(
                flags = sheets, hazeState = playerHaze, accountName = accountName, isPlaying = isPlaying,
                lyricsOffsetOpen = lyricsOffsetOpen, onDismissLyricsOffset = onDismissLyricsOffset, onListenTogether = onListenTogether,
            )
        }
    }
}

/**
 * Dark status icons only in the light theme with no video showing; with a video over the top of the page they are light in
 * both themes. Reads [reveal] through a derived state, so it recomposes once as the video comes in, not per frame.
 */
@Composable
private fun PlayerStatusBarIcons(reveal: State<Float>, lightTheme: Boolean) {
    val noVideo by remember(reveal) { derivedStateOf { reveal.value < 0.5f } }
    StatusBarIcons(dark = noVideo && lightTheme)
}

/**
 * Whether this device is in a Listen Together party, as it changes and only then: the party's own state is replaced on every
 * heartbeat, none of which moves this answer, and a read of the whole state would recompose the screen with each one.
 */
@Composable
private fun rememberInParty(): Boolean {
    val inParty = remember { PlayerPlatform.host.party.map { it.inParty }.distinctUntilChanged() }
    return inParty.collectAsStateWithLifecycle(initialValue = PlayerPlatform.host.party.value.inParty).value
}

/**
 * The lyrics and queue panels' open flags, restored from the last-screen setting when the player opens and written back when
 * they change. At most one is open. Task 10 draws the panels these flags open; until then they only flip.
 */
@Stable
internal class NowPlayingPanels(restored: LastPlayerScreen) {
    var lyricsOpen by mutableStateOf(restored == LastPlayerScreen.LYRICS)
        private set
    var queueOpen by mutableStateOf(restored == LastPlayerScreen.QUEUE)
        private set

    /** What to remember as the last screen. */
    val screen: LastPlayerScreen
        get() = when {
            lyricsOpen -> LastPlayerScreen.LYRICS
            queueOpen -> LastPlayerScreen.QUEUE
            else -> LastPlayerScreen.MAIN
        }

    fun openLyrics() { lyricsOpen = true; queueOpen = false }
    fun closeLyrics() { lyricsOpen = false }
    fun toggleLyrics() = if (lyricsOpen) closeLyrics() else openLyrics()
    fun openQueue() { queueOpen = true; lyricsOpen = false }
    fun closeQueue() { queueOpen = false }
    fun toggleQueue() = if (queueOpen) closeQueue() else openQueue()
}

/** Which of the player's own sheets is up. */
@Stable
internal class NowPlayingSheetFlags {
    var output by mutableStateOf(false)
    var pipeline by mutableStateOf(false)
    var cast by mutableStateOf(false)
    var members by mutableStateOf(false)
}

/**
 * The drawers and dialogs the player raises over itself: the audio output drawer (with the pipeline and the cast picker it
 * opens), the Listen Together members and the lyrics offset. The old ones, called as the old screen calls them. Back closes
 * the topmost one first: a later registration is the one back reaches first.
 */
@Composable
private fun NowPlayingSheets(
    flags: NowPlayingSheetFlags,
    hazeState: HazeState,
    accountName: String?,
    isPlaying: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    onListenTogether: () -> Unit,
) {
    PlayerBackHandler(enabled = flags.output) { flags.output = false }
    PlayerBackHandler(enabled = flags.members) { flags.members = false }
    PlayerBackHandler(enabled = flags.pipeline) { flags.pipeline = false }
    PlayerBackHandler(enabled = flags.cast) { flags.cast = false }
    PlayerBackHandler(enabled = lyricsOffsetOpen, onBack = onDismissLyricsOffset)
    if (flags.output) {
        AudioOutputSheet(
            hazeState = hazeState,
            accountName = accountName,
            onDismiss = { flags.output = false },
            onOpenPipeline = { flags.pipeline = true },
            onOpenCast = { flags.cast = true },
        )
    }
    if (flags.pipeline) {
        AudioPipelineDialog(hazeState = hazeState, isPlaying = isPlaying, onDismiss = { flags.pipeline = false })
    }
    if (flags.cast) {
        PlayerPlatform.host.CastDialog(hazeState = hazeState, onDismiss = { flags.cast = false })
    }
    if (flags.members) {
        ListenTogetherMembersSheet(
            hazeState = hazeState,
            onDismiss = { flags.members = false },
            onManage = {
                flags.members = false
                onListenTogether()
            },
        )
    }
    if (lyricsOffsetOpen) {
        LyricsOffsetSheet(hazeState = hazeState, onDismiss = onDismissLyricsOffset)
    }
}
