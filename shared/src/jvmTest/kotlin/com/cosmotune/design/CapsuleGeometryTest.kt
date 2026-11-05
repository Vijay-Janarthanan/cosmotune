package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The capsule describes itself: what the player that grows out of it starts from (the card's corners, the cover's place, size
 * and corners, where the words begin and end) is the capsule's own, so the first frame of the grow is the capsule, to the pixel,
 * whichever capsule it is (the phone's mini player or the desktop's player bar).
 */
class CapsuleGeometryTest {

    // ---- the two capsules --------------------------------------------------------------------------------------------

    @Test
    fun thePhoneCapsuleIsWhatTheOverlayAlwaysAssumed() {
        val g = CapsuleGeometry.PHONE
        assertEquals(22f, g.cornerRadiusDp, 0f)
        assertEquals(ShellMetrics.CAPSULE_RADIUS_DP, g.cornerRadiusDp, 0f)
        assertEquals(8f, g.coverInsetLeftDp, 0f)
        assertEquals(8f, g.coverInsetTopDp, 0f)
        assertEquals(48f, g.coverSizeDp, 0f)
        assertEquals(14f, g.coverRadiusDp, 0f)
        // 8 dp of padding, the 48 dp cover and a 12 dp gap; next (48 dp with its touch target), 8 dp, play (48 dp) and 8 dp of padding
        assertEquals(68f, g.textStartDp, 0f)
        assertEquals(112f, g.textEndDp, 0f)
        // the cover sits in the middle of the capsule's height
        assertEquals((ShellMetrics.CAPSULE_HEIGHT_DP - g.coverSizeDp) / 2f, g.coverInsetTopDp, 0f)
    }

    @Test
    fun theDesktopBarIsNotThePhoneCapsule() {
        val g = PlayerBarMetrics.capsuleGeometry(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 908f)
        assertEquals(28f, g.cornerRadiusDp, 0f)
        assertEquals(16f, g.coverInsetLeftDp, 0f)
        assertEquals(16f, g.coverInsetTopDp, 0f)
        assertEquals(48f, g.coverSizeDp, 0f)
        assertEquals(12f, g.coverRadiusDp, 0f)
        assertEquals(76f, g.textStartDp, 0f)
        // the title column is 220 dp wide in the full plan: what is right of it is the capsule's width less 76 and 220
        assertEquals(908f - 76f - 220f, g.textEndDp, 0f)
        assertTrue(g != CapsuleGeometry.PHONE)
    }

    @Test
    fun theBarsGeometryIsItsOwnLayoutAtEveryWidthAndPlan() {
        for (widthDp in 368..2400 step 8) {
            val w = widthDp.toFloat()
            val plan = PlayerBarMetrics.plan(w)
            val g = PlayerBarMetrics.capsuleGeometry(plan, w)
            val at = "at $w ($plan)"
            assertEquals(PlayerBarMetrics.RADIUS_DP, g.cornerRadiusDp, 0f, at)
            // the cover is 16 dp in, and centred in the 80 dp bar
            assertEquals(PlayerBarMetrics.PADDING_DP, g.coverInsetLeftDp, 0f, at)
            assertEquals((PlayerBarMetrics.HEIGHT_DP - PlayerBarMetrics.COVER_DP) / 2f, g.coverInsetTopDp, 0f, at)
            // the words start after the cover and its gap, and the column that holds them is inside the capsule
            assertEquals(g.coverInsetLeftDp + g.coverSizeDp + PlayerBarMetrics.GAP_DP, g.textStartDp, 0f, at)
            assertTrue(g.textEndDp >= 0f, "the words end inside the capsule $at")
            val column = w - g.textStartDp - g.textEndDp
            if (plan.tier == PlayerBarMetrics.Tier.MINIMAL) {
                assertTrue(column >= PlayerBarMetrics.MINIMAL_TEXT_DP - 0.001f, "the narrowest plan keeps its least title column $at: $column")
            } else {
                assertEquals(plan.textDp, column, 0.001f, "the column is the plan's own $at")
            }
        }
    }

    // ---- the grow starts from the capsule --------------------------------------------------------------------------------

    private val phoneCapsuleLeft = 16f
    private val phoneCapsuleTop = 690f
    private val density = 2.625f

    @Test
    fun theCardStartsOnTheCapsulesRectangleAndCorners() {
        for (g in listOf(CapsuleGeometry.PHONE, PlayerBarMetrics.capsuleGeometry(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 908f))) {
            val radiusPx = g.cornerRadiusDp * density
            // the card's rectangle and corners at t = 0 are exactly the capsule's own
            assertEquals(phoneCapsuleLeft, Morph.lerp(phoneCapsuleLeft, 0f, 0f), 0f)
            assertEquals(phoneCapsuleTop, Morph.lerp(phoneCapsuleTop, 0f, 0f), 0f)
            assertEquals(radiusPx, Morph.cornerRadius(0f, radiusPx), 0f)
            // and at t = 1 it is square
            assertEquals(0f, Morph.cornerRadius(1f, radiusPx), 0f)
        }
    }

    @Test
    fun theCoverStartsWhereTheCapsulesCoverIsExactly() {
        for (g in listOf(CapsuleGeometry.PHONE, PlayerBarMetrics.capsuleGeometry(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 908f))) {
            val origin = 0f
            val fromX = Morph.insetFrom(phoneCapsuleLeft, origin, g.coverInsetLeftDp * density)
            val fromY = Morph.insetFrom(phoneCapsuleTop, origin, g.coverInsetTopDp * density)
            val toX = 120f
            val toY = 300f
            // the frame at t = 0 is the capsule's cover, to the pixel; at t = 1 it is the player's cover
            assertEquals(phoneCapsuleLeft + g.coverInsetLeftDp * density, Morph.lerp(fromX, toX, 0f), 0f)
            assertEquals(phoneCapsuleTop + g.coverInsetTopDp * density, Morph.lerp(fromY, toY, 0f), 0f)
            assertEquals(toX, Morph.lerp(fromX, toX, 1f), 1e-3f)
            assertEquals(toY, Morph.lerp(fromY, toY, 1f), 1e-3f)
        }
    }

    @Test
    fun theCoverStartsAtTheCapsulesCoverSizeAndCornerOnScreen() {
        for (g in listOf(CapsuleGeometry.PHONE, PlayerBarMetrics.capsuleGeometry(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 908f))) {
            val startSize = g.coverSizeDp * density
            val startRadius = g.coverRadiusDp * density
            // the player's cover is laid out once at its own size (320 dp here), then scaled
            val laidOut = 320f * density
            val playerRadius = 28f * density
            val scale0 = Morph.coverScale(0f, startSize, laidOut)
            assertEquals(startSize, laidOut * scale0, 1e-3f, "the cover is the capsule's size at t = 0")
            // the radius is in the cover's unscaled space: scaled it is the capsule's cover's corner
            assertEquals(startRadius, Morph.coverRadius(0f, startRadius, startSize, laidOut, playerRadius) * scale0, 1e-3f)
            // at t = 1 the cover is the player's, whole, with the player's corner
            assertEquals(1f, Morph.coverScale(1f, startSize, laidOut), 0f)
            assertEquals(playerRadius, Morph.coverRadius(1f, startRadius, startSize, laidOut, playerRadius), 1e-3f)
        }
    }

    @Test
    fun theWordsStartWhereTheCapsulesWordsAreAndFitTheColumnItGivesThem() {
        for (g in listOf(CapsuleGeometry.PHONE, PlayerBarMetrics.capsuleGeometry(PlayerBarMetrics.planFor(PlayerBarMetrics.Tier.FULL), 908f))) {
            assertEquals(phoneCapsuleLeft + g.textStartDp * density, Morph.insetFrom(phoneCapsuleLeft, 0f, g.textStartDp * density), 0f)
        }
        val phone = CapsuleGeometry.PHONE
        // a 368 dp wide phone capsule: 368 - 68 - 112 = 188 dp for the words
        assertEquals(188f, Morph.textWidth(368f, phone.textStartDp, phone.textEndDp), 0f)
        // a capsule with no room gives the words none, not a negative width
        assertEquals(0f, Morph.textWidth(100f, phone.textStartDp, phone.textEndDp), 0f)
    }
}
