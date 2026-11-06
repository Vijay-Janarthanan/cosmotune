package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable

/**
 * The Orbit song row, the only one (Orbit rule 8): [height] tall, radius 22, ink at 5 %; a disc of [cover] at the start (44 dp in a
 * 52 dp box), the title over the subtitle, and [trailing] at the end (a button, a handle) after [trailingText] (a duration). The
 * [now] row (the song that plays) has the peach gradient, a peach title and the orbit round its disc, which moves with [progress]
 * when given and circles slowly while [playing] otherwise.
 *
 * [discSize] is the disc (its box and orbit are 8 dp wider), [gap] the space after it; [radius] rounds the row, and [plain] takes away its
 * fill and its peach wash (a ranking's rows, whose rank goes in [leading]).
 *
 * [leading] goes before the disc (a drag handle). [discContent] replaces the disc (a selection mark, a spinner, a logo) and with it
 * the orbit. [icon] stands in for a cover (an artist, an album). [titleContent] replaces the title line (an explicit mark) and
 * [subtitleLeading] goes before the subtitle (a quality pill). A hold, or a right click on the desktop, is [onLongClick]; a null
 * [onClick] with no [onLongClick] makes the row inert, and [enabled] false does the same. [endPadding] is the row's end gutter (16 dp;
 * the library's rows end in a button and give 6 dp).
 */
@Composable
internal fun OrbitSongRow(
    title: String,
    subtitle: String,
    cover: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    playing: Boolean = false,
    progress: (() -> Float?)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    height: Dp = 68.dp,
    icon: ImageVector? = null,
    endPadding: Dp = 16.dp,
    discSize: Dp = 44.dp,
    gap: Dp = 12.dp,
    radius: Dp = 22.dp,
    plain: Boolean = false,
    titleContent: (@Composable () -> Unit)? = null,
    subtitleLeading: (@Composable () -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    discContent: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(radius))
            .then(if (plain) Modifier else Modifier.background(if (now) Brush.horizontalGradient(listOf(look.accent.copy(alpha = 0.28f), look.row)) else Brush.linearGradient(listOf(look.row, look.row))))
            .then(
                if (onClick != null || onLongClick != null) Modifier.longPressMenuClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick, enabled = enabled)
                else Modifier,
            )
            .contextClick(onLongClick.takeIf { enabled })
            .padding(start = if (leading != null) 4.dp else 10.dp, end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Box(Modifier.size(discSize + 8.dp), contentAlignment = Alignment.Center) {
            when {
                discContent != null -> discContent()
                icon != null -> {
                    Box(Modifier.size(discSize).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = look.ink, modifier = Modifier.size(20.dp))
                    }
                    if (now) OrbitRing(discSize + 8.dp, look.accent, progress = progress, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
                }
                else -> {
                    Disc(cover, discSize)
                    if (now) OrbitRing(discSize + 8.dp, look.accent, progress = progress, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
                }
            }
        }
        Spacer(Modifier.width(gap))
        Column(Modifier.weight(1f)) {
            if (titleContent != null) titleContent()
            else Text(title, style = fonts.title, color = if (now) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitleLeading != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subtitleLeading()
                    if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailingText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
        trailing?.invoke(this)
    }
}

/** A duration at a row's end, in the mono label face. */
@Composable
internal fun RowScope.OrbitRowTime(text: String?, fonts: OrbitFonts, look: OrbitLook) {
    if (text != null) Text(text, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp))
}
