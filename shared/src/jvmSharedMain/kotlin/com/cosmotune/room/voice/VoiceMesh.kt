package com.cosmotune.room.voice

import com.cosmotune.room.RoomState
import com.cosmotune.room.RtdbApi
import com.cosmotune.room.ServerTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One other person's voice as this device hears it. */
data class PeerVoice(
    val uid: String,
    val state: PeerState = PeerState.NEW,
    val level: Float = 0f,
    val mutedHere: Boolean = false,
    val volume: Float = 1f,
) {
    val speaking: Boolean get() = state == PeerState.CONNECTED && !mutedHere && level > SPEAKING_LEVEL

    companion object {
        const val SPEAKING_LEVEL = 0.06f
    }
}

data class VoiceState(
    val peers: Map<String, PeerVoice> = emptyMap(),
    /** This device is sending its microphone to the room. */
    val micLive: Boolean = false,
    /** Every voice is silenced on this device. */
    val deafened: Boolean = false,
    val localLevel: Float = 0f,
    /** The last thing that went wrong for the person to read ("Microphone not available"); null when fine. */
    val message: String? = null,
) {
    val speakingUids: Set<String> get() = peers.values.filter { it.speaking }.map { it.uid }.toSet()
}

/**
 * Everyone in the room hears everyone: each device keeps one connection to each other member (a mesh), which is comfortable for a
 * handful of people. The connections are set up through the room's signal inbox in the Realtime Database; after that the audio goes
 * straight between devices.
 *
 * The member with the lower user id makes the offer, so two devices never offer at once and there is nothing to untangle. A member
 * who leaves has their connection closed; one that fails is made again after a moment. Receiving needs no permission: the
 * microphone is only opened the first time the person unmutes.
 */
class VoiceMesh(
    private val rtdb: RtdbApi,
    private val code: String,
    private val myUid: String,
    private val engine: VoiceEngine,
    private val scope: CoroutineScope,
    private val restartAfterMs: Long = 2_000,
) {
    private class Handle(var peer: VoicePeer, val offerer: Boolean) {
        var remoteSet = false
        val pendingIce = mutableListOf<IceCandidate>()
        var levelJob: Job? = null
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val handles = mutableMapOf<String, Handle>()
    private val seen = mutableSetOf<String>()
    private var jobs: List<Job> = emptyList()
    private var membersNow: Set<String> = emptySet()

    private val mutableState = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()

    /** Follows the room: a connection per other member, and this device's inbox. */
    fun start(room: StateFlow<RoomState?>) {
        jobs = listOf(
            scope.launch { room.collect { reconcile(it?.members?.map { m -> m.uid }.orEmpty().filter { id -> id != myUid }.toSet()) } },
            scope.launch { listenForSignals() },
            scope.launch { engine.localLevel.collect { level -> mutableState.update { it.copy(localLevel = level) } } },
        )
    }

    suspend fun setMicLive(live: Boolean): Boolean {
        if (live && !engine.startCapture()) {
            mutableState.update { it.copy(micLive = false, message = "The microphone is not available") }
            return false
        }
        engine.setSending(live)
        mutableState.update { it.copy(micLive = live, message = null) }
        return true
    }

    /** Silences every voice on this device without leaving the room. */
    suspend fun setDeafened(deafened: Boolean) = lock.withLock {
        handles.forEach { (uid, handle) -> handle.peer.setRemoteMuted(deafened || mutableState.value.peers[uid]?.mutedHere == true) }
        mutableState.update { it.copy(deafened = deafened) }
    }

    suspend fun setMemberMuted(uid: String, muted: Boolean) = lock.withLock {
        handles[uid]?.peer?.setRemoteMuted(muted || mutableState.value.deafened)
        mutableState.update { s -> s.copy(peers = s.peers + (uid to (s.peers[uid] ?: PeerVoice(uid)).copy(mutedHere = muted))) }
    }

    suspend fun setMemberVolume(uid: String, volume: Float) = lock.withLock {
        val v = volume.coerceIn(0f, 1f)
        handles[uid]?.peer?.setRemoteVolume(v)
        mutableState.update { s -> s.copy(peers = s.peers + (uid to (s.peers[uid] ?: PeerVoice(uid)).copy(volume = v))) }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    suspend fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        lock.withLock {
            handles.values.forEach { it.levelJob?.cancel(); runCatching { it.peer.close() } }
            handles.clear()
        }
        mutableState.value = VoiceState()
    }

    // ---- connections ----

    private suspend fun reconcile(others: Set<String>) {
        lock.withLock { membersNow = others }
        for (uid in others) ensurePeer(uid)
        val gone = lock.withLock { handles.keys.filter { it !in others } }
        for (uid in gone) dropPeer(uid)
    }

    private suspend fun ensurePeer(uid: String) {
        val created = lock.withLock {
            if (handles.containsKey(uid)) return@withLock null
            try {
                newHandle(uid).also { handles[uid] = it }
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (failure: Throwable) {
                // an engine that cannot make a connection (no native library, no audio device) turns voice off; it must not end the room
                mutableState.update { it.copy(message = "Voice is not available on this device") }
                null
            }
        } ?: return
        if (created.offerer) offer(uid, created)
    }

    private fun newHandle(uid: String): Handle {
        val peer = engine.createPeer(uid, object : PeerEvents {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch { send(uid, "ice", candidateJson(candidate)) }
            }

            override fun onState(state: PeerState) {
                mutableState.update { s -> s.copy(peers = s.peers + (uid to (s.peers[uid] ?: PeerVoice(uid)).copy(state = state))) }
                if (state == PeerState.FAILED) scope.launch { restart(uid) }
            }
        })
        val handle = Handle(peer, offerer = myUid < uid)
        val current = mutableState.value.peers[uid] ?: PeerVoice(uid)
        mutableState.update { it.copy(peers = it.peers + (uid to current.copy(state = PeerState.NEW))) }
        peer.setRemoteMuted(current.mutedHere || mutableState.value.deafened)
        peer.setRemoteVolume(current.volume)
        handle.levelJob = scope.launch {
            peer.remoteLevel.collect { level -> mutableState.update { s -> s.copy(peers = s.peers + (uid to (s.peers[uid] ?: PeerVoice(uid)).copy(level = level))) } }
        }
        return handle
    }

    private suspend fun offer(uid: String, handle: Handle) {
        runCatching { send(uid, "offer", handle.peer.createOffer()) }
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun dropPeer(uid: String) {
        lock.withLock {
            handles.remove(uid)?.let { it.levelJob?.cancel(); runCatching { it.peer.close() } }
            mutableState.update { it.copy(peers = it.peers - uid) }
        }
    }

    private suspend fun restart(uid: String) {
        delay(restartAfterMs)
        if (uid !in lock.withLock { membersNow }) return
        dropPeer(uid)
        ensurePeer(uid)
    }

    // ---- the inbox ----

    private suspend fun listenForSignals() {
        var attempt = 0
        while (true) {
            try {
                rtdb.listen("signals/$code/$myUid").collect { tree ->
                    attempt = 0
                    process(tree as? JsonObject ?: return@collect)
                }
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: Throwable) {
                // the inbox is read again after a pause; whatever arrived meanwhile is still there
            }
            delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
        }
    }

    private suspend fun process(tree: JsonObject) {
        // signals are handled oldest first within a sender; an offer must land before its candidates
        val pending = tree.entries.flatMap { (from, byId) ->
            (byId as? JsonObject)?.entries.orEmpty().mapNotNull { (id, message) ->
                (message as? JsonObject)?.let { Triple(from, id, it) }
            }
        }.sortedWith(compareBy({ ((it.third["t"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L) }, { it.second }))
        for ((from, id, message) in pending) {
            if (!seen.add("$from/$id")) continue
            runCatching { handle(from, message) }.onFailure { if (it is CancellationException) throw it }
            runCatching { rtdb.delete("signals/$code/$myUid/$from/$id") }
        }
    }

    private suspend fun handle(from: String, message: JsonObject) {
        val type = message["type"]?.jsonPrimitive?.contentOrNull ?: return
        val payload = message["payload"]?.jsonPrimitive?.contentOrNull ?: return
        // someone not in the room (any more) is not answered
        if (from !in lock.withLock { membersNow }) return
        ensurePeer(from)
        val handle = lock.withLock { handles[from] } ?: return
        when (type) {
            "offer" -> {
                if (handle.offerer) return // this side makes the offer; an offer from that person is stale
                val active = if (handle.remoteSet) {
                    // a new offer on a connection that already ran: the other side started over, so this side does too
                    dropPeer(from); ensurePeer(from); lock.withLock { handles[from] } ?: return
                } else handle
                val answer = active.peer.acceptOffer(payload)
                active.remoteSet = true
                flush(active)
                send(from, "answer", answer)
            }
            "answer" -> {
                if (!handle.offerer || handle.remoteSet) return
                handle.peer.acceptAnswer(payload)
                handle.remoteSet = true
                flush(handle)
            }
            "ice" -> {
                val candidate = parseCandidate(payload) ?: return
                if (handle.remoteSet) handle.peer.addIceCandidate(candidate) else handle.pendingIce += candidate
            }
        }
    }

    private fun flush(handle: Handle) {
        handle.pendingIce.forEach { handle.peer.addIceCandidate(it) }
        handle.pendingIce.clear()
    }

    private suspend fun send(to: String, type: String, payload: String) {
        runCatching {
            rtdb.push("signals/$code/$to/$myUid", buildJsonObject {
                put("type", type)
                put("payload", payload.take(8_000))
                put("t", ServerTimestamp)
            })
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun candidateJson(c: IceCandidate): String = buildJsonObject {
        c.sdpMid?.let { put("sdpMid", it) }
        put("sdpMLineIndex", c.sdpMLineIndex)
        put("candidate", c.candidate)
    }.toString()

    private fun parseCandidate(text: String): IceCandidate? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        IceCandidate(
            (o["sdpMid"] as? JsonPrimitive)?.contentOrNull,
            (o["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
            o["candidate"]!!.jsonPrimitive.content,
        )
    }.getOrNull()
}
