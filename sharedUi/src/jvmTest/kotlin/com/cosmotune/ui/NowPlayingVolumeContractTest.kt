package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The volume never disappears from the song card: whatever the width, `volume = true` places an OrbitVolume or the speaker button. */
class NowPlayingVolumeContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun card(): String = File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingCard.kt")
        .readText().replace("\r\n", "\n")

    @Test fun theOldRuleThatHidTheVolumeIsGone() {
        assertTrue(!card().contains("if (volume && roomForVolume)"), "the volume is not dropped for want of room")
    }

    @Test fun everyPlacementOfTheCardHasTheVolume() {
        val src = card()
        val wide = src.substringAfter("if (wide) {").substringBefore("} else {")
        assertTrue(wide.contains("if (volume) OrbitVolume("), "the wide card places it under the buttons")
        val oneColumn = src.substringAfter("val topRight = volume && !roomBesideButtons")
        assertTrue(oneColumn.contains("if (topRight)"), "else it goes top right")
        assertTrue(
            oneColumn.contains("OrbitVolume(ink, style = VolumeStyle.Compact") && oneColumn.contains("OrbitVolumeButton(ink, fill, 32.dp"),
            "the short line, then the speaker button",
        )
        assertTrue(oneColumn.contains("if (volume && roomBesideButtons)"), "beside the buttons where it fits: exactly one of the two places")
    }

    @Test fun theSlimCardHasTheSpeakerAndEveryControlTurnsWithTheWheel() {
        val src = card()
        assertTrue(src.substringAfter("fun NowPlayingSlimCard").contains("OrbitVolumeButton(ink, fill, 40.dp"), "Cinema's card has the speaker")
        assertTrue(Regex("volumeWheel\\(").findAll(src).count() >= 4, "the line, the button and its popover turn with the wheel")
    }
}
