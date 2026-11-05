package com.cosmotune.ui.room

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomState

/**
 * What the room screen shows. Built by each platform from [com.cosmotune.room.RoomClient] (and its own player), so the screen itself
 * is one piece of shared UI for phone and desktop and holds no network or player code.
 */
data class RoomUiState(
    /** The joined room, or null while connecting or after it ended. */
    val room: RoomState?,
    val connection: RoomConnection,
    /** The signed-in account's id: tells which member is "you" and whether you are the host. */
    val selfUid: String,
    /** The shared playhead now, from the server clock; the screen does not need to tick it, the host updates this about once a second. */
    val positionMs: Long,
    /** This device's microphone as chosen by the person (live to the room or muted). */
    val micLive: Boolean = false,
    /** Voice calls are a later step: while false the mic button is shown but explains that voice is coming, and does not claim to be live. */
    val voiceAvailable: Boolean = false,
    /** Voices muted for this device only. */
    val deafened: Boolean = false,
    /** A short message from the last action that failed (a refused control, no connection); the screen shows it and the host clears it. */
    val message: String? = null,
    /** Chat messages not yet seen while another page was open. */
    val unreadChat: Int = 0,
    /**
     * The people heard speaking now, from the voice links' audio levels. The host adds [selfUid] while this device's own mic is live
     * and loud, so your own face rings too. A person muted here never rings (see [showsSpeaking]).
     */
    val speakingUids: Set<String> = emptySet(),
    /** The people this device has muted for itself only; nobody else hears a difference. */
    val mutedHere: Set<String> = emptySet(),
    /** This device's volume for each person, 0 to 1; a person missing from the map is at full volume (see [volumeOf]). */
    val volumes: Map<String, Float> = emptyMap(),
    /** The people whose voice link is still being made. */
    val voiceConnecting: Set<String> = emptySet(),
    /** The microphone permission was refused for good: only the system settings can give it back, so the mic button opens them. */
    val micBlocked: Boolean = false,
    /** How loud this device's own microphone is now, 0 to 1, for the small meter on the mic button while it is live. */
    val micLevel: Float = 0f,
    /** A voice problem to show until dismissed ("The microphone is not available"); the host clears it on [RoomActions.dismissVoiceMessage]. */
    val voiceMessage: String? = null,
) {
    val self get() = room?.member(selfUid)
    val isHost get() = room?.meta?.hostUid == selfUid
    /** Whether this person may drive the music: everyone, unless the host switched on host-only control. */
    val canControl get() = room != null && (!room.meta.hostOnlyControl || isHost)
}

/** What a person can do in the room screen. Every one is a call into the platform's room client or player; none blocks the screen. */
interface RoomActions {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()

    /** Plays a song now in the room (from a request card or the queue). */
    fun playTrack(track: PartyTrack)
    fun removeFromQueue(videoId: String)
    fun clearQueue()

    fun sendChat(text: String)

    /** Asks the room to play a song: a request card in the chat. The platform opens its own song picker. */
    fun askToPlay()

    fun setMicLive(live: Boolean)
    fun setDeafened(deafened: Boolean)

    /** Host only. */
    fun setHostOnlyControl(enabled: Boolean)
    fun removeMember(uid: String)

    fun copyInviteCode(code: String)
    fun shareInvite(code: String)
    fun leave()
    fun dismissMessage()

    /** The chat page was opened: the host resets the unread count. */
    fun chatSeen()

    /**
     * Opens this device's own playback options for what the room is playing (video quality, captions, audio track, speed). Each
     * person has their own: changing them never changes the room. A host that has nothing to open leaves this as it is.
     */
    fun openPlaybackOptions() {}

    /** Mutes (or unmutes) [uid] for this device only. Nothing is sent to the room. */
    fun setMemberMuted(uid: String, muted: Boolean) {}

    /** This device's volume for [uid], 0 to 1. Called while the slider moves, so the change is heard at once. */
    fun setMemberVolume(uid: String, volume: Float) {}

    /** Opens the system settings where the microphone permission can be given back ([RoomUiState.micBlocked]). */
    fun openMicSettings() {}

    /** The voice message was dismissed: the host clears [RoomUiState.voiceMessage]. */
    fun dismissVoiceMessage() {}
}

/**
 * What a screen elsewhere in the app needs from the room, without knowing how rooms work. A Video watch page, a Podio station or
 * episode and the song menus show "Play in room" through this; the dock shows the room icon through it. Provided by the platform
 * host through [LocalRoomLauncher]; null where rooms are not offered.
 */
interface RoomLauncher {
    /** A room is joined on this device. */
    val inRoom: Boolean

    /** Whether this person may start something for everyone now: any member, or only the host when the host switched that on. */
    val canControl: Boolean

    /** How many people are in the room and how many chat messages are unread, for the dock icon. Zero when not in a room. */
    val memberCount: Int
    val unreadChat: Int

    /**
     * Plays [item] for everyone in the joined room (each device opens it in its own player; nothing is streamed between them).
     * With no room joined it asks to make or join one first.
     */
    fun playInRoom(item: com.cosmotune.music.data.listentogether.PartyTrack)

    /** Brings the room screen up at once, from any page. */
    fun openRoom()
}

val LocalRoomLauncher = androidx.compose.runtime.staticCompositionLocalOf<RoomLauncher?> { null }
