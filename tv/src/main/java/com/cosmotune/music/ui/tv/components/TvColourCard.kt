package com.cosmotune.music.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.tv.theme.TvType
import com.cosmotune.music.ui.tv.theme.tvOrbitLook
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.BiteShape
import com.cosmotune.ui.orbit.HeroBite
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.coverFill

/**
 * The Orbit colour card made for the sofa (board batch-be section E): flat in the cover's own deep colour, the mono label over the
 * title and the artist on the left, the cover as a disc on the right clear of the bite, and play in the bite at the bottom-end
 * corner ([HeroBite], [OrbitPlayButton]). The card and its play button are the two things on it that take focus: select on the
 * card is [onClick] (open what it shows), select on play is [onPlay]. Focus on the card is the peach ring round its bitten outline;
 * Right from the card goes to play (it sits inside the card's bounds, so a plain search would never find it) and Left from play
 * comes back.
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
    val cardFocus = remember { FocusRequester() }
    val play = playFocus ?: remember { FocusRequester() }
    Box(modifier.fillMaxWidth().height(height), propagateMinConstraints = true) {
        Box(
            Modifier
                .then(
                    if (onClick != null) {
                        Modifier
                            .focusRequester(cardFocus)
                            .focusProperties { right = play }
                            .tvFocusRing(HeroBiteRing)
                            .clip(HeroBite)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    } else Modifier.clip(HeroBite),
                )
                .background(fill),
        ) {
            TvColourCardContent(cover, disc, ink, look.accent, ring, progress, spinning, discContent, label, title, subtitle, below)
        }
        Box(Modifier.align(Alignment.BottomEnd).wrapContentSize(Alignment.BottomEnd).padding(13.dp)) {
            OrbitPlayButton(
                playing = playing,
                onClick = { if (playEnabled) onPlay() },
                look = look,
                modifier = Modifier
                    .focusRequester(play)
                    .then(if (onClick != null) Modifier.focusProperties { left = cardFocus } else Modifier)
                    .tvFocusRing(CircleShape, gap = 2.dp),
            )
        }
    }
}

/**
 * The card's outline as the focus ring draws it: grown by the ring's reach ([RingGap] and half its width) the corners round
 * more and the bite round play shrinks about the same centre, so the ring keeps an even distance from the card and from play.
 */
private val HeroBiteRing = (RingGap + RingWidth / 2).let { reach -> BiteShape(corner = 34.dp + reach, bite = 41.dp - reach, inset = 46.dp + reach) }

@Composable
private fun TvColourCardContent(
    cover: String?,
    disc: Dp,
    ink: androidx.compose.ui.graphics.Color,
    moon: androidx.compose.ui.graphics.Color,
    ring: Boolean,
    progress: (() -> Float?)?,
    spinning: Boolean,
    discContent: (@Composable BoxScope.() -> Unit)?,
    label: String,
    title: String,
    subtitle: String?,
    below: (@Composable ColumnScope.() -> Unit)?,
) {
    run {
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
                if (ring) OrbitRing(disc + 14.dp, ink, progress = progress, spinning = spinning, stroke = 2.dp, moon = 5.dp, moonColor = moon)
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
