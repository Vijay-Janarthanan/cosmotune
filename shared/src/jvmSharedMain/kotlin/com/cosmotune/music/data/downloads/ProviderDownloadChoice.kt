package com.cosmotune.music.data.downloads

import java.util.Base64
import java.security.MessageDigest

/** Exact manifest option. Kept separate from the generic HIGH/LOSSLESS policy. */
data class ProviderDownloadChoice(val provider: String, val qualityId: String) {
    init {
        require(provider.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}")))
        require(qualityId.isNotBlank() && qualityId.length <= 200 && qualityId.none(Char::isISOControl))
    }
    fun encode() = "PROVIDER:$provider:" + Base64.getUrlEncoder().withoutPadding().encodeToString(qualityId.toByteArray(Charsets.UTF_8))
    companion object {
        fun parse(value: String?): ProviderDownloadChoice? = runCatching {
            val fields = requireNotNull(value).split(':', limit = 3)
            require(fields.size == 3 && fields[0] == "PROVIDER" && value.length <= 500)
            ProviderDownloadChoice(fields[1], String(Base64.getUrlDecoder().decode(fields[2]), Charsets.UTF_8))
                .also { require(it.encode() == value) }
        }.getOrNull()
        fun jobId(trackId: String, quality: String): String = if (parse(quality) == null) trackId else "$trackId#download=${digest(quality)}"
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
