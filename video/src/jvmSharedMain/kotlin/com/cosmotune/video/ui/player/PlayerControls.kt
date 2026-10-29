package com.cosmotune.video.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.LiveWindow
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerKeyAction
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.ScrubberGeometry
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_badge_live
import com.cosmotune.video.resources.video_badge_upcoming
import com.cosmotune.video.resources.video_captions
import com.cosmotune.video.resources.video_collapse
import com.cosmotune.video.resources.video_error_age
import com.cosmotune.video.resources.video_error_bot
import com.cosmotune.video.resources.video_error_geo
import com.cosmotune.video.resources.video_error_kids
import com.cosmotune.video.resources.video_error_network
import com.cosmotune.video.resources.video_error_paid
import com.cosmotune.video.resources.video_error_parsing
import com.cosmotune.video.resources.video_error_private
import com.cosmotune.video.resources.video_error_stopped
import com.cosmotune.video.resources.video_error_unavailable
import com.cosmotune.video.resources.video_exit_fullscreen
import com.cosmotune.video.resources.video_full_window
import com.cosmotune.video.resources.video_fullscreen
import com.cosmotune.video.resources.video_live_starts
import com.cosmotune.video.resources.video_next
import com.cosmotune.video.resources.video_pip
import com.cosmotune.video.resources.video_player_settings
import com.cosmotune.video.resources.video_previous
import com.cosmotune.video.resources.video_replay
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.resources.video_seek_back
import com.cosmotune.video.resources.video_seek_forward
import com.cosmotune.video.resources.video_time_toggle
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The chrome's numbers that VideoMetrics does not carry, each from spec 6.5 or 5.4:
 * the top row's padding 8; the bottom row's padding 0 12 8 (full screen 0 24 16); the title 15.5 sp 650; the "Skip" pill 12 dp from
 * the right; the full window's bar 80 dp tall with radius 28 (CosmoRadii.xl).
 */
private const val TOP_ROW_PAD_DP = 8f
private const val BOTTOM_PAD_SIDE_DP = 12f
private const val BOTTOM_PAD_BOTTOM_DP = 8f
private const val BOTTOM_PAD_SIDE_FULL_DP = 24f
private const val BOTTOM_PAD_BOTTOM_FULL_DP = 16f
private const val TITLE_SP = 15.5f
private const val TITLE_WEIGHT = 650
private const val TIME_WEIGHT = 600
private const val TIME_ALPHA = 0.9f
internal const val SKIP_END_DP = 12f
private const val FULL_WINDOW_BAR_H_DP = 80f
private const val FULL_WINDOW_BAR_SIDE_DP = 24f
private const val FULL_WINDOW_BAR_PAD_DP = 12f

/** The scrubber's touch band: 48 dp, the app's touch target (the line itself is 3 dp). */
internal const val SCRUB_TOUCH_DP = 48f

/** The scrubber's band inside the full window's bar (the bar is 80 dp: the 48 dp transport row and this). */
private const val SCRUB_BAR_TOUCH_DP = 24f

/** The badge of a live stream (spec 5.0: 20 high, padding 0 6, radius 6, 12 sp 650 tabular, on `danger`). */
private const val LIVE_WEIGHT = 650

/**
 * The chrome's numbers for one layout (spec 6.5): the page's ([PlayerLayout.PAGE]) or full screen's ([full], also the desktop page),
 * and the full window's bar ([fullWindow]). Built once per layout.
 */
@Immutable
internal class ChromeMetrics private constructor(val full: Boolean, val fullWindow: Boolean) {
    val scrimTopDp: Float = if (full) VideoMetrics.SCRIM_TOP_FULL_DP else VideoMetrics.SCRIM_TOP_PAGE_DP
    val scrimBottomDp: Float = if (full) VideoMetrics.SCRIM_BOTTOM_FULL_DP else VideoMetrics.SCRIM_BOTTOM_PAGE_DP
    val centreGapDp: Float = if (full) VideoMetrics.CENTRE_GAP_FULL_DP else VideoMetrics.CENTRE_GAP_PAGE_DP
    val bottomSideDp: Float = if (full) BOTTOM_PAD_SIDE_FULL_DP else BOTTOM_PAD_SIDE_DP
    val bottomBottomDp: Float = if (full) BOTTOM_PAD_BOTTOM_FULL_DP else BOTTOM_PAD_BOTTOM_DP

    /** The top row's height: its padding, a 40 dp button, its padding. */
    val topRowDp: Float = TOP_ROW_PAD_DP * 2 + VideoMetrics.CHROME_ICON_DP

    /**
     * Where the chips ("2x speed", "Muted: ...") stand from the top: in the page (202 dp tall) on the top row's middle line, between
     * its buttons, so they miss the centre; in full screen under the top row.
     */
    val chipTopDp: Float = if (full) topRowDp else TOP_ROW_PAD_DP + (VideoMetrics.CHROME_ICON_DP - VideoMetrics.SEEK_PILL_H_DP) / 2

    /** What the bottom controls take from the bottom edge (the subtitles stand above it while the chrome shows). */
    val bottomZoneDp: Float = when {
        fullWindow -> VideoMetrics.FULL_WINDOW_BAR_BOTTOM_DP + FULL_WINDOW_BAR_H_DP
        full -> VideoMetrics.SCRUB_FULL_BOTTOM_DP + VideoMetrics.SCRUB_DOT_DRAG_DP / 2
        else -> BOTTOM_PAD_BOTTOM_DP + VideoMetrics.CHROME_ICON_DP
    }

    /** How far the subtitles rise while the chrome shows: above the bottom row, with the top row's 8 dp of air. */
    val subtitleLiftDp: Float = bottomZoneDp + TOP_ROW_PAD_DP

    /** The "Skip" pill's distance from the bottom (spec 6.5: 48, full screen 96). */
    val skipBottomDp: Float = if (full) VideoMetrics.SKIP_BOTTOM_FULL_DP else VideoMetrics.SKIP_BOTTOM_PAGE_DP

    /** The toast's distance from the bottom: on the page's bottom edge (the box is short), above the controls in full screen. */
    val toastBottomDp: Float = if (full) bottomZoneDp else 0f

    companion object {
        private val PAGE = ChromeMetrics(full = false, fullWindow = false)
        private val FULL = ChromeMetrics(full = true, fullWindow = false)
        private val FULL_WINDOW = ChromeMetrics(full = true, fullWindow = true)

        fun of(layout: PlayerLayout, fullWindow: Boolean): ChromeMetrics = when {
            fullWindow -> FULL_WINDOW
            layout == PlayerLayout.PAGE -> PAGE
            else -> FULL
        }
    }
}

/**
 * The controls over the video (spec 6.5, 5.4): the top row; unless the video failed, the centre and, in the page, full screen and the
 * desktop page, the scrubber under the bottom row (so the row's buttons take their own taps); in the full window, the glass bar.
 */
@Composable
internal fun BoxScope.ChromeControls(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    layout: PlayerLayout,
    fullWindow: Boolean,
    metrics: ChromeMetrics,
    title: String?,
    failed: Boolean,
    buffering: Boolean,
    playhead: Playhead,
    scrubbing: MutableState<Boolean>,
    onCollapse: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onEnterPip: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    settingsMenu: (@Composable () -> Unit)?,
    onInput: () -> Unit,
    onScrub: (Boolean) -> Unit,
) {
    val step = settings.doubleTapSeconds
    val tools: @Composable () -> Unit = {
        CaptionsButton(playback, state, settings, onInput, floatingPlain = fullWindow)
        Box {
            CosmoIconButton(
                icon = CosmoIcons.Settings,
                contentDescription = stringResource(Res.string.video_player_settings),
                onClick = { onInput(); onOpenSettings() },
                style = if (fullWindow) CosmoIconButtonStyle.PLAIN else CosmoIconButtonStyle.GLASS,
                floating = true,
                size = VideoMetrics.CHROME_ICON_DP.dp,
            )
            settingsMenu?.invoke()
        }
        if (onEnterPip != null) {
            CosmoIconButton(
                icon = VideoIcons.Pip,
                contentDescription = stringResource(Res.string.video_pip),
                onClick = onEnterPip,
                style = if (fullWindow) CosmoIconButtonStyle.PLAIN else CosmoIconButtonStyle.GLASS,
                floating = true,
                size = VideoMetrics.CHROME_ICON_DP.dp,
            )
        }
    }

    // ---- the top row ----
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(TOP_ROW_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layout == PlayerLayout.PAGE && onCollapse != null) {
            CosmoIconButton(
                icon = CosmoIcons.ChevronDown,
                contentDescription = stringResource(Res.string.video_collapse),
                onClick = onCollapse,
                style = CosmoIconButtonStyle.GLASS,
                size = VideoMetrics.CHROME_ICON_DP.dp,
            )
        }
        if ((layout == PlayerLayout.FULLSCREEN || fullWindow) && title != null) ChromeTitle(title, Modifier.weight(1f).padding(horizontal = TOP_ROW_PAD_DP.dp))
        else Spacer(Modifier.weight(1f))
        if (!fullWindow) Row(verticalAlignment = Alignment.CenterVertically) { tools() }
    }

    if (failed) return

    if (fullWindow) {
        FullWindowBar(playback, state, settings, step, buffering, playhead, onToggleFullscreen, onInput, onScrub, tools, Modifier.align(Alignment.BottomCenter))
        return
    }

    // ---- the centre ----
    CentreControls(playback, state, layout, metrics, step, buffering, playhead, onInput, Modifier.align(Alignment.Center))

    // ---- the scrubber, then the bottom row over it ----
    val full = metrics.full
    PlayerScrubber(
        playback = playback,
        state = state,
        settings = settings,
        playhead = playhead,
        lineAtBottom = !full,
        onGlass = false,
        onScrub = { start -> scrubbing.value = start; onScrub(start) },
        onInput = onInput,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(
                start = if (full) VideoMetrics.SCRUB_FULL_SIDE_DP.dp else 0.dp,
                end = if (full) VideoMetrics.SCRUB_FULL_SIDE_DP.dp else 0.dp,
                bottom = if (full) (VideoMetrics.SCRUB_FULL_BOTTOM_DP - SCRUB_TOUCH_DP / 2).dp else 0.dp,
            )
            .height(SCRUB_TOUCH_DP.dp),
    )
    Row(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(start = metrics.bottomSideDp.dp, end = metrics.bottomSideDp.dp, bottom = metrics.bottomBottomDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.live) LiveStatus(playback, state, playhead, onInput) else TimeText(playhead, state.durationMs, onGlass = false, onInput = onInput)
        Spacer(Modifier.weight(1f))
        val fullscreen = layout == PlayerLayout.FULLSCREEN
        CosmoIconButton(
            icon = if (fullscreen) VideoIcons.FullscreenExit else VideoIcons.Fullscreen,
            contentDescription = stringResource(
                when (layout) {
                    PlayerLayout.FULLSCREEN -> Res.string.video_exit_fullscreen
                    PlayerLayout.WINDOW -> Res.string.video_full_window
                    PlayerLayout.PAGE -> Res.string.video_fullscreen
                },
            ),
            onClick = { onInput(); onToggleFullscreen() },
            style = CosmoIconButtonStyle.GLASS,
            size = VideoMetrics.CHROME_ICON_DP.dp,
        )
    }
}

/** The title in full screen (spec 6.5: 15.5 sp 650, white over the scrim: the documented exception). */
@Composable
private fun ChromeTitle(title: String, modifier: Modifier) {
    val type = LocalCosmoType.current
    val style = remember(type, title) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(TITLE_WEIGHT), fontSize = TITLE_SP.sp, lineHeight = TITLE_SP.sp * 1.3f), title)
    }
    Text(title, style = style, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * The centre (spec 6.5): back and forward by the double-tap step (C icon buttons 56), the C play button 64 (Replay alone once the
 * video ended and nothing is next), 28 dp apart in the page and 40 in full screen; previous and next (48) only outside the page with a
 * queue.
 */
@Composable
private fun CentreControls(
    playback: VideoPlayback,
    state: PlaybackUiState,
    layout: PlayerLayout,
    metrics: ChromeMetrics,
    step: Int,
    buffering: Boolean,
    playhead: Playhead,
    onInput: () -> Unit,
    modifier: Modifier,
) {
    val queueButtons = layout != PlayerLayout.PAGE && state.queue.isNotEmpty()
    // Once the video has ended, Replay stands alone (back and forward would look like it and seek nothing worth seeking).
    val ended = state.status == PlayerStatus.ENDED && state.item != null
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(metrics.centreGapDp.dp), verticalAlignment = Alignment.CenterVertically) {
        if (queueButtons) QueueButton(previous = true, playback, onInput)
        if (!ended) SeekButton(forward = false, step, playback, playhead, onInput, VideoMetrics.CENTRE_BUTTON_DP, CosmoIconButtonStyle.GLASS)
        PlayOrReplay(playback, state, buffering, VideoMetrics.CENTRE_PLAY_DP, onInput)
        if (!ended) SeekButton(forward = true, step, playback, playhead, onInput, VideoMetrics.CENTRE_BUTTON_DP, CosmoIconButtonStyle.GLASS)
        if (queueButtons) QueueButton(previous = false, playback, onInput)
    }
}

@Composable
private fun QueueButton(previous: Boolean, playback: VideoPlayback, onInput: () -> Unit, size: Float = VideoMetrics.QUEUE_BUTTON_DP, style: CosmoIconButtonStyle = CosmoIconButtonStyle.GLASS) {
    CosmoIconButton(
        icon = if (previous) CosmoIcons.Previous else CosmoIcons.Next,
        contentDescription = stringResource(if (previous) Res.string.video_previous else Res.string.video_next),
        onClick = { onInput(); if (previous) playback.playPrevious() else playback.playNext() },
        style = style,
        floating = true,
        size = size.dp,
    )
}

@Composable
private fun SeekButton(forward: Boolean, step: Int, playback: VideoPlayback, playhead: Playhead, onInput: () -> Unit, size: Float, style: CosmoIconButtonStyle) {
    CosmoIconButton(
        icon = if (forward) VideoIcons.Forward else SeekBackIcon,
        contentDescription = stringResource(if (forward) Res.string.video_seek_forward else Res.string.video_seek_back, step),
        onClick = {
            onInput()
            playback.seekBy((if (forward) step else -step) * 1000L)
            playhead.refresh()
        },
        style = style,
        floating = true,
        size = size.dp,
    )
}

/** The C play button, or Replay (`play` of the same video from the start) once the video has ended (the controller stays ENDED). */
@Composable
private fun PlayOrReplay(playback: VideoPlayback, state: PlaybackUiState, buffering: Boolean, size: Float, onInput: () -> Unit) {
    val item = state.item
    if (state.status == PlayerStatus.ENDED && item != null) {
        CosmoIconButton(
            icon = SeekBackIcon,
            contentDescription = stringResource(Res.string.video_replay),
            onClick = { onInput(); playback.play(item.id, startMs = 0L) },
            style = CosmoIconButtonStyle.GLASS,
            size = size.dp,
        )
    } else {
        PlayMorphButton(
            playing = state.playing,
            loading = buffering,
            onClick = { onInput(); playback.togglePlayPause() },
            size = size.dp,
            floating = true,
        )
    }
}

/**
 * The captions button: on while a subtitle language is shown; a tap shows the default language of the settings (else the first
 * track made by a person, else the first) or hides them. Dimmed when the video has no subtitles.
 */
@Composable
private fun CaptionsButton(playback: VideoPlayback, state: PlaybackUiState, settings: VideoSettings, onInput: () -> Unit, floatingPlain: Boolean) {
    val on = state.subtitleLanguage != null
    CosmoIconButton(
        icon = VideoIcons.Captions,
        contentDescription = stringResource(Res.string.video_captions),
        onClick = {
            onInput()
            playback.selectSubtitles(if (on) null else preferredSubtitle(state, settings))
        },
        style = if (floatingPlain) CosmoIconButtonStyle.PLAIN else CosmoIconButtonStyle.GLASS,
        floating = true,
        size = VideoMetrics.CHROME_ICON_DP.dp,
        on = on,
        enabled = state.subtitles.isNotEmpty(),
    )
}

/** The language the captions button turns on: the settings' language when the video has it, else a track made by a person, else the first. */
internal fun preferredSubtitle(state: PlaybackUiState, settings: VideoSettings): String? {
    val tracks = state.subtitles
    val wanted = settings.captionsLanguage
    if (wanted != null) {
        tracks.firstOrNull { it.language == wanted && !it.autoGenerated }?.let { return it.language }
        tracks.firstOrNull { it.language.substringBefore('-') == wanted.substringBefore('-') }?.let { return it.language }
    }
    return (tracks.firstOrNull { !it.autoGenerated } ?: tracks.firstOrNull())?.language
}

/**
 * The time (spec 6.5: 12.5 sp 600 tabular, white 90 %, "1:21 / 4:08"); a tap shows the time left ("-2:47 / 4:08"). It shows the
 * playhead's whole second, so it recomposes once a second while playing. On the full window's glass ([onGlass]) it is ink.
 */
@Composable
internal fun TimeText(playhead: Playhead, durationMs: Long, onGlass: Boolean, onInput: () -> Unit, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    var remaining by rememberSaveable { mutableStateOf(false) }
    val second = playhead.second.longValue
    val positionMs = second * 1000
    val text = when {
        durationMs <= 0 -> ScrubberGeometry.timeLabel(positionMs)
        remaining -> "-" + ScrubberGeometry.timeLabel((durationMs - positionMs).coerceAtLeast(0)) + " / " + ScrubberGeometry.timeLabel(durationMs)
        else -> ScrubberGeometry.timeLabel(positionMs) + " / " + ScrubberGeometry.timeLabel(durationMs)
    }
    val style = remember(type) {
        TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(TIME_WEIGHT), fontSize = VideoMetrics.TIME_SP.sp).tabular()
    }
    val toggle = stringResource(Res.string.video_time_toggle)
    Text(
        text = text,
        style = style,
        color = if (onGlass) Color(palette.textPrimary) else Color.White.copy(alpha = TIME_ALPHA),
        maxLines = 1,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = toggle,
            ) { onInput(); remaining = !remaining }
            .padding(vertical = TOP_ROW_PAD_DP.dp / 2),
    )
}

/** The Live badge (spec 5.0, 5.13): `danger` with `onDanger` text, 20 high, padding 0 6, radius 6, 12 sp 650 tabular. */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    PlayerBadge(stringResource(Res.string.video_badge_live), fill = palette.danger, ink = palette.onDanger, modifier)
}

/** The Upcoming badge (spec 5.0, 5.13): as the Live badge, on `accentContainer` with `onAccentContainer` text. */
@Composable
internal fun UpcomingBadge(modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    PlayerBadge(stringResource(Res.string.video_badge_upcoming), fill = palette.accentContainer, ink = palette.onAccentContainer, modifier)
}

/** A badge of spec 5.0 over the video: 20 high, padding 0 6, radius 6, 12 sp 650 tabular, in the palette's [fill] and [ink]. */
@Composable
private fun PlayerBadge(label: String, fill: Int, ink: Int, modifier: Modifier) {
    val type = LocalCosmoType.current
    val style = remember(type) { TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(LIVE_WEIGHT), fontSize = VideoMetrics.BADGE_SP.sp).tabular() }
    Box(
        modifier
            .heightIn(min = VideoMetrics.BADGE_H_DP.dp)
            .background(Color(fill), RoundedCornerShape(VideoMetrics.BADGE_RADIUS_DP.dp))
            .padding(horizontal = VideoMetrics.BADGE_PAD_H_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style, color = Color(ink), maxLines = 1)
    }
}

/**
 * The full window's controls (spec 5.4, the `np-fullwindow` board): a C glass bar 80 dp tall, radius 28, 20 dp from the bottom and at
 * most 720 dp wide; the time at the start, the transport in the middle (previous and next with a queue, back, play, forward), the
 * captions, settings and exit buttons at the end, the scrubber under them.
 */
@Composable
private fun FullWindowBar(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    step: Int,
    buffering: Boolean,
    playhead: Playhead,
    onToggleFullscreen: () -> Unit,
    onInput: () -> Unit,
    onScrub: (Boolean) -> Unit,
    tools: @Composable () -> Unit,
    modifier: Modifier,
) {
    val shape = remember { SuperellipseShape(CosmoRadii.xl) }
    Box(
        modifier
            .padding(start = FULL_WINDOW_BAR_SIDE_DP.dp, end = FULL_WINDOW_BAR_SIDE_DP.dp, bottom = VideoMetrics.FULL_WINDOW_BAR_BOTTOM_DP.dp)
            .widthIn(max = VideoMetrics.FULL_WINDOW_BAR_MAX_DP.dp)
            .fillMaxWidth()
            .height(FULL_WINDOW_BAR_H_DP.dp),
    ) {
        // The glass is a layer of its own under the controls, so its clip does not cut the scrubber's bubble above the bar.
        Box(Modifier.matchParentSize().cosmoGlass(hazeState = null, shape = shape, overVideo = true))
        FullWindowBarContent(playback, state, settings, step, buffering, playhead, onToggleFullscreen, onInput, onScrub, tools)
    }
}

@Composable
private fun FullWindowBarContent(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    step: Int,
    buffering: Boolean,
    playhead: Playhead,
    onToggleFullscreen: () -> Unit,
    onInput: () -> Unit,
    onScrub: (Boolean) -> Unit,
    tools: @Composable () -> Unit,
) {
    val queue = state.queue.isNotEmpty()
    Column(
        Modifier.fillMaxSize().padding(horizontal = FULL_WINDOW_BAR_PAD_DP.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(VideoMetrics.QUEUE_BUTTON_DP.dp)) {
            if (state.live) LiveStatus(playback, state, playhead, onInput, Modifier.align(Alignment.CenterStart))
            else TimeText(playhead, state.durationMs, onGlass = true, onInput = onInput, modifier = Modifier.align(Alignment.CenterStart))
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                if (queue) QueueButton(previous = true, playback, onInput, VideoMetrics.CHROME_ICON_DP, CosmoIconButtonStyle.PLAIN)
                SeekButton(forward = false, step, playback, playhead, onInput, VideoMetrics.CHROME_ICON_DP, CosmoIconButtonStyle.PLAIN)
                PlayOrReplay(playback, state, buffering, VideoMetrics.QUEUE_BUTTON_DP, onInput)
                SeekButton(forward = true, step, playback, playhead, onInput, VideoMetrics.CHROME_ICON_DP, CosmoIconButtonStyle.PLAIN)
                if (queue) QueueButton(previous = false, playback, onInput, VideoMetrics.CHROME_ICON_DP, CosmoIconButtonStyle.PLAIN)
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                tools()
                CosmoIconButton(
                    icon = VideoIcons.FullscreenExit,
                    contentDescription = stringResource(Res.string.video_exit_fullscreen),
                    onClick = { onInput(); onToggleFullscreen() },
                    style = CosmoIconButtonStyle.PLAIN,
                    floating = true,
                    size = VideoMetrics.CHROME_ICON_DP.dp,
                )
            }
        }
        PlayerScrubber(
            playback = playback,
            state = state,
            settings = settings,
            playhead = playhead,
            lineAtBottom = false,
            onGlass = true,
            onScrub = onScrub,
            onInput = onInput,
            modifier = Modifier.fillMaxWidth().height(SCRUB_BAR_TOUCH_DP.dp),
            // The line stands this far below the bar's top: the bubble clears the bar.
            bubbleLiftDp = (FULL_WINDOW_BAR_H_DP - VideoMetrics.QUEUE_BUTTON_DP - SCRUB_BAR_TOUCH_DP) / 2 + VideoMetrics.QUEUE_BUTTON_DP + SCRUB_BAR_TOUCH_DP / 2,
        )
    }
}

/**
 * The error (spec 5.4, 6.1): the message of the [VideoUiError] in white over the black of the box, and "Try again" (a C secondary
 * button), which plays the video again with fresh details. An upcoming video ([LiveWindow.isUpcoming], spec 5.13) shows the
 * Upcoming badge and its start time instead ("Starts 18 Oct 2026, 20:00"; the source's own words when it gave no time); "Try
 * again" then looks whether it has started.
 */
@Composable
internal fun PlayerErrorState(state: PlaybackUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val type = LocalCosmoType.current
    val upcoming = LiveWindow.isUpcoming(state)
    val message = if (upcoming) upcomingText(state) else stringResource(errorMessage(state.error ?: VideoUiError.STOPPED))
    Column(
        modifier.fillMaxWidth(ERROR_WIDTH_SHARE).padding(TOP_ROW_PAD_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BOTTOM_PAD_SIDE_DP.dp),
    ) {
        if (upcoming) UpcomingBadge()
        if (message.isNotEmpty()) {
            Text(
                message,
                style = type.forText(type.body(), message),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        if (state.item != null) {
            CosmoButton(text = stringResource(Res.string.video_retry), onClick = onRetry, kind = CosmoButtonKind.QUIET, size = CosmoButtonSize.SMALL, floating = true)
        }
    }
}

/** The error's message stays within 80 % of the box. */
private const val ERROR_WIDTH_SHARE = 0.8f

/** An upcoming video's line: "Starts" and its start time, or the source's own words ("Premieres in 2 days") without a time. */
@Composable
private fun upcomingText(state: PlaybackUiState): String {
    val start = LiveWindow.upcomingStartMs(state) ?: return state.item?.uploadedText.orEmpty()
    val time = remember(start) { startTimeText(start) }
    return stringResource(Res.string.video_live_starts, time)
}

/** A start time in the device's language and time zone: the medium date and the short time ("18 Oct 2026, 20:00"). */
internal fun startTimeText(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(epochMs))

/** The `video_error_*` text of each error (spec 5.4, 6.1). */
internal fun errorMessage(error: VideoUiError): StringResource = when (error) {
    VideoUiError.STOPPED -> Res.string.video_error_stopped
    VideoUiError.UNAVAILABLE -> Res.string.video_error_unavailable
    VideoUiError.AGE -> Res.string.video_error_age
    VideoUiError.KIDS -> Res.string.video_error_kids
    VideoUiError.PRIVATE -> Res.string.video_error_private
    VideoUiError.PAID -> Res.string.video_error_paid
    VideoUiError.GEO -> Res.string.video_error_geo
    VideoUiError.BOT -> Res.string.video_error_bot
    VideoUiError.NETWORK -> Res.string.video_error_network
    VideoUiError.PARSING -> Res.string.video_error_parsing
}

// ---- keys (spec 6.6) ----------------------------------------------------------------------------------------------------------

/** The names [com.cosmotune.video.player.PlayerKeyMap] reads, for the Compose keys it knows. */
private val KEY_NAMES: Map<Key, String> = mapOf(
    Key.Spacebar to "Space", Key.DirectionLeft to "Left", Key.DirectionRight to "Right", Key.DirectionUp to "Up", Key.DirectionDown to "Down",
    Key.Escape to "Escape", Key.Comma to "Comma", Key.Period to "Period",
    Key.K to "K", Key.J to "J", Key.L to "L", Key.M to "M", Key.F to "F", Key.T to "T", Key.C to "C", Key.N to "N", Key.P to "P",
    Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3", Key.Four to "4",
    Key.Five to "5", Key.Six to "6", Key.Seven to "7", Key.Eight to "8", Key.Nine to "9",
    Key.NumPad0 to "0", Key.NumPad1 to "1", Key.NumPad2 to "2", Key.NumPad3 to "3", Key.NumPad4 to "4",
    Key.NumPad5 to "5", Key.NumPad6 to "6", Key.NumPad7 to "7", Key.NumPad8 to "8", Key.NumPad9 to "9",
)

/** The name of [event]'s key for [com.cosmotune.video.player.PlayerKeyMap.action], or null for a key the player has no use for. */
fun playerKeyName(event: KeyEvent): String? = KEY_NAMES[event.key]

/**
 * Carries out [action] (spec 6.6) on [playback]: play and pause, seeks (Replay from the start once ended), the volume through
 * [platform], mute, captions (the default language of [settings]), next and previous, the speed (0.25 to 4), the jumps to tenths. [onFullWindow], [onTheatre] and
 * [onExitFullWindow] and [onPictureInPicture] are the page's (null: not handled here). True when the action was carried out.
 */
fun performPlayerKey(
    action: PlayerKeyAction,
    playback: VideoPlayback,
    state: PlaybackUiState = playback.state.value,
    settings: VideoSettings? = null,
    platform: PlayerPlatformControls? = null,
    onFullWindow: (() -> Unit)? = null,
    onTheatre: (() -> Unit)? = null,
    onExitFullWindow: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
): Boolean {
    when (action) {
        PlayerKeyAction.TogglePlay -> {
            val item = state.item
            if (state.status == PlayerStatus.ENDED && item != null) playback.play(item.id, startMs = 0L) else playback.togglePlayPause()
        }
        is PlayerKeyAction.SeekBy -> playback.seekBy(action.deltaMs)
        is PlayerKeyAction.SeekToFraction -> {
            // A live stream's tenths are of its window up to the live edge (spec 5.13).
            val span = if (state.live) LiveWindow.liveEdgeMs(state.durationMs) else state.durationMs
            if (span <= 0) return false
            playback.seekTo((span * action.fraction).toLong())
        }
        is PlayerKeyAction.Volume -> {
            val controls = platform ?: return false
            controls.setVolume((controls.volume() + action.delta).coerceIn(0f, 1f))
        }
        PlayerKeyAction.ToggleMute -> playback.setMuted(!state.muted)
        PlayerKeyAction.Captions -> playback.selectSubtitles(
            if (state.subtitleLanguage != null) null else preferredSubtitle(state, settings ?: VideoSettings()) ?: return false,
        )
        PlayerKeyAction.Next -> playback.playNext()
        PlayerKeyAction.Previous -> playback.playPrevious()
        is PlayerKeyAction.Speed -> playback.setSpeed((state.speed + action.delta).coerceIn(SPEED_MIN, SPEED_MAX))
        PlayerKeyAction.FullWindow -> (onFullWindow ?: return false).invoke()
        PlayerKeyAction.Theatre -> (onTheatre ?: return false).invoke()
        PlayerKeyAction.ExitFullWindow -> (onExitFullWindow ?: return false).invoke()
        PlayerKeyAction.PictureInPicture -> (onPictureInPicture ?: return false).invoke()
    }
    return true
}

/**
 * Back by the step, and Replay: the mirror of `VideoIcons.Forward` (an arc and its arrow on the 24 dp grid, stroke 2, round caps and
 * joins). `CosmoIcons.Replay` is Music's Replay (an arc round bars), not "back". The paint is a placeholder: the button tints it.
 */
internal val SeekBackIcon: ImageVector by lazy {
    val paint = SolidColor(Color.White)
    ImageVector.Builder("seek-back", 24.dp, 24.dp, 24f, 24f).apply {
        for (d in listOf("M4.6 12a7.4 7.4 0 1 0 2.2-5.3", "M4.4 4.6v3.6H8")) {
            addPath(addPathNodes(d), fill = null, stroke = paint, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
}

/** The speed's range (spec 6.5: 0.25 to 4). */
internal const val SPEED_MIN = 0.25f
internal const val SPEED_MAX = 4f
