package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape-morph numbers of the B+C spec (`v3/01-components-spec-bc.md`): radius = k x height, superellipse exponent 4
 * unless the control is round (k = 0.5, exponent 2), row by row.
 */
class ControlMorphTest {

    private fun assertTarget(radius: Float, exponent: Float, scale: Float, actual: MorphTarget, what: String, scaleY: Float = scale) {
        assertEquals(radius, actual.radiusDp, 0.001f, "$what radius")
        assertEquals(exponent, actual.exponent, 0.001f, "$what exponent")
        assertEquals(scale, actual.scale, 0.001f, "$what scale")
        assertEquals(scaleY, actual.scaleY, 0.001f, "$what scaleY")
    }

    // --- B: in the page --------------------------------------------------------------------------

    @Test
    fun heroPlayButtonEighty() {
        assertTarget(25.6f, 4f, 1f, ControlMorph.playButton(80f, playing = false, pressed = false), "paused")
        assertTarget(40f, 2f, 1f, ControlMorph.playButton(80f, playing = true, pressed = false), "playing is a circle")
        assertTarget(16f, 4f, 0.92f, ControlMorph.playButton(80f, playing = true, pressed = true), "pressed")
        assertTarget(16f, 4f, 0.92f, ControlMorph.playButton(80f, playing = false, pressed = true), "pressed paused")
    }

    @Test
    fun heroPlayButtonOnACard() {
        assertTarget(20.48f, 4f, 1f, ControlMorph.playButton(64f, playing = false, pressed = false), "paused")
        assertTarget(32f, 2f, 1f, ControlMorph.playButton(64f, playing = true, pressed = false), "playing")
    }

    @Test
    fun iconButtonFortyEight() {
        assertTarget(14.4f, 4f, 1f, ControlMorph.iconButton(48f, on = false, pressed = false), "rest")
        assertTarget(24f, 2f, 1f, ControlMorph.iconButton(48f, on = true, pressed = false), "on turns round")
        assertTarget(9.6f, 4f, 0.94f, ControlMorph.iconButton(48f, on = true, pressed = true), "pressed")
        assertTarget(9.6f, 4f, 0.94f, ControlMorph.iconButton(48f, on = false, pressed = true), "pressed off")
    }

    @Test
    fun filterChipThirtySix() {
        assertTarget(7.92f, 4f, 1f, ControlMorph.chip(36f, selected = false, pressed = false), "rest")
        assertTarget(18f, 2f, 1f, ControlMorph.chip(36f, selected = true, pressed = false), "selected turns round")
        assertTarget(5.04f, 4f, 1f, ControlMorph.chip(36f, selected = false, pressed = true), "pressed")
    }

    @Test
    fun segmentForty() {
        assertTarget(20f, 2f, 1f, ControlMorph.segment(40f, selected = true, pressed = false), "selected is a full pill")
        assertTarget(6f, 4f, 1f, ControlMorph.segment(40f, selected = false, pressed = false), "unselected inner corner")
        assertTarget(16f, 4f, 1f, ControlMorph.segment(40f, selected = false, pressed = true), "pressed")
    }

    @Test
    fun primaryButtonSquaresUpAndSquishesSidewaysOnly() {
        assertTarget(14.4f, 4f, 1f, ControlMorph.primary(48f, pressed = false), "rest 48")
        assertTarget(8.64f, 4f, 0.965f, ControlMorph.primary(48f, pressed = true), "pressed 48", scaleY = 1f)
        assertTarget(16.8f, 4f, 1f, ControlMorph.primary(56f, pressed = false), "rest 56")
        assertTarget(12f, 4f, 1f, ControlMorph.primary(40f, pressed = false), "rest 40")
    }

    // --- C: floating -------------------------------------------------------------------------------

    @Test
    fun floatingPlayButtonIsACircleWhenPausedAndSquarerWhilePlaying() {
        assertTarget(40f, 2f, 1f, ControlMorph.playButton(80f, playing = false, pressed = false, floating = true), "paused")
        assertTarget(25.6f, 4f, 1f, ControlMorph.playButton(80f, playing = true, pressed = false, floating = true), "playing")
        assertTarget(20.8f, 4f, 0.92f, ControlMorph.playButton(80f, playing = true, pressed = true, floating = true), "pressed")
        assertTarget(14.08f, 4f, 1f, ControlMorph.playButton(44f, playing = true, pressed = false, floating = true), "mini playing")
    }

    @Test
    fun floatingPrimaryIsAPillThatScalesUniformly() {
        assertTarget(24f, 2f, 1f, ControlMorph.primary(48f, pressed = false, floating = true), "rest")
        assertTarget(24f, 2f, 0.97f, ControlMorph.primary(48f, pressed = true, floating = true), "pressed")
    }

    @Test
    fun floatingIconButtonAndChipStayRound() {
        assertTarget(24f, 2f, 1f, ControlMorph.iconButton(48f, on = true, pressed = false, floating = true), "icon on")
        assertTarget(24f, 2f, 0.94f, ControlMorph.iconButton(48f, on = false, pressed = true, floating = true), "icon pressed")
        assertTarget(18f, 2f, 1f, ControlMorph.chip(36f, selected = false, pressed = false, floating = true), "chip rest")
        assertTarget(18f, 2f, 1f, ControlMorph.chip(36f, selected = true, pressed = true, floating = true), "chip selected pressed")
    }

    @Test
    fun aPressedControlShrinksAndARestingOneDoesNot() {
        assertTrue(ControlMorph.playButton(80f, true, true).scale < 1f)
        assertTrue(ControlMorph.iconButton(48f, false, true).scale < 1f)
        assertTrue(ControlMorph.primary(48f, true).scale < 1f)
        assertTrue(ControlMorph.primary(48f, true, floating = true).scale < 1f)
        assertEquals(1f, ControlMorph.playButton(80f, true, false).scale)
        assertEquals(1f, ControlMorph.primary(48f, false).scale)
    }

    @Test
    fun theRadiusNeverExceedsHalfTheHeight() {
        for (h in floatArrayOf(36f, 40f, 44f, 48f, 56f, 64f, 80f)) for (pressed in listOf(false, true)) for (flag in listOf(false, true)) {
            for (t in listOf(
                ControlMorph.playButton(h, flag, pressed), ControlMorph.playButton(h, flag, pressed, floating = true),
                ControlMorph.iconButton(h, flag, pressed), ControlMorph.chip(h, flag, pressed), ControlMorph.primary(h, pressed),
                ControlMorph.primary(h, pressed, floating = true), ControlMorph.segment(h, flag, pressed),
            )) assertTrue(t.radiusDp <= h / 2f + 1e-4f, "radius ${t.radiusDp} at height $h")
        }
    }
}
