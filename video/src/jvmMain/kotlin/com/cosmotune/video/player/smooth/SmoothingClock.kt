package com.cosmotune.video.player.smooth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.StateFlow

/**
 * The clock that makes in-between frames possible: while [pipeline] is smoothing and a source frame is still moving towards the
 * next, it asks for a draw on the screen's own frame clock (every frame of the display, or at the chosen target rate) by moving
 * [pace]. Between source frames of a paused or still video it rests and asks for nothing. With smoothing off (or no pipeline)
 * it does not run at all, so the plain path costs nothing.
 */
@Composable
internal fun SmoothingClock(pipeline: SmoothingPipeline?, frames: StateFlow<Long>, pace: MutableLongState) {
    if (pipeline == null) return
    val on by pipeline.enabled.collectAsState()
    LaunchedEffect(pipeline, on) {
        if (!on) return@LaunchedEffect
        while (true) {
            pipeline.awaitAnimation(frames)
            withFrameNanos { now ->
                if (pipeline.vsync(System.nanoTime())) pace.longValue = now
            }
        }
    }
}
