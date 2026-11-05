package com.cosmotune.providers

/** Format fields are provider declarations; the native player's decoder remains authoritative. */
data class ProviderAudioStream(val url: String, val sourceName: String, val codec: String? = null,
    val kbps: Int? = null, val durationMs: Long? = null, val container: String? = null,
    val sampleRateHz: Int? = null, val bitDepth: Int? = null)

object ProviderAudioQuality {
    fun choose(options: List<ProviderQuality>, maxKbps: Int? = null, lossless: Boolean = false): String {
        require(maxKbps == null || maxKbps > 0)
        if (options.isEmpty()) return if (maxKbps != null) error("Provider does not declare capped audio qualities") else if (lossless) "LOSSLESS" else "HIGH"
        fun isLossless(option: ProviderQuality) = option.kind.equals("lossless", true) || Regex("lossless|flac|alac|hi[_ -]?res", RegexOption.IGNORE_CASE).containsMatchIn(option.id)
        val lossy = options.filterNot(::isLossless).mapNotNull { option -> bitrate(option.id + " " + option.label)?.let { option to it } }
        if (maxKbps != null) return lossy.filter { it.second <= maxKbps }.maxByOrNull { it.second }?.first?.id
            ?: error("Provider has no declared quality within this connection's limit")
        if (lossless) options.filter(::isLossless).maxByOrNull {
            if (Regex("hi[_ -]?res|24.?bit|192|96", RegexOption.IGNORE_CASE).containsMatchIn(it.id + " " + it.label)) 1 else 0
        }?.let { return it.id }
        return lossy.maxByOrNull { it.second }?.first?.id ?: options.firstOrNull {
            !isLossless(it) && !Regex("atmos|joc", RegexOption.IGNORE_CASE).containsMatchIn(it.id + " " + it.label)
        }?.id ?: error("Provider has no declared lossy quality")
    }
    fun bitrate(text: String): Int? = Regex("(?i)(?:aac|mp3|opus)[_ -]?([0-9]{1,4})|([0-9]{1,4})\\s*kbps").find(text)?.let {
        it.groupValues.drop(1).firstNotNullOfOrNull { part -> part.toIntOrNull() }
    }?.takeIf { it in 1..2_000 }
}
