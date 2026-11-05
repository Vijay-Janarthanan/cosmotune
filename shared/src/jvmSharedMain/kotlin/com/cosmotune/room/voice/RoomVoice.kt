package com.cosmotune.room.voice

import com.cosmotune.room.RoomState
import com.cosmotune.room.RtdbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MicAccessResult { GRANTED, DENIED, BLOCKED }

/** The platform's microphone permission. Asked for the first time the person unmutes, never when they join. */
interface MicAccess {
    suspend fun ensure(): MicAccessResult
    fun openSettings()
}

/** What the room screen shows about voice, in the screen's own terms. */
data class RoomVoiceUi(
    /** False until a voice engine is running: while false the screen explains that voice is coming. */
    val available: Boolean = false,
    val speakingUids: Set<String> = emptySet(),
    val mutedHere: Set<String> = emptySet(),
    val volumes: Map<String, Float> = emptyMap(),
    /** People whose voice link is still being made. */
    val connecting: Set<String> = emptySet(),
    val micLive: Boolean = false,
    /** How loud this device's own microphone is now, 0 to 1 (for the level meter on the mic button). */
    val micLevel: Float = 0f,
    val deafened: Boolean = false,
    /** The permission was refused for good: only the system settings can change it. */
    val micBlocked: Boolean = false,
    val message: String? = null,
)

/**
 * Voice for the room this device is in, as the platform hosts use it: it starts with the room and ends with it, builds the engine
 * when the first room is joined (and treats an engine that cannot be built as "voice is not available here"), asks for the
 * microphone only at the first unmute, and turns the mesh's state into [RoomVoiceUi]. One implementation for the phone and the desktop;
 * only the engine and the permission differ.
 */
class RoomVoice(
    private val rtdb: RtdbApi,
    private val myUid: () -> String,
    private val engineFactory: () -> VoiceEngine,
    private val room: StateFlow<RoomState?>,
    /** Tells the room whether this device's microphone is live (the member's `mic` field), so everyone sees it. */
    private val publishMic: suspend (Boolean) -> Unit,
    private val mic: MicAccess,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()

    /** Controls run one at a time, in the order they were asked, so two quick toggles never finish out of order. */
    private val controls = Mutex()
    private var mesh: VoiceMesh? = null
    private var meshCode: String? = null
    private var engine: VoiceEngine? = null
    private var followJob: Job? = null
    private var meshJob: Job? = null

    private val mutableUi = MutableStateFlow(RoomVoiceUi())
    val ui: StateFlow<RoomVoiceUi> = mutableUi.asStateFlow()

    private var blocked = false
    private var failure: String? = null

    fun start() {
        if (followJob != null) return
        followJob = scope.launch {
            room.collect { current ->
                // a different room without a gap in between (the null was not seen) is a new call, not the old one
                if (current != null && meshCode != null && meshCode != current.code) closeVoice()
                if (current != null) open(current) else closeVoice()
            }
        }
    }

    private suspend fun open(current: RoomState) = lock.withLock {
        if (mesh != null) return@withLock
        val built = try {
            engineFactory()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (problem: Throwable) {
            failure = "Voice is not available on this device"
            publish(null)
            return@withLock
        }
        val made = VoiceMesh(rtdb, current.code, myUid(), built, scope)
        engine = built
        mesh = made
        meshCode = current.code
        made.start(room)
        meshJob = scope.launch { made.state.collect { publish(it) } }
    }

    private suspend fun closeVoice() = lock.withLock {
        meshJob?.cancel(); meshJob = null
        mesh?.close(); mesh = null; meshCode = null
        runCatching { engine?.close() }; engine = null
        blocked = false
        failure = null
        mutableUi.value = RoomVoiceUi()
    }

    private fun publish(voice: VoiceState?) {
        val self = myUid()
        val peers = voice?.peers.orEmpty().values
        mutableUi.value = RoomVoiceUi(
            available = voice != null,
            speakingUids = buildSet {
                addAll(voice?.speakingUids.orEmpty())
                if (voice != null && voice.micLive && voice.localLevel > PeerVoice.SPEAKING_LEVEL) add(self)
            },
            mutedHere = peers.filter { it.mutedHere }.map { it.uid }.toSet(),
            volumes = peers.filter { it.volume != 1f }.associate { it.uid to it.volume },
            connecting = peers.filter { it.state == PeerState.NEW || it.state == PeerState.CONNECTING }.map { it.uid }.toSet(),
            micLive = voice?.micLive ?: false,
            micLevel = if (voice?.micLive == true) voice.localLevel else 0f,
            deafened = voice?.deafened ?: false,
            micBlocked = blocked,
            message = voice?.message ?: failure,
        )
    }

    /** The first unmute asks for the microphone; a refusal is reported, a permanent one says to open the settings. */
    suspend fun setMicLive(live: Boolean) = controls.withLock {
        val voice = mesh ?: return@withLock
        if (live) {
            when (mic.ensure()) {
                MicAccessResult.GRANTED -> blocked = false
                MicAccessResult.DENIED -> { fail(voice, "The microphone permission was not given", blocked = false); return@withLock }
                MicAccessResult.BLOCKED -> { fail(voice, "The microphone is blocked in the system settings", blocked = true); return@withLock }
            }
        }
        if (voice.setMicLive(live)) runCatching { publishMic(live) }
        publish(voice.state.value) // read back at once, so the button does not wait for the next change of the mesh
    }

    private fun fail(voice: VoiceMesh, text: String, blocked: Boolean) {
        this.blocked = blocked
        failure = text
        publish(voice.state.value.copy(message = text))
    }

    suspend fun setDeafened(deafened: Boolean) = controls.withLock { mesh?.setDeafened(deafened); Unit }
    suspend fun setMemberMuted(uid: String, muted: Boolean) = controls.withLock { mesh?.setMemberMuted(uid, muted); Unit }
    suspend fun setMemberVolume(uid: String, volume: Float) = controls.withLock { mesh?.setMemberVolume(uid, volume); Unit }
    fun openMicSettings() = mic.openSettings()

    fun dismissMessage() {
        failure = null
        mesh?.dismissMessage()
        mutableUi.update { it.copy(message = null) }
    }

    /** Leaving the app or the room: the connections and the microphone are released. */
    suspend fun close() {
        followJob?.cancel(); followJob = null
        closeVoice()
    }
}
