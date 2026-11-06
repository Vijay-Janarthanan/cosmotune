package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.cosmoAmbientGround
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** What the phone's media slot holds, which decides how tall it is. */
internal enum class PhoneMedia {
    /** The video shown whole: as tall as the page's width gives its shape, never more than the room. */
    WHOLE,
    /** The video cut to fill, or a panel: all the room above the card. */
    ROOM,
    /** The record: a square of the page's width at most. */
    RECORD,
}

/**
 * The phone held upright (the Orbit board, section 5): the top row (close, "FROM · source", more), then the [media] across the
 * top (the whole video, the video cut to fill, the record, or the panel a chip opened), the song [card] right under it with play
 * in its bite, and Home's chips at the foot. How tall the media is depends on [mediaKind] ([PhoneMedia]); [aspect] is the
 * video's shape for [PhoneMedia.WHOLE]. The card is laid out first, so the media takes what it leaves. Everything keeps clear of
 * the status bar (its stable inset, so hiding it moves nothing), the navigation bar and the cutout.
 */
@Composable
internal fun NowPlayingPhone(
    hazeState: HazeState,
    mediaKind: PhoneMedia,
    aspect: Float,
    topRow: @Composable (Modifier) -> Unit,
    media: @Composable (Modifier) -> Unit,
    card: @Composable (Modifier) -> Unit,
    chips: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().hazeSource(hazeState)) {
        Box(Modifier.fillMaxSize().cosmoAmbientGround(artwork = true))
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(com.cosmotune.music.ui.theme.stableStatusBarInsets())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = PHONE_SIDE),
        ) {
            com.cosmotune.ui.shell.WindowCaptionArea(Modifier.fillMaxWidth()) { topRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)) }
            MediaOverCard(mediaKind, aspect, Modifier.weight(1f).fillMaxWidth(), media, card)
            Spacer(Modifier.height(14.dp))
            chips(Modifier.fillMaxWidth().padding(bottom = 18.dp))
        }
    }
}

/** The media and, [PHONE_GAP] under it, the card; the card is measured first and the media gets the rest as [kind] says. */
@Composable
private fun MediaOverCard(kind: PhoneMedia, aspect: Float, modifier: Modifier, media: @Composable (Modifier) -> Unit, card: @Composable (Modifier) -> Unit) {
    Layout(content = { Box { media(Modifier.fillMaxSize()) }; Box { card(Modifier.fillMaxWidth()) } }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val cardPlaced = measurables[1].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
        val gap = PHONE_GAP.roundToPx()
        val room = (constraints.maxHeight - cardPlaced.height - gap).coerceAtLeast(0)
        val wanted = when (kind) {
            PhoneMedia.WHOLE -> (width / (if (aspect > 0f) aspect else com.cosmotune.design.VideoView.DEFAULT_ASPECT)).toInt()
            PhoneMedia.ROOM -> room
            PhoneMedia.RECORD -> (width * 0.9f).toInt()
        }
        val height = wanted.coerceIn(0, room)
        val mediaPlaced = measurables[0].measure(Constraints.fixed(width, height))
        layout(width, constraints.maxHeight) {
            mediaPlaced.place(0, 0)
            cardPlaced.place(0, height + gap)
        }
    }
}

/**
 * The phone on its side, without Cinema (Cinema is what turning the phone shows a video as; leaving it lands here): the media or
 * the open panel on the left, and on the right the top row, the card and the chips.
 */
@Composable
internal fun NowPlayingPhoneLandscape(
    hazeState: HazeState,
    topRow: @Composable (Modifier) -> Unit,
    media: @Composable (Modifier) -> Unit,
    card: @Composable (Modifier) -> Unit,
    chips: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().hazeSource(hazeState)) {
        Box(Modifier.fillMaxSize().cosmoAmbientGround(artwork = true))
        Row(
            Modifier.fillMaxSize()
                .windowInsetsPadding(com.cosmotune.music.ui.theme.stableStatusBarInsets())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = PHONE_SIDE, vertical = 10.dp),
        ) {
            media(Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                topRow(Modifier.fillMaxWidth())
                card(Modifier.fillMaxWidth())
                chips(Modifier.fillMaxWidth())
            }
        }
    }
}

/** The phone's chips, centred under the card. */
@Composable
internal fun PhoneChips(panel: NowPlayingPanel, onPanel: (NowPlayingPanel) -> Unit, fonts: com.cosmotune.ui.orbit.OrbitFonts, look: com.cosmotune.ui.orbit.OrbitLook, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { PanelChips(panel, onPanel, fonts, look, closable = true, icons = false) }
}

private val PHONE_SIDE = 14.dp
private val PHONE_GAP = 18.dp
