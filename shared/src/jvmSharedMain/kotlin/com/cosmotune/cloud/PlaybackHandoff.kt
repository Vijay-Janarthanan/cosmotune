package com.cosmotune.cloud

import com.cosmotune.music.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one device is playing, kept in the Cosmotune account (`users/{uid}/playback/now`) so another device signed in to the same
 * account can carry on with it: the song, where it is up to, and the queue around it. One document, overwritten; a device writes
 * it when the song, the queue or play/pause changes and every few minutes while it plays ([HandoffPolicy]), and reads it when it
 * is not playing itself.
 */
@Serializable
data class PlaybackHandoff(
    val deviceId: String,
    /** How the other devices name this one: "your phone", "your Windows PC". */
    val deviceName: String,
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    /** When the device wrote it, by its own clock. */
    val atMs: Long,
) {
    val song: Song? get() = songs.getOrNull(index)

    /** How many songs follow the current one. */
    val upNext: Int get() = (songs.size - index - 1).coerceAtLeast(0)

    /** Where it is up to at [nowMs]: still moving while it was playing, up to the end of the song. */
    fun positionAt(nowMs: Long): Long {
        val moved = if (playing) (nowMs - atMs).coerceAtLeast(0) else 0L
        val at = positionMs + moved
        return if (durationMs > 0) at.coerceAtMost(durationMs) else at
    }

    companion object {
        /** Songs kept before the current one, and after it: enough to carry on, small enough for one document. */
        const val KEEP_BEFORE = 10
        const val KEEP_AFTER = 100

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** The handoff of [songs] playing at [index], cut to [KEEP_BEFORE] before and [KEEP_AFTER] after it. */
        fun of(
            deviceId: String, deviceName: String, songs: List<Song>, index: Int, positionMs: Long, durationMs: Long, playing: Boolean, atMs: Long,
        ): PlaybackHandoff? {
            if (index !in songs.indices) return null
            val from = (index - KEEP_BEFORE).coerceAtLeast(0)
            val to = (index + 1 + KEEP_AFTER).coerceAtMost(songs.size)
            return PlaybackHandoff(deviceId, deviceName, songs.subList(from, to).toList(), index - from, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), playing, atMs)
        }

        /** The stored form: a few plain fields to read at a glance, and the whole of it as one JSON string. */
        fun toFields(handoff: PlaybackHandoff): JsonObject = buildJsonObject {
            put("deviceId", handoff.deviceId)
            put("deviceName", handoff.deviceName)
            put("atMs", handoff.atMs)
            put("data", json.encodeToString(serializer(), handoff))
        }

        fun fromFields(fields: JsonObject): PlaybackHandoff? =
            (fields["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** When a device writes what it plays, and when another device's song is worth offering. Pure, so it can be tested. */
object HandoffPolicy {
    /** While a song plays, the document is written at least this often, so the position the other device starts from stays close. */
    const val PLAYING_EVERY_MS = 2 * 60 * 1000L

    /** A position this far from where the last write said it would be is a seek, which is written at once. */
    const val SEEK_MS = 15_000L

    /** An offer older than this is no longer shown: the listener has moved on. */
    const val OFFER_FOR_MS = 12 * 60 * 60 * 1000L

    /** Whether [next] should be written, given what was written last ([last], null for nothing yet). */
    fun shouldWrite(last: PlaybackHandoff?, next: PlaybackHandoff): Boolean {
        if (last == null) return true
        if (last.song?.videoId != next.song?.videoId) return true
        if (last.playing != next.playing) return true
        if (last.songs.map(Song::videoId) != next.songs.map(Song::videoId)) return true
        val expected = last.positionAt(next.atMs)
        if (kotlin.math.abs(next.positionMs - expected) > SEEK_MS) return true
        return next.playing && next.atMs - last.atMs >= PLAYING_EVERY_MS
    }

    /**
     * The handoff to offer on this device, or null. Offered when it came from another device, is recent, has a song, is newer than
     * what this device last played ([localLastActivityMs]) and was not put aside here ([dismissedAtMs]), and this device is not
     * playing ([localPlaying]).
     */
    fun offer(
        remote: PlaybackHandoff?, myDeviceId: String, localPlaying: Boolean, localLastActivityMs: Long, dismissedAtMs: Long, nowMs: Long,
    ): PlaybackHandoff? {
        if (remote == null || remote.song == null) return null
        if (remote.deviceId == myDeviceId || localPlaying) return null
        if (remote.atMs <= localLastActivityMs || remote.atMs == dismissedAtMs) return null
        if (nowMs - remote.atMs > OFFER_FOR_MS) return null
        return remote
    }
}
