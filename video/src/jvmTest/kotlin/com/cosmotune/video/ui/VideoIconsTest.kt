package com.cosmotune.video.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The 22 icons of Video mode, built by Compose's own vector parser: no window needed. */
class VideoIconsTest {

    /** The plan's table, in its order: the name of the vector, how many paths it draws and how many of those are filled. */
    private val table = listOf(
        Triple("fullscreen", 4, 0), Triple("fullscreen-exit", 4, 0), Triple("pip", 2, 0), Triple("captions", 3, 0),
        Triple("captions-off", 4, 0), Triple("speed", 2, 0), Triple("thumb-up", 2, 0), Triple("thumb-down", 2, 0),
        Triple("comment", 1, 0), Triple("subscriptions", 4, 1), Triple("live", 5, 1), Triple("skip-segment", 3, 2),
        Triple("forward", 2, 0), Triple("history", 3, 0), Triple("playlist-add", 5, 0), Triple("bookmark", 1, 0),
        Triple("volume-off", 3, 0), Triple("brightness", 9, 0), Triple("shorts", 2, 1), Triple("hd", 5, 0),
        Triple("info", 3, 0), Triple("upload", 3, 0),
    )

    private fun paths(vector: ImageVector): List<VectorPath> {
        fun walk(node: VectorNode): List<VectorPath> = when (node) {
            is VectorPath -> listOf(node)
            is VectorGroup -> node.flatMap { walk(it) }
        }
        return vector.root.flatMap { walk(it) }
    }

    @Test fun hasExactlyTheTwentyTwoIconsOfThePlanInItsOrder() {
        assertEquals(22, table.size)
        assertEquals(22, VideoIcons.all.size)
        assertEquals(table.map { it.first }, VideoIcons.all.map { it.first })
    }

    @Test fun namesAreUniqueTheVectorsCarryThemAndNoneIsMusicsName() {
        val names = VideoIcons.all.map { it.first }
        assertEquals(22, names.toSet().size, "no name twice")
        VideoIcons.all.forEach { (name, vector) -> assertEquals(name, vector.name) }
        assertTrue(names.none { it in CosmoIcons.all.keys }, "a name of the Music set would make two icons look alike in a log")
    }

    @Test fun everyIconBuildsAndIsA24By24Box() {
        VideoIcons.all.forEach { (name, v) ->
            assertEquals(24.dp, v.defaultWidth, name)
            assertEquals(24.dp, v.defaultHeight, name)
            assertEquals(24f, v.viewportWidth, name)
            assertEquals(24f, v.viewportHeight, name)
        }
    }

    @Test fun everyIconDrawsTheNumberOfPathsTheTableGives() {
        val byName = VideoIcons.all.toMap()
        for ((name, count, _) in table) {
            val p = paths(byName.getValue(name))
            assertEquals(count, p.size, "$name draws $count paths")
            p.forEach { assertTrue(it.pathData.isNotEmpty(), "$name has path data") }
        }
    }

    @Test fun onlyTheShapesTheTableFillsAreFilled() {
        val byName = VideoIcons.all.toMap()
        for ((name, _, filled) in table) {
            assertEquals(filled, paths(byName.getValue(name)).count { it.fill != null }, "$name fills $filled")
        }
    }

    @Test fun everyStrokeIsTwoWideWithRoundCapsAndJoins() {
        VideoIcons.all.forEach { (name, v) ->
            paths(v).forEach { p ->
                // a line has a stroke and no fill; a filled shape is also stroked, so its corners are round
                assertNotNull(p.stroke, "$name: every path is stroked")
                assertEquals(2f, p.strokeLineWidth, "$name stroke width")
                assertEquals(StrokeCap.Round, p.strokeLineCap, "$name caps")
                assertEquals(StrokeJoin.Round, p.strokeLineJoin, "$name joins")
            }
        }
    }

    @Test fun thumbDownIsThumbUpMirroredTopToBottom() {
        val up = VideoIcons.ThumbUp
        val down = VideoIcons.ThumbDown
        val groups = down.root.filterIsInstance<VectorGroup>()
        assertEquals(1, groups.size, "one group holds the mirrored paths")
        assertEquals(-1f, groups.single().scaleY, "mirrored top to bottom")
        assertEquals(1f, groups.single().scaleX)
        assertEquals(12f, groups.single().pivotY, "about the middle of the grid, so y becomes 24 - y")
        assertEquals(paths(up).map { it.pathData }, paths(down).map { it.pathData }, "the same shape, not a second drawing of it")
    }

    @Test fun captionsOffIsCaptionsWithOneStrokeAcross() {
        val on = paths(VideoIcons.Captions)
        val off = paths(VideoIcons.CaptionsOff)
        assertEquals(on.map { it.pathData }, off.take(on.size).map { it.pathData }, "the three of Captions")
        assertEquals(on.size + 1, off.size, "and the slash")
    }
}
