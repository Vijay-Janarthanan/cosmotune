package com.cosmotune.music.playback.cast

/**
 * The two hand-overs of a Cast session, as decisions with no Cast or player types in them, so the
 * rules can be tested with plain values: what the receiver is given when a session starts, and
 * where the phone picks up when it ends ("return to device").
 */
object CastHandover {

    /** What the phone was doing at the moment a session began. */
    data class LocalState(
        val currentMediaId: String?,
        val positionMs: Long,
        /** Playing, or about to: play-when-ready and not ended. */
        val playing: Boolean,
    )

    sealed interface Start {
        /** The receiver is already on the phone's track (a session that outlived the app): leave it playing. */
        data object KeepReceiver : Start

        /** Load the phone's track on the receiver at [positionMs], playing only if the phone was. */
        data class Load(val mediaId: String?, val positionMs: Long, val play: Boolean) : Start
    }

    fun onStart(local: LocalState, resumed: Boolean, receiverMediaId: String?): Start =
        if (resumed && local.currentMediaId != null && local.currentMediaId == receiverMediaId) {
            Start.KeepReceiver
        } else {
            Start.Load(local.currentMediaId, local.positionMs.coerceAtLeast(0L), local.playing)
        }

    /** What the receiver was doing as its session ended. */
    data class ReceiverEnd(
        val mediaId: String?,
        val positionMs: Long,
        val playing: Boolean,
    )

    /**
     * Where the phone resumes. [queueIndex] is the row holding the receiver's last track, the one
     * nearest the phone's current row when the track is queued twice, or null when the track is
     * not in the phone's queue (then only the position is restored on the current row).
     * [play] is true only when the listener chose this phone and the receiver was playing: a
     * receiver switched off or a dropped Wi-Fi must not make the phone start playing aloud.
     */
    data class Return(val queueIndex: Int?, val positionMs: Long, val play: Boolean)

    fun onEnd(
        end: ReceiverEnd,
        localQueue: List<String>,
        localCurrentIndex: Int,
        listenerChoseThisDevice: Boolean,
    ): Return {
        val index = nearestIndexOf(end.mediaId, localQueue, localCurrentIndex)
        return Return(
            queueIndex = index,
            positionMs = end.positionMs.coerceAtLeast(0L),
            play = listenerChoseThisDevice && end.playing,
        )
    }

    /** The row of [mediaId] nearest [current]; null when it is absent. */
    fun nearestIndexOf(mediaId: String?, queue: List<String>, current: Int): Int? {
        if (mediaId == null) return null
        val at = current.coerceAtLeast(0)
        return queue.indices.filter { queue[it] == mediaId }.minByOrNull { kotlin.math.abs(it - at) }
    }
}

/**
 * Whether a stream can play on a Cast receiver at all, decided before it is sent rather than
 * after the receiver fails to open it.
 *
 * Google's supported-media list for Cast receivers (developers.google.com/cast/docs/media,
 * read 2026-10-24): FLAC up to 96 kHz / 24-bit, HE-AAC and LC-AAC, MP3, Opus, Vorbis, WAV (LPCM)
 * and WebM, in MP2T, MP3, MP4, OGG, WAV and WebM containers. ALAC, DSD, AIFF, APE and WavPack are
 * not on it, so those are refused with the "cannot be cast" message instead of sent and left silent.
 */
object CastFormatSupport {

    sealed interface Verdict {
        data class Supported(val mimeType: String) : Verdict
        data class Unsupported(val reason: String) : Verdict
    }

    private val UNSUPPORTED_EXTENSIONS = mapOf(
        "dsf" to "DSD", "dff" to "DSD", "aif" to "AIFF", "aiff" to "AIFF", "aifc" to "AIFF",
        "ape" to "APE", "wv" to "WavPack", "alac" to "ALAC", "tta" to "TTA", "wma" to "WMA",
    )

    private val UNSUPPORTED_MIME = mapOf(
        "audio/alac" to "ALAC", "audio/x-alac" to "ALAC", "audio/dsd" to "DSD", "audio/x-dsf" to "DSD",
        "audio/x-dff" to "DSD", "audio/aiff" to "AIFF", "audio/x-aiff" to "AIFF", "audio/ape" to "APE",
        "audio/x-ape" to "APE", "audio/x-wavpack" to "WavPack", "audio/x-ms-wma" to "WMA",
    )

    /**
     * [mimeType] is what the stream was labelled (possibly a guess), [extension] the URL path's
     * extension without the dot, [codec] the codec when the source knows it (e.g. "alac" inside an
     * .m4a, which the container name alone hides), [sampleRateHz] for FLAC's 96 kHz ceiling.
     */
    fun check(mimeType: String, extension: String?, codec: String? = null, sampleRateHz: Int? = null): Verdict {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        val ext = extension?.lowercase()
        UNSUPPORTED_MIME[mime]?.let { return Verdict.Unsupported("$it is not a Cast receiver format") }
        ext?.let(UNSUPPORTED_EXTENSIONS::get)?.let { return Verdict.Unsupported("$it is not a Cast receiver format") }
        val codecName = codec?.lowercase()
        if (codecName != null && (codecName == "alac" || codecName.startsWith("dsd"))) {
            return Verdict.Unsupported("${if (codecName == "alac") "ALAC" else "DSD"} is not a Cast receiver format")
        }
        val flac = mime == "audio/flac" || mime == "audio/x-flac" || ext == "flac"
        if (flac && sampleRateHz != null && sampleRateHz > 96_000) {
            return Verdict.Unsupported("FLAC above 96 kHz is not a Cast receiver format")
        }
        return Verdict.Supported(mime)
    }
}
