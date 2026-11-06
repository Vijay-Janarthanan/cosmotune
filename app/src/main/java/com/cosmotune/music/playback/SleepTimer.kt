package com.cosmotune.music.playback

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sleep timer. Holds a deadline; [PlaybackService] watches it and pauses
 * playback once it passes.
 *
 * A deadline rather than a countdown: nothing has to tick for the timer to
 * stay accurate, so it survives the player UI being dismissed, and any
 * observer can work out how long is left for itself. elapsedRealtime, not
 * wall clock, so changing the system time can't cut a timer short.
 */
object SleepTimer {

    /**
     * The clock the deadline is on. [SystemClock.elapsedRealtime] keeps counting
     * while the phone is asleep; a test swaps in its own.
     */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Deadline on [clock], or null when no timer is set. */
    val deadline = MutableStateFlow<Long?>(null)

    /**
     * How long the timer was set for, in minutes, extensions included, so the
     * picker can tick the preset that was chosen. Null when off.
     */
    val minutes = MutableStateFlow<Int?>(null)

    /**
     * Pause when the current track ends instead of after a fixed wait.
     *
     * Deliberately not expressed as a deadline of "duration minus position":
     * seeking, crossfade and a queue that reorders itself would all leave that
     * number wrong, whereas the track ending is an event the player reports.
     */
    val afterTrack = MutableStateFlow(false)

    /** Durations offered in the picker. */
    val PRESETS = listOf(15, 30, 45, 60)

    /** What Extend adds to a running timer. */
    const val EXTEND_MINUTES = 15

    /**
     * The longest the watcher waits before it looks at the clock again.
     *
     * The wait itself runs on uptime (a coroutine delay is a Handler message),
     * and uptime stops while the CPU sleeps, which it does between audio buffer
     * refills with the screen off. A single delay of the whole half hour could
     * then take hours of real time. Waiting in short steps and reading
     * [clock] after each means the timer is late by at most one step of
     * uptime, however much of the time the phone spent asleep.
     */
    const val CHECK_EVERY_MS = 5_000L

    /** Whether any kind of timer is currently armed. */
    val isRunning: Boolean get() = deadline.value != null || afterTrack.value

    fun start(minutes: Int) {
        require(minutes > 0) { "Sleep timer duration must be positive" }
        afterTrack.value = false
        this.minutes.value = minutes
        deadline.value = clock() + minutes * 60_000L
    }

    /**
     * Adds [by] minutes to a running timer, or starts one of that length when
     * none is running (an "after this song" timer becomes a timed one).
     */
    fun extend(by: Int = EXTEND_MINUTES) {
        val current = deadline.value
        if (current == null) {
            start(by)
            return
        }
        afterTrack.value = false
        minutes.value = (minutes.value ?: 0) + by
        // From the later of the old deadline and now, so extending a timer
        // that is already due still gives the whole extension.
        deadline.value = maxOf(current, clock()) + by * 60_000L
    }

    /** Pause once the track playing right now finishes. */
    fun startAfterTrack() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = true
    }

    fun cancel() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = false
    }

    /** How long is left, or null when no timer is running. */
    fun remainingMs(): Long? =
        deadline.value?.let { (it - clock()).coerceAtLeast(0L) }

    /** Whether a timed timer is set and its deadline has passed. */
    fun isDue(): Boolean = deadline.value?.let { clock() >= it } ?: false

    /**
     * Returns once [clock] has reached [deadline], waiting at most
     * [CHECK_EVERY_MS] at a time between readings; see [CHECK_EVERY_MS] for why
     * the wait is never the whole remainder at once.
     */
    suspend fun awaitDeadline(
        deadline: Long,
        wait: suspend (Long) -> Unit = { delay(it) },
    ) {
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0L) return
            wait(remaining.coerceAtMost(CHECK_EVERY_MS))
        }
    }
}
