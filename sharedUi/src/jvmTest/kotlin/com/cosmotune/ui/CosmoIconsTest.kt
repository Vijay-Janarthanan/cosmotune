package com.cosmotune.ui

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 54 icons (the 30 of the shell and the player, the 22 of part two), drawn by Compose's own vector parser: no window needed. */
class CosmoIconsTest {

    private val expected = listOf(
        "canvas", "canvas-off", "home", "home-fill", "search", "search-fill", "library", "library-fill", "grid", "play", "pause", "next", "prev",
        "shuffle", "repeat", "repeat-one", "heart", "heart-fill", "more", "more-v", "queue", "lyrics", "devices", "together",
        "chevron-down", "chevron-left", "chevron-right", "check", "video", "video-off", "expand", "volume",
        // part two
        "download", "downloaded", "chevron-up", "plus", "close", "settings", "equaliser", "volume-low", "timer", "share", "replay",
        "cellular", "clock", "pin", "sort", "waveform", "translate", "arrow-left", "radio", "bell", "user", "list",
    )

    private val partTwo = listOf(
        "download" to CosmoIcons.Download, "downloaded" to CosmoIcons.Downloaded, "chevron-up" to CosmoIcons.ChevronUp,
        "plus" to CosmoIcons.Plus, "close" to CosmoIcons.Close, "settings" to CosmoIcons.Settings, "equaliser" to CosmoIcons.Equaliser,
        "volume-low" to CosmoIcons.VolumeLow, "timer" to CosmoIcons.Timer, "share" to CosmoIcons.Share, "replay" to CosmoIcons.Replay,
        "cellular" to CosmoIcons.Cellular, "clock" to CosmoIcons.Clock, "pin" to CosmoIcons.Pin, "sort" to CosmoIcons.Sort,
        "waveform" to CosmoIcons.Waveform, "translate" to CosmoIcons.Translate, "arrow-left" to CosmoIcons.ArrowLeft,
        "radio" to CosmoIcons.Radio, "bell" to CosmoIcons.Bell, "user" to CosmoIcons.User, "list" to CosmoIcons.List,
    )

    private fun paths(vector: ImageVector): List<VectorPath> {
        fun walk(node: VectorNode): List<VectorPath> = when (node) {
            is VectorPath -> listOf(node)
            is VectorGroup -> node.flatMap { walk(it) }
        }
        return vector.root.flatMap { walk(it) }
    }

    @Test fun hasExactlyTheFiftyFourNames() {
        assertEquals(54, expected.size)
        assertEquals(54, expected.toSet().size, "no name twice")
        assertEquals(expected.toSet(), CosmoIcons.all.keys)
        assertEquals(54, CosmoIcons.all.size)
    }

    @Test fun theTwentyTwoNewOnesAreNamedAfterTheirAssetKeys() {
        assertEquals(22, partTwo.size)
        partTwo.forEach { (key, v) -> assertEquals(key, v.name) }
        assertEquals(expected.takeLast(22), partTwo.map { it.first })
    }

    @Test fun namesAreUniqueAndMatchTheKeys() {
        val vectors = CosmoIcons.all
        assertEquals(54, vectors.values.map { it.name }.toSet().size, "every vector has its own name")
        vectors.forEach { (key, v) -> assertEquals(key, v.name) }
    }

    @Test fun everyIconIsA24By24Box() {
        CosmoIcons.all.forEach { (name, v) ->
            assertEquals(24.dp, v.defaultWidth, name)
            assertEquals(24.dp, v.defaultHeight, name)
            assertEquals(24f, v.viewportWidth, name)
            assertEquals(24f, v.viewportHeight, name)
        }
    }

    @Test fun everyIconHasDrawnPaths() {
        CosmoIcons.all.forEach { (name, v) ->
            val p = paths(v)
            assertTrue(p.isNotEmpty(), "$name has a path")
            p.forEach { assertTrue(it.pathData.isNotEmpty(), "$name has path data") }
        }
    }

    @Test fun strokeWidthsAreTwoOrTwoPointSix() {
        CosmoIcons.all.forEach { (name, v) ->
            paths(v).forEach { p ->
                if (p.stroke != null) assertTrue(p.strokeLineWidth == 2f || p.strokeLineWidth == 2.6f, "$name stroke ${p.strokeLineWidth}")
            }
        }
    }

    @Test fun downloadedIsOneEvenOddFillWithTheArrowCutOutAndNoStroke() {
        val p = paths(CosmoIcons.Downloaded)
        assertEquals(1, p.size, "the disc and the arrow's outline are one path")
        assertEquals(PathFillType.EvenOdd, p.single().pathFillType)
        assertNull(p.single().stroke, "a vector takes one tint: no stroke to draw the arrow in")
        assertTrue(p.single().fill != null, "filled")
    }

    @Test fun theLineIconsAreStrokedWithRoundEndsAndTheFilledOnesOnlyWhereTheAssetFills() {
        val strokeOnly = partTwo.map { it.first }.filter { it != "downloaded" }
        for (name in strokeOnly) {
            val v = CosmoIcons.all.getValue(name)
            paths(v).forEach { assertNull(it.fill, "$name is a line icon: no fill") }
        }
    }
}
