package com.cosmotune.music.desktop

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.partyQueueIndexOf
import com.cosmotune.music.data.listentogether.partyUpcomingAfter
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.QueueTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps this device's playback and the party's in step, ported from Android's `PartySync`.
 *
 * The two directions are deliberately separate, and never meet:
 *
 *  - **Outbound** is [onLocalIntent]: something the listener asked for here becomes a control the
 *    party is told about.
 *  - **Inbound** is [reconcile]: it writes to the engine directly, so nothing this class does to
 *    playback can come back round as an intent and be republished.
 *
 * Three details carry most of the weight, and each is a correction of something that reads as a
 * bug without it:
 *
 *  - **Resuming waits for the scheduled start.** The server anchors a resume slightly in the
 *    future so every device has one instant to aim at. Playing the moment the frame lands would
 *    start this device early by exactly that lead — and being *consistently* early is worse than
 *    occasionally late, because it sits under the drift limit and is never corrected.
 *  - **Drift is corrected by seeking, and only when it is real.** A seek is audible, so
 *    [DRIFT_LIMIT_MS] sits well above the jitter of two decoders running independently, and a
 *    correction needs [DRIFT_STRIKES] readings in a row before it fires.
 *  - **Position is only trusted once the clock is.** Before the first ping/pong there is no
 *    measured offset, so the track and play/pause are applied and the playhead is left alone
 *    rather than seeked to a guess.
 *
 * What Android has and this does not is audio focus: a desktop does not lose its audio to a phone
 * call, so there is no detach-and-rejoin here. If that changes the Android class is the reference.
 *
 * A Firebase room drives this the same way, through [DesktopListenTogether] (see [DesktopRooms]). Three things differ there, and
 * the decisions for them are in [DesktopRoomPlayback]: drift is corrected sooner, the song is moved on by the shared clock rather
 * than by the player that finished first, and a song with no source here is held quietly instead of retried.
 *
 * A room's item is not always a song. A station or an episode plays in the music player as Podio plays it (a station is tuned to and
 * never seeked); a video opens in Video mode's own player ([video]), started, paused and kept in step by the same rules, while the
 * music player stands down. Each device plays the item itself, at its own quality, captions and speed.
 */
internal class DesktopPartySync(
    private val scope: CoroutineScope,
    private val engine: DesktopPlaybackEngine,
    /**
     * Puts the party's track on this device, resolving a stream for it: the party's running order
     * and the index of the track within it, as the phone's `PartySync.load` takes them.
     */
    private val playTrack: (queue: List<PartyTrack>, index: Int) -> Unit,
    /** The visible desktop queue, used to publish queue edits with playback intents. */
    private val localQueue: () -> Pair<List<Song>, Int> = { emptyList<Song>() to -1 },
    /**
     * Puts the party's upcoming tracks after the one playing here, without feeding them back
     * through [onLocalIntent] — the phone's `reconcileQueue`.
     */
    private val applyPartyUpcoming: (List<PartyTrack>) -> Unit = {},
    private val applyPartyAutoplay: (Boolean) -> Unit = {},
    private val onEnteredParty: () -> Unit = {},
    private val onLeftParty: () -> Unit = {},
    /** Video mode's player, for a room's videos; null where there is none, and a video is then held like a song with no source. */
    private val video: RoomVideoPlayer? = null,
    /** True while a new room is about to be started with an item of the person's choosing, so the host does not seed it with music. */
    private val holdSeed: () -> Boolean = { false },
) {

    private val jobs = mutableListOf<Job>()
    private var startJob: Job? = null
    private var publishJob: Job? = null

    private var loadingVideoId: String? = null
    private var alignedSeq = -1L
    private var driftStrikes = 0
    private var driftCooldownUntilMs = 0L
    /**
     * The seqs the party reaches once every control this device last sent has come back round.
     * Until then a reconcile would undo the very thing the listener just did; past them the quiet
     * window is over, however long it was meant to last.
     */
    private var awaitPlaybackSeq = Long.MAX_VALUE
    private var awaitQueueSeq = Long.MAX_VALUE
    private var locallyPaused = false
    private var lastPartyCode: String? = null
    private var appliedAutoplay: Boolean? = null
    /** A track selection whose decoder is still resolving. */
    private var pendingIntentVideoId: String? = null
    /** The room's playback seq this device last moved on from at the end of a song, so it sends Next once. */
    private var advancedSeq = -1L
    /** A shared track no source here could play, already announced. */
    private var heldVideoId: String? = null
    /** The room video this device last opened in Video mode, so the music item after it closes it and a closed one is not reopened. */
    private var openedVideoId: String? = null
    /** Whether this device last told the Video player to play or to pause, and whether the player has since done so. */
    private var videoCommanded: Boolean? = null
    private var videoSettled = false

    /**
     * Until when inbound reconciliation is held off.
     *
     * A control this device issued takes a moment to come back as a state frame; without a quiet
     * window the device that *issued* a seek would immediately fight its own echo.
     */
    private var quietUntilMs = 0L

    fun start() {
        stop()
        jobs += scope.launch {
            DesktopListenTogether.state
                .map { Triple(it.playback.seq, it.queue.seq, it.code) }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.third != lastPartyCode) {
                        val wasInParty = lastPartyCode != null
                        val nowInParty = key.third != null
                        lastPartyCode = key.third
                        if (!wasInParty && nowInParty) onEnteredParty()
                        if (wasInParty && !nowInParty) onLeftParty()
                    }
                    // Every control sent has come back, so the party now describes what the
                    // listener did — or somebody else has moved it on past that, which is as good
                    // a reason to stop holding reconcile off.
                    if (pendingIntentVideoId == null &&
                        key.first >= awaitPlaybackSeq &&
                        key.second >= awaitQueueSeq
                    ) {
                        quietUntilMs = 0L
                    }
                    reconcile()
                }
        }
        // A steady tick as well as the frames: drift accumulates between controls, and nothing
        // arrives to announce it.
        jobs += scope.launch {
            while (true) {
                delay(RECONCILE_INTERVAL_MS)
                reconcile()
            }
        }
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        startJob?.cancel()
        startJob = null
        publishJob?.cancel()
        publishJob = null
        loadingVideoId = null
        alignedSeq = -1L
        driftStrikes = 0
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        locallyPaused = false
        lastPartyCode = null
        appliedAutoplay = null
        pendingIntentVideoId = null
        advancedSeq = -1L
        heldVideoId = null
        openedVideoId = null
        videoCommanded = null
        videoSettled = false
    }

    /** Something the listener asked for here, which the party should be told about. */
    fun onLocalIntent(expectedVideoId: String? = null) {
        val party = DesktopListenTogether.state.value
        if (!party.inParty || party.controlsLocked) return
        // Queue/transport gestures can arrive while a preceding song selection is still loading.
        // Keep waiting for that selection rather than letting the later gesture cancel its guard.
        val waitForVideoId = expectedVideoId
            ?: engine.state.value.takeIf { it.isLoading }?.song?.videoId
        pendingIntentVideoId = waitForVideoId
        // Nothing has gone out yet, so there is nothing to wait for and somebody else's control
        // must not end the window protecting this one.
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        // A song selection first updates the visible state, then resolves a stream on a worker.
        // Keep reconciliation out of the way until that new track is actually playable; otherwise
        // the old party state can arrive during the resolve and pause/replace the new selection.
        quietUntilMs = if (waitForVideoId != null) Long.MAX_VALUE else nowMs() + INTENT_QUIET_MS
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            if (waitForVideoId != null) {
                repeat(PUBLISH_WAIT_ATTEMPTS) {
                    val playback = engine.state.value
                    if (playback.song?.videoId == waitForVideoId && !playback.isLoading) {
                        pendingIntentVideoId = null
                        quietUntilMs = nowMs() + INTENT_QUIET_MS
                        publish()
                        return@launch
                    }
                    delay(PUBLISH_WAIT_STEP_MS)
                }
                // The requested stream failed or was superseded. Do not publish the stale player
                // state; the regular party reconciliation will recover it.
                pendingIntentVideoId = null
                quietUntilMs = nowMs()
                return@launch
            }
            publish()
        }
    }

    /** In a host-locked party, play/pause affects only this computer. */
    fun handleLockedPlayPause(): Boolean {
        val party = DesktopListenTogether.state.value
        if (!party.controlsLocked) return false
        if (engine.state.value.isPlaying) {
            locallyPaused = true
            engine.pause()
        } else {
            locallyPaused = false
            DesktopListenTogether.partyPositionMs()?.let(engine::seekTo)
            engine.play()
        }
        return true
    }

    /** Puts this device where the party is, writing to the engine rather than through intents. */
    fun reconcile() {
        val party = DesktopListenTogether.state.value
        if (!party.inParty) {
            loadingVideoId = null
            locallyPaused = false
            appliedAutoplay = null
            // A video the room opened keeps playing after leaving; it is simply the person's own from now on.
            openedVideoId = null
            videoCommanded = null
            return
        }
        if (!party.controlsLocked) locallyPaused = false
        if (nowMs() < quietUntilMs) return
        val target = party.playback
        if (target.autoplayEnabled != appliedAutoplay) {
            appliedAutoplay = target.autoplayEnabled
            applyPartyAutoplay(target.autoplayEnabled)
        }
        val track = target.track
        if (track == null) {
            // A newly created party is empty. Its host seeds it with the music already playing;
            // listeners never race the host for that first state. Held quiet like a local intent,
            // so the next tick does not seed it a second time before the first has come back.
            // A room made from "Play in room" starts with that item, which is on its way.
            if (party.you?.isHost == true && party.connection == DesktopListenTogether.Connection.LIVE && !holdSeed()) {
                quietUntilMs = nowMs() + INTENT_QUIET_MS
                publish()
            }
            return
        }
        val playback = engine.state.value
        val inRoom = DesktopListenTogether.inRoom
        val want = DesktopListenTogether.partyPositionMs()
        val player = DesktopRoomPlayback.playerFor(track)
        val videoLocal = video?.local()

        // A room moves on by its shared clock, not by whichever player happened to finish first:
        // a member who cannot play this song still moves on with everyone else.
        if (inRoom) {
            val duration = track.durationMs ?: when (player) {
                DesktopRoomPlayback.Player.MUSIC -> playback.durationMs.takeIf { playback.song?.videoId == track.videoId }
                DesktopRoomPlayback.Player.VIDEO -> videoLocal?.takeIf { it.videoId == track.videoId }?.durationMs
            }
            // Unclamped: how far past the end the room's clock is decides who steps in.
            val overall = DesktopListenTogether.roomPositionMs()
            if (DesktopRoomPlayback.shouldAdvance(party.you?.memberId, party.members, party.hostOnlyControl, target, overall, duration, advancedSeq)) {
                advancedSeq = target.seq
                DesktopTrackLog.log("room: the song is over on the shared clock; moving the room on")
                DesktopListenTogether.next()
                return
            }
        }

        // An item this device cannot open at all (a link that is not https, a kind from a newer app, no Video player): quiet here,
        // and the next item is picked up on the shared clock.
        if (!DesktopRoomPlayback.playableHere(track) || (player == DesktopRoomPlayback.Player.VIDEO && video == null)) {
            if (playback.isPlaying && playback.song?.roomShareable() != false) engine.pause()
            if (videoLocal?.videoId != null && videoLocal.videoId == openedVideoId) closeRoomVideo()
            hold(track)
            return
        }
        // One item plays at a time: the player that is not this item's stands down.
        when (DesktopRoomPlayback.standDown(track, playback.isPlaying, videoLocal?.videoId != null && videoLocal.videoId == openedVideoId)) {
            DesktopRoomPlayback.Player.MUSIC -> engine.pause()
            DesktopRoomPlayback.Player.VIDEO -> closeRoomVideo()
            null -> Unit
        }
        if (player == DesktopRoomPlayback.Player.VIDEO) {
            reconcileVideo(party, track, videoLocal ?: return, want, inRoom)
            return
        }

        // A local file is this device's own business; the party has no copy of it to agree on.
        if (playback.song?.roomShareable() == false) return

        val step = DesktopRoomPlayback.step(
            target = target,
            local = DesktopRoomPlayback.LocalPlayer(
                videoId = playback.song?.videoId,
                isPlaying = playback.isPlaying,
                isLoading = playback.isLoading,
                failed = playback.error != null,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                speed = engine.playbackRate,
            ),
            partyPositionMs = want,
            msUntilStart = DesktopListenTogether.msUntilStart(),
            clockSynced = party.clockSynced,
            locallyPaused = locallyPaused,
            loadingVideoId = loadingVideoId,
        )
        if (step is DesktopRoomPlayback.Step.Load) {
            loadingVideoId = track.videoId
            // The queue is only usable if the track is actually in it. It may not be: the
            // queue and the track are two controls, and between them the party holds a new
            // running order with the old song still current. Falling back to the track alone
            // is always right; falling back to position zero never is.
            val at = partyQueueIndexOf(party.queue, target, track.videoId)
            if (at >= 0) playTrack(party.queue.items, at) else playTrack(listOf(track), 0)
            return
        }
        if (playback.song?.videoId != track.videoId) return
        loadingVideoId = null
        reconcileQueue(party)

        when (step) {
            is DesktopRoomPlayback.Step.Hold -> {
                // No source here has this song. It is skipped quietly: nothing is sent, nothing
                // retried, and the next song of the party is picked up on the shared clock.
                hold(track)
                return
            }
            is DesktopRoomPlayback.Step.Pause -> {
                locallyPaused = false
                if (playback.isPlaying) engine.pause()
                step.seekToMs?.let(engine::seekTo)
                return
            }
            is DesktopRoomPlayback.Step.WaitForStart -> {
                startJob?.cancel()
                startJob = scope.launch {
                    delay(step.delayMs)
                    reconcile()
                }
                return
            }
            is DesktopRoomPlayback.Step.Start -> {
                step.seekToMs?.let(engine::seekTo)
                startJob?.cancel()
                engine.play()
                return
            }
            is DesktopRoomPlayback.Step.Follow -> Unit
            else -> return
        }

        follow(step, playback.positionMs, target.seq, inRoom, engine::seekTo)
    }

    /** The drift rule, for whichever player holds the item: a new control aligns at once, ordinary drift only when it is real. */
    private fun follow(step: DesktopRoomPlayback.Step.Follow, positionMs: Long, controlSeq: Long, inRoom: Boolean, seekTo: (Long) -> Unit) {
        val drift = positionMs - step.wantMs
        val now = nowMs()
        val decision = decideSeek(
            drift = drift,
            controlSeq = controlSeq,
            alignedSeq = alignedSeq,
            strikes = driftStrikes,
            nowMs = now,
            cooldownUntilMs = driftCooldownUntilMs,
            limitMs = if (inRoom) DesktopRoomPlayback.DRIFT_LIMIT_MS else DRIFT_LIMIT_MS,
            correctDrift = step.correctDrift,
        )
        alignedSeq = decision.alignedSeq
        driftStrikes = decision.strikes
        if (decision.cooldownUntilMs > 0) driftCooldownUntilMs = decision.cooldownUntilMs
        if (decision.seek) {
            DesktopTrackLog.log("party: ${decision.reason} ${drift}ms")
            seekTo(step.wantMs)
        }
    }

    /**
     * The room's video, in Video mode's player: opened, started, paused and kept in step as a song is, and a play or a pause made in
     * that player's own controls is told to the room as a gesture of this device (or, in a host-only room, kept to this device).
     */
    private fun reconcileVideo(
        party: DesktopListenTogether.State,
        track: PartyTrack,
        local: DesktopRoomPlayback.LocalPlayer,
        want: Long?,
        inRoom: Boolean,
    ) {
        val player = video ?: return
        val target = party.playback
        if (local.videoId == track.videoId) loadingVideoId = null
        DesktopRoomPlayback.videoGesture(videoCommanded, videoSettled, local, track.videoId)?.let { playing ->
            videoGesture(party, playing, local.positionMs)
            return
        }
        if (local.videoId == track.videoId && !local.isLoading && local.isPlaying == videoCommanded) videoSettled = true
        val step = DesktopRoomPlayback.step(
            target = target,
            local = local,
            partyPositionMs = want,
            msUntilStart = DesktopListenTogether.msUntilStart(),
            clockSynced = party.clockSynced,
            locallyPaused = locallyPaused,
            loadingVideoId = loadingVideoId,
            leftPlayer = openedVideoId == track.videoId && local.videoId != track.videoId && !player.shown(),
        )
        when (step) {
            is DesktopRoomPlayback.Step.Load -> {
                loadingVideoId = track.videoId
                if (player.open(track, want ?: target.positionMs)) {
                    openedVideoId = track.videoId
                    // A new video: whatever the last one settled on says nothing about this one.
                    videoCommanded = target.isPlaying
                    videoSettled = false
                } else {
                    hold(track)
                }
            }
            is DesktopRoomPlayback.Step.Hold -> hold(track)
            is DesktopRoomPlayback.Step.Pause -> {
                locallyPaused = false
                if (local.isPlaying) player.pause()
                step.seekToMs?.let(player::seekTo)
                commandVideo(false)
            }
            is DesktopRoomPlayback.Step.WaitForStart -> {
                startJob?.cancel()
                startJob = scope.launch {
                    delay(step.delayMs)
                    reconcile()
                }
            }
            is DesktopRoomPlayback.Step.Start -> {
                step.seekToMs?.let(player::seekTo)
                startJob?.cancel()
                player.play()
                commandVideo(true)
            }
            is DesktopRoomPlayback.Step.Follow -> follow(step, local.positionMs, target.seq, inRoom, player::seekTo)
            DesktopRoomPlayback.Step.None -> Unit
        }
    }

    /** What this device told the Video player; a change starts a new wait for the player to settle on it. */
    private fun commandVideo(playing: Boolean) {
        if (videoCommanded == playing) return
        videoCommanded = playing
        videoSettled = false
    }

    /** A play or a pause pressed in the Video player: the room's, unless the host keeps the controls, when it is this device's alone. */
    private fun videoGesture(party: DesktopListenTogether.State, playing: Boolean, positionMs: Long) {
        videoCommanded = playing
        if (party.controlsLocked) {
            locallyPaused = !playing
            return
        }
        locallyPaused = false
        val basePlayback = party.playback.seq
        val before = DesktopListenTogether.roomBumps()
        if (playing) DesktopListenTogether.play(positionMs) else DesktopListenTogether.pause(positionMs)
        val sent = if (DesktopListenTogether.inRoom) DesktopListenTogether.roomBumps().first - before.first else 1
        awaitPlaybackSeq = basePlayback + sent
        awaitQueueSeq = party.queue.seq
        quietUntilMs = nowMs() + INTENT_QUIET_MS
        DesktopTrackLog.log("room: ${if (playing) "play" else "pause"} from the Video player at ${positionMs}ms")
    }

    /** Closes the video the room opened here, now that the room plays something else. */
    private fun closeRoomVideo() {
        video?.stop()
        openedVideoId = null
        videoCommanded = null
        videoSettled = false
    }

    /** An item this device cannot play: said once, then left until the room moves on. */
    private fun hold(track: PartyTrack) {
        if (heldVideoId == track.videoId) return
        heldVideoId = track.videoId
        DesktopTrackLog.log("party: '${track.title}' (${track.kind}) cannot be played here; waiting for the next item")
        DesktopPlayerHost.showMessage(
            when (track.kind) {
                PartyTrack.KIND_VIDEO -> "This video can't be played here. You'll rejoin on the next item."
                PartyTrack.KIND_RADIO -> "This station can't be played here. You'll rejoin on the next item."
                PartyTrack.KIND_PODCAST -> "This episode can't be played here. You'll rejoin on the next item."
                else -> "This song can't be played here. You'll rejoin on the next one."
            },
        )
    }

    /**
     * Brings what plays after this track into line with the party's — every tick, not just when
     * the queue's seq moves, so a local edit the party never took is put right rather than lived
     * with until the next track. Only while the track playing here is in the party's queue; a new
     * empty party keeps the host's queue, which [publish] is about to seed it with.
     */
    private fun reconcileQueue(party: DesktopListenTogether.State) {
        if (party.queue.items.isEmpty()) return
        val (songs, index) = localQueue()
        val currentId = songs.getOrNull(index)?.videoId ?: return
        if (partyQueueIndexOf(party.queue, party.playback, currentId) < 0) return
        applyPartyUpcoming(
            partyUpcomingAfter(party.queue, party.playback, currentId).take(MAX_PARTY_UPCOMING_QUEUE),
        )
    }

    /** Tells the party what this device just did. */
    private fun publish() {
        val party = DesktopListenTogether.state.value
        if (!party.inParty) return
        val playback = engine.state.value
        // Unlike ExoPlayer, the desktop state cannot represent "play when ready" while a stream
        // is still resolving: it temporarily reads as paused. Wait for the decoder so creating a
        // party during a load cannot seed a false pause or an incomplete current track.
        if (playback.isLoading) return
        val song = playback.song ?: return
        if (!song.roomShareable()) return
        val track = song.toPartyTrack(playback.durationMs)
        val position = playback.positionMs.coerceAtLeast(0L)
        val basePlayback = party.playback.seq
        val baseQueue = party.queue.seq
        var playbackControls = 0
        var queueControls = 0
        val roomBumpsBefore = DesktopListenTogether.roomBumps()

        val (songs, index) = localQueue()
        val (shareable, shareIndex) = queueForPartyPublish(
            songs = songs,
            currentIndex = index,
            currentVideoId = song.videoId,
            currentDurationMs = playback.durationMs,
        )
        // Compared from the playing track on, by the tracks and their sections. Not by the party
        // queue's index, which only moves when the queue is resent and so read as different after
        // every ordinary track change; and not over history, which each device trims to its own
        // length, so two devices would otherwise resend their own pasts at each other on every
        // pause.
        val partyAt = partyQueueIndexOf(party.queue, party.playback, song.videoId)
        if (partyAt < 0 || shareIndex < 0 ||
            shareable.drop(shareIndex).map { it.videoId to it.fromAutoplay } !=
            party.queue.items.drop(partyAt).map { it.videoId to it.fromAutoplay }
        ) {
            DesktopListenTogether.setQueue(shareable, shareIndex)
            queueControls++
        }

        // After the queue, never before: a track change that arrives pointing into a running order
        // nobody has yet is the bug that played the wrong song.
        when {
            party.playback.track?.videoId != track.videoId -> {
                DesktopListenTogether.setTrack(track, position, playback.isPlaying)
                playbackControls++
            }
            playback.isPlaying && !party.playback.isPlaying -> {
                DesktopListenTogether.play(position)
                playbackControls++
            }
            !playback.isPlaying && party.playback.isPlaying -> {
                DesktopListenTogether.pause(position)
                playbackControls++
            }
            abs(position - (DesktopListenTogether.partyPositionMs() ?: position)) > SEEK_REPORT_FLOOR_MS -> {
                DesktopListenTogether.seek(position)
                playbackControls++
            }
        }
        if (DesktopListenTogether.inRoom) {
            // A room counts its sequences differently (every control moves the playback's, and a
            // queue edit may be two controls), so the seqs to wait for are what was actually sent.
            val after = DesktopListenTogether.roomBumps()
            playbackControls = after.first - roomBumpsBefore.first
            queueControls = after.second - roomBumpsBefore.second
            awaitPlaybackSeq = basePlayback + playbackControls
            awaitQueueSeq = baseQueue + queueControls
        } else {
            awaitPlaybackSeq = basePlayback + playbackControls
            awaitQueueSeq = baseQueue + queueControls
        }
        if (playbackControls == 0 && queueControls == 0) quietUntilMs = 0L
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** What [reconcile] decided about the playhead, and the counters it leaves behind. */
    internal data class SeekDecision(
        val seek: Boolean,
        val alignedSeq: Long,
        val strikes: Int,
        val cooldownUntilMs: Long = 0,
        val reason: String = "",
    )

    internal companion object {

        /**
         * Whether the playhead should be moved, given how far out it is.
         *
         * Pure so the rules can be stated rather than inferred: a new control aligns at once and
         * tightly, ordinary drift has to be both large and persistent, and a correction that has
         * just fired will not fire again until the cooldown is out. With [correctDrift] false (the person plays at a speed of their
         * own) only the first of those applies.
         */
        fun decideSeek(
            drift: Long,
            controlSeq: Long,
            alignedSeq: Long,
            strikes: Int,
            nowMs: Long,
            cooldownUntilMs: Long,
            limitMs: Long = DRIFT_LIMIT_MS,
            correctDrift: Boolean = true,
        ): SeekDecision {
            // A deliberate jump by whoever holds the party: align to it now.
            if (controlSeq != alignedSeq) {
                return SeekDecision(
                    seek = abs(drift) > ALIGN_TOLERANCE_MS,
                    alignedSeq = controlSeq,
                    strikes = 0,
                    reason = "aligning onto control $controlSeq",
                )
            }
            if (!correctDrift || abs(drift) <= limitMs) {
                return SeekDecision(seek = false, alignedSeq = alignedSeq, strikes = 0)
            }
            if (nowMs < cooldownUntilMs) {
                return SeekDecision(seek = false, alignedSeq = alignedSeq, strikes = strikes)
            }
            val next = strikes + 1
            if (next < DRIFT_STRIKES) {
                return SeekDecision(seek = false, alignedSeq = alignedSeq, strikes = next)
            }
            return SeekDecision(
                seek = true,
                alignedSeq = alignedSeq,
                strikes = 0,
                cooldownUntilMs = nowMs + DRIFT_COOLDOWN_MS,
                reason = "correcting",
            )
        }
        /** Well above two decoders' jitter, and at the level of an actual desync. */
        const val DRIFT_LIMIT_MS = 1_200L

        /** Readings in a row before a correction fires, so one stall does not cause a seek. */
        const val DRIFT_STRIKES = 2
        const val DRIFT_COOLDOWN_MS = 6_000L
        const val PAUSED_TOLERANCE_MS = 400L
        const val ALIGN_TOLERANCE_MS = 120L
        const val SEEK_REPORT_FLOOR_MS = 1_000L
        /**
         * The longest a local action is protected from being reconciled away. Normally it ends
         * sooner, when the controls it sent come back; this covers an echo that never does.
         */
        const val INTENT_QUIET_MS = 4_500L
        const val PUBLISH_DEBOUNCE_MS = 120L
        const val PUBLISH_WAIT_STEP_MS = 100L
        const val PUBLISH_WAIT_ATTEMPTS = 100
        const val RECONCILE_INTERVAL_MS = 700L
        const val MAX_PARTY_UPCOMING_QUEUE = 25

        /** The same queue projection Android publishes to a party. */
        internal fun queueForPartyPublish(
            songs: List<Song>,
            currentIndex: Int,
            currentVideoId: String,
            currentDurationMs: Long,
        ): Pair<List<PartyTrack>, Int> {
            val raw = songs.withIndex()
                .filter { (_, song) -> song.roomShareable() }
            val rawCurrent = raw.indexOfFirst { (originalIndex, song) ->
                originalIndex == currentIndex && song.videoId == currentVideoId
            }.takeIf { it >= 0 } ?: raw.indexOfFirst { it.value.videoId == currentVideoId }

            val withoutContextTail = if (rawCurrent >= 0) {
                raw.take(rawCurrent + 1) + raw.drop(rawCurrent + 1)
                    .filter { it.value.queueTier != QueueTier.CONTEXT }
            } else {
                raw.filter { it.value.queueTier != QueueTier.CONTEXT }
            }
            val projectedCurrent = withoutContextTail.indexOfFirst { (originalIndex, song) ->
                originalIndex == currentIndex && song.videoId == currentVideoId
            }.takeIf { it >= 0 }
                ?: withoutContextTail.indexOfFirst { it.value.videoId == currentVideoId }
            val endExclusive = if (projectedCurrent >= 0) {
                (projectedCurrent + 1 + MAX_PARTY_UPCOMING_QUEUE)
                    .coerceAtMost(withoutContextTail.size)
            } else {
                (1 + MAX_PARTY_UPCOMING_QUEUE).coerceAtMost(withoutContextTail.size)
            }
            val tracks = withoutContextTail.take(endExclusive).map { (_, queued) ->
                queued.toPartyTrack(
                    if (queued.videoId == currentVideoId) currentDurationMs else 0L,
                )
            }
            return tracks to tracks.indexOfFirst { it.videoId == currentVideoId }
        }
    }
}

/**
 * This device's row as the party knows it — identity and artwork, not how the audio was got. A Podio station or episode playing in
 * the music player goes as one, with the https address every device fetches it from; a station has no length to share.
 */
internal fun Song.toPartyTrack(durationMs: Long): PartyTrack {
    val kind = when {
        videoId.startsWith("podio:radio:") -> PartyTrack.KIND_RADIO
        videoId.startsWith("podio:") -> PartyTrack.KIND_PODCAST
        else -> PartyTrack.KIND_MUSIC
    }
    val track = PartyTrack(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = durationMs.takeIf { it > 0 && kind != PartyTrack.KIND_RADIO },
        fromAutoplay = queueTier == QueueTier.AUTOPLAY,
        kind = kind,
        url = localUri.takeIf { kind != PartyTrack.KIND_MUSIC },
    )
    return if (track.url == null) track else track.copy(url = DesktopRoomPlayback.streamUrlOf(track))
}

/**
 * Whether this row can be shared with a party at all: not a file on this computer, which nobody else has. A Podio station or episode
 * carries its stream's address where a file would be, and is shareable when that address is https.
 */
internal fun Song.roomShareable(): Boolean {
    if (localPath != null) return false
    val uri = localUri ?: return true
    return videoId.startsWith("podio:") && DesktopRoomPlayback.streamUrlOf(PartyTrack(videoId, url = uri)) != null
}

/**
 * A shared queue item as a desktop-playable catalogue song, in the section the phone puts it in:
 * AutoPlay's, or the listener's own queue. A station or an episode becomes the row Podio itself
 * plays (its stream as the address the resolver fetches); a video is marked as one.
 */
internal fun PartyTrack.toDesktopSong(): Song {
    val podio = kind == PartyTrack.KIND_RADIO || kind == PartyTrack.KIND_PODCAST
    return Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        // Not cosmetic: a cross-source match is made on it, as on the phone.
        durationText = durationMs?.takeUnless { isLive }?.let { ms ->
            val total = ms / 1000
            "%d:%02d".format(total / 60, total % 60)
        },
        queueTier = if (fromAutoplay) QueueTier.AUTOPLAY else QueueTier.USER_QUEUE,
        localUri = if (podio) DesktopRoomPlayback.streamUrlOf(this) else null,
        albumName = when (kind) {
            PartyTrack.KIND_RADIO -> "Podio · Live radio"
            PartyTrack.KIND_PODCAST -> "Podio · Podcast"
            else -> null
        },
        isVideo = kind == PartyTrack.KIND_VIDEO,
    )
}
