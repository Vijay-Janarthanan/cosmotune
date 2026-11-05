package com.cosmotune.music.data.room.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.util.Log
import androidx.core.content.ContextCompat
import com.cosmotune.room.voice.IceCandidate
import com.cosmotune.room.voice.PeerEvents
import com.cosmotune.room.voice.PeerState
import com.cosmotune.room.voice.VoiceEngine
import com.cosmotune.room.voice.VoicePeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Room voice on Android: Google's libwebrtc, one peer connection per other member, driven by the shared VoiceMesh.
 *
 * Every call into WebRTC runs on one serial dispatcher ([rtc]), so a connection is never used on one thread while it is being
 * disposed on another (a disposed connection is freed native memory, and touching it would crash the process, not throw). Every
 * one of those calls is wrapped: a failure is logged and turns voice off for that connection, or for the whole engine when it is
 * the engine itself that failed, and never reaches the app.
 *
 * Music keeps playing as it was. The app's players (Media3 in PlaybackService, Video, Podio) are left alone: this engine never
 * changes the audio mode, never asks for audio focus and never touches the speakerphone or Bluetooth SCO (the library itself does
 * none of these; checked in its bytecode), and the other people's voices are played as media ([PLAYOUT]) so they mix into the
 * same output as the music at the media volume. What that costs is listed at [PLAYOUT].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVoiceEngine(context: Context) : VoiceEngine {
    private val appContext = context.applicationContext
    private val rtc = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + rtc)

    @Volatile private var closed = false
    @Volatile private var sending = false

    // touched on [rtc] only
    private var started = false
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var source: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private val peers = mutableListOf<AndroidPeer>()
    private var pollJob: Job? = null

    private val local = MutableStateFlow(0f)
    override val localLevel: StateFlow<Float> = local.asStateFlow()

    override fun createPeer(remoteUid: String, events: PeerEvents): VoicePeer {
        val peer = AndroidPeer(remoteUid, events)
        if (closed) return peer.also { it.close() }
        scope.launch { peer.open() }
        return peer
    }

    override suspend fun startCapture(): Boolean {
        if (closed) return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) return false
        return withContext(rtc) {
            if (micTrack != null) return@withContext true
            val f = factory() ?: return@withContext false
            guard("capture") {
                // the default constraints keep WebRTC's own echo cancelling, noise suppression and gain control on
                val src = f.createAudioSource(MediaConstraints())
                val track = f.createAudioTrack(MIC_TRACK_ID, src)
                track.setEnabled(sending)
                source = src
                micTrack = track
                if (sending) peers.forEach { it.attach(track) }
                true
            } ?: false
        }
    }

    /**
     * Unmuting puts the microphone track on every connection's sender (no new negotiation: the sender has been there from the
     * start), muting takes it off again. Taken off, nothing sends, and WebRTC stops recording, so the system's microphone
     * indicator goes out while muted rather than staying lit for the rest of the room. While it sends, the microphone foreground
     * service keeps the capture alive with the app in the background (Android 14 and later stop it otherwise).
     */
    override fun setSending(sending: Boolean) {
        if (closed) return
        this.sending = sending
        if (!sending) local.value = 0f
        scope.launch {
            val track = micTrack
            guard("send") { track?.setEnabled(sending) }
            peers.forEach { it.attach(if (sending) track else null) }
            if (sending && track != null) VoiceForeground.start(appContext) else VoiceForeground.stop(appContext)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        sending = false
        local.value = 0f
        VoiceForeground.stop(appContext)
        scope.launch {
            pollJob?.cancel()
            // the connections first: the factory they came from goes last
            peers.toList().forEach { it.dispose() }
            guard("close") { micTrack?.dispose() }
            guard("close") { source?.dispose() }
            guard("close") { factory?.dispose() }
            guard("close") { adm?.release() }
            micTrack = null; source = null; factory = null; adm = null
            scope.cancel()
        }
    }

    // ---- engine ----

    /** The factory, built on first use; null once building it has failed, which leaves voice off for this engine. */
    private fun factory(): PeerConnectionFactory? {
        if (closed) return null
        if (started) return factory
        started = true
        factory = guard("start") {
            initialiseOnce(appContext)
            val module = JavaAudioDeviceModule.builder(appContext)
                .setAudioAttributes(PLAYOUT)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioRecordErrorCallback(RecordErrors)
                .setAudioTrackErrorCallback(PlayoutErrors)
                .createAudioDeviceModule()
            adm = module
            PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setAudioDeviceModule(module)
                .createPeerConnectionFactory()
        }
        return factory
    }

    private fun ensurePolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            var localNow = 0f
            while (isActive) {
                delay(POLL_MS)
                val open = peers.toList()
                open.forEach { it.poll() }
                val heard = if (sending) open.maxOfOrNull { it.lastLocal } ?: 0f else 0f
                localNow = VoiceLevels.smooth(localNow, heard)
                local.value = localNow
            }
        }
    }

    private inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (failure: Throwable) {
        Log.w(TAG, "voice $what failed", failure)
        null
    }

    // ---- one connection ----

    private inner class AndroidPeer(private val remoteUid: String, private val events: PeerEvents) : VoicePeer {
        @Volatile private var gone = false
        private var connection: PeerConnection? = null
        private var sender: RtpSender? = null
        private var remoteTrack: AudioTrack? = null
        private var mutedHere = false
        private var volume = 1f

        @Volatile var lastLocal = 0f
        private val remote = MutableStateFlow(0f)
        override val remoteLevel: StateFlow<Float> = remote.asStateFlow()

        /** Builds the connection, on [rtc], before anything queued after it. */
        fun open() {
            if (gone) return
            val f = factory() ?: return
            val pc = guard("connect") {
                f.createPeerConnection(configuration(), observer)?.also { pc ->
                    val transceiver = pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
                    )
                    sender = transceiver.sender
                    remoteTrack = transceiver.receiver.track() as? AudioTrack
                }
            }
            if (pc == null) {
                Log.w(TAG, "no voice connection to $remoteUid")
                return
            }
            connection = pc
            peers += this
            applyHearing()
            if (sending) attach(micTrack)
            ensurePolling()
        }

        fun attach(track: AudioTrack?) {
            if (gone || connection == null) return
            guard("attach") { sender?.setTrack(track, false) }
        }

        fun poll() {
            val pc = connection ?: return
            if (gone) return
            guard("stats") {
                pc.getStats { report ->
                    val (heard, own) = VoiceLevels.levelsOf(report.statsMap.values.map { it.type to it.members })
                    if (gone) return@getStats
                    remote.value = VoiceLevels.smooth(remote.value, heard ?: 0f)
                    lastLocal = own ?: 0f
                }
            }
        }

        override suspend fun createOffer(): String = withContext(rtc) {
            val pc = usable()
            val offer = sdp { pc.createOffer(it, MediaConstraints()) }
            setLocal(offer)
            usable().localDescription?.description ?: offer.description
        }

        override suspend fun acceptOffer(offer: String): String = withContext(rtc) {
            setRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
            val answer = sdp { usable().createAnswer(it, MediaConstraints()) }
            setLocal(answer)
            usable().localDescription?.description ?: answer.description
        }

        override suspend fun acceptAnswer(answer: String) = withContext(rtc) {
            setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
        }

        override fun addIceCandidate(candidate: IceCandidate) {
            scope.launch {
                val pc = connection ?: return@launch
                if (gone) return@launch
                guard("candidate") { pc.addIceCandidate(org.webrtc.IceCandidate(candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex, candidate.candidate)) }
            }
        }

        override fun setRemoteMuted(muted: Boolean) {
            mutedHere = muted
            scope.launch { applyHearing() }
        }

        override fun setRemoteVolume(volume: Float) {
            this.volume = volume
            scope.launch { applyHearing() }
        }

        override fun close() {
            if (gone) return
            gone = true
            remote.value = 0f
            lastLocal = 0f
            scope.launch { dispose() }
        }

        /** On [rtc]. Also disposes the sender, the receiver and the remote track; the microphone track is the engine's and stays. */
        fun dispose() {
            gone = true
            peers -= this
            val pc = connection ?: return
            connection = null; sender = null; remoteTrack = null
            guard("close") { pc.dispose() }
        }

        private fun applyHearing() {
            if (gone) return
            val track = remoteTrack ?: return
            guard("hearing") {
                track.setEnabled(!mutedHere)
                track.setVolume(VoiceLevels.gain(volume))
            }
        }

        private fun usable(): PeerConnection = connection?.takeIf { !gone } ?: throw IllegalStateException("voice connection to $remoteUid is not open")

        /**
         * Creates an offer or answer; the callback only resumes, so the connection is touched on [rtc] alone. A connection
         * disposed meanwhile may never call back, and the mesh waits on this, so the wait is bounded; running out is a plain
         * failure, not a cancellation, so it does not stop the mesh that called.
         */
        private suspend fun sdp(start: (SdpObserver) -> Unit): SessionDescription =
            withTimeoutOrNull(SDP_TIMEOUT_MS) { createSdp(start) } ?: throw IllegalStateException("no session description from $remoteUid's connection")

        private suspend fun createSdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) { if (cont.isActive) cont.resume(description) }
                override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }
            try { start(observer) } catch (failure: Throwable) { if (cont.isActive) cont.resumeWithException(failure) }
        }

        private suspend fun setLocal(description: SessionDescription) = set { usable().setLocalDescription(it, description) }

        private suspend fun setRemote(description: SessionDescription) = set { usable().setRemoteDescription(it, description) }

        private suspend fun set(start: (SdpObserver) -> Unit) {
            withTimeoutOrNull(SDP_TIMEOUT_MS) { setSdp(start) } ?: throw IllegalStateException("$remoteUid's connection did not take the session description")
        }

        private suspend fun setSdp(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
            }
            try { start(observer) } catch (failure: Throwable) { if (cont.isActive) cont.resumeWithException(failure) }
        }

        private val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                if (gone) return
                runCatching { events.onIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (gone) return
                runCatching { events.onState(VoiceLevels.peerState(newState.name)) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            // the one audio transceiver is negotiated once; nothing is ever added that would need another round
            override fun onRenegotiationNeeded() {}
        }
    }

    private object RecordErrors : JavaAudioDeviceModule.AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordInitError(message: String?) { Log.w(TAG, "microphone: $message") }
        override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, message: String?) { Log.w(TAG, "microphone: $code $message") }
        override fun onWebRtcAudioRecordError(message: String?) { Log.w(TAG, "microphone: $message") }
    }

    private object PlayoutErrors : JavaAudioDeviceModule.AudioTrackErrorCallback {
        override fun onWebRtcAudioTrackInitError(message: String?) { Log.w(TAG, "voice playout: $message") }
        override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode?, message: String?) { Log.w(TAG, "voice playout: $code $message") }
        override fun onWebRtcAudioTrackError(message: String?) { Log.w(TAG, "voice playout: $message") }
    }

    companion object {
        private const val TAG = "CosmoVoice"
        private const val MIC_TRACK_ID = "cosmotune-mic"
        private const val POLL_MS = 200L
        private const val SDP_TIMEOUT_MS = 10_000L

        /** Public STUN only (no TURN yet): two devices behind strict NATs on both ends may not reach each other. */
        val STUN_SERVERS = listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")

        /**
         * How the other people's voices are played. WebRTC's default is a voice call (USAGE_VOICE_COMMUNICATION), which outside a
         * call is routed like one: to the earpiece, at the call volume, and over Bluetooth only through the headset profile. As
         * media they come out wherever the music does, at the media volume, and Bluetooth headphones stay on their music profile.
         *
         * The costs, since the audio mode stays normal: the platform's echo canceller (which most phones only run in a call) is
         * left off and WebRTC's own is used instead. It cancels the voices it plays itself, but not the music, which the
         * microphone hears from a loudspeaker and sends on; headphones avoid that. With Bluetooth headphones the phone's own
         * microphone is used, not the headset's (that would need the call profile, which would take the music down to call quality).
         */
        val PLAYOUT: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }

        @Volatile private var initialised = false

        private fun initialiseOnce(context: Context) {
            if (initialised) return
            synchronized(this) {
                if (initialised) return
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialised = true
            }
        }

        private fun configuration() = PeerConnection.RTCConfiguration(
            STUN_SERVERS.map { PeerConnection.IceServer.builder(it).createIceServer() },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // keeps gathering, so a phone moving from Wi-Fi to mobile data offers its new address
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }
}
