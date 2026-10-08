package com.music.bitchord.ui.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.rememberIsForeground
import com.opencanvas.core.sync.SyncMap
import kotlin.math.abs

/**
 * What a canvas that follows the song needs from the player.
 *
 * A clip from Spotify or Apple is a few seconds long and loops under the song, so it never asks
 * where the song is. An OpenCanvas clip is the artist's own music video, and it is only right when
 * its picture is at the same moment as the audio: the decoder is told the song's position and keeps
 * the video there, jumps when the song does (seek, skip, restart), pauses with it, and never loops.
 *
 * The position is the same clock the synced lyrics run on, so the picture and the words agree.
 */
class CanvasSyncSpec(
    /**
     * How the song's timeline maps onto the video's: segments with their own offsets (a video with a
     * 22 s intro starts the song at +22 000), because a music video is usually an edit of the song.
     */
    val map: SyncMap,
    /** Length of the video in ms, or 0 when unknown. */
    val durationMs: Long,
    /** Where the song is now, in ms. Read from frame loops and effects, never in composition. */
    val songMs: () -> Long,
    /** Changes whenever the song position jumped on purpose: a seek, a skip, a restart. */
    val epoch: () -> Long,
)

/**
 * The player's playhead, attached by the player screen for as long as it is composed so a canvas
 * mounted anywhere beneath it can follow the song without every call site passing it down.
 *
 * More than one screen can be composed at once while the mini player grows into the full one, and
 * the old screen is disposed after the new one has attached; a single slot would be cleared by that
 * disposal and leave the new screen's canvas with nothing to follow, so every screen keeps its own
 * attachment and the latest one that is still attached is the playhead.
 */
internal object CanvasSyncSource {
    private val attached = ArrayList<PlaybackPosition>()

    val position: PlaybackPosition?
        @Synchronized get() = attached.lastOrNull()

    @Synchronized
    fun attach(position: PlaybackPosition) {
        attached += position
    }

    @Synchronized
    fun detach(position: PlaybackPosition) {
        attached.remove(position)
    }
}

/** A [CanvasSyncSpec] for [canvas] when it is a clip that follows the song, otherwise null. */
@Composable
internal fun rememberCanvasSync(canvas: CanvasArtwork, isPlaying: Boolean): CanvasSyncSpec? {
    val map = canvas.syncMap ?: return null
    val position = CanvasSyncSource.position ?: return null
    val clock = rememberSongClock(canvas.url, position, isPlaying)
    return remember(canvas, position, clock) {
        CanvasSyncSpec(
            map = map,
            durationMs = canvas.videoDurationMs,
            songMs = { clock.longValue },
            epoch = { position.seeks.toLong() },
        )
    }
}

/**
 * The song position, ticking every frame: the lyric clock without the lyrics offset. The player
 * reports where it is about twice a second, which is far too coarse to keep a picture on a beat, so
 * a clock runs on the frames in between and the readings steer it - see [LyricClock].
 */
@Composable
private fun rememberSongClock(trackKey: Any, position: PlaybackPosition, isPlaying: Boolean): MutableLongState {
    val clock = remember(trackKey) {
        mutableLongStateOf(Snapshot.withoutReadObservation { position.positionMs })
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }

    // Not moving: each reading is the truth, including a seek made while paused.
    if (!isPlaying) {
        LaunchedEffect(engine, position) {
            snapshotFlow { position.positionMs }.collect { positionMs ->
                engine.hold(positionMs, System.nanoTime() / 1_000_000.0)
                clock.longValue = positionMs
            }
        }
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(engine, position, isPlaying, foreground) {
        if (!isPlaying || !foreground) return@LaunchedEffect
        engine.restart()
        // The frame loop below stops whenever the window is not drawing, and the clock with it, which
        // froze the picture while the song played on. This watchdog is not tied to frames: if the clock
        // has not moved for a moment it carries on by wall time until the engine moves it again.
        launch {
            var seen = clock.longValue
            var changedAt = System.nanoTime()
            var written = Long.MIN_VALUE
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                val now = System.nanoTime()
                val value = clock.longValue
                if (value != seen && value != written) {
                    seen = value
                    changedAt = now
                } else if ((now - changedAt) / 1_000_000L > WATCHDOG_STALL_MS) {
                    written = seen + (now - changedAt) / 1_000_000L
                    clock.longValue = written
                }
            }
        }
        while (true) {
            withFrameNanos { frameNanos ->
                val system = System.nanoTime()
                val now = if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system
                val sampledAt = position.sampledAtNanos
                clock.longValue = engine.frame(
                    nowMs = now / 1_000_000.0,
                    reportedMs = position.positionMs,
                    sampledAtMs = if (sampledAt > 0L) sampledAt / 1_000_000.0 else Double.NaN,
                    discontinuity = position.seeks.toLong(),
                )
            }
        }
    }
    return clock
}

private const val WATCHDOG_INTERVAL_MS = 50L
private const val WATCHDOG_STALL_MS = 400L

/** A frame time this close to [System.nanoTime] is on the same clock. */
private const val SAME_CLOCK_NANOS = 250_000_000L
