package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.VideoView
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.watch.SourcesState
import com.cosmotune.watch.WATCH_SKIP_MS
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the player screen does; the page binds these to [com.cosmotune.watch.WatchPlayer] and its engine. */
internal class WatchPlayerControls(
    val playPause: () -> Unit,
    val seekTo: (Long) -> Unit,
    val switchStream: (Stream) -> Unit,
    val selectAudio: (String) -> Unit,
    val selectSubtitle: (String?) -> Unit,
    val searchSubtitles: () -> Unit,
    val subtitleDelay: (Long) -> Unit,
    val playNext: () -> Unit,
    val cancelNext: () -> Unit,
    val close: () -> Unit,
    /** Plays what is on for the room, from where it is. */
    val watchTogether: () -> Unit = {},
    val retry: (() -> Unit)? = null,
    val toggleMute: () -> Unit = {},
    val cast: (() -> Unit)? = null,
    val remote: Boolean = false,
    /** Enters picture-in-picture now; null where there is none (no support, or the film plays on a cast receiver). */
    val pictureInPicture: (() -> Unit)? = null,
    /** The engine's speed, for full screen's long press at 2x; null where it cannot change (a cast receiver). */
    val speed: com.cosmotune.ui.fullscreen.FullScreenSpeed? = null,
    /** The sound mix (Night, Clear voices) and the way to change it, kept for every video. */
    val sound: com.cosmotune.video.player.AudioMix = com.cosmotune.video.player.AudioMix.NORMAL,
    val setSound: (com.cosmotune.video.player.AudioMix) -> Unit = {},
)

/** The Audio menu's rows: the audio tracks when there is a choice, then the sound mixes. */
internal fun watchAudioItems(state: WatchPlayerState, controls: WatchPlayerControls): List<OrbitMenuItem> = buildList {
    if (state.audioTracks.size > 1) state.audioTracks.forEach { t ->
        add(OrbitMenuItem(t.label, listOfNotNull(t.codec, t.channels?.let { "$it ch" }).joinToString(" · ").ifBlank { null }, selected = t.selected) { controls.selectAudio(t.trackId) })
    }
    com.cosmotune.watch.WatchSoundChoice.all.forEach { choice ->
        add(OrbitMenuItem(choice.label, choice.detail, selected = controls.sound == choice.mix) { controls.setSound(choice.mix) })
    }
}

/**
 * The player (board notes: "the same player as Now Playing"): the picture, then the film card in the title's colour with play in
 * its bite, where you are, back and forward, audio, subtitles and the screen button (Whole, Fill, Cinema); the next episode
 * counts down on its own row near the end. Opens the route's target through the host's [com.cosmotune.watch.WatchPlayer], which
 * resumes from saved progress. Closing the page leaves the film playing in the small player where Watch draws one
 * ([LocalWatchKeepsPlaying]); elsewhere it stops when the page goes.
 */
@Composable
internal fun WatchPlayerPage(route: WatchRoute.Play, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val playback = LocalWatchPlayback.current
    if (playback == null) { WatchPlayerUnavailable(route, actions, fonts, look, frame); return }
    val player = playback.player
    // An item the room opened here is already playing (the room follower drives it): shown as it is, and left going on the way out.
    LaunchedEffect(route) { if (!route.attached && !playback.roomHolds(route.target.key)) player.open(route.target, route.summary, route.stream) }
    // The page's picture-in-picture: asked for here, before the page's own leaving, which reads it.
    val pip = com.cosmotune.ui.pip.rememberPip(com.cosmotune.ui.pip.PipOwner.WATCH)
    // Where Watch draws the small player, closing the page leaves the film playing there; elsewhere it stops as the page goes.
    val keepsPlaying = LocalWatchKeepsPlaying.current
    DisposableEffect(player, route, keepsPlaying) {
        WatchMini.keptInPip = false
        onDispose {
            val key = player.state.value.target?.key ?: route.target.key
            // In picture-in-picture the film plays on in the small window after the page has gone (its session is kept there).
            val returned = WatchPipReturns.take(key.value)
            WatchMini.keptInPip = pip.active
            if (!keepsPlaying && (!route.attached || returned) && !playback.roomHolds(key) && !pip.active) player.stop()
        }
    }
    val state by player.state.collectAsState()
    val snapshot by player.engine.snapshot.collectAsState()
    val watchPrefs by actions.repository.preferences.collectAsState()
    LaunchedEffect(player, watchPrefs.sound) { player.setSoundMix(watchPrefs.sound) }
    LaunchedEffect(player, watchPrefs.softwareDecoding) { player.setSoftwareDecoding(watchPrefs.softwareDecoding) }
    LaunchedEffect(player, watchPrefs.passthrough) { player.engine.setAudioPassthrough(watchPrefs.passthrough) }
    val casting = remember(player, route) { com.cosmotune.watch.WatchCasting(player, actions.repository) }
    DisposableEffect(casting) { onDispose { casting.close() } }
    var castOpen by remember { mutableStateOf(false) }
    val castRenderer by casting.renderer.collectAsState()
    val remoteState by (castRenderer?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.cosmotune.music.data.cast.dlna.DlnaState()) }).collectAsState()
    val castScope = rememberCoroutineScope()
    if (castOpen) WatchCastSheet(casting, state.stream, fonts, look) { castOpen = false }
    val roomOwnsPlayback = playback.roomHolds(route.target.key)
    val controls = remember(player, actions, route, roomOwnsPlayback, castRenderer, pip, pip.supported, watchPrefs.sound) {
        WatchPlayerControls(
            playPause = { val target = castRenderer; if (target != null) castScope.launch { if (target.state.value.playing) target.pause() else target.play() }
                else if (player.engine.snapshot.value.playing) player.engine.pause() else player.engine.play() },
            seekTo = { position -> val target = castRenderer; if (target != null) castScope.launch { target.seek(position) } else player.engine.seekTo(position) },
            switchStream = player::switchStream,
            selectAudio = player::selectAudio,
            selectSubtitle = player::selectSubtitle,
            searchSubtitles = { player.searchSubtitles() },
            subtitleDelay = player::setSubtitleDelay,
            playNext = player::playNextEpisode,
            cancelNext = player::cancelNextEpisode,
            close = { WatchNav.back() },
            retry = if (roomOwnsPlayback) null else ({
                val start = if (route.target is com.cosmotune.watch.model.PlayTarget.Live) 0L
                    else player.engine.positionMs().takeIf { it > 0 }
                val selected = player.state.value.takeIf { it.target?.key == route.target.key }?.stream
                player.open(route.target, route.summary, selected ?: route.stream, startMs = start)
            }),
            toggleMute = player::toggleMute,
            cast = if (roomOwnsPlayback) null else ({ castOpen = true }),
            remote = castRenderer != null,
            pictureInPicture = if (pip.supported && castRenderer == null) ({ pip.enter(); Unit }) else null,
            speed = if (castRenderer == null) com.cosmotune.ui.fullscreen.FullScreenSpeed({ player.engine.snapshot.value.speed }, player.engine::setSpeed) else null,
            sound = watchPrefs.sound,
            setSound = { mix -> castScope.launch { actions.repository.setPreferences(actions.repository.preferences.value.copy(sound = mix)) } },
            watchTogether = {
                val s = player.state.value
                s.target?.let { t -> actions.watchTogether(t, s.summary ?: route.summary, s.stream, s.durationMs.takeIf { it > 0 } ?: player.engine.snapshot.value.durationMs) }
            },
        )
    }
    val aspect = if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) snapshot.videoWidth.toFloat() / snapshot.videoHeight else VideoView.DEFAULT_ASPECT
    // A channel: what is on it now (from the guide, when the guide has it, checked again each minute) and, on the phone, the other
    // channels on now under the card, so the half of the screen under the player is not empty.
    val channel = (route.target as? com.cosmotune.watch.model.PlayTarget.Live)?.channel
    val onNow by androidx.compose.runtime.produceState<com.cosmotune.watch.LiveNowItem?>(null, channel) {
        if (channel == null) return@produceState
        while (true) {
            value = runCatching { actions.repository.nowNext(channel) }.getOrNull() ?: value
            delay(60_000)
        }
    }
    val otherChannels by androidx.compose.runtime.produceState(emptyList<com.cosmotune.watch.LiveNowItem>(), channel) {
        if (channel != null) value = runCatching { actions.repository.liveNow(12) }.getOrDefault(emptyList()).filter { it.channel.key != channel.key }
    }
    val shellSettings = LocalCosmoShell.current?.settings
    // Picture-in-picture: the next channel is the one after this in its own playlist.
    val channelRing by androidx.compose.runtime.produceState(emptyList<com.cosmotune.watch.model.Channel>(), channel) {
        if (channel != null) value = runCatching { actions.repository.channels(com.cosmotune.watch.ChannelFilter(pluginId = channel.pluginId)) }.getOrDefault(emptyList())
    }
    val nextChannel = channel?.let { c -> channelAfter(channelRing, c.key) ?: otherChannels.firstOrNull()?.channel }
    val pipBinding = rememberWatchPip(
        pip, player, state, playing = castRenderer == null && snapshot.playing, videoWidth = snapshot.videoWidth, videoHeight = snapshot.videoHeight,
        local = castRenderer == null, nextChannel = nextChannel?.let { next -> { actions.playChannel(next) } }, surface = playback.surface,
        reopen = {
            shellSettings?.setMode(com.cosmotune.design.AppMode.WATCH)
            player.state.value.target?.let { WatchNav.showPlaying(it, player.state.value.summary) }
        },
    )
    val summary = state.summary ?: route.summary
    val episodeTarget = (state.target ?: route.target) as? PlayTarget.EpisodeOf
    val details: (@Composable () -> Unit)? = if (channel == null && summary != null) {
        {
            WatchPlayerDetails(summary, route.episode?.takeIf { it.key == episodeTarget?.key }, episodeTarget, actions, fonts, look, frame)
        }
    } else null
    WatchPlayerScreen(
        state = state,
        playing = if (castRenderer != null) remoteState.playing else snapshot.playing,
        aspect = aspect,
        position = { castRenderer?.state?.value?.positionMs ?: player.engine.positionMs() },
        durationMs = remoteState.durationMs.takeIf { castRenderer != null && it > 0 } ?: state.durationMs.takeIf { it > 0 } ?: snapshot.durationMs,
        surface = playback.surface,
        controls = controls,
        watchTogether = actions.watchTogetherReady,
        fonts = fonts,
        look = look,
        wide = frame.wide,
        padding = frame.padding,
        programme = onNow?.now?.title?.substringAfter(": "),
        channels = otherChannels,
        onChannel = { actions.playChannel(it) },
        details = details,
        pip = pipBinding,
    )
}

/**
 * The player screen itself, from the player's state alone (the page above binds it; a render test draws it with a state of
 * its own). [position] is read in draw.
 */
@Composable
internal fun WatchPlayerScreen(
    state: WatchPlayerState,
    playing: Boolean,
    aspect: Float,
    position: () -> Long,
    durationMs: Long,
    surface: @Composable (WatchSurfaceScale, Modifier) -> Unit,
    controls: WatchPlayerControls,
    watchTogether: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    wide: Boolean,
    padding: PaddingValues,
    startInCinema: Boolean = false,
    /** A channel's programme on now, from the guide; null without one. */
    programme: String? = null,
    /** The other channels on now, listed under the card on the phone. */
    channels: List<com.cosmotune.watch.LiveNowItem> = emptyList(),
    onChannel: (com.cosmotune.watch.model.Channel) -> Unit = {},
    details: (@Composable () -> Unit)? = null,
    /** Picture-in-picture: the phone then shows the picture alone, the desktop a stand-in while its small window holds it. */
    pip: com.cosmotune.ui.pip.PipBinding? = null,
) {
    val settings = LocalCosmoShell.current?.settings
    val keptView = settings?.videoView?.collectAsState()?.value
    var localView by remember { mutableStateOf(VideoView.WHOLE) }
    val view = keptView ?: localView
    val setView: (VideoView) -> Unit = { v -> if (settings != null) settings.setVideoView(v) else localView = v }
    var cinema by rememberSaveable { mutableStateOf(startInCinema) }
    val summary = state.summary
    val (fill, ink) = coverFill(summary?.backdropUrl ?: summary?.posterUrl, summary?.key?.value ?: "watch")
    val pipHandle = pip?.handle
    val inPip = pipHandle?.active == true
    val picture: @Composable (Modifier, Dp, VideoView) -> Unit = { m, radius, shown ->
        val placed = if (pip != null && !inPip) m.onGloballyPositioned { pip.bounds(it.boundsInWindow()) } else m
        if (inPip && pipHandle?.manual == true) {
            WatchPipStandIn(summary?.backdropUrl ?: summary?.posterUrl, summary?.key?.value ?: "watch", radius, fonts, onReturn = { pipHandle.exit() }, modifier = placed)
        } else {
            // In full screen the shared player takes the taps; on the page a double tap goes full screen.
            WatchVideo(shown, aspect, radius, surface, still = summary?.backdropUrl, key = summary?.key?.value ?: "watch", modifier = placed,
                onDoubleTap = if (cinema) null else ({ cinema = true }))
        }
    }
    // The keyboard (the desktop's): Esc leaves Cinema, else closes the player; F goes in and out of Cinema.
    val keyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@ { e ->
        if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@handler false
        when (e.key) {
            Key.Escape -> { if (cinema) cinema = false else controls.close(); true }
            Key.F -> { cinema = !cinema; true }
            Key.M -> { controls.toggleMute(); true }
            else -> false
        }
    }
    val content: @Composable () -> Unit = {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent(keyHandler)) {
            WatchPlayerLayout(state, playing, position, durationMs, controls, watchTogether, view, setView, { m, r -> picture(m, r, view) }, onCinema = { cinema = true }, fonts, look, wide, padding, fill, ink,
                programme = programme, channels = channels, onChannel = onChannel, details = details)
        }
    }
    when {
        // The phone's picture-in-picture window is the app itself: the picture alone, nothing over it.
        inPip && pipHandle?.manual != true -> Box(Modifier.fillMaxSize().background(Color.Black)) { picture(Modifier.fillMaxSize(), 0.dp, view) }
        // Full screen: the one plain player Now Playing and Video use, in the host's full window (a wide film held in landscape).
        cinema -> com.cosmotune.ui.fullscreen.FullScreenWindow({ cinema = false }, landscape = aspect >= 1f) {
            WatchFullScreen(picture, state, playing, position, durationMs, controls, onLeave = { cinema = false }, fonts = fonts, look = look,
                programme = programme, channels = channels, onChannel = onChannel)
        }
        else -> content()
    }
}

@Composable
private fun WatchPlayerLayout(
    state: WatchPlayerState,
    playing: Boolean,
    position: () -> Long,
    durationMs: Long,
    controls: WatchPlayerControls,
    watchTogether: Boolean,
    view: VideoView,
    setView: (VideoView) -> Unit,
    picture: @Composable (Modifier, Dp) -> Unit,
    onCinema: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    wide: Boolean,
    padding: PaddingValues,
    fill: Color,
    ink: Color,
    programme: String? = null,
    channels: List<com.cosmotune.watch.LiveNowItem> = emptyList(),
    onChannel: (com.cosmotune.watch.model.Channel) -> Unit = {},
    details: (@Composable () -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        WatchWash(fill, look)
        val compactLandscape = maxWidth >= 600.dp && maxHeight < 540.dp && maxWidth > maxHeight * 1.2f
        val twoPane = maxWidth >= 1100.dp && maxHeight >= 600.dp && details != null
        if (compactLandscape) {
            Row(Modifier.fillMaxSize().padding(padding).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    picture(Modifier.fillMaxSize(), 22.dp)
                    WatchLoading(state, fonts)
                    WatchRoundButton(CosmoIcons.Expand, "Full screen", look, onClick = onCinema, ink = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                Column(Modifier.weight(0.85f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerTopRow(state, controls, watchTogether, false, fonts, look, LocalWatchDownloads.current)
                    if (state.status == WatchPlayerStatus.ERROR || state.status == WatchPlayerStatus.NOT_FOUND) WatchPlaybackFailure(state, controls, fonts, look)
                    else FilmCard(state, playing, position, durationMs, controls, view, setView, fonts, look, fill, ink,
                        slim = true, wide = false, programme = programme, channels = channels, onChannel = onChannel)
                    NextEpisodeRow(state, controls, false, fonts, look)
                    details?.invoke()
                }
            }
            return@BoxWithConstraints
        }
        val playerHeight = (maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding() - 265.dp)
            .coerceIn(180.dp, 560.dp)
        val playerContent: @Composable ColumnScope.() -> Unit = {
            PlayerTopRow(state, controls, watchTogether, wide, fonts, look, downloads = LocalWatchDownloads.current)
            state.failover?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
            state.lengthNote?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            if (state.status == WatchPlayerStatus.NOT_FOUND || state.status == WatchPlayerStatus.ERROR) {
                WatchPlaybackFailure(state, controls, fonts, look)
            } else {
                val size = when {
                    !wide -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    details != null -> Modifier.fillMaxWidth().height(playerHeight)
                    else -> Modifier.weight(1f).fillMaxWidth()
                }
                Box(size) {
                    picture(Modifier.fillMaxSize(), if (wide) 34.dp else 26.dp)
                    WatchLoading(state, fonts)
                    Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.72f), CircleShape)) {
                        WatchRoundButton(CosmoIcons.Expand, "Full screen", look, onClick = onCinema, ink = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                NextEpisodeRow(state, controls, wide, fonts, look)
                FilmCard(state, playing, position, durationMs, controls, view, setView, fonts, look, fill, ink, slim = false, wide = wide,
                    programme = programme, channels = channels, onChannel = onChannel)
            }
            if (details == null && !wide && state.isLive && channels.isNotEmpty()) {
                // The phone's lower half: the other channels on now, a tap switches to one.
                val clock = remember { { System.currentTimeMillis() } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "on-now") { OrbitLabel("Also on now", fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 2.dp)) }
                    items(channels.size, key = { channels[it].channel.key.value }) { i ->
                        LiveRow(channels[i], fonts, look, clock) { onChannel(channels[i].channel) }
                    }
                }
            } else if (details == null && !wide) Spacer(Modifier.weight(1f))
        }
        val content = Modifier.fillMaxSize().padding(padding)
        if (twoPane) {
            Row(content.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1.6f).verticalScroll(rememberScrollState()), content = playerContent)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { details?.invoke() }
            }
        } else if (details == null) {
            Column(content.padding(horizontal = 16.dp, vertical = 14.dp), content = playerContent)
        } else {
            Column(content.verticalScroll(rememberScrollState()).padding(top = 14.dp)) {
                Column(Modifier.padding(horizontal = 16.dp), content = playerContent)
                details()
            }
        }
    }
}

@Composable
private fun WatchPlaybackFailure(state: WatchPlayerState, controls: WatchPlayerControls, fonts: OrbitFonts, look: OrbitLook) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchMessage(
            if (state.status == WatchPlayerStatus.NOT_FOUND) "Sources" else "Playback",
            if (state.status == WatchPlayerStatus.NOT_FOUND) "No playable copy yet" else "This copy did not play",
            state.error ?: "The source could not start.", fonts, look,
            action = "Retry".takeIf { controls.retry != null }, onAction = controls.retry,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.streams.any { it.isPlayable && it != state.stream }) {
                PlayerSourcesButton(state, controls, fonts, look, label = "Try another source")
            }
            WatchCardChip("Open Plugins", look.ink, fonts, onClick = { controls.close(); WatchNav.go(WatchTab.PLUGINS) })
        }
    }
}

@Composable
private fun PlayerSourcesButton(
    state: WatchPlayerState, controls: WatchPlayerControls, fonts: OrbitFonts, look: OrbitLook,
    label: String = "Sources", onMenuOpen: (Boolean) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    DisposableEffect(open) {
        onMenuOpen(open)
        onDispose { if (open) onMenuOpen(false) }
    }
    Box {
        WatchCardChip(label, look.ink, fonts, onClick = { open = true }, enabled = state.streams.isNotEmpty())
        WatchSourcesMenu(open, { open = false }, state, fonts, look, controls.switchStream)
    }
}

@Composable
private fun WatchLoading(state: WatchPlayerState, fonts: OrbitFonts) {
    val label = when (state.status) {
        WatchPlayerStatus.RESOLVING -> if (state.isLive) "Finding a live stream" else "Finding a copy"
        WatchPlayerStatus.LOADING -> "Starting playback"
        WatchPlayerStatus.BUFFERING -> "Buffering"
        else -> return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = fonts.body, color = Color.White,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp)).padding(12.dp))
    }
}

/** The picture in its box: Whole at its own shape and centred (never cut), Fill cut to the box. Nothing is drawn on it. */
@Composable
private fun WatchVideo(view: VideoView, aspect: Float, radius: Dp, surface: @Composable (WatchSurfaceScale, Modifier) -> Unit, still: String?, key: String, modifier: Modifier, onDoubleTap: (() -> Unit)?) {
    BoxWithConstraints(modifier) {
        val box = watchPictureFrame(view, maxWidth.value, maxHeight.value, aspect)
        Box(
            Modifier.offset(box[0].dp, box[1].dp).size(box[2].dp, box[3].dp).clip(RoundedCornerShape(radius)).background(Color.Black)
                .then(if (onDoubleTap != null) Modifier.pointerInput(onDoubleTap) { detectTapGestures(onDoubleTap = { onDoubleTap() }) } else Modifier),
        ) {
            if (still != null) WatchPicture(still, key, Modifier.fillMaxSize(), radius = 0.dp)
            // The engine draws on a SurfaceView, which can keep the place and size it was first laid out at when only the boxes
            // round it move (the video's shape becoming known, the window going into or out of picture-in-picture): the picture
            // then sat off to one side, too large. A new frame gets a new surface, laid out where the frame is now.
            androidx.compose.runtime.key(surfaceFrameKey(box)) {
                surface(if (view == VideoView.FILL) WatchSurfaceScale.CROP else WatchSurfaceScale.FIT, Modifier.fillMaxSize())
            }
            // The subtitles: the engines hand the cues to the app, which draws them in the viewer's look.
            LocalWatchPlayback.current?.let { playback ->
                val preferences by watchRepository().preferences.collectAsState()
                WatchEngineSubtitles(playback.player.engine, preferences.subtitles)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerTopRow(state: WatchPlayerState, controls: WatchPlayerControls, watchTogether: Boolean, wide: Boolean, fonts: OrbitFonts, look: OrbitLook,
    downloads: com.cosmotune.watch.WatchDownloads? = null) {
    var download by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchRoundButton(CosmoIcons.ChevronDown, "Close the player", look, onClick = controls.close)
        val from = state.stream?.source?.pluginName?.let { if (wide) "From · $it" else it } ?: when (state.status) {
            WatchPlayerStatus.RESOLVING -> "Finding a copy"
            WatchPlayerStatus.LOADING -> "Loading"
            else -> "Watch"
        }
        OrbitLabel(from, fonts, look.dim, Modifier.weight(1f))
      }
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!controls.remote) PlayerSourcesButton(state, controls, fonts, look)
        controls.cast?.let { cast -> WatchRoundButton(CosmoIcons.Devices, if (controls.remote) "Casting · receiver controls" else "Cast video", look, onClick = cast, on = controls.remote) }
        if (downloads != null && !state.isLive && state.target != null) Box {
            val list by downloads.items.collectAsState()
            val stream = state.stream
            val target = state.target
            val kept = if (stream != null && target != null) list.firstOrNull { it.id == downloads.find(target, stream)?.id } else null
            WatchRoundButton(if (kept?.status == com.cosmotune.watch.WatchDownload.Status.DONE) CosmoIcons.Downloaded else CosmoIcons.Download, "Download", look, onClick = { download = true })
            val why = stream?.let { downloads.whyNot(it) } ?: "Nothing is playing yet."
            OrbitMenu(download, { download = false }, fonts = fonts, look = look, header = "Download", width = 320.dp, items = listOf(
                when {
                    stream?.protocol == com.cosmotune.watch.model.StreamProtocol.LOCAL_FILE -> OrbitMenuItem("On this device", "This copy plays from a file here", enabled = false) {}
                    kept?.status == com.cosmotune.watch.WatchDownload.Status.DONE -> OrbitMenuItem("Downloaded", "In Downloads", CosmoIcons.Downloaded) { controls.close(); WatchNav.go(WatchTab.DOWNLOADS) }
                    kept?.status == com.cosmotune.watch.WatchDownload.Status.DOWNLOADING -> OrbitMenuItem("Downloading", kept.fraction?.let { "${(it * 100).toInt()}%" } ?: "Starting", CosmoIcons.Download) { controls.close(); WatchNav.go(WatchTab.DOWNLOADS) }
                    stream != null && target != null && downloads.canDownload(stream) -> OrbitMenuItem("Download this copy", listOfNotNull(stream.source.pluginName, stream.label, sizeText(stream.sizeBytes)).joinToString(" · "), CosmoIcons.Download) {
                        downloads.start(target, state.summary, stream, state.subtitleLine ?: listOfNotNull(stream.source.pluginName, stream.label).joinToString(" · "))
                    }
                    else -> OrbitMenuItem("Can't download this copy", why, enabled = false) {}
                },
            ))
        }
        // Another player on this device (VLC, mpv, MX Player): for a codec this one cannot play, or a player the viewer prefers.
        if (!controls.remote) WatchOpenElsewhere(state, controls, fonts, look)
        if (!controls.remote && state.stream != null) WatchPlaybackInfoButton(state, look)
        if (wide) WatchCardChip("Watch together", look.ink, fonts, onClick = controls.watchTogether, icon = CosmoIcons.Together, enabled = watchTogether && state.target != null)
        else WatchRoundButton(CosmoIcons.Together, "Watch together", look, onClick = controls.watchTogether, enabled = watchTogether && state.target != null)
      }
    }
}

/** Near the end of an episode: "Next episode in 8 s", what it is, Play now and Cancel. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NextEpisodeRow(state: WatchPlayerState, controls: WatchPlayerControls, wide: Boolean, fonts: OrbitFonts, look: OrbitLook) {
    val next = state.nextEpisode ?: return
    val inMs = state.nextEpisodeInMs ?: return
    val words: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            // A torrent's next video has no season or number of its own: its name alone.
            val queued = state.target is PlayTarget.Item
            OrbitLabel((if (queued) "Next video in " else "Next episode in ") + "${(inMs + 999) / 1000} s", fonts, look.accent)
            Text(if (queued) next.name else "S${next.season} E${next.number} · ${next.name}", style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val buttons: @Composable () -> Unit = {
        WatchCardChip("Play now", look.ink, fonts, onClick = controls.playNext, solid = true, onSolid = look.ground)
        WatchCardChip("Cancel", look.ink, fonts, onClick = controls.cancelNext)
    }
    if (wide) WatchRowFrame(look, Modifier.padding(bottom = 12.dp), height = 66.dp, highlighted = true, card = true) {
        WatchPicture(next.stillUrl, next.key.value, Modifier.padding(start = 2.dp).size(84.dp, 48.dp), radius = 12.dp)
        words(Modifier.weight(1f))
        buttons()
    } else WatchRowFrame(look, Modifier.padding(bottom = 12.dp), height = 118.dp, highlighted = true, card = true) {
        WatchPicture(next.stillUrl, next.key.value, Modifier.padding(start = 4.dp).size(84.dp, 48.dp), radius = 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            words(Modifier)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}

/**
 * The film card (Now Playing's song card become the film's): the mono time, the title and the episode line, back 10 and
 * forward 30 seconds, audio, subtitles and the screen button, the seek line, and play in the bite. [slim] is Cinema's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilmCard(
    state: WatchPlayerState,
    playing: Boolean,
    position: () -> Long,
    durationMs: Long,
    controls: WatchPlayerControls,
    view: VideoView,
    setView: (VideoView) -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    fill: Color,
    ink: Color,
    slim: Boolean,
    wide: Boolean = true,
    programme: String? = null,
    channels: List<com.cosmotune.watch.LiveNowItem> = emptyList(),
    onChannel: (com.cosmotune.watch.model.Channel) -> Unit = {},
    onMenuOpen: (Boolean) -> Unit = {},
) {
    var audio by remember { mutableStateOf(false) }
    // The phone's card is narrow: the title may take two lines, and Audio and Subtitles are round buttons so all the controls clear the bite.
    val narrow = !wide && !slim
    var subtitles by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    if (styleOpen) WatchSubtitleLookDialog { styleOpen = false }
    var screen by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    val menuOpen = audio || subtitles || screen || guide
    DisposableEffect(menuOpen) {
        onMenuOpen(menuOpen)
        onDispose { if (menuOpen) onMenuOpen(false) }
    }
    // The label is redrawn every half second, not every frame.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) { while (playing) { delay(500); tick++ } }
    val summary = state.summary
    OrbitBiteCard(fill, Modifier.fillMaxWidth(), bite = { OrbitPlayButton(playing, controls.playPause, look, size = if (slim) 56.dp else 66.dp) }) {
        Column(Modifier.padding(start = 22.dp, top = if (slim) 14.dp else 20.dp, end = 22.dp, bottom = if (slim) 14.dp else 20.dp)) {
            val label = if (state.isLive) "● Live" else run {
                tick
                val p = position()
                when (state.status) {
                    WatchPlayerStatus.RESOLVING -> "Finding the best copy"
                    WatchPlayerStatus.LOADING, WatchPlayerStatus.BUFFERING -> "Loading · ${longClock(p)}"
                    else -> (if (wide) "Now watching · " else "") + "${longClock(p)} / ${longClock(durationMs)}" + (chapterAt(state, p)?.let { " · $it" }
                        ?: if (durationMs > p) " · " + leftText(durationMs - p) else "")
                }
            }
            OrbitLabel(label, fonts, ink.copy(alpha = 0.72f), Modifier.padding(end = 80.dp))
            Text(summary?.name ?: "Watch", style = fonts.heroTitle.copy(fontSize = if (slim) 20.sp else 24.sp, lineHeight = if (slim) 22.sp else 26.sp), color = ink,
                maxLines = if (narrow) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, end = if (narrow) 0.dp else 80.dp))
            // A channel's line is what is on it now when the guide knows; its own line otherwise.
            (if (state.isLive && programme != null) "On now · $programme" else state.subtitleLine)?.let { Text(it, style = fonts.body, color = ink.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, end = 80.dp)) }
            if (!state.isLive && durationMs > 0) {
                val skip = state.skipIntervals.firstOrNull { position() in it.startMs until it.endMs }
                if (skip != null) com.cosmotune.ui.orbit.WatchCardChip(skip.label, ink, fonts, onClick = { controls.seekTo(skip.endMs) }, modifier = Modifier.padding(top = 8.dp))
                OrbitProgressLine({ (position().toFloat() / durationMs).coerceIn(0f, 1f) }, ink, Modifier.padding(top = 12.dp, end = 90.dp), onSeek = { controls.seekTo((it * durationMs).toLong()) },
                    preview = state.previews?.let { previews -> { at -> WatchSeekThumb(previews, (at * durationMs).toLong()) } })
            }
            FlowRow(Modifier.padding(top = if (slim) 8.dp else 12.dp, end = 68.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isLive) {
                    WatchRoundButton(CosmoIcons.Replay30, "Back 30 seconds", look,
                        onClick = { controls.seekTo((position() - WATCH_SKIP_MS).coerceIn(0, durationMs)) }, ink = ink, enabled = durationMs > 0)
                    WatchRoundButton(CosmoIcons.Forward30, "Forward 30 seconds", look,
                        onClick = { controls.seekTo((position() + WATCH_SKIP_MS).coerceIn(0, durationMs)) }, ink = ink, enabled = durationMs > 0)
                } else {
                    controls.retry?.let { WatchRoundButton(CosmoIcons.Replay, "Jump to live", look, onClick = it, ink = ink) }
                    if (channels.isNotEmpty()) Box {
                        WatchRoundButton(CosmoIcons.Queue, "Channels", look, onClick = { guide = true }, ink = ink)
                        OrbitMenu(guide, { guide = false }, fonts = fonts, look = look, header = "Also on now", items =
                            channels.map { row -> OrbitMenuItem(row.channel.name, row.now?.title) { onChannel(row.channel) } })
                    }
                }
                if (!controls.remote) WatchRoundButton(CosmoIcons.Volume, if (state.muted) "Unmute" else "Mute", look,
                    onClick = controls.toggleMute, ink = ink, on = state.muted)
                if (!controls.remote) Box {
                    // The audio track gets the waveform, so it does not read as a second Mute beside the speaker.
                    if (narrow) WatchRoundButton(CosmoIcons.Waveform, "Audio", look, onClick = { audio = true }, ink = ink)
                    else WatchCardChip("Audio", ink, fonts, onClick = { audio = true }, icon = CosmoIcons.Waveform)
                    OrbitMenu(audio, { audio = false }, fonts = fonts, look = look, header = "Audio", width = 300.dp,
                        items = watchAudioItems(state, controls))
                }
                if (!controls.remote) Box {
                    if (narrow) WatchRoundButton(CosmoIcons.Lyrics, "Subtitles", look, onClick = { subtitles = true }, ink = ink)
                    else WatchCardChip("Subtitles", ink, fonts, onClick = { subtitles = true }, icon = CosmoIcons.Lyrics)
                    OrbitMenu(subtitles, { subtitles = false }, fonts = fonts, look = look, header = "Subtitles", width = 300.dp, items = buildList {
                        add(OrbitMenuItem("Off", null, selected = state.selectedSubtitleId == null) { controls.selectSubtitle(null) })
                        state.subtitles.forEach { s -> add(OrbitMenuItem(s.label, listOfNotNull(s.sourceLabel, if (s.hearingImpaired) "SDH" else null).joinToString(" · "), selected = s.id == state.selectedSubtitleId) { controls.selectSubtitle(s.id) }) }
                        add(OrbitMenuItem("Find more subtitles", "OpenSubtitles and your subtitle plugins", CosmoIcons.Search) { controls.searchSubtitles() })
                        add(OrbitMenuItem("Subtitle look", "Size, colour, background and height", CosmoIcons.Lyrics) { styleOpen = true })
                        if (state.selectedSubtitleId != null) {
                            add(OrbitMenuItem("Earlier by 0.5 s", "Now ${"%+.1f".format(state.subtitleDelayMs / 1000f)} s", keepOpen = true) { controls.subtitleDelay(state.subtitleDelayMs - 500) })
                            add(OrbitMenuItem("Later by 0.5 s", null, keepOpen = true) { controls.subtitleDelay(state.subtitleDelayMs + 500) })
                        }
                    })
                }
                controls.pictureInPicture?.let { WatchRoundButton(CosmoIcons.Pip, "Picture in picture", look, onClick = it, ink = ink) }
                if (state.chapters.size > 1 && !state.isLive) Box {
                    var chapters by remember { mutableStateOf(false) }
                    WatchRoundButton(CosmoIcons.Queue, "Chapters", look, onClick = { chapters = true }, ink = ink)
                    OrbitMenu(chapters, { chapters = false }, fonts = fonts, look = look, header = "Chapters", width = 300.dp,
                        items = chapterItems(state, position(), controls.seekTo))
                }
                Box {
                    WatchRoundButton(CosmoIcons.Canvas, "Picture size", look, onClick = { screen = true }, ink = ink)
                    OrbitMenu(screen, { screen = false }, fonts = fonts, look = look, header = "Picture size", items = listOf(
                        OrbitMenuItem("Fit", "Shows the full picture", selected = view == VideoView.WHOLE) { setView(VideoView.WHOLE) },
                        OrbitMenuItem("Fill", "Fills the frame and crops the edges", selected = view == VideoView.FILL) { setView(VideoView.FILL) },
                    ))
                }
            }
        }
    }
}

/**
 * Watch's full screen: the one plain full-screen player ([com.cosmotune.ui.fullscreen.FullScreenPlayer], as Now Playing's Cinema
 * and Video's full screen): the title line with ⋯ (captions, audio, sources, the channels on now, and leaving), back and ahead
 * 10 s round play, the seek bar (none for a live channel). Over it, whether the controls show or not: the next episode's countdown
 * with Play now and Cancel, Skip intro while the intro plays, the loading note, and a failure with its way out (the controls stay
 * up then).
 */
@Composable
private fun WatchFullScreen(
    picture: @Composable (Modifier, Dp, VideoView) -> Unit,
    state: WatchPlayerState,
    playing: Boolean,
    position: () -> Long,
    durationMs: Long,
    controls: WatchPlayerControls,
    onLeave: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    programme: String? = null,
    channels: List<com.cosmotune.watch.LiveNowItem> = emptyList(),
    onChannel: (com.cosmotune.watch.model.Channel) -> Unit = {},
) {
    val failed = state.status == WatchPlayerStatus.ERROR || state.status == WatchPlayerStatus.NOT_FOUND
    var subtitleLook by remember { mutableStateOf(false) }
    if (subtitleLook) WatchSubtitleLookDialog { subtitleLook = false }
    // Skip intro follows the playhead: read twice a second, not every frame.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) { while (playing) { delay(500); tick++ } }
    val menus = buildList {
        if (!controls.remote) {
            add(com.cosmotune.ui.fullscreen.FullScreenMenu("Captions", state.subtitles.firstOrNull { it.id == state.selectedSubtitleId }?.label ?: "Off", CosmoIcons.Lyrics, buildList {
                add(OrbitMenuItem("Off", null, selected = state.selectedSubtitleId == null) { controls.selectSubtitle(null) })
                state.subtitles.forEach { s -> add(OrbitMenuItem(s.label, if (s.hearingImpaired) "SDH" else null, selected = s.id == state.selectedSubtitleId) { controls.selectSubtitle(s.id) }) }
                add(OrbitMenuItem("Find more subtitles", "OpenSubtitles and your subtitle plugins", CosmoIcons.Search) { controls.searchSubtitles() })
                add(OrbitMenuItem("Subtitle look", "Size, colour, background and height", CosmoIcons.Lyrics) { subtitleLook = true })
            }))
            add(com.cosmotune.ui.fullscreen.FullScreenMenu("Audio", state.audioTracks.takeIf { it.size > 1 }?.firstOrNull { it.selected }?.label
                ?: com.cosmotune.watch.WatchSoundChoice.of(controls.sound).label, CosmoIcons.Waveform, watchAudioItems(state, controls)))
            if (state.streams.isNotEmpty()) add(com.cosmotune.ui.fullscreen.FullScreenMenu("Sources", state.stream?.let { "${it.source.pluginName} · ${it.label}" }, CosmoIcons.Queue,
                watchSourceItems(state, controls.switchStream)))
        }
        if (state.chapters.size > 1 && !state.isLive) add(com.cosmotune.ui.fullscreen.FullScreenMenu("Chapters", chapterAt(state, position()), CosmoIcons.Queue,
            chapterItems(state, position(), controls.seekTo)))
        if (state.isLive && channels.isNotEmpty()) add(com.cosmotune.ui.fullscreen.FullScreenMenu("Also on now", null, CosmoIcons.Explore,
            channels.map { row -> OrbitMenuItem(row.channel.name, row.now?.title) { onChannel(row.channel) } }))
    }
    com.cosmotune.ui.fullscreen.FullScreenPlayer(
        title = state.summary?.name ?: "Watch",
        subtitle = if (state.isLive && programme != null) "On now · $programme" else state.subtitleLine,
        playing = playing, position = position, durationMs = durationMs,
        onPlayPause = controls.playPause, onSeek = controls.seekTo, onLeave = onLeave,
        transport = com.cosmotune.ui.fullscreen.FullScreenTransport.Skip,
        live = state.isLive, holdControls = failed, menus = menus,
        // Picture in picture where the device has it; a long press plays at 2x (not a live channel).
        actions = listOfNotNull(controls.pictureInPicture?.let { enter -> OrbitMenuItem("Picture in picture", "The film plays on in a small window", CosmoIcons.Pip, onClick = enter) }),
        speed = controls.speed.takeIf { !state.isLive },
        seekPreview = state.previews?.let { previews -> { at -> WatchSeekThumb(previews, at) } },
        picture = { fill, m -> picture(m, 0.dp, if (fill) VideoView.FILL else VideoView.WHOLE) },
        overlay = { shown ->
            WatchLoading(state, fonts)
            if (failed) Box(Modifier.align(Alignment.Center).widthIn(max = 500.dp).padding(24.dp)) { WatchPlaybackFailure(state, controls, fonts, look) }
            // Above the seek bar while the controls show; at the foot when they are hidden.
            val lift = if (shown) 92.dp else 24.dp
            if (!failed && !state.isLive && durationMs > 0) {
                tick
                state.skipIntervals.firstOrNull { position() in it.startMs until it.endMs }?.let { skip ->
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = lift).testTag("watch:fullscreen:skip")) {
                        WatchCardChip(skip.label, Color.White, fonts, onClick = { controls.seekTo(skip.endMs) }, solid = true, onSolid = Color.Black)
                    }
                }
            }
            if (!failed && state.nextEpisode != null && state.nextEpisodeInMs != null) {
                BoxWithConstraints(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = lift).widthIn(max = 860.dp).fillMaxWidth().testTag("watch:cinema:next")) {
                    NextEpisodeRow(state, controls, maxWidth >= 560.dp, fonts, look)
                }
            }
        },
    )
}

/**
 * The player page while the host has no Watch player yet: the title's card and the copies the plugins found, so Play shows
 * what it would play.
 */
@Composable
private fun WatchPlayerUnavailable(route: WatchRoute.Play, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val sources by remember(route.target) { actions.repository.sources(route.target) }.collectAsState(null as SourcesState?)
    val (fill, _) = coverFill(route.summary?.backdropUrl ?: route.summary?.posterUrl, route.summary?.key?.value ?: route.target.key.value)
    Box(Modifier.fillMaxSize()) {
        WatchWash(fill, look)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item { header() } }
            item {
                val title = route.summary?.name ?: "This title"
                val line = route.episode?.let { "S${it.season} E${it.number} · ${it.name}" }
                WatchMessage("Player", listOfNotNull(title, line).joinToString(" · "),
                    "The Watch player is not connected on this device yet. These are the copies it would play, best first.", fonts, look,
                    Modifier.padding(horizontal = frame.side).padding(top = 22.dp))
            }
            item {
                Column(Modifier.padding(horizontal = frame.side).padding(top = 16.dp).widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources?.streams.orEmpty().forEach { s ->
                        SourceRow(s, best = s == sources?.best, fonts = fonts, look = look, titleRuntimeMin = (route.target as? com.cosmotune.watch.model.PlayTarget.Film)?.runtimeMin)
                    }
                }
            }
        }
    }
}

/** The subtitle look editor in a dialog (the player's subtitle menus and Watch settings), kept for this viewer as it changes. */
@Composable
internal fun WatchSubtitleLookDialog(onDismiss: () -> Unit) {
    val repository = watchRepository()
    val preferences by repository.preferences.collectAsState()
    val scope = rememberCoroutineScope()
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    val look = com.cosmotune.ui.orbit.orbitLook()
    var fontMessage by remember { mutableStateOf<String?>(null) }
    val keep: (com.cosmotune.watch.WatchSubtitleStyle) -> Unit = { style -> scope.launch { repository.setPreferences(repository.preferences.value.copy(subtitles = style)) } }
    val pickFont = rememberFontFilePicker { name, bytes ->
        scope.launch {
            runCatching { repository.importSubtitleFont(name, bytes) }
                .onSuccess { path -> fontMessage = null; keep(repository.preferences.value.subtitles.copy(font = com.cosmotune.watch.SubtitleFont.YOURS, fontPath = path,
                    fontName = name.substringBeforeLast('.').take(40))) }
                .onFailure { fontMessage = it.message ?: "That font could not be used." }
        }
    }
    WatchDialog("Subtitles", "Subtitle look", onDismiss = onDismiss) {
        WatchSubtitleEditor(preferences.subtitles, keep, fonts, look, onImportFont = pickFont, fontMessage = fontMessage)
    }
}

/** "Open in another player": the players this device has, or why this copy cannot go to one; the film here pauses. */
@Composable
private fun WatchOpenElsewhere(state: WatchPlayerState, controls: WatchPlayerControls, fonts: OrbitFonts, look: OrbitLook) {
    val players = rememberExternalPlayers()
    if (players.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Box {
        WatchRoundButton(CosmoIcons.Share, "Open in another player", look, onClick = { problem = null; open = true })
        val why = problem ?: externalBlock(state.stream)
        val title = listOfNotNull(state.summary?.name, state.subtitleLine).joinToString(" · ").ifBlank { "Watch" }
        OrbitMenu(open, { open = false }, fonts = fonts, look = look, header = "Open in another player", width = 300.dp, items =
            if (why != null) listOf(OrbitMenuItem("Can't open elsewhere", why, enabled = false) {})
            else players.map { p -> OrbitMenuItem(p.name, "Plays this copy there", CosmoIcons.Play) {
                val stream = state.stream ?: return@OrbitMenuItem
                runCatching { p.open(stream, title) }.onSuccess { if (state.status == WatchPlayerStatus.PLAYING) controls.playPause() }
                    .onFailure { problem = "${p.name} did not open. ${it.message ?: ""}".trim(); open = true }
            } })
    }
}

/** The name of the chapter playing at [positionMs], when what plays has chapters. */
internal fun chapterAt(state: WatchPlayerState, positionMs: Long): String? =
    state.chapters.lastOrNull { it.startMs <= positionMs }?.title?.takeIf { state.chapters.size > 1 }

/** Every chapter with where it starts; the one playing is ticked, a tap goes to its start. */
private fun chapterItems(state: WatchPlayerState, positionMs: Long, seekTo: (Long) -> Unit): List<OrbitMenuItem> {
    val current = state.chapters.lastOrNull { it.startMs <= positionMs }
    return state.chapters.map { c -> OrbitMenuItem(c.title, longClock(c.startMs), selected = c == current) { seekTo(c.startMs) } }
}

/** The lines of Playback info: the copy, how it plays, and what the engine reports now. */
internal fun playbackInfoLines(state: WatchPlayerState, snapshot: com.cosmotune.video.player.PlayerSnapshot): List<Pair<String, String>> {
    val s = state.stream ?: return emptyList()
    val method = when {
        s.isTorrent -> "Torrent, through Cosmotune's engine"
        s.protocol == com.cosmotune.watch.model.StreamProtocol.LOCAL_FILE -> "File on this device"
        s.label.startsWith("Transcoded") -> "Converted by your server"
        s.protocol == com.cosmotune.watch.model.StreamProtocol.HLS -> "Stream (HLS)"
        s.protocol == com.cosmotune.watch.model.StreamProtocol.DASH -> "Stream (DASH)"
        else -> "Direct play"
    }
    return listOfNotNull(
        "Source" to listOf(s.source.pluginName, s.label).joinToString(" · "),
        "Play method" to method,
        s.container.takeIf { it != com.cosmotune.watch.model.Container.UNKNOWN }?.let { "Container" to it.name },
        (if (snapshot.videoWidth > 0) "${snapshot.videoWidth} × ${snapshot.videoHeight}" else s.qualityBadge)?.let { "Picture" to listOfNotNull(it, s.hdr).joinToString(" · ") },
        s.videoCodec?.let { "Video codec" to it.uppercase() },
        state.audioTracks.firstOrNull { it.selected }?.let { a -> "Audio" to listOfNotNull(a.label, a.codec, a.channels?.let { "$it ch" }).joinToString(" · ") },
        snapshot.bandwidthKbps?.takeIf { it > 0 }?.let { "Bitrate" to if (it >= 1000) "%.1f Mb/s".format(it / 1000f) else "$it kb/s" },
        snapshot.decoderName?.let { "Decoder" to it },
        "Dropped frames" to "${snapshot.droppedFrames}",
        s.sizeBytes?.let { sizeText(it) }?.let { "Size" to it },
    )
}

/** Playback info (Jellyfin's overlay and Stremio's stats, as a menu over the page, never over the picture). */
@Composable
private fun WatchPlaybackInfoButton(state: WatchPlayerState, look: OrbitLook) {
    val playback = LocalWatchPlayback.current ?: return
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    var open by remember { mutableStateOf(false) }
    Box {
        WatchRoundButton(CosmoIcons.Settings, "Playback info", look, onClick = { open = true })
        if (open) {
            val snapshot by playback.player.engine.snapshot.collectAsState()
            OrbitMenu(true, { open = false }, fonts = fonts, look = look, header = "Playback info", width = 340.dp,
                items = playbackInfoLines(state, snapshot).map { (k, v) -> OrbitMenuItem(k, v, enabled = false) {} })
        }
    }
}
