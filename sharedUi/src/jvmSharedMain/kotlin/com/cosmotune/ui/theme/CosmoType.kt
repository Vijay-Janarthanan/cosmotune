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
 * window width, Archivo for display and titles, Hanken Grotesk for everything else. [widthDp] is the window width.
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

    /** Display: Archivo at width 72 %, weight 820, line height 0.94, tracking -0.024 em (-0.03 em from display-l up). */
    fun display(step: Int): TextStyle {
        val size = size(step)
        return TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight(DISPLAY_WEIGHT),
            fontSize = size,
            lineHeight = size * 0.94f,
            letterSpacing = (if (step >= TypeScale.DISPLAY_L) -0.03f else -0.024f).em,
        )
    }

    /**
     * Titles: Archivo at width 84 %, weight 720, line height 1.08, tracking -0.012 em. Title-s is the exception the
     * designer drew (`.t-title-s`): Hanken Grotesk, weight 650, line height 1.25.
     */
    fun title(step: Int): TextStyle {
        val size = size(step)
        if (step <= TypeScale.TITLE_S) return TextStyle(fontFamily = textFamily, fontWeight = FontWeight(650), fontSize = size, lineHeight = size * 1.25f)
        return TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight(TITLE_WEIGHT),
            fontSize = size,
            lineHeight = size * 1.08f,
            letterSpacing = (-0.012f).em,
        )
    }

    /** Running text: Hanken Grotesk, line height 1.42. */
    fun body(step: Int = TypeScale.BODY, weight: FontWeight = FontWeight.Normal): TextStyle {
        val size = size(step)
        return TextStyle(fontFamily = textFamily, fontWeight = weight, fontSize = size, lineHeight = size * 1.42f)
    }

    /** Labels and list metadata: 14 sp, weight 600, tracking 0.01 em (`--tr-label`). */
    val label: TextStyle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(600), fontSize = 14.sp, letterSpacing = 0.01.em)

    /** The label of a button: 15 sp, weight 680. */
    val button: TextStyle = TextStyle(fontFamily = textFamily, fontWeight = FontWeight(680), fontSize = 15.sp, letterSpacing = 0.01.em)

    /** The mood chip: Archivo at width 86 %, 18 sp, weight 700. */
    val mood: TextStyle = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight(CosmoFontSpecs.mood.weight), fontSize = 18.sp)

    /**
     * The weight of the title's instance number [index] (0..5) along its motion path, light and wide to heavy and
     * condensed. Step between these pre-built instances; never animate the font axes per frame.
     */
    fun titlePath(index: Int): FontWeight = FontWeight(CosmoFontSpecs.titlePath[index.coerceIn(0, CosmoFontSpecs.titlePath.lastIndex)].weight)

    /** [style] with the 1.5 line height that Devanagari, Tamil, Telugu, Bengali and the other Indian scripts need, when [text] has any. */
    fun forText(style: TextStyle, text: String): TextStyle =
        if (TypeScale.needsTallLines(text) && style.fontSize.isSp) style.copy(lineHeight = style.fontSize * 1.5f) else style

    private companion object {
        const val DISPLAY_WEIGHT = 820
        const val TITLE_WEIGHT = 720
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
