package com.cosmotune.music.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The playhead as the eye should see it: [PlaybackPosition] carries a reading that is half a second old at best (the phone
 * polls Media3 twice a second, the desktop engine reports on its own beat), so a ring or moon drawn straight from it moves in
 * visible steps. A [LivePlayhead] runs on from the last reading on the frame clock, so the ring glides.
 *
 * Read [positionMs] (or [fraction]) only while drawing: it reads the frame stamp, so a draw lambda that calls it is
 * invalidated every frame and nothing above it is recomposed.
 */
@Stable
class LivePlayhead(private val position: PlaybackPosition) {
    private val frame = mutableLongStateOf(0L)

    /** True while the playhead should be running on: playing, on screen and not waiting on audio. Driven by [rememberLivePlayhead]. */
    var running by mutableStateOf(false)
        internal set

    /** How fast the song runs: 1 normally, a podcast's speed. Set by [rememberLivePlayhead]. */
    @Volatile
    var rate: Float = 1f
        internal set

    private var shownMs = -1L
    private var shownSeeks = 0
    private var shownFrom = 0L

    /** Called by the frame loop with the clock the readings are stamped on. */
    internal fun tick(nowNanos: Long) {
        frame.longValue = nowNanos
    }

    /** Where the playhead is now. Frozen at the last reading unless [running]; a seek lands at once; never steps back on its own. */
    fun positionMs(): Long {
        val now = frame.longValue
        val reported = position.positionMs
        val sampled = position.sampledAtNanos
        val seeks = position.seeks
        val target = PlayheadMath.at(reported, sampled, now, running && position.advancing, rate = rate)
        val snap = !running || seeks != shownSeeks || shownMs < 0L || reported != shownFrom && abs(target - shownMs) > PlayheadMath.SNAP_MS
        val next = if (snap) target else maxOf(shownMs, target)
        shownMs = next
        shownSeeks = seeks
        shownFrom = reported
        return next
    }

    /** The playhead as 0..1 of [durationMs]; 0 when the length is not known. */
    fun fraction(durationMs: Long): Float =
        if (durationMs > 0L) (positionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** The arithmetic of [LivePlayhead], apart from state so it can be tested on its own. */
object PlayheadMath {
    /** The longest a reading is carried forward: if reports stop (a stall) the ring does not run ahead without end. */
    const val MAX_CARRY_MS = 2_000L

    /** A reading this far from where the ring is, without a seek being announced, is a jump and not drift. */
    const val SNAP_MS = 1_000L

    /** Reduced motion redraws this often; the position is still true, only less often updated. */
    const val REDUCED_STEP_MS = 250L

    /**
     * [reportedMs] carried forward to [nowNanos] when [advancing]. A reading with no stamp, or one stamped after [nowNanos],
     * is returned as it is.
     */
    fun at(reportedMs: Long, sampledAtNanos: Long, nowNanos: Long, advancing: Boolean, durationMs: Long = 0L, rate: Float = 1f): Long {
        if (!advancing || sampledAtNanos <= 0L || nowNanos <= sampledAtNanos) return reportedMs
        val carried = (((nowNanos - sampledAtNanos) / 1_000_000L) * rate).toLong().coerceIn(0L, MAX_CARRY_MS)
        val moved = reportedMs + carried
        return if (durationMs > 0L) moved.coerceAtMost(maxOf(durationMs, reportedMs)) else moved
    }
}

/**
 * A [LivePlayhead] for [position], advanced once a frame while [playing] (every [PlayheadMath.REDUCED_STEP_MS] under reduced
 * motion). The loop lives in the composition of whoever draws the ring: off screen, paused, or with the app in the background
 * (no frames are delivered) it costs nothing.
 */
@Composable
fun rememberLivePlayhead(position: PlaybackPosition, playing: Boolean, rate: Float = 1f): LivePlayhead {
    val live = remember(position) { LivePlayhead(position) }
    val reduced = LocalReducedMotion.current
    val run = playing && position.advancing
    LaunchedEffect(live, run, reduced, rate) {
        live.running = run
        live.rate = rate
        if (!run) return@LaunchedEffect
        if (reduced) {
            while (true) {
                live.tick(System.nanoTime())
                delay(PlayheadMath.REDUCED_STEP_MS)
            }
        } else {
            while (true) {
                withFrameNanos { frameNanos ->
                    // Readings are stamped on System.nanoTime. A vsync stamp is that clock on Android and the desktop and is
                    // evenly spaced; anywhere it is not, the two are seconds apart and the real clock is used.
                    val system = System.nanoTime()
                    live.tick(if (abs(system - frameNanos) < SAME_CLOCK_NANOS) frameNanos else system)
                }
            }
        }
    }
    return live
}

/**
 * A [LivePlayhead] for a position that arrives as a plain value (Podio's player state, the room's shared playhead) with no
 * timestamp: each new [positionMs] is stamped when it is first seen, and the playhead runs on from it at [rate] until the next.
 * [resetKey] (the episode or track) starts a fresh playhead, so a new one does not glide from the old one's place.
 */
@Composable
fun rememberLivePlayheadOf(positionMs: Long, playing: Boolean, rate: Float = 1f, resetKey: Any? = null): LivePlayhead {
    val position = remember(resetKey) { PlaybackPosition().also { it.report(positionMs) } }
    val reported = remember(position) { longArrayOf(positionMs) }
    if (reported[0] != positionMs) {
        // Stamped as it is seen. The playhead reads these only while drawing, so this write is not seen by composition.
        reported[0] = positionMs
        position.report(positionMs)
    }
    return rememberLivePlayhead(position, playing, rate)
}

private const val SAME_CLOCK_NANOS = 250_000_000L
