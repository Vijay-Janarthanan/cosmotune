package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.design.NowPlayingLayout
import com.cosmotune.design.TypeScale
import com.cosmotune.design.VideoView
import com.cosmotune.lyrics.styles.LyricSurface
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricsSource
import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.music.data.model.PlaybackSourceType
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LastPlayerScreen
import com.cosmotune.music.playback.BACK_RESTARTS_AFTER_MS
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.playback.rememberLivePlayhead
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.lyrics_loading_lines
import com.cosmotune.music.sharedui.resources.played_by
import com.cosmotune.music.sharedui.resources.playing_radio
import com.cosmotune.music.sharedui.resources.queue
import com.cosmotune.music.ui.LyricsProviderState
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.AudioOutputSheet
import com.cosmotune.music.ui.player.AudioPipelineDialog
import com.cosmotune.music.ui.player.CanvasSyncSource
import com.cosmotune.music.ui.player.KeepScreenOn
import com.cosmotune.music.ui.player.ListenTogetherMembersSheet
import com.cosmotune.music.ui.player.LyricsOffsetSheet
import com.cosmotune.music.ui.player.LyricsProviderSheet
import com.cosmotune.music.ui.player.NEXT_CANVAS_PREFETCH_DELAY_MS
import com.cosmotune.music.ui.player.PlayerBackHandler
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerSettings
import com.cosmotune.music.ui.player.RepeatModes
import com.cosmotune.music.ui.player.adjustedLyricsSeekTarget
import com.cosmotune.music.ui.player.rememberCanvasArtwork
import com.cosmotune.music.ui.player.rememberCanvasBlockedByMobileData
import com.cosmotune.music.ui.player.rememberControlsLocked
import com.cosmotune.music.ui.player.rememberLyricPlayhead
import com.cosmotune.music.ui.player.rememberLyricsTranslation
import com.cosmotune.music.ui.player.rememberOutputPicker
import com.cosmotune.music.ui.player.rememberPlayerScrub
import com.cosmotune.music.ui.player.rememberRemoteArtworkUrl
import com.cosmotune.music.ui.theme.StatusBarIcons
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.modes.LocalModeLinks
import com.cosmotune.ui.modes.musicVideoLink
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.sleep.SleepTimerMenu
import com.cosmotune.ui.sleep.SleepTimerUi
import com.cosmotune.ui.sleep.rememberSleepTimer
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalReducedMotion
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * Now Playing in the Orbit language (the board in `docs/superpowers/design/cosmotune/now-playing-orbit/board-v2.html`): the page
 * wears the playing cover; the song's video (shown Whole, Fill or in Cinema, never with anything on it) or, without one, the
 * record whose orbit is the progress; the song card in the cover's colour with play / pause in its bite; and the Lyrics, Up next
 * and Related panels behind Home's chips. A wide window (the desktop, a tablet on its side: [NowPlayingLayout.choose]) gets
 * [NowPlayingWide]; a phone held upright [NowPlayingPhone]; a phone on its side shows a video in Cinema, or
 * [NowPlayingPhoneLandscape]. It takes [NowPlayingScreen]'s parameters, name for name, so [NowPlayingSwitch] can hand every one
 * over; the album flag, the version switch and the sign-in are accepted for the day a screen needs them.
 *
 * What it keeps from the old screen, in the old screen's order: the song's artwork address, the clip's clock
 * ([CanvasSyncSource]), the clip and its mobile-data guard, the prefetch of the next song's clip, the scrub state, the party
 * lock, the output picker, the panel (restored from and written back to the last-screen setting), the lyrics' playhead,
 * translation and sharing, and the player's own sheets. [reveal] (0 = the record, 1 = the video) is the one number the clip's
 * alpha and the record's fade follow.
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
    val hideStatusBar by PlayerSettings.hideStatusBar.collectAsStateWithLifecycle()
    com.cosmotune.music.ui.theme.HideStatusBar(hideStatusBar)
    val shell = LocalCosmoShell.current
    val reduced = LocalReducedMotion.current
    val layout = NowPlayingLayout.choose(windowWidth.value, windowHeight.value)
    val wide = layout == NowPlayingLayout.WIDE
    val landscape = layout == NowPlayingLayout.LANDSCAPE

    // ---- what the old screen sets up first, in its order ----------------------------------------------------------------
    // Remote tracks whose art lives inside the file resolve it here, once; every surface reads the same value.
    val remoteArt = rememberRemoteArtworkUrl(song)
    // A canvas that follows the song (an OpenCanvas music video) reads the same playhead; set while composing, not in an
    // effect, so a clip mounted in this pass already sees it.
    remember(position) { CanvasSyncSource.attach(position) }
    DisposableEffect(position) { onDispose { CanvasSyncSource.detach(position) } }
    val canvas = rememberCanvasArtwork(song)
    // "Watch the music video" (Video mode's link): only when Video is available and the canvas is a music video that follows the song.
    val watchMusicVideo = rememberWatchMusicVideo(song, canvas, position)
    // True only while the mobile-data guard is the reason there is no video; the media then carries the host's line that says so.
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
        PlayerPlatform.host.prefetchCanvas(upNext)
    }
    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    val controlsLocked = rememberControlsLocked()
    val skipHaptics = rememberHaptics()
    val transport = NowPlayingTransport(
        song = song, coverUrl = remoteArt, isPlaying = isPlaying, position = position, durationMs = durationMs, scrub = scrub,
        liked = likeStatus == LikeStatus.LIKE, shuffle = shuffleEnabled, repeatMode = repeatMode, hasPrevious = hasPrevious, hasNext = hasNext,
        onPlayPause = onPlayPause,
        onPrevious = { if (controlsLocked) onBlockedControl() else { skipHaptics.play(Haptic.SkipPrevious); onPrevious() } },
        onNext = { if (controlsLocked) onBlockedControl() else { skipHaptics.play(Haptic.SkipNext); onNext() } },
        onToggleShuffle = onToggleShuffle, onCycleRepeat = onCycleRepeat, onToggleLike = onToggleLike, onSeekFraction = onSeekFraction,
        onOpenArtist = { onOpenArtist(song.artistId, song.artist) },
        isLoading = isLoading,
        live = rememberLivePlayhead(position, isPlaying),
    )
    val inParty = rememberInParty()
    val sheets = remember { NowPlayingSheetFlags() }
    val openAudioOutput = rememberOutputPicker { sheets.output = true }
    val panels = remember { NowPlayingPanels(PlayerSettings.lastPlayerScreen.value) }
    LaunchedEffect(panels.panel) { PlayerSettings.setLastPlayerScreen(panels.screen) }
    // In a wide window one panel is always there (the chips choose it), so it is not a layer that back peels off.
    PlayerBackHandler(enabled = panels.lyricsOpen && !wide) { panels.closeLyrics() }
    PlayerBackHandler(enabled = panels.queueOpen && !wide) { panels.closeQueue() }
    PlayerBackHandler(enabled = panels.relatedOpen && !wide) { panels.closeRelated() }

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
    // Choosing lines to share as a card, and the card they make (the phone's; the desktop has none).
    val lyricShare = rememberCosmoLyricShare(song, lyrics, lyricsTranslation.subLines, remoteArt, haptics)

    // ---- the video: whether it is wanted, how it is shown, and the one number that follows it -----------------------------
    // Reduced motion: the video does not start by itself; the person turns it on.
    var videoHidden by rememberSaveable { mutableStateOf(reduced) }
    // The video is wanted when a canvas exists, the person has not hidden it, and the player's morph has settled. Read in
    // a derived state: the progress moves every frame of the morph, the answer changes once.
    val settled by remember(shell) { derivedStateOf { shell?.playerSettled != false } }
    // A canvas is either a music video that follows the song (it has a map of the song onto the video) or a short animated cover
    // loop. The music video is shown as the view says; the loop plays silently inside the record's disc.
    val musicVideo = canvas != null && canvas.syncMap != null
    val coverLoop = canvas != null && canvas.syncMap == null
    val wantVideo = musicVideo && !videoHidden && settled
    val wantLoop = coverLoop && !videoHidden && settled
    // How the video is shown (Whole or Fill, kept in the look settings), whether Cinema is up, and the clip's own shape.
    val keptView = shell?.settings?.videoView?.collectAsStateWithLifecycle()?.value ?: VideoView.WHOLE
    val setView: (VideoView) -> Unit = { shell?.settings?.setVideoView(it) }
    var cinema by rememberSaveable { mutableStateOf(false) }
    // A phone turned on its side shows its video in Cinema, until Cinema is left; turning it upright again forgets that.
    var leftTurnedCinema by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(landscape) { if (!landscape) leftTurnedCinema = false }
    var videoAspect by remember(canvas?.url) { mutableFloatStateOf(0f) }
    var videoMenuOpen by remember { mutableStateOf(false) }
    val cinemaShown = (cinema || (landscape && !leftTurnedCinema)) && wantVideo
    val leaveCinema: () -> Unit = { cinema = false; if (landscape) leftTurnedCinema = true }
    PlayerBackHandler(enabled = cinemaShown) { leaveCinema() }
    // Keyed on the clip (a version switch that keeps it carries this through) and on whether it is wanted at all: a clip that
    // is mounted again has to show a frame again before the record gives way.
    var rendered by remember(canvas?.url, wantVideo || wantLoop) { mutableStateOf(false) }
    val videoShown = (wantVideo || wantLoop) && rendered
    // 0 = the record, 1 = the video all the way in. A State, read only in layer and draw lambdas.
    val reveal = animateFloatAsState(if (videoShown) 1f else 0f, CosmoMotion.slow(reduced), label = "reveal")

    // Now Playing is one of the zones the cover colours: its colours are the cover's palette, not the chrome's, in both themes.
    val colors = shell?.coverColors ?: LocalCosmoColors.current
    // Dark status icons in the light theme; light over Cinema's black.
    StatusBarIcons(dark = shell != null && !colors.dark && !cinemaShown)
    // The lyrics keep the screen on while they show, and so does Cinema.
    KeepScreenOn(enabled = (if (wide) panels.widePanel == NowPlayingPanel.LYRICS else panels.lyricsOpen) || cinemaShown)

    // ---- the Orbit pieces: the look of the colours above, the screen button's menu, the more menu ------------------------
    val orbitType = orbitFonts()
    val orbitInk = orbitLook(colors)
    val videoMenu: @Composable () -> Unit = {
        OrbitMenu(
            open = videoMenuOpen, onDismiss = { videoMenuOpen = false }, fonts = orbitType, look = orbitInk, header = "Show the video",
            items = videoViewItems(keptView, cinemaShown, !videoHidden, setView, { if (it) cinema = true else leaveCinema() }, { videoHidden = !it }),
        )
    }
    val screenButton: CardButton? = if (!musicVideo) null else CardButton(
        VIDEO_VIEW_ICON, "How the video is shown", { videoMenuOpen = true }, on = videoMenuOpen, menu = videoMenu, movable = true,
    )
    var visualsOpen by rememberSaveable { mutableStateOf(false) }
    if (visualsOpen) LocalVisualsPresentation.current({ visualsOpen = false }) { PlayerVisualsContent(song, { visualsOpen = false }) }
    val together: () -> Unit = { if (inParty) sheets.members = true else onListenTogether() }
    // What is switched on stays in sight: solid chips in the chip row (the sleep timer's pill first), not a state that only a
    // menu shows.
    val features by PlayerPlatform.host.activeFeatures.collectAsStateWithLifecycle()
    val automixOn by PlayerSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val active = NowPlayingActive(
        shuffle = shuffleEnabled, repeatMode = repeatMode, crossfade = features.crossfade, automix = automixOn,
        equaliser = features.equaliser, castingTo = features.castingTo, inRoom = inParty,
    )
    val statusChips: @Composable (Boolean) -> Unit = { transportOnCard ->
        NowPlayingStatusChips(
            activeChips(active, transportOnCard, onToggleShuffle, onCycleRepeat, { sheets.details = true }, { sheets.cast = true }, together),
            orbitType, orbitInk,
        )
    }
    val sleepTimer = rememberSleepTimer()
    var sleepMenuOpen by remember { mutableStateOf(false) }
    val sleepMenu: @Composable () -> Unit = { SleepTimerMenu(sleepMenuOpen, { sleepMenuOpen = false }, orbitType, orbitInk) }
    // The rarer actions, in the top row's "more" (the phone adds shuffle and repeat, which its card has no room for).
    val moreItems: (Boolean) -> List<OrbitMenuItem> = { phone ->
        buildList {
            add(OrbitMenuItem("Song options", "Playlists, sharing, quality and the rest", CosmoIcons.MoreVertical, onClick = onOpenMenu))
            // Says what is left while one runs ("23:41 left"), rather than going quiet once it is set.
            if (SleepTimerUi.control != null) {
                add(OrbitMenuItem("Sleep timer", sleepTimer?.line() ?: "Pause after a while", CosmoIcons.Timer, selected = sleepTimer != null) { sleepMenuOpen = true })
            }
            if (phone) {
                add(OrbitMenuItem(if (shuffleEnabled) "Shuffle is on" else "Shuffle", null, CosmoIcons.Shuffle, selected = shuffleEnabled, keepOpen = true, onClick = onToggleShuffle))
                add(OrbitMenuItem(repeatLabel(repeatMode), null, if (repeatMode == RepeatModes.ONE) CosmoIcons.RepeatOne else CosmoIcons.Repeat, selected = repeatMode != RepeatModes.OFF, keepOpen = true, onClick = onCycleRepeat))
            }
            add(OrbitMenuItem("Play on", "Speakers, headphones and other devices", CosmoIcons.Devices, onClick = openAudioOutput))
            add(OrbitMenuItem("Listen together", if (inParty) "Who is in the room" else "Start or join a room", CosmoIcons.Together, onClick = together))
            watchMusicVideo?.let { add(OrbitMenuItem("Watch the music video", "In Video mode, from this moment", CosmoIcons.Video, onClick = it)) }
            if (coverLoop) add(OrbitMenuItem(if (videoHidden) "Animated cover" else "Still cover", if (videoHidden) "Play the cover's loop in the record" else "Show the cover without its loop", CosmoIcons.Canvas) { videoHidden = !videoHidden })
            add(OrbitMenuItem("Visuals", "Artwork, pulse or waveform, full screen", CosmoIcons.Waveform) { visualsOpen = true })
            add(OrbitMenuItem("Audio details", "The signal path, from source to output", CosmoIcons.Equaliser) { sheets.details = true })
            add(OrbitMenuItem("Lyrics source", "Choose where the lyrics come from", CosmoIcons.Lyrics) { sheets.providers = true })
        }
    }
    val sourceLabel = nowPlayingSource(song, playedBy)

    // ---- what the pages are made of --------------------------------------------------------------------------------------
    val playerHaze = remember { HazeState() }
    val openOrigin: () -> Unit = {
        // The caption's way back, as the old one's: the party for a song someone else queued, Up next for a queue-built
        // session, and otherwise the album or playlist the session was started from.
        when {
            playedBy != null -> onListenTogether()
            song.playbackSourceType == PlaybackSourceType.QUEUE -> panels.openQueue()
            else -> onOpenPlaybackSource()
        }
    }
    val coverPlaced: (Rect) -> Unit = { shell?.playerCoverBounds = it }
    val lyricStageSong = remember(song.title, song.artist, song.albumName, remoteArt) { LyricStageSong(song.title, song.artist, song.albumName, remoteArt) }
    val lyricsPanel: @Composable (Modifier, LyricsStyle, Dp) -> Unit = { m, style, topPadding ->
        CosmoLyricsPanel(
            lyrics = lyrics, lyricsUnavailable = lyricsUnavailable, loadingText = lyricsLoadingText, translation = lyricsTranslation,
            trackKey = song.videoId, playhead = lyricsPlayhead, isPlaying = isPlaying && position.advancing,
            onSeekToLine = seekToLyric, onOpenProviders = { sheets.providers = true },
            style = style, modifier = m, topPadding = topPadding, share = lyricShare,
            song = lyricStageSong, surface = if (wide) LyricSurface.DESKTOP else LyricSurface.PHONE,
        )
    }
    val upNextPanel: @Composable (Modifier, Boolean) -> Unit = { m, header ->
        NowPlayingUpNext(
            queue = queue, queueIndex = queueIndex, autoplayEnabled = autoplayEnabled, controlsLocked = controlsLocked,
            isPlaying = isPlaying, progress = transport::progress, onJumpTo = onJumpTo, onRemove = onRemoveFromQueue,
            onMove = onMoveInQueue, onClear = onClearQueue, onToggleAutoplay = onToggleAutoplay,
            onDragActiveChange = onQueueDragActiveChange, fonts = orbitType, look = orbitInk, modifier = m, header = header,
        )
    }
    val relatedPanel: @Composable (Modifier, Boolean) -> Unit = { m, header ->
        NowPlayingRelated(
            song = song, queue = queue, queueIndex = queueIndex, sourceLabel = sourceLabel, autoplayEnabled = autoplayEnabled,
            controlsLocked = controlsLocked, onOpenArtist = onOpenArtist, onOpenAlbum = onOpenAlbum, onOpenOrigin = openOrigin,
            onJumpTo = onJumpTo, onToggleAutoplay = onToggleAutoplay, fonts = orbitType, look = orbitInk, modifier = m, header = header,
        )
    }
    val record: @Composable (Dp) -> Unit = { disc ->
        NowPlayingRecord(
            coverUrl = remoteArt, disc = disc, ink = orbitInk.ink, moon = orbitInk.accent,
            progress = transport::progress, playing = isPlaying, onSeek = transport::seek,
            modifier = if (musicVideo) Modifier.fadedBy(reveal) else Modifier, onPlaced = coverPlaced,
            loop = if (wantLoop && canvas != null) { m ->
                NowPlayingCoverLoop(canvas, isPlaying, reveal, onRenderedChanged = { rendered = it }, modifier = m)
            } else null,
        )
    }
    // The video or the record in the box given. The page's video is not mounted while Cinema shows its own.
    val pageMedia: @Composable (Modifier, Dp, Dp, Boolean) -> Unit = { m, radius, maxDisc, phone ->
        // Nothing on top of media (Orbit rule 9): the mobile-data note stands under the frame, the frame taking the rest.
        androidx.compose.foundation.layout.Column(m) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RecordBox(Modifier.fillMaxSize(), maxDisc) { record(it) }
            if (wantVideo && !cinemaShown && canvas != null) {
                NowPlayingVideo(
                    canvas = canvas, isPlaying = isPlaying, view = keptView, aspect = videoAspect, onAspect = { videoAspect = it },
                    reveal = reveal, onRenderedChanged = { rendered = it }, coverUrl = remoteArt, radius = radius,
                    modifier = Modifier.fillMaxSize(), onDoubleTap = { cinema = true }, onPinch = if (phone) setView else null,
                    onFramePlaced = coverPlaced,
                )
            }
        }
        if (blockedByMobileData) Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { PlayerPlatform.host.CanvasMobileDataHint(Modifier) }
        }
    }
    val likeButton = CardButton(if (transport.liked) CosmoIcons.HeartFill else CosmoIcons.Heart, if (transport.liked) "Remove from liked" else "Like", onToggleLike, on = transport.liked)
    // As the old transport: back restarts the song once it is a few seconds in, so it works on the first song too; in a party
    // whose host holds the controls both stay enabled and say so (onBlockedControl) instead of acting.
    val pastRestartPoint by remember(position) { derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS } }
    val previousButton = CardButton(CosmoIcons.Previous, "Previous", transport.onPrevious, enabled = controlsLocked || hasPrevious || pastRestartPoint)
    val nextButton = CardButton(CosmoIcons.Next, "Next", transport.onNext, enabled = controlsLocked || hasNext)

    // The wide page (board sections 1 and 3): the top row with the chips, the video or the record over the song card on the
    // left, the chosen panel on the right. The chips are the panel; one always shows.
    val widePage: @Composable BoxScope.() -> Unit = {
        NowPlayingWide(
            hazeState = playerHaze, video = musicVideo && !videoHidden,
            topRow = { m ->
                NowPlayingTopRow(
                    source = sourceLabel, onOpenOrigin = openOrigin, panel = panels.widePanel, onPanel = { panels.panel = it },
                    more = moreItems(false), fonts = orbitType, look = orbitInk, modifier = m,
                    status = { statusChips(true) }, moreOverlay = sleepMenu,
                )
            },
            media = { m -> pageMedia(m, 34.dp, 440.dp, false) },
            card = { m, wideCard ->
                NowPlayingSongCard(
                    transport, orbitType, orbitInk, modifier = m, wide = wideCard, showLine = wideCard, format = true, volume = true,
                    buttons = listOfNotNull(
                        CardButton(CosmoIcons.Shuffle, if (shuffleEnabled) "Shuffle on" else "Shuffle off", onToggleShuffle, on = shuffleEnabled, movable = true),
                        CardButton(if (repeatMode == RepeatModes.ONE) CosmoIcons.RepeatOne else CosmoIcons.Repeat, repeatLabel(repeatMode), onCycleRepeat, on = repeatMode != RepeatModes.OFF, movable = true),
                        previousButton, nextButton, likeButton, screenButton,
                    ),
                )
            },
            panel = { m ->
                NowPlayingPanelSwitch(
                    panels.widePanel, modifier = m,
                    lyrics = { lm -> lyricsPanel(lm, WIDE_LYRICS_STYLE, WIDE_LYRICS_TOP_PADDING) },
                    upNext = { um -> upNextPanel(um, true) },
                    related = { rm -> relatedPanel(rm, true) },
                )
            },
            // F (or a double click on the video) enters Cinema.
            modifier = Modifier.cinemaKey(enabled = musicVideo && !videoHidden) { cinema = true },
        )
    }

    // The phone (board section 5): upright, the media across the top (or the panel a chip opened), the card under it, the
    // chips at the foot; on its side, the media or the panel beside the card.
    val phonePanel: @Composable (Modifier) -> Unit = { m ->
        NowPlayingPanelSwitch(
            panels.panel, modifier = m,
            lyrics = { lm -> lyricsPanel(lm, if (landscape) LyricsStyle.Landscape else LyricsStyle.Portrait, PHONE_LYRICS_TOP_PADDING) },
            upNext = { um -> upNextPanel(um, false) },
            related = { rm -> relatedPanel(rm, false) },
        )
    }
    val panelOpen = panels.panel != NowPlayingPanel.NONE
    val phoneTop: @Composable (Modifier) -> Unit = { m ->
        NowPlayingTopRow(
            source = sourceLabel, onOpenOrigin = openOrigin, panel = panels.panel, onPanel = { panels.panel = it },
            more = moreItems(true), fonts = orbitType, look = orbitInk, modifier = m, chips = false, moreOverlay = sleepMenu,
        )
    }
    val phoneCard: @Composable (Modifier) -> Unit = { m ->
        NowPlayingSongCard(
            transport, orbitType, orbitInk, modifier = m, showLine = wantVideo || panelOpen, buttonSize = 44.dp,
            buttons = listOfNotNull(previousButton, nextButton, likeButton, screenButton),
        )
    }
    val phoneChips: @Composable (Modifier) -> Unit = { m -> PhoneChips(panels.panel, { panels.panel = it }, orbitType, orbitInk, m, status = { statusChips(false) }) }
    val phonePage: @Composable BoxScope.() -> Unit = {
        NowPlayingPhone(
            hazeState = playerHaze,
            mediaKind = when {
                panelOpen -> PhoneMedia.ROOM
                !wantVideo -> PhoneMedia.RECORD
                keptView == VideoView.FILL -> PhoneMedia.ROOM
                else -> PhoneMedia.WHOLE
            },
            aspect = videoAspect,
            topRow = phoneTop,
            media = { m -> if (panelOpen) phonePanel(m) else pageMedia(m, 26.dp, 300.dp, true) },
            card = phoneCard,
            chips = phoneChips,
            recordDetails = { RecordAudioDetails(song.videoId, orbitType, orbitInk) { sheets.pipeline = true } },
        )
    }
    val phoneLandscapePage: @Composable BoxScope.() -> Unit = {
        NowPlayingPhoneLandscape(
            hazeState = playerHaze,
            topRow = phoneTop,
            media = { m -> if (panelOpen) phonePanel(m) else pageMedia(m, 26.dp, 300.dp, true) },
            card = phoneCard,
            chips = phoneChips,
        )
    }

    CompositionLocalProvider(LocalCosmoColors provides colors, LocalOpenAudioDetails provides { sheets.details = true }) {
        Box(modifier.fillMaxSize()) {
            when (layout) {
                NowPlayingLayout.PORTRAIT -> phonePage()
                NowPlayingLayout.LANDSCAPE -> phoneLandscapePage()
                NowPlayingLayout.WIDE -> widePage()
            }
            if (cinemaShown && canvas != null) {
                NowPlayingCinema(
                    canvas = canvas, isPlaying = isPlaying, view = keptView, aspect = videoAspect, onAspect = { videoAspect = it },
                    reveal = reveal, onRenderedChanged = { rendered = it }, coverUrl = remoteArt, t = transport,
                    fonts = orbitType, look = orbitInk, menuOpen = videoMenuOpen, onLeave = leaveCinema,
                    buttons = listOfNotNull(previousButton, nextButton, likeButton, screenButton), volume = wide,
                )
            }
            NowPlayingSheets(
                song = song, flags = sheets, hazeState = playerHaze, accountName = accountName, isPlaying = isPlaying,
                lyricsSource = lyricsSource, lyricsProviderStates = lyricsProviderStates, onSelectLyricsProvider = onSelectLyricsProvider,
                lyricsOffsetOpen = lyricsOffsetOpen, onDismissLyricsOffset = onDismissLyricsOffset, onListenTogether = onListenTogether,
            )
            // After the sheets: back reaches the card, then the pick, before any of them.
            CosmoLyricShareHost(lyricShare, playerHaze)
        }
    }
}

/** Where the first line of the phone's lyrics starts: their panel already sits under the top row. */
private val PHONE_LYRICS_TOP_PADDING = 8.dp

/**
 * The wide page's lyrics (board section 3): title-m, the line being sung a little larger and in wide Archivo (the lyrics board's
 * Orbit Read), the others in the text face, the sung ones smaller.
 */
private val WIDE_LYRICS_STYLE = LyricsStyle(TypeScale.TITLE_M, 1.12f, 0.9f)

/** The wide column starts at the top of the media, so the list needs only a little air at its top. */
private val WIDE_LYRICS_TOP_PADDING = 8.dp

/** How long a panel takes to fade in or out. */
internal const val PANEL_FADE_MS = 240

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
 * The action of "Watch the music video" for the song that plays, or null when there is none ([musicVideoLink]): Video mode is
 * available (its host provides [LocalModeLinks]), and [canvas] is a music video that OpenCanvas found and that follows the song
 * (an id and a map). Decided once per canvas; the playhead is read when it is chosen, so the video opens at the song's moment
 * then and nothing here recomposes with the position.
 */
@Composable
internal fun rememberWatchMusicVideo(song: Song, canvas: CanvasArtwork?, position: PlaybackPosition): (() -> Unit)? {
    val links = LocalModeLinks.current
    return remember(links, canvas, song.title, song.artist, position) {
        musicVideoLink(links, canvas, song.title, song.artist) { position.positionMs }
    }
}

/**
 * Which panel is open: the lyrics, Up next or Related (or none, on the phone), restored from the last-screen setting when the
 * player opens and written back when it changes (Related is kept as the main screen).
 */
@Stable
internal class NowPlayingPanels(restored: LastPlayerScreen) {
    var panel by mutableStateOf(
        when (restored) {
            LastPlayerScreen.LYRICS -> NowPlayingPanel.LYRICS
            LastPlayerScreen.QUEUE -> NowPlayingPanel.UP_NEXT
            else -> NowPlayingPanel.NONE
        },
    )

    val lyricsOpen: Boolean get() = panel == NowPlayingPanel.LYRICS
    val queueOpen: Boolean get() = panel == NowPlayingPanel.UP_NEXT
    val relatedOpen: Boolean get() = panel == NowPlayingPanel.RELATED

    /** What to remember as the last screen. */
    val screen: LastPlayerScreen
        get() = when (panel) {
            NowPlayingPanel.LYRICS -> LastPlayerScreen.LYRICS
            NowPlayingPanel.UP_NEXT -> LastPlayerScreen.QUEUE
            else -> LastPlayerScreen.MAIN
        }

    /** The wide page always shows one: the lyrics unless another is chosen. */
    val widePanel: NowPlayingPanel get() = if (panel == NowPlayingPanel.NONE) NowPlayingPanel.LYRICS else panel

    fun openQueue() { panel = NowPlayingPanel.UP_NEXT }
    fun closeLyrics() { if (lyricsOpen) panel = NowPlayingPanel.NONE }
    fun closeQueue() { if (queueOpen) panel = NowPlayingPanel.NONE }
    fun closeRelated() { if (relatedOpen) panel = NowPlayingPanel.NONE }
}

/** Which of the player's own sheets is up. */
@Stable
internal class NowPlayingSheetFlags {
    var output by mutableStateOf(false)
    var providers by mutableStateOf(false)
    var details by mutableStateOf(false)
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
    song: Song,
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
    PlayerBackHandler(enabled = flags.details) { flags.details = false }
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
    if (flags.details) {
        AudioDetailsSheet(song, hazeState, onDismiss = { flags.details = false },
            onPipeline = { flags.details = false; flags.pipeline = true })
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

/** "Repeat off", "Repeat all", "Repeat one", for the button and the menu. */
internal fun repeatLabel(mode: Int): String = when (mode) {
    RepeatModes.OFF -> "Repeat off"
    RepeatModes.ONE -> "Repeat one"
    else -> "Repeat all"
}

/** Where the music comes from, for the top row's "FROM ·": who queued it in a party, the radio, the source, the album or the queue. */
@Composable
internal fun nowPlayingSource(song: Song, playedBy: String?): String =
    playedBy?.let { stringResource(Res.string.played_by, it) }
        ?: song.radioName?.let { stringResource(Res.string.playing_radio, it) }
        ?: song.playbackSource?.takeIf { it.isNotBlank() }
        ?: song.albumName?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.queue)

/**
 * F enters Cinema ([onCinema]) on a page that has the keyboard: the page takes focus when it opens, so the key reaches it even
 * when nothing on it was clicked.
 */
@Composable
private fun Modifier.cinemaKey(enabled: Boolean, onCinema: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return focusRequester(focus).focusable().onKeyEvent { event ->
        if (enabled && event.type == KeyEventType.KeyDown && event.key == Key.F && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) { onCinema(); true } else false
    }
}
