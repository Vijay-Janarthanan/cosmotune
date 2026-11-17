package com.cosmotune.cloud

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * One device's side of carrying on from another: it writes what this device plays ([playing]) and looks for what another device
 * of the account played while this one plays nothing ([look], [elsewhere]). The phone and the desktop each make one, with their own
 * cloud account, preferences and device name. Nothing here throws, and nothing is sent unless cloud sync is on ([enabled]).
 */
class HandoffHost(
    /** How the other devices name this one: "your phone", "your Windows PC". */
    private val deviceName: String,
    private val enabled: () -> Boolean,
    private val publish: suspend (PlaybackHandoff) -> Boolean,
    private val latest: suspend () -> PlaybackHandoff?,
    /** A small text preference of this device, "" when unset. */
    private val read: (String) -> String,
    private val write: (String, String) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** This device's own id among the account's devices, made once. */
    val deviceId: String by lazy {
        read(KEY_DEVICE_ID).ifBlank { java.util.UUID.randomUUID().toString().also { write(KEY_DEVICE_ID, it) } }
    }

    // When this device last played, kept to the half minute so that playing does not write the preferences every second.
    @Volatile private var lastPlayedMs = 0L
    private val lastWritten = AtomicReference<PlaybackHandoff?>(null)
    private val offer = MutableStateFlow<PlaybackHandoff?>(null)

    /** Another device's song this one can carry on with, while this one plays nothing; null otherwise. */
    val elsewhere: StateFlow<PlaybackHandoff?> = offer.asStateFlow()

    /** What this device plays. Cheap to call every second: it writes only what [HandoffPolicy] says is worth writing. */
    fun playing(songs: List<Song>, index: Int, positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        if (isPlaying) {
            val at = now()
            if (at - lastPlayedMs > 30_000L) {
                lastPlayedMs = at
                write(KEY_LAST_PLAYED_MS, at.toString())
            }
            offer.value = null
        }
        if (!enabled()) return
        val next = PlaybackHandoff.of(deviceId, deviceName, songs, index, positionMs, durationMs, isPlaying, now()) ?: return
        if (!HandoffPolicy.shouldWrite(lastWritten.get(), next)) return
        lastWritten.set(next)
        scope.launch { if (!runCatching { publish(next) }.getOrDefault(false)) lastWritten.compareAndSet(next, null) }
    }

    /** Looks for another device's song to offer (see [elsewhere]); [isPlaying] is whether this device plays now. */
    suspend fun look(isPlaying: Boolean) {
        if (!enabled() || isPlaying) {
            offer.value = null
            return
        }
        val remote = runCatching { latest() }.getOrNull()
        val lastPlayed = maxOf(lastPlayedMs, read(KEY_LAST_PLAYED_MS).toLongOrNull() ?: 0L)
        val putAside = read(KEY_PUT_ASIDE_MS).toLongOrNull() ?: 0L
        offer.value = HandoffPolicy.offer(remote, deviceId, false, lastPlayed, putAside, now())
    }

    /** No thanks: this offer is not shown again here. */
    fun putAside(handoff: PlaybackHandoff) {
        write(KEY_PUT_ASIDE_MS, handoff.atMs.toString())
        offer.value = null
    }

    companion object {
        const val KEY_DEVICE_ID = "handoff_device_id"
        const val KEY_LAST_PLAYED_MS = "handoff_last_played_ms"
        const val KEY_PUT_ASIDE_MS = "handoff_put_aside_ms"
    }
}
