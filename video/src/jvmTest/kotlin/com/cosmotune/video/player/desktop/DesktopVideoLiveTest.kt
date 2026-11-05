package com.cosmotune.video.player.desktop

import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.select.StreamSelector
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.newpipe.NewPipeVideoSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in network and native playback check: VIDEO_LIVE=true. Audio is sent to a null output. */
class DesktopVideoLiveTest {
    @Test fun aMusicVideoLoadsAtHighQualityAndPlaysFromTheRequestedPosition() = runBlocking {
        assumeTrue(System.getenv("VIDEO_LIVE") == "true")
        val engine = DesktopVideoEngine({ testMpv() }, null, mapOf("ao" to "null"))
        try {
            val details = NewPipeVideoSource().details("dQw4w9WgXcQ")
            val selected = StreamSelector().choose(details.streams, engine.capabilities,
                VideoSettings(wifiQuality = 1080), false, null, null, null, (details.item.durationSec ?: 0) * 1_000)
            val input = assertNotNull(selected.input)
            println("LIVE: selected ${selected.video?.width}x${selected.video?.height}")
            assertTrue((selected.video?.height ?: 0) >= 720)
            engine.load(PlaybackRequest(details.item.id, input, emptyList(), details.streams.requestHeaders,
                30_000, false, details.item.title, null, null))
            withTimeout(60_000) {
                while (engine.snapshot.value.status != PlayerStatus.READY) {
                    check(engine.snapshot.value.status != PlayerStatus.ERROR) { engine.snapshot.value.error.toString() }
                    delay(50)
                }
            }
            engine.play()
            withTimeout(15_000) {
                while (!engine.snapshot.value.playing || engine.positionMs() < 31_000) delay(50)
            }
            println("LIVE: playing ${engine.snapshot.value.videoWidth}x${engine.snapshot.value.videoHeight} at ${engine.positionMs()} ms")
            assertTrue(engine.snapshot.value.videoHeight >= 720)
        } finally {
            engine.release()
            assertTrue(engine.awaitRelease(15_000), "native player teardown finishes")
        }
    }
}
