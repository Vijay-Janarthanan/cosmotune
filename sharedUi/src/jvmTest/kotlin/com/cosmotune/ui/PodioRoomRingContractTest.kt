package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Podio's and the room's rings run on the live playhead (at the episode's speed). */
class PodioRoomRingContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String): String = File(root, path).readText().replace("\r\n", "\n")
    private val src = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune"

    @Test fun podioRingsReadTheLivePlayheadAtTheEpisodesSpeed() {
        val player = read("$src/podio/ui/PodioPlayer.kt")
        assertTrue(Regex("""rememberLivePlayheadOf\(playback\.positionMs, [^)]*playback\.rate""").containsMatchIn(player), "the speed is the playhead's rate")
        assertFalse(player.contains("(playback.positionMs.toFloat() / playback.durationMs)"), "no ring or line reads the stepped reading")
        assertTrue(read("$src/podio/ui/PodioChrome.kt").contains("head?.fraction(it)"), "the playing row's orbit too")
        assertTrue(read("$src/podio/ui/PodioApp.kt").contains("head = head"), "handed to every page")
    }

    @Test fun theRoomsRingAndLineReadTheLivePlayhead() {
        val room = read("$src/ui/room/RoomNowPlaying.kt")
        assertTrue(room.contains("rememberLivePlayheadOf(positionMs, isPlaying"), "run on from each arrival of the shared playhead")
        assertFalse(room.contains("progress = { roomPositionFraction(positionMs, durationMs) }"), "the line is not drawn from the stepped value")
        assertFalse(room.contains("else roomPositionFraction(positionMs, durationMs) },"), "nor the ring")
    }
}
