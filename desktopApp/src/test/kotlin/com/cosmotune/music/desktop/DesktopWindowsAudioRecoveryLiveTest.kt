package com.cosmotune.music.desktop

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Silent, opt-in WASAPI integration checks. Uses this test JVM's own shared-mode client. */
class DesktopWindowsAudioRecoveryLiveTest {
    @Test(timeout = 15_000) fun pausedWritesAndDrainFinishAndUiMetadataDoesNotWaitForPlayback() {
        assumeTrue(DesktopPlatform.isWindows && System.getenv("COSMOTUNE_WINDOWS_AUDIO_LIVE") == "1")
        val audio = Executors.newSingleThreadExecutor { task -> Thread(task, "WASAPI recovery check").apply { isDaemon = true } }
        val ui = Executors.newSingleThreadExecutor { task -> Thread(task, "WASAPI metadata check").apply { isDaemon = true } }
        try {
            audio.submit<Int> { DesktopWindowsAudio.open("", DesktopPcmFormat(48_000, 2, 4, true)).getOrThrow() }.get(4, TimeUnit.SECONDS)
            val name = DesktopWindowsAudio.deviceName()
            assertFalse(name.isNullOrBlank())
            // Keep queued samples while stopped. Neither drain nor a further write may wait for
            // playback to restart on the very worker they are blocking.
            audio.submit { DesktopWindowsAudio.write(FloatArray(4_800), 4_800, 0f); DesktopWindowsAudio.pause() }.get(2, TimeUnit.SECONDS)
            audio.submit { DesktopWindowsAudio.drain() }.get(500, TimeUnit.MILLISECONDS)
            assertEquals(0, audio.submit<Int> { DesktopWindowsAudio.write(FloatArray(96_000), 96_000, 0f) }.get(500, TimeUnit.MILLISECONDS))
            audio.submit { DesktopWindowsAudio.resume() }.get(1, TimeUnit.SECONDS)
            val writing = audio.submit<Int> { DesktopWindowsAudio.write(FloatArray(96_000), 96_000, 0f) }
            assertEquals(name, ui.submit<String?> { DesktopWindowsAudio.deviceName() }.get(250, TimeUnit.MILLISECONDS))
            assertEquals(96_000 * 4, writing.get(3, TimeUnit.SECONDS))
        } finally {
            runCatching { audio.submit { DesktopWindowsAudio.close() }.get(2, TimeUnit.SECONDS) }
            audio.shutdownNow(); ui.shutdownNow()
        }
    }
}
