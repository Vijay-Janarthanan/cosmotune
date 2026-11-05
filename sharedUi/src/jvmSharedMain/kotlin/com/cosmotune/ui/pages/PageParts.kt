package com.cosmotune.ui.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_about
import com.cosmotune.music.sharedui.resources.cosmo_read_more
import com.cosmotune.music.sharedui.resources.cosmo_show_less
import org.jetbrains.compose.resources.stringResource

/*
 * The pieces the album, playlist and artist pages share (DESIGN_SYSTEM.md 6.1): their numbers, the artist line, the meta line, About
 * and the stat pills inside it, and the filter that the find-in-list field applies.
 */

/** The numbers of the pages. Dp as floats, like the other metrics objects. */
internal object PageMetrics {
    /** The cover of a release page: 220 dp, centred, on a phone; 240 dp on the page's start edge from 600 dp. */
    const val COVER_DP = 220f
    const val COVER_WIDE_DP = 240f
    const val WIDE_PAGE_DP = 600f
    const val COVER_TOP_DP = 8f

    /** How far the cover colour reaches behind a detail header (DESIGN_SYSTEM.md 2.3). */
    const val HEADER_GRADIENT_DP = 320f

    /** The track rows of a release (`.tracks2 .mrow`). */
    const val TRACK_ROW_DP = BlockMetrics.TRACK_ROW_DP

    /** The rows of an artist's list (`.mrow`), which the artist page's skeleton stands for. */
    const val ROW_DP = BlockMetrics.ROW_DP

    /** Where the find-in-list field stands once open: after the cover, the meta and the actions. */
    const val SEARCH_ITEM_INDEX = 3

    /** About: three lines of body text until "Read more". */
    const val ABOUT_LINES = 3

    /** The artist's hero: 280 dp of the picture, full bleed; the name stands 16 dp from the left and 14 dp from the bottom. */
    const val BANNER_DP = 280f
    const val BANNER_NAME_START_DP = 16f
    const val BANNER_NAME_BOTTOM_DP = 14f

    /** The hero's fade to the ground, as stops of its height: clear to 60 %, solid at the foot (the bottom 40 %). */
    const val BANNER_SCRIM_CLEAR_AT = 0.60f

    /** The artist's first songs: five, numbered 1 to 5. */
    const val POPULAR_COUNT = 5

    /** The latest release's cover. */
    const val LATEST_COVER_DP = 64f

    /** `DetailScreen.kt` `ARTIST_ROW_MAX_ITEMS`: a shelf of more than this many cards shows this many and "Show all". */
    const val SHELF_PREVIEW_ITEMS = 5
}

/**
 * The songs a typed query leaves standing, each still carrying its place in the full list (an album's track numbers are the release's
 * own, not positions in what the filter left). The rule of the find-in-list field: the title or the artist contains the query,
 * whatever the case; a blank query leaves everything.
 */
internal fun List<Song>.matchingQuery(query: String): List<IndexedValue<Song>> {
    val all = withIndex().toList()
    val needle = query.trim()
    if (needle.isEmpty()) return all
    return all.filter { (_, song) -> song.title.contains(needle, ignoreCase = true) || song.artist.contains(needle, ignoreCase = true) }
}

/** Whether every card of the shelf is an artist (a browse id of a channel, `UC...`): artists are drawn as circles. */
internal fun HomeShelf.isArtists(): Boolean = items.isNotEmpty() && items.all { it.browseId?.startsWith("UC") == true }

/** The artist's name under a release's title, in the primary ink. A tap ([onClick]) opens the artist; null leaves it plain text. 48 dp tall to be a target. */
@Composable
internal fun ArtistCredit(name: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val action = if (onClick != null) Modifier.clip(RoundedCornerShape(CosmoRadii.rSm)).clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier
            .padding(horizontal = CosmoSpacing.margin - ARTIST_ROW_INSET)
            .then(action)
            .heightIn(min = 48.dp)
            .padding(horizontal = ARTIST_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, style = type.forText(type.rowTitle, name), color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The click area of the artist line reaches this far past the name, so the ripple has some room. */
private val ARTIST_ROW_INSET = 6.dp

/** The line under the title: `meta`, `textSecondary`, tabular figures. */
@Composable
internal fun ReleaseMetaLine(text: String, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(type.meta.tabular(), text),
        color = Color(palette.textSecondary),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = CosmoSpacing.margin),
    )
}

/** The overline of a block: `overline`. */
internal fun CosmoType.pageOverlineStyle(): TextStyle = overline

/**
 * About (DESIGN_SYSTEM.md 6.1), on the page with no card around it: the heading, [stats] under it (the artist's pills), then the text in
 * `body`, `textSecondary`, cut to three lines with a text button to read the rest. The button waits until the text has been laid out
 * and has lost something, so a short text shows none.
 */
@Composable
internal fun AboutTile(text: String?, modifier: Modifier = Modifier, stats: (@Composable () -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    var expanded by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin)) {
        val title = stringResource(Res.string.cosmo_about)
        Text(
            text = title,
            style = type.forText(type.titleLarge, title),
            color = Color(palette.textPrimary),
            modifier = Modifier.semantics { heading() },
        )
        if (stats != null) {
            Spacer(Modifier.height(12.dp))
            stats()
        }
        if (text != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                style = type.forText(type.bodyText, text),
                color = Color(palette.textSecondary),
                maxLines = if (expanded) Int.MAX_VALUE else PageMetrics.ABOUT_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (!expanded) clipped = result.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth().then(if (reduced) Modifier else Modifier.animateContentSize()),
            )
            if (clipped || expanded) {
                // A text button has 12 dp of padding inside its label: the label stands on the text's edge.
                KitTextButton(
                    text = stringResource(if (expanded) Res.string.cosmo_show_less else Res.string.cosmo_read_more),
                    onClick = { expanded = !expanded },
                    modifier = Modifier.offset(x = (-12).dp),
                )
            }
        }
    }
}

/** A stat pill of the artist's About ("1.2M subscribers"): neutral, `caption` on `surface2` (DESIGN_SYSTEM.md 4.11). */
@Composable
internal fun StatPill(text: String, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(type.caption, text),
        color = Color(palette.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .drawBehind { drawRect(colors.live(CoverField.SURFACE_HIGH)) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The artist's pills, side by side and wrapping onto a second line on a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatPills(subscribers: String?, listeners: String?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subscribers != null) StatPill(subscribers)
        if (listeners != null) StatPill(listeners)
    }
}

// ---- the skeleton of a page's list -------------------------------------------------------------------------------------------

/** A 48 dp cover, 16 dp before two lines 12 dp tall and 8 apart, ragged in width so they read as text (DESIGN_SYSTEM.md 4.15). */
private val SKELETON_COVER_SHAPE = RoundedCornerShape(CosmoRadii.rSm)
private val SKELETON_LINE_SHAPE = RoundedCornerShape(CosmoRadii.rXs)
private val SKELETON_TITLE_WIDTHS = floatArrayOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val SKELETON_SUBTITLE_WIDTHS = floatArrayOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/** The place of the page's buttons: two 40 dp pills, and the 56 dp play circle at the end. */
private val SKELETON_PILL_SHAPE = RoundedCornerShape(50)

/**
 * A page's list while its songs load: the place of the page's buttons, then [rows] rows [rowDp] tall, 16 dp from the sides, each a
 * 48 dp cover and two lines. Every block is the cover's own skeleton
 * ([CosmoShimmer]: `skelBase` with a sweep of `skelShine`, still under reduced motion) at full strength: the old `detailSkeleton`
 * dimmed its blocks to 40 % over the page, which read as a grey wash, and drew the old page's round buttons. Keys are stable.
 */
internal fun LazyListScope.pageSkeleton(keyPrefix: String, rowDp: Float, rows: Int = 8) {
    item(key = keyPrefix + ":actions", contentType = "skeleton-actions") {
        Row(
            Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(width = 104.dp, height = 40.dp), SKELETON_PILL_SHAPE)
            CosmoShimmer(Modifier.size(width = 88.dp, height = 40.dp), SKELETON_PILL_SHAPE)
            Spacer(Modifier.weight(1f))
            CosmoShimmer(Modifier.size(56.dp), CircleShape)
        }
    }
    items(rows, key = { keyPrefix + ":row:" + it }, contentType = { "skeleton-row" }) { index ->
        Row(
            Modifier.fillMaxWidth().height(rowDp.dp).padding(horizontal = CosmoSpacing.margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(BlockMetrics.ROW_ART_DP.dp), SKELETON_COVER_SHAPE)
            Spacer(Modifier.width(BlockMetrics.ROW_ART_GAP_DP.dp))
            Column(Modifier.weight(1f)) {
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_TITLE_WIDTHS[index % SKELETON_TITLE_WIDTHS.size]).height(12.dp), SKELETON_LINE_SHAPE)
                Spacer(Modifier.height(8.dp))
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_SUBTITLE_WIDTHS[index % SKELETON_SUBTITLE_WIDTHS.size]).height(12.dp), SKELETON_LINE_SHAPE)
            }
        }
    }
}
