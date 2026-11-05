package com.cosmotune.music.playback

import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import kotlin.math.abs

/**
 * What [PartySync] does to the player on one reconcile, as a plain function of the shared state and the player's, so the rules can
 * be tested without Media3. [PartySync] keeps everything with memory (the quiet window after a tap, the drift strikes and cooldown,
 * which control it last aligned to) and acts on the [Step] this returns; the rules themselves, and the reasons for each, are
 * documented where they are applied in [PartySync.reconcile].
 */
internal object PartyFollow {

    /** The player as a reconcile reads it. */
    data class Local(
        val mediaId: String?,
        /** Playing a file on this device, which the party cannot follow and must not yank it off. */
        val isDeviceFile: Boolean = false,
        /** Briefly silenced by the system (a chime): its playhead is frozen and reads as drift that is not there. */
        val suppressed: Boolean = false,
        val playWhenReady: Boolean,
        /** Settled and measurable; while buffering the position is where it will resume, not where it is. */
        val ready: Boolean,
        val positionMs: Long,
        /** Played to the end of the room's item, with nothing after it in this player (the next item is another player's). */
        val ended: Boolean = false,
        /** Playing at a speed of its own: the person's choice, so it keeps its own pace and is not pulled back for drifting. */
        val ownPace: Boolean = false,
    )

    /** The party as a reconcile reads it: [wantMs] is where this device should be now, from the shared clock. */
    data class Shared(
        val playback: PartyPlayback,
        val clockSynced: Boolean,
        val wantMs: Long?,
        val msUntilStart: Long = 0,
    )

    sealed interface Step {
        /** Nothing to do with this player right now. */
        data object Leave : Step

        /** The room's item is the other player's (a video while this is the music player, or the other way round): stop this one. */
        data object Elsewhere : Step

        /** This device reached the end of the room's item and has nothing after it here: the room is moved on to the next item. */
        data object Ended : Step

        /** The party is on a song this device could not play: sit it out quietly, and rejoin on the next one. */
        data object SitOut : Step

        /** Put the party's song (and its running order) on the player. */
        data object Load : Step

        /** Muted by its own listener in a locked party: follow the song and the queue, not the sound. */
        data object Muted : Step

        /** The party is paused: pause here too, and hold the playhead where the party stopped. */
        data class Hold(val seekTo: Long?) : Step

        /** The party plays but the clock has not been measured: nothing to seek to yet. */
        data object AwaitClock : Step

        /** A resume is scheduled a moment ahead: start then, not now. */
        data class WaitForStart(val ms: Long) : Step

        /** Start the player, from [seekTo] when it is known. */
        data class Start(val seekTo: Long?) : Step

        /** Playing with the party, but there is no position to compare against. */
        data object NoPosition : Step

        /** Playing but still buffering, so it cannot be measured. */
        data object Settling : Step

        /** A new control everybody lands on exactly. */
        data class Align(val seekTo: Long, val driftMs: Long) : Step

        /** Out of step by more than the drift limit; [PartySync] decides whether this reading is worth a seek. */
        data class Drift(val seekTo: Long, val driftMs: Long) : Step

        data object InStep : Step
    }

    /**
     * The step for one reconcile. [alignedSeq] is the last control this device put itself exactly on, and [anchorChanged] whether
     * the control at [Shared.playback] moved the playhead at all (a queue edit bumps the sequence without moving it). [player] is
     * which of this phone's players is asking ([RoomKinds.Player]): the music player for songs, stations and episodes, Video mode's
     * for videos.
     *
     * A live station ([com.cosmotune.music.data.listentogether.PartyTrack.isLive]) has no playhead to share: it is loaded, paused and
     * played with the room and never seeked, so everyone is simply "now".
     */
    fun decide(
        shared: Shared,
        local: Local,
        locallyPaused: Boolean = false,
        unplayableHere: Boolean = false,
        alignedSeq: Long = -1,
        anchorChanged: Boolean = true,
        driftLimitMs: Long = DRIFT_LIMIT_MS,
        pausedToleranceMs: Long = PAUSED_TOLERANCE_MS,
        alignToleranceMs: Long = ALIGN_TOLERANCE_MS,
        player: RoomKinds.Player = RoomKinds.Player.MUSIC,
    ): Step {
        val target = shared.playback
        val track = target.track ?: return Step.Leave
        if (RoomKinds.playerOf(track) != player) return Step.Elsewhere
        if (local.suppressed || local.isDeviceFile) return Step.Leave
        if (local.mediaId != RoomKinds.mediaIdOf(track)) return if (unplayableHere) Step.SitOut else Step.Load
        if (locallyPaused) return Step.Muted
        if (!target.isPlaying) {
            if (track.isLive) return Step.Hold(null)
            return Step.Hold(target.positionMs.takeIf { abs(local.positionMs - it) > pausedToleranceMs })
        }
        // Live: playing with the room is all there is to it, and no clock is needed to be "now".
        if (track.isLive) return if (local.playWhenReady) Step.InStep else Step.Start(null)
        if (local.ended) return Step.Ended
        if (!shared.clockSynced) return Step.AwaitClock
        if (!local.playWhenReady) {
            return if (shared.msUntilStart > 0) Step.WaitForStart(shared.msUntilStart) else Step.Start(shared.wantMs)
        }
        val want = shared.wantMs ?: return Step.NoPosition
        if (!local.ready) return Step.Settling
        val drift = local.positionMs - want
        if (target.seq != alignedSeq && anchorChanged && abs(drift) > alignToleranceMs) return Step.Align(want, drift)
        // At a speed of its own the playhead runs away from the room by design; each control still lands it exactly (above).
        if (local.ownPace) return Step.InStep
        return if (abs(drift) > driftLimitMs) Step.Drift(want, drift) else Step.InStep
    }

    /**
     * Whether a video stopped by the person's own hand (the watch page's pause, the notification, the app going to the background)
     * rather than by the room: the room last had it playing ([commandedPlaying]), the room still plays, the video is settled and not
     * playing, and the room's own last command is older than [graceMs] (a play takes a moment to show). Such a pause is this device's
     * own: it sits the sound out until the person plays again, and is never sent to the room.
     */
    fun pausedHere(
        commandedPlaying: Boolean,
        roomPlaying: Boolean,
        playing: Boolean,
        ready: Boolean,
        msSinceCommand: Long,
        graceMs: Long = LOCAL_PAUSE_GRACE_MS,
    ): Boolean = commandedPlaying && roomPlaying && !playing && ready && msSinceCommand >= graceMs

    /** How long after the room started a video a stopped player is still taken as starting rather than paused by hand. */
    const val LOCAL_PAUSE_GRACE_MS = 2_500L

    /**
     * Which member moves the party on when a song ends, so every device reaching the end at once does not each send the advance
     * (on the Realtime Database, the losers of that race would each skip one more song). The host while connected; otherwise the
     * first connected member in seat order, the same on every device. With host-only control nobody else may, so only the host.
     */
    fun advancer(members: List<PartyMember>, hostOnly: Boolean): String? {
        val host = members.firstOrNull { it.isHost }
        if (hostOnly) return host?.memberId
        return host?.takeIf { it.connected }?.memberId ?: members.firstOrNull { it.connected }?.memberId
    }

    /**
     * How far out of step is worth an audible seek. Above the jitter of two decoders on different hardware and at the level of a
     * real desync; measured on devices, see [PartySync] for why tightening it makes a party seek more rather than sync better.
     */
    const val DRIFT_LIMIT_MS = 1_200L

    /** While paused there is nothing to hear, so the playhead can be exact. */
    const val PAUSED_TOLERANCE_MS = 400L

    /** How exactly a device lands on a new control. */
    const val ALIGN_TOLERANCE_MS = 120L
}
