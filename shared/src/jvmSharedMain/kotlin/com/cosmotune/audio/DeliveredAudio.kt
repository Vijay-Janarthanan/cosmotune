package com.cosmotune.audio

import java.io.File

/**
 * What the first bytes of a delivered stream or saved file prove about its encoding (MN-03).
 *
 * A provider's `format: "flac"`, a `.flac` suffix or a `LOSSLESS` tier is a claim; this reads the
 * container and, where the probe window holds it, the codec of the bytes that actually arrived.
 * [lossless] is null when the bytes do not settle it (an MP4 whose `moov` box lies beyond the probe,
 * an AIFC with an unknown compression). Even a verified lossless container does not prove a lossless
 * master: an upsampled lossy source is the spectral check's job (`LibraryAudioAnalysis.possibleLossySource`).
 */
data class DeliveredAudio(
    val container: String,
    val codec: String? = null,
    val lossless: Boolean? = null,
    val sampleRate: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: Int? = null,
    /** False for an HTML/JSON/text body served where audio was promised. */
    val audio: Boolean = true,
) {
    val hiRes: Boolean get() = lossless == true && ((sampleRate ?: 0) > 48_000 || (bitsPerSample ?: 0) > 16)

    companion object {
        /** Enough for FLAC/WAV/Ogg headers, ID3 skips and a front-loaded MP4 `moov`. */
        const val PROBE_BYTES = 256 * 1024

        fun inspect(file: File): DeliveredAudio? = runCatching {
            val head = file.inputStream().use { input ->
                val buffer = ByteArray(PROBE_BYTES); var count = 0
                while (count < buffer.size) { val read = input.read(buffer, count, buffer.size - count); if (read < 0) break; count += read }
                buffer.copyOf(count)
            }
            val found = inspect(head)
            if (found?.container == "mp4" && found.codec == null) trailingMovie(file, head) ?: found else found
        }.getOrNull()

        /** A saved MP4 may keep `moov` after the media data; walk the top-level boxes to read it (at most 4 MiB). */
        private fun trailingMovie(file: File, head: ByteArray): DeliveredAudio? = java.io.RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            repeat(64) {
                if (offset + 8 > raf.length()) return null
                raf.seek(offset)
                var size = raf.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
                if (size == 1L) size = raf.readLong()
                if (size == 0L) size = raf.length() - offset
                if (size < 8) return null
                if (type == "moov") {
                    val length = minOf(size, 4L * 1024 * 1024).toInt()
                    val movie = ByteArray(length); raf.seek(offset); raf.readFully(movie)
                    // Re-use the in-window parser: the file's own ftyp header followed by the movie box.
                    return inspect(head.copyOf(minOf(head.size, 8)) + movie)?.takeIf { it.codec != null }
                }
                offset += size
            }
            null
        }

        fun inspect(bytes: ByteArray): DeliveredAudio? {
            fun u8(i: Int) = bytes[i].toInt() and 255
            fun at(offset: Int, text: String) = offset >= 0 && offset + text.length <= bytes.size &&
                text.indices.all { u8(offset + it) == text[it].code }
            fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun be32(i: Int) = (be16(i).toLong() shl 16) or be16(i + 2).toLong()
            fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun le32(i: Int) = le16(i).toLong() or (le16(i + 2).toLong() shl 16)
            if (bytes.size < 4) return null
            return when {
                at(0, "fLaC") -> flac(bytes, 4, "flac")
                at(0, "RIFF") && at(8, "WAVE") -> wav(bytes, ::le16, ::le32)
                at(0, "FORM") && at(8, "AIFF") -> DeliveredAudio("aiff", "pcm", true)
                at(0, "FORM") && at(8, "AIFC") -> DeliveredAudio("aiff", null, null)
                at(0, "DSD ") -> DeliveredAudio("dsf", "dsd", true)
                at(0, "FRM8") && at(12, "DSD ") -> DeliveredAudio("dff", "dsd", true)
                at(0, "OggS") -> ogg(bytes, ::u8)
                at(4, "ftyp") -> mp4(bytes, ::at, ::be16, ::be32)
                at(0, "ID3") && bytes.size >= 10 -> {
                    val size = (u8(6) shl 21) or (u8(7) shl 14) or (u8(8) shl 7) or u8(9)
                    val frame = 10 + size + if ((u8(5) and 0x10) != 0) 10 else 0
                    when {
                        at(frame, "fLaC") -> flac(bytes, frame + 4, "flac")
                        frame + 1 < bytes.size -> mpegFrame(u8(frame), u8(frame + 1)) ?: DeliveredAudio("mp3", null, null)
                        else -> DeliveredAudio("mp3", null, null)
                    }
                }
                else -> mpegFrame(u8(0), u8(1)) ?: text(bytes)
            }
        }

        private fun flac(bytes: ByteArray, block: Int, container: String): DeliveredAudio {
            // STREAMINFO is always the first metadata block: 20-bit rate, 3-bit channels-1, 5-bit depth-1.
            val info = block + 4
            if (info + 18 > bytes.size || (bytes[block].toInt() and 0x7f) != 0) return DeliveredAudio(container, "flac", true)
            fun u8(i: Int) = bytes[i].toInt() and 255
            val rate = (u8(info + 10) shl 12) or (u8(info + 11) shl 4) or (u8(info + 12) shr 4)
            val channels = ((u8(info + 12) shr 1) and 7) + 1
            val depth = (((u8(info + 12) and 1) shl 4) or (u8(info + 13) shr 4)) + 1
            return DeliveredAudio(container, "flac", true, rate.takeIf { it > 0 }, depth, channels)
        }

        private fun wav(bytes: ByteArray, le16: (Int) -> Int, le32: (Int) -> Long): DeliveredAudio {
            var chunk = 12
            while (chunk + 8 <= bytes.size) {
                val id = String(bytes, chunk, 4, Charsets.US_ASCII)
                val size = le32(chunk + 4)
                if (id == "fmt " && chunk + 24 <= bytes.size) {
                    val tag = le16(chunk + 8)
                    val channels = le16(chunk + 10); val rate = le32(chunk + 12).toInt(); val bits = le16(chunk + 22)
                    // PCM, IEEE float and WAVE_FORMAT_EXTENSIBLE carry samples; 0x55 is MP3, 0xFF AAC and so on.
                    val pcm = tag == 1 || tag == 3 || tag == 0xFFFE
                    return DeliveredAudio("wav", if (pcm) "pcm" else "wav-0x%04x".format(tag), pcm, rate, bits.takeIf { pcm }, channels)
                }
                if (size < 0 || size > Int.MAX_VALUE - chunk - 9) break
                chunk += 8 + size.toInt() + (size.toInt() and 1)
            }
            return DeliveredAudio("wav", null, null)
        }

        private fun ogg(bytes: ByteArray, u8: (Int) -> Int): DeliveredAudio {
            if (bytes.size < 27) return DeliveredAudio("ogg", null, null)
            val payload = 27 + u8(26)
            fun at(offset: Int, text: String) = offset + text.length <= bytes.size && text.indices.all { u8(offset + it) == text[it].code }
            return when {
                at(payload, "OpusHead") -> DeliveredAudio("ogg", "opus", false, 48_000, channels = if (payload + 9 < bytes.size) u8(payload + 9) else null)
                at(payload, "\u0001vorbis") -> DeliveredAudio("ogg", "vorbis", false)
                // Ogg FLAC: 0x7F "FLAC", version, header count, then the native "fLaC" and STREAMINFO block.
                at(payload, "\u007fFLAC") && at(payload + 9, "fLaC") -> flac(bytes, payload + 13, "ogg")
                else -> DeliveredAudio("ogg", null, null)
            }
        }

        private fun mp4(bytes: ByteArray, at: (Int, String) -> Boolean, be16: (Int) -> Int, be32: (Int) -> Long): DeliveredAudio {
            // The first audio sample description names the codec; it is only readable when `moov` is in the window.
            var i = 8
            while (i + 24 < bytes.size) {
                if (at(i, "stsd")) {
                    val entry = i + 12
                    if (entry + 36 > bytes.size) break
                    val type = String(bytes, entry + 4, 4, Charsets.ISO_8859_1)
                    val channels = be16(entry + 24); val bits = be16(entry + 26); val rate = (be32(entry + 32) ushr 16).toInt()
                    // The 16.16 field cannot hold rates above 65,535 Hz; ALAC's own cookie box states the real format.
                    val cookie = entry + 36
                    if (type == "alac" && cookie + 36 <= bytes.size && at(cookie + 4, "alac")) {
                        val cookieRate = be32(cookie + 32).toInt()
                        return DeliveredAudio("mp4", "alac", true, cookieRate.takeIf { it > 0 } ?: rate,
                            bytes[cookie + 17].toInt() and 255, bytes[cookie + 21].toInt() and 255)
                    }
                    return when (type) {
                        "alac" -> DeliveredAudio("mp4", "alac", true, rate, bits, channels)
                        "fLaC" -> DeliveredAudio("mp4", "flac", true, rate, bits, channels)
                        "mp4a" -> DeliveredAudio("mp4", "aac", false, rate, channels = channels)
                        "ec-3", "ac-3" -> DeliveredAudio("mp4", if (type == "ec-3") "eac3" else "ac3", false, rate, channels = channels)
                        "Opus" -> DeliveredAudio("mp4", "opus", false, rate, channels = channels)
                        else -> DeliveredAudio("mp4", type.trim().lowercase(), null, rate, channels = channels)
                    }
                }
                i++
            }
            return DeliveredAudio("mp4", null, null)
        }

        /** An MPEG audio frame (MP3) or an ADTS AAC frame from its two sync bytes. */
        private fun mpegFrame(first: Int, second: Int): DeliveredAudio? {
            if (first != 0xFF || (second and 0xE0) != 0xE0) return null
            val layer = (second shr 1) and 3
            return when {
                (second and 0xF6) == 0xF0 -> DeliveredAudio("adts", "aac", false)
                layer == 1 -> DeliveredAudio("mp3", "mp3", false)
                layer != 0 -> DeliveredAudio("mpeg", "mp${4 - layer}", false)
                else -> null
            }
        }

        private fun text(bytes: ByteArray): DeliveredAudio? {
            val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1).trimStart().lowercase()
            return if (head.startsWith("<") || head.startsWith("{") || head.startsWith("[") || head.startsWith("#extm3u"))
                DeliveredAudio("text", null, false, audio = false) else null
        }
    }
}

/** How a quality claim compares with what arrived. */
enum class QualityEvidence { VERIFIED_LOSSLESS, LOSSY_DESPITE_CLAIM, LOSSY, CLAIMED_UNVERIFIED, NOT_AUDIO, UNKNOWN }

/**
 * The label Cosmotune may show for a recording: measured facts first, a claim only as a claim.
 * [sourceQuality] is the value to store on a saved song (`FLAC`/`ALAC`/`WAV`/`DSD` draw the lossless
 * badge; null draws none), so a provider that promised FLAC and sent AAC never wears a lossless badge.
 */
data class HonestQuality(val evidence: QualityEvidence, val label: String, val sourceQuality: String?, val hiRes: Boolean)

fun honestQuality(claimedLossless: Boolean, delivered: DeliveredAudio?): HonestQuality {
    if (delivered != null && !delivered.audio) return HonestQuality(QualityEvidence.NOT_AUDIO, "Not audio", null, false)
    val codec = delivered?.codec?.uppercase()
    val detail = listOfNotNull(
        delivered?.bitsPerSample?.let { "$it-bit" },
        delivered?.sampleRate?.let { rate -> if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(java.util.Locale.ROOT, rate / 1000.0) },
    ).joinToString(" ")
    return when (delivered?.lossless) {
        true -> {
            val stored = when (delivered.codec) { "flac" -> "FLAC"; "alac" -> "ALAC"; "pcm" -> "WAV"; "dsd" -> "DSD"; else -> "LOSSLESS" }
            HonestQuality(QualityEvidence.VERIFIED_LOSSLESS, listOf(stored, detail).filter { it.isNotBlank() }.joinToString(" "), stored, delivered.hiRes)
        }
        false -> if (claimedLossless) HonestQuality(QualityEvidence.LOSSY_DESPITE_CLAIM, "${codec ?: "Lossy"} (offered as lossless)", null, false)
            else HonestQuality(QualityEvidence.LOSSY, codec ?: "Lossy", null, false)
        null -> if (claimedLossless) HonestQuality(QualityEvidence.CLAIMED_UNVERIFIED, "Lossless (not verified)", null, false)
            else HonestQuality(QualityEvidence.UNKNOWN, codec ?: delivered?.container?.uppercase() ?: "Unknown", null, false)
    }
}

/** Labels that draw a lossless badge; a saved download may only keep one when its bytes prove it. */
val LOSSLESS_LABELS = setOf("LOSSLESS", "HI-RES", "HIRES", "FLAC", "ALAC", "WAV", "DSD")

/**
 * The format label a finished download may carry, from the bytes on disk: a verified lossless file
 * names its codec; a lossless claim over lossy bytes names the codec that arrived; an unverified
 * lossless claim is dropped; other labels (a Dolby or YouTube bitrate badge) are kept. Throws when
 * the "audio" is a web page or JSON error, so the caller aborts instead of saving it.
 */
fun verifiedDownloadLabel(claimed: String?, delivered: DeliveredAudio?,
                          claimedLossless: Boolean = claimed?.uppercase() in LOSSLESS_LABELS): String? {
    val honest = honestQuality(claimedLossless, delivered)
    return when (honest.evidence) {
        QualityEvidence.NOT_AUDIO -> throw IllegalStateException("The source sent a web page instead of audio")
        QualityEvidence.VERIFIED_LOSSLESS -> honest.sourceQuality
        QualityEvidence.LOSSY_DESPITE_CLAIM -> delivered?.codec?.uppercase()
        QualityEvidence.LOSSY, QualityEvidence.CLAIMED_UNVERIFIED, QualityEvidence.UNKNOWN ->
            claimed?.takeUnless { it.uppercase() in LOSSLESS_LABELS } ?: if (honest.evidence == QualityEvidence.LOSSY) delivered?.codec?.uppercase() else null
    }
}
