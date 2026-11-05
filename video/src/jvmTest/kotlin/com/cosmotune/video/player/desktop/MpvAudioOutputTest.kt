package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.SubtitleCue
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises real decoding and output fallback without a sound device or a developer's video file. */
class MpvAudioOutputTest {
    @Test
    fun aMissingPreferredOutputFallsBackAndDecodesStereoAudio() {
        val mpv = testMpv()
        assumeTrue("libmpv is available", mpv != null)
        val library = assertNotNull(mpv)
        val file = Files.createTempFile("cosmotune-audio-output-", ".m4a")
        val snapshot = MutableStateFlow(PlayerSnapshot.Idle)
        val options = MpvPlayer.DEFAULT_OPTIONS + mapOf("vo" to "null", "ao" to "cosmotune-missing-output,null")
        var player: MpvPlayer? = null
        try {
            javaClass.getResourceAsStream("/audio/stereo-tone.m4a")!!.use { input ->
                Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            val core = MpvPlayer(library, snapshot, MutableStateFlow<List<SubtitleCue>>(emptyList()), options, latestLoad = { 1 })
            player = core
            core.load(1, file.toString(), 0, live = false, remote = false, audioOnly = true, userAgent = null, headerFields = emptyList())
            await("audio file loads") { snapshot.value.status == PlayerStatus.READY }
            core.set("pause", "no")
            await("fallback output starts") { core.positionMs() > 250 && property(library, core, "current-ao") == "null" }
            assertEquals("48000", property(library, core, "audio-params/samplerate"))
            assertEquals("2", property(library, core, "audio-params/channel-count"))
            assertTrue(snapshot.value.error == null, snapshot.value.error.toString())
        } finally {
            player?.close()
            Files.deleteIfExists(file)
        }
    }

    private fun property(mpv: MpvLibrary, player: MpvPlayer, name: String): String? {
        val value = PointerByReference()
        if (mpv.mpv_get_property(player.handle, name, MpvLibrary.MPV_FORMAT_STRING, value) < 0) return null
        val pointer = value.value ?: return null
        return try { pointer.getString(0, "UTF-8") } finally { mpv.mpv_free(pointer) }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $description" }
            Thread.sleep(20)
        }
    }

}
