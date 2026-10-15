package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fluid modular scale of `02-design-formulas.md` section 1, against its table and its worked example. */
class TypeScaleTest {

    private fun assertSp(expected: Float, actual: Float, message: String) = assertEquals(expected, actual, 0.01f, message)

    @Test
    fun bodyGrowsFromFifteenToSixteen() {
        assertSp(15f, TypeScale.sizeSp(TypeScale.BODY, 360f), "body at 360")
        assertSp(15.5f, TypeScale.sizeSp(TypeScale.BODY, 600f), "body at 600")
        assertSp(16f, TypeScale.sizeSp(TypeScale.BODY, 840f), "body at 840")
    }

    @Test
    fun theWorkedExampleIsDisplayLargeOnA411DpPhone() {
        assertSp(46.52f, TypeScale.sizeSp(TypeScale.DISPLAY_L, 411f), "display-l at 411")
    }

    @Test
    fun theEndsOfTheScale() {
        assertSp(12.5f, TypeScale.sizeSp(TypeScale.CAPTION, 360f), "caption at 360")
        assertSp(76.29f, TypeScale.sizeSp(TypeScale.DISPLAY_XL, 840f), "display-xl at 840")
    }

    @Test
    fun everyRowOfTheDesignTableAtTheTwoEnds() {
        // size(n) = 15 x 1.2^n at 360 and 16 x 1.25^n at 840 (the table's 360 and 840 columns, to 0.01).
        val at360 = floatArrayOf(12.50f, 15.00f, 18.00f, 21.60f, 25.92f, 31.10f, 37.32f, 44.79f, 53.75f)
        val at840 = floatArrayOf(12.80f, 16.00f, 20.00f, 25.00f, 31.25f, 39.06f, 48.83f, 61.04f, 76.29f)
        for (i in at360.indices) {
            val step = TypeScale.CAPTION + i
            assertSp(at360[i], TypeScale.sizeSp(step, 360f), "step $step at 360")
            assertSp(at840[i], TypeScale.sizeSp(step, 840f), "step $step at 840")
        }
    }

    @Test
    fun theStepConstantsRunFromMinusOneToSeven() {
        assertEquals(-1, TypeScale.CAPTION)
        assertEquals(0, TypeScale.BODY)
        assertEquals(1, TypeScale.TITLE_S)
        assertEquals(2, TypeScale.TITLE_M)
        assertEquals(3, TypeScale.TITLE_L)
        assertEquals(4, TypeScale.DISPLAY_S)
        assertEquals(5, TypeScale.DISPLAY_M)
        assertEquals(6, TypeScale.DISPLAY_L)
        assertEquals(7, TypeScale.DISPLAY_XL)
    }

    @Test
    fun widthsOutsideTheRangeClampToTheEnds() {
        for (step in TypeScale.CAPTION..TypeScale.DISPLAY_XL) {
            assertSp(TypeScale.sizeSp(step, 360f), TypeScale.sizeSp(step, 280f), "step $step below 360")
            assertSp(TypeScale.sizeSp(step, 840f), TypeScale.sizeSp(step, 1920f), "step $step above 840")
        }
    }

    @Test
    fun theSizeNeverShrinksAsTheWindowGrows() {
        for (step in TypeScale.CAPTION..TypeScale.DISPLAY_XL) {
            var last = 0f
            for (w in 300..900 step 10) {
                val size = TypeScale.sizeSp(step, w.toFloat())
                assertTrue(size >= last, "step $step at $w")
                last = size
            }
        }
    }

    @Test
    fun longTitlesStepDownToDisplaySmall() {
        assertEquals(TypeScale.DISPLAY_L, TypeScale.titleStep("Glasshouse Weather"))
        assertEquals(TypeScale.DISPLAY_S, TypeScale.titleStep("The Last Train Out of Bandra (Live)"))
    }

    @Test
    fun theLongTitleLimitIsTwentySixCharacters() {
        assertEquals(TypeScale.DISPLAY_L, TypeScale.titleStep("a".repeat(26)))
        assertEquals(TypeScale.DISPLAY_S, TypeScale.titleStep("a".repeat(27)))
    }

    @Test
    fun indianScriptsNeedTallLines() {
        assertTrue(TypeScale.needsTallLines("மழைக்குப் பின்"))
        assertTrue(TypeScale.needsTallLines("नमक"))
        assertFalse(TypeScale.needsTallLines("Overpass"))
    }

    @Test
    fun tallLinesCoverDevanagariToMalayalamAndNothingBeside() {
        assertTrue(TypeScale.needsTallLines("ऀ"))
        assertTrue(TypeScale.needsTallLines("ൿ"))
        assertFalse(TypeScale.needsTallLines("ࣿ"))
        assertFalse(TypeScale.needsTallLines("඀"))
        assertFalse(TypeScale.needsTallLines(""))
    }
}
