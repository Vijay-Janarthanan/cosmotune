package com.cosmotune.music.playback

import androidx.media3.common.PlaybackException
import java.io.File

/** Rules that keep complete offline copies separate from partial or newly selected renditions. */
internal object CachedAudioPolicy {
    data class Copy(val key: String, val complete: Boolean, val keptOrigin: Boolean)

    /** Only YouTube substitution buckets changed identity; downloads, originals and local files did not. */
    fun isSubstitutionKey(key: String): Boolean {
        val base = key.substringBefore('#')
        if (base.length != 11 || base.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) return false
        return key.substringAfter('#', "") in setOf("alt", "alt-noatmos", "alt#watch", "alt-noatmos#watch")
    }

    fun sibling(preferred: String, preferredHasBytes: Boolean, offline: Boolean, copies: List<Copy>): String? {
        if (preferredHasBytes) return null
        // While online, an alternative bucket must still resolve the source/quality currently
        // requested. A plain YouTube request may reuse a complete retained YouTube/JioSaavn copy.
        if (!offline && '#' in preferred) return null
        return copies.firstOrNull { it.key != preferred && it.complete && it.keptOrigin }?.key
    }

    /** A failed move leaves every old span and its UID/index in place for the next startup. */
    fun directory(legacy: File, persistent: File, move: (File, File) -> Boolean = { from, to -> from.renameTo(to) }): File {
        if (!legacy.isDirectory || persistent.exists()) return persistent
        persistent.parentFile?.mkdirs()
        return if (move(legacy, persistent)) persistent else legacy
    }

    fun keepComplete(errorCode: Int, offline: Boolean): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> true
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> offline
        else -> false // a malformed container is still malformed without a connection
    }
}
