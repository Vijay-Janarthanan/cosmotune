package com.cosmotune.ui.orbit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text

/*
 * The phone's own sheets and small pages (download manager, playlist picker, reorder, equalizer presets, Spotify setup): the few
 * public pieces they share, drawn from the internal Orbit pieces so the app module does not draw its own button or heading.
 */

/** A sheet's head: the title in wide type, the [subtitle] in the dim body face under it, and [trailing] (a chip or a round button) at the end. */
@Composable
fun OrbitSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.section, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** A sheet's or a page's button: the 48 dp pill, [primary] the one peach action, the others the quiet chip. */
@Composable
fun OrbitSheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    OrbitPillButton(label, onClick, orbitFonts(), orbitLook(), modifier, primary = primary, enabled = enabled, icon = icon, height = height)
}

/** A sheet's chip with no icon (Cancel all, Clear, Use this profile): the 40 dp chip; [primary] makes it the solid one. */
@Composable
fun OrbitSheetAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    OrbitPillButton(label, onClick, orbitFonts(), orbitLook(), modifier, primary = primary, enabled = enabled, height = 40.dp)
}
