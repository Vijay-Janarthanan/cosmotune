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
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.ui.blocks.MetaLine
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_home_up_next_for_you
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.badgeText
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import org.jetbrains.compose.resources.stringResource

/**
 * Home's colour card (board frame 1, "Up next for you"): the first video of the feed in one slim card, about 180 dp, in the colour
 * of its picture. The picture stands beside the mono line ("UP NEXT FOR YOU · 18:42") and a two-line title; the channel's disc,
 * its name and the views and age sit at the foot. Play stands in the bite and is the card's one action ([onPlay], as is a tap on
 * the card); Watch later and Watch together are in the video's sheet, which a hold opens. The channel opens with [onChannel].
 * From 600 dp the picture is larger and the title gets more room.
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
    val (fill, ink) = coverFill(item.thumbnailUrl, item.id)
    val label = listOfNotNull(
        stringResource(Res.string.video_home_up_next_for_you),
        item.durationSec?.takeIf { it > 0 && !item.isLive }?.let { VideoText.duration(it) },
    ).joinToString(" · ").uppercase()
    // "167K views · 2 days ago" under the channel's name.
    val meta = MetaLine(item, rememberNowMs(), showChannel = false)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        OrbitBiteCard(fill, Modifier.fillMaxWidth().testTag("video:home:upnext"), onClick = onPlay, bite = { OrbitPlayButton(false, onPlay, look) }) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 16.dp)) {
                // The words start level with the picture's top, so a two-line title ends well above Play's bite in the corner.
                Row(verticalAlignment = Alignment.Top) {
                    ThumbnailFrame(item.thumbnailUrl, Modifier.width(if (wide) 240.dp else 150.dp).aspectRatio(16f / 9f), RoundedCornerShape(14.dp),
                        badge = badgeText(item), badgeLive = item.isLive)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(label, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            title, style = fonts.heroTitle.copy(fontSize = if (wide) 22.sp else 17.sp, lineHeight = if (wide) 26.sp else 21.sp), color = ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                        )
                    }
                }
                val name = item.channelName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    Row(
                        Modifier.padding(top = 12.dp, end = 76.dp).clip(RoundedCornerShape(20.dp)).then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(item.channelAvatarUrl, 30.dp, name = name)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(name, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (meta.isNotEmpty()) Text(meta, style = fonts.body.copy(fontSize = 12.sp), color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
