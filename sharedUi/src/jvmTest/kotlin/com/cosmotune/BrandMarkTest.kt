package com.cosmotune

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.jetbrains.compose.resources.decodeToImageVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-app mark, `ic_logo.xml`, is drawn by Compose's own vector parser on both platforms (the shared copy)
 * and by Android's resource compiler (the app's copy, used by the Replay posters). Every call site sizes and tints it,
 * so it has to keep the box of the drawing it replaced, 28 x 18 dp over a 730 x 484 viewport, be plain filled
 * paths, and be the same drawing in both places.
 */
class BrandMarkTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val shared = File(root, "sharedUi/src/commonMain/composeResources/drawable/ic_logo.xml")
    private val app = File(root, "app/src/main/res/drawable/ic_logo.xml")

    @Test
    fun composeReadsTheSharedMarkAsOneFilledWhitePathInTheOldBox() {
        val vector = shared.readBytes().decodeToImageVector(Density(1f))
        assertEquals(28.dp, vector.defaultWidth)
        assertEquals(18.dp, vector.defaultHeight)
        assertEquals(730f, vector.viewportWidth)
        assertEquals(484f, vector.viewportHeight)
        val nodes = vector.root.toList()
        assertEquals(1, nodes.size, "one path and nothing else (no group, no clip)")
        val path = nodes.single() as VectorPath
        assertTrue(path.pathData.isNotEmpty(), "the path has commands")
        assertEquals(SolidColor(androidx.compose.ui.graphics.Color.White), path.fill, "filled white: every call site tints it")
        assertEquals(null, path.stroke, "filled, not stroked")
    }

    @Test
    fun theAppAndSharedCopiesAreTheSameDrawing() {
        fun text(f: File) = f.readText().replace("\r\n", "\n")
        assertEquals(text(shared), text(app))
    }
}
