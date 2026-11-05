package com.cosmotune.music.data.room.voice

import com.cosmotune.room.voice.PeerState
import kotlin.math.log10
import kotlin.math.max

/**
 * The parts of the Android voice engine that are plain arithmetic, kept apart from WebRTC so they can be tested on the JVM:
 * turning the stats' audio levels into the 0 to 1 the room's "speaking" ring reads, and the connection state into the room's.
 */
internal object VoiceLevels {
    /**
     * WebRTC reports `audioLevel` as linear amplitude, where ordinary speech sits around 0.01 to 0.3; read straight, a quiet
     * speaker barely moves the ring. Levels are mapped through decibels instead: [FLOOR_DB] (noise-suppressed silence lies
     * well below it) is 0 and [CEILING_DB] is 1, so the room's speaking threshold of 0.06 lands near -43 dB.
     */
    const val FLOOR_DB = -45.0
    const val CEILING_DB = -5.0

    /** How much of the previous level is kept when the new one is lower, per poll, so the ring fades instead of flickering. */
    const val RELEASE = 0.5f

    fun normalise(raw: Double?): Float {
        if (raw == null || raw.isNaN() || raw <= 0.0) return 0f
        val db = 20.0 * log10(raw.coerceAtMost(1.0))
        return ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
    }

    /** Rises at once, falls by [RELEASE] per poll. */
    fun smooth(previous: Float, next: Float): Float {
        if (next >= previous) return next
        val faded = max(next, previous * RELEASE)
        return if (faded < 0.01f) 0f else faded
    }

    /**
     * Picks the two levels out of one connection's stats: what arrives from the other person (`inbound-rtp`) and what this
     * device's microphone gives (`media-source`). Each report is its type and its members; null when that report is absent.
     */
    fun levelsOf(reports: Collection<Pair<String, Map<String, Any?>>>): Pair<Float?, Float?> {
        var remote: Float? = null
        var local: Float? = null
        for ((type, members) in reports) {
            if (members["kind"] != "audio") continue
            val level = (members["audioLevel"] as? Number)?.toDouble()
            when (type) {
                "inbound-rtp" -> remote = max(remote ?: 0f, normalise(level))
                "media-source" -> local = max(local ?: 0f, normalise(level))
            }
        }
        return remote to local
    }

    /** The connection state by WebRTC's name for it; a dropped connection that may come back counts as connecting. */
    fun peerState(webRtcName: String): PeerState = when (webRtcName) {
        "NEW" -> PeerState.NEW
        "CONNECTING", "DISCONNECTED" -> PeerState.CONNECTING
        "CONNECTED" -> PeerState.CONNECTED
        "FAILED" -> PeerState.FAILED
        "CLOSED" -> PeerState.CLOSED
        else -> PeerState.CONNECTING
    }

    /** A per-person volume the mesh hands over (0 to 1) as the gain WebRTC applies to their track (1 is as received). */
    fun gain(volume: Float): Double = if (volume.isNaN()) 1.0 else volume.coerceIn(0f, 1f).toDouble()
}
