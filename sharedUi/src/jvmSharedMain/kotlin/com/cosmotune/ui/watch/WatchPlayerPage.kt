package com.cosmotune.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.model.Stream
import kotlinx.coroutines.delay

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
)

typealias WatchFullscreenHost = @Composable (() -> Unit, Boolean, @Composable () -> Unit) -> Unit
val LocalWatchFullscreen = staticCompositionLocalOf<WatchFullscreenHost?> { null }

/**
 * The player (board notes: "the same player as Now Playing"): the picture, then the film card in the title's colour with play in
 * its bite, where you are, back and forward, audio, subtitles and the screen button (Whole, Fill, Cinema); the next episode
 * counts down on its own row near the end. Opens the route's target through the host's [com.cosmotune.watch.WatchPlayer], which
 * resumes from saved progress, and stops it when the page goes.
 */
@Composable
internal fun WatchPlayerPage(route: WatchRoute.Play, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val playback = LocalWatchPlayback.current
    if (playback == null) { WatchPlayerUnavailable(route, actions, fonts, look, frame); return }
    val player = playback.player
    // An item the room opened here is already playing (the room follower drives it): shown as it is, and left going on the way out.
    LaunchedEffect(route) { if (!route.attached && !playback.roomHolds(route.target.key)) player.open(route.target, route.summary, route.stream) }
    DisposableEffect(player, route) {
        onDispose {
            val key = player.state.value.target?.key ?: route.target.key
            if (!route.attached && !playback.roomHolds(key)) player.stop()
        }
    }
    val state by player.state.collectAsState()
    val snapshot by player.engine.snapshot.collectAsState()
    val roomOwnsPlayback = playback.roomHolds(route.target.key)
    val controls = remember(player, actions, route, roomOwnsPlayback) {
        WatchPlayerControls(
            playPause = { if (player.engine.snapshot.value.playing) player.engine.pause() else player.engine.play() },
            seekTo = player.engine::seekTo,
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
    WatchPlayerScreen(
        state = state,
        playing = snapshot.playing,
        aspect = aspect,
        position = player.engine::positionMs,
        durationMs = state.durationMs.takeIf { it > 0 } ?: snapshot.durationMs,
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
) {
    val settings = LocalCosmoShell.current?.settings
    val keptView = settings?.videoView?.collectAsState()?.value
    var localView by remember { mutableStateOf(VideoView.WHOLE) }
    val view = keptView ?: localView
    val setView: (VideoView) -> Unit = { v -> if (settings != null) settings.setVideoView(v) else localView = v }
    var cinema by rememberSaveable { mutableStateOf(startInCinema) }
    val fullscreen = LocalWatchFullscreen.current
    val summary = state.summary
    val (fill, ink) = coverFill(summary?.backdropUrl ?: summary?.posterUrl, summary?.key?.value ?: "watch")
    val picture: @Composable (Modifier, Dp) -> Unit = { m, radius ->
        WatchVideo(view, aspect, radius, surface, still = summary?.backdropUrl, key = summary?.key?.value ?: "watch", modifier = m, onDoubleTap = { cinema = !cinema })
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
            if (cinema) WatchCinema(picture, state, playing, position, durationMs, controls, view, setView, onLeave = { cinema = false }, fonts = fonts, look = look, fill = fill, ink = ink, programme = programme,
                channels = channels, onChannel = onChannel)
            else WatchPlayerLayout(state, playing, position, durationMs, controls, watchTogether, view, setView, picture, onCinema = { cinema = true }, fonts, look, wide, padding, fill, ink,
                programme = programme, channels = channels, onChannel = onChannel)
        }
    }
    if (cinema && fullscreen != null) fullscreen({ cinema = false }, aspect >= 1f, content) else content()
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
) {
    Box(Modifier.fillMaxSize()) {
        WatchWash(fill, look)
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 14.dp)) {
            PlayerTopRow(state, controls, watchTogether, wide, fonts, look, downloads = LocalWatchDownloads.current)
            state.failover?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
            state.lengthNote?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            if (state.status == WatchPlayerStatus.NOT_FOUND || state.status == WatchPlayerStatus.ERROR) {
                WatchPlaybackFailure(state, controls, fonts, look)
                Spacer(Modifier.weight(1f))
            } else {
                val size = if (wide) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
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
            if (!wide && state.isLive && channels.isNotEmpty()) {
                // The phone's lower half: the other channels on now, a tap switches to one.
                val clock = remember { { System.currentTimeMillis() } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "on-now") { OrbitLabel("Also on now", fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 2.dp)) }
                    items(channels.size, key = { channels[it].channel.key.value }) { i ->
                        LiveRow(channels[i], fonts, look, clock) { onChannel(channels[i].channel) }
                    }
                }
            } else if (!wide) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun WatchPlaybackFailure(state: WatchPlayerState, controls: WatchPlayerControls, fonts: OrbitFonts, look: OrbitLook) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchMessage(
            if (state.status == WatchPlayerStatus.NOT_FOUND) "Sources" else "Playback",
            if (state.status == WatchPlayerStatus.NOT_FOUND) "No playable copy yet" else "This copy did not play",
            state.error ?: "The source could not start. Try again or choose another source.", fonts, look,
            action = "Retry".takeIf { controls.retry != null }, onAction = controls.retry,
        )
        WatchCardChip("Open Plugins", look.ink, fonts, onClick = { controls.close(); WatchNav.go(WatchTab.PLUGINS) })
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
private fun WatchVideo(view: VideoView, aspect: Float, radius: Dp, surface: @Composable (WatchSurfaceScale, Modifier) -> Unit, still: String?, key: String, modifier: Modifier, onDoubleTap: () -> Unit) {
    BoxWithConstraints(modifier) {
        val box = if (view == VideoView.FILL) floatArrayOf(0f, 0f, maxWidth.value, maxHeight.value) else VideoView.whole(maxWidth.value, maxHeight.value, aspect)
        Box(
            Modifier.offset(box[0].dp, box[1].dp).size(box[2].dp, box[3].dp).clip(RoundedCornerShape(radius)).background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) },
        ) {
            if (still != null) WatchPicture(still, key, Modifier.fillMaxSize(), radius = 0.dp)
            surface(if (view == VideoView.FILL) WatchSurfaceScale.CROP else WatchSurfaceScale.FIT, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PlayerTopRow(state: WatchPlayerState, controls: WatchPlayerControls, watchTogether: Boolean, wide: Boolean, fonts: OrbitFonts, look: OrbitLook,
    downloads: com.cosmotune.watch.WatchDownloads? = null) {
    var sources by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchRoundButton(CosmoIcons.ChevronDown, "Close the player", look, onClick = controls.close)
        val from = state.stream?.source?.pluginName?.let { if (wide) "From · $it" else it } ?: when (state.status) {
            WatchPlayerStatus.RESOLVING -> "Finding a copy"
            WatchPlayerStatus.LOADING -> "Loading"
            else -> "Watch"
        }
        OrbitLabel(from, fonts, look.dim, Modifier.weight(1f))
        Box {
            WatchCardChip("Sources", look.ink, fonts, onClick = { sources = true }, enabled = state.streams.isNotEmpty())
            OrbitMenu(
                sources, onDismiss = { sources = false }, fonts = fonts, look = look, header = "Play from · best first", width = 320.dp,
                items = state.streams.map { s ->
                    OrbitMenuItem("${s.source.pluginName} · ${s.label}", s.description, selected = s == state.stream, enabled = s.isPlayable) { controls.switchStream(s) }
                },
            )
        }
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
        if (wide) WatchCardChip("Watch together", look.ink, fonts, onClick = controls.watchTogether, icon = CosmoIcons.Together, enabled = watchTogether && state.target != null)
        else WatchRoundButton(CosmoIcons.Together, "Watch together", look, onClick = controls.watchTogether, enabled = watchTogether && state.target != null)
    }
}

/** Near the end of an episode: "Next episode in 8 s", what it is, Play now and Cancel. */
@Composable
private fun NextEpisodeRow(state: WatchPlayerState, controls: WatchPlayerControls, wide: Boolean, fonts: OrbitFonts, look: OrbitLook) {
    val next = state.nextEpisode ?: return
    val inMs = state.nextEpisodeInMs ?: return
    val words: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            OrbitLabel("Next episode in ${(inMs + 999) / 1000} s", fonts, look.accent)
            Text("S${next.season} E${next.number} · ${next.name}", style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val buttons: @Composable () -> Unit = {
        WatchCardChip("Play now", look.ink, fonts, onClick = controls.playNext, solid = true, onSolid = look.ground)
        WatchCardChip("Cancel", look.ink, fonts, onClick = controls.cancelNext)
    }
    if (wide) WatchRowFrame(look, Modifier.padding(bottom = 12.dp), height = 66.dp, highlighted = true) {
        WatchPicture(next.stillUrl, next.key.value, Modifier.padding(start = 2.dp).size(84.dp, 48.dp), radius = 12.dp)
        words(Modifier.weight(1f))
        buttons()
    } else WatchRowFrame(look, Modifier.padding(bottom = 12.dp), height = 118.dp, highlighted = true) {
        WatchPicture(next.stillUrl, next.key.value, Modifier.padding(start = 4.dp).size(84.dp, 48.dp), radius = 12.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            words(Modifier)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
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
) {
    var audio by remember { mutableStateOf(false) }
    // The phone's card is narrow: the title may take two lines, and Audio and Subtitles are round buttons so all the controls clear the bite.
    val narrow = !wide && !slim
    var subtitles by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
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
                    else -> (if (wide) "Now watching · " else "") + "${longClock(p)} / ${longClock(durationMs)}" + if (durationMs > p) " · " + leftText(durationMs - p) else ""
                }
            }
            OrbitLabel(label, fonts, ink.copy(alpha = 0.72f), Modifier.padding(end = 80.dp))
            Text(summary?.name ?: "Watch", style = fonts.heroTitle.copy(fontSize = if (slim) 20.sp else 24.sp, lineHeight = if (slim) 22.sp else 26.sp), color = ink,
                maxLines = if (narrow) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, end = if (narrow) 0.dp else 80.dp))
            // A channel's line is what is on it now when the guide knows; its own line otherwise.
            (if (state.isLive && programme != null) "On now · $programme" else state.subtitleLine)?.let { Text(it, style = fonts.body, color = ink.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, end = 80.dp)) }
            if (!state.isLive && durationMs > 0) {
                OrbitProgressLine({ (position().toFloat() / durationMs).coerceIn(0f, 1f) }, ink, Modifier.padding(top = 12.dp, end = 90.dp), onSeek = { controls.seekTo((it * durationMs).toLong()) })
            }
            FlowRow(Modifier.padding(top = if (slim) 8.dp else 12.dp, end = 68.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchRoundButton(CosmoIcons.Volume, if (state.muted) "Unmute" else "Mute", look,
                    onClick = controls.toggleMute, ink = ink, on = state.muted)
                if (!state.isLive) {
                    WatchRoundButton(CosmoIcons.Replay, "Back 10 seconds", look, onClick = { controls.seekTo((position() - 10_000).coerceAtLeast(0)) }, ink = ink)
                    WatchRoundButton(CosmoIcons.Next, "Forward 30 seconds", look, onClick = { controls.seekTo(position() + 30_000) }, ink = ink)
                } else {
                    controls.retry?.let { WatchRoundButton(CosmoIcons.Replay, "Jump to live", look, onClick = it, ink = ink) }
                    if (channels.isNotEmpty()) Box {
                        WatchRoundButton(CosmoIcons.Queue, "Channels", look, onClick = { guide = true }, ink = ink)
                        OrbitMenu(guide, { guide = false }, fonts = fonts, look = look, header = "Also on now", items =
                            channels.map { row -> OrbitMenuItem(row.channel.name, row.now?.title) { onChannel(row.channel) } })
                    }
                }
                Box {
                    if (narrow) WatchRoundButton(CosmoIcons.Volume, "Audio", look, onClick = { audio = true }, ink = ink, enabled = state.audioTracks.size > 1)
                    else WatchCardChip("Audio", ink, fonts, onClick = { audio = true }, icon = CosmoIcons.Volume, enabled = state.audioTracks.size > 1)
                    OrbitMenu(audio, { audio = false }, fonts = fonts, look = look, header = "Audio",
                        items = state.audioTracks.map { t -> OrbitMenuItem(t.label, listOfNotNull(t.codec, t.channels?.let { "$it ch" }).joinToString(" · ").ifBlank { null }, selected = t.selected) { controls.selectAudio(t.trackId) } })
                }
                Box {
                    if (narrow) WatchRoundButton(CosmoIcons.Lyrics, "Subtitles", look, onClick = { subtitles = true }, ink = ink)
                    else WatchCardChip("Subtitles", ink, fonts, onClick = { subtitles = true }, icon = CosmoIcons.Lyrics)
                    OrbitMenu(subtitles, { subtitles = false }, fonts = fonts, look = look, header = "Subtitles", width = 300.dp, items = buildList {
                        add(OrbitMenuItem("Off", null, selected = state.selectedSubtitleId == null) { controls.selectSubtitle(null) })
                        state.subtitles.forEach { s -> add(OrbitMenuItem(s.label, listOfNotNull(s.origin.name.lowercase().replaceFirstChar { it.uppercase() }, if (s.hearingImpaired) "SDH" else null).joinToString(" · "), selected = s.id == state.selectedSubtitleId) { controls.selectSubtitle(s.id) }) }
                        add(OrbitMenuItem("Find more subtitles", "OpenSubtitles and your subtitle plugins", CosmoIcons.Search) { controls.searchSubtitles() })
                        if (state.selectedSubtitleId != null) {
                            add(OrbitMenuItem("Earlier by 0.5 s", "Now ${"%+.1f".format(state.subtitleDelayMs / 1000f)} s", keepOpen = true) { controls.subtitleDelay(state.subtitleDelayMs - 500) })
                            add(OrbitMenuItem("Later by 0.5 s", null, keepOpen = true) { controls.subtitleDelay(state.subtitleDelayMs + 500) })
                        }
                    })
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

/** Cinema: the picture takes the whole window; the buttons and the slim card show on movement and fade three seconds after. */
@Composable
private fun WatchCinema(
    picture: @Composable (Modifier, Dp) -> Unit,
    state: WatchPlayerState,
    playing: Boolean,
    position: () -> Long,
    durationMs: Long,
    controls: WatchPlayerControls,
    view: VideoView,
    setView: (VideoView) -> Unit,
    onLeave: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    fill: Color,
    ink: Color,
    programme: String? = null,
    channels: List<com.cosmotune.watch.LiveNowItem> = emptyList(),
    onChannel: (com.cosmotune.watch.model.Channel) -> Unit = {},
) {
    var stir by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    val failed = state.status == WatchPlayerStatus.ERROR || state.status == WatchPlayerStatus.NOT_FOUND
    LaunchedEffect(stir, playing, failed) {
        chrome = true
        if (playing && !failed) { delay(3_000); chrome = false }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF050304)).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Move || e.type == PointerEventType.Press || e.type == PointerEventType.Scroll) stir++
                }
            }
        },
    ) {
        picture(Modifier.fillMaxSize(), 0.dp)
        WatchLoading(state, fonts)
        if (failed) Box(Modifier.align(Alignment.Center).widthIn(max = 500.dp).padding(24.dp)) {
            WatchPlaybackFailure(state, controls, fonts, look)
        }
        AnimatedVisibility(chrome, Modifier.fillMaxSize(), enter = fadeIn(tween(220)), exit = fadeOut(tween(420))) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    WatchRoundButton(CosmoIcons.ChevronDown, "Close the player", look, onClick = controls.close, ink = Color(0xFFF7ECE4))
                    WatchRoundButton(CosmoIcons.Expand, "Exit full screen", look, onClick = onLeave, ink = Color(0xFFF7ECE4))
                }
                if (!failed) Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 860.dp).fillMaxWidth()) {
                    FilmCard(state, playing, position, durationMs, controls, view, setView, fonts, look, fill, ink, slim = true, programme = programme,
                        channels = channels, onChannel = onChannel)
                }
            }
        }
    }
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
                    sources?.streams.orEmpty().forEachIndexed { i, s -> SourceRow(s, best = i == 0, fonts = fonts, look = look, selected = s == route.stream) {} }
                }
            }
        }
    }
}
