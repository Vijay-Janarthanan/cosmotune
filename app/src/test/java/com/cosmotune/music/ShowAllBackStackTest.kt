package com.cosmotune.music

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-34's shelf half, recorded rather than ported: upstream's eight "show all" back-stack commits (`7b5bff54` to `a2f13dd1`) were
 * reverted as a whole by `9fdde092`, so its final behaviour is the one this fork already has. This pins that behaviour on both
 * looks (they share these lines): opening another page from a "Show all" grid closes the grid (the bug `22fcaf8b` chased cannot
 * happen), Back closes an open grid before it leaves the page, and the grid is not kept under the page it opened.
 */
class ShowAllBackStackTest {
    private val activity = File(
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile },
        "app/src/main/java/com/cosmotune/music/MainActivity.kt",
    ).readText().replace("\r\n", "\n")

    @Test fun anotherPageClosesTheGridAndBackClosesItFirst() {
        assertTrue("a new page starts without a grid", "LaunchedEffect(detail?.browseId) { detailActiveShelf = null }" in activity)
        assertTrue("Back closes the grid before the page", "BackHandler(enabled = detailActiveShelf != null) { detailActiveShelf = null }" in activity)
        assertTrue("both looks get the same state", "activeShelf = detailActiveShelf," in activity && "onActiveShelfChange = { detailActiveShelf = it }," in activity)
    }
}
