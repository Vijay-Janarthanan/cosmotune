package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing

/*
 * The few Orbit pieces the video pages without a kit piece of their own share (the library's import and local pages, the
 * description and the channel's about): a mono label over a group, an Orbit row, and the 40 dp round action.
 */

/** The mono, upper-case label over a group of rows, in the page's margin. */
@Composable
internal fun OrbitGroupLabel(text: String, modifier: Modifier = Modifier) {
    OrbitLabel(text, orbitFonts(), orbitLook().dim, modifier.padding(start = CosmoSpacing.margin + 4.dp, end = CosmoSpacing.margin, top = 14.dp, bottom = 8.dp))
}

/** An Orbit row (62 dp, radius 22) with [icon] in a disc, or [cover] when there is one, [title] over [subtitle]. */
@Composable
internal fun OrbitInfoRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    cover: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    OrbitSongRow(
        title = title,
        subtitle = subtitle.orEmpty(),
        cover = cover,
        fonts = orbitFonts(),
        look = orbitLook(),
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
        height = 62.dp,
        icon = if (cover == null) icon else null,
        endPadding = if (trailing != null) 6.dp else 16.dp,
        trailing = trailing,
    )
}

/** The Orbit round button in the page's ink. */
@Composable
internal fun OrbitAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    OrbitRoundButton(icon, label, orbitLook().ink, onClick, modifier, size)
}
