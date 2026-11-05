package com.cosmotune.room.voice

import kotlinx.coroutines.flow.StateFlow

/** One candidate address a peer connection offers for reaching this device; exchanged through the room's signal inbox. */
data class IceCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String)

enum class PeerState { NEW, CONNECTING, CONNECTED, FAILED, CLOSED }

/** What a connection tells the mesh as it goes. */
interface PeerEvents {
    fun onIceCandidate(candidate: IceCandidate)
    fun onState(state: PeerState)
}

/**
 * The voice engine of a platform (WebRTC on Android, a native WebRTC library on the desktop), reduced to what the room needs, so the
 * connection logic is the same everywhere and can be tested without a microphone. Audio never passes through our service: it goes
 * straight between devices, and only the offers and addresses that set it up pass through the room's signal inbox.
 */
interface VoiceEngine {
    /**
     * A connection to one other person. It always sends and receives audio, with nothing to send until [startCapture] has been
     * called, so that unmuting later never needs a new negotiation.
     */
    fun createPeer(remoteUid: String, events: PeerEvents): VoicePeer

    /**
     * Opens the microphone. False when it could not be opened (permission refused, no device); the platform host asks for the permission
     * before calling this, the first time the person unmutes and not before.
     */
    suspend fun startCapture(): Boolean

    /** Sends what the microphone hears to every peer (true) or stops sending (false); the capture stays open so unmuting is instant. */
    fun setSending(sending: Boolean)

    /** How loud this device's own microphone is now, 0 to 1, for the "speaking" ring. */
    val localLevel: StateFlow<Float>

    fun close()
}

interface VoicePeer {
    /** The offer this side sends (the side with the lower user id makes it). Returns the session description as text. */
    suspend fun createOffer(): String

    /** Takes the other side's offer and returns this side's answer. */
    suspend fun acceptOffer(offer: String): String

    suspend fun acceptAnswer(answer: String)

    fun addIceCandidate(candidate: IceCandidate)

    /** Silences this person for this device only, and sets how loud they are (0 to 1). Never changes what anyone else hears. */
    fun setRemoteMuted(muted: Boolean)
    fun setRemoteVolume(volume: Float)

    /** How loud this person is now, 0 to 1. */
    val remoteLevel: StateFlow<Float>

    fun close()
}
