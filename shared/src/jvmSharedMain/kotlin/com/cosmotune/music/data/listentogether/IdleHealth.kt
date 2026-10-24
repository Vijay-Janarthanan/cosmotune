package com.cosmotune.music.data.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When the idle Listen Together status row may ask the party server's health endpoint, on the phone and the desktop.
 *
 * Every probe is a request to a server shared by every install, so nothing is asked while nobody can see the answer
 * (the screen is closed), while a live party socket already proves the server is reachable, or, outside a party, on
 * a metered connection. The poll is 10 s and the debounce 5 s: a debounce above the poll interval would silently
 * stretch the poll to the debounce length.
 */
object IdleHealthPolicy {
    const val POLL_INTERVAL_MS = 10_000L
    const val MIN_GAP_MS = 5_000L

    /** Whether an idle health probe should be skipped. [metered] null is an unknown network, not a metered one. */
    fun shouldSkipIdleProbe(screenActive: Boolean, connectionLive: Boolean, inParty: Boolean, metered: Boolean?): Boolean {
        if (!screenActive) return true
        if (connectionLive) return true
        if (!inParty && metered == true) return true
        return false
    }

    /**
     * Whether a probe asked for at [nowMs] falls inside the debounce after the last one ([lastProbeMs], null before
     * the first). A [force]d probe (the listener pressed refresh, the server changed) is never debounced. Times are
     * a monotonic clock's.
     */
    fun debounced(nowMs: Long, lastProbeMs: Long?, force: Boolean, minGapMs: Long = MIN_GAP_MS): Boolean =
        !force && lastProbeMs != null && nowMs - lastProbeMs in 0 until minGapMs
}

/**
 * The idle poll itself: while [setScreenActive] holds it open, one probe on opening and one every [pollIntervalMs],
 * each passed through [IdleHealthPolicy]. [state] is read at every tick, so a party socket that comes up or a network
 * that turns metered stops the requests without the caller doing anything. [probe] runs on [scope] and is never
 * overlapped with itself by this monitor.
 */
class IdleHealthMonitor(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val state: () -> State,
    private val probe: suspend () -> Unit,
    private val pollIntervalMs: Long = IdleHealthPolicy.POLL_INTERVAL_MS,
    private val minGapMs: Long = IdleHealthPolicy.MIN_GAP_MS,
) {
    data class State(val connectionLive: Boolean, val inParty: Boolean, val metered: Boolean?)

    private var screenActive = false
    private var lastProbeMs: Long? = null
    private var loop: Job? = null

    /** Opens or closes the poll. Opening probes at once unless the debounce or the policy says not to. */
    @Synchronized
    fun setScreenActive(active: Boolean) {
        screenActive = active
        loop?.cancel()
        loop = null
        if (!active) return
        loop = scope.launch {
            while (isActive) {
                maybeProbe()
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun maybeProbe() {
        val snapshot = state()
        val allowed = synchronized(this) {
            if (IdleHealthPolicy.shouldSkipIdleProbe(screenActive, snapshot.connectionLive, snapshot.inParty, snapshot.metered)) return@synchronized false
            val now = nowMs()
            if (IdleHealthPolicy.debounced(now, lastProbeMs, force = false, minGapMs = minGapMs)) return@synchronized false
            lastProbeMs = now
            true
        }
        if (allowed) probe()
    }

    /** Records a probe made outside the poll (a refresh button, a server change), so the poll does not repeat it at once. */
    @Synchronized
    fun noteProbe() {
        lastProbeMs = nowMs()
    }
}
