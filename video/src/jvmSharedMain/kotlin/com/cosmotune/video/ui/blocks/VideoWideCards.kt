package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.WatchProgressLine
import com.cosmotune.ui.orbit.liveRed
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource

/*
 * The big card of the wide pages, in the Orbit row's look (radius 26, ink at 5 %): the result card of Search. Like every card it is one button (`videoClickable`: a tap, a hold, a
 * right click, the focus ring), answers a mouse the way the grid's cards do, and takes the numbers it shares with them from VideoMetrics.
 */

/** The result card: the picture 360 dp wide (16:9), 16 dp from the words. */
private val RESULT_PICTURE_W = 360.dp
private val RESULT_GAP = 16.dp
private val RESULT_AVATAR = 24.dp
private const val RESULT_SNIPPET_LINES = 2

/**
 * A search result on a wide page, laid out as the video apps lay it out: the picture 360 x 202.5 at the start; at its side the title
 * in 18 sp (two lines), the meta line ("1.2M views · 3 days ago"), the channel with its 24 dp picture, a snippet of the description
 * when the item has one (two lines), and the more button at the end of the title line. One button for assistive technology; a tap
 * calls [onClick], the more button, a hold and a right click call [onMore]. [watchedFraction] draws the watched bar, [title] stands in
 * for the item's (a DeArrow title), [downloaded] marks a downloaded video.
 */
@Composable
fun VideoResultCard(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    title: String = item.title,
    downloaded: Boolean = false,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val meta = CardMetaLine(item, rememberNowMs(), showChannel = false, downloaded = downloaded)
    val snippet = item.snippet?.takeIf { it.isNotBlank() }
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    PreResolveOnHover(hover, item)
    Row(
        with(hover) { modifier.fillMaxWidth().track() }
            .padding(horizontal = CosmoSpacing.margin, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(look.row)
            .videoClickable(26.dp, onClick, onMore)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(RESULT_PICTURE_W)) {
            ThumbnailFrame(
                url = item.thumbnailUrl,
                modifier = with(hover) { Modifier.fillMaxWidth().aspectRatio(16f / 9f).lifted() },
                shape = RoundedCornerShape(20.dp),
                badge = badgeText(item),
                badgeLive = item.isLive,
            )
            if (watchedFraction != null) WatchProgressLine(watchedFraction, look, Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.width(RESULT_GAP))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(title, style = fonts.title.copy(fontSize = 17.sp, lineHeight = 22.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = buildAnnotatedString {
                if (item.isLive) {
                    withStyle(SpanStyle(color = liveRed(look), fontFamily = fonts.label.fontFamily, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)) { append("● LIVE") }
                    if (meta.isNotBlank()) append(" · ")
                }
                append(meta)
            }
            if (line.isNotEmpty()) Text(line, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (!item.channelName.isNullOrBlank()) {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(item.channelAvatarUrl, RESULT_AVATAR, name = item.channelName)
                    Spacer(Modifier.width(8.dp))
                    Text(item.channelName.orEmpty(), style = fonts.body.copy(fontWeight = FontWeight(600)), color = look.ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (snippet != null) {
                Text(snippet, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = RESULT_SNIPPET_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
        }
        OrbitRoundButton(CosmoIcons.MoreVertical, more, look.ink, onMore, size = 40.dp)
    }
}
