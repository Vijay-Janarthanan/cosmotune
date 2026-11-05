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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.cosmotune.music.sharedui.resources.widget_previous
import com.cosmotune.ui.nowplaying.PlayingQualityPill
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.AppMode
import com.cosmotune.design.CapsuleGeometry
import com.cosmotune.design.ShellMetrics
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
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.now_playing
import com.cosmotune.music.sharedui.resources.widget_next
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.PlayerPlatform
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import com.cosmotune.music.sharedui.resources.cosmo_close
import com.cosmotune.ui.theme.rememberDarkChromeColors

/** One tab of the dock: its [label] (shown under its icon on every tab) and the two icons (outlined at rest, filled when chosen). */
@Immutable
data class CosmoTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * The phone's dock (layer C of the B+C system, revamped after Prism's and YTXTube's docked players; see NOTICE.md): one glass
 * surface along the bottom of the window, [ShellMetrics.SIDE_MARGIN_DP] from the sides and [ShellMetrics.bottomOffsetDp] above the
 * bottom (16 dp above a gesture bar, 8 dp above a taller three-button bar), that holds the mini player (when a song is loaded) on
 * top of the tab bar. Notices stand above it.
 *
 * One surface, so the mini player and the tabs never stack as two floating cards over the page, and one blurred layer instead of
 * two. The glass follows [cosmoGlass]'s policy: solid (no blur) below Android 12, with "Reduce dynamic blur", on low-RAM phones
 * and without a [hazeState]; with reduced motion the selection pill and the dock's growth are short fades, not springs.
 *
 * [onHeightChanged] gets the column's height in pixels including that bottom offset, for upstream's `FloatingBarsTapGuard`; the same
 * height is published on the shell ([com.cosmotune.ui.theme.CosmoShellState.dockHeightPx]) for the pages that pad for it
 * ([rememberDockClearance]). [controlsLocked] is a party whose host has taken the controls: "next" (and a sideways swipe) then
 * answers with [onBlockedControl]. [onPrevious], when given, is what a swipe to the right does. [miniPlayer] stands in for the
 * built-in mini player (Video's, whose capsule is its own): it is drawn inside the dock, above the tabs, when [song] is null.
 *
 * [mode] is the mode this chrome belongs to: while the stored mode ([com.cosmotune.settings.CosmoLookSettings.mode]) is another
 * one it draws nothing (Music's compact desktop bar steps aside in Video; Video's shell passes [AppMode.VIDEO]).
 *
 * A mode that plays its own media (Video) passes [ownMedia]: its [song] is not Music's, so the mini player owes nothing to Music's
 * player (no morph bounds, no hiding while Music's player stands open, no artwork host, no measured-quality pill). [onClose] adds a
 * close button after play and pause: what stops that media. [showTabs] false leaves the mini player alone in the dock (a wide window,
 * whose tabs stand in the sidebar). [dark] draws the dock on the dark palette in either theme (next to the Shorts pager, which is black).
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
    val docked = song != null || miniPlayer != null
    val dockShape = if (docked) DOCK_SHAPE else TAB_BAR_SHAPE
    val moveSpec = remember(reduced) { moveSizeSpec(reduced) }
    val darkDock = rememberDarkChromeColors()
    // The same call tree either way: only the value of the local changes.
    CompositionLocalProvider(LocalCosmoColors provides (if (dark) darkDock else LocalCosmoColors.current)) {
    Column(
        modifier
            // Before the padding, so the height reported is the whole of what the chrome takes from the bottom.
            .onSizeChanged { onHeightChanged(it.height); shell?.dockHeightPx = it.height }
            .padding(horizontal = DOCK_SIDE_INSET)
            .padding(bottom = ShellMetrics.bottomOffsetDp(navInsetDp).dp),
    ) {
        notices()
        Column(
            Modifier
                .fillMaxWidth()
                .cosmoGlass(hazeState, dockShape)
                // The dock grows by the mini player's height on the move spring (a fade with reduced motion).
                .animateContentSize(moveSpec),
        ) {
            if (song != null) {
                MiniCapsule(song, isPlaying, isLoading, position, durationMs, onPlayPause, onNext, onPrevious, onExpand, controlsLocked, onBlockedControl, onClose, ownMedia)
            } else if (miniPlayer != null) {
                miniPlayer()
            }
            if (showTabs) FloatingTabBar(tabs, selectedIndex, onTabSelected, divided = docked)
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
 * The tab bar (`.bar2`, revamped): 64 dp, every tab labelled under its icon, and one selection pill (the tonal `accentContainer`)
 * that slides from tab to tab on the move spring; the chosen tab's icon and label take `onAccentContainer` (4.5:1 on the pill by
 * the palette's rails), the others `textSecondary`. [divided]: a hairline separates it from the mini player above it.
 */
@Composable
private fun FloatingTabBar(tabs: List<CosmoTab>, selectedIndex: Int, onTabSelected: (Int) -> Unit, divided: Boolean) {
    val colors = LocalCosmoColors.current
    val reduced = LocalReducedMotion.current
    val count = tabs.size.coerceAtLeast(1)
    // Where the pill is, in tabs (0 = the first). Read in the draw phase only: a slide redraws the bar and recomposes nothing.
    val pill = remember { Animatable(selectedIndex.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(selectedIndex, reduced) {
        if (selectedIndex >= 0) pill.animateTo(selectedIndex.toFloat(), CosmoMotion.move(reduced))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ShellMetrics.TAB_BAR_HEIGHT_DP.dp)
            .drawBehind {
                if (divided) {
                    val inset = DIVIDER_INSET.toPx()
                    drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = DIVIDER_ALPHA), Offset(inset, 0f), Size(size.width - 2f * inset, 1.dp.toPx()))
                }
                if (selectedIndex < 0) return@drawBehind
                val pad = TAB_BAR_PAD.toPx()
                val slot = (size.width - 2f * pad) / count
                val inset = PILL_INSET.toPx()
                val height = PILL_HEIGHT.toPx()
                val start = pad + slot * pill.value + inset
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - start - (slot - 2f * inset) else start
                // DESIGN_SYSTEM.md 4.16: the selection is neutral (surface3), never the accent.
                drawRoundRect(
                    colors.live(CoverField.SURFACE_VARIANT),
                    Offset(left, (size.height - height) / 2f),
                    Size(slot - 2f * inset, height),
                    CornerRadius(height / 2f),
                )
            }
            .padding(horizontal = TAB_BAR_PAD)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            TabPill(tab, selected = index == selectedIndex, onClick = { onTabSelected(index) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One tab: its icon over its label, always both (an unlabelled glyph is a guess). The chosen one draws its filled icon in
 * `onAccentContainer` over the bar's pill; the others draw in `textSecondary`. It shrinks to 0.94 under the finger (not under
 * reduced motion).
 */
@Composable
private fun TabPill(tab: CosmoTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed, reduced) { press.animateTo(if (pressed && !reduced) TAB_PRESS_SCALE else 1f, CosmoMotion.press(reduced)) }
    val ink by animateColorAsState(
        targetValue = if (selected) Color(palette.textPrimary) else Color(palette.textSecondary),
        animationSpec = CosmoMotion.effects(reduced),
        label = "tab-ink",
    )
    val type = LocalCosmoType.current
    Column(
        modifier
            .graphicsLayer { scaleX = press.value; scaleY = press.value }
            .height(PILL_HEIGHT)
            .cosmoFocusRing(interaction, TAB_RADIUS, floating = true)
            .clip(TAB_SHAPE)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = { haptics.play(Haptic.Select); onClick() },
            )
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(TAB_ICON))
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            style = type.tabLabel,
            color = ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The move spring (or the 150 ms fade) as a finite spec for [animateContentSize]: [CosmoMotion.move] returns the wider
 * `AnimationSpec`, which that modifier does not take. Same numbers, same source.
 */
private fun moveSizeSpec(reduced: Boolean): FiniteAnimationSpec<IntSize> =
    if (reduced) tween(Springs.REDUCED_MS) else spring(Springs.MOVE.dampingRatio, Springs.MOVE.stiffness)

/** The dock (DESIGN_SYSTEM.md 4.16): one container with `rLg` corners, with or without the mini player, 8 dp in from the sides. */
private val DOCK_SHAPE = RoundedCornerShape(CosmoRadii.rLg)
private val TAB_BAR_SHAPE = DOCK_SHAPE
private val DOCK_SIDE_INSET = 8.dp
private val TAB_BAR_PAD = 6.dp
private val PILL_HEIGHT = 52.dp
private val PILL_INSET = 4.dp
private val TAB_ICON = 22.dp
private val TAB_RADIUS = 26.dp
private val TAB_SHAPE = RoundedCornerShape(TAB_RADIUS)
private val DIVIDER_INSET = 16.dp
private const val DIVIDER_ALPHA = 0.08f
private const val TAB_PRESS_SCALE = 0.94f

// ---- The mini player ----

/**
 * The mini player (`.mini2`, docked): the top 64 dp of the dock. Cover, title over artist (with the measured quality pill when the
 * stream is notable: Atmos, Hi-Res, lossless, high bitrate), next, and the play button. A 2 dp accent line on a hairline track runs
 * along its foot, drawn from the playhead in the draw phase only; the card itself stays neutral.
 *
 * A tap, an upward drag of 48 dp or an upward fling faster than 1000 px/s opens the player, which grows out of this row: the row
 * reports where it is ([com.cosmotune.ui.theme.CosmoShellState.capsuleBounds]) and goes invisible while the player stands in its
 * place. With [onPrevious] given, a sideways swipe of 56 dp (or a fling) skips: to the left the next song, to the right the previous one ([onPrevious]); the
 * row follows the finger by half the distance, and not at all with reduced motion. Screen readers get "Next" as a button and
 * "Previous" as an action.
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
    val colors = LocalCosmoColors.current
    val palette = colors.palette
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

    // What the morph copies: the words and the picture, and how this capsule is built (its corners, where the cover and the
    // words sit, their styles: the phone's numbers, which the layout below is made of). Published as a value, so an unchanged song
    // writes nothing.
    val label = remember(song.title, song.artist, coverUrl, type) {
        CapsuleLabel(song.title, song.artist, coverUrl, CapsuleGeometry.PHONE, capsuleTitleStyle(type.textFamily), capsuleArtistStyle(type.textFamily))
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
    // The sideways swipe: the row's offset, read in its layer block only.
    val swipe = remember { Animatable(0f) }
    val swipeState = rememberDraggableState { dx -> scope.launch { swipe.snapTo(swipe.value + dx) } }
    val skipDragPx = with(density) { SKIP_DRAG_DP.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(ShellMetrics.CAPSULE_HEIGHT_DP.dp)
            // Invisible while the player stands in its place. Not drawn at all, and decided in the draw phase, where the overlay's
            // card is decided too: the one swaps for the other in a single frame.
            .drawWithContent { if (ownMedia || shell?.capsuleHidden != true) drawContent() }
            .onGloballyPositioned { if (!ownMedia) shell?.capsuleBounds = it.boundsInRoot() }
            .testTag(DOCK_MINI_TAG)
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
            // The sideways swipe is opt-in (DESIGN_SYSTEM.md 4.16): only a caller that passes onPrevious turns it on.
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
            )
            .drawBehind {
                // The playhead is read here and nowhere else: a tick of the song redraws the tint and the line and recomposes nothing.
                val played = if (durationMs > 0L) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                // DESIGN_SYSTEM.md 4.16: a 2 dp accent line on a hairline track, flush with the mini player's foot; the card stays neutral.
                val rtl = layoutDirection == LayoutDirection.Rtl
                val width = size.width * played
                val left = if (rtl) size.width - width else 0f
                val thickness = PROGRESS_HEIGHT.toPx()
                drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = PROGRESS_TRACK_ALPHA), Offset(0f, size.height - thickness), Size(size.width, thickness))
                drawRect(colors.live(CoverField.ACCENT), Offset(left, size.height - thickness), Size(width, thickness))
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { if (!reduced) translationX = swipe.value * SWIPE_FOLLOW }
                .padding(CAPSULE_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(coverUrl, COVER_SIZE, CosmoRadii.coverMini, shadow = false)
            Spacer(Modifier.width(CAPSULE_COVER_GAP))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(song.title, style = capsuleTitleStyle(type.textFamily), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!ownMedia) PlayingQualityPill(Modifier.padding(end = 6.dp))
                    Text(song.artist, style = capsuleArtistStyle(type.textFamily), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CosmoIconButton(
                icon = CosmoIcons.Next,
                contentDescription = next,
                // A locked party answers with the host-only notice; the button stays drawn, a little dimmer.
                onClick = { if (controlsLocked) onBlockedControl() else onNext() },
                modifier = Modifier.graphicsLayer { alpha = if (controlsLocked) LOCKED_ALPHA else 1f },
                style = CosmoIconButtonStyle.PLAIN,
                size = 48.dp,
                floating = true,
            )
            PlayMorphButton(playing = isPlaying, loading = isLoading, onClick = onPlayPause, size = 48.dp, floating = true)
            if (onClose != null) {
                CosmoIconButton(
                    icon = CosmoIcons.Close,
                    contentDescription = stringResource(Res.string.cosmo_close),
                    onClick = onClose,
                    style = CosmoIconButtonStyle.PLAIN,
                    size = 48.dp,
                    floating = true,
                )
            }
        }
    }
}

/** The test tag of the dock's mini player. */
const val DOCK_MINI_TAG = "dock:mini"

/** The capsule's title: 15 sp, weight 700. Also drawn by the morph, so the two are the same text. */
internal fun capsuleTitleStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 18.75.sp)

/** The capsule's artist: 13 sp. */
internal fun capsuleArtistStyle(family: FontFamily) =
    TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 16.sp)

/** The capsule's cover: 48 dp. */
internal val COVER_SIZE = CapsuleGeometry.PHONE.coverSizeDp.dp

/**
 * The capsule's padding (the cover's inset from the left, top and bottom edges) and the gap between the cover and the words:
 * [CapsuleGeometry.PHONE]'s numbers, which the morph starts from, so the layout and what it says about itself cannot part.
 */
private val CAPSULE_PADDING = CapsuleGeometry.PHONE.coverInsetLeftDp.dp
private val CAPSULE_COVER_GAP = (CapsuleGeometry.PHONE.textStartDp - CapsuleGeometry.PHONE.coverInsetLeftDp - CapsuleGeometry.PHONE.coverSizeDp).dp

private val PROGRESS_HEIGHT = 2.dp
/** The progress track under the line: the hairline's ink at this alpha. */
private const val PROGRESS_TRACK_ALPHA = 0.08f
private const val EXPAND_DRAG_DP = 48
private const val EXPAND_FLING_PX_PER_S = 1000f
private const val SKIP_DRAG_DP = 56
private const val SKIP_FLING_PX_PER_S = 1200f
/** The row follows a sideways finger by this share of the distance. */
private const val SWIPE_FOLLOW = 0.5f
private const val LOCKED_ALPHA = 0.5f
