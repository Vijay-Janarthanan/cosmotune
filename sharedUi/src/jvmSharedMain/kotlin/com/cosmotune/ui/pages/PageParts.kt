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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.liftShadow
import com.cosmotune.ui.components.tonalFill
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
 * The pieces the album, playlist and artist pages share (`v2/v2.js` `V2.album`, `screens-v2.css` `.al-*`, `screens-more-a.js`
 * `pageArtist`): the numbers of the boards, the album hero's deeper shadow, the artist line, the meta line, the About tile and the
 * stat pills inside it, and the filter that the find-in-list field applies.
 */

/** The numbers of the pages, each with the board rule it comes from. Dp as floats, like the other metrics objects. */
internal object PageMetrics {
    /** `.al-cover .cv`: the cover of a release page is 236 dp, 6 dp under the top of its list (`.al-cover` padding). */
    const val COVER_DP = 236f
    const val COVER_TOP_DP = 6f

    /** `02-design-formulas.md` section 4: "The album hero uses `0 30 60 -18`": a lift of 24 dp in `shadow`, under the cover's own. */
    const val HERO_SHADOW_DP = 24f

    /** `.al-artist`: a 28 dp circle and the name, 10 dp apart, the name 15 sp. */
    const val ARTIST_AVATAR_DP = 28f
    const val ARTIST_GAP_DP = 10f
    const val ARTIST_NAME_SP = 15f

    /** `.al-meta .faint`: 13 sp. */
    const val META_SP = 13f

    /** `.al-actions`: 8 dp between the buttons; on a window narrower than [ACTIONS_NARROW_DP] with four icon buttons the gap is 4. */
    const val ACTIONS_GAP_DP = 8f
    const val ACTIONS_GAP_NARROW_DP = 4f
    const val ACTIONS_NARROW_DP = 380f

    /** The track rows of a release (`.tracks2 .mrow`). */
    const val TRACK_ROW_DP = BlockMetrics.TRACK_ROW_DP

    /** The rows of an artist's list (`.mrow`), which the artist page's skeleton stands for. */
    const val ROW_DP = BlockMetrics.ROW_DP

    /** Where the find-in-list field stands once open: after the cover, the meta and the actions. */
    const val SEARCH_ITEM_INDEX = 3

    /** The About tile: radius 24, padding 16, four lines of 14 sp text at a line height of 1.55. */
    const val ABOUT_PADDING_DP = 16f
    const val ABOUT_LINES = 4
    const val ABOUT_TEXT_SP = 14f
    const val ABOUT_LINE_HEIGHT = 1.55f

    /** A stat pill of the About tile: 13 sp text, 12 dp across and 6 dp up and down. */
    const val STAT_SP = 13f

    /** `pageArtist`: the banner is 360 dp tall; the name stands 16 dp from the left and 14 dp from the bottom. */
    const val BANNER_DP = 360f
    const val BANNER_NAME_START_DP = 16f
    const val BANNER_NAME_BOTTOM_DP = 14f

    /** The scrim over the banner, as stops of its height: `backgroundTop` at 30 % of alpha, clear at 30 % of the height, 85 % at 85 %, solid at the foot. */
    const val BANNER_SCRIM_TOP_ALPHA = 0.30f
    const val BANNER_SCRIM_CLEAR_AT = 0.30f
    const val BANNER_SCRIM_DEEP_AT = 0.85f
    const val BANNER_SCRIM_DEEP_ALPHA = 0.85f

    /** The artist's first songs: five, numbered 1 to 5. */
    const val POPULAR_COUNT = 5

    /** The latest release tile: radius 24, padding 12, a 96 dp cover of radius 12. */
    const val LATEST_PADDING_DP = 12f
    const val LATEST_COVER_DP = 96f

    /** `DetailScreen.kt` `ARTIST_ROW_MAX_ITEMS`: a shelf of more than this many cards shows this many and "Show all". */
    const val SHELF_PREVIEW_ITEMS = 5

    /** The cards of an artist's shelves are 150 dp wide. */
    const val SHELF_CARD_DP = 150f
}

/**
 * The album hero's deeper shadow (`0 30 60 -18`): a lift of 24 dp in the palette's `shadow`, put under the cover's own shadow. The
 * colour is read in the layer block, so a palette fade moves it and nothing recomposes ([liftShadow]).
 */
@Composable
internal fun Modifier.heroShadow(radius: Dp): Modifier {
    val colors = LocalCosmoColors.current
    val shape = remember(radius) { SuperellipseShape(radius) }
    return liftShadow(
        shape,
        PageMetrics.HERO_SHADOW_DP.dp,
        spot = { colors.live(CoverField.SHADOW).copy(alpha = 1f) },
        ambient = { Color.Transparent },
    )
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

/**
 * `.al-artist`: a 28 dp circle with the artist's initial on the tonal colour, and the name in weight 650, 15 sp. A tap ([onClick])
 * opens the artist; null leaves it plain text. The row is 48 dp tall to be a target.
 */
@Composable
internal fun ArtistCredit(name: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val initial = remember(name) { name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty() }
    val action = if (onClick != null) Modifier.clip(RoundedCornerShape(CosmoRadii.xs)).clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier
            .padding(horizontal = CosmoSpacing.margin - ARTIST_ROW_INSET)
            .then(action)
            .heightIn(min = 48.dp)
            .padding(horizontal = ARTIST_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PageMetrics.ARTIST_GAP_DP.dp),
    ) {
        Box(
            Modifier
                .size(PageMetrics.ARTIST_AVATAR_DP.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(tonalFill(colors)) }
                // The name beside it says who this is.
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp), initial),
                color = Color(palette.onAccentContainer),
            )
        }
        Text(
            text = name,
            style = type.forText(
                TextStyle(
                    fontFamily = type.textFamily,
                    fontWeight = FontWeight(650),
                    fontSize = PageMetrics.ARTIST_NAME_SP.sp,
                    lineHeight = PageMetrics.ARTIST_NAME_SP.sp * 1.3f,
                ),
                name,
            ),
            color = Color(palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The click area of the artist line reaches this far past the circle and the name, so the ripple has some room. */
private val ARTIST_ROW_INSET = 6.dp

/** The faint line under the title (`.al-meta .faint`): 13 sp, `textTertiary`, tabular figures. */
@Composable
internal fun ReleaseMetaLine(text: String, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(
            TextStyle(
                fontFamily = type.textFamily,
                fontWeight = FontWeight.Normal,
                fontSize = PageMetrics.META_SP.sp,
                lineHeight = PageMetrics.META_SP.sp * 1.4f,
            ).tabular(),
            text,
        ),
        color = Color(palette.textTertiary),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = CosmoSpacing.margin),
    )
}

/** The small bold heading of a block, 12 sp, weight 700, `textSecondary` (the overline of the title and of the latest release). */
internal fun CosmoType.pageOverlineStyle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 12.sp * 1.3f, letterSpacing = 0.01.em)

/**
 * The About tile (a card: superellipse 24, 16 dp from the sides, padding 16): "About" in `title(TITLE_S)`, [stats] under it (the
 * artist's pills), then the text at 14 sp in `textSecondary`, line height 1.55, cut to four lines with a text button to read the rest.
 * The button waits until the text has been laid out and has lost something, so a short text shows none.
 */
@Composable
internal fun AboutTile(text: String?, modifier: Modifier = Modifier, stats: (@Composable () -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val shape = remember { SuperellipseShape(CosmoRadii.card) }
    var expanded by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin)
            .cosmoTile(shape)
            .padding(PageMetrics.ABOUT_PADDING_DP.dp),
    ) {
        val title = stringResource(Res.string.cosmo_about)
        Text(
            text = title,
            style = type.forText(type.title(TypeScale.TITLE_S), title),
            color = Color(palette.textPrimary),
            modifier = Modifier.semantics { heading() },
        )
        if (stats != null) {
            Spacer(Modifier.height(10.dp))
            stats()
        }
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = type.forText(
                    TextStyle(
                        fontFamily = type.textFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = PageMetrics.ABOUT_TEXT_SP.sp,
                        lineHeight = PageMetrics.ABOUT_TEXT_SP.sp * PageMetrics.ABOUT_LINE_HEIGHT,
                    ),
                    text,
                ),
                color = Color(palette.textSecondary),
                maxLines = if (expanded) Int.MAX_VALUE else PageMetrics.ABOUT_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> if (!expanded) clipped = result.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth().then(if (reduced) Modifier else Modifier.animateContentSize()),
            )
            if (clipped || expanded) {
                // A text button has 12 dp of padding inside its label: the label stands on the text's edge.
                CosmoButton(
                    text = stringResource(if (expanded) Res.string.cosmo_show_less else Res.string.cosmo_read_more),
                    onClick = { expanded = !expanded },
                    modifier = Modifier.offset(x = (-12).dp),
                    kind = CosmoButtonKind.TEXT,
                    size = CosmoButtonSize.SMALL,
                )
            }
        }
    }
}

/** A stat pill of the artist's About tile ("1.2M subscribers"): 13 sp, in the tonal fill. */
@Composable
internal fun StatPill(text: String, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val type = LocalCosmoType.current
    Text(
        text = text,
        style = type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(600), fontSize = PageMetrics.STAT_SP.sp, lineHeight = PageMetrics.STAT_SP.sp * 1.3f),
            text,
        ),
        color = Color(colors.palette.onAccentContainer),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .drawBehind { drawRect(tonalFill(colors)) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
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

/** `.skel-row`: a 52 dp cover of radius 12, 14 dp before two lines 12 dp tall and 8 apart, ragged in width so they read as text. */
private val SKELETON_COVER_SHAPE = SuperellipseShape(12.dp)
private val SKELETON_LINE_SHAPE = RoundedCornerShape(CosmoRadii.xs)
private val SKELETON_TITLE_WIDTHS = floatArrayOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val SKELETON_SUBTITLE_WIDTHS = floatArrayOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

/** The place of the page's buttons: the 56 dp Play (a rounded square, 0.30 of its height) and two 48 dp icon buttons. */
private val SKELETON_PLAY_SHAPE = SuperellipseShape(17.dp)
private val SKELETON_ICON_SHAPE = SuperellipseShape(14.dp)

/**
 * A page's list while its songs load, in the new look (`.skel-row`, `components-v3bc.css`): the place of the page's buttons, then
 * [rows] rows [rowDp] tall, 16 dp from the sides, each a 52 dp cover and two lines. Every block is the cover's own skeleton
 * ([CosmoShimmer]: `skelBase` with a sweep of `skelShine`, still under reduced motion) at full strength: the old `detailSkeleton`
 * dimmed its blocks to 40 % over the page, which read as a grey wash, and drew the old page's round buttons. Keys are stable.
 */
internal fun LazyListScope.pageSkeleton(keyPrefix: String, rowDp: Float, rows: Int = 8) {
    item(key = keyPrefix + ":actions", contentType = "skeleton-actions") {
        Row(
            Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, top = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(PageMetrics.ACTIONS_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(width = 124.dp, height = 56.dp), SKELETON_PLAY_SHAPE)
            CosmoShimmer(Modifier.size(48.dp), SKELETON_ICON_SHAPE)
            CosmoShimmer(Modifier.size(48.dp), SKELETON_ICON_SHAPE)
        }
    }
    items(rows, key = { keyPrefix + ":row:" + it }, contentType = { "skeleton-row" }) { index ->
        Row(
            Modifier.fillMaxWidth().height(rowDp.dp).padding(horizontal = CosmoSpacing.margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(BlockMetrics.ROW_COVER_DP.dp), SKELETON_COVER_SHAPE)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_TITLE_WIDTHS[index % SKELETON_TITLE_WIDTHS.size]).height(12.dp), SKELETON_LINE_SHAPE)
                Spacer(Modifier.height(8.dp))
                CosmoShimmer(Modifier.fillMaxWidth(SKELETON_SUBTITLE_WIDTHS[index % SKELETON_SUBTITLE_WIDTHS.size]).height(12.dp), SKELETON_LINE_SHAPE)
            }
        }
    }
}
