package com.cosmotune.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 30 player and shell icons, drawn by Compose's own vector parser: no window needed. */
class CosmoIconsTest {

    private val expected = listOf(
        "home", "home-fill", "search", "search-fill", "library", "library-fill", "grid", "play", "pause", "next", "prev",
        "shuffle", "repeat", "repeat-one", "heart", "heart-fill", "more", "more-v", "queue", "lyrics", "devices", "together",
        "chevron-down", "chevron-left", "chevron-right", "check", "video", "video-off", "expand", "volume",
    )

    private fun paths(vector: ImageVector): List<VectorPath> {
        fun walk(node: VectorNode): List<VectorPath> = when (node) {
            is VectorPath -> listOf(node)
            is VectorGroup -> node.flatMap { walk(it) }
        }
        return vector.root.flatMap { walk(it) }
    }

    @Test fun hasExactlyTheThirtyNames() {
        assertEquals(30, expected.size)
        assertEquals(expected.toSet(), CosmoIcons.all.keys)
        assertEquals(30, CosmoIcons.all.size)
    }

    @Test fun namesAreUniqueAndMatchTheKeys() {
        val vectors = CosmoIcons.all
        assertEquals(30, vectors.values.map { it.name }.toSet().size, "every vector has its own name")
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
}
