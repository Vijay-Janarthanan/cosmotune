package com.cosmotune.ui.orbit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.cosmotune.design.HomePlan
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingShelfItemMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.archivo_variable
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.music.ui.components.SignInBanner
import com.cosmotune.music.ui.components.feedMoreSkeleton
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
import com.cosmotune.browse.BrowseSplit
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.room.LocalRoomLauncher
import com.cosmotune.ui.room.RoomLauncher
import com.cosmotune.ui.theme.CoverPaletteLoader
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.cosmoFontFamilies
import org.jetbrains.compose.resources.Font
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/*
 * The Orbit kit (Canvas repo, docs/superpowers/plans/2026-11-06-cosmotune-orbit-revamp.md): the pieces every Orbit screen
 * is built from, so no screen draws its own card, disc, ring, chip or row. A screen that needs a new piece adds it here.
 */

// ---- the look -----------------------------------------------------------------------------------------------------------------

val PEACH = Color(0xFFFFB98F)
val PEACH_INK = Color(0xFFC9663A)
internal val ROOM_FILL = Color(0xFF1F1C3D)
internal val ROOM_INK = Color(0xFFECE8FF)
internal val ROOM_DIM = Color(0xFFA9A3E6)
internal val ROOM_TEXT = Color(0xFFC9C4F2)
val LIVE = Color(0xFF7CE3A1)
internal val CARD_FALLBACK = Color(0xFF3A2A2A)
val CARD_INK = Color(0xFFFFEDE2)

class OrbitFonts(val wide: FontFamily, val narrow: FontFamily, val text: FontFamily) {
    val greeting = TextStyle(fontFamily = wide, fontWeight = FontWeight(800), fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val heroTitle = TextStyle(fontFamily = wide, fontWeight = FontWeight(800), fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp)
    val section = TextStyle(fontFamily = wide, fontWeight = FontWeight(800), fontSize = 21.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp)
    val number = TextStyle(fontFamily = narrow, fontWeight = FontWeight(900), fontSize = 58.sp, lineHeight = 52.sp)
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.3.sp)
    val title = TextStyle(fontFamily = text, fontWeight = FontWeight(700), fontSize = 15.sp, lineHeight = 19.sp)
    val body = TextStyle(fontFamily = text, fontWeight = FontWeight(500), fontSize = 13.5.sp, lineHeight = 18.sp)
    val chip = TextStyle(fontFamily = text, fontWeight = FontWeight(700), fontSize = 14.sp)
}

@Composable
fun orbitFonts(): OrbitFonts {
    val wide = Font(Res.font.archivo_variable, FontWeight(800), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(800), FontVariation.width(125f)))
    val narrow = Font(Res.font.archivo_variable, FontWeight(900), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(900), FontVariation.width(62f)))
    val text = cosmoFontFamilies().second
    return remember(wide, narrow, text) { OrbitFonts(FontFamily(wide), FontFamily(narrow), text) }
}

/** The page's own colours: ink and its dim from the theme, peach for what is playing, a quiet fill for chips and rows. */
class OrbitLook(val dark: Boolean, val ink: Color, val dim: Color, val chip: Color, val row: Color, val accent: Color, val ground: Color)

@Composable
fun orbitLook(): OrbitLook = orbitLook(LocalCosmoColors.current)

/** The look for [colors] (a screen that provides its own colours reads them before providing them). */
fun orbitLook(colors: com.cosmotune.ui.theme.CosmoColors): OrbitLook {
    val dark = colors.dark
    val ink = Color(colors.palette.textPrimary)
    return OrbitLook(
        dark = dark,
        ink = ink,
        dim = Color(colors.palette.textSecondary),
        chip = ink.copy(alpha = if (dark) 0.08f else 0.07f),
        row = ink.copy(alpha = if (dark) 0.05f else 0.045f),
        accent = if (dark) PEACH else PEACH_INK,
        ground = Color(colors.palette.backgroundTop),
    )
}

/** One deep colour from a cover and the ink that reads on it; always the dark palette, so the card is deep in both themes. */
class CoverTint(val fill: Color, val ink: Color)

@Composable
fun rememberCoverTint(url: String?): CoverTint? {
    val context = LocalPlatformContext.current
    val follow = LocalCosmoShell.current?.settings?.coverColours?.value != false
    val tint by produceState<CoverTint?>(null, url, follow) {
        value = if (!follow || url == null) null else CoverPaletteLoader.load(context, url, dark = true)?.let { vivid(it.seed) }
    }
    return tint
}

/**
 * The cover's own hue made into a card: saturated and deep enough for light ink to read on it (a near-grey cover stays a quiet
 * charcoal), and the ink a pale tint of the same hue.
 */
fun vivid(argb: Int): CoverTint {
    val c = Color(argb)
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val d = max - min
    val s = if (max == 0f) 0f else d / max
    var h = when {
        d == 0f -> 0f
        max == c.red -> 60f * (((c.green - c.blue) / d) % 6f)
        max == c.green -> 60f * (((c.blue - c.red) / d) + 2f)
        else -> 60f * (((c.red - c.green) / d) + 4f)
    }
    if (h < 0f) h += 360f
    return if (s < 0.14f) CoverTint(Color.hsv(h, s, 0.27f), Color.hsv(h, 0.03f, 0.97f))
    else CoverTint(Color.hsv(h, s.coerceIn(0.58f, 0.86f), 0.40f), Color.hsv(h, 0.10f, 0.99f))
}

/** Deep colours a card wears until its cover's colour is known, picked by the item so a row is not a run of grey. */
val STAND_INS = listOf(0xFF5E2A1C, 0xFF1C4C49, 0xFF2E2758, 0xFF4E3C12, 0xFF44203F, 0xFF1D3656, 0xFF3F4A16).map { Color(it) }

/** The fill and ink of a card for [url]: the stand-in for [key] first, then the cover's colour fading in. */
@Composable
fun coverFill(url: String?, key: String): Pair<Color, Color> {
    val tint = rememberCoverTint(url)
    val fill by animateColorAsState(tint?.fill ?: STAND_INS[Math.floorMod(key.hashCode(), STAND_INS.size)], tween(450), label = "fill")
    return fill to (tint?.ink ?: CARD_INK)
}

// ---- the shapes -----------------------------------------------------------------------------------------------------------------

/** A rounded card with a round bite out of its bottom-end corner: the bite's centre is [inset] in from both edges. */
class BiteShape(private val corner: Dp, private val bite: Dp, private val inset: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val card = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner.toPx()))) }
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - inset.toPx() else inset.toPx()
        val hole = Path().apply { addOval(Rect(Offset(x, size.height - inset.toPx()), bite.toPx())) }
        Outline.Generic(Path.combine(PathOperation.Difference, card, hole))
    }
}

val HeroBite = BiteShape(corner = 34.dp, bite = 41.dp, inset = 46.dp)

@Composable
fun Disc(url: String?, size: Dp, modifier: Modifier = Modifier) {
    CoverImage(url = url, size = size, radius = size / 2, modifier = modifier, shadow = false)
}

/**
 * The orbit round a disc: a faint track, the part already played, and a moon at the current second. With no position to show
 * (a song that is not the playing one) a playing disc's moon circles slowly instead; a paused one shows the track alone.
 */
@Composable
fun OrbitRing(size: Dp, color: Color, progress: (() -> Float?)?, spinning: Boolean, modifier: Modifier = Modifier, stroke: Dp = 2.dp, moon: Dp = 5.dp, moonColor: Color = color) {
    val spin = if (progress == null && spinning) {
        rememberInfiniteTransition(label = "orbit").animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "moon")
    } else null
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        val r = this.size.minDimension / 2 - moon.toPx()
        drawCircle(color.copy(alpha = 0.24f), r, style = Stroke(s))
        val p = progress?.invoke()?.coerceIn(0f, 1f)
        if (p != null && p > 0f) {
            drawArc(color, -90f, 360f * p, false, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2), style = Stroke(s * 1.25f, cap = StrokeCap.Round))
        }
        val at = p ?: spin?.value
        if (at != null) {
            val a = Math.toRadians(360.0 * at - 90.0)
            drawCircle(moonColor, moon.toPx(), center + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
        }
    }
}


internal fun Color.compositeOver(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
/**
 * A chip: 40 dp, ink at 8 %; [selected] makes it solid ink with ground-coloured text (a tab that is showing). [leading] is
 * optional (null: the title alone, padded evenly).
 */
fun OrbitChip(
    title: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)?,
) {
    val text = if (selected) look.ground else look.ink
    Row(
        modifier.height(40.dp).clip(CircleShape).background(if (selected) look.ink else look.chip).clickable(onClick = onClick)
            .padding(start = if (leading != null) 8.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides text) { leading() }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = fonts.chip, color = text, maxLines = 1)
    }
}

// ---- the room -------------------------------------------------------------------------------------------------------------------

@Composable
internal fun OrbitRoomCard(room: RoomLauncher, fonts: OrbitFonts, modifier: Modifier) {
    val live = room.inRoom
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(ROOM_FILL).clickable { room.openRoom() }) {
        // The room as a small system: a disc in the middle and one moon on its orbits per person in it.
        Canvas(Modifier.align(Alignment.TopEnd).offset(x = 64.dp, y = (-52).dp).size(200.dp)) {
            val c = center
            drawCircle(ROOM_INK.copy(alpha = 0.16f), 44.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(ROOM_INK.copy(alpha = 0.16f), 78.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(ROOM_INK.copy(alpha = 0.22f), 20.dp.toPx(), c)
            val people = if (live) room.memberCount.coerceIn(1, 6) else 3
            val colors = listOf(Color(0xFFE9875A), Color(0xFF5B8C6A), Color(0xFF8B6CC9), Color(0xFFD9A441), Color(0xFF4F8FD9), Color(0xFFC96C9A))
            repeat(people) { i ->
                val r = if (i % 2 == 0) 78.dp.toPx() else 44.dp.toPx()
                val a = Math.toRadians(110.0 + i * 47.0)
                drawCircle(colors[i % colors.size].copy(alpha = if (live) 1f else 0.55f), 12.dp.toPx(), c + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
            }
        }
        Column(Modifier.padding(20.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) { Box(Modifier.size(7.dp).clip(CircleShape).background(LIVE)); Spacer(Modifier.width(8.dp)) }
                Text(if (live) "LIVE ROOM · ${room.memberCount}" else "LISTEN TOGETHER", style = fonts.label, color = ROOM_DIM)
            }
            Spacer(Modifier.height(10.dp))
            Text(if (live) "Your room" else "Start a room", style = fonts.section.copy(fontSize = 23.sp), color = ROOM_INK)
            Text(
                if (live) "${room.memberCount} listening with you. Open it to chat, ask for songs or talk."
                else "Friends join with a code and hear the same song in their own app.",
                style = fonts.body, color = ROOM_TEXT, modifier = Modifier.padding(top = 6.dp).widthIn(max = 196.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(34.dp).clip(CircleShape).background(ROOM_INK).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(if (live) "Open room" else "Start", style = fonts.chip.copy(fontSize = 13.sp, fontWeight = FontWeight(800)), color = ROOM_FILL) }
        }
    }
}

// ---- sections -------------------------------------------------------------------------------------------------------------------

@Composable
fun OrbitSectionHeader(title: String, subtitle: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = fonts.section, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

