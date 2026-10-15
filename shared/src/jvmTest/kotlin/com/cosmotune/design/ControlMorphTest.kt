package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shape-morph table of `02-design-formulas.md` section 3, row by row (radius dp, superellipse exponent, scale). */
class ControlMorphTest {

    private fun assertTarget(radius: Float, exponent: Float, scale: Float, actual: MorphTarget, what: String) {
        assertEquals(radius, actual.radiusDp, 0.001f, "$what radius")
        assertEquals(exponent, actual.exponent, 0.001f, "$what exponent")
        assertEquals(scale, actual.scale, 0.001f, "$what scale")
    }

    @Test
    fun playButtonEighty() {
        assertTarget(40f, 2f, 1f, ControlMorph.playButton(80f, playing = false, pressed = false), "rest")
        assertTarget(24f, 4f, 1f, ControlMorph.playButton(80f, playing = true, pressed = false), "playing")
        assertTarget(16f, 4f, 0.92f, ControlMorph.playButton(80f, playing = true, pressed = true), "pressed")
        assertTarget(16f, 4f, 0.92f, ControlMorph.playButton(80f, playing = false, pressed = true), "pressed paused")
    }

    @Test
    fun playButtonFortyFourInTheMiniPlayer() {
        assertTarget(22f, 2f, 1f, ControlMorph.playButton(44f, playing = false, pressed = false), "rest")
        assertTarget(13.2f, 4f, 1f, ControlMorph.playButton(44f, playing = true, pressed = false), "playing")
        assertTarget(8.8f, 4f, 0.92f, ControlMorph.playButton(44f, playing = true, pressed = true), "pressed")
    }

    @Test
    fun iconButtonFortyEight() {
        assertTarget(24f, 2f, 1f, ControlMorph.iconButton(48f, on = false, pressed = false), "rest")
        assertTarget(16f, 4f, 1f, ControlMorph.iconButton(48f, on = true, pressed = false), "on")
        assertTarget(14f, 4f, 0.94f, ControlMorph.iconButton(48f, on = true, pressed = true), "pressed")
    }

    @Test
    fun chipThirtySix() {
        assertTarget(18f, 2f, 1f, ControlMorph.chip(36f, selected = false, pressed = false), "rest")
        assertTarget(10f, 4f, 1f, ControlMorph.chip(36f, selected = true, pressed = false), "selected")
        assertTarget(16f, 4f, 1f, ControlMorph.chip(36f, selected = false, pressed = true), "pressed")
    }

    @Test
    fun segmentForty() {
        assertTarget(20f, 2f, 1f, ControlMorph.segment(40f, selected = true, pressed = false), "selected")
        assertTarget(6f, 4f, 1f, ControlMorph.segment(40f, selected = false, pressed = false), "unselected inner corner")
        assertTarget(16f, 4f, 1f, ControlMorph.segment(40f, selected = false, pressed = true), "pressed")
    }

    @Test
    fun primaryButton() {
        assertTarget(20f, 2f, 1f, ControlMorph.primary(40f, pressed = false), "rest")
        assertTarget(16f, 4f, 0.96f, ControlMorph.primary(40f, pressed = true), "pressed")
    }

    @Test
    fun aPressedControlShrinksAndARestingOneDoesNot() {
        assertTrue(ControlMorph.playButton(80f, true, true).scale < 1f)
        assertTrue(ControlMorph.iconButton(48f, false, true).scale < 1f)
        assertTrue(ControlMorph.primary(48f, true).scale < 1f)
        assertEquals(1f, ControlMorph.playButton(80f, true, false).scale)
        assertEquals(1f, ControlMorph.primary(48f, false).scale)
    }
}
