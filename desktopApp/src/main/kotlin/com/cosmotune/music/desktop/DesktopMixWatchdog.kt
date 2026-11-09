package com.cosmotune.music.desktop

/** Timing and session fault budget for the single-sink mixer. Its audio thread owns all writes. */
internal class DesktopMixWatchdog(private val stallNanos: Long = 3_000_000_000L) {
    private var outgoingDrySince: Long? = null
    private var incomingDrySince: Long? = null
    @Volatile private var faults = 0

    val beatMatchAllowed: Boolean get() = faults < 3
    val overlapAllowed: Boolean get() = faults < 6

    fun outgoingReady() { outgoingDrySince = null }
    fun incomingReady() { incomingDrySince = null }

    fun outgoingStalled(now: Long): Boolean {
        val since = outgoingDrySince ?: now.also { outgoingDrySince = it }
        return now - since >= stallNanos
    }

    fun incomingStalled(now: Long): Boolean {
        val since = incomingDrySince ?: now.also { incomingDrySince = it }
        return now - since >= stallNanos
    }

    fun resetClocks() {
        outgoingReady()
        incomingReady()
    }

    fun recordFault(): Int = ++faults
}
