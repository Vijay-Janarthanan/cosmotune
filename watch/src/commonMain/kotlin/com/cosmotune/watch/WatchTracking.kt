package com.cosmotune.watch

import kotlinx.coroutines.flow.StateFlow

enum class WatchTracker(val label: String) { TRAKT("Trakt"), SIMKL("Simkl") }
data class WatchTrackingStatus(val service: WatchTracker, val connected: Boolean = false, val busy: Boolean = false,
    val message: String? = null, val verificationUrl: String? = null, val userCode: String? = null)

interface WatchTracking {
    val statuses: StateFlow<List<WatchTrackingStatus>>
    /** Register a public client with the service. No client secret is embedded in the app. */
    suspend fun connect(service: WatchTracker, clientId: String)
    suspend fun disconnect(service: WatchTracker)
    suspend fun sync(service: WatchTracker)
    suspend fun report(progress: com.cosmotune.watch.model.WatchProgress, action: String) {}
}
