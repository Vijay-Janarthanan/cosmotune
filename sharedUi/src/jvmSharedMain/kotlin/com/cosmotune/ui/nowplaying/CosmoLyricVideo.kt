package com.cosmotune.ui.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.cosmotune.lyrics.styles.LyricArc
import com.cosmotune.lyrics.styles.LyricCursor
import com.cosmotune.lyrics.styles.StageLine
import com.cosmotune.music.data.lyrics.LyricLine

/**
 * How the lyric share video ("Save as video") is drawn: one of the board's share styles (Poster, Stretch, Orbital), or the
 * classic layout each platform has drawn until now (the lines over the darkened cover).
 */
enum class LyricVideoLook(val title: String) {
    POSTER("Poster"),
    STRETCH("Stretch"),
    ORBITAL("Orbital"),
    CLASSIC("Classic"),
    ;

    /** The look after this one, for a button that steps through them. */
    fun next(): LyricVideoLook = entries[(ordinal + 1) % entries.size]
}

/**
 * The share video's frames in one of the styles, drawn with Compose's own canvas into a bitmap: the same drawing as the stages on
 * screen (the scenes and the draw functions are theirs), so a saved video looks like the lyrics did. Works off any window, on
 * the phone and the desktop alike; each platform reads the pixels and encodes them with its own encoder.
 *
 * [fonts] and [resolver] come from a composition (the share sheet's), where the bundled fonts are loaded. A frame is
 * [WIDTH] by [HEIGHT], the 9:16 of a story. Not thread-safe: one exporter draws its frames in order.
 */
class LyricVideoFrames(
    val look: LyricVideoLook,
    private val lines: List<LyricLine>,
    private val song: LyricStageSong,
    private val fonts: LyricStageFonts,
    resolver: FontFamily.Resolver,
    /** The cover's deep colour and its pale ink (a colour card's pair). */
    private val coverFill: Color,
    private val coverInk: Color,
    private val cover: ImageBitmap?,
) {
    private val density = Density(1f)
    private val measurer = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = 96)
    private val bitmap = ImageBitmap(WIDTH, HEIGHT)
    private val canvas = Canvas(bitmap)
    private val scope = CanvasDrawScope()
    private val size = Size(WIDTH.toFloat(), HEIGHT.toFloat())
    private val ink = StageInk(
        text = coverInk,
        unlit = coverInk.copy(alpha = 0.26f),
        next = coverInk.copy(alpha = 0.62f),
        faint = coverInk.copy(alpha = 0.16f),
        accent = lerp(coverInk, Color.White, 0.55f),
        ground = coverFill,
        dark = true,
    )

    private val pad = 56f
    private val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, letterSpacing = 2.6.sp)
    private val label: TextLayoutResult = measurer.measure(
        posterLabel(song), labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (WIDTH - 2 * pad).toInt()),
    )
    private val brand: TextLayoutResult = measurer.measure("COSMOTUNE", labelStyle)
    private val title: TextLayoutResult = measurer.measure(
        song.title, stageStyle(fonts.orbital, StageWeights.ORBITAL, 40f, density, 1.1f).copy(textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (WIDTH - 2 * pad).toInt()),
    )
    private val artist: TextLayoutResult = measurer.measure(
        song.artist, stageTextStyle(fonts, 28f, density, 500).copy(textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (WIDTH - 2 * pad).toInt()),
    )
    private val rings = LyricArc.rings(WIDTH - 40f, dp = 2f)
    private val disc = Path().apply { addOval(Rect(Offset(WIDTH / 2f, ORBIT_CY), rings.disc)) }
    private val miniDisc = Path().apply { addOval(Rect(Offset(WIDTH - pad - 54f, HEIGHT - pad - 54f), 54f)) }

    private var focus = Int.MIN_VALUE
    private var stage: StageLine? = null
    private var poster: PosterScene? = null
    private var stretch: StretchScene? = null
    private var arc: ArcText? = null
    private var nextArc: ArcText? = null
    private var nextLine: TextLayoutResult? = null

    /** Draws the frame at [positionMs] of the song and copies it into [into] (ARGB, [WIDTH] × [HEIGHT], row by row). */
    fun render(positionMs: Long, into: IntArray) {
        val at = LyricCursor.focus(lines, positionMs)
        if (at != focus) layOut(at)
        scope.draw(density, LayoutDirection.Ltr, canvas, size) { frame(positionMs) }
        bitmap.readPixels(into, 0, 0, WIDTH, HEIGHT)
    }

    private fun layOut(at: Int) {
        focus = at
        val current = stageLineOf(lines, at)
        stage = current
        val next = lines.getOrNull(LyricCursor.nextSung(lines, at))?.text
        val width = WIDTH - 2 * pad
        poster = null; stretch = null; arc = null; nextArc = null; nextLine = null
        if (current == null) return
        when (look) {
            LyricVideoLook.POSTER -> {
                poster = PosterScene.build(measurer, current, fonts, density, width, HEIGHT - 2 * pad - 340f)
                nextLine = next?.let {
                    measurer.measure(it, stageTextStyle(fonts, 28f, density), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (width - 150f).toInt()))
                }
            }
            LyricVideoLook.STRETCH -> stretch = StretchScene.build(measurer, current, next, fonts, density, width, HEIGHT - 2 * pad - 360f, perWidth = 5.6f)
            LyricVideoLook.ORBITAL -> {
                val (lineStyle, nextStyle) = orbitalStyles(fonts, rings, density)
                val built = ArcText.build(measurer, current.text, lineStyle, rings.inner, rings.outer)
                arc = built
                nextArc = next?.let { ArcText.build(measurer, it, nextStyle, nextRing(rings, built), null) }
            }
            LyricVideoLook.CLASSIC -> Unit
        }
    }

    private fun DrawScope.frame(positionMs: Long) {
        drawRect(coverFill)
        when (look) {
            LyricVideoLook.POSTER -> {
                drawText(label, ink.text.copy(alpha = 0.6f), Offset(pad, pad))
                poster?.let { drawPoster(it, positionMs, ink.text, Offset(pad, pad + 110f), reduced = false) }
                nextLine?.let { drawText(it, ink.next, Offset(pad, HEIGHT - pad - 150f)) }
                drawText(brand, ink.text.copy(alpha = 0.6f), Offset(pad, HEIGHT - pad - brand.size.height - 20f))
                coverDisc(miniDisc, Offset(WIDTH - pad - 54f, HEIGHT - pad - 54f), 54f)
            }
            LyricVideoLook.STRETCH -> {
                drawText(title, ink.text, Offset((WIDTH - title.size.width) / 2f, pad + 40f))
                drawText(artist, ink.next, Offset((WIDTH - artist.size.width) / 2f, pad + 40f + title.size.height + 6f))
                stretch?.let { scene ->
                    translateTo(pad, pad + 240f) { drawStretch(scene, positionMs, ink.unlit, Color.White, ink.text, ink.next, reduced = false) }
                }
                drawText(brand, ink.text.copy(alpha = 0.6f), Offset((WIDTH - brand.size.width) / 2f, HEIGHT - pad - brand.size.height))
            }
            LyricVideoLook.ORBITAL -> {
                coverDisc(disc, Offset(WIDTH / 2f, ORBIT_CY), rings.disc)
                translateTo(20f, ORBIT_CY - (WIDTH - 40f) / 2f, WIDTH - 40f, WIDTH - 40f) {
                    drawOrbital(arc, nextArc, stage, positionMs, ink, rings, 1f, moon = true, moonPx = 9f, trackPx = 2f)
                }
                val below = ORBIT_CY + (WIDTH - 40f) / 2f + 50f
                drawText(title, ink.text, Offset((WIDTH - title.size.width) / 2f, below))
                drawText(artist, ink.next, Offset((WIDTH - artist.size.width) / 2f, below + title.size.height + 6f))
                drawText(brand, ink.text.copy(alpha = 0.6f), Offset((WIDTH - brand.size.width) / 2f, HEIGHT - pad - brand.size.height))
            }
            LyricVideoLook.CLASSIC -> Unit
        }
    }

    /** The cover as a disc in [clip], centred at [center] with [radius]; without a cover, a disc of the ink over the fill. */
    private fun DrawScope.coverDisc(clip: Path, center: Offset, radius: Float) {
        val art = cover
        if (art == null) {
            drawCircle(lerp(coverFill, coverInk, 0.35f), radius, center)
        } else {
            clipPath(clip) {
                val side = minOf(art.width, art.height)
                drawImage(
                    art, srcOffset = IntOffset((art.width - side) / 2, (art.height - side) / 2), srcSize = IntSize(side, side),
                    dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()), dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                )
            }
        }
        drawCircle(coverInk.copy(alpha = 0.3f), radius + 3f, center, style = Stroke(2f))
    }

    /** Runs [block] with the origin at ([x], [y]) and the scope's size cut to [w] by [h] (the full frame's rest by default). */
    private inline fun DrawScope.translateTo(x: Float, y: Float, w: Float = WIDTH - 2 * x, h: Float = HEIGHT - y, block: DrawScope.() -> Unit) {
        drawContext.transform.translate(x, y)
        val was = drawContext.size
        drawContext.size = Size(w, h)
        try { block() } finally {
            drawContext.size = was
            drawContext.transform.translate(-x, -y)
        }
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        private const val ORBIT_CY = 560f

        /** The looks offered before saving, the board's three first and the classic one last. */
        val offered: List<LyricVideoLook> = LyricVideoLook.entries.toList()

        /** The cover's poster colours from its seed (a colour card is deep in both themes). */
        fun coverColours(seedArgb: Int?): Pair<Color, Color> {
            if (seedArgb == null) return Color(0xFF3A2A2A) to POSTER_INK
            val tint = com.cosmotune.ui.orbit.vivid(seedArgb)
            return tint.fill to tint.ink
        }    }
}

/**
 * What a share sheet needs to draw the video in a style: given the look and the cover (as the exporter loaded it), the frames,
 * or null for [LyricVideoLook.CLASSIC], which each platform draws itself. The fonts and the font resolver are taken here, in the
 * sheet's composition, where the bundled fonts load; the cover's colour from the same tint the cards use.
 */
@androidx.compose.runtime.Composable
fun rememberLyricVideoFrames(request: com.cosmotune.music.ui.player.LyricsShareRequest): (LyricVideoLook, ImageBitmap?) -> LyricVideoFrames? {
    val fonts = rememberLyricStageFonts()
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val tint = com.cosmotune.ui.orbit.rememberCoverTint(request.artworkUrl)
    return androidx.compose.runtime.remember(request, fonts, resolver, tint) {
        { look, cover ->
            if (look == LyricVideoLook.CLASSIC) null else LyricVideoFrames(
                look, request.lrcLines,
                LyricStageSong(request.song.title, request.song.artist, request.song.albumName, request.artworkUrl),
                fonts, resolver, coverFill = tint?.fill ?: Color(0xFF3A2A2A), coverInk = tint?.ink ?: POSTER_INK, cover = cover,
            )
        }
    }
}
