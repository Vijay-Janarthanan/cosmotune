package com.cosmotune.music.playback.recovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long to wait between reconnect attempts while the network is gone, and for how long to keep
 * trying at all.
 *
 * A dropped connection used to get two retries 350 ms apart, after which a song that had not
 * started was skipped — on a dead connection that walked several songs down the queue in a few
 * seconds. The phone and the desktop now keep the song and its position and retry on this
 * schedule instead, the way Metrolist does (GPL-3.0, `PlaybackNetworkRecovery.kt`): quickly at
 * first, for the blip of a tunnel or a Wi-Fi handover, then every [ceilingMs] until [budgetMs]
 * has passed since the outage began.
 */
data class ReconnectSchedule(
    /** The waits before the first few attempts; the attempt after the last one waits [ceilingMs]. */
    val stepsMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L),
    val ceilingMs: Long = 10_000L,
    /** From the first failure of an outage to giving up and falling back to the skip rules. */
    val budgetMs: Long = 120_000L,
) {
    /** The wait before attempt [attempt], counted from zero. */
    fun delayFor(attempt: Int): Long = stepsMs.getOrNull(attempt.coerceAtLeast(0)) ?: ceilingMs

    companion object {
        val DEFAULT = ReconnectSchedule()
    }
}

/**
 * One network outage, as the player lives through it: when it started, how many reconnects it has
 * had, whether its time is up, and whether it has already cost the listener a song.
 *
 * Owned by one player and used from one thread. [now] is a monotonic clock in milliseconds, so the
 * tests can drive it by hand.
 *
 * The outage ends — and everything here resets — when audio plays again ([onPlaybackResumed]) or
 * the listener asks for something themselves ([reset]): pressing play or picking a song after a
 * give-up must get a fresh two minutes, not an instant refusal.
 */
class NetworkOutage(
    private val now: () -> Long,
    private val schedule: ReconnectSchedule = ReconnectSchedule.DEFAULT,
) {
    sealed interface Decision {
        /** Keep the song and position; try again after [delayMs] (sooner if the network returns). */
        data class Retry(val attempt: Int, val delayMs: Long) : Decision

        /** The outage has outlasted the budget: fall back to the ordinary failure rules. */
        data object GiveUp : Decision
    }

    private var startedAt: Long? = null
    private var attempts = 0
    private var exhausted = false
    private var skipped = false

    /** True while a reconnect is pending: the player should say "Reconnecting…". */
    val reconnecting: Boolean get() = startedAt != null && !exhausted

    /** True once the outage has outlasted the budget and not yet ended. */
    val gaveUp: Boolean get() = exhausted

    /** How long the current outage has lasted, or 0 when there is none. */
    fun elapsedMs(): Long = startedAt?.let { now() - it } ?: 0L

    /** A stream failed for want of a network. */
    fun onNetworkFailure(): Decision {
        val at = now()
        val start = startedAt ?: at.also {
            startedAt = it
            attempts = 0
        }
        if (exhausted) return Decision.GiveUp
        val elapsed = at - start
        if (elapsed >= schedule.budgetMs) {
            exhausted = true
            return Decision.GiveUp
        }
        // The last wait is cut short so the final attempt lands on the budget, not past it.
        val wait = schedule.delayFor(attempts).coerceAtMost(schedule.budgetMs - elapsed)
        attempts++
        return Decision.Retry(attempts, wait)
    }

    /**
     * The system reports a usable network again. True when a reconnect is waiting and should run
     * now rather than at the end of its backoff.
     */
    fun onNetworkAvailable(): Boolean = reconnecting

    /**
     * Whether the fallback may move the queue past the failed song. Answers true once per outage:
     * a second song failing in the same outage is the connection, not the song, and skipping it too
     * would walk the listener down a queue nothing can play.
     */
    fun claimSkip(): Boolean {
        if (skipped) return false
        skipped = true
        return true
    }

    /** Audio is coming out again: the outage is over. Returns how long it lasted, or null if none. */
    fun onPlaybackResumed(): Long? {
        val lasted = startedAt?.let { now() - it }
        reset()
        return lasted
    }

    /** The listener asked for something; whatever happens next starts a fresh outage. */
    fun reset() {
        startedAt = null
        attempts = 0
        exhausted = false
        skipped = false
    }
}

/**
 * What the player screens show while a stream waits for the network: the song whose stream is
 * reconnecting, or null. Both applications run their player in the same process as their screens,
 * so this is a plain shared value rather than something sent over a session.
 */
object PlaybackReconnectStatus {
    private val _reconnecting = MutableStateFlow<String?>(null)

    /** The id of the song waiting for the network, or null when nothing is. */
    val reconnecting: StateFlow<String?> = _reconnecting.asStateFlow()

    fun begin(songId: String) {
        _reconnecting.value = songId
    }

    fun end() {
        _reconnecting.value = null
    }

    /** The text the player shows in place of where the music comes from. */
    const val LABEL = "Reconnecting…"
}
