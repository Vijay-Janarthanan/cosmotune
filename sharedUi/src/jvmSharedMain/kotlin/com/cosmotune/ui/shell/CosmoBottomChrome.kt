package com.cosmotune.ui.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.AppMode
import com.cosmotune.design.ShellMetrics
import com.cosmotune.design.Springs
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_close
import com.cosmotune.music.sharedui.resources.now_playing
import com.cosmotune.music.sharedui.resources.widget_next
import com.cosmotune.music.sharedui.resources.widget_previous
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.PlayingQualityPill
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitDockMetrics
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitDockTab
import com.cosmotune.ui.orbit.OrbitMiniButton
import com.cosmotune.ui.orbit.OrbitMiniMetrics
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitDockFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CapsuleLabel
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberDarkChromeColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** One tab of the dock: its [label] (beside its icon while chosen, and its name for a screen reader) and the two icons (outlined at rest, filled when chosen). */
@Immutable
data class CosmoTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)


/**
 * The phone's dock in Orbit (board batch-a, section 4): along the bottom of the window, [DOCK_SIDE_INSET] from the sides and
 * [ShellMetrics.bottomOffsetDp] above the bottom (16 dp above a gesture bar, 8 dp above a taller three-button bar), the small
 * player (when a song is loaded) and under it the tab bar. Notices stand above both.
 *
 * The small player is the desktop bar's colour card made phone-sized: filled with the playing cover's deep colour ([coverFill]),
 * the disc in its orbit, the title over the artist, next, and play in the round bite out of its right end ([OrbitMiniMetrics]).
 * The tab bar is Home's chips on a quiet rounded bar ([orbitDockFill], solid, so nothing that scrolls under it shows through): the
 * chosen tab a solid ink chip with its name, the others their icon alone. Flat (rule 4): [hazeState] is kept for the callers.
 *
 * [onHeightChanged] gets the column's height in pixels including that bottom offset, for upstream's `FloatingBarsTapGuard`; the same
 * height is published on the shell ([com.cosmotune.ui.theme.CosmoShellState.dockHeightPx]) for the pages that pad for it
 * ([rememberDockClearance]). [controlsLocked] is a party whose host has taken the controls: "next" (and a sideways swipe) then
 * answers with [onBlockedControl]. [onPrevious], when given, is what a swipe to the right does. [miniPlayer] stands in for the
 * built-in small player (Video's, whose capsule is its own): it is drawn above the tabs, on the bar's ground, when [song] is null.
 *
 * [mode] is the mode this chrome belongs to: while the stored mode ([com.cosmotune.settings.CosmoLookSettings.mode]) is another
 * one it draws nothing (Music's compact desktop bar steps aside in Video; Video's shell passes [AppMode.VIDEO]).
 *
 * A mode that plays its own media (Video) passes [ownMedia]: its [song] is not Music's, so the small player owes nothing to Music's
 * player (no morph bounds, no hiding while Music's player stands open, no artwork host, no measured-quality pill). [onClose] adds a
 * close button after next: what stops that media. [showTabs] false leaves the small player alone (a wide window, whose tabs stand
 * in the sidebar). [dark] draws the dock on the dark palette in either theme (next to the Shorts pager, which is black).
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
    mode: AppMode = AppMode.MUSIC,
    onPrevious: (() -> Unit)? = null,
    miniPlayer: (@Composable () -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    showTabs: Boolean = true,
    dark: Boolean = false,
    ownMedia: Boolean = false,
) {
    if ((LocalCosmoShell.current?.settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC) != mode) return
    val shell = LocalCosmoShell.current
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val navInsetDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }
    if (shell != null) DisposableEffect(shell) { onDispose { shell.dockHeightPx = 0 } }
    val moveSpec = remember(reduced) { moveSizeSpec(reduced) }
    val darkDock = rememberDarkChromeColors()
    // The same call tree either way: only the value of the local changes.
    CompositionLocalProvider(LocalCosmoColors provides (if (dark) darkDock else LocalCosmoColors.current)) {
    val look = orbitLook()
    Column(
        modifier
            // Before the padding, so the height reported is the whole of what the chrome takes from the bottom.
            .onSizeChanged { onHeightChanged(it.height); shell?.dockHeightPx = it.height }
            .padding(horizontal = DOCK_SIDE_INSET)
            .padding(bottom = ShellMetrics.bottomOffsetDp(navInsetDp).dp)
            // The dock grows by the small player's height on the move spring (a fade with reduced motion).
            .animateContentSize(moveSpec),
    ) {
        notices()
        // The small player and the tabs stand on one rounded slab of the page's ground: the gap between them and the small player's
        // bite show that ground, never the page scrolling under the dock.
        val ground = Color(LocalCosmoColors.current.palette.backgroundBottom)
        val backed = showTabs && (song != null || miniPlayer != null)
        Column(if (backed) Modifier.fillMaxWidth().background(ground, DOCK_SHAPE) else Modifier.fillMaxWidth()) {
            if (song != null) {
                MiniCapsule(song, isPlaying, isLoading, position, durationMs, onPlayPause, onNext, onPrevious, onExpand, controlsLocked, onBlockedControl, onClose, ownMedia)
                if (showTabs) Spacer(Modifier.height(DOCK_GAP))
            } else if (miniPlayer != null) {
                Box(Modifier.fillMaxWidth().clip(DOCK_SHAPE).background(orbitDockFill(look))) { miniPlayer() }
                if (showTabs) Spacer(Modifier.height(DOCK_GAP))
            }
            if (showTabs) FloatingTabBar(tabs, selectedIndex, onTabSelected)
        }
    }
    }
}

/**
 * The room a scrolling page keeps under its last row for the dock: the dock's measured height (what it takes from the bottom of
 * the window, its gap to the edge included) and [gap] more, or [fallback] while no dock has been measured (the first frame, the
 * look off). A page that is given its padding by its shell already gets this; this is for the pages that are not.
 */
@Composable
fun rememberDockClearance(fallback: Dp = 0.dp, gap: Dp = 8.dp): Dp {
    val px = LocalCosmoShell.current?.dockHeightPx ?: 0
    val density = LocalDensity.current
    return if (px <= 0) fallback else maxOf(fallback, with(density) { px.toDp() } + gap)
}

// ---- The tab bar ----

/**
 * The tab bar in Orbit: a 60 dp bar with round ends on the dock's solid ground and a soft shadow, the tabs spread evenly along it as
 * [OrbitDockTab] chips (the chosen one solid ink with its name, the others their icon alone, each named for a screen reader).
 */
@Composable
private fun FloatingTabBar(tabs: List<CosmoTab>, selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    val look = orbitLook()
    val fonts = orbitFonts()
    Row(
        Modifier
            .fillMaxWidth()
            .height(OrbitDockMetrics.BAR_DP.dp)
            .shadow(16.dp, DOCK_SHAPE, clip = false)
            .clip(DOCK_SHAPE)
            .background(orbitDockFill(look))
            .padding(horizontal = OrbitDockMetrics.PAD_DP.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            TabPill(tab, selected = index == selectedIndex, onClick = { onTabSelected(index) }, fonts = fonts, look = look)
        }
    }
}

/** One tab: an [OrbitDockTab] that plays the select haptic and shrinks to 0.94 under the finger (not under reduced motion). */
@Composable
private fun TabPill(tab: CosmoTab, selected: Boolean, onClick: () -> Unit, fonts: OrbitFonts, look: OrbitLook) {
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed, reduced) { press.animateTo(if (pressed && !reduced) TAB_PRESS_SCALE else 1f, CosmoMotion.press(reduced)) }
    OrbitDockTab(
        label = tab.label,
        icon = tab.icon,
        selectedIcon = tab.selectedIcon,
        selected = selected,
        onClick = { haptics.play(Haptic.Select); onClick() },
        fonts = fonts,
        look = look,
        interaction = interaction,
        modifier = Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value },
    )
}

/**
 * The move spring (or the 150 ms fade) as a finite spec for [animateContentSize]: [CosmoMotion.move] returns the wider
 * `AnimationSpec`, which that modifier does not take. Same numbers, same source.
 */
private fun moveSizeSpec(reduced: Boolean): FiniteAnimationSpec<IntSize> =
    if (reduced) tween(Springs.REDUCED_MS) else spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness)

/** The bar's round ends and the small player's corners (board: 30). */
private val DOCK_SHAPE = RoundedCornerShape(OrbitMiniMetrics.RADIUS_DP.dp)
private val DOCK_SIDE_INSET = 12.dp
/** Between the small player and the tab bar. */
private val DOCK_GAP = 8.dp
private const val TAB_PRESS_SCALE = 0.94f

// ---- The small player ----

/**
 * The small player in Orbit: the top of the dock, [OrbitMiniMetrics.HEIGHT_DP] high, cut by [OrbitMiniMetrics.shape] and filled with
 * the cover's deep colour; the disc in its orbit (the played part and a moon at the current second, read while drawing), the title
 * over the artist (with the measured quality pill when the stream is notable: Atmos, Hi-Res, lossless, high bitrate), next, and
 * [OrbitPlayButton] standing in the bite (a moon circles it there while the song loads).
 *
 * A tap, an upward drag of 48 dp or an upward fling faster than 1000 px/s opens the player, which grows out of this card: the card
 * reports where it is ([com.cosmotune.ui.theme.CosmoShellState.capsuleBounds]) and what it is ([OrbitMiniMetrics.geometry]), and goes
 * invisible while the player stands in its place. With [onPrevious] given, a sideways swipe of 56 dp (or a fling) skips: to the left
 * the next song, to the right the previous one ([onPrevious]); the card follows the finger by half the distance, and not at all with
 * reduced motion. Screen readers get "Next" as a button and "Previous" as an action.
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
    onPrevious: (() -> Unit)?,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    onClose: (() -> Unit)?,
    ownMedia: Boolean,
) {
    val shell = LocalCosmoShell.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val coverUrl = if (ownMedia) song.thumbnailUrl else PlayerPlatform.host.rememberRemoteArtworkUrl(song)
    val nowPlaying = stringResource(Res.string.now_playing)
    val next = stringResource(Res.string.widget_next)
    val previous = stringResource(Res.string.widget_previous)
    val type = LocalCosmoType.current
    val look = orbitLook()
    val (fill, ink) = coverFill(coverUrl, song.videoId)

    // What the morph copies: the words and the picture, and how this card is built (its corners, where the disc and the words sit,
    // their styles). Published as a value, so an unchanged song writes nothing.
    val label = remember(song.title, song.artist, coverUrl, type) {
        CapsuleLabel(song.title, song.artist, coverUrl, OrbitMiniMetrics.geometry, capsuleTitleStyle(type.textFamily), capsuleArtistStyle(type.textFamily))
    }
    if (shell != null && !ownMedia) {
        SideEffect { shell.capsuleLabel = label }
        DisposableEffect(shell) { onDispose { shell.capsuleBounds = null; shell.capsuleLabel = null } }
    }

    val skipNext = { if (controlsLocked) onBlockedControl() else { haptics.play(Haptic.SkipNext); onNext() } }
    val skipPrevious: (() -> Unit)? = onPrevious?.let { back -> { if (controlsLocked) onBlockedControl() else { haptics.play(Haptic.SkipPrevious); back() } } }

    // A plain holder: the distance dragged is read once, when the finger lifts, so nothing recomposes while it moves.
    val dragged = remember { floatArrayOf(0f) }
    val dragState = rememberDraggableState { dy -> dragged[0] += dy }
    val expandDragPx = with(density) { EXPAND_DRAG_DP.dp.toPx() }
    // The sideways swipe: the card's offset, read in its layer block only.
    val swipe = remember { Animatable(0f) }
    val swipeState = rememberDraggableState { dx -> scope.launch { swipe.snapTo(swipe.value + dx) } }
    val skipDragPx = with(density) { SKIP_DRAG_DP.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(OrbitMiniMetrics.HEIGHT_DP.dp)
            // Invisible while the player stands in its place. Not drawn at all, and decided in the draw phase, where the overlay's
            // card is decided too: the one swaps for the other in a single frame.
            .drawWithContent { if (ownMedia || shell?.capsuleHidden != true) drawContent() }
            .onGloballyPositioned { if (!ownMedia) shell?.capsuleBounds = it.boundsInRoot() }
            .testTag(DOCK_MINI_TAG)
            .graphicsLayer { if (!reduced) translationX = swipe.value * SWIPE_FOLLOW },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(OrbitMiniMetrics.shape)
                .background(fill)
                .semantics {
                    if (skipPrevious != null) customActions = listOf(CustomAccessibilityAction(previous) { skipPrevious(); true })
                }
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
                // The sideways swipe is opt-in: only a caller that passes onPrevious turns it on.
                .draggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    enabled = onPrevious != null,
                    onDragStopped = { velocity ->
                        val dx = swipe.value
                        when {
                            dx < -skipDragPx || velocity < -SKIP_FLING_PX_PER_S -> skipNext()
                            (dx > skipDragPx || velocity > SKIP_FLING_PX_PER_S) && skipPrevious != null -> skipPrevious()
                        }
                        swipe.animateTo(0f, CosmoMotion.move(reduced))
                    },
                ),
        ) {
            Row(
                Modifier.fillMaxSize().padding(start = OrbitMiniMetrics.PAD_START_DP.dp, end = OrbitMiniMetrics.END_PAD_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(OrbitMiniMetrics.RING_DP.dp), contentAlignment = Alignment.Center) {
                    Disc(coverUrl, OrbitMiniMetrics.DISC_DP.dp)
                    // The playhead is read here and nowhere else, while the orbit draws: a tick of the song recomposes nothing.
                    OrbitRing(
                        OrbitMiniMetrics.RING_DP.dp, ink,
                        progress = { if (durationMs > 0L) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null },
                        spinning = isPlaying, stroke = 2.dp, moon = 4.dp,
                    )
                }
                Spacer(Modifier.width(OrbitMiniMetrics.GAP_DP.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(song.title, style = capsuleTitleStyle(type.textFamily), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!ownMedia) PlayingQualityPill(Modifier.padding(end = 6.dp))
                        Text(song.artist, style = capsuleArtistStyle(type.textFamily), color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                // A locked party answers with the host-only notice; the button stays drawn, a little dimmer.
                OrbitMiniButton(
                    CosmoIcons.Next, next, ink,
                    onClick = { if (controlsLocked) onBlockedControl() else onNext() },
                    modifier = Modifier.graphicsLayer { alpha = if (controlsLocked) LOCKED_ALPHA else 1f },
                )
                if (onClose != null) {
                    Spacer(Modifier.width(6.dp))
                    OrbitMiniButton(CosmoIcons.Close, stringResource(Res.string.cosmo_close), ink, onClick = onClose)
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = (OrbitMiniMetrics.BITE_INSET_DP - OrbitMiniMetrics.BITE_DP).dp)
                .size((OrbitMiniMetrics.BITE_DP * 2).dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) OrbitRing((OrbitMiniMetrics.BITE_DP * 2).dp, look.accent, progress = null, spinning = true, stroke = 1.5.dp, moon = 3.dp)
            OrbitPlayButton(isPlaying, onPlayPause, look, size = OrbitMiniMetrics.PLAY_DP.dp, touch = OrbitMiniMetrics.TOUCH_DP.dp)
        }
    }
}

/** The test tag of the dock's small player. */
const val DOCK_MINI_TAG = "dock:mini"

/** The small player's title: 14.5 sp, weight 800. Also drawn by the morph, so the two are the same text. */
internal fun capsuleTitleStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 14.5.sp, fontWeight = FontWeight(800), lineHeight = 18.sp)

/** The small player's artist: 12.5 sp. */
internal fun capsuleArtistStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 12.5.sp, lineHeight = 16.sp)

/** The small player's disc: 48 dp. */
internal val COVER_SIZE = OrbitMiniMetrics.DISC_DP.dp

private const val EXPAND_DRAG_DP = 48
private const val EXPAND_FLING_PX_PER_S = 1000f
private const val SKIP_DRAG_DP = 56
private const val SKIP_FLING_PX_PER_S = 1200f
/** The card follows a sideways finger by this share of the distance. */
private const val SWIPE_FOLLOW = 0.5f
private const val LOCKED_ALPHA = 0.5f
