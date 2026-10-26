package com.cosmotune.music.desktop

import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.TelemetryProvenance

/** A decoder's actual JOC profile is evidence; an ordinary E-AC-3 codec name is not. */
internal fun observedCodecName(codec: String?, profile: String?): String? =
    if (codec == "eac3" && profile?.let { Regex("atmos|\\bjoc\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true) "eac3-joc" else codec

/**
 * The engine's stream format as the player's quality badge, format line and stats panel read it.
 *
 * [DesktopStreamFormat.kbps] is either the decoder's own figure (FFmpeg's codec or container bit rate) or what the
 * source declared for the stream before it opened, so it is the encoded bitrate and carries
 * [TelemetryProvenance.AUTHORITATIVE] — except for uncompressed PCM, where FFmpeg's "bit rate" is the sample stream
 * itself. That one is PCM throughput, kept apart from encoded bitrate as the phone does ([TelemetryProvenance.DERIVED]
 * plus [NerdStats.Snapshot.pcmDataRateKbps]), so the format line never calls it a bitrate.
 */
internal fun desktopNerdSnapshot(format: DesktopStreamFormat?, sourceId: String?, offer: DesktopStreamFormat? = null, sourceName: String? = sourceId): NerdStats.Snapshot? {
    format ?: return null
    val codec = format.codec?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val rawPcm = codec != null && (codec.startsWith("pcm_") || codec == "raw")
    val kbps = format.kbps?.takeIf { it > 0 }
    return NerdStats.Snapshot(
        mimeType = codec?.let { if (rawPcm) "audio/raw" else "audio/$it" },
        bitrateKbps = kbps,
        sampleRateHz = format.sampleRateHz,
        channels = format.channels,
        bitDepth = format.bitDepth,
        sourceName = sourceName,
        sourceId = sourceId,
        claimed = offer?.let { com.cosmotune.music.data.sources.StreamFormat(it.codec, it.kbps, it.sampleRateHz, it.bitDepth) },
        bitrateProvenance = when {
            kbps == null -> TelemetryProvenance.UNKNOWN
            rawPcm -> TelemetryProvenance.DERIVED
            else -> TelemetryProvenance.AUTHORITATIVE
        },
        pcmDataRateKbps = kbps?.takeIf { rawPcm },
    )
}
