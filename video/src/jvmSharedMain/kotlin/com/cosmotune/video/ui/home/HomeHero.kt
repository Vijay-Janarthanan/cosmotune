package com.cosmotune.video.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.ui.blocks.MetaLine
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_home_up_next_for_you
import com.cosmotune.video.ui.blocks.ChannelAvatar
import org.jetbrains.compose.resources.stringResource

/**
 * Home's first card (board frame 1, "Up next for you"): the first video of the feed in one slim card on the muted Orbit card ground
 * (the ink at 8 %, as a form card raised), never a saturated fill. The picture ([HeroArt], filling its 16:9 slot) stands beside the
 * mono line ("UP NEXT FOR YOU · 18:42") and a two-line title; the channel's disc, its name and the views and age sit at the foot.
 * Play stands in the bite and is the card's one action ([onPlay], as is a tap on the card); Watch later and Watch together are in
 * the video's sheet, which a hold opens. The channel opens with [onChannel]. From 600 dp the picture is larger and the card is as
 * wide as its picture and words need ([HERO_MAX_WIDE]), not the whole row.
 */
@Composable
internal fun HomeUpNextCard(
    item: VideoItem,
    title: String,
    onPlay: () -> Unit,
    onChannel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val fill = look.chip
    val ink = look.ink
    val label = listOfNotNull(
        stringResource(Res.string.video_home_up_next_for_you),
        item.durationSec?.takeIf { it > 0 && !item.isLive }?.let { VideoText.duration(it) },
    ).joinToString(" · ").uppercase()
    // "167K views · 2 days ago" under the channel's name.
    val meta = MetaLine(item, rememberNowMs(), showChannel = false)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        OrbitBiteCard(fill, (if (wide) Modifier.wrapContentWidth(Alignment.Start).widthIn(max = HERO_MAX_WIDE) else Modifier).fillMaxWidth().testTag("video:home:upnext"), onClick = onPlay, bite = { OrbitPlayButton(false, onPlay, look) }) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 16.dp)) {
                // The words start level with the picture's top, so a two-line title ends well above Play's bite in the corner.
                Row(verticalAlignment = Alignment.Top) {
                    HeroArt(item, Modifier.width(if (wide) 320.dp else 150.dp).aspectRatio(16f / 9f), RoundedCornerShape(14.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(label, style = fonts.label, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            title, style = fonts.heroTitle.copy(fontSize = if (wide) 22.sp else 17.sp, lineHeight = if (wide) 26.sp else 21.sp), color = ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                        )
                    }
                }
                val name = item.channelName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    Row(
                        // Ends 16 dp short of Play's ring (its 41 dp radius about 46 dp in from the corner), so the name never runs into it.
                        // The pressed shape's round ends must not cut the words: they stand 14 dp inside its end.
                        Modifier.padding(top = 12.dp, end = 90.dp).clip(RoundedCornerShape(20.dp)).then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier)
                            .padding(end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(item.channelAvatarUrl, 30.dp, name = name)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(name, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (meta.isNotEmpty()) Text(meta, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The widest the card grows on a wide page: the 320 dp picture, two lines of title beside it and the bite's corner. */
internal val HERO_MAX_WIDE = 760.dp
