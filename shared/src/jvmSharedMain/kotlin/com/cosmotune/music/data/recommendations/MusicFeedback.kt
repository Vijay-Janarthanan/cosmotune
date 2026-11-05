package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class MusicFeedback { LIKE, LESS_LIKE_THIS, CLEAR }

/** Only SHA-256 recording fingerprints persist; never a provider session, title, account or listening log. */
internal class ExplicitMusicFeedback {
    private val values = LinkedHashMap<String, MusicFeedback>()
    @Synchronized fun update(song: Song, feedback: MusicFeedback): Boolean {
        val key = fingerprint(song)
        val before = values[key]
        if (feedback == MusicFeedback.CLEAR) values.remove(key) else values[key] = feedback
        while (values.size > LIMIT) values.remove(values.keys.first())
        return before != values[key]
    }
    @Synchronized fun value(song: Song): MusicFeedback? = values[fingerprint(song)]
    @Synchronized fun encode(): ByteArray = Json.encodeToString(values).toByteArray(StandardCharsets.UTF_8)
    companion object {
        const val LIMIT = 2_048
        const val MAX_BYTES = 256 * 1024
        private val hex = "0123456789abcdef"
        private fun fingerprint(song: Song): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(MusicRecordingIdentity.key(song).toByteArray(StandardCharsets.UTF_8))
            return buildString(64) { bytes.forEach { byte -> val value = byte.toInt() and 255; append(hex[value ushr 4]); append(hex[value and 15]) } }
        }
        fun decode(bytes: ByteArray): ExplicitMusicFeedback {
            require(bytes.size <= MAX_BYTES)
            val map = Json.decodeFromString<Map<String, MusicFeedback>>(bytes.toString(StandardCharsets.UTF_8))
            require(map.size <= LIMIT && map.keys.all { it.matches(Regex("[a-f0-9]{64}")) } && map.values.none { it == MusicFeedback.CLEAR })
            return ExplicitMusicFeedback().apply { values.putAll(map) }
        }
    }
}
