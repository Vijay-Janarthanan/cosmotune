package com.cosmotune.music.data.room.voice

import android.content.Context
import androidx.annotation.StringRes
import com.cosmotune.music.R
import com.cosmotune.room.voice.MicAccess
import com.cosmotune.room.voice.MicAccessResult
import com.cosmotune.room.voice.RoomVoiceUi
import com.cosmotune.ui.room.RoomUiState

/**
 * The shared room voice's [MicAccess] on the phone, over [MicPermission]: already granted needs no dialog; otherwise the system
 * request is shown (this is only reached from the first unmute), and a refusal is final when the system will not ask again.
 * The four checks are parameters so the mapping can be tested without Android.
 */
class AndroidMicAccess internal constructor(
    private val granted: () -> Boolean,
    private val request: suspend () -> Boolean,
    private val blocked: () -> Boolean,
    private val settings: () -> Unit,
) : MicAccess {
    constructor(context: Context) : this(
        granted = { MicPermission.isGranted(context) },
        request = { MicPermission.ensure() },
        blocked = { MicPermission.isBlocked(context) },
        settings = { MicPermission.openAppSettings(context) },
    )

    override suspend fun ensure(): MicAccessResult = when {
        granted() -> MicAccessResult.GRANTED
        request() -> MicAccessResult.GRANTED
        blocked() -> MicAccessResult.BLOCKED
        else -> MicAccessResult.DENIED
    }

    override fun openSettings() = settings()
}

/** The room voice's state in the room screen's fields, and its messages in the phone's own strings. */
object PhoneRoomVoice {
    /** [state] with every voice field taken from [voice]; [text] turns a string resource into its text. */
    fun applyTo(state: RoomUiState, voice: RoomVoiceUi, text: (Int) -> String): RoomUiState = state.copy(
        voiceAvailable = voice.available,
        micLive = voice.micLive,
        deafened = voice.deafened,
        speakingUids = voice.speakingUids,
        mutedHere = voice.mutedHere,
        volumes = voice.volumes,
        voiceConnecting = voice.connecting,
        micBlocked = voice.micBlocked,
        micLevel = voice.micLevel,
        voiceMessage = voice.message?.let { message -> messageRes(message)?.let(text) ?: message },
    )

    /**
     * The shared voice code says what went wrong in English; the ones it says are translated here. One it says that is not
     * listed is shown as it is rather than not at all.
     */
    @StringRes
    fun messageRes(message: String): Int? = when (message) {
        "The microphone permission was not given" -> R.string.room_voice_mic_denied
        "The microphone is blocked in the system settings" -> R.string.room_voice_mic_blocked
        "The microphone is not available" -> R.string.room_voice_mic_unavailable
        "Voice is not available on this device" -> R.string.room_voice_unavailable
        else -> null
    }
}
