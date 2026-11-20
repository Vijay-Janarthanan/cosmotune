package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons

/*
 * Select several (the Music board's 7b): a hold on a row, or Select, turns each row's disc into a check; [OrbitSelectionHead] says
 * how many are chosen with a close button and "Select all", and [OrbitSelectionBar] at the foot holds what can be done with them.
 * The queue, an album, a playlist and the library use the same pieces.
 */

/** One action of the selection bar: its icon, its name under it, and what it does; [enabled] false dims it (nothing chosen yet). */
@Immutable
class OrbitBarAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The selection bar: 72 dp, corners of 30, the inverse ground (the page's ink) with the actions spread along it, each its icon over
 * its name in the page's ground colour.
 */
@Composable
fun OrbitSelectionBar(actions: List<OrbitBarAction>, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().height(72.dp).shadow(16.dp, BAR_SHAPE, clip = false).clip(BAR_SHAPE).background(look.ink).testTag("orbit:selection-bar"),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            Column(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
                    .alpha(if (action.enabled) 1f else 0.45f).padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(action.icon, contentDescription = null, tint = look.ground, modifier = Modifier.size(22.dp))
                Text(
                    action.label, style = fonts.chip.copy(fontSize = 11.5.sp, fontWeight = FontWeight(700)), color = look.ground, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val BAR_SHAPE = RoundedCornerShape(30.dp)

/** The head of select mode: a round close button, "3 selected" in the wide face, and "Select all" as a chip at its end. */
@Composable
fun OrbitSelectionHead(count: Int, onClose: () -> Unit, onSelectAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OrbitRoundButton(CosmoIcons.Close, "Done", look.ink, onClose, onInk = look.ground, touch = 48.dp)
        Text(if (count == 1) "1 selected" else "$count selected", style = fonts.section, color = look.ink, maxLines = 1, modifier = Modifier.weight(1f))
        if (onSelectAll != null) OrbitChip("Select all", fonts, look, onSelectAll, leading = null)
    }
}

/**
 * A flat switch row (the Music board's 4d, "Keep downloaded"): a 48 dp round tile with [icon] in the accent, the [title] over its
 * [line], and the switch at the end; a tap anywhere on the row turns it.
 */
@Composable
fun OrbitSwitchRow(icon: ImageVector, title: String, line: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = look.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title, color = look.ink, maxLines = 1)
            Text(line, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OrbitSwitch(checked, onChange, look, title, Modifier.padding(end = 4.dp))
    }
}
