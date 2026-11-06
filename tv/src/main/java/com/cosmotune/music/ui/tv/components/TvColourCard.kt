package com.cosmotune.music.ui.tv.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.tv.theme.TvType
import com.cosmotune.music.ui.tv.theme.tvOrbitLook
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.coverFill

/**
 * The Orbit colour card made for the sofa (board batch-be section E): flat in the cover's own deep colour, the mono label over the
 * title and the artist on the left, the cover as a disc on the right clear of the bite, and play in the bite at the bottom-end
 * corner ([OrbitBiteCard], [OrbitPlayButton]). The card and its play button are the two things on it that take focus: select on the
 * card is [onClick] (open what it shows), select on play is [onPlay].
 *
 * [progress] (read in draw) and [spinning] drive the orbit round the disc; [discContent] stands in for a cover (a station's call
 * letters); [below] goes under the artist (a blurb, a row of buttons).
 */
@Composable
fun TvColourCard(
    cover: String?,
    key: String,
    label: String,
    title: String,
    subtitle: String?,
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    onClick: (() -> Unit)? = null,
    progress: (() -> Float?)? = null,
    spinning: Boolean = false,
    ring: Boolean = true,
    playFocus: FocusRequester? = null,
    playEnabled: Boolean = true,
    discContent: (@Composable BoxScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val (fill, ink) = coverFill(cover, key)
    val look = tvOrbitLook()
    val disc = (height - 40.dp).coerceAtMost(170.dp)
    OrbitBiteCard(
        fill = fill,
        modifier = modifier.fillMaxWidth().height(height),
        onClick = onClick,
        bite = {
            OrbitPlayButton(
                playing = playing,
                onClick = { if (playEnabled) onPlay() },
                look = look,
                modifier = Modifier
                    .then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                    .tvFocusRing(CircleShape, gap = 2.dp),
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 118.dp).size(disc + 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (discContent != null) {
                    Box(Modifier.size(disc), contentAlignment = Alignment.Center, content = discContent)
                } else {
                    Disc(cover, disc)
                }
                if (ring) OrbitRing(disc + 14.dp, ink, progress = progress, spinning = spinning, stroke = 2.dp, moon = 5.dp, moonColor = look.accent)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 118.dp + disc + 40.dp),
            ) {
                Text(label.uppercase(), style = TvType.Label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    title,
                    style = TvType.LargeTitle.copy(fontSize = if (title.length > 26) 28.sp else 36.sp, lineHeight = if (title.length > 26) 30.sp else 38.sp),
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = TvType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight(600)),
                        color = ink.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                below?.invoke(this)
            }
        }
    }
}
