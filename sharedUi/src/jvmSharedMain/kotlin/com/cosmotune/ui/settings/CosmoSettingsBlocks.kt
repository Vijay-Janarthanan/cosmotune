package com.cosmotune.ui.settings

import com.cosmotune.ui.orbit.OrbitAccountCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.design.ColorMix
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.ControlMorph
import com.cosmotune.design.GroupSegments
import com.cosmotune.design.SliderSteps
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.components.DISABLED_ALPHA
import com.cosmotune.ui.components.FitLabel
import com.cosmotune.ui.components.MorphPath
import com.cosmotune.ui.components.STATE_LAYER_HOVERED
import com.cosmotune.ui.components.STATE_LAYER_PRESSED
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.components.ink
import com.cosmotune.ui.components.innerRing
import com.cosmotune.ui.components.keepRedrawnWith
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.components.topLine
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.music.ui.components.thumbnailBorder
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import kotlin.math.min

/*
 * The settings pages in Orbit (Canvas repo, design/cosmotune/batch-a/board.html, section 2): a group is the board's quiet card, a
 * mono label over the ink at 5 % (radius 26), its rows Orbit's title and body faces with the category's colour tile and Orbit's
 * switch, and a choice is a row of chips with the chosen one solid ink. The names and the order of the parameters are the ones of
 * the phone's `SettingsSheet.kt` functions that delegate to them (`SettingsGroup`, `RowDivider`, `SettingsRow`, ...), so the old
 * screens' call sites keep working; the desktop's settings functions delegate to the same blocks.
 *
 * A group is a column of rows with a 3 dp gap wherever a [CosmoTileGap] stands; the group draws one rounded tile behind each run
 * of rows between two gaps. It finds the gaps from the dividers themselves: each reports where it was placed, relative to the group.
 */

/** The insets the rows share, for a caller that lays something under a row's text (a segmented control, say). */
object CosmoSettingsInsets {
    /** A group's distance from the sides of the page, and a row's padding inside its tile. */
    val side: Dp = CosmoSpacing.margin

    /** Where a row's text starts inside the group: its padding, the 40 dp lead tile and the 14 dp gap (16 + 40 + 14). */
    val textStart: Dp = 70.dp
}

private val ROW_PADDING_H = 16.dp
private val ROW_PADDING_V = 10.dp
private val ROW_MIN_HEIGHT = 60.dp
private val ROW_GAP = 14.dp
private val LEAD_SIZE = 40.dp
private val LEAD_ICON = 20.dp
private val LEAD_RADIUS = 14.dp
private val SUB_ROW_BOTTOM = 10.dp
private val SLIDER_BOTTOM = 4.dp
private val DESTRUCTIVE_MIN_HEIGHT = 52.dp
private val BADGE_HEIGHT = 18.dp
private val BADGE_PADDING = 6.dp
private val CHEVRON_SIZE = 20.dp
private val CHIP_GAP = 8.dp
private val HEADER_TOP = 24.dp
private val HEADER_BOTTOM = 8.dp
private val FOOTER_SIDE = 20.dp
private val FOOTER_TOP = 8.dp
private const val FOCUS_RING = 3f

/** The tile is Orbit's row fill: the ink at 5 % in the dark, 4.5 % in the light (`orbitLook().row`). */
private const val TILE_INK_DARK = 0.05f
private const val TILE_INK_LIGHT = 0.045f

/** The corners of a group's tiles: the outer ones the board's card (26), the ones where two tiles meet across a gap 8. */
private const val TILE_OUTER_DP = 26f
private const val TILE_INNER_DP = 8f
private const val MINI_SEGMENT_HEIGHT_DP = 32f
private const val CHIP_HEIGHT_DP = 40f

/** A settings segment's label, shrunk half a sp at a time from its own size to fit; never below 12 sp (then the ellipsis cuts). */
private val SEGMENT_FIT = FitLabel(ControlMetrics.settingsSegmentLabelMaxSp(compact = false), ControlMetrics.SEGMENT_LABEL_MIN_SP, 0.5f, wholeWords = false)
private val COMPACT_SEGMENT_FIT = FitLabel(ControlMetrics.settingsSegmentLabelMaxSp(compact = true), ControlMetrics.SEGMENT_LABEL_MIN_SP, 0.5f, wholeWords = false)

// ---- the tiles of a group -----------------------------------------------------------------------------------------------------

/**
 * What a group knows about its own layout: where its dividers are, which gives its tiles ([segments], in px from the group's top,
 * already with the corner radii it draws). Written during layout (a placed divider, a placed group), read while drawing, so a
 * change of layout redraws the tiles and never recomposes anything.
 */
@Stable
private class Tiles(private val outerPx: Float, private val innerPx: Float) {
    var group: LayoutCoordinates? = null
        private set
    private val dividers = LinkedHashMap<Any, LayoutCoordinates>()

    var segments by mutableStateOf<List<GroupSegments.Segment>>(emptyList())
        private set

    fun groupPlaced(coordinates: LayoutCoordinates) {
        group = coordinates
        refresh()
    }

    fun dividerPlaced(key: Any, coordinates: LayoutCoordinates) {
        dividers[key] = coordinates
        refresh()
    }

    fun dividerGone(key: Any) {
        if (dividers.remove(key) != null) refresh()
    }

    private fun refresh() {
        val whole = group ?: return
        if (!whole.isAttached) return
        val gaps = ArrayList<ClosedFloatingPointRange<Float>>(dividers.size)
        for (divider in dividers.values) {
            if (!divider.isAttached) continue
            val y = whole.localPositionOf(divider, Offset.Zero).y
            gaps.add(y..(y + divider.size.height))
        }
        val next = GroupSegments.of(whole.size.height.toFloat(), gaps, outerPx, innerPx)
        if (next != segments) segments = next
    }
}

private val LocalTiles = compositionLocalOf<Tiles?> { null }

/** Where one row stands inside its group (px from the group's top): the row finds its own tile, and so its corners, from this. */
private class RowPlace {
    var top = 0f
    var bottom = 0f
    var known = false

    fun update(tiles: Tiles?, coordinates: LayoutCoordinates) {
        val whole = tiles?.group
        if (whole == null || !whole.isAttached || !coordinates.isAttached) {
            known = false
            return
        }
        top = whole.localPositionOf(coordinates, Offset.Zero).y
        bottom = top + coordinates.size.height
        known = true
    }
}

/**
 * The corner radii of a row (top-left, top-right, bottom-right, bottom-left, in px) from the tile it stands in: a corner of the row that
 * is a corner of the tile takes the tile's radius, the others are square. A row that is in no group has no rounding.
 */
private fun cornersOf(tiles: Tiles?, place: RowPlace, out: FloatArray) {
    var top = 0f
    var bottom = 0f
    if (tiles != null && place.known) {
        val segments = tiles.segments
        for (i in segments.indices) {
            val s = segments[i]
            if (place.top >= s.top - EDGE_PX && place.bottom <= s.bottom + EDGE_PX) {
                top = if (place.top <= s.top + EDGE_PX) s.topRadius else 0f
                bottom = if (place.bottom >= s.bottom - EDGE_PX) s.bottomRadius else 0f
                break
            }
        }
    }
    out[0] = top; out[1] = top; out[2] = bottom; out[3] = bottom
}

private const val EDGE_PX = 1.5f

/** The tile from [top] to [bottom] across [width], its upper corners [topRadius] and its lower ones [bottomRadius] (each held to half the height). */
private fun tileOf(width: Float, top: Float, bottom: Float, topRadius: Float, bottomRadius: Float): RoundRect {
    val half = (bottom - top) / 2f
    val t = CornerRadius(min(topRadius, half))
    val b = CornerRadius(min(bottomRadius, half))
    return RoundRect(0f, top, width, bottom, t, t, b, b)
}

// ---- type -----------------------------------------------------------------------------------------------------------------

/** A row's title: Orbit's title face (Hanken Grotesk 700) at 14.5 sp, as the board's rows. */
private fun OrbitFonts.rowTitle(): TextStyle = title.copy(fontSize = 14.5.sp, lineHeight = 18.sp)

/** The line under a row's title: Orbit's body at 12.5 sp, drawn in the dim ink. */
private fun OrbitFonts.rowSubtitle(): TextStyle = body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)

// ---- the group ------------------------------------------------------------------------------------------------------------------

/**
 * One group of rows: Orbit's mono label as its [header] (in capitals, tracked, the dim ink), a plain-language [footer] below, and
 * between them the tile or tiles that [content] stands on, 16 dp from the sides. Without a header there is [topSpacing] of room
 * above instead. The tiles are the runs of rows between [CosmoTileGap]s: the first has outer corners 26 at the top, the last 26 at
 * the bottom, every other corner is 8, and the gap between two is 3 dp.
 */
@Composable
fun CosmoSettingsGroup(
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val fonts = orbitFonts()
    val look = orbitLook()
    val density = LocalDensity.current.density
    val tiles = remember(density) { Tiles(outerPx = TILE_OUTER_DP * density, innerPx = TILE_INNER_DP * density) }
    if (header != null) {
        // Orbit's mono label, a little apart, so the sections of a page read at a glance.
        val overline = remember(header) { header.uppercase() }
        Text(
            text = overline,
            style = fonts.label,
            color = look.dim,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CosmoSettingsInsets.side + 6.dp, end = CosmoSettingsInsets.side, top = HEADER_TOP, bottom = HEADER_BOTTOM)
                .semantics { heading() },
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    CompositionLocalProvider(LocalTiles provides tiles) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CosmoSettingsInsets.side)
                .onPlaced { tiles.groupPlaced(it) }
                .drawWithCache {
                    // The tiles are rebuilt when the group's size or its dividers move; the colours are read below, while drawing, so a
                    // palette fade repaints them without rebuilding anything.
                    keepRedrawnWith(palette)
                    val segments = tiles.segments
                    val width = size.width
                    val paths = Array(segments.size) { i ->
                        Path().apply { addRoundRect(tileOf(width, segments[i].top, segments[i].bottom, segments[i].topRadius, segments[i].bottomRadius)) }
                    }
                    onDrawBehind {
                        val fill = colors.live(CoverField.TEXT_PRIMARY).copy(alpha = if (colors.dark) TILE_INK_DARK else TILE_INK_LIGHT)
                        for (i in paths.indices) drawPath(paths[i], fill)
                    }
                },
        ) {
            content()
        }
    }
    if (footer != null) {
        Text(
            text = footer,
            style = fonts.rowSubtitle(),
            color = look.dim,
            modifier = Modifier.fillMaxWidth().padding(start = FOOTER_SIDE, end = FOOTER_SIDE, top = FOOTER_TOP),
        )
    }
}

/**
 * The line between two rows of one card of a [CosmoSettingsGroup]: a hairline in the text colour at 8 %, from where the rows' text
 * starts to the card's end. The rows stay one card; [CosmoTileGap] is the break that starts a new one.
 */
@Composable
fun CosmoRowDivider() {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(HAIRLINE_ROOM)
            .padding(start = CosmoSettingsInsets.textStart)
            .drawBehind {
                keepRedrawnWith(palette)
                val y = size.height / 2f
                drawLine(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = HAIRLINE_ALPHA), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            },
    )
}

/**
 * A 3 dp gap between two tiles of a [CosmoSettingsGroup], for rows that should stand apart (an action that throws something away).
 * It reports where it stands, so the group knows where one tile ends and the next begins; outside a group it is just a 3 dp spacer.
 */
@Composable
fun CosmoTileGap() {
    val tiles = LocalTiles.current
    val key = remember { Any() }
    DisposableEffect(tiles, key) { onDispose { tiles?.dividerGone(key) } }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(GroupSegments.GAP_DP.dp)
            .onPlaced { tiles?.dividerPlaced(key, it) },
    )
}

private val HAIRLINE_ROOM = 1.dp
private const val HAIRLINE_ALPHA = 0.08f

/**
 * The colour of the lead tiles of the rows below: a page of Settings gives its rows its category's colour (the icon is drawn in
 * white on it); null keeps the tonal tile.
 */
val LocalSettingsTileColor = compositionLocalOf<Color?> { null }

// ---- press, hover and focus on a row ---------------------------------------------------------------------------------------------

/**
 * Makes a row clickable and gives it its state layer (`textPrimary` at 12 % pressed, 8 % under a mouse) and, while it has the keyboard
 * focus, an `accentStrong` ring along the inside of its edge. Both follow the corners of the tile the row stands in (a row in the middle
 * of a tile has square ones), or [squircleDp] when the row is a tile of its own. The ring is inside because the next tile is 3 dp away.
 */
@Composable
private fun Modifier.tileClickable(
    onClick: () -> Unit,
    enabled: Boolean,
    role: Role,
    squircleDp: Float = 0f,
): Modifier {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val tiles = LocalTiles.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val layer = remember { Animatable(0f) }
    val layerTarget = if (!enabled) 0f else if (pressed) STATE_LAYER_PRESSED else if (hovered) STATE_LAYER_HOVERED else 0f
    LaunchedEffect(layerTarget, reduced) { layer.animateTo(layerTarget, CosmoMotion.effects(reduced)) }
    val place = remember { RowPlace() }
    val corners = remember { FloatArray(4) }
    val outline = remember { Path() }
    val morph = remember { MorphPath() }
    return this
        .onPlaced { place.update(tiles, it) }
        .drawBehind {
            keepRedrawnWith(palette)
            val alpha = layer.value
            val ring = focused && enabled
            if (alpha > 0.001f || ring) {
                val shape = if (squircleDp > 0f) {
                    morph.update(size.width, size.height, squircleDp.dp.toPx(), 4f)
                } else {
                    cornersOf(tiles, place, corners)
                    outline.reset()
                    outline.addRoundRect(
                        RoundRect(
                            0f, 0f, size.width, size.height,
                            CornerRadius(min(corners[0], size.height / 2f)), CornerRadius(min(corners[1], size.height / 2f)),
                            CornerRadius(min(corners[2], size.height / 2f)), CornerRadius(min(corners[3], size.height / 2f)),
                        ),
                    )
                    outline
                }
                if (alpha > 0.001f) drawPath(shape, colors.live(CoverField.TEXT_PRIMARY).copy(alpha = alpha))
                if (ring) innerRing(shape, colors.live(CoverField.ACCENT_STRONG), FOCUS_RING)
            }
        }
        .hoverable(interaction, enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

// ---- the rows -------------------------------------------------------------------------------------------------------------------------

/**
 * Whether [CosmoSettingsRow] (and the rows built on it) draw their icon tile. A page whose own rows have none (the desktop's
 * Playback page, its toggles and sliders written without icons) provides false, so every row on the page reads the same.
 */
val LocalSettingsRowIcons = androidx.compose.runtime.compositionLocalOf { true }

/**
 * The standard row (`.row2`): a 40 dp lead tile with the icon in the cover's tonal colour (none when there is no icon), the title
 * on one line with an optional [badge] after it, the subtitle (up to five lines, or [subtitleContent]), and at the end [trailing] (a
 * switch, say) or the [value] followed by a chevron. A row with an [onClick] is a button; it shows the chevron even without a value.
 * Disabled, the row's own parts are drawn at 38 % and it cannot be clicked; [trailing] keeps its own look, because it is a control
 * that dims itself when it is disabled.
 */
@Composable
fun CosmoSettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier
            .fillMaxWidth()
            // A search result that points at this row brings it into view and lights it (a page that provides a highlight).
            .settingsHighlight(title)
            .then(if (onClick != null) Modifier.tileClickable(onClick, enabled, Role.Button) else Modifier)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (LocalSettingsRowIcons.current && (icon != null || iconPainter != null)) LeadTile(icon, iconPainter)
            Column(Modifier.weight(1f)) {
                TitleLine(title, badge)
                if (subtitleContent != null) {
                    subtitleContent()
                } else if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = fonts.rowSubtitle(),
                        color = look.dim,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing == null && (value != null || onClick != null)) {
                if (value != null) {
                    Text(
                        text = value,
                        style = fonts.body,
                        color = look.dim,
                        maxLines = 1,
                    )
                }
                CosmoChevron()
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(ROW_GAP))
            trailing()
        }
    }
}

/** The title of a row on one line, with its [badge] after it. */
@Composable
private fun TitleLine(title: String, badge: String?) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = fonts.rowTitle(),
            color = look.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            CosmoBadge(badge)
        }
    }
}

/** The 40 dp tile at the start of a row: superellipse 14, the tonal fill, a 20 dp icon in `onAccentContainer`. */
@Composable
private fun LeadTile(icon: ImageVector?, painter: Painter?) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val morph = remember { MorphPath() }
    val fill = LocalSettingsTileColor.current
    val tint = if (fill != null) Color.White else Color(palette.onAccentContainer)
    Box(
        Modifier
            .size(LEAD_SIZE)
            .drawBehind {
                keepRedrawnWith(palette)
                val shape = morph.update(size.width, size.height, LEAD_RADIUS.toPx(), 4f)
                if (fill != null) drawPath(shape, fill) else drawPath(shape, tonalFill(colors))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Icon(painter, contentDescription = null, tint = tint, modifier = Modifier.size(LEAD_ICON))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(LEAD_ICON))
        }
    }
}

/**
 * A toggle that reads as part of the option above it: no tile, its text under the titles (70 dp from the start), the top padding
 * 0 and the bottom 10. The whole row toggles.
 */
@Composable
fun CosmoSettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier
            .fillMaxWidth()
            .tileClickable({ onCheckedChange(!checked) }, enabled = true, role = Role.Switch)
            .padding(start = CosmoSettingsInsets.textStart, end = ROW_PADDING_H, top = 0.dp, bottom = SUB_ROW_BOTTOM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TitleLine(title, badge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = fonts.rowSubtitle(),
                    color = look.dim,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(ROW_GAP))
        CosmoSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A continuous or stepped setting: the row's header (tile, title, subtitle, the [value] at the end) and under it the thick slider, 70 dp
 * from the start. [sliderValue] is in [valueRange]; the slider reports a fraction, which [SliderSteps] turns into the nearest of the
 * [steps] stops, so the value jumps from stop to stop as it does with Material's slider. [onSliderValue] is called when the stop changes.
 */
@Composable
fun CosmoSliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.fillMaxWidth()) {
        CosmoSettingsRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            trailing = {
                Text(text = value, style = fonts.label, color = look.dim, maxLines = 1)
            },
        )
        Box(
            Modifier
                .padding(start = CosmoSettingsInsets.textStart, end = ROW_PADDING_H, bottom = SLIDER_BOTTOM)
                .semantics(mergeDescendants = true) { stateDescription = value },
        ) {
            CosmoSlider(
                compact = true,
                value = SliderSteps.fractionOf(sliderValue, valueRange),
                onValueChange = { fraction ->
                    val stop = SliderSteps.snap(fraction, valueRange, steps)
                    if (stop != sliderValue) onSliderValue(stop)
                },
                contentDescription = title,
            )
        }
    }
}

/** Sign out: the label centred on its row, in the rows' title face and the theme's error colour (the danger red), 52 dp at least. */
@Composable
fun CosmoDestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fonts = orbitFonts()
    Box(
        Modifier
            .fillMaxWidth()
            .tileClickable(onClick, enabled, Role.Button)
            .heightIn(min = DESTRUCTIVE_MIN_HEIGHT)
            .padding(horizontal = ROW_PADDING_H),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = fonts.rowTitle(),
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
        )
    }
}

/** A small pill after a title (Blocking, Connected): 18 dp high, the ink at 8 % under Orbit's mono label in the ink. */
@Composable
fun CosmoBadge(text: String) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val fonts = orbitFonts()
    val look = orbitLook()
    val label = remember(text) { text.uppercase() }
    Box(
        Modifier
            .height(BADGE_HEIGHT)
            .drawBehind {
                keepRedrawnWith(palette)
                drawRoundRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = 0.08f), cornerRadius = CornerRadius(size.height / 2f))
            }
            .padding(horizontal = BADGE_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = fonts.label.copy(fontSize = 9.5.sp, letterSpacing = 1.sp),
            color = look.ink,
            maxLines = 1,
        )
    }
}

/** The arrow at the end of a row that opens something: 20 dp in `textTertiary`. */
@Composable
fun CosmoChevron() {
    val palette = LocalCosmoColors.current.palette
    Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(CHEVRON_SIZE))
}

// ---- the segmented control of a settings page ------------------------------------------------------------------------------------------

/**
 * The choice of a settings page (theme, quality, refresh rate, colour intensity) as Orbit's chips (board 2): 40 dp pills 8 dp apart,
 * each an equal share of the width it is given so that four or five options still fit, the ink at 8 % and the chosen one solid ink
 * with the ground's colour for its words. [compact] is the small one (32 dp, 12.5 sp), for a choice that stands under a row.
 * Disabled it is drawn at 38 % and takes no input.
 */
@Composable
fun CosmoSettingsSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val height = if (compact) MINI_SEGMENT_HEIGHT_DP else CHIP_HEIGHT_DP
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        options.forEachIndexed { index, label ->
            SettingsSegment(
                label = label,
                index = index,
                count = options.size,
                selected = index == selectedIndex,
                heightDp = height,
                compact = compact,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { onSelect(index) }
        }
    }
}

@Composable
private fun SettingsSegment(
    label: String,
    index: Int,
    count: Int,
    selected: Boolean,
    heightDp: Float,
    compact: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val fonts = orbitFonts()
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val layer = remember { Animatable(0f) }
    val layerTarget = if (!enabled) 0f else if (pressed) STATE_LAYER_PRESSED else if (hovered) STATE_LAYER_HOVERED else 0f
    LaunchedEffect(layerTarget, reduced) { layer.animateTo(layerTarget, CosmoMotion.effects(reduced)) }
    // Orbit's chip: the ink at 8 %, the chosen one solid ink with the ground's colour for its words.
    val look = orbitLook()
    val ink = if (selected) look.ground else look.ink
    val textStyle = fonts.chip.copy(fontSize = if (compact) 12.5.sp else 14.sp, color = ink)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(heightDp.dp)
            .cosmoFocusRing(interaction, (heightDp / 2f).dp)
            .drawBehind {
                keepRedrawnWith(palette)
                val radius = CornerRadius(size.height / 2f)
                val text = colors.live(CoverField.TEXT_PRIMARY)
                drawRoundRect(if (selected) text else text.copy(alpha = 0.08f), cornerRadius = radius)
                if (layer.value > 0.001f) drawRoundRect((if (selected) colors.live(CoverField.BACKGROUND_TOP) else text).copy(alpha = layer.value), cornerRadius = radius)
            }
            .hoverable(interaction, enabled)
            .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.Tab) { haptics.play(Haptic.Select); onSelect() }
            // The chip keeps at least the old segment's room for its words: no check mark, the old ends when small (P4-17).
            .padding(horizontal = if (compact) ControlMetrics.SEGMENT_FILL_PADDING_DP.dp else 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = if (compact) COMPACT_SEGMENT_FIT else SEGMENT_FIT,
        )
    }
}

/**
 * One option of a choice that lays its own options out (a row of phrase lengths, the window's backdrop): Orbit's 40 dp chip, the ink
 * at 8 %, the chosen one solid ink with the ground's colour for its words.
 */
@Composable
fun CosmoSettingsChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SettingsSegment(
        label = label, index = 0, count = 1, selected = selected, heightDp = CHIP_HEIGHT_DP, compact = false, enabled = enabled,
        modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }, onSelect = onClick,
    )
}

// ---- the account card -------------------------------------------------------------------------------------------------------------------

/**
 * Who is signed in, at the top of the settings (board 2): Home's colour card from the picture, the picture as a disc, the mono
 * [label] (where the account is from, "Account · Google"), the [name] and the [subtitle] (the sync status). No bite, since nothing
 * plays. It stands 16 dp from the sides with 16 dp above it, unless it is [grouped]: then a [CosmoSettingsGroup] holds it and it
 * keeps to the group's width.
 */
@Composable
fun CosmoProfileCard(
    name: String,
    subtitle: String,
    avatarUrl: String?,
    onClick: (() -> Unit)? = null,
    grouped: Boolean = false,
    label: String = "Account",
) {
    val fonts = orbitFonts()
    OrbitAccountCard(
        label = label,
        name = name,
        line = subtitle,
        pictureUrl = avatarUrl,
        fonts = fonts,
        onClick = onClick,
        modifier = if (grouped) Modifier else Modifier.padding(start = CosmoSettingsInsets.side, end = CosmoSettingsInsets.side, top = CosmoSettingsInsets.side),
        compact = true,
    )
}
