package com.cosmotune.listening

import java.util.Locale

/**
 * The ids a tagger (MusicBrainz Picard, beets, foobar2000's tagger) wrote into a song file: the MusicBrainz recording id, the
 * AcoustID fingerprint id and the ISRC. They name the recording exactly, so the song's details are looked up by them instead of by
 * its title. Read by [LocalTagEditor.identifiers]; anything malformed is left out.
 */
data class TrackIdentifiers(val recordingId: String? = null, val acoustId: String? = null, val isrc: String? = null) {
    val isEmpty: Boolean get() = recordingId == null && acoustId == null && isrc == null

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")

        fun uuid(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)?.takeIf(UUID::matches)
        fun isrc(value: String?): String? = value?.replace("-", "")?.trim()?.uppercase(Locale.ROOT)?.takeIf(ISRC::matches)

        /** From Vorbis comments (FLAC, Ogg, Opus): `MUSICBRAINZ_TRACKID`, `ACOUSTID_ID`, `ISRC`. */
        fun fromVorbis(comments: List<String>): TrackIdentifiers {
            val all = comments.filter { '=' in it }.groupBy({ it.substringBefore('=').trim().uppercase(Locale.ROOT) }, { it.substringAfter('=') })
            fun first(key: String) = all[key]?.firstOrNull()
            return TrackIdentifiers(uuid(first("MUSICBRAINZ_TRACKID")), uuid(first("ACOUSTID_ID")), isrc(first("ISRC")))
        }

        /**
         * From ID3v2 frames, each its id and body: the recording id in the `UFID` frame owned by `http://musicbrainz.org`, the
         * AcoustID in the `TXXX` frame described `Acoustid Id`, the ISRC in `TSRC`.
         */
        fun fromId3(frames: List<Pair<String, ByteArray>>): TrackIdentifiers {
            var recording: String? = null
            var acoust: String? = null
            var code: String? = null
            for ((id, body) in frames) when (id) {
                "UFID" -> {
                    val end = body.indexOf(0)
                    if (end > 0 && String(body, 0, end, Charsets.ISO_8859_1) == "http://musicbrainz.org") {
                        recording = recording ?: uuid(String(body, end + 1, body.size - end - 1, Charsets.ISO_8859_1))
                    }
                }
                "TXXX" -> {
                    val parts = id3Strings(body)
                    if (parts.size >= 2 && parts[0].equals("Acoustid Id", ignoreCase = true)) acoust = acoust ?: uuid(parts[1])
                    if (parts.size >= 2 && parts[0].equals("MusicBrainz Track Id", ignoreCase = true) && recording == null) recording = uuid(parts[1])
                }
                "TSRC" -> code = code ?: isrc(id3Strings(body).firstOrNull())
            }
            return TrackIdentifiers(recording, acoust, code)
        }

        /** From MP4 freeform atoms (`----:com.apple.iTunes:<name>`), keyed by their upper-cased name. */
        fun fromFreeform(items: Map<String, String>): TrackIdentifiers =
            TrackIdentifiers(uuid(items["MUSICBRAINZ TRACK ID"]), uuid(items["ACOUSTID ID"]), isrc(items["ISRC"]))

        /** The strings of an ID3 text-like frame body, split on its encoding's terminator. */
        internal fun id3Strings(body: ByteArray): List<String> {
            if (body.isEmpty()) return emptyList()
            val encoding = body[0].toInt()
            val wide = encoding == 1 || encoding == 2
            val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
            val out = ArrayList<String>()
            var start = 1
            var at = 1
            val step = if (wide) 2 else 1
            while (at + step <= body.size) {
                val zero = if (wide) body[at] == 0.toByte() && body[at + 1] == 0.toByte() else body[at] == 0.toByte()
                if (zero) { out += String(body, start, at - start, charset); start = at + step }
                at += step
            }
            if (start < body.size) out += String(body, start, body.size - start, charset).trimEnd('\u0000')
            return out
        }

        private fun ByteArray.indexOf(value: Int): Int { for (i in indices) if (this[i].toInt() == value) return i; return -1 }
    }
}
