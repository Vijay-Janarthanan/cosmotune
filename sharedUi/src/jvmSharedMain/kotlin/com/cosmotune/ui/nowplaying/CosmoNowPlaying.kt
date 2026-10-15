package com.cosmotune.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.design.NowPlayingLayout
import com.cosmotune.design.Springs
import com.cosmotune.design.WideNowPlayingMetrics
import com.cosmotune.ui.shell.CosmoPlayerBar
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
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.lyrics_loading_lines
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.AudioOutputSheet
import com.music.bitchord.ui.player.AudioPipelineDialog
import com.music.bitchord.ui.player.CanvasSyncSource
import com.music.bitchord.ui.player.ListenTogetherMembersSheet
import com.music.bitchord.ui.player.KeepScreenOn
import com.music.bitchord.ui.player.LyricsOffsetSheet
import com.music.bitchord.ui.player.LyricsProviderSheet
import com.music.bitchord.ui.player.NEXT_CANVAS_PREFETCH_DELAY_MS
import com.music.bitchord.ui.player.PlayerBackHandler
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.player.PlayerSettings
import com.music.bitchord.ui.player.adjustedLyricsSeekTarget
import com.music.bitchord.ui.player.rememberCanvasArtwork
import com.music.bitchord.ui.player.rememberCanvasBlockedByMobileData
import com.music.bitchord.ui.player.rememberControlsLocked
import com.music.bitchord.ui.player.rememberLyricPlayhead
import com.music.bitchord.ui.player.rememberLyricsTranslation
import com.music.bitchord.ui.player.rememberOutputPicker
import com.music.bitchord.ui.player.rememberPlayerScrub
import com.music.bitchord.ui.player.rememberRemoteArtworkUrl
import com.music.bitchord.ui.theme.StatusBarIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringArrayResource

/**
 * The new Now Playing: the cover on the ambient ground, which gives way to the song's video when it has one; the title, the
 * artist, the wavy scrubber, the transport and the four tools over it; the lyrics, which fill in as they are sung, and the
 * queue, each over the page in place of the cover; and, on its side, the same screen in two panes. It takes [NowPlayingScreen]'s
 * parameters, name for name, so [NowPlayingSwitch] can hand every one over; the ones nobody reads yet (the album and the autoplay
 * toggle, the version switch, the sign-in) are accepted here for the day a screen needs them. A wide window (the desktop, or
 * a tablet on its side: [NowPlayingLayout.choose]) gets [NowPlayingWide]: a header, the lyrics or the queue on the left, the
 * video card over the cover on the right and the player bar along the foot, which has the transport, the scrubber and the volume.
 *
 * What it keeps from the old screen, in the old screen's order: the song's artwork address, the clip's clock
 * ([CanvasSyncSource]), the clip and its mobile-data guard, the prefetch of the next song's clip, the scrub state, the party
 * lock, the output picker, the panels' flags (restored from and written back to the last-screen setting) and the lyrics'
 * playhead and translation. What it adds: [reveal], the one number (0 = cover, 1 = video) the cover's trip, the scrims, the
 * clip's alpha, the chip slot and the status bar icons all follow.
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
    val layout = NowPlayingLayout.choose(windowWidth.value, windowHeight.value)
    val wide = layout == NowPlayingLayout.WIDE

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
    // In a wide window the lyrics or the queue is always there (the tabs of the header choose between them), so they are not
    // layers that back peels off, and the lyrics are showing unless the queue is.
    KeepScreenOn(enabled = if (wide) !panels.queueOpen else panels.lyricsOpen)
    PlayerBackHandler(enabled = panels.lyricsOpen && !wide) { panels.closeLyrics() }
    PlayerBackHandler(enabled = panels.queueOpen && !wide) { panels.closeQueue() }

    // ---- the lyrics: the playhead their clock reads, the romanisation and translation, where a tap on a line goes ----------
    val haptics = rememberHaptics()
    val lyricsPlayhead = rememberLyricPlayhead(position)
    val lyricsOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val seekToLyric: (Long) -> Unit = { lineTimeMs -> onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs)) }
    val lyricsLoadingLines = stringArrayResource(Res.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId) { lyricsLoadingLines.random() }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId, lyrics = lyrics, lyricsSource = lyricsSource, lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText, haptics = haptics,
    )

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

    // Dark over a video in both themes: see [rememberPlayerColors]. Not in a wide window, where the video is a card on the ambient
    // ground and the page stays in the theme.
    val colors = rememberPlayerColors(shell, hasVideo = canvas != null && !wide, videoShown = videoShown && !wide, reduced = reduced)
    val lightTheme = shell != null && !LocalCosmoColors.current.dark

    // ---- what the page is made of ----------------------------------------------------------------------------------------
    val playerHaze = remember { HazeState() }
    val stackHeightPx = remember { mutableIntStateOf(0) }
    val chipOrigin = remember { mutableStateOf<Offset?>(null) }
    // The landscape page has no chip beside the artist: the cover stays in its pane and the video takes the pane's place.
    val noReveal = remember { mutableFloatStateOf(0f) }
    // The large cover is not drawn while the lyrics or the queue have the page.
    val coverShown = animateFloatAsState(if (panels.lyricsOpen || panels.queueOpen) 0f else 1f, CosmoMotion.effects(reduced), label = "coverShown")
    val openOrigin: () -> Unit = {
        // The caption's way back, as the old one's: the party for a song someone else queued, the queue for a queue-built
        // session, and otherwise the album or playlist the session was started from.
        when {
            playedBy != null -> onListenTogether()
            song.playbackSourceType == PlaybackSourceType.QUEUE -> panels.openQueue()
            else -> onOpenPlaybackSource()
        }
    }
    val topBar: @Composable (Modifier) -> Unit = { m ->
        NowPlayingTopBar(
            song = song, playedBy = playedBy, hasVideo = canvas != null, videoOn = canvas != null && !videoHidden,
            blockedByMobileData = blockedByMobileData, onToggleVideo = { videoHidden = !videoHidden },
            onOpenOrigin = openOrigin, onOpenMenu = onOpenMenu, modifier = m,
        )
    }
    val stack: @Composable (Modifier, Boolean, (@Composable BoxScope.() -> Unit)?) -> Unit = { m, landscape, panel ->
        NowPlayingStack(
            song = song, coverUrl = remoteArt, hasVideo = canvas != null && !landscape,
            reveal = if (landscape) noReveal else reveal,
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
            landscape = landscape, panel = panel, modifier = m,
        )
    }
    val lyricsPanel: @Composable (Modifier, LyricsStyle, Dp) -> Unit = { m, style, topPadding ->
        CosmoLyricsPanel(
            lyrics = lyrics, lyricsUnavailable = lyricsUnavailable, loadingText = lyricsLoadingText, translation = lyricsTranslation,
            trackKey = song.videoId, playhead = lyricsPlayhead, isPlaying = isPlaying && position.advancing,
            onSeekToLine = seekToLyric, onOpenProviders = { sheets.providers = true },
            style = style, modifier = m, topPadding = topPadding,
        )
    }
    val queuePanel: @Composable (Modifier) -> Unit = { m ->
        CosmoQueuePanel(
            queue = queue, queueIndex = queueIndex, autoplayEnabled = autoplayEnabled, controlsLocked = controlsLocked,
            onJumpTo = onJumpTo, onRemoveFromQueue = onRemoveFromQueue, onMoveInQueue = onMoveInQueue,
            onClearQueue = onClearQueue, onQueueDragActiveChange = onQueueDragActiveChange, modifier = m,
        )
    }
    // In two panes the lyrics or the queue take the place of the title and the artist; the scrubber, the transport and the tools stay.
    val landscapeLyrics: @Composable BoxScope.() -> Unit = { lyricsPanel(Modifier.fillMaxSize(), LyricsStyle.Landscape, LANDSCAPE_LYRICS_TOP_PADDING) }
    val landscapeQueue: @Composable BoxScope.() -> Unit = { queuePanel(Modifier.fillMaxSize().padding(vertical = 8.dp)) }
    val page: @Composable BoxScope.() -> Unit = {
        NowPlayingBackdrop(
            canvas = canvas, wantVideo = wantVideo, isPlaying = isPlaying, reveal = reveal, lyricsOpen = panels.lyricsOpen,
            onRenderedChanged = { rendered = it }, hazeState = playerHaze, modifier = Modifier.fillMaxSize(),
        )
        // Measured with its own padding, so the cover and the panels know how much of the window the stack takes.
        stack(Modifier.align(Alignment.BottomCenter).onSizeChanged { stackHeightPx.intValue = it.height }, false, null)
        // Above the stack: on its way into the chip it passes over the title. It takes no touches.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverShown.value }) {
            NowPlayingCover(url = remoteArt, reveal = reveal, chipOrigin = chipOrigin, stackHeightPx = stackHeightPx, shell = shell)
        }
        PortraitPanels(
            lyricsOpen = panels.lyricsOpen, queueOpen = panels.queueOpen, stackHeightPx = stackHeightPx, reduced = reduced,
            lyrics = { lyricsPanel(Modifier.fillMaxSize(), LyricsStyle.Portrait, LyricsMetrics.PORTRAIT_TOP_PADDING_DP.dp) },
            queue = { queuePanel(Modifier.fillMaxSize()) },
        )
        topBar(Modifier.align(Alignment.TopCenter))
    }
    val landscapePage: @Composable BoxScope.() -> Unit = {
        NowPlayingLandscape(
            hazeState = playerHaze,
            media = {
                NowPlayingLandscapeMedia(
                    canvas = canvas, wantVideo = wantVideo, isPlaying = isPlaying, reveal = reveal,
                    onRenderedChanged = { rendered = it }, coverUrl = remoteArt, shell = shell,
                )
            },
            topBar = { topBar(Modifier) },
            controls = {
                stack(Modifier.weight(1f), true, if (panels.lyricsOpen) landscapeLyrics else if (panels.queueOpen) landscapeQueue else null)
            },
        )
    }

    // The wide page. The lyrics and the queue are the header's two tabs (the queue's flag is the tab); the bar is the desktop's
    // player capsule drawn inside the player (`embedded`), so it has the transport, the scrubber, like, the panels and the volume.
    val widePage: @Composable BoxScope.() -> Unit = {
        NowPlayingWide(
            windowWidth = windowWidth, windowHeight = windowHeight, hazeState = playerHaze,
            header = { m ->
                NowPlayingWideHeader(
                    song = song, playedBy = playedBy, coverUrl = remoteArt, queueSelected = panels.queueOpen,
                    onSelectTab = { if (it == 0) panels.openLyrics() else panels.openQueue() },
                    hasVideo = canvas != null, videoOn = canvas != null && !videoHidden, onToggleVideo = { videoHidden = !videoHidden },
                    onOpenOrigin = openOrigin, onOpenMenu = onOpenMenu, modifier = m,
                )
            },
            panel = { m ->
                NowPlayingWidePanels(
                    queueSelected = panels.queueOpen, reduced = reduced, modifier = m,
                    lyrics = { lyricsPanel(Modifier.fillMaxSize(), WIDE_LYRICS_STYLE, WIDE_LYRICS_TOP_PADDING) },
                    queue = { queuePanel(Modifier.fillMaxSize()) },
                )
            },
            media = { cover, video, side ->
                NowPlayingWideMedia(
                    canvas = canvas, wantVideo = wantVideo, isPlaying = isPlaying, reveal = reveal, onRenderedChanged = { rendered = it },
                    blockedByMobileData = blockedByMobileData, coverUrl = remoteArt, shell = shell,
                    coverModifier = cover, videoModifier = video, coverSide = side,
                )
            },
            bar = { m ->
                CosmoPlayerBar(
                    song = song, isPlaying = isPlaying, isLoading = isLoading, position = position, durationMs = durationMs,
                    liked = likeStatus == LikeStatus.LIKE, shuffle = shuffleEnabled, repeatMode = repeatMode, controlsLocked = controlsLocked,
                    lyricsOn = !panels.queueOpen, queueOn = panels.queueOpen,
                    onPlayPause = onPlayPause, onPrevious = onPrevious, onNext = onNext,
                    onToggleShuffle = onToggleShuffle, onCycleRepeat = onCycleRepeat, onToggleLike = onToggleLike,
                    onSeekFraction = onSeekFraction, onLyrics = panels::openLyrics, onQueue = panels::openQueue,
                    onOutput = openAudioOutput, onExpand = {}, onBlockedControl = onBlockedControl,
                    hazeState = playerHaze, modifier = m, embedded = true,
                )
            },
        )
    }

    CompositionLocalProvider(LocalCosmoColors provides colors) {
        PlayerStatusBarIcons(reveal = if (wide) noReveal else reveal, lightTheme = lightTheme)
        Box(modifier.fillMaxSize()) {
            // The portrait page fits any window; a window wider than tall gets the two panes, and from 840 x 600 dp the wide page.
            when (layout) {
                NowPlayingLayout.PORTRAIT -> page()
                NowPlayingLayout.LANDSCAPE -> landscapePage()
                NowPlayingLayout.WIDE -> widePage()
            }
            NowPlayingSheets(
                flags = sheets, hazeState = playerHaze, accountName = accountName, isPlaying = isPlaying,
                lyricsSource = lyricsSource, lyricsProviderStates = lyricsProviderStates, onSelectLyricsProvider = onSelectLyricsProvider,
                lyricsOffsetOpen = lyricsOffsetOpen, onDismissLyricsOffset = onDismissLyricsOffset, onListenTogether = onListenTogether,
            )
        }
    }
}

/** Where the first line starts in the landscape lyrics: just under the top of their pane, there is little room to spare. */
private val LANDSCAPE_LYRICS_TOP_PADDING = 8.dp

/** The wide page's lyrics: title-l, the line being sung 1.35 times as big, the lines already sung 0.82 (`.dn-lyrics .ln`). */
private val WIDE_LYRICS_STYLE = LyricsStyle(WideNowPlayingMetrics.LYRICS_BASE_STEP, WideNowPlayingMetrics.LYRICS_CURRENT_SCALE, WideNowPlayingMetrics.LYRICS_PAST_SCALE)

/** The wide column starts where the board's first line does (150 dp down), so the list needs only a little air at its top. */
private val WIDE_LYRICS_TOP_PADDING = 8.dp

/** How long the lyrics and the queue take to fade in or out over the page. */
internal const val PANEL_FADE_MS = 240

/**
 * The lyrics and the queue in a portrait window. Each fades in over the page in the room between the top bar and the stack
 * ([PanelArea]); the stack gives the queue the transport's height and the lyrics what it has left once the title has stepped down.
 */
@Composable
private fun PortraitPanels(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    stackHeightPx: State<Int>,
    reduced: Boolean,
    lyrics: @Composable () -> Unit,
    queue: @Composable () -> Unit,
) {
    val ms = if (reduced) Springs.REDUCED_MS else PANEL_FADE_MS
    AnimatedVisibility(lyricsOpen, Modifier.fillMaxSize(), enter = fadeIn(tween(ms)), exit = fadeOut(tween(ms))) {
        PanelArea(stackHeightPx) { Box(Modifier.fillMaxSize().padding(horizontal = LyricsMetrics.PORTRAIT_SIDE_DP.dp)) { lyrics() } }
    }
    AnimatedVisibility(queueOpen, Modifier.fillMaxSize(), enter = fadeIn(tween(ms)), exit = fadeOut(tween(ms))) {
        PanelArea(stackHeightPx) { Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) { queue() } }
    }
}

/**
 * The room a panel gets: the window's width, from the bottom of the top bar to the top of the stack. The stack's height is read in
 * the layout phase, so a stack that changes (the title stepping down, the queue folding the upper blocks away) moves the panel's
 * edge without recomposing it.
 */
@Composable
private fun PanelArea(stackHeightPx: State<Int>, content: @Composable () -> Unit) {
    val statusBars = WindowInsets.statusBars
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val top = (statusBars.getTop(this) + TOP_BAR_BELOW_STATUS_DP.dp.roundToPx()).coerceAtMost(constraints.maxHeight)
        val bottom = (constraints.maxHeight - stackHeightPx.value).coerceIn(top, constraints.maxHeight)
        val height = bottom - top
        val placeable = measurables.firstOrNull()?.measure(Constraints.fixed(constraints.maxWidth, height))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable?.place(0, top) }
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
 * they change. At most one is open; the back handlers close them.
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
    var providers by mutableStateOf(false)
    var pipeline by mutableStateOf(false)
    var cast by mutableStateOf(false)
    var members by mutableStateOf(false)
}

/**
 * The drawers and dialogs the player raises over itself, each over [hazeState] (the page's ground and video): the audio output
 * drawer (with the pipeline and the cast picker it opens), the lyrics provider chooser, the Listen Together members and the
 * lyrics offset. The old ones, called as the old screen calls them. Back closes the topmost one first: a later registration
 * is the one back reaches first, so the order here is the order of `NowPlayingScreen.kt:1022-1053`, with the lyrics and the
 * queue (registered before this) under all of them.
 */
@Composable
private fun NowPlayingSheets(
    flags: NowPlayingSheetFlags,
    hazeState: HazeState,
    accountName: String?,
    isPlaying: Boolean,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    onListenTogether: () -> Unit,
) {
    PlayerBackHandler(enabled = flags.output) { flags.output = false }
    PlayerBackHandler(enabled = flags.providers) { flags.providers = false }
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
    if (flags.providers) {
        LyricsProviderSheet(
            hazeState = hazeState,
            currentSource = lyricsSource,
            states = lyricsProviderStates,
            onSelect = onSelectLyricsProvider,
            onDismiss = { flags.providers = false },
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
