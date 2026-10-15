package com.cosmotune.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.theme.CosmoFontSpecs
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.tabular
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CosmoTypeTest {
    private val display = FontFamily.Serif
    private val text = FontFamily.SansSerif
    private val phone = CosmoType(411f, display, text)

    private fun near(expected: Float, actual: Float, eps: Float = 0.01f) = assertTrue(abs(expected - actual) <= eps, "$expected vs $actual")

    @Test fun sizesFollowTheScaleAtTheWindowWidth() {
        near(46.516f, phone.size(TypeScale.DISPLAY_L).value)
        near(15.106f, phone.size(TypeScale.BODY).value)
        assertEquals(phone.size(TypeScale.BODY).value, TypeScale.sizeSp(TypeScale.BODY, 411f))
    }

    @Test fun displayIsArchivoWeight820WithTightLines() {
        val s = phone.display(TypeScale.DISPLAY_L)
        assertSame(display, s.fontFamily)
        assertEquals(FontWeight(820), s.fontWeight)
        near(s.fontSize.value * 0.94f, s.lineHeight.value, 0.001f)
        assertEquals(-0.03f, s.letterSpacing.value)
        assertEquals(-0.024f, phone.display(TypeScale.DISPLAY_M).letterSpacing.value)
        assertEquals(-0.03f, phone.display(TypeScale.DISPLAY_XL).letterSpacing.value)
    }

    @Test fun titlesAreWeight720AndTitleSIsTheTextFace() {
        val l = phone.title(TypeScale.TITLE_L)
        assertSame(display, l.fontFamily)
        assertEquals(FontWeight(720), l.fontWeight)
        near(l.fontSize.value * 1.08f, l.lineHeight.value, 0.001f)
        assertEquals(-0.012f, l.letterSpacing.value)
        val s = phone.title(TypeScale.TITLE_S)
        assertSame(text, s.fontFamily)
        assertEquals(FontWeight(650), s.fontWeight)
        near(s.fontSize.value * 1.25f, s.lineHeight.value, 0.001f)
    }

    @Test fun bodyAndLabelUseTheTextFamily() {
        val b = phone.body()
        assertSame(text, b.fontFamily)
        assertEquals(FontWeight.Normal, b.fontWeight)
        near(b.fontSize.value * 1.42f, b.lineHeight.value, 0.001f)
        assertEquals(FontWeight.Medium, phone.body(weight = FontWeight.Medium).fontWeight)
        assertEquals(14.sp, phone.label.fontSize)
        assertEquals(FontWeight(600), phone.label.fontWeight)
        assertEquals(15.sp, phone.button.fontSize)
        assertEquals(FontWeight(680), phone.button.fontWeight)
        assertSame(display, phone.mood.fontFamily)
    }

    @Test fun theTitlePathHasSixInstances() {
        assertEquals(listOf(300, 420, 540, 660, 760, 820), (0..5).map { phone.titlePath(it).weight })
    }

    @Test fun everyWeightAStyleAsksForIsAnInstanceOfItsFamily() {
        val archivo = CosmoFontSpecs.display.map { it.weight }
        listOf(phone.display(TypeScale.DISPLAY_L), phone.title(TypeScale.TITLE_L), phone.mood).forEach {
            assertTrue(it.fontWeight!!.weight in archivo, "Archivo has ${it.fontWeight}")
        }
        (0..5).forEach { assertTrue(phone.titlePath(it).weight in archivo) }
        assertEquals(archivo.size, archivo.toSet().size, "one instance per weight")
        val hanken = CosmoFontSpecs.textWeights
        listOf(phone.label, phone.button, phone.title(TypeScale.TITLE_S), phone.body(), phone.body(weight = FontWeight.Medium)).forEach {
            assertTrue(it.fontWeight!!.weight in hanken, "Hanken Grotesk has ${it.fontWeight}")
        }
    }

    @Test fun indianScriptsGetLineHeight1Point5() {
        val body = phone.body()
        val tamil = phone.forText(body, "இசை")
        near(body.fontSize.value * 1.5f, tamil.lineHeight.value, 0.001f)
        assertSame(body, phone.forText(body, "Music"))
    }

    @Test fun tabularFiguresAreSwitchedOnByTheFeature() {
        assertEquals("tnum", phone.label.tabular().fontFeatureSettings)
    }

    @Test fun theTokensAreTheDesignersNumbers() {
        assertEquals(listOf(4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96).map { it.dp }, with(CosmoSpacing) { listOf(s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12) })
        assertEquals(listOf(8, 12, 16, 22, 28, 32).map { it.dp }, with(CosmoRadii) { listOf(xs, sm, md, lg, xl, xxl) })
        assertEquals(listOf(28, 14, 9, 12, 12, 14).map { it.dp }, with(CosmoRadii) { listOf(coverNowPlaying, coverMini, coverChip, coverRow, coverDesktopBar, coverDesktopHead) })
        assertEquals(8.dp, CosmoRadii.coverByFormula(52.dp), "the formula would round the 52 dp mini cover down to 8, the screen draws 14")
        assertEquals(28.dp, CosmoRadii.coverByFormula(300.dp))
    }

    @Test fun reducedMotionTurnsEverySpringIntoAFade() {
        val specs = listOf(CosmoMotion.press<Float>(true), CosmoMotion.move<Float>(true), CosmoMotion.slow<Float>(true), CosmoMotion.effects<Float>(true), CosmoMotion.palette(true))
        specs.forEach { assertTrue(it is TweenSpec<*>, "$it") }
        listOf(CosmoMotion.press<Float>(false), CosmoMotion.move<Float>(false), CosmoMotion.slow<Float>(false), CosmoMotion.effects<Float>(false))
            .forEach { assertTrue(it is SpringSpec<*>, "$it") }
        val press = CosmoMotion.press<Float>(false) as SpringSpec<Float>
        assertEquals(0.6f, press.dampingRatio)
        assertEquals(800f, press.stiffness)
    }
}
