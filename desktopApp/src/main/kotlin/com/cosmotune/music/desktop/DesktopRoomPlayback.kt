package com.cosmotune.music.desktop

import com.cosmotune.cloud.CloudException
import com.cosmotune.cloud.CloudSignInRequired
import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartySnapshot
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomControl
import com.cosmotune.room.RoomException
import kotlin.math.abs

/**
 * The decisions behind following a room, as plain functions so they can be tested without a player or a network: what this device's
 * player should do to be where the room is, who moves the room on at the end of a song, and how the party-shaped calls the player code
 * already makes become room controls. [DesktopPartySync] applies them; nothing here touches the engine.
 */
internal object DesktopRoomPlayback {

    /** In a room the playhead is corrected sooner than on the party server: every device reads one shared clock. */
    const val DRIFT_LIMIT_MS = 800L

    /** A song counts as over this close to its end, so a device a little behind does not keep the room waiting. */
    const val END_SLACK_MS = 300L

    /** How long each member after the one whose turn it is waits before moving the room on in their place. */
    const val BACKUP_ADVANCE_MS = 4_000L

    /** What this device's player is doing, as far as following the room goes. */
    data class LocalPlayer(
        val videoId: String?,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        /** The track was tried and no source could play it here. */
        val failed: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        /** The person's own speed. Theirs to keep: a device not at 1x is not pulled back to the room's clock as it drifts. */
        val speed: Float = 1f,
    )

    // ---- what plays a room item here ----

    /** This device's two players: the music player (Podio's stations and episodes play in it too) and Video mode's. */
    enum class Player { MUSIC, VIDEO }

    /** Which player opens [track]: Video mode's for a video, the music player for a song, a station or an episode. */
    fun playerFor(track: PartyTrack): Player = if (track.kind == PartyTrack.KIND_VIDEO) Player.VIDEO else Player.MUSIC

    /**
     * Whether this device can try [track] at all. A station or an episode needs its https address (or, for an episode from YouTube
     * Music, its video id) and Podio's id, which is what tells the music player's resolver it is Podio's; an unknown kind is from a
     * newer app. Anything else is held quietly, as a song with no source here is.
     */
    fun playableHere(track: PartyTrack): Boolean = when (track.kind) {
        PartyTrack.KIND_MUSIC, PartyTrack.KIND_VIDEO -> track.videoId.isNotBlank()
        PartyTrack.KIND_RADIO -> track.videoId.startsWith(RADIO_PREFIX) && streamUrlOf(track) != null
        PartyTrack.KIND_PODCAST -> track.videoId.startsWith(EPISODE_PREFIX) &&
            (streamUrlOf(track) != null || com.cosmotune.podio.YouTubePodcasts.videoId(track.videoId) != null)
        else -> false
    }

    /** The address a station or an episode is fetched from: https with a host and nothing else, as the room's rules hold it. */
    fun streamUrlOf(track: PartyTrack): String? {
        val raw = track.url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return raw
    }

    /**
     * What the other player does while [track] is the room's: a video stops the music player when it is playing, anything else closes
     * the video the room opened in Video mode. Null when the other player is already quiet.
     */
    fun standDown(track: PartyTrack, musicPlaying: Boolean, videoHoldsRoomItem: Boolean): Player? = when (playerFor(track)) {
        Player.VIDEO -> Player.MUSIC.takeIf { musicPlaying }
        Player.MUSIC -> Player.VIDEO.takeIf { videoHoldsRoomItem }
    }

    /**
     * A play or a pause made in the Video player's own controls, which the room should hear about: the new playing state, or null.
     * Only once the player has [settled] on what this device last told it ([commandedPlaying]), so a video still starting, buffering
     * or at its end never reads as somebody pressing pause.
     */
    fun videoGesture(commandedPlaying: Boolean?, settled: Boolean, local: LocalPlayer, trackId: String): Boolean? {
        if (commandedPlaying == null || !settled || local.videoId != trackId || local.isLoading || local.failed) return null
        if (local.isPlaying == commandedPlaying) return null
        val atEnd = local.durationMs > 0 && local.positionMs >= local.durationMs - VIDEO_END_SLACK_MS
        return if (!local.isPlaying && atEnd) null else local.isPlaying
    }

    /** How close to its end a video counts as finished rather than paused. */
    const val VIDEO_END_SLACK_MS = 1_500L

    private const val RADIO_PREFIX = "podio:radio:"
    private const val EPISODE_PREFIX = "podio:"

    /** The one thing to do to the player now. */
    sealed interface Step {
        /** In step, or nothing this device can usefully do yet. */
        data object None : Step

        /** Put the room's track on. */
        data class Load(val videoId: String) : Step

        /** The room's track cannot be played here: stay quiet and rejoin when the room moves on. */
        data object Hold : Step

        /** The room is paused; [seekToMs] is where, when this device is meaningfully elsewhere. */
        data class Pause(val seekToMs: Long?) : Step

        /** The room resumes a moment from now; start then, not early. */
        data class WaitForStart(val delayMs: Long) : Step

        /** The room is playing and this device is not: start, at [seekToMs] when known. */
        data class Start(val seekToMs: Long?) : Step

        /**
         * Both are playing the same track: check the drift against [wantMs]. [correctDrift] is false while the person plays at a speed
         * of their own, so only a new control from the room moves them.
         */
        data class Follow(val wantMs: Long, val correctDrift: Boolean = true) : Step
    }

    /**
     * What to do so this device's player is where [target] says the room is. [partyPositionMs] is the shared playhead now, null while
     * nothing is playing; [loadingVideoId] is a track already asked for, so it is not asked for again. [leftPlayer] is true when the
     * person closed the player this item had opened here (they left Video mode on the room's video): they are not pulled back to it,
     * and rejoin on the next item.
     *
     * A live station has no playhead: it is tuned to and played, paused with the room and resumed with it, and never seeked.
     */
    fun step(
        target: PartyPlayback,
        local: LocalPlayer,
        partyPositionMs: Long?,
        msUntilStart: Long,
        clockSynced: Boolean,
        locallyPaused: Boolean,
        loadingVideoId: String?,
        leftPlayer: Boolean = false,
    ): Step {
        val track = target.track ?: return Step.None
        val live = track.isLive
        // Before the first clock sample the playhead is a guess, so a playing room is left alone until there is one. A station has none.
        if (target.isPlaying && !clockSynced && !live) return Step.None
        if (local.videoId != track.videoId) {
            if (leftPlayer || loadingVideoId == track.videoId) return Step.None
            // An item already over on the room's clock is not opened only to end: the room is about to move on.
            val duration = track.durationMs?.takeIf { it > 0 }
            if (!live && target.isPlaying && duration != null && partyPositionMs != null && partyPositionMs >= duration - END_SLACK_MS) {
                return Step.None
            }
            return Step.Load(track.videoId)
        }
        if (local.failed && !local.isLoading) return Step.Hold
        if (live) {
            if (!target.isPlaying) return Step.Pause(null)
            if (locallyPaused || local.isPlaying) return Step.None
            return Step.Start(null)
        }
        if (!target.isPlaying) {
            val off = abs(local.positionMs - target.positionMs) > DesktopPartySync.PAUSED_TOLERANCE_MS
            return Step.Pause(if (off) target.positionMs else null)
        }
        if (locallyPaused) return Step.None
        if (!local.isPlaying) {
            if (msUntilStart > 0) return Step.WaitForStart(msUntilStart)
            // A song that ended here a moment before the room's clock did: starting it again would only end it again.
            if (partyPositionMs != null && local.durationMs > 0 && partyPositionMs >= local.durationMs - END_SLACK_MS) return Step.None
            return Step.Start(partyPositionMs)
        }
        return if (partyPositionMs == null) Step.None else Step.Follow(partyPositionMs, correctDrift = local.speed == 1f)
    }

    /** Whose turn it is to move the room on: the host while connected, otherwise the first connected member in seat order. */
    fun advancerId(members: List<PartyMember>): String? =
        members.firstOrNull { it.isHost && it.connected }?.memberId ?: members.firstOrNull { it.connected }?.memberId

    /**
     * Whether this device should send Next because the room's song is over. Going by the shared clock rather than the local player, so
     * a member who cannot play the song still moves on at the right time. The member whose turn it is goes at once; each one after waits
     * [BACKUP_ADVANCE_MS] longer than the one before, so a departed host does not stall the room and two devices do not both skip.
     */
    fun shouldAdvance(
        selfId: String?,
        members: List<PartyMember>,
        hostOnlyControl: Boolean,
        playback: PartyPlayback,
        partyPositionMs: Long?,
        durationMs: Long?,
        lastAdvancedSeq: Long,
    ): Boolean {
        // A station has no end; the room stays on it until somebody plays something else.
        if (selfId == null || !playback.isPlaying || playback.track?.isLive != false) return false
        if (playback.seq == lastAdvancedSeq) return false
        val duration = durationMs?.takeIf { it > 0 } ?: return false
        val position = partyPositionMs ?: return false
        val over = position - duration
        if (over < -END_SLACK_MS) return false
        val first = advancerId(members)
        if (hostOnlyControl) return selfId == first && members.any { it.memberId == selfId && it.isHost }
        val order = (listOfNotNull(first) + members.filter { it.connected }.map { it.memberId }).distinct()
        val rank = order.indexOf(selfId)
        if (rank < 0) return false
        return over + END_SLACK_MS >= rank * BACKUP_ADVANCE_MS
    }

    /**
     * How a control moves the room's two sequence numbers, so the device that sent it knows when its own change has come back. Every
     * accepted control moves the playback's; all but play, pause, seek and a restart move the queue's as well.
     */
    fun seqBumps(control: RoomControl): Pair<Int, Int> = when (control) {
        is RoomControl.Play, is RoomControl.Pause, is RoomControl.Seek, RoomControl.Previous -> 1 to 0
        else -> 1 to 1
    }

    /**
     * The party's "here is the running order" as room controls. A room's SetQueue starts its track from the top, which is right for a
     * new song and wrong for an edit around the one already playing: that becomes a clear and an add, which leave the playhead alone.
     */
    fun queueControls(roomTrackId: String?, roomQueue: PartyQueue, items: List<PartyTrack>, index: Int): List<RoomControl> {
        if (items.isEmpty()) return emptyList()
        val at = index.coerceIn(0, items.lastIndex)
        if (roomTrackId == null || items[at].videoId != roomTrackId) return listOf(RoomControl.SetQueue(items, at))
        val upcoming = items.drop(at + 1)
        val roomUpcoming = roomQueue.items.drop((roomQueue.index + 1).coerceAtLeast(0))
        if (upcoming.map { it.videoId to it.fromAutoplay } == roomUpcoming.map { it.videoId to it.fromAutoplay }) return emptyList()
        return buildList {
            if (roomUpcoming.isNotEmpty()) add(RoomControl.QueueClear)
            if (upcoming.isNotEmpty()) add(RoomControl.QueueAdd(upcoming))
        }
    }

    /**
     * The room as the party player code reads it: the same state the party server sent, from [RoomClient.snapshot]. A room that ended,
     * or that this device was removed from, reads as no party at all, so the player goes back to the person's own queue.
     */
    fun mirrorOf(
        snapshot: PartySnapshot?,
        code: String?,
        connection: RoomConnection,
        selfUid: String,
        clockSynced: Boolean,
        roundTripMs: Long,
    ): DesktopListenTogether.State {
        if (code == null || connection == RoomConnection.ENDED || connection == RoomConnection.REMOVED || connection == RoomConnection.IDLE) {
            return DesktopListenTogether.State()
        }
        val live = if (connection == RoomConnection.LIVE) DesktopListenTogether.Connection.LIVE else DesktopListenTogether.Connection.CONNECTING
        if (snapshot == null) return DesktopListenTogether.State(code = code, connection = live, clockSynced = clockSynced, roundTripMs = roundTripMs)
        return DesktopListenTogether.State(
            code = snapshot.code.ifBlank { code },
            you = snapshot.members.firstOrNull { it.memberId == selfUid },
            members = snapshot.members,
            maxMembers = snapshot.maxMembers,
            hostOnlyControl = snapshot.hostOnlyControl,
            playback = snapshot.playback,
            queue = snapshot.queue,
            connection = live,
            clockSynced = clockSynced,
            roundTripMs = roundTripMs,
        )
    }

    /** Chat from others newer than the last time the chat was open. */
    fun unreadCount(chat: List<com.cosmotune.room.RoomChatMessage>, selfUid: String, seenAtMs: Long): Int =
        chat.count { it.uid != selfUid && it.atMs > seenAtMs }

    /** What every page's room icon and "Play in room" button read: see [com.cosmotune.ui.room.RoomLauncher]. */
    data class LauncherSummary(
        val inRoom: Boolean = false,
        val canControl: Boolean = false,
        val memberCount: Int = 0,
        val unreadChat: Int = 0,
    )

    /** The summary of a joined room for this person; all zero until the room has been read, and after it ended or they were removed. */
    fun launcherSummary(joined: Boolean, room: com.cosmotune.room.RoomState?, connection: RoomConnection, selfUid: String, seenAtMs: Long): LauncherSummary {
        if (!joined) return LauncherSummary()
        if (room == null || connection == RoomConnection.ENDED || connection == RoomConnection.REMOVED) return LauncherSummary(inRoom = true)
        val isHost = room.meta.hostUid == selfUid
        return LauncherSummary(
            inRoom = true,
            canControl = !room.meta.hostOnlyControl || isHost,
            memberCount = room.members.size,
            unreadChat = unreadCount(room.chat, selfUid, seenAtMs),
        )
    }

    /** What "Play in room" does from where this person stands. */
    enum class Launch {
        /** No room yet: make or join one first, and the item plays once in. */
        JOIN_FIRST,

        /** The host keeps the controls here: say so, send nothing. */
        REFUSED,

        /** Play it for everyone now. */
        PLAY,
    }

    fun launch(summary: LauncherSummary): Launch = when {
        !summary.inRoom -> Launch.JOIN_FIRST
        !summary.canControl -> Launch.REFUSED
        else -> Launch.PLAY
    }

    /** What a person reads when a room action fails: short, and saying what to do where there is something to do. */
    fun errorMessage(failure: Throwable?): String = when (failure) {
        is RoomException -> when (failure.reason) {
            RoomException.Reason.NOT_FOUND -> "No room with that code. Check it and try again."
            RoomException.Reason.FULL -> "That room is full."
            RoomException.Reason.EXPIRED -> "That room has ended."
            RoomException.Reason.DENIED -> "The room did not allow that. It may have ended."
            RoomException.Reason.NOT_IN_ROOM -> "You are not in a room."
            RoomException.Reason.HOST_ONLY -> "Only the host can control the music here."
            RoomException.Reason.OFFLINE -> "No connection. Check your internet and try again."
            RoomException.Reason.OTHER -> "Something went wrong. Try again."
        }
        is CloudSignInRequired -> "Sign in with Google again to use rooms."
        is CloudException -> "Could not sign in for rooms. Try again."
        is java.io.IOException -> "No connection. Check your internet and try again."
        is IllegalStateException -> failure.message ?: "Something went wrong. Try again."
        else -> "Something went wrong. Try again."
    }

    /** "Guest" and four characters of this install, so two guests in one room can tell themselves apart. */
    fun guestName(deviceId: String): String =
        "Guest " + deviceId.filter(Char::isLetterOrDigit).takeLast(4).uppercase().ifBlank { "1" }

    /** A song's "3:45" as milliseconds, for the duration a room's track carries. */
    fun durationMsOf(text: String?): Long? {
        val parts = text?.trim()?.split(':')?.map { it.toLongOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000L
    }
}
