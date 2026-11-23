package com.cosmotune.video.ui

import com.cosmotune.video.ui.home.hasSideBands
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Home's "Up next for you" card: a quiet ground, its picture filling the slot, and its words never cut. */
class HeroCardTest {
    private val hero get() = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/home/HomeHero.kt"))

    private fun picture(width: Int, height: Int, at: (Int, Int, Random) -> Int): IntArray {
        val random = Random(7)
        return IntArray(width * height) { i -> at(i % width, i / width, random) }
    }

    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test fun aVerticalFrameInPlainBandsIsFound() {
        val w = 96; val h = 54
        // The site's 16:9 still of a Short: flat grey sides, the frame with detail in the middle third.
        val banded = picture(w, h) { x, _, r -> if (x < w * 0.34 || x > w * 0.66) grey(200) else grey(r.nextInt(256)) }
        assertTrue(hasSideBands(banded, w, h))
        // The site's bands are often a soft blur of the frame: some detail, far less than the middle's.
        val blurred = picture(w, h) { x, _, r -> if (x < w * 0.34 || x > w * 0.66) grey(120 + r.nextInt(40)) else grey(r.nextInt(256)) }
        assertTrue(hasSideBands(blurred, w, h))
        // A landscape still has detail right to its edges.
        val landscape = picture(w, h) { _, _, r -> grey(r.nextInt(256)) }
        assertFalse(hasSideBands(landscape, w, h))
        // A plain picture (a title card) is not a vertical frame either.
        assertFalse(hasSideBands(picture(w, h) { _, _, _ -> grey(30) }, w, h))
        assertFalse(hasSideBands(IntArray(4), 2, 2), "too small to tell")
    }

    @Test fun theCardIsQuietSizedToItsContentAndItsWordsAreWhole() {
        assertTrue("val fill = look.chip" in hero && "coverFill(" !in hero, "the muted Orbit card ground, not the picture's saturated colour")
        assertTrue("Modifier.wrapContentWidth(Alignment.Start).widthIn(max = HERO_MAX_WIDE)" in hero && "internal val HERO_MAX_WIDE = 760.dp" in hero, "on a wide page the card is as wide as its content")
        assertTrue("HeroArt(item, Modifier.width(if (wide) 320.dp else 150.dp).aspectRatio(16f / 9f)" in hero, "the picture fills its 16:9 slot")
        assertTrue(".clip(RoundedCornerShape(20.dp)).then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier) .padding(end = 14.dp)" in hero.replace(Regex("\\s+"), " "),
            "the round end of the channel's pressed shape no longer cuts the last letters of the views and age")
        val art = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/home/HeroArt.kt"))
        assertTrue(".blur(18.dp)" in art && "aspectRatio(9f / 16f)" in art, "a vertical frame stands over a blurred copy of itself, no empty bands")
    }
}
