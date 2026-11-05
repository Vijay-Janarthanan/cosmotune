package com.cosmotune.music.data.room

import androidx.annotation.StringRes
import com.cosmotune.music.R
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.listentogether.PartyPreview
import com.cosmotune.music.data.listentogether.PartyPreviewMember
import com.cosmotune.music.data.listentogether.PartySnapshot
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomException
import com.cosmotune.room.RoomLogic
import com.cosmotune.room.RoomPreview

/**
 * The room in the shapes the existing phone code already reads, as plain functions: the party state [ListenTogether] publishes to
 * the player integration and the party screens, the preview the join sheet draws, and the short messages for what went wrong.
 */
internal object RoomPartyState {

    /**
     * The party state for a joined room. [serverNowMs] is the shared clock as best known (this device's own clock until the first
     * sample), used only for the rough playhead the player starts from before the clock is measured.
     */
    fun of(
        snapshot: PartySnapshot,
        selfUid: String,
        connection: RoomConnection,
        clockSynced: Boolean,
        roundTripMs: Long,
        serverNowMs: Long,
        error: String? = null,
    ): ListenTogether.State = ListenTogether.State(
        code = snapshot.code,
        you = snapshot.members.firstOrNull { it.memberId == selfUid },
        members = snapshot.members,
        maxMembers = snapshot.maxMembers,
        hostOnlyControl = snapshot.hostOnlyControl,
        playback = snapshot.playback.copy(effectivePositionMs = RoomLogic.positionAt(snapshot.playback, serverNowMs)),
        queue = snapshot.queue,
        connection = when (connection) {
            RoomConnection.LIVE -> ListenTogether.Connection.LIVE
            RoomConnection.CONNECTING, RoomConnection.RECONNECTING -> ListenTogether.Connection.CONNECTING
            RoomConnection.IDLE, RoomConnection.ENDED, RoomConnection.REMOVED -> ListenTogether.Connection.OFFLINE
        },
        clockSynced = clockSynced,
        roundTripMs = roundTripMs,
        error = error,
    )

    /** What the join sheet shows before a seat is taken. The room's peek has names only, no faces. */
    fun preview(room: RoomPreview): PartyPreview = PartyPreview(
        code = room.code,
        hostName = room.hostName,
        memberCount = room.memberCount,
        maxMembers = room.maxMembers,
        isFull = room.isFull,
        members = room.memberNames.map { PartyPreviewMember(displayName = it, isHost = it == room.hostName) },
    )

    @StringRes
    fun messageFor(failure: Throwable): Int = if (failure is PhoneRooms.SignInFailed) {
        if (failure.cause is java.io.IOException) R.string.room_err_offline else R.string.room_err_sign_in
    } else when ((failure as? RoomException)?.reason) {
        RoomException.Reason.NOT_FOUND -> R.string.room_err_not_found
        RoomException.Reason.FULL -> R.string.room_err_full
        RoomException.Reason.EXPIRED -> R.string.room_err_expired
        RoomException.Reason.DENIED -> R.string.room_err_denied
        RoomException.Reason.HOST_ONLY -> R.string.listen_together_host_only_notice
        RoomException.Reason.OFFLINE -> R.string.room_err_offline
        RoomException.Reason.NOT_IN_ROOM -> R.string.room_err_not_in_room
        RoomException.Reason.OTHER, null -> if (failure is java.io.IOException) R.string.room_err_offline else R.string.room_err_other
    }

    /** A short, stable suffix for a guest's name, so two guests in one room can be told apart: the same on every launch. */
    fun guestSuffix(deviceId: String): String =
        deviceId.filter(Char::isLetterOrDigit).takeLast(4).uppercase().ifEmpty { "0000" }

    /** Chat lines from other people that arrived after [seenAtMs]. System lines are not news. */
    fun unread(chat: List<RoomChatMessage>, selfUid: String, seenAtMs: Long): Int =
        chat.count { it.uid != selfUid && it.kind != "system" && it.atMs > seenAtMs }
}
