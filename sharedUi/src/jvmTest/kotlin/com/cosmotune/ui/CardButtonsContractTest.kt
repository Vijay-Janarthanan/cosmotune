package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The song card's buttons move to its More menu rather than overflow the row; previous, next and like never move. */
class CardButtonsContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String): String = File(root, path).readText().replace("\r\n", "\n")
    private val src = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune"

    @Test fun theCardsButtonsNeverOverflowTheirRow() {
        val card = read("$src/ui/nowplaying/NowPlayingCard.kt")
        assertTrue(card.contains("available = footer"), "the one-column card gives the row its width")
        assertTrue(card.contains("planButtons(") && card.contains("OrbitMenu("), "short of room, buttons go to the More menu")
        assertTrue(card.contains("keepOpen = b.menu == null") && card.contains("selected = b.on"), "the moved items show their state")
        val screen = read("$src/ui/nowplaying/CosmoNowPlaying.kt")
        assertTrue(Regex("onToggleShuffle, on = shuffleEnabled, movable = true").containsMatchIn(screen), "shuffle may move")
        assertTrue(Regex("onCycleRepeat, on = repeatMode != RepeatModes.OFF, movable = true").containsMatchIn(screen), "repeat may move")
        assertTrue(screen.contains("menu = videoMenu, movable = true"), "the video view may move")
        val fixed = screen.lines().filter { it.contains("CosmoIcons.Previous") || it.contains("CosmoIcons.Next") }
        assertTrue(fixed.isNotEmpty() && fixed.none { it.contains("movable") }, "previous and next never move")
    }
}
