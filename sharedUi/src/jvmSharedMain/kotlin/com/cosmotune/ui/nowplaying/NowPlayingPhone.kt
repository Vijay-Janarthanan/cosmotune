package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import com.cosmotune.ui.theme.cosmoPlayerGroundEdges
import com.cosmotune.ui.theme.cosmoAmbientGround
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/** What the phone's picture slot holds, which decides its size. */
internal enum class PhoneMedia {
    /** The song's video, Whole (its own shape in the middle, its glow round it) or Fill: the screen's width, all the room. */
    VIDEO,
    /** The square cover (Off, or a song without a video): the screen's width, as tall as it is wide, at most the room. */
    COVER,
    /** The panel a chip opened (the lyrics, Up next, Related): inside the page's margins, all the room. */
    PANEL,
}

/**
 * The phone held upright (the Now Playing board, section B): the top row (close, "FROM · source", more), then the [media] across
 * the top (the video, the square cover, or the panel a chip opened), and the song [card] with Home's [chips] anchored at the foot,
 * in the same place whatever the media is. How large the media is depends on [mediaKind] ([PhoneMedia]). The video and the cover
 * run the full width of the screen and fade into the page at the top (under the top row) and at the bottom (above the card)
 * instead of ending in a straight line. Everything keeps clear of the status bar (its stable inset, so hiding it moves nothing),
 * the navigation bar and the cutout.
 */
@Composable
internal fun NowPlayingPhone(
    hazeState: HazeState,
    mediaKind: PhoneMedia,
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
            MediaOverCard(mediaKind, Modifier.weight(1f).fillMaxWidth(), media, card, chips)
        }
    }
}

/**
 * The media at the top, the card and the chips anchored at the foot (the redesign's "picture at the top, controls at the bottom"):
 * the card and the chips are measured first and placed at the bottom, so they stand in the same place whatever the media is; the
 * media starts right under the top row. The video takes all the room above the card (Whole sits in the middle of it with its glow
 * round it, Fill is cut to it); the cover is a square of the screen's width at the top of the room, cut at the room's foot on a
 * short screen. Both run past the page's side margins to the screen's edges, and over them the page's ground fades in at the top
 * and the bottom ([cosmoPlayerGroundEdges]). The panel keeps the margins and has no fade.
 */
@Composable
private fun MediaOverCard(
    kind: PhoneMedia,
    modifier: Modifier,
    media: @Composable (Modifier) -> Unit,
    card: @Composable (Modifier) -> Unit,
    chips: @Composable (Modifier) -> Unit,
) {
    val bleed = kind != PhoneMedia.PANEL
    Layout(
        content = {
            Box { media(Modifier.fillMaxSize()) }
            // On a tablet held upright the card keeps a phone's measure, centred, instead of stretching its few controls across.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { card(Modifier.widthIn(max = CARD_MAX_WIDTH).fillMaxWidth()) }
            Box { chips(Modifier.fillMaxWidth().padding(bottom = 18.dp)) }
            if (bleed) Box(Modifier.testTag("nowplaying:soft-edges").cosmoPlayerGroundEdges(top = SOFT_TOP, bottom = SOFT_BOTTOM))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val side = PHONE_SIDE.roundToPx()
        val cardPlaced = measurables[1].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
        val chipsPlaced = measurables[2].measure(Constraints(minWidth = width, maxWidth = width))
        val gap = PHONE_GAP.roundToPx()
        val chipsGap = PHONE_CHIPS_GAP.roundToPx()
        val room = (constraints.maxHeight - cardPlaced.height - gap - chipsGap - chipsPlaced.height).coerceAtLeast(0)
        val mediaWidth = if (bleed) width + 2 * side else width
        val height = when (kind) {
            PhoneMedia.COVER -> mediaWidth.coerceAtMost(room)
            PhoneMedia.VIDEO, PhoneMedia.PANEL -> room
        }
        val mediaPlaced = measurables[0].measure(Constraints.fixed(mediaWidth, height))
        val edgesPlaced = measurables.getOrNull(3)?.measure(Constraints.fixed(mediaWidth, height))
        val cardTop = constraints.maxHeight - chipsPlaced.height - chipsGap - cardPlaced.height
        layout(width, constraints.maxHeight) {
            val x = if (bleed) -side else 0
            mediaPlaced.place(x, 0)
            edgesPlaced?.place(x, 0)
            cardPlaced.place(0, cardTop)
            chipsPlaced.place(0, cardTop + cardPlaced.height + chipsGap)
        }
    }
}

/** How far the page's ground reaches into the picture: under the top row, and above the card. */
internal val SOFT_TOP = 28.dp
internal val SOFT_BOTTOM = 72.dp

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
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** The widest the phone layout's card grows (a tablet held upright). */
private val CARD_MAX_WIDTH = 600.dp
