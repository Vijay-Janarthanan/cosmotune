package com.cosmotune.music.playback.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import com.cosmotune.music.data.TrackLog

/**
 * Android 14's bit-perfect USB mixer, asked for only when the listener switched it on.
 *
 * From API 34 an app may ask, for `USAGE_MEDIA` on a USB output, for one of the mixer attributes
 * [AudioManager.getSupportedMixerAttributes] lists for that device. One whose behaviour is
 * [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT] makes the platform send this app's samples to
 * the DAC without mixing them. It only does that for a track in exactly the attributes' format, so
 * the request is made for the format of the AudioTrack that is actually open, and the readout calls
 * the path bit-perfect only when [AudioManager.getPreferredMixerAttributes] reads back a bit-perfect
 * entry in that same format for the routed device ([BitPerfectMixerPolicy.confirmed]).
 *
 * Off by default. Anything that does not fit (an older Android, a non-USB route, a DAC that lists
 * no bit-perfect format, no format matching the track, a refused request) leaves the normal mixed
 * path untouched and says which of those it was.
 */
object BitPerfectMixerPolicy {

    enum class Status {
        OFF,
        ANDROID_TOO_OLD,
        NOT_USB,
        NO_TRACK_YET,
        NO_BIT_PERFECT_FORMATS,
        NO_MATCHING_FORMAT,
        REFUSED,
        REQUESTED_UNCONFIRMED,
        CONFIRMED,
    }

    /** One mixer format, by the AudioFormat encoding constant. */
    data class MixerFormat(
        val encoding: Int,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitPerfect: Boolean,
    )

    /** The format of the AudioTrack Cosmotune has open. */
    data class TrackFormat(val encoding: Int, val sampleRateHz: Int, val channelCount: Int)

    data class State(
        val status: Status = Status.OFF,
        /** The device's bit-perfect formats, for the readout; empty when unknown. */
        val bitPerfectFormats: List<MixerFormat> = emptyList(),
        val requested: MixerFormat? = null,
        val deviceId: Int? = null,
    ) {
        val confirmed: Boolean get() = status == Status.CONFIRMED
        val supported: Boolean get() = bitPerfectFormats.isNotEmpty()

        val detail: String
            get() = when (status) {
                Status.OFF -> "Off"
                Status.ANDROID_TOO_OLD -> "Needs Android 14 or later"
                Status.NOT_USB -> "Only for a USB DAC"
                Status.NO_TRACK_YET -> "Waiting for playback"
                Status.NO_BIT_PERFECT_FORMATS -> "This USB device offers no bit-perfect format"
                Status.NO_MATCHING_FORMAT -> "No bit-perfect format matches the track (${formatList(bitPerfectFormats)})"
                Status.REFUSED -> "Android refused the request"
                Status.REQUESTED_UNCONFIRMED -> "Requested; Android has not confirmed it for this track"
                Status.CONFIRMED -> "Confirmed by Android · ${requested?.let(::describe).orEmpty()}"
            }
    }

    /**
     * Which bit-perfect format to ask for, or why none. Pure, for tests: the caller passes the SDK
     * level, whether the routed device is USB, what it lists and the open track's format.
     */
    fun decide(
        enabled: Boolean,
        sdkInt: Int,
        routeIsUsb: Boolean,
        supported: List<MixerFormat>,
        track: TrackFormat?,
    ): State {
        if (!enabled) return State(Status.OFF)
        if (sdkInt < 34) return State(Status.ANDROID_TOO_OLD)
        if (!routeIsUsb) return State(Status.NOT_USB)
        val bitPerfect = supported.filter { it.bitPerfect }.distinct()
        if (bitPerfect.isEmpty()) return State(Status.NO_BIT_PERFECT_FORMATS)
        if (track == null) return State(Status.NO_TRACK_YET, bitPerfect)
        val match = bitPerfect.firstOrNull { it.matches(track) }
            ?: return State(Status.NO_MATCHING_FORMAT, bitPerfect)
        return State(Status.REQUESTED_UNCONFIRMED, bitPerfect, requested = match)
    }

    /**
     * Whether Android confirms the path: what it reads back as preferred for the device is a
     * bit-perfect entry in exactly the open track's format, and that track was opened after the
     * preference was in place. The preference only shapes an output when one opens, so a track
     * that was already playing when it was set may still be on the mixed path; a request alone
     * is not confirmation.
     */
    fun confirmed(preferred: MixerFormat?, track: TrackFormat?, trackOpenedAfterRequest: Boolean): Boolean =
        trackOpenedAfterRequest && preferred != null && track != null &&
            preferred.bitPerfect && preferred.matches(track)

    private fun MixerFormat.matches(track: TrackFormat): Boolean =
        encoding == track.encoding && sampleRateHz == track.sampleRateHz && channelCount == track.channelCount

    fun describe(format: MixerFormat): String =
        "${encodingName(format.encoding)} / ${format.sampleRateHz / 1000.0} kHz / ${format.channelCount} ch"

    private fun formatList(formats: List<MixerFormat>): String =
        formats.take(4).joinToString(", ") { "${encodingName(it.encoding)} ${it.sampleRateHz / 1000.0} kHz" } +
            if (formats.size > 4) ", …" else ""

    fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float32"
        else -> "encoding $encoding"
    }
}

/** The AudioManager side of [BitPerfectMixerPolicy]: the only place the API 34 calls live. */
class BitPerfectMixer {

    /** The device a preference was set on, so it can be cleared again. */
    private var heldDevice: AudioDeviceInfo? = null

    /** The track generation that was open when the preference was set; later ones opened under it. */
    private var heldSinceGeneration: Long = Long.MAX_VALUE

    /**
     * Sets, keeps or clears the preference for [device] and returns what the readout should say.
     * Never throws: a failing call leaves the mixed path as it was.
     */
    fun apply(
        manager: AudioManager,
        enabled: Boolean,
        device: AudioDeviceInfo?,
        track: BitPerfectMixerPolicy.TrackFormat?,
        trackGeneration: Long,
    ): BitPerfectMixerPolicy.State {
        val usb = device?.type in USB_TYPES
        if (!enabled || Build.VERSION.SDK_INT < 34 || !usb || device == null) {
            release(manager)
            return BitPerfectMixerPolicy.decide(enabled, Build.VERSION.SDK_INT, usb, emptyList(), track)
        }
        return runCatching { applyOn34(manager, device, track, trackGeneration) }
            .onFailure { TrackLog.d(TAG, "bit-perfect mixer: ${it.message}") }
            .getOrElse {
                release(manager)
                BitPerfectMixerPolicy.State(BitPerfectMixerPolicy.Status.REFUSED, deviceId = device.id)
            }
    }

    @RequiresApi(34)
    private fun applyOn34(
        manager: AudioManager,
        device: AudioDeviceInfo,
        track: BitPerfectMixerPolicy.TrackFormat?,
        trackGeneration: Long,
    ): BitPerfectMixerPolicy.State {
        if (heldDevice != null && heldDevice?.id != device.id) release(manager)
        val supported = manager.getSupportedMixerAttributes(device)
        val formats = supported.map(::formatOf)
        val decision = BitPerfectMixerPolicy.decide(true, Build.VERSION.SDK_INT, true, formats, track)
            .copy(deviceId = device.id)
        val wanted = decision.requested
        if (wanted == null) {
            release(manager)
            return decision
        }
        val current = manager.getPreferredMixerAttributes(MEDIA, device)?.let(::formatOf)
        if (current != wanted) {
            val attributes = supported[formats.indexOf(wanted)]
            if (!manager.setPreferredMixerAttributes(MEDIA, device, attributes)) {
                return decision.copy(status = BitPerfectMixerPolicy.Status.REFUSED)
            }
            heldDevice = device
            heldSinceGeneration = trackGeneration
            TrackLog.i(TAG, "bit-perfect mixer requested: ${BitPerfectMixerPolicy.describe(wanted)} on ${device.productName}")
        } else if (heldDevice == null) {
            // Already in place (left by an earlier run): only a track opened from now on counts.
            heldDevice = device
            heldSinceGeneration = trackGeneration
        }
        val readBack = manager.getPreferredMixerAttributes(MEDIA, device)?.let(::formatOf)
        return if (BitPerfectMixerPolicy.confirmed(readBack, track, trackGeneration > heldSinceGeneration)) {
            decision.copy(status = BitPerfectMixerPolicy.Status.CONFIRMED, requested = readBack)
        } else {
            decision
        }
    }

    /** Gives the device back to the normal mixer. */
    fun release(manager: AudioManager) {
        val device = heldDevice ?: return
        heldDevice = null
        heldSinceGeneration = Long.MAX_VALUE
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { manager.clearPreferredMixerAttributes(MEDIA, device) }
                .onFailure { TrackLog.d(TAG, "bit-perfect mixer clear: ${it.message}") }
            TrackLog.i(TAG, "bit-perfect mixer released on ${device.productName}")
        }
    }

    @RequiresApi(34)
    private fun formatOf(attributes: AudioMixerAttributes): BitPerfectMixerPolicy.MixerFormat {
        val format = attributes.format
        return BitPerfectMixerPolicy.MixerFormat(
            encoding = format.encoding,
            sampleRateHz = format.sampleRate,
            channelCount = format.channelCount,
            bitPerfect = attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
        )
    }

    private companion object {
        const val TAG = "AUDIO_OUT"
        val MEDIA: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
