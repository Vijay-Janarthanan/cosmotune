package com.cosmotune.music.ui.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.music.ui.tv.components.TvActivityIndicator
import com.cosmotune.music.ui.tv.components.TvArtwork
import com.cosmotune.music.ui.tv.components.TvButton
import com.cosmotune.music.ui.tv.components.TvColourCard
import com.cosmotune.music.ui.tv.components.TvDialog
import com.cosmotune.music.ui.tv.components.TvDialogList
import com.cosmotune.music.ui.tv.components.TvListRow
import com.cosmotune.music.ui.tv.components.tvFocusRing
import com.cosmotune.music.ui.tv.components.tvInitialFocus
import com.cosmotune.music.ui.tv.theme.TvDimensions
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvType
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.pip.PipOwner
import com.cosmotune.ui.pip.rememberPip
import com.cosmotune.ui.watch.LocalWatchRepository
import com.cosmotune.ui.watch.channelAfter
import com.cosmotune.ui.watch.rememberWatchPip
import com.cosmotune.watch.ChannelFilter
import com.cosmotune.watch.model.Channel
import com.cosmotune.ui.watch.WatchBackend
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchPlayback
import com.cosmotune.ui.watch.WatchRoute
import com.cosmotune.ui.watch.WatchSurfaceScale
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.watch.LiveNowItem
import com.cosmotune.watch.WATCH_SKIP_MS
import com.cosmotune.watch.WatchPlayer
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.WatchStreamFilter
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.watchMetadata
import kotlinx.coroutines.delay

/** How long the film card stays up after the last press while the film plays. */
private const val CARD_MS = 5_000L

/**
 * Watch's player on the TV: the picture fills the screen (Watch's Media3 surface from [WatchPlayback]) and the film card comes up
 * over its foot only when asked. With the card down the remote drives the film: centre plays and pauses, left and right seek
 * by 30 seconds, and up brings up the card (focus on its play button); the play and media keys work at any time. The card has
 * back and forward, Sources, Audio, Subtitles and the screen (Whole or Fill), and goes again five seconds after the last press while
 * the film plays. Back hides the visible card before leaving the player. The route's target is opened unless
 * the room already holds it, and stopped when the player goes.
 */
@Composable
internal fun TvWatchPlayer(route: WatchRoute.Play, playback: WatchPlayback?, modifier: Modifier = Modifier) {
    if (playback == null) {
        TvWatchPlayerMessage(
            title = "The player is not ready",
            body = "Watch's player could not start on this TV.",
            modifier = modifier,
        )
        return
    }
    val player = playback.player
    LaunchedEffect(route) { if (!route.attached && !playback.roomHolds(route.target.key)) player.open(route.target, route.summary, route.stream) }
    DisposableEffect(player, route) {
        onDispose {
            val key = player.state.value.target?.key ?: route.target.key
            if (!route.attached && !playback.roomHolds(key)) player.stop()
        }
    }
    // Home on the remote (the app going to the background) pauses the film rather than leaving it playing behind the launcher.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.engine.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val state by player.state.collectAsState()
    val snapshot by player.engine.snapshot.collectAsState()
    val playing = snapshot.playing
    val durationMs = state.durationMs.takeIf { it > 0 } ?: snapshot.durationMs
    var scale by remember { mutableStateOf(WatchSurfaceScale.FIT) }
    var cardUp by remember { mutableStateOf(true) }
    var stir by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<TvWatchMenu?>(null) }
    var filter by remember(route.target.key) { mutableStateOf(WatchStreamFilter()) }
    var flash by remember { mutableStateOf<TvFlash?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    var seekNote by remember { mutableStateOf<String?>(null) }
    var seekTick by remember { mutableIntStateOf(0) }
    val stage = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val failed = state.status == WatchPlayerStatus.NOT_FOUND || state.status == WatchPlayerStatus.ERROR
    val countingDown = state.nextEpisode != null && state.nextEpisodeInMs != null
    BackHandler(enabled = cardUp && menu == null && !failed) { cardUp = false }

    val playPause: () -> Unit = { if (player.engine.snapshot.value.playing) player.engine.pause() else player.engine.play() }
    val retry: (() -> Unit)? = if (playback.roomHolds(route.target.key)) null else ({
        val start = if (route.target is PlayTarget.Live) 0L else player.engine.positionMs().takeIf { it > 0 }
        val selected = player.state.value.takeIf { it.target?.key == route.target.key }?.stream
        player.open(route.target, route.summary, selected ?: route.stream, startMs = start)
    })
    val repository = LocalWatchRepository.current ?: WatchBackend.repository
    // Picture-in-picture (Home on the remote, or the card's button): the picture alone in the system's small window, with play and
    // pause and, for an episode the next one, for a channel the next in its playlist. Closing it pauses the film.
    val pip = rememberPip(PipOwner.WATCH)
    val liveChannel = (route.target as? PlayTarget.Live)?.channel
    val playlist by produceState(emptyList<Channel>(), liveChannel) {
        if (liveChannel != null) value = runCatching { repository.channels(ChannelFilter(pluginId = liveChannel.pluginId)) }.getOrDefault(emptyList())
    }
    val nextChannel = liveChannel?.let { channelAfter(playlist, it.key) }
    rememberWatchPip(pip, player, state, playing, snapshot.videoWidth, snapshot.videoHeight, local = true,
        nextChannel = nextChannel?.let { next -> { WatchNav.playChannel(next) } }, surface = playback.surface)
    val channels by produceState(emptyList<LiveNowItem>(), route.target.key, menu) {
        if (menu == TvWatchMenu.CHANNELS) {
            value = runCatching { repository.liveNow(20) }.getOrDefault(emptyList()).filter { it.channel.key != route.target.key }
        }
    }
    val seekBy: (Long) -> Unit = { delta ->
        val to = (player.engine.positionMs() + delta).coerceAtLeast(0L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        player.engine.seekTo(to)
        seekNote = (if (delta < 0) "− ${-delta / 1000} s" else "+ ${delta / 1000} s") + " · " + clock(to)
        seekTick++
    }

    // The card goes a while after the last press, while the film plays and nothing on it needs an answer.
    LaunchedEffect(cardUp, stir, playing, menu, countingDown, failed) {
        if (cardUp && playing && menu == null && !countingDown && !failed) {
            delay(CARD_MS)
            cardUp = false
        }
    }
    LaunchedEffect(countingDown) { if (countingDown) cardUp = true }
    // Focus follows the card: its play button while it is up, the picture (which takes the remote's keys) while it is down.
    LaunchedEffect(cardUp, failed) {
        delay(16)
        if (failed) return@LaunchedEffect
        runCatching { if (cardUp) playFocus.requestFocus() else stage.requestFocus() }
    }

    val keys = Modifier.onPreviewKeyEvent { e ->
        stir++
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.MediaPlayPause -> { playPause(); true }
            Key.MediaPlay -> { player.engine.play(); true }
            Key.MediaPause -> { player.engine.pause(); true }
            Key.MediaRewind -> { if (!state.isLive) seekBy(-WATCH_SKIP_MS); true }
            Key.MediaFastForward -> { if (!state.isLive) seekBy(WATCH_SKIP_MS); true }
            else -> if (cardUp || failed) false else stageKey(e, state, onPlayPause = {
                playPause()
                flash = if (player.engine.snapshot.value.playing) TvFlash.Pause else TvFlash.Play
                flashTick++
            }, onSeek = seekBy, onCard = { cardUp = true })
        }
    }

    if (pip.active) {
        // In picture-in-picture the window is the picture alone: no card, no notes, nothing over it.
        Box(modifier.fillMaxSize().background(Color.Black)) { playback.surface(scale, Modifier.fillMaxSize()) }
        return
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(keys)
            // The player holds the remote: focus never wanders out to the chips behind it; Back leaves.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        val summary = state.summary ?: route.summary
        val still = summary?.backdropUrl ?: summary?.posterUrl
        if (still != null && !playing && state.status != WatchPlayerStatus.PAUSED) {
            TvArtwork(url = still, shape = RectangleShape, px = 1280, modifier = Modifier.fillMaxSize())
        }
        playback.surface(scale, Modifier.fillMaxSize())
        // The picture takes focus while the card is down, so the remote's keys reach [keys].
        Box(Modifier.fillMaxSize().focusRequester(stage).focusable().pointerInput(failed) {
            if (!failed) detectTapGestures { cardUp = !cardUp }
        })

        when {
            failed -> TvWatchPlayerMessage(
                title = if (state.status == WatchPlayerStatus.NOT_FOUND) "No playable copy yet" else "This copy did not play",
                body = state.error ?: "Switch on more plugins, or add your own server or add-on in Plugins.",
                action = "Retry".takeIf { retry != null },
                onAction = retry,
                onSources = if (state.streams.any { it.isPlayable }) ({ menu = TvWatchMenu.SOURCES }) else null,
                showPlugins = true,
            )
            state.status == WatchPlayerStatus.IDLE || state.status == WatchPlayerStatus.RESOLVING ||
                state.status == WatchPlayerStatus.LOADING || state.status == WatchPlayerStatus.BUFFERING -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TvActivityIndicator(size = 36.dp)
                Text(
                    if (state.status == WatchPlayerStatus.BUFFERING) "Loading" else if (state.status == WatchPlayerStatus.LOADING) "Loading the copy" else "Finding the best copy",
                    style = TvType.Callout, color = TvGlass.TextSecondary, modifier = Modifier.padding(top = 14.dp),
                )
            }
            else -> Unit
        }

        TvFlashGlyph(flash, flashTick, Modifier.align(Alignment.BottomCenter))
        TvSeekNote(seekNote, seekTick, Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            visible = cardUp && !failed,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 4 },
            exit = ExitTransition.None,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .padding(horizontal = TvDimensions.SafeMarginHorizontal, vertical = 28.dp),
            ) {
                if (countingDown) TvNextEpisode(state, player)
                TvFilmCard(
                    state = state,
                    route = route,
                    player = player,
                    playing = playing,
                    durationMs = durationMs,
                    playFocus = playFocus,
                    onPlayPause = playPause,
                    onSeek = seekBy,
                    onMenu = { menu = it },
                    onLive = retry,
                    onFullScreen = { cardUp = false },
                    onPip = if (pip.supported) ({ pip.enter(); Unit }) else null,
                )
            }
        }
    }

    when (menu) {
        TvWatchMenu.SOURCES -> TvDialog(onDismissRequest = { menu = null }, title = "Play from", message = "Best first", width = 560.dp) {
            val providers = state.streams.map { it.source }.distinctBy { it.pluginId }
            val qualities = WatchStreamFilter.qualities(state.streams)
            val matches = filter.applyTo(state.streams)
            val runtime = state.summary?.takeIf { it.type == TitleType.FILM }?.runtimeMin
            TvSourceRows {
                if (qualities.size > 1 || filter.quality != null) {
                    TvListRow("Quality", value = filter.quality ?: "All", onClick = { menu = TvWatchMenu.SOURCE_QUALITY },
                        modifier = Modifier.tvInitialFocus(true))
                }
                if (providers.size > 1 || filter.providerId != null) {
                    TvListRow("Provider", value = providers.firstOrNull { it.pluginId == filter.providerId }?.pluginName ?: "All",
                        onClick = { menu = TvWatchMenu.SOURCE_PROVIDER }, modifier = Modifier.tvInitialFocus(qualities.size <= 1 && filter.quality == null))
                }
                if (matches.isEmpty()) TvListRow("No streams match", subtitle = "Reset filters", onClick = { filter = WatchStreamFilter() })
                matches.forEach { s ->
                    TvListRow(
                        title = "${s.source.pluginName} · ${s.label}",
                        subtitle = listOfNotNull(
                            s.watchMetadata(state.durationMs.takeIf { s == state.stream }, runtime),
                            s.description?.lineSequence()?.firstOrNull { it.isNotBlank() },
                        ).joinToString("\n"),
                        value = if (s == state.stream) "Playing" else null,
                        enabled = s.isPlayable,
                        onClick = { menu = null; if (s != state.stream || failed) player.switchStream(s) },
                        modifier = Modifier.tvInitialFocus(qualities.size <= 1 && providers.size <= 1 && s == matches.firstOrNull { it.isPlayable }),
                    )
                }
            }
        }
        TvWatchMenu.SOURCE_QUALITY, TvWatchMenu.SOURCE_PROVIDER -> {
            val quality = menu == TvWatchMenu.SOURCE_QUALITY
            val options = if (quality) WatchStreamFilter.qualities(state.streams).map { it to it }
            else state.streams.map { it.source }.distinctBy { it.pluginId }.map { it.pluginId to it.pluginName }
            TvDialog(onDismissRequest = { menu = TvWatchMenu.SOURCES }, title = if (quality) "Quality" else "Provider") {
                TvSourceRows {
                    (listOf(null to "All") + options).forEach { (id, label) ->
                        val selected = id == if (quality) filter.quality else filter.providerId
                        TvListRow(label, value = "Selected".takeIf { selected }, modifier = Modifier.tvInitialFocus(selected), onClick = {
                            filter = if (quality) filter.copy(quality = id) else filter.copy(providerId = id)
                            menu = TvWatchMenu.SOURCES
                        })
                    }
                }
            }
        }
        TvWatchMenu.AUDIO -> TvDialog(onDismissRequest = { menu = null }, title = "Audio", width = 520.dp) {
            TvDialogList {
                state.audioTracks.forEach { t ->
                    TvListRow(
                        title = t.label,
                        subtitle = listOfNotNull(t.codec, t.channels?.let { "$it ch" }).joinToString(" · ").ifBlank { null },
                        value = if (t.selected) "On" else null,
                        onClick = { menu = null; player.selectAudio(t.trackId) },
                        modifier = Modifier.tvInitialFocus(t.selected),
                    )
                }
            }
        }
        TvWatchMenu.SUBTITLES -> TvDialog(onDismissRequest = { menu = null }, title = "Subtitles", width = 560.dp) {
            TvDialogList {
                TvListRow(
                    title = "Off",
                    value = if (state.selectedSubtitleId == null) "On" else null,
                    onClick = { menu = null; player.selectSubtitle(null) },
                    modifier = Modifier.tvInitialFocus(state.selectedSubtitleId == null),
                )
                state.subtitles.forEach { s ->
                    TvListRow(
                        title = s.label,
                        subtitle = listOfNotNull(s.origin.name.lowercase().replaceFirstChar { it.uppercase() }, if (s.hearingImpaired) "SDH" else null).joinToString(" · "),
                        value = if (s.id == state.selectedSubtitleId) "On" else null,
                        onClick = { menu = null; player.selectSubtitle(s.id) },
                        modifier = Modifier.tvInitialFocus(s.id == state.selectedSubtitleId),
                    )
                }
                TvListRow(title = "Find more subtitles", subtitle = "OpenSubtitles and your subtitle plugins", onClick = { menu = null; player.searchSubtitles() })
                if (state.selectedSubtitleId != null) {
                    TvListRow(title = "Earlier by 0.5 s", value = "%+.1f s".format(state.subtitleDelayMs / 1000f), onClick = { player.setSubtitleDelay(state.subtitleDelayMs - 500) })
                    TvListRow(title = "Later by 0.5 s", onClick = { player.setSubtitleDelay(state.subtitleDelayMs + 500) })
                }
            }
        }
        TvWatchMenu.SCREEN -> TvDialog(onDismissRequest = { menu = null }, title = "Picture size", width = 480.dp) {
            TvDialogList {
                TvListRow(title = "Fit", subtitle = "Shows the full picture", value = if (scale == WatchSurfaceScale.FIT) "On" else null,
                    onClick = { menu = null; scale = WatchSurfaceScale.FIT }, modifier = Modifier.tvInitialFocus(scale == WatchSurfaceScale.FIT))
                TvListRow(title = "Fill", subtitle = "Fills the screen, cuts the edges", value = if (scale == WatchSurfaceScale.CROP) "On" else null,
                    onClick = { menu = null; scale = WatchSurfaceScale.CROP }, modifier = Modifier.tvInitialFocus(scale == WatchSurfaceScale.CROP))
            }
        }
        TvWatchMenu.CHANNELS -> TvDialog(onDismissRequest = { menu = null }, title = "Channels", width = 560.dp) {
            TvDialogList {
                if (channels.isEmpty()) TvListRow(title = "Open the live guide", modifier = Modifier.tvInitialFocus(true),
                    onClick = { menu = null; WatchNav.back(); WatchNav.go(WatchTab.LIVE) })
                channels.forEachIndexed { index, row ->
                    TvListRow(title = row.channel.name, subtitle = row.now?.title, modifier = Modifier.tvInitialFocus(index == 0), onClick = {
                        menu = null
                        WatchNav.playChannel(row.channel)
                    })
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun TvSourceRows(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    TvDialogList(
        Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)
            .verticalScroll(rememberScrollState()).padding(4.dp),
        content = content,
    )
}

private enum class TvWatchMenu { SOURCES, SOURCE_QUALITY, SOURCE_PROVIDER, AUDIO, SUBTITLES, SCREEN, CHANNELS }

/** The remote with the card down: centre plays and pauses, left and right seek (not live), up brings up the card. */
private fun stageKey(e: KeyEvent, state: WatchPlayerState, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onCard: () -> Unit): Boolean = when (e.key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (state.isLive) onCard() else onPlayPause(); true }
    Key.Spacebar -> { onPlayPause(); true }
    Key.DirectionLeft -> { if (state.isLive) onCard() else onSeek(-WATCH_SKIP_MS); true }
    Key.DirectionRight -> { if (state.isLive) onCard() else onSeek(WATCH_SKIP_MS); true }
    Key.DirectionUp, Key.DirectionDown -> { onCard(); true }
    else -> false
}

/**
 * The film card (the shared player's, made for the sofa): the time and what is left, the title and the episode, the orbit round
 * the poster as how far in, back 10 and forward 30, Sources, Audio, Subtitles and Screen; play in the bite.
 */
@Composable
private fun TvFilmCard(
    state: WatchPlayerState,
    route: WatchRoute.Play,
    player: WatchPlayer,
    playing: Boolean,
    durationMs: Long,
    playFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onMenu: (TvWatchMenu) -> Unit,
    onLive: (() -> Unit)?,
    onFullScreen: () -> Unit,
    onPip: (() -> Unit)? = null,
) {
    val summary = state.summary ?: route.summary
    // A channel: what is on it now and what comes next, from the guide, asked again as each programme ends.
    val channel = (route.target as? PlayTarget.Live)?.channel
    val repository = LocalWatchRepository.current ?: WatchBackend.repository
    val onAir by produceState<LiveNowItem?>(null, channel, repository) {
        if (channel == null) return@produceState
        while (true) {
            val now = runCatching { repository.nowNext(channel) }.getOrNull()
            value = now
            val ends = now?.now?.stopMs?.minus(System.currentTimeMillis())
            delay((ends ?: 60_000L).coerceIn(15_000L, 10 * 60_000L))
        }
    }
    val programme = onAir?.now
    // The label is redrawn every half second, not every frame.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) { while (playing) { delay(500); tick++ } }
    // The orbit is drawn from the engine's own clock every frame (read in the draw pass), so it glides between those half seconds.
    val ringFrame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) { while (playing) withInfiniteAnimationFrameNanos { ringFrame.longValue = it } }
    val label = run {
        tick
        val p = player.engine.positionMs()
        when {
            state.isLive -> if (programme != null) "● Live · " + (summary?.name ?: channel?.name.orEmpty()) else "● Live"
            state.status == WatchPlayerStatus.RESOLVING -> "Finding the best copy"
            state.status == WatchPlayerStatus.LOADING || state.status == WatchPlayerStatus.BUFFERING -> "Loading · ${clock(p)}"
            durationMs > 0 -> "${clock(p)} / ${clock(durationMs)}" + if (durationMs > p) " · " + left(durationMs - p) else ""
            else -> clock(p)
        }
    }
    val ink = coverFill(summary?.backdropUrl ?: summary?.posterUrl, summary?.key?.value ?: route.target.key.value).second
    val liveLine = if (state.isLive && programme != null) {
        listOfNotNull(
            hourMinute(programme.startMs) + " – " + hourMinute(programme.stopMs),
            onAir?.next?.let { "Next " + hourMinute(it.startMs) + " " + it.title.substringAfter(": ") },
        ).joinToString(" · ")
    } else null
    // A channel's own "Live" line says nothing the label does not; the programme's times and what is next say more.
    val subtitle = listOfNotNull(liveLine ?: state.subtitleLine.takeUnless { state.isLive && it.equals("Live", ignoreCase = true) }, state.failover, state.lengthNote)
        .joinToString(" · ").ifBlank { null }
    TvColourCard(
        cover = summary?.posterUrl ?: summary?.backdropUrl,
        key = summary?.key?.value ?: route.target.key.value,
        label = label,
        title = programme?.title?.substringAfter(": ") ?: summary?.name ?: "Watch",
        subtitle = subtitle,
        playing = playing,
        onPlay = onPlayPause,
        height = 236.dp,
        progress = if (durationMs > 0 && !state.isLive) ({ ringFrame.longValue; (player.engine.positionMs().toFloat() / durationMs).coerceIn(0f, 1f) }) else null,
        spinning = playing,
        playFocus = playFocus,
        below = {
            // The row scrolls, so it keeps room inside itself for its buttons' focus rings.
            Row(
                Modifier.padding(top = 6.dp).offset(x = (-10).dp).horizontalScroll(rememberScrollState()).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.isLive) {
                    TvWatchRound(CosmoIcons.Replay30, "Back 30 seconds", ink) { onSeek(-WATCH_SKIP_MS) }
                    TvWatchRound(CosmoIcons.Forward30, "Forward 30 seconds", ink) { onSeek(WATCH_SKIP_MS) }
                } else {
                    TvButton("Channels", onClick = { onMenu(TvWatchMenu.CHANNELS) })
                    onLive?.let { TvButton("Jump to live", onClick = it) }
                }
                TvWatchRound(CosmoIcons.Expand, "Full screen", ink, onFullScreen)
                onPip?.let { TvWatchRound(CosmoIcons.Pip, "Picture in picture", ink, it) }
                TvButton(if (state.muted) "Unmute" else "Mute", onClick = player::toggleMute)
                if (state.streams.isNotEmpty()) TvButton("Sources", onClick = { onMenu(TvWatchMenu.SOURCES) })
                if (state.audioTracks.size > 1) TvButton("Audio", onClick = { onMenu(TvWatchMenu.AUDIO) })
                TvButton("Subtitles", onClick = { onMenu(TvWatchMenu.SUBTITLES) })
                TvWatchRound(CosmoIcons.Canvas, "Screen", ink) { onMenu(TvWatchMenu.SCREEN) }
            }
        },
    )
}

/** "21:45", in the TV's own time zone. */
private fun hourMinute(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** Near the end of an episode: "Next episode in 8 s", what it is, Play now (focused) and Cancel. */
@Composable
private fun TvNextEpisode(state: WatchPlayerState, player: WatchPlayer) {
    val next = state.nextEpisode ?: return
    val inMs = state.nextEpisodeInMs ?: return
    val playNow = remember { FocusRequester() }
    LaunchedEffect(next.key) { delay(32); runCatching { playNow.requestFocus() } }
    Row(
        Modifier.padding(bottom = 16.dp).clip(RoundedCornerShape(22.dp)).background(TvGlass.FillStrong).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.widthIn(max = 520.dp)) {
            Text("NEXT EPISODE IN ${(inMs + 999) / 1000} S", style = TvType.Label, color = TvGlass.Peach)
            Text("S${next.season} E${next.number} · ${next.name}", style = TvType.Headline, color = TvGlass.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TvButton("Play now", onClick = player::playNextEpisode, isPrimary = true, modifier = Modifier.focusRequester(playNow))
        TvButton("Cancel", onClick = player::cancelNextEpisode)
    }
}

/** A round tonal button on the card, in the card's ink, ringed in peach on focus. */
@Composable
private fun TvWatchRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color, onClick: () -> Unit) {
    OrbitRoundButton(icon = icon, label = label, ink = ink, size = 44.dp, onClick = onClick, modifier = Modifier.tvFocusRing(CircleShape))
}

/** "+ 30 s · 0:41:02" low on the screen after a seek with the card down. Shows, holds, fades. */
@Composable
private fun TvSeekNote(note: String?, tick: Int, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(900)
        alpha.animateTo(0f, tween(360))
    }
    val shown = note ?: return
    Text(
        shown,
        style = TvType.Headline,
        color = TvGlass.TextPrimary,
        modifier = modifier
            .padding(bottom = 48.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

/** A message in the middle of the player (no copy, the player missing), with one button and Back to leave. */
@Composable
private fun TvWatchPlayerMessage(title: String, body: String, modifier: Modifier = Modifier, action: String? = null,
    onAction: (() -> Unit)? = null, onSources: (() -> Unit)? = null, showPlugins: Boolean = false) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = TvType.Title, color = TvGlass.TextPrimary, textAlign = TextAlign.Center)
            Text(body, style = TvType.Callout, color = TvGlass.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (action != null && onAction != null) TvButton(action, onClick = onAction, isPrimary = true, modifier = Modifier.tvInitialFocus())
                if (onSources != null) TvButton("Sources", onClick = onSources, modifier = Modifier.tvInitialFocus(action == null))
                if (showPlugins) TvButton("Open Plugins", onClick = { WatchNav.back(); WatchNav.go(WatchTab.PLUGINS) })
                TvButton("Back", onClick = { WatchNav.back() }, modifier = Modifier.tvInitialFocus(action == null && onSources == null))
            }
        }
    }
}

/** "0:40:12" for a position in a film. */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "24 min left", "1h 12m left". */
private fun left(ms: Long): String {
    val min = ((ms + 59_999) / 60_000).toInt()
    return if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m left" else "$min min left"
}
