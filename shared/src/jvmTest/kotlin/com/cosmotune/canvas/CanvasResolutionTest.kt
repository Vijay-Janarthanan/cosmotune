package com.cosmotune.canvas

import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CanvasResolutionTest {
    private fun track(url: String, stage: SyncStage = SyncStage.MEASURED) =
        OpenCanvasTrack(videoId = "video", videoStreamUrl = url, title = "Song", artist = "Artist", syncStage = stage)

    @Test fun waitsForTheRequestedRenditionBeyondTheOldGracePeriod() = runTest {
        val selected = flow {
            emit(track("preview-360p"))
            delay(4_000)
            emit(track("requested-1080p"))
        }.resolvedCanvas(20_000)
        assertEquals("requested-1080p", selected?.videoStreamUrl)
    }

    @Test fun aStalledUpgradeKeepsTheSyncedFallback() = runTest {
        val selected = flow {
            emit(track("preview-360p"))
            awaitCancellation()
        }.resolvedCanvas(20_000)
        assertEquals("preview-360p", selected?.videoStreamUrl)
    }

    @Test fun anUnmeasuredVideoIsNeverShown() = runTest {
        assertNull(flow { emit(track("preview", SyncStage.PENDING)); awaitCancellation() }.resolvedCanvas(20_000))
    }

    @Test fun cachedAnswersAreSpecificToQualityAndEnabledState() {
        assertNotEquals(canvasSongKey("song", true, "360p"), canvasSongKey("song", true, "1080p"))
        assertNotEquals(canvasSongKey("song", true, "720p"), canvasSongKey("song", false, "720p"))
        assertEquals(canvasSongKey("song", true, "720p"), canvasSongKey("song", true, "720p"))
    }
}
