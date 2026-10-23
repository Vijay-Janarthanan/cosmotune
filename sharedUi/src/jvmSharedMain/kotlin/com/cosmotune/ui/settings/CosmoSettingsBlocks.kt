package com.cosmotune.ui.settings

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
 * The settings pages in the new look (B: they are part of the page). The names and the order of the parameters are the ones of the
 * phone's `SettingsSheet.kt` functions that delegate to them (`SettingsGroup`, `RowDivider`, `SettingsRow`, ...), so the old
 * screens' call sites keep working; the desktop's four private settings functions delegate to the same blocks.
 *
 * The numbers are `components-v3bc.css:135-144` (`.grp`, `.row2`) and `v2/screens-v2.css:117-124` (`.prof2`, `.grp-h`, the stacked
 * segmented control).
 *
 * A group is a column of rows with a 3 dp gap wherever a [CosmoRowDivider] stands; the group draws one rounded tile behind each run
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
private val ROW_MIN_HEIGHT = 64.dp
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
private val AVATAR_SIZE = 48.dp
private val PROFILE_RADIUS = 26.dp
private val HEADER_TOP = 22.dp
private val HEADER_BOTTOM = 8.dp
private val FOOTER_SIDE = 20.dp
private val FOOTER_TOP = 8.dp
private const val FOCUS_RING = 3f

/** The tile fill is `mix(surfaceHigh, surface, 0.4)` at the top and `surface` at the bottom (`--tile-fill`). */
private const val TILE_TOP_MIX = 0.4f
private const val TITLE_WEIGHT = 650
private const val MINI_SEGMENT_HEIGHT_DP = 32f
private const val SEGMENT_WEIGHT = 640

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

/** Hanken Grotesk at [size] sp and [weight]; the sizes of the settings page are the design's, not the scale's. */
private fun CosmoType.settings(size: Float, weight: Int, lineHeight: Float = 1.3f): TextStyle =
    body(TypeScale.BODY, FontWeight(weight)).copy(fontSize = size.sp, lineHeight = (size * lineHeight).sp)

private fun CosmoType.rowTitle(text: String): TextStyle = forText(settings(15.5f, TITLE_WEIGHT, 1.25f), text)
private fun CosmoType.rowSubtitle(text: String): TextStyle = forText(settings(13f, 400, 1.35f), text)

// ---- the group ------------------------------------------------------------------------------------------------------------------

/**
 * One group of rows: an overline [header] above (12 sp, weight 700, `textSecondary`, as written, not in capitals), a plain-language
 * [footer] below, and between them the tile or tiles that [content] stands on, 16 dp from the sides. Without a header there is
 * [topSpacing] of room above instead. The tiles are the runs of rows between [CosmoRowDivider]s: the first has outer corners 24
 * at the top, the last 24 at the bottom, every other corner is 6, and the gap between two is 3 dp.
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
    val type = LocalCosmoType.current
    val density = LocalDensity.current.density
    val tiles = remember(density) {
        Tiles(
            outerPx = GroupSegments.drawnRadius(GroupSegments.OUTER_RADIUS_DP) * density,
            innerPx = GroupSegments.drawnRadius(GroupSegments.INNER_RADIUS_DP) * density,
        )
    }
    if (header != null) {
        Text(
            text = header,
            style = type.forText(type.settings(12f, 700, 1.3f).copy(letterSpacing = 0.01.em), header),
            color = Color(palette.textSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = CosmoSettingsInsets.side, end = CosmoSettingsInsets.side, top = HEADER_TOP, bottom = HEADER_BOTTOM)
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
                        val base = colors.live(CoverField.SURFACE)
                        val from = Color(ColorMix.mix(colors.live(CoverField.SURFACE_HIGH).toArgb(), base.toArgb(), TILE_TOP_MIX))
                        for (i in paths.indices) {
                            drawPath(paths[i], Brush.verticalGradient(listOf(from, base), startY = segments[i].top, endY = segments[i].bottom))
                        }
                    }
                },
        ) {
            content()
        }
    }
    if (footer != null) {
        Text(
            text = footer,
            style = type.rowSubtitle(footer),
            color = Color(palette.textSecondary),
            modifier = Modifier.fillMaxWidth().padding(start = FOOTER_SIDE, end = FOOTER_SIDE, top = FOOTER_TOP),
        )
    }
}

/**
 * A 3 dp gap between two tiles of a [CosmoSettingsGroup]. It reports where it stands, so the group knows where one tile ends and the
 * next begins; outside a group it is just a 3 dp spacer.
 */
@Composable
fun CosmoRowDivider() {
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        Modifier
            .fillMaxWidth()
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
            if (icon != null || iconPainter != null) LeadTile(icon, iconPainter)
            Column(Modifier.weight(1f)) {
                TitleLine(title, badge)
                if (subtitleContent != null) {
                    subtitleContent()
                } else if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = type.rowSubtitle(subtitle),
                        color = Color(palette.textSecondary),
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
                        style = type.settings(14f, 400),
                        color = Color(palette.textSecondary),
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = type.rowTitle(title),
            color = Color(palette.textPrimary),
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
    val tint = Color(palette.onAccentContainer)
    Box(
        Modifier
            .size(LEAD_SIZE)
            .drawBehind {
                keepRedrawnWith(palette)
                drawPath(morph.update(size.width, size.height, LEAD_RADIUS.toPx(), 4f), tonalFill(colors))
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
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
                    style = type.rowSubtitle(subtitle),
                    color = Color(palette.textSecondary),
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
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth()) {
        CosmoSettingsRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            trailing = {
                Text(text = value, style = type.settings(14f, 400), color = Color(palette.textSecondary), maxLines = 1)
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

/** Sign out: the label centred on its row, 15.5 sp weight 650 in the theme's error colour (the danger red), 52 dp at least. */
@Composable
fun CosmoDestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val type = LocalCosmoType.current
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
            style = type.rowTitle(label),
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA },
        )
    }
}

/** A small pill after a title (Blocking, Connected): 18 dp high, `accentContainer` with `onAccentContainer` capitals, 11.5 sp weight 700. */
@Composable
fun CosmoBadge(text: String) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val label = remember(text) { text.uppercase() }
    Box(
        Modifier
            .height(BADGE_HEIGHT)
            .drawBehind {
                keepRedrawnWith(palette)
                drawRoundRect(colors.live(CoverField.ACCENT_CONTAINER), cornerRadius = CornerRadius(size.height / 2f))
            }
            .padding(horizontal = BADGE_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.settings(11.5f, 700, 1.1f),
            color = Color(palette.onAccentContainer),
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
 * The segmented control of a settings page (theme, quality, refresh rate, colour intensity): the B segments of the B+C system (3 dp
 * apart, the outer ends pills, the inner corners 6 dp that morph to 16 dp while pressed, the selected segment a tonal pill) filling
 * the width it is given, each option an equal share (`.row2.stack .seg2 > button { flex: 1 }`), so that four or five options still fit.
 * [compact] is the small one (`.mini-seg`: 32 dp tall, 13 sp, no check mark), for a control that stands under a row. Disabled it is
 * drawn at 38 % and takes no input.
 *
 * `CosmoSegmented` itself is 40 dp tall with segments as wide as their labels, so it cannot do either; this draws the same segments
 * from the same numbers and helpers.
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
    val height = if (compact) MINI_SEGMENT_HEIGHT_DP else ControlMetrics.SEGMENT_HEIGHT_DP
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ControlMetrics.SEGMENT_GAP_DP.dp),
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
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val target = ControlMorph.segment(heightDp, selected, pressed && enabled)
    val inner = remember { Animatable(target.radiusDp) }
    val layer = remember { Animatable(0f) }
    val layerTarget = if (!enabled) 0f else if (pressed) STATE_LAYER_PRESSED else if (hovered) STATE_LAYER_HOVERED else 0f
    LaunchedEffect(target.radiusDp, reduced) { inner.animateTo(target.radiusDp, CosmoMotion.press(reduced)) }
    LaunchedEffect(layerTarget, reduced) { layer.animateTo(layerTarget, CosmoMotion.effects(reduced)) }
    val corners = remember { FloatArray(4) }
    val outline = remember { Path() }
    val ink = Color(if (selected) palette.onAccentContainer else palette.textSecondary)
    val outer = heightDp / 2f
    val check = ControlMetrics.settingsSegmentShowsCheck(selected, compact, count)
    val textStyle = type.forText(
        type.label.copy(fontWeight = FontWeight(SEGMENT_WEIGHT), fontSize = ControlMetrics.settingsSegmentLabelMaxSp(compact).sp, color = ink),
        label,
    )
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(heightDp.dp)
            .cosmoFocusRing(interaction, outer.dp)
            .drawBehind {
                keepRedrawnWith(palette)
                ControlMetrics.segmentCorners(index, count, inner.value, corners, outer)
                outline.reset()
                outline.addRoundRect(
                    RoundRect(
                        0f, 0f, size.width, size.height,
                        topLeftCornerRadius = CornerRadius(corners[0].dp.toPx()), topRightCornerRadius = CornerRadius(corners[1].dp.toPx()),
                        bottomRightCornerRadius = CornerRadius(corners[2].dp.toPx()), bottomLeftCornerRadius = CornerRadius(corners[3].dp.toPx()),
                    ),
                )
                if (selected) drawPath(outline, tonalFill(colors)) else drawPath(outline, SolidColor(colors.ink(ControlMetrics.SEGMENT_REST_FILL)))
                if (layer.value > 0.001f) drawPath(outline, ink.copy(alpha = layer.value))
                if (selected) topLine(outline, Color.White.copy(alpha = ControlMetrics.HIGHLIGHT_TONAL))
                else innerRing(outline, colors.ink(ControlMetrics.OUTLINE), 1f)
            }
            .hoverable(interaction, enabled)
            .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.Tab) { haptics.play(Haptic.Select); onSelect() }
            // The segment shares its row and its label is centred, so these ends only show when the label is long (see
            // ControlMetrics.settingsSegmentLabelRoomDp).
            .padding(horizontal = ControlMetrics.SEGMENT_FILL_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(ControlMetrics.SEGMENT_CHECK_GAP_DP.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (check) Icon(CosmoIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(ControlMetrics.SEGMENT_ICON_DP.dp))
        BasicText(
            text = label,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = if (compact) COMPACT_SEGMENT_FIT else SEGMENT_FIT,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ---- the profile card -------------------------------------------------------------------------------------------------------------------

/**
 * Who is signed in, at the top of the settings (`.prof2`): a 48 dp avatar, the [name] (17 sp, weight 700), the [subtitle] on one line
 * and a chevron when the card opens something. It is a tile of the page (the tile fill, superellipse 26), 16 dp from the sides with
 * 16 dp above it, unless it is [grouped]: then a [CosmoSettingsGroup] already supplies the tile and the card draws none.
 */
@Composable
fun CosmoProfileCard(
    name: String,
    subtitle: String,
    avatarUrl: String?,
    onClick: (() -> Unit)? = null,
    grouped: Boolean = false,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val morph = remember { MorphPath() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (grouped) {
                    Modifier
                } else {
                    Modifier
                        .padding(start = CosmoSettingsInsets.side, end = CosmoSettingsInsets.side, top = CosmoSettingsInsets.side)
                        .drawBehind {
                            keepRedrawnWith(palette)
                            val base = colors.live(CoverField.SURFACE)
                            val from = Color(ColorMix.mix(colors.live(CoverField.SURFACE_HIGH).toArgb(), base.toArgb(), TILE_TOP_MIX))
                            drawPath(
                                morph.update(size.width, size.height, PROFILE_RADIUS.toPx(), 4f),
                                Brush.verticalGradient(listOf(from, base), startY = 0f, endY = size.height),
                            )
                        }
                },
            )
            .then(if (onClick != null) Modifier.tileClickable(onClick, true, Role.Button, squircleDp = if (grouped) 0f else PROFILE_RADIUS.value) else Modifier)
            .padding(horizontal = ROW_PADDING_H, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarUrl)
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = type.forText(type.settings(17f, 700, 1.25f), name),
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = type.rowSubtitle(subtitle),
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) CosmoChevron()
    }
}

@Composable
private fun Avatar(url: String?) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    Box(
        Modifier
            .size(AVATAR_SIZE)
            .clip(CircleShape)
            .drawBehind {
                keepRedrawnWith(palette)
                drawRect(colors.live(CoverField.SURFACE_VARIANT))
            }
            .thumbnailBorder(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(AVATAR_SIZE))
        } else {
            Icon(CosmoIcons.User, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(LEAD_ICON))
        }
    }
}
