package com.cosmotune.music.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Desktop equivalent of Android's elapsed-realtime sleep timer. */
object DesktopSleepTimer {
    private val deadlineNs = AtomicLong(0L)

    /** The clock the deadline is on; a test swaps in its own. */
    internal var nanoTime: () -> Long = System::nanoTime

    val minutes = MutableStateFlow<Int?>(null)
    val afterTrack = MutableStateFlow(false)

    val presets = listOf(15, 30, 45, 60)

    /** What Extend adds to a running timer. */
    const val EXTEND_MINUTES = 15

    fun start(minutes: Int) {
        require(minutes > 0) { "Sleep timer duration must be positive" }
        afterTrack.value = false
        this.minutes.value = minutes
        deadlineNs.set(nanoTime() + minutes * 60L * 1_000_000_000L)
    }

    /**
     * Adds [by] minutes to a running timer, from the later of its deadline and
     * now, or starts one of that length when none is running.
     */
    fun extend(by: Int = EXTEND_MINUTES) {
        val current = deadlineNs.get()
        if (current == 0L) {
            start(by)
            return
        }
        afterTrack.value = false
        minutes.value = (minutes.value ?: 0) + by
        val now = nanoTime()
        val from = if (current - now > 0) current else now
        deadlineNs.set(from + by * 60L * 1_000_000_000L)
    }

    fun startAfterTrack() {
        minutes.value = null
        deadlineNs.set(0L)
        afterTrack.value = true
    }

    fun cancel() {
        minutes.value = null
        deadlineNs.set(0L)
        afterTrack.value = false
    }

    fun remainingMs(): Long? = deadlineNs.get().takeIf { it != 0L }
        ?.let { (it - nanoTime()).coerceAtLeast(0L) / 1_000_000L }

    fun isExpired(): Boolean = deadlineNs.get() != 0L && remainingMs() == 0L

    /**
     * Whether the engine should open the next track to blend into. Not on
     * repeat-one (the track replays from the engine's end instead) and not
     * with "End of this track" armed: a blend hands over to the next track
     * without the current one ever ending, and its end is where that timer
     * pauses.
     */
    fun preparesNext(repeatOne: Boolean, afterTrack: Boolean): Boolean = !repeatOne && !afterTrack
}

/** [DesktopSleepTimer] as the shared screens reach it (the player's pill and menu). */
internal object DesktopSleepTimerControl : com.cosmotune.ui.sleep.SleepTimerControl {
    override val minutes: kotlinx.coroutines.flow.StateFlow<Int?> get() = DesktopSleepTimer.minutes
    override val afterTrack: kotlinx.coroutines.flow.StateFlow<Boolean> get() = DesktopSleepTimer.afterTrack
    override val presets: List<Int> get() = DesktopSleepTimer.presets
    override fun remainingMs(): Long? = DesktopSleepTimer.remainingMs()
    override fun start(minutes: Int) = DesktopSleepTimer.start(minutes)
    override fun startAfterTrack() = DesktopSleepTimer.startAfterTrack()
    override fun extend() = DesktopSleepTimer.extend()
    override fun cancel() = DesktopSleepTimer.cancel()
}
