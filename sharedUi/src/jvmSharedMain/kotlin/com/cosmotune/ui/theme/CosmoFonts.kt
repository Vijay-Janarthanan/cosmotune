package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.archivo_variable
import com.cosmotune.music.sharedui.resources.hanken_grotesk_variable
import org.jetbrains.compose.resources.Font

/** One instance cut from a variable font: the weight axis, and the width axis for Archivo (null for Hanken Grotesk). */
internal data class CosmoFontSpec(val weight: Int, val width: Int?)

/**
 * The instances the type scale draws (design formulas 02 section 2, handoff "Variable fonts": step between pre-built
 * instances, never animate the axes per frame). Pure data, so a test can build the same fonts without a window.
 */
internal object CosmoFontSpecs {
    /** The six instances along the title's motion path, from light and wide to heavy and condensed. */
    val titlePath = listOf(
        CosmoFontSpec(300, 112), CosmoFontSpec(420, 104), CosmoFontSpec(540, 96),
        CosmoFontSpec(660, 88), CosmoFontSpec(760, 80), CosmoFontSpec(820, 72),
    )

    /** The title at rest (weight 720, width 84), and the mood chip's label (weight 700, width 86). */
    val title = CosmoFontSpec(720, 84)
    val mood = CosmoFontSpec(700, 86)

    /** Every Archivo instance, one per weight (Compose picks an instance by weight alone). */
    val display: List<CosmoFontSpec> = (titlePath + title + mood).sortedBy { it.weight }

    /** Hanken Grotesk: body 400 and 500; label 600; 650; the button label 680; 700. */
    val textWeights = listOf(400, 500, 600, 650, 680, 700)
}

/**
 * The display family (Archivo, width and weight axes) and the text family (Hanken Grotesk, weight axis), in that order.
 *
 * Both files are variable fonts, bundled whole. Compose cuts one instance per weight with `FontVariation.Settings`: on
 * Android through `Typeface.Builder` (API 26 and up, our minSdk), on the desktop through Skia (`FontVariationTest` renders
 * both and compares widths). A style asks for an instance by weight alone, so each weight appears once per family.
 *
 * Only Latin, Latin Extended, Vietnamese and Greek are in the files. Any other script (Devanagari, Tamil, Telugu, Bengali,
 * Cyrillic, CJK) falls back to the system's own fonts (Noto on Android, the platform fallback in Skia), which have no width
 * axis; that is expected, and the tall line height of Indian scripts is set by the type styles.
 */
@Composable
internal fun cosmoFontFamilies(): Pair<FontFamily, FontFamily> {
    val display = CosmoFontSpecs.display.map {
        Font(
            Res.font.archivo_variable, FontWeight(it.weight), FontStyle.Normal,
            FontVariation.Settings(FontVariation.weight(it.weight), FontVariation.width(requireNotNull(it.width).toFloat())),
        )
    }
    val text = CosmoFontSpecs.textWeights.map {
        Font(Res.font.hanken_grotesk_variable, FontWeight(it), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(it)))
    }
    return remember(display, text) { FontFamily(display) to FontFamily(text) }
}
