package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.horizontalScroll
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
    recordDetails: @Composable () -> Unit = {},
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
            MediaOverCard(mediaKind, aspect, Modifier.weight(1f).fillMaxWidth(), media, card, chips, recordDetails)
        }
    }
}

/**
 * The media, [PHONE_GAP] under it the card, and [PHONE_CHIPS_GAP] under that the chips, as one block: the card and the chips are
 * measured first and the media gets the rest as [kind] says. A media that does not take all the room (the whole video, the record)
 * leaves some over; the block then stands a third of the way down it instead of the chips sinking to the foot, so the chips stay with
 * the card they belong to.
 */
@Composable
private fun MediaOverCard(
    kind: PhoneMedia,
    aspect: Float,
    modifier: Modifier,
    media: @Composable (Modifier) -> Unit,
    card: @Composable (Modifier) -> Unit,
    chips: @Composable (Modifier) -> Unit,
    recordDetails: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { media(Modifier.fillMaxSize()) }
            Box { card(Modifier.fillMaxWidth()) }
            Box { chips(Modifier.fillMaxWidth().padding(bottom = 18.dp)) }
            if (kind == PhoneMedia.RECORD) Box { recordDetails() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val cardPlaced = measurables[1].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
        val chipsPlaced = measurables[2].measure(Constraints(minWidth = width, maxWidth = width))
        val gap = PHONE_GAP.roundToPx()
        val chipsGap = PHONE_CHIPS_GAP.roundToPx()
        val room = (constraints.maxHeight - cardPlaced.height - gap - chipsGap - chipsPlaced.height).coerceAtLeast(0)
        val wanted = when (kind) {
            PhoneMedia.WHOLE -> (width / (if (aspect > 0f) aspect else com.cosmotune.design.VideoView.DEFAULT_ASPECT)).toInt()
            PhoneMedia.ROOM -> room
            PhoneMedia.RECORD -> (width * 0.9f).toInt()
        }
        val detailsGap = 8.dp.roundToPx()
        val details = measurables.getOrNull(3)?.measure(Constraints(maxWidth = width, maxHeight = room))
            ?.takeIf { it.height > 0 && room - wanted >= it.height + detailsGap }
        val extra = details?.let { it.height + detailsGap } ?: 0
        val height = wanted.coerceIn(0, room - extra)
        val mediaPlaced = measurables[0].measure(Constraints.fixed(width, height))
        val top = (room - height - extra) / 3
        val cardTop = top + height + gap + extra
        layout(width, constraints.maxHeight) {
            mediaPlaced.place(0, top)
            details?.placeRelative((width - details.width) / 2, top + height + detailsGap)
            cardPlaced.place(0, cardTop)
            chipsPlaced.place(0, cardTop + cardPlaced.height + chipsGap)
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

/**
 * The phone's chips, centred under the card: the panels, then [status], what is switched on (the sleep timer's pill, repeat,
 * shuffle and the rest) as solid chips. Wider than the phone, the row scrolls sideways.
 */
@Composable
internal fun PhoneChips(
    panel: NowPlayingPanel,
    onPanel: (NowPlayingPanel) -> Unit,
    fonts: com.cosmotune.ui.orbit.OrbitFonts,
    look: com.cosmotune.ui.orbit.OrbitLook,
    modifier: Modifier,
    status: @Composable () -> Unit = {},
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = PHONE_SIDE),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelChips(panel, onPanel, fonts, look, closable = true, icons = false)
            status()
        }
    }
}

private val PHONE_SIDE = 14.dp
private val PHONE_GAP = 18.dp
private val PHONE_CHIPS_GAP = 14.dp
