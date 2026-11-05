package com.cosmotune.room

import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack

/** How this device appears in a room. The id is stable per install, so a rejoin takes the same seat. */
data class RoomProfile(val name: String, val avatarUrl: String?, val deviceId: String)

data class RoomMeta(
    val createdAtMs: Long,
    val hostUid: String,
    val maxMembers: Int,
    val hostOnlyControl: Boolean = false,
    val autoplay: Boolean = false,
)

/** One person in the room. [mic] is what they chose; [connected] is whether they were heard from lately, which is the only presence there is. */
data class RoomMember(
    val seat: Int,
    val uid: String,
    val name: String,
    val avatarUrl: String?,
    val deviceId: String,
    val joinedAtMs: Long,
    val lastSeenMs: Long,
    val micLive: Boolean,
    val isHost: Boolean,
    val connected: Boolean,
)

/** [kind] is `text`, `request` or `system`. A request carries the song in [ref], so any member can play it without looking it up. */
data class RoomChatMessage(val id: String, val uid: String, val name: String, val kind: String, val text: String, val ref: PartyTrack?, val atMs: Long)

/** Everything a joined device knows about the room, rebuilt from one stream. */
data class RoomState(
    val code: String,
    val meta: RoomMeta,
    val members: List<RoomMember>,
    val playback: PartyPlayback,
    val queue: PartyQueue,
    val chat: List<RoomChatMessage>,
    /** The server's clock as this device reads it when the state was built; null until the clock has a sample. */
    val serverNowMs: Long?,
) {
    fun member(uid: String): RoomMember? = members.firstOrNull { it.uid == uid }
    val host: RoomMember? get() = members.firstOrNull { it.isHost }
}

/** Who is inside a room, to somebody who has only the code. */
data class RoomPreview(val code: String, val hostName: String, val memberCount: Int, val maxMembers: Int, val memberNames: List<String>) {
    val isFull: Boolean get() = memberCount >= maxMembers
}

enum class RoomConnection { IDLE, CONNECTING, LIVE, RECONNECTING, ENDED, REMOVED }

/** What a person can do to the shared music. Each becomes one write with the next sequence number. */
sealed interface RoomControl {
    data class Play(val positionMs: Long? = null) : RoomControl
    data class Pause(val positionMs: Long? = null) : RoomControl
    data class Seek(val positionMs: Long) : RoomControl
    data class SetTrack(val track: PartyTrack, val positionMs: Long = 0, val isPlaying: Boolean = true) : RoomControl
    data class SetQueue(val items: List<PartyTrack>, val index: Int) : RoomControl
    data class QueueAdd(val tracks: List<PartyTrack>, val playNext: Boolean = false) : RoomControl
    data class QueueRemove(val videoId: String) : RoomControl
    data object QueueClear : RoomControl
    data class QueueMove(val fromIndex: Int, val toIndex: Int, val videoId: String? = null) : RoomControl
    data object Next : RoomControl

    /**
     * Next, but only if the room is still at [expectedSeq]. Two devices that both notice a song ended send this, and the one that
     * loses the race does nothing instead of skipping a second song.
     */
    data class NextAfter(val expectedSeq: Long) : RoomControl
    data object Previous : RoomControl
}

class RoomException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_FOUND, FULL, EXPIRED, DENIED, NOT_IN_ROOM, HOST_ONLY, OFFLINE, OTHER }
}
