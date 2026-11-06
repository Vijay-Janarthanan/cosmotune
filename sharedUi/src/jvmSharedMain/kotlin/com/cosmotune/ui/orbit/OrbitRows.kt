package com.cosmotune.ui.orbit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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

/**
 * Home's song row (Orbit rule 8): [height] tall, radius 22, ink at 5 %; a disc of [cover] at the start (44 dp in a 52 dp box), the
 * title over the subtitle, and [trailing] at the end (a duration, a button). The [now] row (the song that plays) has the peach
 * gradient, a peach title and the orbit round its disc, which moves with [progress] when given and circles slowly while
 * [playing] otherwise. [leading] goes before the disc (a drag handle). [icon] stands in for a cover (an artist, an album).
 */
@OptIn(ExperimentalFoundationApi::class)
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
    height: Dp = 68.dp,
    icon: ImageVector? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(22.dp))
            .background(if (now) Brush.horizontalGradient(listOf(look.accent.copy(alpha = 0.28f), look.row)) else Brush.linearGradient(listOf(look.row, look.row)))
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier)
            .padding(start = if (leading != null) 4.dp else 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = look.ink, modifier = Modifier.size(20.dp))
                }
            } else {
                Disc(cover, 44.dp)
            }
            if (now) OrbitRing(52.dp, look.accent, progress = progress, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title, color = if (now) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

/** A duration at a row's end, in the mono label face. */
@Composable
internal fun RowScope.OrbitRowTime(text: String?, fonts: OrbitFonts, look: OrbitLook) {
    if (text != null) Text(text, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp))
}
