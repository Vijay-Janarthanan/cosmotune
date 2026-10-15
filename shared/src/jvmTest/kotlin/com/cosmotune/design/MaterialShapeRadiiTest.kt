package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The corner sizes the Material theme takes. Material's `Shapes` draws circular corners only, and "a superellipse corner
 * reads smaller than a circular arc of the same radius, so radii are about 1.3 times the old circular values"
 * (`02-design-formulas.md` section 3), so each B+C radius is divided by 1.3 and rounded.
 */
class MaterialShapeRadiiTest {

    @Test
    fun theFiveMaterialSlotsAreTheCircularEquivalentsOfTheBcRadii() {
        assertEquals(17f, MaterialShapeRadii.EXTRA_SMALL, "menu, 22")
        assertEquals(9f, MaterialShapeRadii.SMALL, "chip, 12")
        assertEquals(18f, MaterialShapeRadii.MEDIUM, "card, 24")
        assertEquals(22f, MaterialShapeRadii.LARGE, "tile, 28")
        assertEquals(28f, MaterialShapeRadii.EXTRA_LARGE, "sheet, 36")
    }

    @Test
    fun aSuperellipseRadiusDividedByOnePointThreeAndRounded() {
        assertEquals(10f, MaterialShapeRadii.circularFor(13f))
        assertEquals(17f, MaterialShapeRadii.circularFor(22f))
        assertEquals(9f, MaterialShapeRadii.circularFor(12f))
        assertEquals(0f, MaterialShapeRadii.circularFor(0f))
    }

    @Test
    fun theSlotsGrowWithTheirSize() {
        val all = listOf(MaterialShapeRadii.SMALL, MaterialShapeRadii.EXTRA_SMALL, MaterialShapeRadii.MEDIUM, MaterialShapeRadii.LARGE, MaterialShapeRadii.EXTRA_LARGE)
        assertEquals(all.sorted(), all)
    }
}
