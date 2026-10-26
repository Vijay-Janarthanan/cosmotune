package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Small online logistic ranker. Only hashed weights persist; no song IDs or listening log. */
internal class OnlinePreferenceModel {
    private val weights = DoubleArray(FEATURES)
    private val gradients = DoubleArray(FEATURES)
    var observations = 0
        private set
    var enabled = true
        private set
    private var updatedDay = 0L

    @Synchronized fun setEnabled(value: Boolean) { enabled = value }

    @Synchronized fun reset() {
        weights.fill(0.0); gradients.fill(0.0); observations = 0; updatedDay = 0
    }

    @Synchronized fun adjustment(previous: Song?, song: Song, hour: Int, day: Long, work: RankingWork? = null): Float {
        if (!enabled || observations < 5) return 0f
        decay(day, work)
        val strength = (observations / 40.0).coerceAtMost(1.0)
        val indices = features(previous, song, hour)
        work?.let { it.preferenceFeatures += indices.size }
        return ((probability(indices) - .5) * .3 * strength).toFloat()
    }

    @Synchronized fun learn(previous: Song?, song: Song, liked: Boolean, hour: Int, day: Long) {
        if (!enabled) return
        decay(day)
        val values = features(previous, song, hour)
        val error = probability(values) - if (liked) 1.0 else 0.0
        val scale = 1.0 / sqrt(values.size.toDouble())
        for (index in values) {
            val gradient = error * scale + .001 * weights[index]
            gradients[index] = (gradients[index] + gradient * gradient).coerceAtMost(1e12)
            weights[index] = (weights[index] - .2 * gradient / sqrt(gradients[index] + 1e-6)).coerceIn(-16.0, 16.0)
        }
        observations = (observations + 1).coerceAtMost(1_000_000_000)
    }

    private fun decay(day: Long, work: RankingWork? = null) {
        if (updatedDay == 0L) { updatedDay = day; return }
        if (day <= updatedDay) return
        work?.let { it.decayedValues += 2L * FEATURES }
        val factor = .98.pow((day - updatedDay).coerceAtMost(3650).toDouble())
        for (i in weights.indices) { weights[i] *= factor; gradients[i] *= factor }
        updatedDay = day
    }

    private fun probability(indices: IntArray): Double {
        val value = indices.sumOf { weights[it] } / sqrt(indices.size.toDouble())
        return 1.0 / (1.0 + exp(-value.coerceIn(-30.0, 30.0)))
    }

    private fun features(previous: Song?, song: Song, hour: Int): IntArray {
        // Structured credits take priority. Keep punctuation intact when only a display credit exists.
        fun artist(track: Song) = track.artists.firstOrNull()?.let { it.browseId ?: it.name }
            ?: track.artistId ?: track.artist
        val artist = RecommendationModel.normalize(artist(song))
        val album = RecommendationModel.normalize(song.albumName.orEmpty())
        val duration = song.durationMillis().takeIf { it > 0 }?.div(60_000)?.coerceAtMost(20)
        val keys = mutableListOf("bias", "artist:$artist", "recording:$artist:${RecommendationModel.titleKey(song.title)}",
            "hour:${hour.coerceIn(0, 23) / 4}", "artist-hour:$artist:${hour.coerceIn(0, 23) / 4}")
        if (album.isNotEmpty()) keys += "album:$artist:$album"
        duration?.let { keys += "duration:$it" }
        previous?.let { keys += "transition:${RecommendationModel.normalize(artist(it))}:$artist" }
        return keys.map { key ->
            var hash = 0x811c9dc5.toInt()
            for (byte in key.toByteArray(Charsets.UTF_8)) hash = (hash xor (byte.toInt() and 255)) * 16777619
            hash and (FEATURES - 1)
        }.distinct().toIntArray()
    }

    @Synchronized fun encode(): ByteArray {
        val bytes = ByteArray(STATE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC).putInt(1).putLong(updatedDay).putInt(observations).putInt(if (enabled) 1 else 0)
        weights.forEach(buffer::putDouble); gradients.forEach(buffer::putDouble)
        buffer.putInt(CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
        return bytes
    }

    companion object {
        const val FEATURES = 4096
        const val STATE_BYTES = 28 + FEATURES * 16 + 4
        private val MAGIC = "CTPREF01".toByteArray()

        fun decode(bytes: ByteArray): OnlinePreferenceModel {
            require(bytes.size == STATE_BYTES) { "Invalid preference state size" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8).also(buffer::get)
            require(magic.contentEquals(MAGIC) && buffer.int == 1)
            val day = buffer.long
            val count = buffer.int
            val enabled = buffer.int
            require(day in 0..10_000_000 && count in 0..1_000_000_000 && enabled in 0..1)
            val model = OnlinePreferenceModel()
            model.updatedDay = day; model.observations = count; model.enabled = enabled == 1
            for (i in model.weights.indices) model.weights[i] = buffer.double.also { require(it.isFinite() && it in -16.0..16.0) }
            for (i in model.gradients.indices) model.gradients[i] = buffer.double.also { require(it.isFinite() && it in 0.0..1e12) }
            require(buffer.int == CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()) { "Preference state checksum failed" }
            return model
        }
    }
}

/** Measures actual sampled playback time. Position/seek values never enter the learning signal. */
internal class PreferencePlaybackSession(private val feedback: (Song?, Song, Boolean) -> Unit) {
    private var song: Song? = null
    private var previous: Song? = null
    private var sampledAt: Long? = null
    private var audibleMs = 0L
    private var learned = false
    private var knownDurationMs = 0L

    @Synchronized fun sample(current: Song, durationMs: Long, monotonicMs: Long) {
        if (com.cosmotune.podio.PodioPlaybackPolicy.isPodio(current.videoId)) {
            sampledAt = null
            return
        }
        val length = durationMs.takeIf { it > 0 } ?: current.durationMillis()
        if (song?.videoId != current.videoId) {
            previous = song; song = current; audibleMs = 0; learned = false; sampledAt = null
        }
        knownDurationMs = length
        val last = sampledAt
        if (last != null && monotonicMs >= last) audibleMs += (monotonicMs - last).coerceAtMost(8_000)
        sampledAt = monotonicMs
        val threshold = if (length > 0) (length * .8).toLong().coerceIn(30_000, 240_000) else 120_000
        if (!learned && audibleMs >= threshold) {
            learned = true; feedback(previous, current, true)
        }
    }

    @Synchronized fun pause() { sampledAt = null }

    @Synchronized fun skipped(expectedId: String) {
        val old = song ?: return
        if (old.videoId != expectedId) return
        val length = knownDurationMs
        // A very brief accidental tap is ignored. Pauses, failures and app shutdown are not skips.
        if (!learned && length > 0 && audibleMs in 3_000 until minOf(20_000, length / 5)) {
            learned = true; feedback(previous, old, false)
        }
    }
}
