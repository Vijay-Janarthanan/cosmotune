package com.cosmotune.music.desktop

import com.cosmotune.music.desktop.voice.DesktopVoiceEngine
import com.cosmotune.room.voice.MicAccess
import com.cosmotune.room.voice.MicAccessResult
import com.cosmotune.room.voice.RoomVoiceUi
import com.cosmotune.room.voice.VoiceEngine
import com.cosmotune.ui.room.RoomUiState

/**
 * The desktop's side of room voice that is not the engine itself: which voice fields the room screen reads, where the system's
 * microphone settings are, and the one-time hint about Windows' microphone privacy. Pure decisions, so tests never open a device
 * or the registry; [DesktopRooms] holds the [com.cosmotune.room.voice.RoomVoice] that uses them.
 */
internal object DesktopRoomVoice {
    /** Windows gives a desktop app that its privacy settings block a silent microphone, not an error, so this is said once instead. */
    const val MIC_PRIVACY_HINT = "If others cannot hear you, check Settings > Privacy > Microphone allows desktop apps"

    private const val WINDOWS_MIC_SETTINGS = "ms-settings:privacy-microphone"
    private const val MAC_MIC_SETTINGS = "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"

    /** The page of the system settings that holds the microphone permission; null on Linux, which has none. */
    fun micSettingsUri(windows: Boolean = DesktopPlatform.isWindows, mac: Boolean = DesktopPlatform.isMac): String? = when {
        windows -> WINDOWS_MIC_SETTINGS
        mac -> MAC_MIC_SETTINGS
        else -> null
    }

    /**
     * The room screen's voice fields from [voice]. This device has no voice link to itself, so [RoomUiState.selfUid] is never muted,
     * turned down or "connecting" here; it rings as speaking only as [voice] says (while its own mic is live and loud).
     */
    fun withVoice(state: RoomUiState, voice: RoomVoiceUi): RoomUiState {
        val self = state.selfUid
        return state.copy(
            voiceAvailable = voice.available,
            micLive = voice.micLive,
            deafened = voice.deafened,
            speakingUids = voice.speakingUids,
            mutedHere = voice.mutedHere - self,
            volumes = voice.volumes - self,
            voiceConnecting = voice.connecting - self,
            micBlocked = voice.micBlocked,
            micLevel = if (voice.micLive) voice.micLevel.coerceIn(0f, 1f) else 0f,
            voiceMessage = voice.message,
        )
    }

    /** An engine for the room just joined; one whose natives did not load is refused here, which the room reports as "not available". */
    fun engine(log: (String) -> Unit = DesktopTrackLog::log): VoiceEngine {
        val engine = DesktopVoiceEngine()
        if (!engine.available) {
            val reason = engine.unavailableReason ?: "Voice is not available on this computer"
            log("room voice: $reason")
            runCatching { engine.close() }
            throw IllegalStateException(reason)
        }
        return engine
    }
}

/**
 * The microphone permission on the desktop. Windows and macOS ask, or hand over silence, by themselves when capture starts, so there is
 * nothing to ask here; a capture that fails outright is reported by the voice mesh. [openSettings] opens the system's page for it.
 */
internal class DesktopMicAccess(
    private val settingsUri: String? = DesktopRoomVoice.micSettingsUri(),
    private val open: (String) -> Result<Unit> = DesktopExternalLinks::open,
) : MicAccess {
    override suspend fun ensure(): MicAccessResult = MicAccessResult.GRANTED

    override fun openSettings() {
        val uri = settingsUri ?: return
        open(uri).onFailure { DesktopTrackLog.log("room voice: could not open the microphone settings (${it.message?.take(160)})") }
    }
}

/**
 * [DesktopRoomVoice.MIC_PRIVACY_HINT], once per install, after the first unmute that went live; only on Windows, whose privacy
 * setting is the silent kind. [shown] and [markShown] are the stored flag (the preferences in the app, a variable in tests).
 */
internal class DesktopMicHint(
    private val windows: Boolean,
    private val shown: () -> Boolean,
    private val markShown: () -> Unit,
) {
    /** The hint to show after an unmute, or null; [liveNow] is whether the mic did go live. */
    fun afterUnmute(liveNow: Boolean): String? {
        if (!windows || !liveNow || shown()) return null
        markShown()
        return DesktopRoomVoice.MIC_PRIVACY_HINT
    }
}
