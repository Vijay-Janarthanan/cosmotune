package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.states.CosmoShimmer

/*
 * The compact page header (the shared board's A1, the Music board's 4a): the one colour card of a page, made short. A 120 dp cover
 * disc stands beside the title block (a mono eyebrow line, the 30 sp title in two lines at most, the subtitle), one row of round
 * actions runs under them, and Play stands in Orbit's bite in the card's bottom-end corner. 220 dp tall. When the page scrolls past
 * it, the heading collapses into the top bar ([OrbitCollapsedHeader]) and Play moves there. The same card, made smaller, is Home's
 * pick-up card (no actions, 160 dp) and Search's top result (an 80 dp disc, 120 dp).
 */

/** The three sizes of [OrbitPageHeader]. */
enum class OrbitHeaderSize(
    val height: Dp,
    val disc: Dp,
    val title: TextUnit,
    val titleLine: TextUnit,
    val corner: Dp,
    val play: Dp,
    /** The clear ring of the page's ground round the Play button. */
    val ring: Dp,
    /** From the card's bottom and end edges to the Play button. */
    val playInset: Dp,
) {
    /** An album, a playlist, an artist, a show, a channel: 220 dp, a 120 dp disc, the actions row, a 56 dp Play. */
    PAGE(220.dp, 120.dp, 30.sp, 31.sp, 32.dp, 56.dp, 7.dp, 13.dp),

    /** Home's "pick up where you left off": the page header without its actions, 160 dp. */
    PICK_UP(160.dp, 120.dp, 26.sp, 28.sp, 32.dp, 56.dp, 7.dp, 13.dp),

    /** Search's top result: an 80 dp disc and a 22 sp title, 120 dp, a 48 dp Play. */
    RESULT(120.dp, 80.dp, 22.sp, 25.sp, 28.dp, 48.dp, 6.dp, 12.dp);

    /** The card's outline: its corners and the bite round Play. */
    internal val shape: BiteShape get() = BiteShape(corner, play / 2 + ring, playInset + play / 2)
}

/** The card's inside: 20 dp on every side. */
private val HEADER_PADDING = 20.dp

/**
 * A page's compact header ([OrbitHeaderSize]): one flat card in [colors] (the cover's own colour by default), the cover as a disc
 * (or [lead]: an artist's portrait in its dashed ring, an animated cover), the mono [label], the [title] (or [titleContent], the
 * album's title art) in two lines at most, the [subtitle] (a tap is [onSubtitle]: the artist), and
 * [buttons] as one row of round actions ([OrbitCardButton]); Play ([onPlay], showing pause while [playing]) stands in the bite. [corner]
 * goes in the top-end corner (the top result's "Save"), [backdrop] under the words (a channel's banner). A tap on the card is [onClick], a hold [onLongClick].
 */
@Composable
fun OrbitPageHeader(
    cover: String?,
    label: String,
    title: String,
    subtitle: String?,
    playing: Boolean,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: OrbitHeaderSize = OrbitHeaderSize.PAGE,
    colors: OrbitCardColors = coverFill(cover, title).let { (fill, ink) -> OrbitCardColors(fill, ink) },
    onSubtitle: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    lead: (@Composable (OrbitCardColors) -> Unit)? = null,
    titleContent: (@Composable (OrbitCardColors) -> Unit)? = null,
    corner: (@Composable BoxScope.(OrbitCardColors) -> Unit)? = null,
    /** Drawn under the words, filling the card (a channel's banner faded into its colour). */
    backdrop: (@Composable BoxScope.(OrbitCardColors) -> Unit)? = null,
    buttons: (@Composable RowScope.(OrbitCardColors) -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val ink = colors.ink
    Box(modifier.height(size.height)) {
        Box(
            Modifier.fillMaxSize().clip(size.shape).background(colors.fill)
                .then(if (onClick != null || onLongClick != null) Modifier.longPressMenuClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick).contextClick(onLongClick) else Modifier),
        ) {
            if (backdrop != null) Box(Modifier.matchParentSize()) { backdrop(colors) }
            Column(Modifier.fillMaxSize().padding(HEADER_PADDING)) {
                Row(Modifier.fillMaxWidth().height(size.disc), verticalAlignment = Alignment.CenterVertically) {
                    if (lead != null) lead(colors) else Disc(cover, size.disc)
                    Spacer(Modifier.width(16.dp))
                    // Clear of the bite where the card has no actions row under the words (the pick-up card), and of the corner button.
                    val end = when {
                        corner != null -> 36.dp
                        size == OrbitHeaderSize.PICK_UP -> size.play + size.ring - 6.dp
                        else -> 0.dp
                    }
                    Column(Modifier.weight(1f).padding(end = end)) {
                        if (label.isNotBlank()) {
                            Text(label.uppercase(), style = fonts.label, color = ink.copy(alpha = 0.74f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(if (size == OrbitHeaderSize.RESULT) 4.dp else 8.dp))
                        }
                        if (titleContent != null) titleContent(colors)
                        else Text(
                            title, style = fonts.heroTitle.copy(fontSize = size.title, lineHeight = size.titleLine), color = ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle, style = fonts.body.copy(fontSize = 14.sp, fontWeight = FontWeight(700)), color = ink.copy(alpha = 0.82f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                                    .then(if (onSubtitle != null) Modifier.clickable(onClick = onSubtitle) else Modifier),
                            )
                        }
                    }
                }
                if (buttons != null && size == OrbitHeaderSize.PAGE) {
                    Spacer(Modifier.height(16.dp))
                    // The actions start the row; Play ends it in the bite, so the row stops clear of it.
                    Row(
                        Modifier.fillMaxWidth().padding(end = size.play + size.ring + 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { buttons(colors) }
                }
            }
            if (corner != null) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { corner(colors) }
        }
        if (onPlay != null) {
            OrbitPlayButton(
                playing, onPlay, look, Modifier.align(Alignment.BottomEnd).padding(size.playInset).testTag("orbit:header:play"),
                size = size.play,
            )
        }
    }
}

/**
 * A round action of a page header: 44 dp, the card's ink at 12 % (solid when [on]). [progress] (0..1) draws a peach ring round it, the
 * share done (the Download button: how much of the list is saved).
 */
@Composable
fun OrbitHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    colors: OrbitCardColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    progress: Float? = null,
) {
    if (progress == null) {
        OrbitRoundButton(icon, label, colors.ink, onClick, modifier, size = 44.dp, on = on, onInk = colors.fill, touch = 48.dp)
        return
    }
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        OrbitRoundButton(icon, label, colors.ink, onClick, size = 44.dp, on = on, onInk = colors.fill, touch = 48.dp)
        androidx.compose.foundation.Canvas(Modifier.size(50.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            drawArc(
                PEACH, -90f, 360f * progress.coerceIn(0f, 1f), false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

// ---- the heading collapsed into the top bar ----------------------------------------------------------------------------------

/**
 * What a page's header leaves in the top bar once it has scrolled away (the Music board's 4b): a small disc, the title and the
 * subtitle, Shuffle (when [onShuffle] is given) and the peach Play.
 */
@Stable
class OrbitCollapsedHeader(
    val cover: String?,
    val title: String,
    val subtitle: String?,
    val playing: Boolean,
    val onPlay: (() -> Unit)?,
    val onShuffle: (() -> Unit)?,
)

/**
 * Where a page hands its collapsed heading to the window's top bar: the phone's shell provides one ([LocalOrbitHeaderSlot]) and its
 * top bar draws [header] once the page is scrolled; a page with no slot (the desktop's) draws [OrbitCollapsedHeaderBar] itself.
 */
@Stable
class OrbitHeaderSlot {
    var header by mutableStateOf<OrbitCollapsedHeader?>(null)
        internal set
}

val LocalOrbitHeaderSlot = staticCompositionLocalOf<OrbitHeaderSlot?> { null }

/** Publishes [header] in the window's slot for as long as the page is composed (and takes it back when it leaves). */
@Composable
fun PublishCollapsedHeader(header: OrbitCollapsedHeader?) {
    val slot = LocalOrbitHeaderSlot.current ?: return
    SideEffect { slot.header = header }
    DisposableEffect(slot) { onDispose { if (slot.header === header) slot.header = null } }
}

/**
 * The collapsed heading's own row (the top bar's middle once scrolled, or a page's sticky bar where there is no slot): a 36 dp disc,
 * the title over the subtitle, then Shuffle and the 44 dp peach Play.
 */
@Composable
fun OrbitCollapsedHeaderRow(header: OrbitCollapsedHeader, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Disc(header.cover, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(header.title, style = fonts.title.copy(fontSize = 16.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (!header.subtitle.isNullOrBlank()) Text(header.subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        header.onShuffle?.let { OrbitRoundButton(CosmoIcons.Shuffle, "Shuffle", look.ink, it, onInk = look.ground, touch = 48.dp) }
        header.onPlay?.let { OrbitPlayButton(header.playing, it, look, Modifier.testTag("orbit:collapsed:play"), size = 44.dp, touch = 48.dp) }
    }
}

/** [OrbitCollapsedHeaderRow] as a 56 dp bar on the page's ground with a hairline under it, for a page that has no top-bar slot. */
@Composable
fun OrbitCollapsedHeaderBar(header: OrbitCollapsedHeader, modifier: Modifier = Modifier, side: Dp = 16.dp) {
    val look = orbitLook()
    Column(modifier.fillMaxWidth().background(look.ground)) {
        OrbitCollapsedHeaderRow(header, Modifier.fillMaxWidth().height(56.dp).padding(start = side, end = side - 4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(look.ink.copy(alpha = if (look.dark) 0.08f else 0.09f)))
    }
}

/**
 * The collapsed heading of a page that draws its own top (Podio, Read, Manga, Video and Watch, which have no top-bar slot): once
 * [list] has scrolled its first [after] items away (the header), [header] stands at the top of the page as [OrbitCollapsedHeaderBar],
 * on the page's ground under the status bar. Put it last in the page's box.
 */
@Composable
fun BoxScope.OrbitCollapsingHeaderBar(list: androidx.compose.foundation.lazy.LazyListState, header: OrbitCollapsedHeader, after: Int = 1, side: Dp = 16.dp) {
    val collapsed by androidx.compose.runtime.remember(list, after) { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex >= after } }
    if (!collapsed) return
    val look = orbitLook()
    OrbitCollapsedHeaderBar(
        header,
        Modifier.align(Alignment.TopCenter).background(look.ground)
            .windowInsetsPadding(WindowInsets.statusBars).testTag("orbit:collapsed"),
        side = side,
    )
}

// ---- placeholders shaped like the page ---------------------------------------------------------------------------------------

/**
 * The compact header while its page loads (the shared board's A3): the card's own outline as a placeholder, with the disc, three
 * lines of words, four round actions and the Play button's circle in their places, so nothing moves when the page arrives.
 */
@Composable
fun OrbitHeaderSkeleton(modifier: Modifier = Modifier, size: OrbitHeaderSize = OrbitHeaderSize.PAGE) {
    Box(modifier.height(size.height)) {
        CosmoShimmer(Modifier.fillMaxSize(), size.shape)
        Column(Modifier.fillMaxSize().padding(HEADER_PADDING)) {
            Row(Modifier.height(size.disc), verticalAlignment = Alignment.CenterVertically) {
                CosmoShimmer(Modifier.size(size.disc), CircleShape)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CosmoShimmer(Modifier.size(110.dp, 10.dp), RoundedCornerShape(4.dp))
                    CosmoShimmer(Modifier.size(170.dp, 24.dp), RoundedCornerShape(6.dp))
                    CosmoShimmer(Modifier.size(90.dp, 12.dp), RoundedCornerShape(4.dp))
                }
            }
            if (size == OrbitHeaderSize.PAGE) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(4) { CosmoShimmer(Modifier.size(44.dp), CircleShape) } }
            }
        }
        CosmoShimmer(Modifier.align(Alignment.BottomEnd).padding(size.playInset).size(size.play), CircleShape)
    }
}

/**
 * A list row while it loads, the row's own shape: the disc (or, [numbered], a short bar where the track number goes) and two ragged
 * lines, at the row's height.
 */
@Composable
fun OrbitRowSkeleton(index: Int, modifier: Modifier = Modifier, numbered: Boolean = false) {
    val disc = orbitRowDisc()
    Row(modifier.fillMaxWidth().height(orbitRowHeight()), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(disc + OrbitRowMetrics.ORBIT_ROOM_DP.dp), contentAlignment = Alignment.Center) {
            if (numbered) CosmoShimmer(Modifier.size(18.dp, 12.dp), RoundedCornerShape(4.dp)) else CosmoShimmer(Modifier.size(disc), CircleShape)
        }
        Spacer(Modifier.width(OrbitRowMetrics.GAP_DP.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CosmoShimmer(Modifier.fillMaxWidth(SKELETON_TITLES[index % SKELETON_TITLES.size]).height(13.dp), RoundedCornerShape(4.dp))
            CosmoShimmer(Modifier.fillMaxWidth(SKELETON_SUBTITLES[index % SKELETON_SUBTITLES.size]).height(11.dp), RoundedCornerShape(4.dp))
        }
    }
}

/** [rows] [OrbitRowSkeleton]s as items of a list, [side] from its edges, with stable keys. */
fun LazyListScope.orbitRowSkeletons(keyPrefix: String, side: Dp, rows: Int = 8, numbered: Boolean = false) {
    items(rows, key = { "$keyPrefix:row:$it" }, contentType = { "skeleton-row" }) { index ->
        OrbitRowSkeleton(index, Modifier.padding(horizontal = side), numbered = numbered)
    }
}

/** A grid of records while it loads: [columns] discs [cell] wide with a line under each, [rows] deep. */
@Composable
fun OrbitRecordGridSkeleton(columns: Int, cell: Dp, modifier: Modifier = Modifier, rows: Int = 2) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ORBIT_GRID_ROW_GAP)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(ORBIT_GRID_GAP)) {
                repeat(columns) { i ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CosmoShimmer(Modifier.size(cell), CircleShape)
                        CosmoShimmer(Modifier.size(cell * SKELETON_TITLES[i % SKELETON_TITLES.size], 12.dp), RoundedCornerShape(4.dp))
                    }
                }
            }
        }
    }
}

private val SKELETON_TITLES = floatArrayOf(0.62f, 0.46f, 0.74f, 0.52f, 0.68f)
private val SKELETON_SUBTITLES = floatArrayOf(0.38f, 0.30f, 0.44f, 0.27f, 0.34f)
