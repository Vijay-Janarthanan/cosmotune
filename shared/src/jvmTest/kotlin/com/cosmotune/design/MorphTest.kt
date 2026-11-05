package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mini player growing into Now Playing (`v2.js` `V2.morph`) on an 844 dp frame: capsule top 690 (844 - 90 - 64),
 * height 64, left 16, radius 22.
 */
class MorphTest {

    private val t = 0.35f

    @Test
    fun theCapsuleRectAtThirtyFivePercent() {
        assertEquals(448.5f, Morph.lerp(690f, 0f, t), 0.01f, "top")
        assertEquals(337f, Morph.lerp(64f, 844f, t), 0.01f, "height")
        assertEquals(10.4f, Morph.lerp(16f, 0f, t), 0.01f, "left")
    }

    @Test
    fun theCornerRadiusShrinksToZero() {
        assertEquals(14.3f, Morph.cornerRadius(t, 22f), 0.01f)
        assertEquals(22f, Morph.cornerRadius(0f, 22f), 1e-6f)
        assertEquals(0f, Morph.cornerRadius(1f, 22f), 1e-6f)
    }

    @Test
    fun theCapsuleTextFadesOutByTheMiddle() {
        assertEquals(0.23f, Morph.capsuleTextAlpha(t), 0.005f)
        assertEquals(1f, Morph.capsuleTextAlpha(0f), 1e-6f)
        assertEquals(0f, Morph.capsuleTextAlpha(0.5f), 1e-6f)
        assertEquals(0f, Morph.capsuleTextAlpha(1f), 1e-6f)
    }

    @Test
    fun thePlayerContentFadesInOverTheLastFifth() {
        assertEquals(0f, Morph.playerContentAlpha(0.7f), 1e-6f)
        assertEquals(0f, Morph.playerContentAlpha(0.8f), 1e-6f)
        assertEquals(0.5f, Morph.playerContentAlpha(0.9f), 0.001f)
        assertEquals(1f, Morph.playerContentAlpha(1f), 1e-6f)
    }

    @Test
    fun lerpHitsBothEnds() {
        assertEquals(5f, Morph.lerp(5f, 9f, 0f), 1e-6f)
        assertEquals(9f, Morph.lerp(5f, 9f, 1f), 1e-6f)
    }

    @Test
    fun aSpringThatOvershootsProgressStillGivesBoundedAlphas() {
        assertEquals(1f, Morph.playerContentAlpha(1.05f), 1e-6f)
        assertEquals(1f, Morph.capsuleTextAlpha(-0.05f), 1e-6f)
    }
}
