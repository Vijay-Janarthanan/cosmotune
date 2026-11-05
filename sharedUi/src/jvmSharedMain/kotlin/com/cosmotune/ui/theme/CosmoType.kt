package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import kotlin.math.roundToInt

/**
 * The type system (design formulas 02 section 1 and `tokens-v3bc.css`): a fluid modular scale in sp that follows the
 * window width, in one family: Hanken Grotesk, light for display and titles, regular to semibold for everything else. Archivo
 * ([displayFamily]) is kept for the title's motion path ([titlePath]) only. [widthDp] is the window width.
 *
 * Steps are the [TypeScale] constants (caption -1, body 0, title-s 1, ... display-xl 7). Sizes are sp, so the user's
 * font scale still multiplies them. Indian scripts need a taller line: pass the styled text through [forText].
 */
@Immutable
class CosmoType internal constructor(
    val widthDp: Float,
    val displayFamily: FontFamily,
    val textFamily: FontFamily,
) {
    /** The size of [step] at this window width. */
    fun size(step: Int): TextUnit = TypeScale.sizeSp(step, widthDp).sp

    /**
     * Display (the page titles, the greeting): the text family, light (weight 300), line height 1.1, tracking -0.02 em
     * (-0.025 em from display-l up). The revamp (docs/design/REVAMP_DIRECTION.md) draws titles large and light, never condensed
     * or black.
     */
    fun display(step: Int): TextStyle {
        val size = size(step)
        return TextStyle(
            fontFamily = textFamily,
            fontWeight = FontWeight(DISPLAY_WEIGHT),
            fontSize = size,
            lineHeight = size * 1.1f,
            letterSpacing = (if (step >= TypeScale.DISPLAY_L) -0.025f else -0.02f).em,
        )
    }

    /**
     * Titles: the text family at weight 400, line height 1.18, tracking -0.01 em. Title-s is the exception the designer drew
     * (`.t-title-s`): weight 650, line height 1.25.
     */
    fun title(step: Int): TextStyle {
        val size = size(step)
        if (step <= TypeScale.TITLE_S) return TextStyle(fontFamily = textFamily, fontWeight = FontWeight(650), fontSize = size, lineHeight = size * 1.25f)
        return TextStyle(
            fontFamily = textFamily,
            fontWeight = FontWeight(TITLE_WEIGHT),
            fontSize = size,
            lineHeight = size * 1.18f,
            letterSpacing = (-0.01f).em,
        )
    }

    // ---- The One type scale (DESIGN_SYSTEM.md 3.2): fixed sp, one step up from 600 dp windows, Hanken Grotesk only. ----

    private val compact: Boolean get() = widthDp < 600f
    private fun one(compactSp: Float, compactLine: Float, wideSp: Float, wideLine: Float, weight: Int, trackingEm: Float) = TextStyle(
        fontFamily = textFamily,
        fontWeight = FontWeight(weight),
        fontSize = (if (compact) compactSp else wideSp).sp,
        lineHeight = (if (compact) compactLine else wideLine).sp,
        letterSpacing = trackingEm.em,
    )

    /** The large top-bar title of a root page: 34/40 (40/46 wide), weight 300. */
    val displayLarge: TextStyle get() = one(34f, 40f, 40f, 46f, 300, -0.005f)
    /** A detail page's title (album, series, show, book, channel): 26/32 (30/36), weight 400. */
    val headline: TextStyle get() = one(26f, 32f, 30f, 36f, 400, -0.003f)
    /** A section header, a sheet's title, the collapsed top-bar title: 20/26 (22/28), weight 500. */
    val titleLarge: TextStyle get() = one(20f, 26f, 22f, 28f, 500, 0f)
    /** A card's or a dialog's title: 16/22, weight 600. */
    val titleMedium: TextStyle get() = one(16f, 22f, 16f, 22f, 600, 0f)
    /** A media row's or a settings row's title: 15/20, weight 500. */
    val rowTitle: TextStyle get() = one(15f, 20f, 15f, 20f, 500, 0f)
    /** Body text and descriptions: 14/20 (15/22), weight 400. */
    val bodyText: TextStyle get() = one(14f, 20f, 15f, 22f, 400, 0f)
    /** Row subtitles and counts: 13/18, weight 400 (add [tabular] for numbers and times). */
    val meta: TextStyle get() = one(13f, 18f, 13f, 18f, 400, 0.001f)
    /** Buttons, chips, segmented controls: 14/20, weight 600. */
    val control: TextStyle get() = one(14f, 20f, 14f, 20f, 600, 0.005f)
    /** The dock's and the rail's tab labels: 12/16, weight 600. */
    val tabLabel: TextStyle get() = one(12f, 16f, 12f, 16f, 600, 0.005f)
    /** Pills, footnotes, timestamps on thumbnails: 12/16, weight 400. */
    val caption: TextStyle get() = one(12f, 16f, 12f, 16f, 400, 0.002f)
    /** The Now Playing title: 22/28 (26/32), weight 600. */
    val playerTitle: TextStyle get() = one(22f, 28f, 26f, 32f, 600, -0.002f)
    /** The active lyric line: 28/36 (34/44), weight 600. */
    val lyric: TextStyle get() = one(28f, 36f, 34f, 44f, 600, -0.003f)

    /** The small all-caps label over a section or a settings group ("MADE FOR YOU"): 11/16, weight 600, tracking 0.08 em. Upper-case the text yourself. */
    val overline: TextStyle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.08.em)

    /** Running text: Hanken Grotesk, line height 1.42. */
    fun body(step: Int = TypeScale.BODY, weight: FontWeight = FontWeight.Normal): TextStyle {
        val size = size(step)
        return TextStyle(fontFamily = textFamily, fontWeight = weight, fontSize = size, lineHeight = size * 1.42f)
    }

    /** Labels and list metadata: 14 sp, weight 600, tracking 0.01 em (`--tr-label`). */
    val label: TextStyle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(600), fontSize = 14.sp, letterSpacing = 0.01.em)

    /** The label of a button: 15 sp, weight 680. */
    val button: TextStyle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(680), fontSize = 15.sp, letterSpacing = 0.01.em)

    /** The mood chip: the text family, 15 sp, weight 600 (a thin chip, not a block). */
    val mood: TextStyle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(600), fontSize = 15.sp)

    /**
     * The weight of the title's instance number [index] (0..5) along its motion path, light and wide to heavy and
     * condensed. Step between these pre-built instances; never animate the font axes per frame.
     */
    fun titlePath(index: Int): FontWeight = FontWeight(CosmoFontSpecs.titlePath[index.coerceIn(0, CosmoFontSpecs.titlePath.lastIndex)].weight)

    /** [style] with the 1.5 line height that Devanagari, Tamil, Telugu, Bengali and the other Indian scripts need, when [text] has any. */
    fun forText(style: TextStyle, text: String): TextStyle =
        if (TypeScale.needsTallLines(text) && style.fontSize.isSp) style.copy(lineHeight = style.fontSize * 1.5f) else style

    private companion object {
        const val DISPLAY_WEIGHT = 300
        const val TITLE_WEIGHT = 400
    }
}

/** Times, counts and durations take tabular figures so they do not jitter as they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

val LocalCosmoType = compositionLocalOf<CosmoType> { error("CosmoLookTheme is not installed") }

/** The type for this window's width. The width is rounded to whole dp, so a resize does not rebuild it every pixel. */
@Composable
fun rememberCosmoType(): CosmoType {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current.density
    val widthDp = (widthPx / density).roundToInt().toFloat()
    val (display, text) = cosmoFontFamilies()
    return remember(widthDp, display, text) { CosmoType(widthDp, display, text) }
}
