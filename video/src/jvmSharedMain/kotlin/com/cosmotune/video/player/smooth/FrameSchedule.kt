package com.cosmotune.video.player.smooth

import kotlin.math.abs
import kotlin.math.max

/**
 * The timing arithmetic of the phone's smoothing (pure: no clock, no thread, no GL). The desktop reads the time frames arrive;
 * the phone can do better, since Media3 hands every decoded frame to the surface with the time it is meant to be shown, and
 * that time is much steadier than the time the frame turns up.
 */
object SmoothSchedule {

    /**
     * When a frame is meant to be shown. [stampNanos] is the surface texture's timestamp for it: the release time Media3 gave the
     * codec (on the same monotonic clock as [arrivalNanos]) when that is plausible, else (a presentation time on the media's own
     * clock, or nothing) the moment the frame [arrivalNanos]. Never earlier than [lastNanos] (frames stay in order).
     */
    fun frameTime(stampNanos: Long, arrivalNanos: Long, lastNanos: Long): Long {
        val plausible = stampNanos > 0 && abs(stampNanos - arrivalNanos) <= MAX_STAMP_SKEW_NANOS
        val time = if (plausible) stampNanos else arrivalNanos
        return if (lastNanos == Long.MIN_VALUE) time else max(time, lastNanos + 1)
    }

    /**
     * The moment of the screen's own refresh that the picture drawn on the frame callback at [frameTimeNanos] is first shown:
     * one refresh later. The pair is evaluated for that moment, which is the one Media3 aims its release times at.
     */
    fun displayTime(frameTimeNanos: Long, periodNanos: Long): Long = frameTimeNanos + periodNanos

    /** Whether the screen keeps drawing: frames still waiting to take their turn, or the newer frame not yet reached (plus a tail). */
    fun animating(nowNanos: Long, newerAtNanos: Long, intervalNanos: Long?, pending: Boolean): Boolean {
        if (pending) return true
        if (intervalNanos == null) return false
        return nowNanos - newerAtNanos < intervalNanos + TAIL_NANOS
    }

    /**
     * How long the GPU took for a piece of work that was seen finished [observedMs] after it was submitted, when finished work is
     * only looked for once per refresh of [tickMs]: the work ended somewhere in the last tick, so half a tick is taken off.
     */
    fun gpuWorkMs(observedMs: Double, tickMs: Double): Double = max(0.0, observedMs - tickMs / 2)

    /**
     * How long the sound is held back so that it matches the picture (which trails the newest frame by one source interval), in
     * seconds of the video's own time: the interval on the clock, times the playback [speed] (at 2x the clock's interval holds twice
     * the video's time). Never more than [MAX_DELAY_S] of the clock.
     */
    fun audioDelaySeconds(intervalNanos: Long, speed: Float): Double =
        (intervalNanos / 1e9).coerceAtMost(MAX_DELAY_S) * speed.coerceIn(0.1f, 8f)

    /**
     * The size of the small grey picture the motion search looks at for a frame of [width] x [height]: about
     * [LumaExtractor.MAX_WIDTH] across, both sides whole blocks (the CPU extractor's own sizing, so the field means the same).
     */
    fun analysisSize(width: Int, height: Int): IntArray {
        val block = MotionEstimator.BLOCK
        val w = (minOf(LumaExtractor.MAX_WIDTH, width) / block * block).coerceAtLeast(block)
        val h = ((height.toLong() * w / width).toInt() / block * block).coerceAtLeast(block)
        return intArrayOf(w, h)
    }

    /**
     * The size frames are kept at: the video's own, or smaller when its longer side is over [MAX_FRAME_SIDE] (a 4K frame would take
     * 33 MB a slot), keeping the shape.
     */
    fun frameSize(width: Int, height: Int): IntArray {
        val longer = maxOf(width, height)
        if (longer <= MAX_FRAME_SIDE) return intArrayOf(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val scale = MAX_FRAME_SIDE.toDouble() / longer
        return intArrayOf((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    /** Frames are kept no larger than this on their longer side. */
    const val MAX_FRAME_SIDE = 1920

    /** The sound is never moved by more than this (a 10 fps video would want 100 ms). */
    const val MAX_DELAY_S = 0.1

    /** A stamp further from the arrival than this is not a release time. */
    const val MAX_STAMP_SKEW_NANOS = 500_000_000L

    /** The screen keeps drawing this long after the phase reached the newer frame, so the last frame drawn is the newer one itself. */
    const val TAIL_NANOS = 60_000_000L
}

/**
 * Which of the frames kept in the ring is shown, and which can be reused. Frames arrive early (Media3 releases a frame to the
 * surface a little before it is due), so they wait in turn: the pair on screen is the one whose newer frame is due, and the next
 * pair does not replace it before then. Slots are numbered 0 until [slotCount].
 *
 * A slot is taken by [acquire], handed back by [offer] (it joins the queue) or [giveBack] (the frame could not be used), and freed
 * again when the pair has moved past it. Single-threaded: the thread that owns the GL context calls everything.
 */
class FramePlanner(private val slotCount: Int) {

    /** The pair on screen. [older] is -1 for the first frame; [gap] is how long before the newer frame the older one was due (0 for none). */
    class Shown(val older: Int, val newer: Int, val newerAt: Long, val gap: Long, val no: Long)

    private class Entry(val slot: Int, val time: Long)

    private val taken = BooleanArray(slotCount)
    private var older: Entry? = null
    private var newer: Entry? = null
    private val waiting = ArrayDeque<Entry>()
    private var counter = 0L
    private var newestOffered = -1

    /** The slot offered last (the one the next frame is compared with), or -1. */
    val last: Int get() = newestOffered

    /** Frames waiting for their turn. */
    val pending: Int get() = waiting.size

    /** A free slot, taken, or -1 when all are in use. */
    fun acquire(): Int {
        for (i in 0 until slotCount) {
            if (!taken[i]) {
                taken[i] = true
                return i
            }
        }
        return -1
    }

    /** Gives back a slot taken by [acquire] that no frame will be kept in. */
    fun giveBack(slot: Int) {
        taken[slot] = false
    }

    /** Puts the frame in [slot], due at [time], at the end of the queue. */
    fun offer(slot: Int, time: Long) {
        val entry = Entry(slot, time)
        newestOffered = slot
        if (newer == null) newer = entry else waiting.addLast(entry)
    }

    /**
     * Drops the oldest frame that is waiting (a stall let them pile up) to make room, and returns its slot, or -1 when none waits.
     * The newest offered frame is never dropped here.
     */
    fun dropOldestWaiting(): Int {
        if (waiting.size <= 1) return -1
        val dropped = waiting.removeFirst()
        taken[dropped.slot] = false
        return dropped.slot
    }

    /** The pair on screen at [nowNanos]: waiting frames that are due take their turn first. Null before the first frame. */
    fun select(nowNanos: Long): Shown? {
        while (waiting.isNotEmpty() && waiting.first().time <= nowNanos) {
            older?.let { taken[it.slot] = false }
            older = newer
            newer = waiting.removeFirst()
            counter++
        }
        val n = newer ?: return null
        val o = older
        return Shown(o?.slot ?: -1, n.slot, n.time, if (o != null) n.time - o.time else 0L, counter)
    }

    /** Lets everything waiting take its turn at once (smoothing is off: the newest frame is shown as it comes). */
    fun showNewest(): Shown? = select(Long.MAX_VALUE)

    /** Empties the queue and frees every slot (a seek, a new video, a size change). */
    fun clear() {
        taken.fill(false)
        older = null
        newer = null
        waiting.clear()
        newestOffered = -1
    }
}
