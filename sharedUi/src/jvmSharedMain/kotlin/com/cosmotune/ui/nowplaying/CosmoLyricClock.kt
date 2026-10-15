package com.cosmotune.ui.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import com.music.bitchord.ui.player.LyricClock
import com.music.bitchord.ui.player.LyricPlayhead
import com.music.bitchord.ui.rememberIsForeground
import kotlin.math.abs

/**
 * The song position as the lyrics read it, ticking every frame while the song plays.
 *
 * The driver loop is `rememberLyricClock` of the old lyrics panel (`PlayerLyrics.kt:608-660`), copied here because that one is
 * private to its file; the engine is the same [LyricClock], whose rules (a reading is dated by when it was taken, only an
 * announced jump moves the words backwards, readings steer the clock and do not set it) are written at the top of that class.
 * Not playing, each reading is the truth ([LyricClock.hold]); playing and on screen, a [withFrameNanos] loop asks the engine for
 * the position of each frame ([LyricClock.frame]).
 *
 * Returned as state so that it is read inside draw lambdas and derived states only: read in composition, a line would
 * recompose sixty times a second. The loop stops in the background, where a request for a frame is a request to render.
 */
@Composable
internal fun rememberCosmoLyricClock(
    trackKey: Any,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
): MutableLongState {
    val clock = remember(trackKey) {
        mutableLongStateOf(Snapshot.withoutReadObservation { playhead.positionMs })
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }

    // Not moving: each reading is the truth, including a seek made while paused. Followed off the snapshot rather than read
    // here, so a reading recomposes nothing.
    if (!isPlaying) {
        LaunchedEffect(engine, playhead) {
            snapshotFlow { playhead.positionMs }.collect { positionMs ->
                engine.hold(positionMs, nanosToMs(System.nanoTime()))
                clock.longValue = positionMs
            }
        }
    }

    // Gated on the app being on screen. Every start is a fresh run from the latest reading, so neither a pause nor a spell in
    // the background is mistaken for one very long frame. One loop for the whole run, reading the playhead itself: keyed on
    // the reading, every reading restarted it and spent a frame waiting for its first frame time, a hitch twice a second.
    val foreground = rememberIsForeground()
    LaunchedEffect(engine, playhead, isPlaying, foreground) {
        if (!isPlaying || !foreground) return@LaunchedEffect
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                // Readings are stamped on the System.nanoTime clock. The frame time is that clock too wherever it is a vsync
                // stamp, and is preferred for being evenly spaced; anywhere it is not, the two are seconds apart and the real
                // clock is used instead.
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                val sampledAt = playhead.sampledAtNanos
                clock.longValue = engine.frame(
                    nowMs = nanosToMs(now),
                    reportedMs = playhead.positionMs,
                    sampledAtMs = if (sampledAt > 0L) nanosToMs(sampledAt) else Double.NaN,
                    discontinuity = playhead.discontinuity,
                )
            }
        }
    }
    return clock
}

private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

/** A frame time this close to [System.nanoTime] is on the same clock. */
private const val SAME_CLOCK_NANOS = 250_000_000L
