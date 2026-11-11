package com.cosmotune.ui.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cosmotune.design.TitleArt
import com.cosmotune.music.data.canvas.AlbumGenres
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.archivo_variable
import com.cosmotune.music.sharedui.resources.instrument_serif_italic
import com.cosmotune.music.ui.graphics.argbPixels
import com.cosmotune.music.ui.graphics.forPixelAccess
import com.cosmotune.music.ui.graphics.toImageBitmap
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.Font
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.TimeSource

/*
 * Title art for every album (Canvas repo, design/cosmotune/lyrics-titles, "Title art for every album"): one composable,
 * [AlbumTitleArt], that every place showing an album's title as a designed thing draws from. Which treatment a place gets, the
 * album's face and the colours that keep it readable are decided in `com.cosmotune.design.TitleArt` (pure, tested); this file
 * draws them.
 *
 *  - B, Cover Cut: the title in condensed Archivo caps, filled with the cover itself (the letters are drawn, then the cover over
 *    them with BlendMode.SrcIn), sized to the width.
 *  - C, Voiced type: the album's face from its genre and its cover's brightness, in the cover's own colour.
 *  - D, Arc title: the title and the artist round a disc, with the moon on the ring ([ArcTitle] for a place with its own disc).
 *  - E, Breathing: while the album plays on its page, the width axis steps out and back (never per frame; pre-built instances).
 *  - F, Split stack: the first word wide and heavy, the rest narrow and light in the cover's colour.
 *
 * A (the album's own logotype) does not exist in Apple's catalogue data; see `TitleArt`'s note.
 */

/**
 * What title art is drawn from: the [title], the [cover]'s URL, who made it (the arc reads it) and the [genre] when the caller
 * knows it. With no [genre] the catalogues' answer is looked up ([AlbumGenres], filled by the Apple and JioSaavn lookups), by
 * [title] and then by [album], the release's name where the title is a song's (the song card heads with the song).
 */
@Immutable
class TitleArtAlbum(val title: String, val cover: String?, val artist: String? = null, val genre: String? = null, val album: String? = null)

/** [album]'s genre: its own, else what the catalogues have said about its title or its release, followed as answers come in. */
@Composable
internal fun rememberTitleArtGenre(album: TitleArtAlbum): String? {
    if (album.genre != null) return album.genre
    val version by AlbumGenres.version.collectAsState()
    return remember(version, album.title, album.album, album.artist) {
        AlbumGenres.genreFor(album.title, album.artist) ?: album.album?.let { AlbumGenres.genreFor(it, album.artist) }
    }
}

/** A cover ready for title art: the [image] the letters are cut from and its [stats]. */
class TitleArtCover(val image: ImageBitmap, val stats: TitleArt.CoverStats)

/**
 * The covers' stats, once per cover (an LRU of 64 keyed by URL, as the palette loader keeps palettes; the picture at a URL does
 * not change). The picture itself is Coil's, from the cache the discs already fill, at the 480 px rung of the ladder.
 */
internal object TitleArtCovers {
    private const val PX = 480
    private val stats = object : LinkedHashMap<String, TitleArt.CoverStats>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, TitleArt.CoverStats>) = size > 64
    }

    suspend fun load(context: PlatformContext, url: String): TitleArtCover? {
        val request = ImageRequest.Builder(context).data(url.artworkAt(PX)).size(PX).forPixelAccess().build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap() ?: return null
        synchronized(stats) { stats[url] }?.let { return TitleArtCover(image, it) }
        val read = withContext(Dispatchers.Default) { statsOf(image) } ?: return null
        synchronized(stats) { stats[url] = read }
        return TitleArtCover(image, read)
    }

    /** The stats of [image] from about 4,000 of its pixels, evenly spread. */
    fun statsOf(image: ImageBitmap): TitleArt.CoverStats? {
        val all = image.argbPixels()
        val stride = max(1, all.size / 4_096)
        return TitleArt.coverStats(IntArray(all.size / stride) { all[it * stride] })
    }
}

/**
 * The cover at [url] ready for title art, or null when it cannot be had: for a picture drawn off screen (the lyric share video),
 * which has to hold the cover before its first frame.
 */
suspend fun loadTitleArtCover(context: PlatformContext, url: String): TitleArtCover? =
    runCatching { TitleArtCovers.load(context, url) }.getOrNull()

/** The cover at [url] for title art, or null until it is in (or when there is none). */
@Composable
fun rememberTitleArtCover(url: String?): TitleArtCover? {
    val context = LocalPlatformContext.current
    val cover by produceState<TitleArtCover?>(null, url) {
        value = if (url == null) null else runCatching { TitleArtCovers.load(context, url) }.getOrNull()
    }
    return cover
}

/**
 * An album's title as title art: the treatment [TitleArt.choose] gives it in [place] (or [style] when the place asks for one),
 * drawn in [ink] on [ground] (the colour behind it, which the contrast is kept against), the size fitted to the width from
 * [maxSize] down to [minSize] in at most [maxLines] lines and [maxHeight] (a ring is square, as wide as it can be). [playing]
 * lets it breathe where the place allows (the album page) and reduced motion does not stop it. [progress] puts the arc's moon
 * at the playhead. The title is the node's text for accessibility, a [heading] when it heads its page.
 */
@Composable
fun AlbumTitleArt(
    album: TitleArtAlbum,
    ink: Color,
    ground: Color,
    modifier: Modifier = Modifier,
    place: TitleArt.Place = TitleArt.Place.HERO,
    style: TitleArt.Style? = null,
    playing: Boolean = false,
    align: Alignment.Horizontal = Alignment.Start,
    maxSize: TextUnit = 56.sp,
    minSize: TextUnit = 16.sp,
    maxLines: Int = 3,
    maxHeight: Dp = 120.dp,
    heading: Boolean = false,
    progress: (() -> Float?)? = null,
) {
    val cover = rememberTitleArtCover(album.cover)
    AlbumTitleArt(album, cover, ink, ground, modifier, place, style, playing, align, maxSize, minSize, maxLines, maxHeight, heading, progress)
}

/**
 * [AlbumTitleArt] with its [cover] already in hand (null: not yet, or none); the render tests draw through this, and hold a
 * breathing title at [breathStep].
 */
@Composable
internal fun AlbumTitleArt(
    album: TitleArtAlbum,
    cover: TitleArtCover?,
    ink: Color,
    ground: Color,
    modifier: Modifier = Modifier,
    place: TitleArt.Place = TitleArt.Place.HERO,
    style: TitleArt.Style? = null,
    playing: Boolean = false,
    align: Alignment.Horizontal = Alignment.Start,
    maxSize: TextUnit = 56.sp,
    minSize: TextUnit = 16.sp,
    maxLines: Int = 3,
    maxHeight: Dp = 120.dp,
    heading: Boolean = false,
    progress: (() -> Float?)? = null,
    breathStep: Int? = null,
) {
    val stats = cover?.stats
    val voice = TitleArt.voice(rememberTitleArtGenre(album), stats?.brightness, stats?.saturation)
    val chosen = style ?: TitleArt.choose(album.title, place, voice)
    val breathes = TitleArt.breathes(place, chosen, voice, playing, LocalReducedMotion.current)
    val semantics = Modifier.semantics {
        text = AnnotatedString(album.title)
        if (heading) heading()
    }
    if (chosen == TitleArt.Style.ARC) {
        ArcTitleArt(album, cover, ink, ground, modifier.then(semantics), maxHeight, progress)
        return
    }
    val faces = titleFaces(chosen, voice, stats?.brightness, breathes)
    val step = if (!breathes) 0 else breathStep ?: rememberBreathStep()
    BlockTitleArt(album.title, chosen, voice, faces, step, cover, ink, ground, align, maxSize, minSize, maxLines, maxHeight, modifier.then(semantics))
}

// ---- the faces --------------------------------------------------------------------------------------------------------------------

/** One line's face along its breath: a family per step (one when it does not breathe), and the step it is widest at. */
internal class FaceSteps(val families: List<FontFamily>, val weight: Int, val widest: Int)

/**
 * The faces a treatment draws in: [first] for the whole of Cover Cut and Voiced type and the first line of the Split stack,
 * [rest] for the stack's other words. [caps], [tracking] (em) and [italic] are the face's own.
 */
internal class TitleFaces(val first: FaceSteps, val rest: FaceSteps, val caps: Boolean, val tracking: Float, val italic: Boolean = false)

@Composable
private fun archivoSteps(weight: Int, rest: Float, toward: Float, steps: Int): FaceSteps {
    val families = List(steps) { i ->
        key(weight, rest, toward, i) {
            val width = if (steps == 1) rest else TitleArt.breathWidth(rest, toward, i)
            val font = Font(Res.font.archivo_variable, FontWeight(weight), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)))
            remember(font) { FontFamily(font) }
        }
    }
    return remember(families, weight) { FaceSteps(families, weight, if (toward > rest) steps - 1 else 0) }
}

@Composable
internal fun titleFaces(style: TitleArt.Style, voice: TitleArt.Voice?, brightness: Float?, breathes: Boolean): TitleFaces {
    val steps = if (breathes) TitleArt.BREATH_STEPS else 1
    return when (style) {
        // The board's stack: the first word at width 125 and weight 900, the rest at 62 and 300; they breathe towards each other.
        TitleArt.Style.SPLIT -> {
            val first = archivoSteps(900, 125f, 100f, steps)
            val rest = archivoSteps(300, 62f, 90f, steps)
            remember(first, rest) { TitleFaces(first, rest, caps = true, tracking = -0.01f) }
        }
        TitleArt.Style.VOICED -> {
            val v = voice ?: TitleArt.Voice.WIDE
            val weight = TitleArt.voiceWeight(v, brightness)
            val face = when (v) {
                TitleArt.Voice.SERIF -> {
                    val font = Font(Res.font.instrument_serif_italic, FontWeight(400), FontStyle.Italic)
                    remember(font) { FaceSteps(listOf(FontFamily(font)), 400, 0) }
                }
                TitleArt.Voice.CONDENSED -> archivoSteps(weight, 62f, 90f, steps)
                TitleArt.Voice.WIDE -> archivoSteps(weight, 125f, 100f, steps)
                TitleArt.Voice.MONO -> remember(weight) { FaceSteps(listOf(FontFamily.Monospace), weight, 0) }
            }
            val tracking = when (v) {
                TitleArt.Voice.MONO -> 0.06f
                TitleArt.Voice.SERIF -> 0f
                else -> -0.01f
            }
            remember(face, v) { TitleFaces(face, face, caps = TitleArt.voiceCaps(v), tracking = tracking, italic = v == TitleArt.Voice.SERIF) }
        }
        // Cover Cut: the board's condensed caps (width 62, weight 900), breathing out to 96.
        else -> {
            val cut = archivoSteps(900, 62f, 96f, steps)
            remember(cut) { TitleFaces(cut, cut, caps = true, tracking = 0f) }
        }
    }
}

/**
 * The breath's step while the title is composed. It sleeps until the step next changes (a few times a second at most), so a
 * breathing title recomposes once per step and asks for no frames in between.
 */
@Composable
private fun rememberBreathStep(): Int {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val now = start.elapsedNow().inWholeMilliseconds
            step = TitleArt.breathStep(now)
            var next = now + 10
            while (TitleArt.breathStep(next) == step && next - now < TitleArt.BREATH_MS) next += 10
            delay(next - now)
        }
    }
    return step
}

private fun TitleFaces.style(first: Boolean, step: Int, sizePx: Float, density: Float, align: TextAlign, text: String): TextStyle {
    val face = if (first) this.first else rest
    val sp = (sizePx / density).sp
    return TextStyle(
        fontFamily = face.families[step.coerceIn(0, face.families.size - 1)],
        fontWeight = FontWeight(face.weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontSynthesis = FontSynthesis.None,
        fontSize = sp,
        lineHeight = (sizePx * TitleArt.lineHeight(text) / density).sp,
        letterSpacing = tracking.em,
        textAlign = align,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

// ---- B, C, F: a block of type -----------------------------------------------------------------------------------------------------

/** What a block of title art lays out to: its lines' layouts (one per breath step) and how tall it stands. */
private class BlockPlan(val first: List<TextLayoutResult>, val rest: List<TextLayoutResult>?, val gap: Float, val height: Int, val sizePx: Float)

/** The vertical room a line of caps keeps above and below for accents and descenders, as a fraction of the size. */
private const val PAD = 0.08f

@Composable
private fun BlockTitleArt(
    title: String,
    style: TitleArt.Style,
    voice: TitleArt.Voice?,
    faces: TitleFaces,
    step: Int,
    cover: TitleArtCover?,
    ink: Color,
    ground: Color,
    align: Alignment.Horizontal,
    maxSize: TextUnit,
    minSize: TextUnit,
    maxLines: Int,
    maxHeight: Dp,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val density = LocalDensity.current
    val textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else if (align == Alignment.End) TextAlign.End else TextAlign.Start
    BoxWithConstraints(modifier) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else with(density) { 360.dp.roundToPx() }
        val capHeight = with(density) { maxHeight.toPx() }.let { if (constraints.hasBoundedHeight) min(it, constraints.maxHeight.toFloat()) else it }
        val maxPx = with(density) { maxSize.toPx() }
        val minPx = with(density) { minSize.toPx() }.coerceAtMost(maxPx)
        val plan = remember(title, style, faces, width, capHeight, maxPx, minPx, maxLines, textAlign, density) {
            planBlock(measurer, title, style, faces, width, capHeight, maxPx, minPx, maxLines, textAlign, density.density)
        }
        val stats = cover?.stats
        val target = TitleArt.target(plan.sizePx / density.density)
        val wash = if (stats == null) 1f else TitleArt.wash(stats, ink.toArgb(), ground.toArgb(), target)
        // The cover comes in under the letters as the wash lifts off them: a title never shows unfilled outlines.
        val shownWash by animateFloatAsState(if (cover == null) 1f else wash, tween(420), label = "titleWash")
        val tint = Color(TitleArt.tint(stats, ink.toArgb(), ground.toArgb(), target))
        val cut = style == TitleArt.Style.CUT
        Canvas(
            Modifier.width(with(density) { width.toDp() }).height(with(density) { plan.height.toDp() })
                .graphicsLayer { if (cut) compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val firstLayout = plan.first[step.coerceIn(0, plan.first.size - 1)]
            val top = plan.sizePx * PAD
            when (style) {
                TitleArt.Style.CUT -> {
                    drawText(firstLayout, color = ink, topLeft = Offset(0f, top))
                    if (cover != null) {
                        drawCoverInto(cover.image, textBounds(firstLayout, top))
                        if (shownWash > 0f) drawRect(ink.copy(alpha = shownWash), blendMode = BlendMode.SrcAtop)
                    }
                }
                TitleArt.Style.SPLIT -> {
                    drawText(firstLayout, color = ink, topLeft = Offset(0f, top))
                    val rest = plan.rest?.let { it[step.coerceIn(0, it.size - 1)] }
                    if (rest != null) drawText(rest, color = tint, topLeft = Offset(0f, top + firstLayout.size.height + plan.gap))
                }
                else -> drawText(firstLayout, color = if (voice == null) ink else tint, topLeft = Offset(0f, top))
            }
        }
    }
}

/** The box the glyphs of [layout] (drawn [top] down) stand in, the box the cover is fitted to. */
private fun textBounds(layout: TextLayoutResult, top: Float): Rect {
    var left = Float.MAX_VALUE
    var right = 0f
    for (line in 0 until layout.lineCount) {
        left = min(left, layout.getLineLeft(line))
        right = max(right, layout.getLineRight(line))
    }
    if (left > right) return Rect(0f, top, layout.size.width.toFloat(), top + layout.size.height)
    return Rect(left, top, right, top + layout.size.height)
}

/** [image] over what is drawn, cropped to fill [box] from its middle (as a disc crops), kept only where letters are (SrcIn). */
private fun DrawScope.drawCoverInto(image: ImageBitmap, box: Rect) {
    val boxAspect = box.width / box.height.coerceAtLeast(1f)
    val imageAspect = image.width.toFloat() / image.height.coerceAtLeast(1)
    val (sw, sh) = if (imageAspect > boxAspect) (image.height * boxAspect) to image.height.toFloat() else image.width.toFloat() to (image.width / boxAspect)
    val sx = ((image.width - sw) / 2f).roundToInt().coerceAtLeast(0)
    val sy = ((image.height - sh) / 2f).roundToInt().coerceAtLeast(0)
    drawImage(
        image,
        srcOffset = IntOffset(sx, sy),
        srcSize = IntSize(sw.roundToInt().coerceIn(1, image.width - sx), sh.roundToInt().coerceIn(1, image.height - sy)),
        dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
        dstSize = IntSize(box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1)),
        blendMode = BlendMode.SrcIn,
    )
}

/**
 * Lays out a block: the largest size that fits the width (no word broken), [maxLines] lines and [capHeight], measured in the
 * widest of the breath's instances so the block never grows as it breathes. A word too wide at [minPx] takes the size down to 60 %
 * of it; a title that still does not fit is cut short.
 */
private fun planBlock(
    measurer: TextMeasurer,
    title: String,
    style: TitleArt.Style,
    faces: TitleFaces,
    width: Int,
    capHeight: Float,
    maxPx: Float,
    minPx: Float,
    maxLines: Int,
    align: TextAlign,
    density: Float,
): BlockPlan {
    val upper = { s: String -> if (faces.caps) s.uppercase() else s }
    val (firstText, restText) = if (style == TitleArt.Style.SPLIT) TitleArt.split(title).let { upper(it.first) to it.second } else upper(title.trim()) to ""
    val firstLines = if (style == TitleArt.Style.SPLIT) 1 else maxLines
    val restLines = (maxLines - 1).coerceAtLeast(1)
    val widest = faces.first.widest
    val restWidest = faces.rest.widest

    fun measure(text: String, first: Boolean, step: Int, size: Float, lines: Int): TextLayoutResult = measurer.measure(
        AnnotatedString(text), faces.style(first, step, size, density, align, text), TextOverflow.Ellipsis, softWrap = lines > 1, maxLines = lines,
        constraints = Constraints.fixedWidth(width), density = androidx.compose.ui.unit.Density(density), skipCache = false,
    )
    // No word is ever broken: the widest word, measured once and scaled with the size (the tracking is in em, so it scales too).
    val reference = 100f
    fun widestWord(text: String, first: Boolean): Float = TitleArt.words(text).ifEmpty { listOf(text) }.maxOf { word ->
        measurer.measure(AnnotatedString(word), faces.style(first, if (first) widest else restWidest, reference, density, align, word), maxLines = 1, softWrap = false,
            density = androidx.compose.ui.unit.Density(density)).size.width.toFloat()
    }
    val firstWord = widestWord(firstText, true)
    val restWord = if (restText.isNotEmpty()) widestWord(restText, false) else 0f
    // And the line breaker's own word: a line that ends inside a word (not at a space or after a hyphen) means the size is too big.
    fun brokeWord(layout: TextLayoutResult, text: String): Boolean = (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        end in 1 until text.length && !text[end - 1].isWhitespace() && text[end - 1] != '-' && !text[end].isWhitespace()
    }
    fun pad(size: Float) = size * PAD * 2
    fun gap(size: Float) = size * 0.04f

    // The stack's other words come down in size before they would break or overflow, rather than shrinking the first word too;
    // they are never larger than the first.
    val restMax = if (restText.isEmpty()) maxPx else TitleArt.fitSize(minPx, maxPx) { size ->
        restWord * size / reference <= width && measure(restText, false, restWidest, size, restLines).let { !it.hasVisualOverflow && !brokeWord(it, restText) }
    }

    fun fits(size: Float): Boolean {
        if (firstWord * size / reference > width) return false
        val a = measure(firstText, true, widest, size, firstLines)
        if (a.hasVisualOverflow || brokeWord(a, firstText)) return false
        var h = a.size.height + pad(size)
        if (restText.isNotEmpty()) h += measure(restText, false, restWidest, min(size, restMax), restLines).size.height + gap(size)
        return h <= capHeight
    }

    // A word too long for the width even at the floor goes under it, down to 60 % of it, rather than be broken.
    val size = TitleArt.fitSize(minPx, maxPx, ::fits).let { if (fits(it)) it else TitleArt.fitSize(minPx * 0.6f, minPx, ::fits) }
    val restPx = min(size, restMax)
    // Every step of a breath keeps the line breaks of its widest step: narrower, the lines still fit, and the title never reflows.
    fun steps(text: String, first: Boolean, count: Int, wideAt: Int, px: Float, lines: Int): List<TextLayoutResult> {
        val wide = measure(text, first, wideAt, px, lines)
        if (count == 1 || wide.hasVisualOverflow) return List(count) { wide }
        val broken = (0 until wide.lineCount).joinToString("\n") { line ->
            text.substring(wide.getLineStart(line), wide.getLineEnd(line, visibleEnd = true)).trim()
        }
        return List(count) { step ->
            if (step == wideAt) wide else measurer.measure(
                AnnotatedString(broken), faces.style(first, step, px, density, align, broken), TextOverflow.Clip, softWrap = false,
                maxLines = wide.lineCount, constraints = Constraints.fixedWidth(width), density = androidx.compose.ui.unit.Density(density),
            )
        }
    }
    val first = steps(firstText, true, faces.first.families.size, widest, size, firstLines)
    val rest = if (restText.isNotEmpty()) steps(restText, false, faces.rest.families.size, restWidest, restPx, restLines) else null
    val height = first[widest].size.height + pad(size) + (rest?.get(restWidest)?.size?.height?.plus(gap(size)) ?: 0f)
    return BlockPlan(first, rest, gap(size), kotlin.math.ceil(height).toInt(), size)
}

// ---- D: the arc -------------------------------------------------------------------------------------------------------------------

/** The arc's words: the title and who made it, in capitals, with the separator after each so the ring reads round. */
fun arcText(title: String, artist: String?): String =
    listOfNotNull(title.trim().takeIf { it.isNotEmpty() }, artist?.trim()?.takeIf { it.isNotEmpty() })
        .joinToString(" · ", postfix = " · ").uppercase()

/** The face of the arc: Archivo at weight 800 and its normal width, as the board sets it, tracked open. */
@Composable
private fun arcFamily(): FontFamily {
    val font = Font(Res.font.archivo_variable, FontWeight(800), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(800), FontVariation.width(100f)))
    return remember(font) { FontFamily(font) }
}

/** One cluster of the arc (a letter with its marks) laid out on its own, and the angle (radians) its middle sits at. */
private class ArcGlyph(val layout: TextLayoutResult, val angle: Float)

/**
 * The clusters of [text] in [family] at [sizePx], set round a circle of [radius] with their middles centred over the top. Too
 * long for the ring, the size comes down to 70 %, then the artist is left off, and only then is the title cut short with an
 * ellipsis; nothing ever overlaps.
 */
private fun layoutArc(measurer: TextMeasurer, text: String, family: FontFamily, sizePx: Float, radius: Float, density: Float): List<ArcGlyph> {
    val clusters = buildList {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            add(text.substring(start, end))
            start = end
            end = it.next()
        }
    }
    if (clusters.isEmpty() || radius <= 0f) return emptyList()
    // A tenth of the ring stays clear at its foot, where the moon rests.
    val room = (2 * PI * radius).toFloat() * 0.9f
    fun style(size: Float) = TextStyle(
        fontFamily = family, fontWeight = FontWeight(800), fontSynthesis = FontSynthesis.None, fontSize = (size / density).sp, letterSpacing = 0.sp,
    )
    fun measure(cluster: String, size: Float) = measurer.measure(
        AnnotatedString(cluster), style(size), maxLines = 1, softWrap = false, density = androidx.compose.ui.unit.Density(density),
    )
    // Latin and CJK are tracked open, as the board sets the ring; a script whose letters join (Tamil, Devanagari) sits close.
    val tracking = { size: Float -> size * (if (TitleArt.tallScript(text)) 0.02f else 0.16f) }
    var size = sizePx
    var layouts = clusters.map { measure(it, size) }
    fun run() = layouts.sumOf { it.size.width.toDouble() }.toFloat() + tracking(size) * layouts.size
    if (run() > room) {
        size = max(sizePx * 0.7f, sizePx * room / run())
        layouts = clusters.map { measure(it, size) }
    }
    // Still too long: the artist goes before the title is cut short.
    val segments = text.split(" · ").filter { it.isNotBlank() }
    if (run() > room && segments.size > 1) {
        val shorter = segments.first() + " · "
        return layoutArc(measurer, shorter, family, sizePx, radius, density)
    }
    if (run() > room) {
        val dots = measure("…", size)
        val kept = ArrayList<TextLayoutResult>()
        var used = dots.size.width + tracking(size) * 2
        for (l in layouts) {
            val w = l.size.width + tracking(size)
            if (used + w > room) break
            kept += l
            used += w
        }
        // End on a letter, not on the separator or a space.
        while (kept.isNotEmpty() && kept.last().layoutInput.text.text.let { it.isBlank() || it == "·" }) kept.removeAt(kept.size - 1)
        layouts = kept + dots
    }
    val sweep = run() / radius
    var at = (-PI / 2).toFloat() - sweep / 2f
    return layouts.map { l ->
        val w = (l.size.width + tracking(size)) / radius
        val glyph = ArcGlyph(l, at + w / 2f)
        at += w
        glyph
    }
}

/** Draws [glyphs] round [center], each upright to the circle, its middle on the circle. */
private fun DrawScope.drawArcGlyphs(glyphs: List<ArcGlyph>, center: Offset, radius: Float, ink: Color) {
    for (g in glyphs) {
        val degrees = Math.toDegrees(g.angle.toDouble()).toFloat() + 90f
        rotate(degrees, pivot = center) {
            drawText(g.layout, color = ink, topLeft = Offset(center.x - g.layout.size.width / 2f, center.y - radius - g.layout.size.height / 2f))
        }
    }
}

/**
 * The arc on its own, for a place that draws its own disc and orbit (the mini player, the TV): [text] (see [arcText]) round a
 * circle of [radius] from the middle of the box, in type [size] high, in [ink]. Decoration only: those places name the song in
 * plain text too, so the arc says nothing to a screen reader.
 */
@Composable
fun ArcTitle(text: String, radius: Dp, size: TextUnit, ink: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val family = arcFamily()
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val sizePx = with(density) { size.toPx() }
    val glyphs = remember(text, family, radiusPx, sizePx, density) { layoutArc(measurer, text, family, sizePx, radiusPx, density.density) }
    Canvas(modifier) {
        drawArcGlyphs(glyphs, center, radiusPx, ink)
    }
}

/**
 * D whole, where no disc stands already (the share video's end card, the renders): the cover as a disc, the title and the
 * artist round it on the orbit's faint track, and the moon on the track at [progress] (at rest in the clear gap at the foot
 * of the ring when there is none). Square, as wide as it can be up to [maxHeight].
 */
@Composable
private fun ArcTitleArt(album: TitleArtAlbum, cover: TitleArtCover?, ink: Color, ground: Color, modifier: Modifier, maxHeight: Dp, progress: (() -> Float?)?) {
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val family = arcFamily()
    val density = LocalDensity.current
    val text = arcText(album.title, album.artist)
    BoxWithConstraints(modifier) {
        val side = min(if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else with(density) { 260.dp.toPx() }, with(density) { maxHeight.toPx() })
        // The board's proportions on a 260 box: the disc 74 across the middle, the words and the track on 96, type 17 high.
        val radius = side * 96f / 260f
        val disc = side * 74f / 260f
        val sizePx = side * 17f / 260f
        val glyphs = remember(text, family, radius, sizePx, density) { layoutArc(measurer, text, family, sizePx, radius, density.density) }
        val tint = Color(TitleArt.tint(cover?.stats, ink.toArgb(), ground.toArgb(), 3.0))
        Canvas(Modifier.size(with(density) { side.toDp() })) {
            val c = center
            val circle = Path().apply { addOval(Rect(c, disc)) }
            if (cover != null) {
                clipPath(circle) {
                    val s = min(cover.image.width, cover.image.height)
                    drawImage(
                        cover.image,
                        srcOffset = IntOffset((cover.image.width - s) / 2, (cover.image.height - s) / 2), srcSize = IntSize(s, s),
                        dstOffset = IntOffset((c.x - disc).roundToInt(), (c.y - disc).roundToInt()), dstSize = IntSize((disc * 2).roundToInt(), (disc * 2).roundToInt()),
                    )
                }
            } else {
                drawCircle(tint.copy(alpha = 0.3f), disc, c)
            }
            drawCircle(ink.copy(alpha = 0.14f), radius, c, style = Stroke(max(1f, side / 260f)))
            drawArcGlyphs(glyphs, c, radius, ink)
            val p = progress?.invoke()?.coerceIn(0f, 1f)
            val a = if (p == null) PI / 2 else Math.toRadians(360.0 * p - 90.0)
            drawCircle(PEACH, side * 5f / 260f, c + Offset((radius * cos(a)).toFloat(), (radius * sin(a)).toFloat()))
        }
    }
}

// ---- the share video's end card ---------------------------------------------------------------------------------------------------

/**
 * The title for the lyric share video's end card (the board's placement table: "Title art on the end card"): the album's title
 * art centred, as large as the card allows, with who made it under it in the mono label. The share video draws the rest of its
 * end card itself; this is only the title. [cover] is the picture already loaded ([loadTitleArtCover]) when the card is drawn
 * off screen, where nothing can wait for a load; on screen it can be left out and is fetched.
 */
@Composable
fun AlbumTitleEndCard(
    album: TitleArtAlbum,
    ink: Color,
    ground: Color,
    modifier: Modifier = Modifier,
    cover: TitleArtCover? = null,
    maxHeight: Dp = 220.dp,
) {
    val shown = cover ?: rememberTitleArtCover(album.cover)
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AlbumTitleArt(
            album, shown, ink, ground, Modifier.fillMaxWidth(), place = TitleArt.Place.END_CARD, align = Alignment.CenterHorizontally,
            maxSize = 84.sp, minSize = 28.sp, maxLines = 3, maxHeight = maxHeight, heading = true,
        )
        val artist = album.artist?.trim().orEmpty()
        if (artist.isNotEmpty()) {
            androidx.compose.material3.Text(
                artist.uppercase(), style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 3.sp),
                color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
