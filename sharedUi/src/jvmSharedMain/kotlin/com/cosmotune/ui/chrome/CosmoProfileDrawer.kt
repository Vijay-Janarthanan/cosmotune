package com.cosmotune.ui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.design.DrawerMetrics
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.STATE_LAYER_HOVERED
import com.cosmotune.ui.components.STATE_LAYER_PRESSED
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.components.trackFocusModality
import com.cosmotune.ui.components.keepRedrawnWith
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_close
import com.music.bitchord.sharedui.resources.cosmo_mode
import com.music.bitchord.sharedui.resources.cosmo_mode_later
import com.music.bitchord.sharedui.resources.cosmo_mode_library_later_text
import com.music.bitchord.sharedui.resources.cosmo_mode_library_later_title
import com.music.bitchord.sharedui.resources.cosmo_mode_library_summary
import com.music.bitchord.sharedui.resources.cosmo_mode_music
import com.music.bitchord.sharedui.resources.cosmo_mode_music_summary
import com.music.bitchord.sharedui.resources.cosmo_mode_video
import com.music.bitchord.sharedui.resources.cosmo_mode_video_later_text
import com.music.bitchord.sharedui.resources.cosmo_mode_video_later_title
import com.music.bitchord.sharedui.resources.cosmo_mode_video_summary
import com.music.bitchord.sharedui.resources.cosmo_not_affiliated
import com.music.bitchord.sharedui.resources.cosmo_profile
import com.music.bitchord.sharedui.resources.ic_logo
import com.music.bitchord.sharedui.resources.library
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** One row of the drawer's list below the modes: an [icon], its [label], an [aside] at the end (a count, a month, a state) and what it does. */
@Immutable
data class CosmoDrawerEntry(val icon: ImageVector, val label: String, val aside: String? = null, val onClick: () -> Unit)

/**
 * The profile drawer (`.drawer` of the boards): from the start edge, [PANEL_WIDTH] wide (at most 85 % of the window), full
 * height, with end corners of 17 dp, on the C glass surface (solid: it floats over a screen that already has two blurred
 * layers, and has no [dev.chrisbanes.haze.HazeState] to sample) under a black scrim at 56 %. It is drawn in a [DrawerWindow],
 * a window as large as the screen with the bars under it, so the panel and the scrim cover them on both platforms; the content
 * keeps clear of the bars by [DrawerMetrics]. A tap on the scrim, the Back key or Escape ([onDismiss]), or a drag or fling towards
 * the start closes it (RTL mirrors).
 *
 * Inside, top to bottom: the head (avatar, [name], [subtitle]; a tap is [onAccount], or nothing while that is null), the
 * mode switch, the [entries] and the foot with the logo and the "not affiliated" line. The switch shows the stored mode
 * ([com.cosmotune.settings.CosmoLookSettings.mode]); a mode that works ([AppModes]) is stored when it is tapped and the drawer closes,
 * and a mode that does not (Library) carries a "Later" tag, stays unselected and opens a card that says
 * when it comes.
 *
 * It slides in over 280 ms and out over 200 ms (reduced motion: a 150 ms fade) and stays composed until the exit has run.
 * The slide, the drag and the scrim are read in draw and layer blocks only, so a finger or a frame recomposes nothing.
 */
@Composable
fun CosmoProfileDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    name: String,
    subtitle: String?,
    avatarUrl: String?,
    entries: List<CosmoDrawerEntry>,
    onAccount: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    // 0 = closed, 1 = open. In: 280 ms; out: 200 ms; reduced motion: 150 ms either way.
    val slide = remember { Animatable(0f) }
    LaunchedEffect(open, reduced) {
        val spec = when {
            reduced -> tween<Float>(REDUCED_MS)
            open -> tween<Float>(ENTER_MS, easing = ENTER_EASING)
            else -> tween<Float>(EXIT_MS, easing = EXIT_EASING)
        }
        slide.animateTo(if (open) 1f else 0f, spec)
    }
    // Composed while open and until the exit has run.
    val shown by remember(open) { derivedStateOf { open || slide.value > 0f } }
    if (!shown) return

    // Read here, in the window that has the bars: the drawer's own window may not report them.
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    DrawerWindow(onDismissRequest = onDismiss) {
        DrawerLayer(
            slide = slide,
            statusTop = statusTop,
            navBottom = navBottom,
            onDismiss = onDismiss,
            name = name,
            subtitle = subtitle,
            avatarUrl = avatarUrl,
            entries = entries,
            onAccount = onAccount,
            modifier = modifier,
        )
    }
}

/**
 * The profile picture of the page's top bar: a [size] avatar circle (the photo at [avatarUrl], or a tonal circle with the
 * user glyph) in a 48 dp touch target. A click opens the drawer it owns; the drawer's head calls [onAccount] and closes it, an
 * entry closes it and calls its own action.
 */
@Composable
fun CosmoProfileButton(
    avatarUrl: String?,
    name: String,
    subtitle: String?,
    entries: List<CosmoDrawerEntry>,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.cosmo_profile)
    val haptics = rememberHaptics()
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = remember { Animatable(1f) }
    LaunchedEffect(pressed, reduced) { press.animateTo(if (pressed && !reduced) PROFILE_PRESS_SCALE else 1f, CosmoMotion.press(reduced)) }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.play(Haptic.Tap); open = true }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Avatar(avatarUrl, size, Modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }.cosmoFocusRing(interaction, size / 2))
    }
    // An entry closes the drawer and then does its thing.
    val closing = remember(entries) { entries.map { entry -> entry.copy(onClick = { open = false; entry.onClick() }) } }
    CosmoProfileDrawer(
        open = open,
        onDismiss = { open = false },
        name = name,
        subtitle = subtitle,
        avatarUrl = avatarUrl,
        entries = closing,
        onAccount = { open = false; onAccount() },
    )
}

// ---- The layer: scrim, panel, "later" card ----------------------------------------------------------------------------------

@Composable
private fun DrawerLayer(
    slide: Animatable<Float, AnimationVector1D>,
    statusTop: Dp,
    navBottom: Dp,
    onDismiss: () -> Unit,
    name: String,
    subtitle: String?,
    avatarUrl: String?,
    entries: List<CosmoDrawerEntry>,
    onAccount: (() -> Unit)?,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    // The way the start edge lies: left in a left-to-right layout (-1), right in a right-to-left one (+1).
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    // How far the finger has pulled the panel towards the start, in pixels (0 = at rest).
    var drag by remember { mutableFloatStateOf(0f) }
    var later by remember { mutableStateOf<AppMode?>(null) }
    val root = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
    // Back and Escape close the "later" card first, then the drawer.
    fun back() {
        if (later != null) later = null else onDismiss()
    }
    val profile = stringResource(Res.string.cosmo_profile)
    val closeLabel = stringResource(Res.string.cosmo_close)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // The drawer's window does not see the main window's keys: its own rows' rings follow its own keys.
            .trackFocusModality()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { back(); true } else false
            }
            .focusRequester(root)
            .focusTarget(),
    ) {
        val panelWidth = minOf(PANEL_WIDTH, maxWidth * PANEL_MAX_SHARE)
        val panelPx = with(density) { panelWidth.toPx() }
        val dismissPx = with(density) { DRAG_CLOSE.toPx() }
        val flingPx = with(density) { FLING_CLOSE.toPx() }
        val dragState = rememberDraggableState { delta -> drag = (drag + delta * towardStart).coerceIn(0f, panelPx) }

        // Scrim and panel: a drag towards the start anywhere on them closes the drawer past 64 dp or a fling.
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = later == null,
                    onDragStopped = { velocity ->
                        if (drag > dismissPx || velocity * towardStart > flingPx) {
                            // Hand the finger's distance to the slide, so the exit starts where the panel is.
                            if (!reduced) { slide.snapTo((1f - drag / panelPx).coerceIn(0f, 1f)); drag = 0f }
                            onDismiss()
                        } else {
                            animate(drag, 0f, animationSpec = CosmoMotion.move(reduced)) { value, _ -> drag = value }
                        }
                    },
                ),
        ) {
            // The scrim: black at 56 %, which follows the slide and the finger; a tap closes.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val share = (slide.value * (1f - drag / panelPx)).coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * share))
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { back() }) }
                    .semantics { contentDescription = closeLabel; onClick(label = closeLabel) { back(); true } },
            )

            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // Slides from the start edge; under reduced motion it stays put and fades.
                        val resting = if (reduced) 0f else (1f - slide.value) * size.width
                        translationX = towardStart * (resting + drag)
                        alpha = if (reduced) slide.value else 1f
                    }
                    .cosmoGlass(hazeState = null, shape = PANEL_SHAPE)
                    // A tap on the panel's own background must not fall through to the scrim.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .semantics { paneTitle = profile; isTraversalGroup = true }
                    .padding(
                        top = DrawerMetrics.panelTopPaddingDp(statusTop.value).dp,
                        bottom = DrawerMetrics.panelBottomPaddingDp(navBottom.value).dp,
                    ),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    DrawerHead(name, subtitle, avatarUrl, onAccount)
                    Separator()
                    ModeSwitch(onChosen = onDismiss, onLater = { later = it })
                    Spacer(Modifier.height(14.dp))
                    Separator()
                    Spacer(Modifier.height(10.dp))
                    for (entry in entries) EntryRow(entry)
                }
                DrawerFoot()
            }
        }

        later?.let { mode -> LaterCard(mode, navBottom, onClose = { later = null }) }
    }
}

// ---- Head ----------------------------------------------------------------------------------------------------------------------

/** The avatar, [name] and [subtitle]; a tap is [onAccount]. 20 dp from the panel's edge, 20 dp below it. */
@Composable
private fun DrawerHead(name: String, subtitle: String?, avatarUrl: String?, onAccount: (() -> Unit)?) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val hovered = interaction.collectIsHoveredAsState()
    val haptics = rememberHaptics()
    // 12 dp in from the panel, 8 dp of padding in the row: the avatar stands 20 dp from the edge, like the labels below it.
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .fillMaxWidth()
            .cosmoFocusRing(interaction, 16.dp)
            .stateLayer(colors, palette, pressed, hovered, 16.dp)
            .then(
                if (onAccount != null) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.play(Haptic.Tap); onAccount() }
                else Modifier,
            )
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarUrl, AVATAR)
        Column(Modifier.weight(1f)) {
            val type = LocalCosmoType.current
            DrawerText(name, type.title(TypeScale.TITLE_M), Color(palette.textPrimary))
            if (subtitle != null) DrawerText(subtitle, type.sized(13f, 400), Color(palette.textSecondary), maxLines = 2)
        }
    }
}

/** A circle with the picture at [avatarUrl] over a tonal placeholder with the user glyph (also what a failed load leaves). */
@Composable
private fun Avatar(avatarUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind {
                keepRedrawnWith(palette)
                drawCircle(tonalFill(colors))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CosmoIcons.User, contentDescription = null, tint = Color(palette.onAccentContainer), modifier = Modifier.size(size * 0.5f))
        if (avatarUrl != null) {
            AsyncImage(model = avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

// ---- Modes ---------------------------------------------------------------------------------------------------------------------

/**
 * "Mode" and its three radio rows. The current mode is the stored one (Music where there is no shell to store it). A tap on a mode
 * that works stores it and calls [onChosen] (it closes the drawer); a mode that does not work opens the "later" card.
 */
@Composable
private fun ModeSwitch(onChosen: () -> Unit, onLater: (AppMode) -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val settings = LocalCosmoShell.current?.settings
    val current = settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC
    Text(
        text = stringResource(Res.string.cosmo_mode),
        style = type.sized(12f, 700),
        color = Color(palette.textTertiary),
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 8.dp).semantics { heading() },
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (mode in AppMode.entries) {
            val available = AppModes.available(mode)
            if (!available) continue
            ModeRow(
                icon = when (mode) { AppMode.MUSIC -> CosmoIcons.Waveform; AppMode.VIDEO -> CosmoIcons.Video; AppMode.LIBRARY -> CosmoIcons.Library },
                title = stringResource(when (mode) { AppMode.MUSIC -> Res.string.cosmo_mode_music; AppMode.VIDEO -> Res.string.cosmo_mode_video; AppMode.LIBRARY -> Res.string.library }),
                summary = stringResource(when (mode) { AppMode.MUSIC -> Res.string.cosmo_mode_music_summary; AppMode.VIDEO -> Res.string.cosmo_mode_video_summary; AppMode.LIBRARY -> Res.string.cosmo_mode_library_summary }),
                // A mode that does not work never becomes the selected one.
                selected = available && mode == current,
                later = !available,
                onClick = { if (available) { settings?.setMode(mode); onChosen() } else onLater(mode) },
            )
        }
    }
}

/**
 * One mode: min 64 dp, padding 10 20, 14 dp between its parts. The selected one has the `--row-on` wash, radius 18, 8 dp in
 * from the sides, and a tick at the end. A mode that is not available is still a focusable radio button that says "Later".
 */
@Composable
private fun ModeRow(icon: ImageVector, title: String, summary: String, selected: Boolean, later: Boolean, onClick: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val hovered = interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .cosmoFocusRing(interaction, 18.dp)
            .stateLayer(colors, palette, pressed, hovered, 18.dp, selectedWash = selected)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = { haptics.play(Haptic.Select); onClick() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color(if (selected) palette.accent else palette.textSecondary), modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DrawerText(title, type.sized(15.5f, 650), Color(palette.textPrimary), modifier = Modifier.weight(1f, fill = false))
                if (later) {
                    Spacer(Modifier.width(6.dp))
                    LaterTag()
                }
            }
            DrawerText(summary, type.sized(13f, 400), Color(palette.textSecondary), modifier = Modifier.padding(top = 2.dp), maxLines = 2)
        }
        if (selected) Icon(CosmoIcons.Check, contentDescription = null, tint = Color(palette.textPrimary), modifier = Modifier.size(20.dp))
    }
}

/** The "Later" pill: 18 dp high (more under a large font), 6 dp of padding, 11.5 sp bold on `accentContainer`. */
@Composable
private fun LaterTag() {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Box(
        Modifier
            .heightIn(min = 18.dp)
            .drawBehind {
                keepRedrawnWith(palette)
                drawRoundRect(colors.live(CoverField.ACCENT_CONTAINER), cornerRadius = CornerRadius(size.height / 2f))
            }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        DrawerText(stringResource(Res.string.cosmo_mode_later), type.sized(11.5f, 700), Color(palette.onAccentContainer))
    }
}

// ---- Entries and foot ------------------------------------------------------------------------------------------------------------

/** A row of the list under the modes (`.drawer-nav a`): 48 dp, 12 dp in from the sides, padding 0 12, radius 16, 16 dp between its parts. */
@Composable
private fun EntryRow(entry: CosmoDrawerEntry) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val hovered = interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .cosmoFocusRing(interaction, 16.dp)
            .stateLayer(colors, palette, pressed, hovered, 16.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { haptics.play(Haptic.Tap); entry.onClick() }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(24.dp))
        DrawerText(entry.label, type.sized(15.5f, 500), Color(palette.textPrimary), modifier = Modifier.weight(1f))
        if (entry.aside != null) DrawerText(entry.aside, type.sized(12f, 400).tabular(), Color(palette.textTertiary))
    }
}

/** At the bottom: the logo mark and the line that says Cosmotune is not any music service's app, 12.5 sp, line height 1.45, 24 dp in. */
@Composable
private fun DrawerFoot() {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Icon(
            painter = painterResource(Res.drawable.ic_logo),
            contentDescription = null,
            tint = brandMarkColor(colors.dark),
            modifier = Modifier.size(TopBarMetrics.LOGO_WIDTH, TopBarMetrics.LOGO_HEIGHT),
        )
        val line = stringResource(Res.string.cosmo_not_affiliated)
        DrawerText(line, type.sized(12.5f, 400, lineHeight = 1.45f), Color(palette.textTertiary), modifier = Modifier.padding(top = 8.dp), maxLines = 4)
    }
}

/** A 1 dp rule in `textPrimary` at 10 % (the menu separator). */
@Composable
private fun Separator() {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                keepRedrawnWith(palette)
                drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = SEPARATOR_ALPHA))
            },
    )
}

// ---- The "later" card ---------------------------------------------------------------------------------------------------------------

/**
 * What a tap on a mode that does not exist yet says: a C card (superellipse 36, solid glass) at the bottom of the window, 8 dp
 * in from its edges, over the drawer and a 40 % black scrim. A tap outside it, Back or Escape close it, and so does its button.
 */
@Composable
private fun BoxScope.LaterCard(mode: AppMode, navBottom: Dp, onClose: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val title = stringResource(if (mode == AppMode.LIBRARY) Res.string.cosmo_mode_library_later_title else Res.string.cosmo_mode_video_later_title)
    val text = stringResource(if (mode == AppMode.LIBRARY) Res.string.cosmo_mode_library_later_text else Res.string.cosmo_mode_video_later_text)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, CosmoMotion.move(reduced)) }
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = CARD_SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, bottom = max(CosmoRadii.sheetInset, navBottom))
            .widthIn(max = CARD_MAX_WIDTH)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1f - appear.value) * CARD_RISE.toPx()
                alpha = appear.value.coerceIn(0f, 1f)
            }
            .cosmoGlass(hazeState = null, shape = CARD_SHAPE)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .semantics { paneTitle = title; isTraversalGroup = true }
            .padding(20.dp),
    ) {
        DrawerText(title, type.title(TypeScale.TITLE_M), Color(palette.textPrimary), maxLines = 3)
        DrawerText(text, type.sized(14f, 400, lineHeight = 1.55f), Color(palette.textSecondary), modifier = Modifier.padding(top = 8.dp), maxLines = 12)
        CosmoButton(
            text = stringResource(Res.string.cosmo_close),
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            kind = CosmoButtonKind.TONAL,
            floating = true,
        )
    }
}

// ---- Shared pieces ---------------------------------------------------------------------------------------------------------------------

/** Text in the drawer: one line unless [maxLines] says more, ellipsised, with the taller line of the Indian scripts. */
@Composable
private fun DrawerText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text = text, modifier = modifier, color = color, style = LocalCosmoType.current.forText(style, text), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Hanken Grotesk at [sp] and [weight] (the sizes of the drawer are the board's, between the steps of the scale). */
private fun CosmoType.sized(sp: Float, weight: Int, lineHeight: Float = 1.42f): TextStyle =
    body(TypeScale.BODY, FontWeight(weight)).copy(fontSize = sp.sp, lineHeight = (sp * lineHeight).sp)

/**
 * What a row draws behind its content: the `--row-on` wash (the cover's `accentContainer` at 70 % fading to nothing along
 * the row, mirrored in RTL) when [selectedWash], then a state layer of ink while pressed or hovered. Colours are read as it draws.
 */
private fun Modifier.stateLayer(
    colors: CosmoColors,
    palette: com.cosmotune.theme.CoverColors,
    pressed: State<Boolean>,
    hovered: State<Boolean>,
    radius: Dp,
    selectedWash: Boolean = false,
): Modifier = drawBehind {
    keepRedrawnWith(palette)
    val corner = CornerRadius(radius.toPx())
    if (selectedWash) {
        val container = colors.live(CoverField.ACCENT_CONTAINER)
        val rtl = layoutDirection == LayoutDirection.Rtl
        drawRoundRect(
            brush = Brush.horizontalGradient(
                0f to container.copy(alpha = container.alpha * ROW_ON_START),
                ROW_ON_MIDDLE_AT to container.copy(alpha = container.alpha * ROW_ON_MIDDLE),
                1f to container.copy(alpha = 0f),
                startX = if (rtl) size.width else 0f,
                endX = if (rtl) 0f else size.width,
            ),
            cornerRadius = corner,
        )
    }
    val layer = if (pressed.value) STATE_LAYER_PRESSED else if (hovered.value) STATE_LAYER_HOVERED else 0f
    if (layer > 0f) drawRoundRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = layer), cornerRadius = corner)
}

// ---- Numbers -----------------------------------------------------------------------------------------------------------------------------

/** `.drawer` is 316 dp wide, at most 85 % of the window. */
internal val PANEL_WIDTH = 316.dp
private const val PANEL_MAX_SHARE = 0.85f

/** 22 / 1.3: the end corners (`--r-lg`). */
private val PANEL_SHAPE = RoundedCornerShape(topStart = 0.dp, topEnd = 17.dp, bottomEnd = 17.dp, bottomStart = 0.dp)

private val AVATAR = 48.dp

/** The black behind the drawer (a documented exception to "no colour by hand": a scrim) and behind the "later" card. */
internal const val SCRIM_ALPHA = 0.56f
private const val CARD_SCRIM_ALPHA = 0.40f

/** `280 ms in, 200 ms out` (`shell-phone.html`), on `--ease-enter` and `--ease-exit`. */
internal const val ENTER_MS = 280
internal const val EXIT_MS = 200
private const val REDUCED_MS = com.cosmotune.design.Springs.REDUCED_MS
private val ENTER_EASING = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EXIT_EASING = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** A drag towards the start of more than this closes it, and so does a fling faster than [FLING_CLOSE] per second. */
private val DRAG_CLOSE = 64.dp
private val FLING_CLOSE = 700.dp

private const val SEPARATOR_ALPHA = 0.10f
private const val ROW_ON_START = 0.7f
private const val ROW_ON_MIDDLE = 0.2f
private const val ROW_ON_MIDDLE_AT = 0.75f
private const val PROFILE_PRESS_SCALE = 0.94f

private val CARD_SHAPE = SuperellipseShape(CosmoRadii.sheet)
private val CARD_MAX_WIDTH = 420.dp
private val CARD_RISE = 16.dp
