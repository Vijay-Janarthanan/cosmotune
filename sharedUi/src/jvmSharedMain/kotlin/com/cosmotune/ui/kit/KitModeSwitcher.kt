package com.cosmotune.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.cosmotune.ui.components.trackFocusModality
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_mode_manage
import com.cosmotune.music.sharedui.resources.cosmo_mode_manga
import com.cosmotune.music.sharedui.resources.cosmo_mode_music
import com.cosmotune.music.sharedui.resources.cosmo_mode_podio
import com.cosmotune.music.sharedui.resources.cosmo_mode_read
import com.cosmotune.music.sharedui.resources.cosmo_mode_switch
import com.cosmotune.music.sharedui.resources.cosmo_mode_video
import com.cosmotune.music.sharedui.resources.cosmo_mode_watch
import androidx.compose.material.icons.rounded.Movie
import com.cosmotune.music.sharedui.resources.library
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource

/*
 * The mode switcher (DESIGN_SYSTEM.md 4.2): one control, in the same place, with the same behaviour in every mode. On the phone a
 * pill top-left on every root page opens the mode sheet; on the desktop the sidebar's mode chip opens the same list as a popup menu
 * ([KitModeMenu]), and Ctrl (Cmd on macOS) + 1 to 6 switches directly. Modes keep their own back stacks; the order is fixed (AppModes.switcherOrder) and the
 * unavailable ones are hidden.
 */

/** A mode's glyph. */
fun modeIcon(mode: AppMode): ImageVector = when (mode) {
    AppMode.MUSIC -> CosmoIcons.Waveform
    AppMode.VIDEO -> CosmoIcons.Video
    AppMode.PODIO -> CosmoIcons.Radio
    AppMode.READ -> CosmoIcons.List
    AppMode.MANGA -> CosmoIcons.Grid
    AppMode.LIBRARY -> CosmoIcons.Library
    AppMode.WATCH -> androidx.compose.material.icons.Icons.Rounded.Movie
}

/** A mode's name. */
@Composable
fun modeName(mode: AppMode): String = stringResource(
    when (mode) {
        AppMode.MUSIC -> Res.string.cosmo_mode_music
        AppMode.VIDEO -> Res.string.cosmo_mode_video
        AppMode.PODIO -> Res.string.cosmo_mode_podio
        AppMode.READ -> Res.string.cosmo_mode_read
        AppMode.MANGA -> Res.string.cosmo_mode_manga
        AppMode.LIBRARY -> Res.string.library
        AppMode.WATCH -> Res.string.cosmo_mode_watch
    },
)

/**
 * The mode pill as an Orbit chip: 40 dp (a 48 dp target), round, the chip fill, the mode's glyph, its name in the chip face and a
 * chevron in the dim ink; [collapsed] (a scrolled bar, the tablet rail) shows the glyph and the chevron only. A tap is [onClick]
 * (open the mode sheet).
 */
@Composable
fun KitModePill(mode: AppMode, onClick: () -> Unit, modifier: Modifier = Modifier, collapsed: Boolean = false) {
    val look = com.cosmotune.ui.orbit.orbitLook()
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    val name = modeName(mode)
    val switchLabel = stringResource(Res.string.cosmo_mode_switch)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(40.dp)
            .cosmoFocusRing(interaction, 20.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(look.chip)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = switchLabel, onClick = onClick)
            .semantics { contentDescription = "$name, $switchLabel" }
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(modeIcon(mode), contentDescription = null, tint = look.ink, modifier = Modifier.size(20.dp))
        if (!collapsed) Text(name, style = fonts.chip, color = look.ink, maxLines = 1)
        Icon(CosmoIcons.ChevronDown, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp))
    }
}

/**
 * The mode sheet as an Orbit sheet (the song sheet's surface): the mono "Mode" label over "Switch mode", then the shown modes as
 * Orbit rows, each with its glyph, its name and its [status] line ("Playing · Golden Hour", "3 new chapters"), the [current] one
 * solid ink with the peach dot. [onManage] adds "Manage modes" at the foot.
 */
@Composable
fun KitModeSheet(
    current: AppMode,
    onSelect: (AppMode) -> Unit,
    onDismiss: () -> Unit,
    status: (AppMode) -> String? = { null },
    onManage: (() -> Unit)? = null,
) {
    com.cosmotune.ui.orbit.OrbitPickerSheet(onDismiss = onDismiss) {
        KitModeSheetContent(current, onSelect, onDismiss, status, onManage)
    }
}

/** What [KitModeSheet] holds, on its own so a render can draw it without the sheet's window. */
@Composable
fun KitModeSheetContent(
    current: AppMode,
    onSelect: (AppMode) -> Unit,
    onDismiss: () -> Unit,
    status: (AppMode) -> String? = { null },
    onManage: (() -> Unit)? = null,
) {
    com.cosmotune.ui.orbit.OrbitSheetTitle(stringResource(Res.string.cosmo_mode_switch), label = "Mode")
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in AppModes.shown()) {
            com.cosmotune.ui.orbit.OrbitPickRow(
                title = modeName(mode),
                detail = status(mode),
                icon = modeIcon(mode),
                selected = mode == current,
                onClick = { onSelect(mode); onDismiss() },
            )
        }
        if (onManage != null) {
            com.cosmotune.ui.orbit.OrbitSheetAction(CosmoIcons.Settings, stringResource(Res.string.cosmo_mode_manage), onClick = { onDismiss(); onManage() })
        }
    }
}

/**
 * The phone's switcher in one piece: the [KitModePill] of the stored mode, which opens [KitModeSheet] and stores the mode chosen
 * (`CosmoLookSettings.setMode`, so every host switches with it). Draws nothing without the look's shell (no theme above).
 */
@Composable
fun KitModeSwitcher(modifier: Modifier = Modifier, collapsed: Boolean = false, status: (AppMode) -> String? = { null }, onManage: (() -> Unit)? = null) {
    val settings = LocalCosmoShell.current?.settings ?: return
    val mode by settings.mode.collectAsState()
    var open by remember { mutableStateOf(false) }
    KitModePill(mode, onClick = { open = true }, modifier = modifier, collapsed = collapsed)
    if (open) KitModeSheet(current = mode, onSelect = settings::setMode, onDismiss = { open = false }, status = status, onManage = onManage)
}

/** The shortcut a mode's line names: "⌘1" on macOS, "Ctrl+1" elsewhere; none past the sixth (the keys stop at 6). */
fun modeShortcutLabel(index: Int, mac: Boolean = com.cosmotune.ui.orbit.orbitIsMac): String? =
    if (index in 0 until 6) com.cosmotune.ui.orbit.orbitShortcut("${index + 1}", mac) else null

/**
 * The desktop's list of modes as a menu: a popup under its anchor (the sidebar's mode chip), [width] wide, on the Orbit menu's
 * ground with its shadow and radius, over the page (the sidebar under it does not move). A line is the mode's small glyph, its name
 * and its shortcut at the end; the [current] one has a check and a faint fill, the pointer's a fainter wash, and the keyboard's
 * focus its ring (only when the keyboard put it there). The arrows move, Enter picks, Esc or a click outside closes.
 */
@Composable
fun KitModeMenu(current: AppMode, onSelect: (AppMode) -> Unit, onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp) {
    val look = com.cosmotune.ui.orbit.orbitLook()
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    val modes = AppModes.shown()
    val requesters = remember(modes) { modes.map { androidx.compose.ui.focus.FocusRequester() } }
    var focused by remember { androidx.compose.runtime.mutableIntStateOf(modes.indexOf(current).coerceAtLeast(0)) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val position = remember(density) { ModeMenuPosition(with(density) { 6.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }) }
    val shape = RoundedCornerShape(20.dp)
    fun move(to: Int) {
        if (modes.isEmpty()) return
        focused = (to + modes.size) % modes.size
        runCatching { requesters[focused].requestFocus() }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(width)
                .trackFocusModality()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.DirectionDown -> { move(focused + 1); true }
                        Key.DirectionUp -> { move(focused - 1); true }
                        Key.MoveHome -> { move(0); true }
                        Key.MoveEnd -> { move(modes.size - 1); true }
                        Key.Enter, Key.NumPadEnter -> { modes.getOrNull(focused)?.let(onSelect); true }
                        else -> false
                    }
                }
                .shadow(24.dp, shape, clip = false)
                .clip(shape)
                .background(com.cosmotune.ui.orbit.orbitFloatGround(look))
                .border(1.dp, com.cosmotune.ui.orbit.orbitFloatEdge(look), shape)
                .padding(6.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            modes.forEachIndexed { index, mode ->
                ModeMenuLine(
                    mode = mode,
                    shortcut = modeShortcutLabel(index),
                    current = mode == current,
                    look = look,
                    fonts = fonts,
                    modifier = Modifier.focusRequester(requesters[index]).onFocusChanged { if (it.isFocused) focused = index },
                    onClick = { onSelect(mode) },
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { requesters[focused].requestFocus() } }
    }
}

@Composable
private fun ModeMenuLine(
    mode: AppMode,
    shortcut: String?,
    current: Boolean,
    look: com.cosmotune.ui.orbit.OrbitLook,
    fonts: com.cosmotune.ui.orbit.OrbitFonts,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        current && hovered -> look.ink.copy(alpha = 0.12f)
        current -> look.ink.copy(alpha = 0.08f)
        hovered -> look.ink.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .cosmoFocusRing(interaction, 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .selectable(selected = current, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(modeIcon(mode), contentDescription = null, tint = if (current) look.ink else look.dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(modeName(mode), style = fonts.chip, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (current) {
            Icon(CosmoIcons.Check, contentDescription = null, tint = look.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (shortcut != null) Text(shortcut, style = fonts.label, color = look.dim, maxLines = 1)
    }
}

/** Under its anchor with their start edges together, 6 dp down; over it when there is no room under; always on screen. */
private class ModeMenuPosition(private val gap: Int, private val margin: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val start = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val x = start.coerceIn(0, maxX.coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height - margin) below
        else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

/**
 * The desktop's mode shortcuts: Ctrl (or Cmd) + 1 to 6 switches to that mode of the switcher's order. For a window's
 * `onPreviewKeyEvent`; true when the key was a mode shortcut and the mode was set.
 */
fun modeShortcut(event: KeyEvent, onSelect: (AppMode) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return false
    val digit = when (event.key) {
        Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4; Key.Five -> 5; Key.Six -> 6
        else -> return false
    }
    val mode = AppModes.forShortcut(digit) ?: return false
    onSelect(mode)
    return true
}
