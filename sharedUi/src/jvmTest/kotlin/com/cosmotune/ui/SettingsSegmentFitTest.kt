package com.cosmotune.ui

import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font as bytesFont
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.ui.theme.CosmoFontSpecs
import com.cosmotune.ui.theme.CosmoType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every label of the phone's settings segmented controls fits a 360 dp phone at 12 sp or more, measured with the bundled Hanken
 * Grotesk through Skia (as [FontVariationTest] does), in every locale the app ships (P4-17: with the board's 12 and 16 dp ends,
 * Intensity's "Balanced" was cut to "Balanc..." with the real font). The rows are the widths the page gives each control at 360 dp:
 * the page keeps 16 dp on each side; Intensity stands under its row's text (70 dp in, 16 dp from the end); the theme control
 * 16 dp in from each end; output precision and refresh rate start at the old page's text inset (16 + 22 + 14 = 52 dp).
 *
 * Only labels the bundled font covers are measured (see [assertFits]): a label in a script Hanken Grotesk does not have falls
 * back to the machine's own fonts, so its width differed between Windows, macOS and Linux and proved nothing about the app.
 */
class SettingsSegmentFitTest {
    private val root = SourceContract.root
    private val resolver = createFontFamilyResolver()

    private val hanken: ByteArray by lazy { File(root, "sharedUi/src/commonMain/composeResources/font/hanken_grotesk_variable.ttf").readBytes() }

    /** Every code point the bundled font has a glyph for, read from its own character map. */
    private val covered: Set<Int> by lazy { FontCharacterMap.codePoints(hanken) }

    private val textFamily: FontFamily by lazy {
        FontFamily(CosmoFontSpecs.textWeights.map {
            bytesFont("hanken-$it", hanken, FontWeight(it), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(it)))
        })
    }

    /** The label's width in dp at 12 sp: the settings segment's style (the label style at weight 640) in the app's text family. */
    private fun widthAt12(label: String): Float {
        val type = CosmoType(360f, FontFamily.Default, textFamily)
        val style = type.forText(type.label.copy(fontWeight = FontWeight(640), fontSize = ControlMetrics.SEGMENT_LABEL_MIN_SP.sp), label)
        return Paragraph(text = label, style = style, constraints = Constraints(maxWidth = 100_000), density = Density(1f), fontFamilyResolver = resolver)
            .maxIntrinsicWidth
    }

    /** The values folders of the locales written in the Latin script, the one Hanken Grotesk is drawn for. */
    private val latinScript = setOf(
        "values", "values-de", "values-es", "values-fr", "values-id", "values-it", "values-pl", "values-pt", "values-tr", "values-vi",
    )

    // The string [key] in the strings.xml of every values folder under [dir], by folder.
    private fun byLocale(dir: String, key: String): Map<String, String> =
        File(root, dir).listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.sortedBy { it.name }.mapNotNull { folder ->
            val file = File(folder, "strings.xml").takeIf { it.isFile } ?: return@mapNotNull null
            Regex("""<string name="$key">([^<]*)</string>""").find(file.readText())?.groupValues?.get(1)?.let { folder.name to it }
        }.toMap()

    private fun everyLocale(dir: String, key: String): List<String> = byLocale(dir, key).values.distinct()
    private fun latinLocales(dir: String, key: String): List<String> = byLocale(dir, key).filterKeys { it in latinScript }.values.distinct()

    /**
     * Measures every label the bundled font can draw in full and holds it to the room strictly. A label with a character the font
     * lacks (Hebrew, Cyrillic, Thai, Devanagari, Chinese, Japanese...) is drawn with whatever fallback font the machine has, so its
     * width says something about that machine rather than the app (the phone draws those scripts with its own system fonts); it is
     * left out and named. A label in [mustMeasure] (every label of a Latin-script locale) is never left out: if the font lacks one
     * of its characters, the test fails, as the app itself would draw that label in a fallback font.
     */
    private fun assertFits(what: String, labels: List<String>, rowDp: Float, count: Int, check: Boolean, mustMeasure: List<String> = labels) {
        assertTrue(mustMeasure.isNotEmpty() && labels.containsAll(mustMeasure), "$what: the labels measured in every case are among the labels")
        val room = ControlMetrics.settingsSegmentLabelRoomDp(rowDp, count, check)
        for (label in labels) {
            val missing = label.codePoints().toArray().filter { it !in covered }.distinct().joinToString { "U+%04X".format(it) }
            if (missing.isNotEmpty()) {
                assertTrue(label !in mustMeasure, "$what: \"$label\" is in a Latin-script locale, but the bundled font has no glyph for $missing")
                println("$what: \"$label\" skipped: the bundled font has no glyph for $missing, so its width would be the machine's fallback font's")
                continue
            }
            val width = widthAt12(label)
            println("$what: \"$label\" at 12 sp is $width dp; room $room dp")
            assertTrue(width <= room, "$what: \"$label\" is $width dp at 12 sp, the segment leaves $room dp")
        }
    }

    private val page = 360f - 2 * 16f

    @Test fun theCharacterMapIsTheBundledFontsOwn() {
        // the basic Latin letters and digits are in, the scripts the font does not have are not
        for (c in ('A'..'Z') + ('a'..'z') + ('0'..'9')) assertTrue(c.code in covered, "'$c' is in Hanken Grotesk")
        for (c in listOf('ש', 'Д', 'ไ', 'क', '曲', 'ラ')) assertTrue(c.code !in covered, "'$c' (U+%04X) is not in Hanken Grotesk".format(c.code))
    }

    @Test fun intensityFitsUnderItsRow() {
        val dir = "sharedUi/src/commonMain/composeResources"
        val keys = listOf("calm", "balanced", "rich").map { "cosmo_intensity_$it" }
        val labels = keys.flatMap { everyLocale(dir, it) }
        assertTrue(labels.size >= 3)
        assertFits("intensity", labels, page - 70f - 16f, 3, check = false, mustMeasure = keys.flatMap { latinLocales(dir, it) })
    }

    @Test fun theThemeFitsInEveryLocaleWithTheCheck() {
        val dir = "app/src/main/res"
        val keys = listOf("system", "light", "dark")
        val labels = keys.flatMap { everyLocale(dir, it) }
        assertTrue(labels.size >= 30, "the three theme names in the app's locales: $labels")
        val latin = keys.flatMap { latinLocales(dir, it) }
        assertTrue(latin.size >= 20, "the theme names of the Latin-script locales: $latin")
        assertFits("theme", labels, page - 16f - 16f, 3, check = true, mustMeasure = latin)
    }

    @Test fun outputPrecisionAndRefreshRateFit() {
        val settings = SourceContract.read("app/src/main/java/com/cosmotune/music/data/settings/AppSettings.kt")
        val enum = settings.substringAfter("enum class OutputPcmMode(").substringBefore("}")
        val pcm = Regex("""[A-Z_0-9]+\("([^"]+)"\)""").findAll(enum).map { it.groupValues[1] }.toList()
        assertTrue(pcm.size == 2, "OutputPcmMode's labels: $pcm")
        val row = page - 52f - 16f
        assertFits("output precision", pcm, row, pcm.size, check = true)
        assertFits("refresh rate (3)", listOf("60 Hz", "90 Hz", "120 Hz"), row, 3, check = true)
        assertFits("refresh rate (4)", listOf("60 Hz", "90 Hz", "120 Hz", "144 Hz"), row, 4, check = false)
    }

    @Test fun theSegmentUsesTheseNumbers() {
        val src = SourceContract.code(SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/settings/CosmoSettingsBlocks.kt"))
        val segment = src.substringAfter("private fun SettingsSegment(")
        assertTrue("val check = ControlMetrics.settingsSegmentShowsCheck(selected, compact, count)" in segment)
        assertTrue(".padding(horizontal = ControlMetrics.SEGMENT_FILL_PADDING_DP.dp)," in segment)
        assertTrue("Arrangement.spacedBy(ControlMetrics.SEGMENT_CHECK_GAP_DP.dp, Alignment.CenterHorizontally)" in segment)
        assertTrue("autoSize = if (compact) COMPACT_SEGMENT_FIT else SEGMENT_FIT," in segment)
        assertTrue(
            "FitLabel(ControlMetrics.settingsSegmentLabelMaxSp(compact = false), ControlMetrics.SEGMENT_LABEL_MIN_SP, 0.5f, wholeWords = false)" in src &&
                "FitLabel(ControlMetrics.settingsSegmentLabelMaxSp(compact = true), ControlMetrics.SEGMENT_LABEL_MIN_SP, 0.5f, wholeWords = false)" in src,
            "the labels shrink from their own size to 12 sp",
        )
    }
}
