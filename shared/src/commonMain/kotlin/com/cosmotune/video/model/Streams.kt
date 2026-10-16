package com.cosmotune.video.model

/** A video codec the selector chooses between (spec 6.4: AV1 or VP9 in hardware, else H.264). */
enum class VideoCodec { H264, VP9, AV1, OTHER }

/** An audio codec (spec 6.4: Opus over AAC at a similar bitrate where the platform decodes Opus). */
enum class AudioCodec { AAC, OPUS, OTHER }

/** How a stream is delivered (spec 6.2): a progressive file, a DASH representation with ranges, or an HLS playlist. */
enum class Delivery { PROGRESSIVE, DASH, HLS }

/** What an audio track is (spec 6.4: "the original track by default"): original, dubbed, descriptive, a secondary track. */
enum class AudioTrackKind { ORIGINAL, DUBBED, DESCRIPTIVE, SECONDARY, UNKNOWN }

/** A byte range of a stream, both ends included (spec 6.4: the `SegmentBase` init and index ranges of the DASH manifest). */
data class ByteRange(val start: Long, val endInclusive: Long)

/**
 * One video stream the source offers (spec 6.4). [codecs] is the RFC 6381 string ("avc1.640028"); [init] and [index] are
 * the DASH ranges (null for streams without them, which fall back to progressive sources, spec 6.2); [hasAudio] is true
 * for a muxed stream.
 */
data class VideoStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codec: VideoCodec,
    val codecs: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val hdr: Boolean,
    val contentLength: Long?,
    val init: ByteRange?,
    val index: ByteRange?,
    val delivery: Delivery,
    val hasAudio: Boolean,
)

/**
 * One audio stream the source offers (spec 6.4): its codec and bitrate, its DASH ranges, and the track it belongs to
 * ([trackId], [trackName], [language], [trackKind]) for the audio-language choice.
 */
data class AudioStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codec: AudioCodec,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val channels: Int?,
    val contentLength: Long?,
    val init: ByteRange?,
    val index: ByteRange?,
    val delivery: Delivery,
    val trackId: String?,
    val trackName: String?,
    val language: String?,
    val trackKind: AudioTrackKind,
)

/**
 * Every stream of one video (spec 4.2, 6.4): video-only, muxed and audio streams, the HLS and DASH addresses when the
 * source gives them (live), when the addresses stop working ([expiresAtMs]), the headers the requests must carry (the
 * User-Agent the set was minted for, spec 6.2) and the duration. Never cached: the controller asks for a fresh set on a
 * `SourceExpired` (spec 6.1).
 */
data class StreamSet(
    val videoOnly: List<VideoStream>,
    val muxed: List<VideoStream>,
    val audio: List<AudioStream>,
    val hlsUrl: String?,
    val dashMpdUrl: String?,
    val expiresAtMs: Long?,
    val requestHeaders: Map<String, String>,
    val durationMs: Long?,
) {
    /**
     * Whether the addresses are about to stop working at [nowMs]: true from [EXPIRY_MARGIN_MS] (2 minutes) before
     * [expiresAtMs], so a fresh set is fetched before a request fails (spec 6.1). A set with no expiry never expires.
     */
    fun isExpired(nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs - EXPIRY_MARGIN_MS

    companion object {
        /** The margin before [expiresAtMs] at which a stream set counts as expired: 2 minutes. */
        const val EXPIRY_MARGIN_MS = 2 * 60_000L
    }
}
