package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.sqrt

/** Quantised graph embeddings and a small discovery vocabulary; no full catalogue in memory. */
internal class TinyRecommendationModel private constructor(private val data: ByteBuffer) {
    private val dimensions = data.getInt(12)
    private val titleBuckets = data.getInt(16)
    private val artistBuckets = data.getInt(20)
    private val albumBuckets = data.getInt(24)
    val itemCount = data.getInt(28)
    private val stringBytes = data.getInt(36)
    private val artistsAt = 40 + titleBuckets * dimensions
    private val albumsAt = artistsAt + artistBuckets * dimensions
    private val recordsAt = albumsAt + albumBuckets * dimensions
    private val vectorsAt = recordsAt + itemCount * 24
    private val stringsAt = vectorsAt + itemCount * dimensions

    fun score(seed: Song, candidate: Song): Float = dot(encode(seed), encode(candidate)).coerceIn(0f, 1f)

    /** A radio batch shares one seed; do its hashing and normalization only once. */
    fun scoreAll(seed: Song, candidates: List<Song>): FloatArray {
        val vector = encode(seed)
        return FloatArray(candidates.size) { dot(vector, encode(candidates[it])).coerceIn(0f, 1f) }
    }

    fun recommend(seed: Song, limit: Int = 24): List<RecommendationModel.Candidate> {
        if (seed.title.isBlank() || seed.artist.isBlank()) return emptyList()
        val vector = encode(seed)
        val scored = ArrayList<Pair<Int, Float>>(itemCount)
        for (i in 0 until itemCount) {
            var score = 0f
            for (d in 0 until dimensions) score += vector[d] * data.get(vectorsAt + i * dimensions + d) / 127f
            if (score >= .65f) scored += i to score
        }
        val artists = HashMap<String, Int>()
        val seen = HashSet<String>()
        return scored.sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first }).mapNotNull { (i, score) ->
            val row = recordsAt + i * 24
            if (data.getInt(row + 20) and 1 != 0) return@mapNotNull null
            val artist = text(data.getInt(row + 8))
            val title = text(data.getInt(row + 4))
            val key = identity(artist, title)
            val credit = primary(artist)
            if (key == identity(seed.artist, seed.title) || artists.getOrDefault(credit, 0) >= 2 || !seen.add(key)) return@mapNotNull null
            artists[credit] = artists.getOrDefault(credit, 0) + 1
            RecommendationModel.Candidate(text(data.getInt(row)), title, artist, text(data.getInt(row + 12)), data.getInt(row + 16), score, 0)
        }.take(limit.coerceIn(0, 64))
    }

    private fun encode(song: Song): FloatArray {
        val artist = primary(song.artist)
        val titleAt = 40 + bucket(identity(song.artist, song.title), titleBuckets) * dimensions
        val artistAt = artistsAt + bucket(artist, artistBuckets) * dimensions
        val albumAt = albumsAt + bucket(artist + "\u001f" + RecommendationModel.normalize(song.albumName.orEmpty()), albumBuckets) * dimensions
        val vector = FloatArray(dimensions)
        var length = 0f
        for (d in vector.indices) {
            val value = .7f * data.get(titleAt + d) + .25f * data.get(artistAt + d) + .05f * data.get(albumAt + d)
            vector[d] = value
            length += value * value
        }
        if (length > 0) {
            val scale = sqrt(length)
            for (d in vector.indices) vector[d] /= scale
        }
        return vector
    }

    private fun text(offset: Int): String {
        require(offset in 0 until stringBytes)
        val start = stringsAt + offset
        var end = start
        while (end < data.limit() && end - start <= 8192 && data.get(end).toInt() != 0) end++
        require(end < data.limit() && end - start <= 8192)
        val value = ByteArray(end - start)
        val view = data.duplicate()
        view.position(start)
        view.get(value)
        return value.toString(Charsets.UTF_8)
    }

    companion object {
        const val MAX_BYTES = 5_000_000L
        private val artistSeparator = Regex("\\s*(?:,|&|;| feat\\.? | featuring | and )\\s*", RegexOption.IGNORE_CASE)
        private fun primary(artist: String) = RecommendationModel.normalize(artist.split(artistSeparator, limit = 2).first()).replace(" ", "")
        private fun identity(artist: String, title: String) = primary(artist) + "\u001f" + RecommendationModel.titleKey(title).replace(" ", "")
        private fun bucket(key: String, size: Int) = (ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))).long and (size - 1).toLong()).toInt()
        private fun dot(a: FloatArray, b: FloatArray): Float {
            var value = 0f
            for (i in a.indices) value += a[i] * b[i]
            return value
        }

        fun open(file: File): TinyRecommendationModel {
            require(file.length() in 40..MAX_BYTES)
            val header = ByteArray(40)
            file.inputStream().use { input -> java.io.DataInputStream(input).readFully(header) }
            val head = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(header.copyOfRange(0, 8).contentEquals("CTTINY01".toByteArray()) && head.getInt(8) == 1)
            require(head.getInt(12) == 32 && head.getInt(16) == 65536 && head.getInt(20) == 32768 && head.getInt(24) == 16384 && head.getInt(32) == 24)
            val count = head.getInt(28)
            val strings = head.getInt(36)
            require(count in 1..4096 && strings > 0)
            val expected = 40L + (65536L + 32768L + 16384L) * 32 + count.toLong() * (24 + 32) + strings
            require(expected == file.length())
            val mapped = FileChannel.open(file.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, expected) }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            val model = TinyRecommendationModel(mapped)
            for (i in 0 until count) for (field in 0..3) require(mapped.getInt(model.recordsAt + i * 24 + field * 4) in 0 until strings)
            return model
        }
    }
}
