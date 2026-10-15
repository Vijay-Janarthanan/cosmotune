package com.cosmotune.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.design.SliderMetrics
import com.cosmotune.design.Springs
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.PlayMorphButton
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CapsuleLabel
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.now_playing
import com.music.bitchord.sharedui.resources.widget_next
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.PlayerPlatform
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource

/** One tab of the floating tab bar: its [label] (shown only while it is the active tab) and the two icons. */
@Immutable
data class CosmoTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * The phone's floating chrome (layer C of the B+C system): notices, the mini player capsule (when a song is loaded) and the
 * tab capsule, in one column along the bottom of the window, [ShellMetrics.SIDE_MARGIN_DP] from the sides and
 * [ShellMetrics.bottomOffsetDp] above the bottom (16 dp above a gesture bar, 8 dp above a taller three-button bar).
 *
 * [onHeightChanged] gets the column's height in pixels including that bottom offset, for upstream's `FloatingBarsTapGuard`.
 * Both capsules are glass over [hazeState]: that makes two blurred layers on this screen, the most there may be.
 * [controlsLocked] is a party whose host has taken the controls: "next" then answers with [onBlockedControl].
 */
@Composable
fun CosmoBottomChrome(
    tabs: List<CosmoTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    hazeState: HazeState?,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    notices: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val navInsetDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }
    Column(
        modifier
            // Before the padding, so the height reported is the whole of what the chrome takes from the bottom.
            .onSizeChanged { onHeightChanged(it.height) }
            .padding(horizontal = ShellMetrics.SIDE_MARGIN_DP.dp)
            .padding(bottom = ShellMetrics.bottomOffsetDp(navInsetDp).dp),
    ) {
        notices()
        if (song != null) {
            MiniCapsule(song, isPlaying, isLoading, position, durationMs, onPlayPause, onNext, onExpand, controlsLocked, onBlockedControl, hazeState)
            Spacer(Modifier.height(ShellMetrics.CAPSULE_GAP_DP.dp))
        }
        FloatingTabBar(tabs, selectedIndex, onTabSelected, hazeState)
    }
}

// ---- The tab capsule ----

/** The tab bar (`.bar2`): a 64 dp glass pill, 8 dp in from its ends, the tabs spread along it. */
@Composable
private fun FloatingTabBar(tabs: List<CosmoTab>, selectedIndex: Int, onTabSelected: (Int) -> Unit, hazeState: HazeState?) {
    val shape = remember { RoundedCornerShape(ShellMetrics.TAB_BAR_RADIUS_DP.dp) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ShellMetrics.TAB_BAR_HEIGHT_DP.dp)
            .cosmoGlass(hazeState, shape)
            .padding(horizontal = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab -> TabPill(tab, selected = index == selectedIndex, onClick = { onTabSelected(index) }) }
    }
}

/**
 * One tab, a 48 dp pill. At rest it is its icon alone in the ink at 72 % (56 dp wide at least); the active tab is solid ink
 * (`textPrimary`) with a `backgroundBottom` label and its filled icon, and its width follows on the move spring.
 * It shrinks to 0.94 under the finger (not under reduced motion).
 */
@Composable
private fun TabPill(tab: CosmoTab, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed, reduced) { press.animateTo(if (pressed && !reduced) TAB_PRESS_SCALE else 1f, CosmoMotion.press(reduced)) }
    // The ink fill arrives on the effects spring; the glyph and label take their colour with it.
    val fill = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected, reduced) { fill.animateTo(if (selected) 1f else 0f, CosmoMotion.effects(reduced)) }
    val ink by animateColorAsState(
        targetValue = if (selected) Color(palette.backgroundBottom) else Color(palette.textPrimary).copy(alpha = TAB_REST_INK),
        animationSpec = CosmoMotion.effects(reduced),
        label = "tab-ink",
    )
    val type = LocalCosmoType.current
    val moveSpec = remember(reduced) { moveSizeSpec(reduced) }
    Row(
        Modifier
            .graphicsLayer { scaleX = press.value; scaleY = press.value }
            .height(TAB_HEIGHT)
            .defaultMinSize(minWidth = TAB_MIN_WIDTH)
            .cosmoFocusRing(interaction, TAB_RADIUS, floating = true)
            .clip(TAB_SHAPE)
            // The fill is drawn from the live palette, so it follows a cover change without a recomposition.
            .drawBehind { drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = fill.value)) }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = { haptics.play(Haptic.Select); onClick() },
            )
            .animateContentSize(moveSpec)
            .padding(horizontal = if (selected) TAB_PAD_ACTIVE else TAB_PAD_REST),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        if (selected) Text(tab.label, style = type.label.copy(fontWeight = FontWeight(650)), color = ink, maxLines = 1, softWrap = false)
    }
}

/**
 * The move spring (or the 150 ms fade) as a finite spec for [animateContentSize]: [CosmoMotion.move] returns the wider
 * `AnimationSpec`, which that modifier does not take. Same numbers, same source.
 */
private fun moveSizeSpec(reduced: Boolean): FiniteAnimationSpec<IntSize> =
    if (reduced) tween(Springs.REDUCED_MS) else spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness)

private val TAB_HEIGHT = 48.dp
private val TAB_MIN_WIDTH = 56.dp
private val TAB_PAD_REST = 14.dp
private val TAB_PAD_ACTIVE = 18.dp
private val TAB_RADIUS = 24.dp
private val TAB_SHAPE = RoundedCornerShape(TAB_RADIUS)
private const val TAB_PRESS_SCALE = 0.94f
private const val TAB_REST_INK = 0.72f

// ---- The mini capsule ----

/**
 * The mini player (`.mini2`): a 64 dp glass capsule with superellipse corners of 22 dp. Cover, title over artist, next, and the
 * play button; a 3 dp progress line 4 dp from the bottom and 18 dp in from both sides. A tap, an upward drag of 48 dp or an
 * upward fling faster than 1000 px/s opens the player, which grows out of this capsule: the capsule reports where it is
 * ([com.cosmotune.ui.theme.CosmoShellState.capsuleBounds]) and goes invisible while the player stands in its place.
 */
@Composable
private fun MiniCapsule(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    hazeState: HazeState?,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val shell = LocalCosmoShell.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val shape = remember { SuperellipseShape(ShellMetrics.CAPSULE_RADIUS_DP.dp) }
    val coverUrl = PlayerPlatform.host.rememberRemoteArtworkUrl(song)
    val nowPlaying = stringResource(Res.string.now_playing)
    val next = stringResource(Res.string.widget_next)
    val type = LocalCosmoType.current

    // What the morph copies: published as a value, so an unchanged song writes nothing.
    val label = remember(song.title, song.artist, coverUrl) { CapsuleLabel(song.title, song.artist, coverUrl) }
    if (shell != null) {
        SideEffect { shell.capsuleLabel = label }
        DisposableEffect(shell) { onDispose { shell.capsuleBounds = null; shell.capsuleLabel = null } }
    }

    // A plain holder: the distance dragged is read once, when the finger lifts, so nothing recomposes while it moves.
    val dragged = remember { floatArrayOf(0f) }
    val dragState = rememberDraggableState { dy -> dragged[0] += dy }
    val expandDragPx = with(density) { EXPAND_DRAG_DP.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(ShellMetrics.CAPSULE_HEIGHT_DP.dp)
            // Invisible while the player stands in its place. Not drawn at all (so no blur or shadow is made for it), and decided
            // in the draw phase, where the overlay's card is decided too: the one swaps for the other in a single frame.
            .drawWithContent { if (shell?.capsuleHidden != true) drawContent() }
            .onGloballyPositioned { shell?.capsuleBounds = it.boundsInRoot() }
            .cosmoGlass(hazeState, shape)
            // The title and artist inside are merged into the button's name; "Now playing" is what a tap does.
            .clickable(onClickLabel = nowPlaying, role = Role.Button) { haptics.play(Haptic.Expand); onExpand() }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { dragged[0] = 0f },
                onDragStopped = { velocity ->
                    if (-dragged[0] > expandDragPx || velocity < -EXPAND_FLING_PX_PER_S) { haptics.play(Haptic.Expand); onExpand() }
                },
            )
            .drawBehind {
                // The playhead is read here and nowhere else: a tick of the song redraws this line and recomposes nothing.
                val inset = PROGRESS_INSET.toPx()
                val thickness = PROGRESS_HEIGHT.toPx()
                val width = size.width - 2f * inset
                if (width <= 0f) return@drawBehind
                val top = size.height - PROGRESS_BOTTOM.toPx() - thickness
                val ink = colors.live(CoverField.TEXT_PRIMARY)
                val round = CornerRadius(thickness / 2f)
                drawRoundRect(ink.copy(alpha = SliderMetrics.trackAlpha(colors.dark)), Offset(inset, top), Size(width, thickness), round)
                val played = if (durationMs > 0L) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                if (played > 0f) drawRoundRect(ink, Offset(inset, top), Size(width * played, thickness), round)
            },
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(coverUrl, COVER_SIZE, CosmoRadii.coverMini, shadow = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(song.title, style = capsuleTitleStyle(type.textFamily), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = capsuleArtistStyle(type.textFamily), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CosmoIconButton(
                icon = CosmoIcons.Next,
                contentDescription = next,
                // A locked party answers with the host-only notice; the button stays drawn, a little dimmer.
                onClick = { if (controlsLocked) onBlockedControl() else onNext() },
                modifier = Modifier.graphicsLayer { alpha = if (controlsLocked) LOCKED_ALPHA else 1f },
                style = CosmoIconButtonStyle.PLAIN,
                size = 40.dp,
                floating = true,
            )
            Spacer(Modifier.width(8.dp))
            PlayMorphButton(playing = isPlaying, loading = isLoading, onClick = onPlayPause, size = 44.dp, floating = true)
        }
    }
}

/** The capsule's title: 15 sp, weight 700. Also drawn by the morph, so the two are the same text. */
internal fun capsuleTitleStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 18.75.sp)

/** The capsule's artist: 13 sp. */
internal fun capsuleArtistStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 16.sp)

/** The capsule's cover: 48 dp. */
internal val COVER_SIZE = 48.dp

/** Where the capsule's text starts, from its left edge: 8 dp of padding, the 48 dp cover and a 12 dp gap. */
internal val CAPSULE_TEXT_START = 8.dp + COVER_SIZE + 12.dp

/** What the capsule keeps to the right of its text: next (48 dp with its touch target), 8 dp, play (48 dp) and 8 dp of padding. */
internal val CAPSULE_TEXT_END = 48.dp + 8.dp + 48.dp + 8.dp

private val PROGRESS_INSET = 18.dp
private val PROGRESS_BOTTOM = 4.dp
private val PROGRESS_HEIGHT = 3.dp
private const val EXPAND_DRAG_DP = 48
private const val EXPAND_FLING_PX_PER_S = 1000f
private const val LOCKED_ALPHA = 0.5f
