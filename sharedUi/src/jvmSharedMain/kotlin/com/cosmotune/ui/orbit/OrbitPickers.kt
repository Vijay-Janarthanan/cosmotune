package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.sheets.SurfaceSheetSwitch

/*
 * The Orbit pickers: a sheet that asks for one choice (the mode, an audio quality, a download quality, a room's door), its title and
 * its rows. The sheet is the song sheet's (SurfaceSheetSwitch on CosmoSheetSurface: flat, its own ground, the grabber); a row is the
 * kit's row (radius 22, the ink at 5 %), the chosen one solid ink with the ground's colour for its words and a check in that colour.
 */

/** A picker sheet: opened whole (never half way), the Orbit sheet surface, [content] scrolling inside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitPickerSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
}

/**
 * A sheet's head: the mono [label] over the [title] in the wide face, [subtitle] in the dim ink under it, with an optional [leading]
 * (a ring, a disc) and [trailing] (a chip or a round button) beside it. A heading for assistive technology.
 */
@Composable
fun OrbitSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            if (label != null) OrbitLabel(label, fonts, look.dim, Modifier.padding(bottom = 6.dp))
            Text(title, style = fonts.heroTitle.copy(fontSize = 21.sp, lineHeight = 24.sp), color = look.ink, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 6.dp))
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

/**
 * One choice of a picker: at least 56 dp, radius 22, [icon] (when given) and the [title] over its [detail]; [selected] is solid ink
 * with the ground's colour for its words and a check in that colour at its end. A radio button for assistive technology; [enabled]
 * false dims it to 45 % and stops the tap. [trailing] is a short word at the end ("Ctrl+1").
 */
@Composable
fun OrbitPickRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val interaction = remember { MutableInteractionSource() }
    val ink = if (selected) look.ground else look.ink
    val dim = if (selected) look.ground.copy(alpha = 0.72f) else look.dim
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 56.dp)
            .cosmoFocusRing(interaction, 22.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) look.ink else look.row)
            .selectable(selected = selected, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = if (icon != null) 14.dp else 18.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title.copy(fontSize = 14.5.sp), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = fonts.label, color = dim, modifier = Modifier.padding(start = 10.dp))
        // A check in the row's own ink, not peach: peach is "playing now" alone.
        if (selected) Icon(com.cosmotune.ui.components.CosmoIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.padding(start = 10.dp).size(20.dp))
    }
}

/** An action of a sheet (not a choice): a 48 dp round row, [icon] in the dim ink and the [label]. One button for assistive technology. */
@Composable
fun OrbitSheetAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 48.dp)
            .cosmoFocusRing(interaction, 24.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = look.dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = fonts.chip, color = look.ink)
    }
}
