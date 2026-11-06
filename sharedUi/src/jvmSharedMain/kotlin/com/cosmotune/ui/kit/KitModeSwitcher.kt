package com.cosmotune.ui.kit

import androidx.compose.foundation.background
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
 * pill top-left on every root page opens the mode sheet; on the desktop the sidebar's first row opens the same list as a menu, and
 * Ctrl/Cmd + 1 to 6 switches directly. Modes keep their own back stacks; the order is fixed (AppModes.switcherOrder) and the
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
 * (`CosmoLookSettings.setMode`, so every host switches with it). Draws nothing without the new look.
 */
@Composable
fun KitModeSwitcher(modifier: Modifier = Modifier, collapsed: Boolean = false, status: (AppMode) -> String? = { null }, onManage: (() -> Unit)? = null) {
    val settings = LocalCosmoShell.current?.settings ?: return
    val mode by settings.mode.collectAsState()
    var open by remember { mutableStateOf(false) }
    KitModePill(mode, onClick = { open = true }, modifier = modifier, collapsed = collapsed)
    if (open) KitModeSheet(current = mode, onSelect = settings::setMode, onDismiss = { open = false }, status = status, onManage = onManage)
}

/**
 * The desktop sidebar's first row (DESIGN_SYSTEM.md 4.2): 56 dp, the mode's glyph, its name and a chevron; it opens the same list
 * as a menu ([KitModeMenuList], shown under it while open).
 */
@Composable
fun KitModeMenuButton(modifier: Modifier = Modifier) {
    val settings = LocalCosmoShell.current?.settings ?: return
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val mode by settings.mode.collectAsState()
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(CosmoRadii.rMd))
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.cosmo_mode_switch)) { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(modeIcon(mode), contentDescription = null, tint = Color(palette.textPrimary), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(modeName(mode), style = type.rowTitle, color = Color(palette.textPrimary), modifier = Modifier.weight(1f), maxLines = 1)
            Icon(if (open) CosmoIcons.ChevronUp else CosmoIcons.ChevronDown, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(18.dp))
        }
        if (open) KitModeMenuList(mode, onSelect = { settings.setMode(it); open = false })
    }
}

/** The list of modes as rows (the desktop's menu): glyph, name, the shortcut ("Ctrl+1"), the current one on `surface3`. */
@Composable
fun KitModeMenuList(current: AppMode, onSelect: (AppMode) -> Unit, modifier: Modifier = Modifier, shortcutPrefix: String = "Ctrl+") {
    val palette = LocalCosmoColors.current.palette
    Column(modifier.fillMaxWidth().selectableGroup()) {
        AppModes.shown().forEachIndexed { index, mode ->
            KitSettingsRow(
                title = modeName(mode),
                icon = modeIcon(mode),
                selected = mode == current,
                onClick = { onSelect(mode) },
                trailing = { Text("$shortcutPrefix${index + 1}", style = LocalCosmoType.current.meta, color = Color(palette.textTertiary)) },
            )
        }
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
