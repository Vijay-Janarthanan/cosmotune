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
        assertTrue(".background(look.chip)" in hero && "coverFill(" !in hero, "the muted Orbit card ground, not the picture's saturated colour")
        assertTrue("Modifier.wrapContentWidth(Alignment.Start).widthIn(max = HERO_MAX_WIDE)" in hero && "internal val HERO_MAX_WIDE = 760.dp" in hero, "on a wide page the card is as wide as its content")
        assertTrue("HeroArt(item, Modifier.width(320.dp).aspectRatio(16f / 9f)" in hero && "HeroArt(item, Modifier.fillMaxWidth().aspectRatio(16f / 9f)" in hero, "the picture fills its 16:9 slot, beside the words or above them on a phone")
        assertTrue(".clip(RoundedCornerShape(20.dp)).then(if (onChannel != null) Modifier.clickable(onClick = onChannel) else Modifier) .padding(end = 14.dp)" in hero.replace(Regex("\\s+"), " "),
            "the round end of the channel's pressed shape no longer cuts the last letters of the views and age")
        val flat = hero.replace(Regex("\\s+"), " ")
        assertTrue("OrbitBiteCard(" !in hero && flat.substringAfter("val card = ").substringBefore("testTag").let { ".height(" !in it && "heightIn" !in it }, "no fixed height: the card hugs the taller of the picture and the words")
        val words = flat.substringAfter("private fun ColumnScope.HeroWords")
        listOf("heroTitle", "Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp))", "item.snippet?.takeIf { it.isNotBlank() }", "maxLines = 2", "OrbitPillButton(play")
            .fold(0) { from, part -> words.indexOf(part, from).also { assertTrue(it >= 0, "$part follows in order") } }
        assertTrue("OrbitTooltip(play) { OrbitPillButton(play, onPlay, fonts, look, primary = true" in words, "Play is the main button, with its tooltip")
        assertTrue("OrbitRoundButton(CosmoIcons.Clock, later" in words && "OrbitRoundButton(VideoIcons.PlaylistAdd, save" in words, "Watch later and Save to a playlist as round buttons (they carry the tooltip)")
        assertTrue("horizontalArrangement = Arrangement.spacedBy(8.dp)" in words, "8 dp between the actions")
        val home = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/home/VideoHome.kt"))
        assertTrue("onWatchLater = { actions.handle(item, VideoAction.WatchLater" in home && "onSave = { actions.handle(item, VideoAction.SaveToPlaylist" in home)
        val art = VideoSourceContract.code(VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/home/HeroArt.kt"))
        assertTrue(".blur(18.dp)" in art && "aspectRatio(9f / 16f)" in art, "a vertical frame stands over a blurred copy of itself, no empty bands")
    }
}
