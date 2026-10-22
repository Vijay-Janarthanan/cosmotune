package com.cosmotune.ui

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.theme.CosmoFontSpecs
import com.cosmotune.ui.theme.CosmoMaterialShapes
import com.cosmotune.ui.theme.CosmoRipple
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.toMaterialTypography
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Material bridge: the type table of P4-1 row by row at 360 and 840 dp, on two legacy scales (Material's own defaults, as
 * the desktop starts from, and a copy of the phone's SF Pro scale), the five corners, the ripple, and what the theme file must
 * look like so that flipping the switch keeps the app's state.
 */
class MaterialBridgeTest {
    private val archivo = FontFamily.Serif
    private val hanken = FontFamily.SansSerif
    private val widths = listOf(360f, 840f)
    private fun typeAt(width: Float) = CosmoType(width, archivo, hanken)

    /** Material's own defaults: sizes 57..11 sp, tracking from +0.5 to -0.25 sp. */
    private val materialDefaults = Typography()

    /** The phone's `CosmotuneTypography` (`Theme.kt`): SF Pro, heavy and tight, only some styles set. */
    private val phone = Typography(
        displayLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
        headlineLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
        titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W400, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W400, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600, fontSize = 12.sp),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.W600, fontSize = 11.sp),
    )

    private val bases = mapOf("material defaults" to materialDefaults, "phone scale" to phone)

    private fun near(expected: Float, actual: Float, what: String, eps: Float = 0.001f) =
        assertTrue(abs(expected - actual) <= eps, "$what: expected $expected, was $actual")

    /** One row of the table: family, weight, size, tracking (value and unit) and line height. */
    private fun assertRow(
        row: String,
        style: TextStyle,
        family: FontFamily,
        weight: Int,
        size: Float,
        tracking: TextUnit,
        lineHeight: TextUnit,
    ) {
        assertSame(family, style.fontFamily, "$row family")
        assertEquals(FontWeight(weight), style.fontWeight, "$row weight")
        assertTrue(style.fontSize.isSp, "$row size is in sp")
        near(size, style.fontSize.value, "$row size")
        assertEquals(tracking.isEm, style.letterSpacing.isEm, "$row tracking unit")
        near(tracking.value, style.letterSpacing.value, "$row tracking", 0.0001f)
        if (lineHeight.isUnspecified) {
            assertTrue(style.lineHeight.isUnspecified, "$row keeps the legacy style's open line height")
        } else {
            assertTrue(style.lineHeight.isSp, "$row line height is in sp")
            near(lineHeight.value, style.lineHeight.value, "$row line height")
        }
    }

    @Test fun displayAndHeadlineStylesTakeTheFluidScaleAtEveryWidthOnEveryBase() {
        for (width in widths) for ((name, base) in bases) {
            val type = typeAt(width)
            val m = type.toMaterialTypography(base)
            fun at(step: Int) = type.size(step).value
            fun row(label: String) = "$label at $width on $name"
            assertRow(row("displayLarge"), m.displayLarge, archivo, 820, at(TypeScale.DISPLAY_M), (-0.024f).em, (at(TypeScale.DISPLAY_M) * 0.94f).sp)
            assertRow(row("displayMedium"), m.displayMedium, archivo, 820, at(TypeScale.DISPLAY_S), (-0.024f).em, (at(TypeScale.DISPLAY_S) * 0.94f).sp)
            assertRow(row("displaySmall"), m.displaySmall, archivo, 720, at(TypeScale.TITLE_L), (-0.012f).em, (at(TypeScale.TITLE_L) * 1.08f).sp)
            assertRow(row("headlineLarge"), m.headlineLarge, archivo, 820, at(TypeScale.DISPLAY_S), (-0.024f).em, (at(TypeScale.DISPLAY_S) * 0.94f).sp)
            assertRow(row("headlineMedium"), m.headlineMedium, archivo, 720, at(TypeScale.TITLE_M), (-0.012f).em, (at(TypeScale.TITLE_M) * 1.08f).sp)
            assertRow(row("headlineSmall"), m.headlineSmall, archivo, 720, at(TypeScale.TITLE_M), (-0.012f).em, (at(TypeScale.TITLE_M) * 1.08f).sp)
            // Title-s is the text face (Hanken Grotesk 650, line 1.25), and the design draws no tracking on it: zero, not the legacy -0.3 sp.
            assertRow(row("titleLarge"), m.titleLarge, hanken, 650, at(TypeScale.TITLE_S), 0.sp, (at(TypeScale.TITLE_S) * 1.25f).sp)
        }
    }

    @Test fun theFluidSizesAreTheScaleAtThatWidth() {
        val narrow = typeAt(360f).toMaterialTypography(phone)
        val wide = typeAt(840f).toMaterialTypography(phone)
        near(37.325f, narrow.displayLarge.fontSize.value, "displayLarge at 360", 0.01f)
        near(48.828f, wide.displayLarge.fontSize.value, "displayLarge at 840", 0.01f)
        near(18f, narrow.titleLarge.fontSize.value, "titleLarge at 360", 0.01f)
        near(20f, wide.titleLarge.fontSize.value, "titleLarge at 840", 0.01f)
        assertTrue(wide.headlineLarge.fontSize.value > narrow.headlineLarge.fontSize.value)
    }

    @Test fun titleBodyAndLabelStylesKeepTheBaseSizeAndChangeFamilyWeightTrackingAndLine() {
        for (width in widths) for ((name, base) in bases) {
            val m = typeAt(width).toMaterialTypography(base)
            fun row(label: String) = "$label at $width on $name"
            fun size(style: TextStyle) = style.fontSize.value

            assertRow(row("titleMedium"), m.titleMedium, hanken, 650, size(base.titleMedium), 0.sp, (size(base.titleMedium) * 1.3f).sp)
            // No line height is drawn for the small title and the labels: the legacy one stays.
            assertRow(row("titleSmall"), m.titleSmall, hanken, 600, size(base.titleSmall), 0.01.em, base.titleSmall.lineHeight)
            for ((label, pick) in listOf<Pair<String, (Typography) -> TextStyle>>("bodyLarge" to { it.bodyLarge }, "bodyMedium" to { it.bodyMedium }, "bodySmall" to { it.bodySmall })) {
                val old = pick(base)
                assertRow(row(label), pick(m), hanken, 400, size(old), 0.sp, (size(old) * 1.42f).sp)
            }
            assertRow(row("labelLarge"), m.labelLarge, hanken, 680, size(base.labelLarge), 0.01.em, base.labelLarge.lineHeight)
            assertRow(row("labelMedium"), m.labelMedium, hanken, 600, size(base.labelMedium), 0.01.em, base.labelMedium.lineHeight)
            assertRow(row("labelSmall"), m.labelSmall, hanken, 700, size(base.labelSmall), 0.01.em, base.labelSmall.lineHeight)
        }
    }

    @Test fun theSampleBasesKeepTheirOldSizes() {
        // The phone's own sizes, which the old rows, chips and segmented controls are laid out for.
        val m = typeAt(360f).toMaterialTypography(phone)
        assertEquals(listOf(16f, 16f, 14f, 12f, 11f), listOf(m.titleMedium, m.bodyLarge, m.bodyMedium, m.labelMedium, m.labelSmall).map { it.fontSize.value })
        // Material's defaults: titleSmall 14, bodySmall 12, labelLarge 14.
        val d = typeAt(360f).toMaterialTypography(materialDefaults)
        assertEquals(listOf(14f, 12f, 14f), listOf(d.titleSmall, d.bodySmall, d.labelLarge).map { it.fontSize.value })
    }

    @Test fun aLegacyStyleWithoutAnSpSizeFallsBackToTheBodySize() {
        val odd = Typography(bodyMedium = TextStyle(fontSize = 1.em))
        val type = typeAt(360f)
        val m = type.toMaterialTypography(odd)
        near(type.size(TypeScale.BODY).value, m.bodyMedium.fontSize.value, "body size")
        near(type.size(TypeScale.BODY).value * 1.42f, m.bodyMedium.lineHeight.value, "body line")
    }

    @Test fun everyWeightIsAnInstanceOfItsFamilyAndNoLegacyWeightSurvives() {
        val archivoWeights = CosmoFontSpecs.display.map { it.weight }
        val hankenWeights = CosmoFontSpecs.textWeights
        for (width in widths) for ((name, base) in bases) {
            val m = typeAt(width).toMaterialTypography(base)
            val rows = mapOf(
                "displayLarge" to m.displayLarge, "displayMedium" to m.displayMedium, "displaySmall" to m.displaySmall,
                "headlineLarge" to m.headlineLarge, "headlineMedium" to m.headlineMedium, "headlineSmall" to m.headlineSmall,
                "titleLarge" to m.titleLarge, "titleMedium" to m.titleMedium, "titleSmall" to m.titleSmall,
                "bodyLarge" to m.bodyLarge, "bodyMedium" to m.bodyMedium, "bodySmall" to m.bodySmall,
                "labelLarge" to m.labelLarge, "labelMedium" to m.labelMedium, "labelSmall" to m.labelSmall,
            )
            for ((row, style) in rows) {
                val weights = if (style.fontFamily === archivo) archivoWeights else hankenWeights
                assertTrue(style.fontFamily === archivo || style.fontFamily === hanken, "$row on $name has a new look family")
                assertTrue(style.fontWeight!!.weight in weights, "$row on $name asks for ${style.fontWeight}, which is not an instance")
                assertTrue(style.fontWeight!!.weight != 800, "$row on $name is not the legacy W800")
            }
        }
    }

    @Test fun theBridgeDoesNotTouchWhatTheNewStyleLeavesOpen() {
        // Colour, alignment and the like stay as the legacy style had them.
        val tinted = Typography(bodyLarge = TextStyle(color = Color.Red, fontSize = 16.sp))
        val m = typeAt(360f).toMaterialTypography(tinted)
        assertEquals(Color.Red, m.bodyLarge.color)
        assertEquals(tinted.bodyMedium.color, m.bodyMedium.color)
    }

    @Test fun theFiveShapesAreTheCircularRadiiOfTheBcRadii() {
        val density = Density(1f)
        val square = Size(400f, 400f)
        fun corners(s: CornerBasedShape): List<Float> = listOf(s.topStart, s.topEnd, s.bottomEnd, s.bottomStart).map { it.toPx(square, density) }
        assertEquals(List(4) { 17f }, corners(CosmoMaterialShapes.extraSmall), "menus and fields")
        assertEquals(List(4) { 9f }, corners(CosmoMaterialShapes.small), "chips")
        assertEquals(List(4) { 18f }, corners(CosmoMaterialShapes.medium), "cards")
        assertEquals(List(4) { 22f }, corners(CosmoMaterialShapes.large), "large containers")
        assertEquals(List(4) { 28f }, corners(CosmoMaterialShapes.extraLarge), "dialogs and sheets")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun theRippleIsTheBControlsStateLayers() {
        assertEquals(Color.Unspecified, CosmoRipple.color, "the ripple takes the content colour")
        val alpha = requireNotNull(CosmoRipple.rippleAlpha)
        assertEquals(0.08f, alpha.hoveredAlpha)
        assertEquals(0.12f, alpha.pressedAlpha)
        assertEquals(0.12f, alpha.focusedAlpha)
        assertEquals(0.16f, alpha.draggedAlpha)
    }

    // ---- the theme file: what no render shows ----

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val themePath = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/theme/CosmoLookTheme.kt"

    private fun read(path: String): String {
        val file = File(root, path)
        if (!file.isFile) fail("$path is not where this test expects it")
        return file.readText().replace("\r\n", "\n")
    }

    /** The text between the braces of the function whose declaration starts with [marker]. Its parameter list has no braces. */
    private fun functionBody(text: String, marker: String): String {
        val at = text.indexOf(marker)
        if (at < 0) fail("no `$marker` found")
        val open = text.indexOf('{', at)
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return text.substring(open + 1, i)
        }
        fail("unbalanced braces after `$marker`")
    }

    /** The text of the call whose opening bracket is the first `(` at or after [from]. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from)
        var depth = 0
        for (i in start until text.length) {
            if (text[i] == open) depth++
            if (text[i] == close && --depth == 0) return text.substring(start + 1, i)
        }
        fail("unbalanced $open$close")
    }

    /** What each `if (...)` governs: its brace block, or the rest of its line. */
    private fun ifBodies(text: String): List<String> = Regex("""\bif\s*\(""").findAll(text).map { m ->
        val afterCondition = m.range.last + balanced(text, m.range.last, '(', ')').length + 2
        val rest = text.substring(afterCondition).trimStart()
        if (rest.startsWith("{")) balanced(rest, 0, '{', '}') else rest.substringBefore('\n')
    }.toList()

    private val themeBody get() = functionBody(read(themePath), "fun CosmoLookTheme(")

    @Test fun typographyAndShapesDependOnTheSwitchAndTheContentIsNeverInsideAnIf() {
        val body = themeBody
        val materialTheme = balanced(body, body.indexOf("MaterialTheme("), '(', ')')
        val typography = materialTheme.substringAfter("typography =").substringBefore("shapes =").trim()
        val shapes = materialTheme.substringAfter("shapes =").trim()
        assertTrue(typography.startsWith("if (newLook)"), "typography depends on newLook: $typography")
        assertTrue("legacyTypography" in typography.substringAfter("else"), "the old branch is the legacy typography")
        assertTrue(shapes.startsWith("if (newLook) CosmoMaterialShapes else legacyShapes"), "shapes depend on newLook: $shapes")
        assertTrue("MaterialTheme.typography" in body && "MaterialTheme.shapes" in body, "the legacy values are read from the legacy theme")

        val contentWord = Regex("""\bcontent\b""")
        for (governed in ifBodies(body)) assertFalse(contentWord.containsMatchIn(governed), "content inside an if: $governed")
        assertEquals(1, Regex("""content = content""").findAll(body).count(), "content is passed once, as the provider's own content")
        assertEquals(1, Regex("""\blegacy\s*\{""").findAll(body).count())
    }

    @Test fun theTypeIsMadeOnceAboveTheThemeAndTheContentColourAndRippleAreProvidedWhicheverWayTheSwitchIs() {
        val body = themeBody
        assertTrue(body.indexOf("val type = rememberCosmoType()") in 0 until body.indexOf("MaterialTheme("), "the type is hoisted above MaterialTheme(")
        assertEquals(1, Regex("""\brememberCosmoType\(""").findAll(body).count())
        assertEquals(1, Regex("""LocalCosmoType provides type\b""").findAll(body).count(), "one provider of the type")
        val provider = balanced(body, body.indexOf("CompositionLocalProvider("), '(', ')')
        assertTrue("LocalContentColor provides (if (newLook) Color(target.textPrimary) else LocalContentColor.current)" in provider)
        assertTrue("LocalRippleConfiguration provides (if (newLook) CosmoRipple else LocalRippleConfiguration.current)" in provider)
    }
}
