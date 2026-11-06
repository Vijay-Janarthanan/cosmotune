package com.cosmotune.music.watch

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.playback.PartyFollow
import com.cosmotune.music.playback.RoomKinds
import com.cosmotune.music.playback.RoomNotices
import com.cosmotune.video.graph.androidVideoGraph
import com.cosmotune.video.player.VideoScale
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.WatchRoom
import com.cosmotune.watch.player.EngineWatchPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The room's Watch items on the phone: a film, an episode or a channel each member finds in their own Watch plugins (the
 * host's release ranked first, the room's public stream when this phone has none) and plays on Video mode's Media3 engine,
 * kept in step by the same rules the music and video followers use ([PartyFollow.decide] with [RoomKinds.Player.WATCH]).
 * Nothing is streamed between devices.
 */
object RoomWatch {
    private const val TAG = "RoomWatch"
    private const val TICK_MS = 700L
    private const val LOAD_RETRY_MS = 10_000L
    private const val DRIFT_STRIKES = 2
    private const val DRIFT_COOLDOWN_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var app: Context? = null
    private var started = false
    private var startJob: Job? = null

    private var roomItem: String? = null
    private var loadingId: String? = null
    private var loadingAtMs = 0L
    private var commandedPlaying = false
    private var commandAtMs = 0L
    private var locallyPaused = false
    private var alignedSeq = -1L
    private var lastAnchor: Triple<Long, Long, Boolean>? = null
    private var driftStrikes = 0
    private var driftCooldownUntilMs = 0L
    private var advancedSeq = -1L
    private var announcedSkip: String? = null
    private val unplayable = LinkedHashSet<String>()

    private val player: EngineWatchPlayer? get() = app?.let { runCatching { AndroidWatch.player(it) }.getOrNull() }

    /** Follows the room from now on (once per process). */
    fun follow(context: Context) {
        app = context.applicationContext
        if (started) return
        started = true
        scope.launch {
            ListenTogether.state.map { Triple(it.playback.seq, it.code, it.clockSynced) }.distinctUntilChanged().collect { reconcile() }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                reconcile()
            }
        }
    }

    /** The room screen's picture while this phone plays the room's Watch item; null draws the artwork. */
    fun media(track: PartyTrack?): (@Composable () -> Unit)? {
        val context = app ?: return null
        if (track?.kind != PartyTrack.KIND_WATCH) return null
        if (player?.state?.value?.target?.key?.value != track.videoId) return null
        val surface = androidVideoGraph(context).surface ?: return null
        return { surface.Surface(Modifier.fillMaxSize(), VideoScale.FIT) }
    }

    private fun reconcile() {
        val party = ListenTogether.state.value
        val p = player
        if (!party.inParty || p == null) { if (!party.inParty) reset(); return }
        val target = party.playback
        val track = target.track ?: return
        val state = p.state.value
        val snapshot = p.engine.snapshot.value
        val loadedId = state.target?.key?.value
        val now = SystemClock.elapsedRealtime()

        if (track.videoId != roomItem) {
            roomItem = track.videoId
            locallyPaused = false
            loadingId = null
            alignedSeq = -1L
            announcedSkip = null
        }
        if (RoomKinds.playerOf(track) != RoomKinds.Player.WATCH) {
            // The room moved on to something another player plays: the room's Watch item stops here.
            if (roomItemOpened != null && loadedId == roomItemOpened) {
                Log.i(TAG, "the room moved on from its Watch item; stopping it here")
                p.roomDurationMs = null
                p.stop()
                roomItemOpened = null
                closePage()
            }
            commandedPlaying = false
            return
        }
        // Already playing here ("Watch together" from this phone's player): the room's item from now on.
        if (loadedId == track.videoId) roomItemOpened = track.videoId
        if (loadedId == track.videoId && (state.status == WatchPlayerStatus.NOT_FOUND || state.status == WatchPlayerStatus.ERROR)) unplayable += track.videoId
        if (loadedId == track.videoId && state.status == WatchPlayerStatus.ENDED && target.isPlaying && advancedSeq != target.seq && !party.controlsLocked) {
            advancedSeq = target.seq
            ListenTogether.nextAfter(target.seq)
            return
        }
        val playing = snapshot.playing
        val ready = state.status == WatchPlayerStatus.PLAYING || state.status == WatchPlayerStatus.PAUSED
        if (loadedId == track.videoId) {
            if (locallyPaused && playing) {
                locallyPaused = false
                alignedSeq = -1L
                commandedPlaying = true
            } else if (PartyFollow.pausedHere(commandedPlaying, target.isPlaying, playing, ready, now - commandAtMs)) {
                locallyPaused = true
            }
        }
        val anchor = Triple(target.anchorMs, target.positionMs, target.isPlaying)
        val step = PartyFollow.decide(
            shared = PartyFollow.Shared(target, party.clockSynced,
                if (party.clockSynced && target.isPlaying) ListenTogether.partyPositionMs() else null, ListenTogether.msUntilStart()),
            local = PartyFollow.Local(mediaId = loadedId, playWhenReady = playing, ready = ready, positionMs = p.engine.positionMs(),
                ownPace = snapshot.speed != 1f),
            locallyPaused = locallyPaused,
            unplayableHere = !RoomKinds.playableHere(track, true) || track.videoId in unplayable,
            alignedSeq = alignedSeq,
            anchorChanged = anchor != lastAnchor,
            player = RoomKinds.Player.WATCH,
        )
        when (step) {
            PartyFollow.Step.Leave, PartyFollow.Step.Elsewhere, PartyFollow.Step.Ended, PartyFollow.Step.Muted,
            PartyFollow.Step.AwaitClock, PartyFollow.Step.NoPosition -> Unit
            PartyFollow.Step.SitOut -> {
                if (loadedId == track.videoId && playing) p.engine.pause()
                if (announcedSkip != track.videoId) { announcedSkip = track.videoId; RoomNotices.skipped(track) }
            }
            PartyFollow.Step.Load -> load(p, track, now)
            is PartyFollow.Step.Hold -> {
                if (playing) p.engine.pause()
                commandedPlaying = false
                step.seekTo?.let { p.engine.seekTo(it) }
            }
            is PartyFollow.Step.WaitForStart -> {
                startJob?.cancel()
                startJob = scope.launch { delay(step.ms); reconcile() }
            }
            is PartyFollow.Step.Start -> {
                step.seekTo?.let { p.engine.seekTo(it) }
                if (!playing) p.engine.play()
                commandedPlaying = true
                commandAtMs = now
                alignedSeq = target.seq
                lastAnchor = anchor
            }
            PartyFollow.Step.Settling -> driftStrikes = 0
            is PartyFollow.Step.Align, is PartyFollow.Step.Drift, PartyFollow.Step.InStep -> {
                if (target.seq != alignedSeq) { alignedSeq = target.seq; lastAnchor = anchor }
                when (step) {
                    is PartyFollow.Step.Align -> p.engine.seekTo(step.seekTo)
                    is PartyFollow.Step.Drift -> if (now >= driftCooldownUntilMs && ++driftStrikes >= DRIFT_STRIKES) {
                        driftStrikes = 0
                        driftCooldownUntilMs = now + DRIFT_COOLDOWN_MS
                        p.engine.seekTo(step.seekTo)
                    }
                    else -> driftStrikes = 0
                }
            }
        }
    }

    @Volatile private var roomItemOpened: String? = null

    /** Whether the room opened [key] in Watch's player here, or plays it now (the player page then leaves it to the room). */
    fun holds(key: String): Boolean = roomItemOpened == key ||
        ListenTogether.state.value.let { it.inParty && it.playback.track?.let { t -> t.kind == PartyTrack.KIND_WATCH && t.videoId == key } == true }

    /**
     * A Watch item the room just opened on this phone, for [com.cosmotune.video.host.PhoneModeHost]: it makes Watch the mode and
     * brings up the item's player page, attached to what plays; the host clears it.
     */
    val opening = kotlinx.coroutines.flow.MutableStateFlow<Pair<com.cosmotune.watch.model.PlayTarget, com.cosmotune.watch.model.TitleSummary?>?>(null)

    /** The room's player page goes when the room's Watch item stops here. */
    private fun closePage() {
        com.cosmotune.ui.watch.WatchNav.stack.value = com.cosmotune.ui.watch.WatchNav.stack.value.filterNot { it is com.cosmotune.ui.watch.WatchRoute.Play && it.attached }
    }

    private fun load(p: EngineWatchPlayer, track: PartyTrack, now: Long) {
        if (loadingId == track.videoId && now - loadingAtMs < LOAD_RETRY_MS) return
        loadingId = track.videoId
        loadingAtMs = now
        roomItemOpened = track.videoId
        p.roomDurationMs = track.watch?.durationMs ?: track.durationMs
        val start = if (track.isLive) 0L else ListenTogether.partyPositionMs()?.coerceAtLeast(0L) ?: 0L
        commandedPlaying = true
        commandAtMs = now
        scope.launch {
            val context = app ?: return@launch
            val target = WatchRoom.targetOf(track, AndroidWatch.repository(context)) ?: return@launch
            val summary = WatchRoom.summaryOf(track)
            p.open(target, summary, startMs = start, hint = WatchRoom.hintOf(track), fallback = WatchRoom.fallbackOf(track))
            opening.value = target to summary
        }
    }

    private fun reset() {
        roomItem = null; loadingId = null; commandedPlaying = false; locallyPaused = false; alignedSeq = -1L; lastAnchor = null
        driftStrikes = 0; advancedSeq = -1L; announcedSkip = null; unplayable.clear(); startJob?.cancel()
        roomItemOpened?.let { player?.stop(); closePage() }
        roomItemOpened = null
    }
}
