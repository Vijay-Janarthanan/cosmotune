package com.cosmotune.cloud

/**
 * When a sync starts without the person pressing anything. Cloud sync is opt-in: until [enabled] says so, none of these does anything,
 * and since [sync] is the only way in to the account, the account is never built, no guest is made and nothing reaches the network.
 *
 * [sync] is told how long to wait for quiet first (null: now), so a burst of edits is one sync. Pure: the platforms supply the flag,
 * the connectivity check and the account call.
 */
class CloudAutoSync(
    private val enabled: () -> Boolean,
    private val online: () -> Boolean,
    private val sync: (quietMs: Long?) -> Unit,
) {
    /** An edit was made (or a sync was due) while offline: it goes when the network comes back. */
    @Volatile var waitingForNetwork = false
        private set

    /** The first sync after launch, and the periodic one. */
    fun due() {
        if (!enabled()) return
        if (online()) sync(null) else waitingForNetwork = true
    }

    /** Something in the library changed on this device. */
    fun changed() {
        if (!enabled()) return
        if (online()) sync(CHANGE_QUIET_MS) else waitingForNetwork = true
    }

    /**
     * The network is back: send what waited for it, or retry a sync that failed for want of it. [lastSyncFailed] is false for a failure
     * the network cannot fix (the person must sign in again), so a flapping connection does not retry that over and over.
     */
    fun networkBack(lastSyncFailed: Boolean) {
        if (!enabled()) return
        if (!waitingForNetwork && !lastSyncFailed) return
        waitingForNetwork = false
        sync(NETWORK_SETTLE_MS)
    }

    /** Cloud sync was turned off: nothing is owed any more. */
    fun stopped() {
        waitingForNetwork = false
    }

    companion object {
        /** As [CloudAccount.requestSync]: renaming a playlist and then adding three songs to it is one sync. */
        const val CHANGE_QUIET_MS = 8_000L

        /** After the network comes back, a moment for it to settle (a captive portal, a VPN) before anything is sent. */
        const val NETWORK_SETTLE_MS = 5_000L
    }
}
