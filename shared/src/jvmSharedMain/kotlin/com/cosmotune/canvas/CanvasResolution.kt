package com.cosmotune.canvas

import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/** Keep listening for the requested rendition, retaining a synced fallback if resolution times out. */
internal suspend fun Flow<OpenCanvasTrack>.resolvedCanvas(timeoutMs: Long): OpenCanvasTrack? {
    var measured: OpenCanvasTrack? = null
    withTimeoutOrNull(timeoutMs) {
        collect { if (it.syncStage == SyncStage.MEASURED) measured = it }
    }
    return measured
}

/** Settings are part of the question: a cached low-resolution URL cannot answer a higher-resolution request. */
fun canvasSongKey(videoId: String, enabled: Boolean, resolution: String): String =
    "song|$videoId|opencanvas=$enabled|resolution=$resolution"
