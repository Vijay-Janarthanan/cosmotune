package com.cosmotune.ui

import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font as bytesFont
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.CosmoFontSpec
import com.cosmotune.ui.theme.CosmoFontSpecs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decision evidence for the fonts (task P3-5): the desktop (Skia) honours variable-font axes when Compose builds the
 * font with `FontVariation.Settings`, so the two variable files are bundled as they are and no static instances are
 * needed. These tests render text through Skia on the JVM, without a window, and compare widths.
 */
class FontVariationTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun fontBytes(name: String) = File(root, "sharedUi/src/commonMain/composeResources/font/$name").readBytes()
    private val archivo by lazy { fontBytes("archivo_variable.ttf") }
    private val hanken by lazy { fontBytes("hanken_grotesk_variable.ttf") }

    private fun archivoFont(weight: Int, width: Int): Font = bytesFont(
        "archivo-$weight-$width", archivo, FontWeight(weight), FontStyle.Normal,
        FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width.toFloat())),
    )

    private fun hankenFont(weight: Int): Font = bytesFont(
        "hanken-$weight", hanken, FontWeight(weight), FontStyle.Normal,
        FontVariation.Settings(FontVariation.weight(weight)),
    )

    private val resolver = createFontFamilyResolver()

    /** The width of [SAMPLE] at 100 sp in [font] alone, measured by Skia through a Compose paragraph. */
    private fun widthOf(font: Font, weight: Int): Float = Paragraph(
        text = SAMPLE,
        style = TextStyle(fontFamily = FontFamily(font), fontWeight = FontWeight(weight), fontSize = 100.sp),
        constraints = Constraints(maxWidth = 100_000),
        density = Density(1f),
        fontFamilyResolver = resolver,
    ).maxIntrinsicWidth

    @Test fun skiaMovesTheWeightAxisOfArchivo() {
        val light = widthOf(archivoFont(300, 100), 300)
        val heavy = widthOf(archivoFont(820, 100), 820)
        println("archivo wdth 100: wght 300 -> $light, wght 820 -> $heavy")
        assertTrue(heavy > light * 1.05f, "heavier is wider: $light vs $heavy")
    }

    @Test fun skiaMovesTheWidthAxisOfArchivo() {
        val condensed = widthOf(archivoFont(820, 72), 820)
        val normal = widthOf(archivoFont(820, 100), 820)
        val wide = widthOf(archivoFont(820, 112), 820)
        println("archivo wght 820: wdth 72 -> $condensed, wdth 100 -> $normal, wdth 112 -> $wide")
        assertTrue(condensed < normal * 0.9f, "wdth 72 is condensed: $condensed vs $normal")
        assertTrue(wide > normal, "wdth 112 is wider than 100: $wide vs $normal")
    }

    @Test fun skiaMovesTheWeightAxisOfHankenGrotesk() {
        val regular = widthOf(hankenFont(400), 400)
        val bold = widthOf(hankenFont(700), 700)
        println("hanken: wght 400 -> $regular, wght 700 -> $bold")
        assertTrue(bold > regular * 1.01f, "bold is wider: $regular vs $bold")
    }

    @Test fun theSameSettingsGiveTheSameFont() {
        assertEquals(widthOf(archivoFont(720, 84), 720), widthOf(archivoFont(720, 84), 720))
    }

    /** The whole Compose path: a family of instances, a style that asks for a weight, a paragraph measured by Skia. */
    @Test fun aFamilyOfInstancesIsPickedByWeight() {
        val family = FontFamily(CosmoFontSpecs.display.map { archivoFont(it.weight, it.width!!) })
        val resolver = createFontFamilyResolver()
        fun widthAt(weight: Int): Float = Paragraph(
            text = SAMPLE,
            style = TextStyle(fontFamily = family, fontWeight = FontWeight(weight), fontSize = 100.sp),
            constraints = Constraints(maxWidth = 100_000),
            density = Density(1f),
            fontFamilyResolver = resolver,
        ).maxIntrinsicWidth
        val widths = CosmoFontSpecs.titlePath.map { widthAt(it.weight) }
        println("title path widths (weights ${CosmoFontSpecs.titlePath.map(CosmoFontSpec::weight)}): $widths")
        assertEquals(widths.size, widths.toSet().size, "each instance of the title path draws differently")
        val solo = widthOf(archivoFont(300, 112), 300)
        assertTrue(kotlin.math.abs(widths.first() - solo) < 1f, "weight 300 asks for the 300 / 112 instance: ${widths.first()} vs $solo")
    }

    private companion object { const val SAMPLE = "Cosmotune Wild Hearts" }
}
