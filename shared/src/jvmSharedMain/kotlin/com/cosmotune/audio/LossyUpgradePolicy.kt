package com.cosmotune.audio

/** Bitrate is comparable within a codec, not a quality score across codecs or masters. */
object LossyUpgradePolicy {
    fun worthReplacing(candidateCodec: String?, candidateKbps: Int?, playingCodec: String?, playingKbps: Int?, minimumGainKbps: Int = 96): Boolean {
        val candidate = codecFamily(candidateCodec) ?: return false
        val playing = codecFamily(playingCodec) ?: return false
        if (candidate != playing || candidateKbps == null || playingKbps == null || playingKbps <= 0) return false
        return candidateKbps.toLong() - playingKbps >= minimumGainKbps.coerceAtLeast(1)
    }

    private fun codecFamily(value: String?): String? {
        val codec = value?.lowercase(java.util.Locale.ROOT)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if ("opus" in codec) return "opus"
        if ("vorbis" in codec) return "vorbis"
        val name = codec.substringBefore(';').substringAfterLast('/').trim()
        return when (name) {
            "aac", "mp4a", "mp4a-latm", "mp4", "m4a" -> "aac"
            "mp3", "mpeg" -> "mp3"
            "opus", "vorbis" -> name
            else -> null
        }
    }
}
