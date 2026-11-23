package com.cosmotune.video.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitTooltip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.MetaLine
import com.cosmotune.video.ui.blocks.rememberNowMs
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_home_up_next_for_you
import com.cosmotune.video.resources.video_play
import com.cosmotune.video.resources.video_save_to_playlist
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.ui.blocks.ChannelAvatar
import org.jetbrains.compose.resources.stringResource

/**
 * Home's first card (board frame 1, "Up next for you"): the first video of the feed in one card on the muted Orbit card ground (the
 * ink at 8 %), never a saturated fill. The picture ([HeroArt], filling its 16:9 slot) stands beside the words from 600 dp and above
 * them on a phone. The words: the mono line ("UP NEXT FOR YOU · 18:42"), the two-line title, 8 dp under it the channel's disc with its
 * name and the views and age, up to two lines of the description when the feed has one, then the card's actions: Play (the main
 * button), Watch later ([inWatchLater] lights it) and Save to a playlist, each named in a tooltip, 8 dp apart. A tap on the card
 * plays too; the channel opens with [onChannel]. The card is as tall as the taller of the picture and the words, and on a wide page
 * as wide as they need ([HERO_MAX_WIDE]), not the whole row.
 */
@Composable
internal fun HomeUpNextCard(
    item: VideoItem,
    title: String,
    onPlay: () -> Unit,
    onChannel: (() -> Unit)?,
    onWatchLater: () -> Unit,
    onSave: () -> Unit,
    inWatchLater: Boolean,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val shape = RoundedCornerShape(28.dp)
        val card = (if (wide) Modifier.wrapContentWidth(Alignment.Start).widthIn(max = HERO_MAX_WIDE) else Modifier).fillMaxWidth()
            .clip(shape).background(look.chip).clickable(onClick = onPlay).padding(14.dp).testTag("video:home:upnext")
        if (wide) {
            Row(card, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                HeroArt(item, Modifier.width(320.dp).aspectRatio(16f / 9f), RoundedCornerShape(16.dp))
                Column(Modifier.weight(1f)) { HeroWords(item, title, wide = true, fonts, look, onPlay, onChannel, onWatchLater, onSave, inWatchLater) }
            }
        } else {
            Column(card) {
                HeroArt(item, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                HeroWords(item, title, wide = false, fonts, look, onPlay, onChannel, onWatchLater, onSave, inWatchLater)
            }
        }
    }
}

/** The card's words and actions, top to bottom, with nothing standing empty between them. */
@Composable
private fun ColumnScope.HeroWords(
    item: VideoItem,
    title: String,
    wide: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    onPlay: () -> Unit,
    onChannel: (() -> Unit)?,
    onWatchLater: () -> Unit,
    onSave: () -> Unit,
    inWatchLater: Boolean,
) {
    val label = listOfNotNull(
        stringResource(Res.string.video_home_up_next_for_you),
        item.durationSec?.takeIf { it > 0 && !item.isLive }?.let { VideoText.duration(it) },
    ).joinToString(" · ").uppercase()
    // "167K views · 2 days ago" under the channel's name.
    val meta = MetaLine(item, rememberNowMs(), showChannel = false)
    Text(label, style = fonts.label, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        title, style = fonts.heroTitle.copy(fontSize = if (wide) 22.sp else 18.sp, lineHeight = if (wide) 26.sp else 22.sp), color = look.ink,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
    val name = item.channelName?.takeIf { it.isNotBlank() }
    if (name != null) {
        Row(
            // The pressed shape's round ends must not cut the words: they stand 14 dp inside its end.
            Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier)
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelAvatar(item.channelAvatarUrl, 30.dp, name = name)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) Text(meta, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else if (meta.isNotEmpty()) {
        Text(meta, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
    // The description, when the feed has one: two lines at most, left out otherwise.
    item.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
        Text(snippet, style = fonts.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
    }
    val play = stringResource(Res.string.video_play)
    val later = stringResource(Res.string.video_watch_later)
    val save = stringResource(Res.string.video_save_to_playlist)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitTooltip(play) { OrbitPillButton(play, onPlay, fonts, look, primary = true, icon = CosmoIcons.Play, height = 40.dp) }
        OrbitRoundButton(CosmoIcons.Clock, later, look.ink, onWatchLater, on = inWatchLater, toggled = inWatchLater)
        OrbitRoundButton(VideoIcons.PlaylistAdd, save, look.ink, onSave)
    }
}

/** The widest the card grows on a wide page: the 320 dp picture and the words and actions beside it. */
internal val HERO_MAX_WIDE = 760.dp
