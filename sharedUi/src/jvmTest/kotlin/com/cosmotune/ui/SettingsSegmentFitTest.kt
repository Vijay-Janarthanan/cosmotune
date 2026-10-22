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
 */
class SettingsSegmentFitTest {
    private val root = SourceContract.root
    private val resolver = createFontFamilyResolver()

    private val textFamily: FontFamily by lazy {
        val hanken = File(root, "sharedUi/src/commonMain/composeResources/font/hanken_grotesk_variable.ttf").readBytes()
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

    // The string [key] in the strings.xml of every values folder under [dir].
    private fun everyLocale(dir: String, key: String): List<String> =
        File(root, dir).listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.mapNotNull { folder ->
            val file = File(folder, "strings.xml").takeIf { it.isFile } ?: return@mapNotNull null
            Regex("""<string name="$key">([^<]*)</string>""").find(file.readText())?.groupValues?.get(1)
        }.distinct()

    private fun assertFits(what: String, labels: List<String>, rowDp: Float, count: Int, check: Boolean) {
        val room = ControlMetrics.settingsSegmentLabelRoomDp(rowDp, count, check)
        for (label in labels) {
            val width = widthAt12(label)
            println("$what: \"$label\" at 12 sp is $width dp; room $room dp")
            assertTrue(width <= room, "$what: \"$label\" is $width dp at 12 sp, the segment leaves $room dp")
        }
    }

    private val page = 360f - 2 * 16f

    @Test fun intensityFitsUnderItsRow() {
        val labels = listOf("calm", "balanced", "rich").flatMap { everyLocale("sharedUi/src/commonMain/composeResources", "cosmo_intensity_$it") }
        assertTrue(labels.size >= 3)
        assertFits("intensity", labels, page - 70f - 16f, 3, check = false)
    }

    @Test fun theThemeFitsInEveryLocaleWithTheCheck() {
        val labels = listOf("system", "light", "dark").flatMap { everyLocale("app/src/main/res", it) }
        assertTrue(labels.size >= 30, "the three theme names in the app's locales: $labels")
        assertFits("theme", labels, page - 16f - 16f, 3, check = true)
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
