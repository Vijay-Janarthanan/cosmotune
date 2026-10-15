package com.cosmotune.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteBlendTest {
    private fun close(a: Int, b: Int) = (0..2).all { s -> kotlin.math.abs(((a shr (8 * s)) and 0xFF) - ((b shr (8 * s)) and 0xFF)) <= 1 }
    private fun closeWithAlpha(a: Int, b: Int) = close(a, b) && kotlin.math.abs((a ushr 24) - (b ushr 24)) <= 1
    private val black = 0xFF000000.toInt(); private val white = 0xFFFFFFFF.toInt(); private val red = 0xFFD4552E.toInt()

    @Test fun theEndsAreTheColoursThemselves() {
        val b = PaletteBlend(intArrayOf(black)); b.retarget(intArrayOf(red), 0f)
        assertTrue(close(black, b.argbAt(0, 0f))); assertTrue(close(red, b.argbAt(0, 1f)))
    }
    @Test fun halfWayIsTheOklabMiddle() {
        val b = PaletteBlend(intArrayOf(black)); b.retarget(intArrayOf(white), 0f)
        assertTrue(close(0xFF636363.toInt(), b.argbAt(0, 0.5f)), "OKLab L 0.5 is #636363")
    }
    @Test fun aNewTargetStartsFromWhatIsOnScreen() {
        val b = PaletteBlend(intArrayOf(black)); b.retarget(intArrayOf(white), 0f)
        val shown = b.argbAt(0, 0.5f)
        b.retarget(intArrayOf(red), 0.5f)
        assertTrue(close(shown, b.argbAt(0, 0f)))
    }

    // A translucent colour (the shadow) is blended with premultiplied alpha, so a retarget in the middle of a fade must
    // start from that same premultiplied colour, not from a straight mix of the two ends: nothing may jump.
    @Test fun aNewTargetStartsFromWhatIsOnScreenAlsoForTranslucentColours() {
        val shadow = 0xA6402010.toInt(); val glass = 0x1FFFFFFF
        val b = PaletteBlend(intArrayOf(shadow)); b.retarget(intArrayOf(glass), 0f)
        for (t in listOf(0.2f, 0.5f, 0.8f)) {
            val shown = b.argbAt(0, t)
            val again = PaletteBlend(intArrayOf(shadow)); again.retarget(intArrayOf(glass), 0f)
            again.retarget(intArrayOf(red), t)
            assertTrue(closeWithAlpha(shown, again.argbAt(0, 0f)), "t=$t shown ${shown.toUInt().toString(16)} then ${again.argbAt(0, 0f).toUInt().toString(16)}")
        }
    }
    @Test fun retargetingFromNothingShownTakesTheNewColourStraightAway() {
        val b = PaletteBlend(intArrayOf(0x00000000))
        b.retarget(intArrayOf(red), 1f)
        assertTrue(closeWithAlpha(red, b.argbAt(0, 1f)))
        assertEquals(0, PaletteBlend(intArrayOf(0x00000000)).argbAt(0, 0.5f))
    }

    @Test fun anOutOfRangeFractionIsHeld() {
        val b = PaletteBlend(intArrayOf(black)); b.retarget(intArrayOf(red), 0f)
        assertEquals(b.argbAt(0, 0f), b.argbAt(0, -3f)); assertEquals(b.argbAt(0, 1f), b.argbAt(0, 7f))
    }

    @Test fun everyFieldOfAPaletteIsBlendedLikeTheEnginesOwnLerp() {
        val a = CoverPalette.fromPixels(IntArray(64 * 64) { 0xFF466044.toInt() }, 64, 64, dark = true)
        val z = CoverPalette.fromPixels(IntArray(64 * 64) { 0xFFD4552E.toInt() }, 64, 64, dark = true)
        val blend = PaletteBlend(a.toFieldArray()); blend.retarget(z.toFieldArray(), 0f)
        for (t in listOf(0.25f, 0.5f, 0.75f)) {
            val engine = CoverPalette.lerp(a, z, t).toFieldArray()
            for (field in 0 until CoverField.COUNT) {
                val ours = blend.argbAt(field, t)
                // The shadow is translucent: the engine mixes its channels straight, the blend premultiplied. The rest is opaque.
                if (field == CoverField.SHADOW) {
                    assertTrue(kotlin.math.abs((ours ushr 24) - (engine[field] ushr 24)) <= 1)
                } else {
                    assertTrue(closeWithAlpha(ours, engine[field]), "field $field at $t: ${ours.toUInt().toString(16)} vs ${engine[field].toUInt().toString(16)}")
                }
            }
        }
    }

    @Test fun theMathRoundTripsEightBitColours() {
        val lab = DoubleArray(4)
        for (c in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFD4552E.toInt(), 0xFF466044.toInt(), 0x80123456.toInt(), 0xFF00E5CC.toInt())) {
            OklabMath.fromArgb(c, lab, 0)
            val back = OklabMath.toArgb(lab[0], lab[1], lab[2], lab[3])
            assertTrue(closeWithAlpha(c, back), "round trip of ${c.toUInt().toString(16)} gave ${back.toUInt().toString(16)}")
        }
    }
}
