package com.cosmotune.music.ui.room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.cosmotune.music.R
import com.cosmotune.music.data.listentogether.JamInvite
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.room.PhoneRooms
import com.cosmotune.room.RoomControl
import com.cosmotune.ui.room.RoomActions

/**
 * The room screen's actions on the phone. The music controls go to the room, not to the player: the player follows the room
 * (see [com.cosmotune.music.playback.PartySync]), so a tap here moves every device in it, this one included. A control the room
 * refuses (host-only) shows its message and leaves the music where it is.
 */
class PhoneRoomActions(
    private val context: Context,
    /** Opens the song picker; the pick goes to [PhoneRooms.askToPlay]. */
    private val onAskToPlay: () -> Unit,
    /** Asks before leaving; the confirmation calls [PhoneRooms.leave]. */
    private val onLeaveRequested: () -> Unit,
    /** This device's own quality, captions and speed for the room's item, where its own player keeps them. */
    private val onPlaybackOptions: () -> Unit = {},
) : RoomActions {
    override fun play() = PhoneRooms.control(RoomControl.Play())
    override fun pause() = PhoneRooms.control(RoomControl.Pause())
    override fun seekTo(positionMs: Long) = PhoneRooms.control(RoomControl.Seek(positionMs))
    override fun next() = PhoneRooms.control(RoomControl.Next)
    override fun previous() = PhoneRooms.control(RoomControl.Previous)
    override fun playTrack(track: PartyTrack) = PhoneRooms.control(RoomControl.SetTrack(track))
    override fun removeFromQueue(videoId: String) = PhoneRooms.control(RoomControl.QueueRemove(videoId))
    override fun clearQueue() = PhoneRooms.control(RoomControl.QueueClear)

    override fun sendChat(text: String) = PhoneRooms.sendChat(text)
    override fun askToPlay() = onAskToPlay()

    // Voice runs off the main thread in PhoneRooms; the first unmute is what asks for the microphone.
    override fun setMicLive(live: Boolean) = PhoneRooms.setMicLive(live)
    override fun setDeafened(deafened: Boolean) = PhoneRooms.setDeafened(deafened)
    override fun setMemberMuted(uid: String, muted: Boolean) = PhoneRooms.setMemberMuted(uid, muted)
    override fun setMemberVolume(uid: String, volume: Float) = PhoneRooms.setMemberVolume(uid, volume)
    override fun openMicSettings() = PhoneRooms.openMicSettings()
    override fun dismissVoiceMessage() = PhoneRooms.dismissVoiceMessage()

    override fun setHostOnlyControl(enabled: Boolean) = PhoneRooms.setHostOnlyControl(enabled)
    override fun removeMember(uid: String) = PhoneRooms.removeMember(uid)

    override fun copyInviteCode(code: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(code, code))
        Toast.makeText(context, context.getString(R.string.room_code_copied), Toast.LENGTH_SHORT).show()
    }

    override fun shareInvite(code: String) = shareRoomInvite(context, code)

    override fun leave() = onLeaveRequested()
    override fun dismissMessage() = PhoneRooms.dismissMessage()
    override fun chatSeen() = PhoneRooms.chatSeen()
    override fun openPlaybackOptions() = onPlaybackOptions()
}

/** The system chooser with the invite link (it opens the app straight into the join sheet) and the code to read aloud. */
fun shareRoomInvite(context: Context, code: String) {
    val message = JamInvite.url(code) + System.lineSeparator() + System.lineSeparator() +
        context.getString(R.string.room_share_text, code)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            },
            null,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
