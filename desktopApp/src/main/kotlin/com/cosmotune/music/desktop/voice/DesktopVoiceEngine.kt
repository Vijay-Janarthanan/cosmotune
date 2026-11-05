package com.cosmotune.music.desktop.voice

import com.cosmotune.room.voice.IceCandidate
import com.cosmotune.room.voice.PeerEvents
import com.cosmotune.room.voice.PeerState
import com.cosmotune.room.voice.VoiceEngine
import com.cosmotune.room.voice.VoicePeer
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsReport
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.MediaType
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where the voice this device sends comes from. The real one is the microphone; tests feed a tone, so they never open a device.
 * [open] is called once, the first time the person unmutes; null means there is nothing to capture from.
 */
interface DesktopVoiceInput {
    fun open(factory: PeerConnectionFactory): AudioTrackSource?

    /** Stops whatever [open] started (a thread pushing samples); the source itself is freed by the engine. */
    fun close() {}
}

/** The system's microphone, through WebRTC's own capture, with echo cancellation, noise suppression and gain control on. */
object DesktopMicrophoneInput : DesktopVoiceInput {
    override fun open(factory: PeerConnectionFactory): AudioTrackSource? {
        if (MediaDevices.getAudioCaptureDevices().isNullOrEmpty()) return null
        return factory.createAudioSource(
            AudioOptions().apply {
                echoCancellation = true
                noiseSuppression = true
                autoGainControl = true
                highpassFilter = true
            },
        )
    }
}

/**
 * The desktop's room voice: WebRTC through webrtc-java, one connection per other member, audio straight between devices.
 *
 * Nothing here may take the app down. Every native call is guarded: a machine whose natives do not load, or that has no audio
 * device, gets an engine that is [available] false, whose [createPeer] throws one clear message and whose [startCapture] is false.
 *
 * [audioModule] is what plays (and, for the microphone, records): the platform's devices by default, [HeadlessAudioDeviceModule]
 * for tests, which still pulls the received audio through the pipeline (so levels are measured) but plays nothing.
 */
class DesktopVoiceEngine(
    private val input: DesktopVoiceInput = DesktopMicrophoneInput,
    audioModule: () -> AudioDeviceModuleBase = { AudioDeviceModule() },
    /** STUN only for now: two people behind strict NATs will not connect until a TURN server is added. */
    private val iceServers: List<String> = DEFAULT_STUN,
    private val levelPollMs: Long = 200,
) : VoiceEngine {

    private class Natives(val module: AudioDeviceModuleBase, val processing: AudioProcessing, val factory: PeerConnectionFactory)

    private val lock = Any()
    private val natives: Natives?

    /** Why voice is off on this machine, for the person to read; null when it works. */
    val unavailableReason: String?
    val available: Boolean get() = natives != null

    private val peers = CopyOnWriteArrayList<Peer>()
    private var source: AudioTrackSource? = null
    private var track: AudioTrack? = null
    @Volatile private var sending = false
    @Volatile private var closed = false

    private val ownLevel = MutableStateFlow(0f)
    override val localLevel: StateFlow<Float> = ownLevel.asStateFlow()

    private val poller: ScheduledExecutorService?

    init {
        var made: Natives? = null
        var failure: Throwable? = null
        runCatching {
            val module = audioModule()
            // The dummy layer does not start playing by itself; it is its render thread that pulls (decodes) what arrives.
            if (module is HeadlessAudioDeviceModule) {
                module.initPlayout()
                module.startPlayout()
            }
            val processing = AudioProcessing().apply { applyConfig(voiceProcessing()) }
            made = Natives(module, processing, PeerConnectionFactory(module, processing))
        }.onFailure { failure = it }
        natives = made
        unavailableReason = failure?.let { "Voice is not available on this computer (${it.javaClass.simpleName}: ${it.message})" }
        poller = if (made != null) {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cosmotune-voice-levels").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay({ pollLevels() }, levelPollMs, levelPollMs, TimeUnit.MILLISECONDS) }
        } else {
            null
        }
    }

    override fun createPeer(remoteUid: String, events: PeerEvents): VoicePeer {
        val n = natives ?: throw IllegalStateException(unavailableReason ?: "Voice is not available")
        // Under the lock so a microphone opening at the same moment either reaches this connection or is already in it.
        return synchronized(lock) {
            check(!closed) { "The voice engine is closed" }
            runCatching { Peer(n.factory, events) }
                .getOrElse { throw IllegalStateException("Could not start a voice connection: ${it.message}", it) }
                .also { peers += it }
        }
    }

    override suspend fun startCapture(): Boolean = withContext(Dispatchers.IO) {
        val n = natives ?: return@withContext false
        synchronized(lock) {
            if (closed) return@withContext false
            if (track != null) return@withContext true
            val opened = runCatching {
                val src = input.open(n.factory) ?: return@runCatching null
                val t = runCatching { n.factory.createAudioTrack("cosmotune-voice", src) }
                    .onFailure { runCatching { src.dispose() } }
                    .getOrThrow()
                t.setEnabled(sending)
                src to t
            }.getOrNull() ?: return@withContext false
            source = opened.first
            track = opened.second
            // Every connection already carries an empty sender; giving it the track needs no new negotiation.
            peers.forEach { it.attach(opened.second) }
            true
        }
    }

    override fun setSending(sending: Boolean) {
        this.sending = sending
        synchronized(lock) { runCatching { track?.setEnabled(sending) } }
        if (!sending) ownLevel.value = 0f
    }

    override fun close() {
        val n = synchronized(lock) {
            if (closed) return
            closed = true
            natives
        }
        poller?.shutdownNow()
        val closing = peers.toList()
        peers.clear()
        closing.forEach { it.close() }
        // The native teardown races a connection that is still shutting down; each says when it is done.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        closing.forEach { it.awaitClosed(deadline) }
        runCatching { input.close() }
        synchronized(lock) {
            // A track that a sender still holds refuses to go (it throws); by now every sender is gone.
            runCatching { track?.dispose() }
            runCatching { source?.dispose() }
            track = null
            source = null
        }
        if (n != null) {
            if (n.module is HeadlessAudioDeviceModule) runCatching { n.module.stopPlayout() }
            runCatching { n.factory.dispose() }
            runCatching { n.processing.dispose() }
            runCatching { n.module.dispose() }
        }
        ownLevel.value = 0f
    }

    private fun pollLevels() {
        if (closed) return
        val live = peers.toList()
        live.forEach { it.requestStats() }
        ownLevel.value = if (sending && track != null) live.maxOfOrNull { it.sentLevel } ?: 0f else 0f
    }

    private fun rtcConfig() = RTCConfiguration().apply {
        iceServers = this@DesktopVoiceEngine.iceServers.map { url -> RTCIceServer().apply { urls = listOf(url) } }
    }

    private inner class Peer(factory: PeerConnectionFactory, private val events: PeerEvents) : VoicePeer {
        @Volatile private var done = false
        private val closedSignal = CountDownLatch(1)
        @Volatile private var muted = false
        @Volatile private var volume = 1f
        @Volatile var sentLevel = 0f
            private set
        private val level = MutableStateFlow(0f)
        override val remoteLevel: StateFlow<Float> = level.asStateFlow()

        private val pc: RTCPeerConnection
        private val transceiver: RTCRtpTransceiver
        @Volatile private var remoteTrack: AudioTrack? = null

        init {
            pc = factory.createPeerConnection(rtcConfig(), object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    if (!done) events.onIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
                }

                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    if (state == RTCPeerConnectionState.CLOSED) closedSignal.countDown()
                    // once this side has let go, the mesh has already forgotten the person; a late "closed" would bring them back
                    if (done) return
                    events.onState(
                        when (state) {
                            RTCPeerConnectionState.NEW -> PeerState.NEW
                            // a dropped path often comes back by itself; if it does not, the connection turns FAILED
                            RTCPeerConnectionState.CONNECTING, RTCPeerConnectionState.DISCONNECTED -> PeerState.CONNECTING
                            RTCPeerConnectionState.CONNECTED -> PeerState.CONNECTED
                            RTCPeerConnectionState.FAILED -> PeerState.FAILED
                            RTCPeerConnectionState.CLOSED -> PeerState.CLOSED
                        },
                    )
                }
            }) ?: error("WebRTC made no connection")
            val init = RTCRtpTransceiverInit().apply {
                direction = RTCRtpTransceiverDirection.SEND_RECV
                streamIds = listOf("cosmotune-voice")
            }
            // One audio line both ways from the start. The sender is empty until the microphone opens, then gets the track
            // (replaceTrack), and a muted track sends silence, so neither unmuting nor muting ever needs a new offer.
            val existing = track
            transceiver = runCatching {
                if (existing != null) pc.addTransceiver(existing, init) else pc.addTransceiver(MediaType.AUDIO, init)
            }.getOrElse { runCatching { pc.close() }; throw it }
            remoteTrack = runCatching { transceiver.receiver.track as? AudioTrack }.getOrNull()
            applyRemote()
        }

        fun attach(local: AudioTrack) {
            if (done) return
            runCatching { transceiver.sender.replaceTrack(local) }
        }

        override suspend fun createOffer(): String {
            val offer = describe { pc.createOffer(RTCOfferOptions(), it) }
            setLocal(offer)
            return offer.sdp
        }

        override suspend fun acceptOffer(offer: String): String {
            setRemote(RTCSessionDescription(RTCSdpType.OFFER, offer))
            val answer = describe { pc.createAnswer(RTCAnswerOptions(), it) }
            setLocal(answer)
            return answer.sdp
        }

        override suspend fun acceptAnswer(answer: String) = setRemote(RTCSessionDescription(RTCSdpType.ANSWER, answer))

        override fun addIceCandidate(candidate: IceCandidate) {
            if (done) return
            runCatching { pc.addIceCandidate(RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)) }
        }

        override fun setRemoteMuted(muted: Boolean) {
            this.muted = muted
            if (muted) level.value = 0f
            applyRemote()
        }

        override fun setRemoteVolume(volume: Float) {
            this.volume = volume.coerceIn(0f, 1f)
            applyRemote()
        }

        private fun applyRemote() {
            if (done) return
            val t = remoteTrack ?: return
            // A disabled remote track plays nothing; the volume (WebRTC's 0 to 10, 1 as received) scales only what is played.
            runCatching { t.setEnabled(!muted) }
            runCatching { t.setVolume(volume.toDouble()) }
        }

        fun requestStats() {
            if (done) return
            runCatching { pc.getStats { report -> if (!done) readLevels(report) } }
        }

        private fun readLevels(report: RTCStatsReport) {
            var heard = 0f
            var sent = 0f
            report.stats.values.forEach { s ->
                val a = s.attributes
                if (a["kind"] != "audio") return@forEach
                val value = (a["audioLevel"] as? Number)?.toFloat() ?: return@forEach
                when (s.type) {
                    RTCStatsType.INBOUND_RTP -> heard = maxOf(heard, value)
                    RTCStatsType.MEDIA_SOURCE -> sent = maxOf(sent, value)
                    else -> Unit
                }
            }
            level.value = if (muted) 0f else heard.coerceIn(0f, 1f)
            sentLevel = sent.coerceIn(0f, 1f)
        }

        override fun close() {
            if (done) return
            done = true
            level.value = 0f
            peers -= this
            // The receiver's track is a view of one the connection owns: let go of it while the owner is still there.
            runCatching { remoteTrack?.dispose() }
            remoteTrack = null
            runCatching { pc.close() }
            runCatching { transceiver.dispose() }
        }

        fun awaitClosed(deadlineNanos: Long) {
            val left = deadlineNanos - System.nanoTime()
            if (left > 0) runCatching { closedSignal.await(left, TimeUnit.NANOSECONDS) }
        }

        private suspend fun describe(start: (CreateSessionDescriptionObserver) -> Unit): RTCSessionDescription = step { cont ->
            start(object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) = cont(Result.success(description))
                override fun onFailure(error: String) = cont(Result.failure(IllegalStateException(error)))
            })
        }

        private suspend fun setLocal(description: RTCSessionDescription) = step<Unit> { cont ->
            pc.setLocalDescription(description, settled(cont))
        }

        private suspend fun setRemote(description: RTCSessionDescription) = step<Unit> { cont ->
            pc.setRemoteDescription(description, settled(cont))
        }

        private fun settled(cont: (Result<Unit>) -> Unit) = object : SetSessionDescriptionObserver {
            override fun onSuccess() = cont(Result.success(Unit))
            override fun onFailure(error: String) = cont(Result.failure(IllegalStateException(error)))
        }

        /** One asynchronous WebRTC step, as a suspension; a callback that never comes ends in a timeout, not a hang. */
        private suspend fun <T> step(start: ((Result<T>) -> Unit) -> Unit): T {
            check(!done) { "The voice connection is closed" }
            return withTimeout(NEGOTIATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val once = AtomicBoolean(false)
                    runCatching {
                        start { result ->
                            if (!once.compareAndSet(false, true)) return@start
                            result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                        }
                    }.onFailure { if (once.compareAndSet(false, true)) cont.resumeWithException(it) }
                }
            }
        }
    }

    companion object {
        val DEFAULT_STUN = listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")
        private const val NEGOTIATION_TIMEOUT_MS = 15_000L

        private fun voiceProcessing() = AudioProcessingConfig().apply {
            // It cancels what WebRTC itself plays (the other voices), not the music the app plays through its own output.
            echoCanceller.enabled = true
            noiseSuppression.enabled = true
            noiseSuppression.level = AudioProcessingConfig.NoiseSuppression.Level.MODERATE
            highPassFilter.enabled = true
            gainControllerDigital.enabled = true
            gainControllerDigital.adaptiveDigital.enabled = true
        }
    }
}
