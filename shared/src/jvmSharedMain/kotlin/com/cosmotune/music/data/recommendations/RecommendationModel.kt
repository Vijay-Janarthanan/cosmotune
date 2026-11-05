package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Read-only mapped graph: records and neighbours stay outside the managed heap. */
internal class RecommendationModel private constructor(private val bytes: ByteBuffer) {
    val itemCount = bytes.getInt(12)
    private val edgeCount = bytes.getInt(16)
    private val stringBytes = bytes.getInt(20)
    private val offsetsAt = HEADER + itemCount * RECORD
    private val edgesAt = offsetsAt + (itemCount + 1) * 4
    private val stringsAt = edgesAt + edgeCount * EDGE

    data class Candidate(
        val key: String, val title: String, val artist: String, val album: String,
        val durationSeconds: Int, val score: Float, val evidence: Int,
    ) {
        fun target() = TrackMatcher.Target(title, artist, durationSeconds.takeIf { it > 0 }, album.takeIf(String::isNotBlank))
    }

    fun recommend(seed: Song, limit: Int = 24): List<Candidate> {
        val signature = titleKey(seed.title)
        if (signature.isBlank() || seed.artist.isBlank()) return emptyList()
        val hash = hash64(signature)
        var lo = 0
        var hi = itemCount
        while (lo < hi) {
            val middle = (lo + hi) ushr 1
            if (bytes.getLong(record(middle) + 32) < hash) lo = middle + 1 else hi = middle
        }
        val matchedRows = ArrayList<Pair<Int, Float>>(16)
        val duration = TrackMatcher.targetOf(seed).durationSec
        while (lo < itemCount && bytes.getLong(record(lo) + 32) == hash) {
            val row = record(lo)
            if (titleKey(string(bytes.getInt(row + 4))) == signature &&
                TrackMatcher.sharesArtist(seed.artist, string(bytes.getInt(row + 8)))) {
                val length = bytes.getInt(row + 16)
                if (duration == null || length <= 0 || seed.isVideo || abs(duration - length) <= maxOf(10, duration / 20)) {
                    val preferred = if (bytes.getInt(row + 24) and 3 != 0) 100f else 0f
                    val albumMatch = if (!seed.albumName.isNullOrBlank() && normalize(seed.albumName!!) == normalize(string(bytes.getInt(row + 12)))) 10f else 0f
                    matchedRows += lo to (preferred + albumMatch + bytes.getFloat(row + 20))
                }
            }
            lo++
        }
        val seedRows = matchedRows.sortedByDescending { it.second }.take(16).map { it.first }
        val scores = HashMap<Int, Pair<Float, Int>>()
        for (seedRow in seedRows) {
            val start = bytes.getInt(offsetsAt + seedRow * 4)
            val end = bytes.getInt(offsetsAt + (seedRow + 1) * 4)
            for (index in start until end) {
                val edge = edgesAt + index * EDGE
                val target = bytes.getInt(edge)
                val score = bytes.getFloat(edge + 4)
                val evidence = bytes.get(edge + 10).toInt() and 0xff
                val support = bytes.getShort(edge + 8).toInt() and 0xffff
                if (target !in 0 until itemCount || !score.isFinite() || score <= 0 || target in seedRows) continue
                if (evidence and 3 != 0 && support < 2) continue
                val old = scores[target]
                scores[target] = maxOf(old?.first ?: 0f, score) to ((old?.second ?: 0) or evidence)
            }
        }
        val artists = HashMap<String, Int>()
        val identities = HashSet<String>()
        return scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Pair<Float, Int>>> { it.value.first }.thenBy { it.key })
            .mapNotNull { (id, value) ->
                val row = record(id)
                // Alternate versions need an explicitly matching seed, rather than slipping into ordinary radio.
                if (bytes.getInt(row + 28) and 1 != 0 && !hasVersion(seed.title)) return@mapNotNull null
                val title = string(bytes.getInt(row + 4))
                val artist = string(bytes.getInt(row + 8))
                val artistKey = normalize(artist)
                val key = artistKey + "\u001f" + titleKey(title)
                if (titleKey(title) == signature && TrackMatcher.sharesArtist(seed.artist, artist)) return@mapNotNull null
                if (title.isBlank() || artist.isBlank() || artists.getOrDefault(artistKey, 0) >= 2 || !identities.add(key)) return@mapNotNull null
                artists[artistKey] = artists.getOrDefault(artistKey, 0) + 1
                Candidate(string(bytes.getInt(row)), title, artist, string(bytes.getInt(row + 12)), bytes.getInt(row + 16), value.first, value.second)
            }.take(limit.coerceIn(0, 64))
    }

    private fun record(id: Int) = HEADER + id * RECORD

    private fun string(offset: Int): String {
        require(offset in 0 until stringBytes)
        var end = stringsAt + offset
        val max = minOf(stringsAt + stringBytes, end + 8193)
        while (end < max && bytes.get(end).toInt() != 0) end++
        require(end < max) { "Unterminated model string" }
        val copy = ByteArray(end - stringsAt - offset)
        val view = bytes.duplicate()
        view.position(stringsAt + offset)
        view.get(copy)
        return copy.toString(Charsets.UTF_8)
    }

    companion object {
        private const val HEADER = 40
        private const val RECORD = 56
        private const val EDGE = 12
        internal const val MAX_BYTES = 256L * 1024 * 1024
        private val packaging = Regex("""\s*[\(\[]\s*(?:from\b|official\b|album\b)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
        private val versions = Regex("\\b(?:remix|karaoke|instrumental|sped up|slowed|live at|live from|cover version)\\b")

        fun normalize(text: String): String {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            return buildString {
                normalized.codePoints().forEach { code ->
                    if (Character.getType(code) in 1..11) append(String(Character.toChars(code))) else append(' ')
                }
            }.trim().replace(Regex("\\s+"), " ")
        }

        fun titleKey(text: String): String = normalize(packaging.replace(text, " "))
        private fun hasVersion(text: String) = versions.containsMatchIn(normalize(text))
        private fun hash64(text: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))).long

        fun open(file: File): RecommendationModel {
            require(file.length() in HEADER.toLong()..MAX_BYTES)
            val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            file.inputStream().use { input ->
                val data = header.array()
                var offset = 0
                while (offset < data.size) {
                    val read = input.read(data, offset, data.size - offset)
                    require(read > 0)
                    offset += read
                }
            }
            val magic = ByteArray(8)
            header.duplicate().get(magic)
            require(magic.contentEquals("CTREC003".toByteArray()) && header.getInt(8) == 3)
            require(header.getInt(24) == RECORD && header.getInt(28) == EDGE && header.getInt(32) == 2)
            val items = header.getInt(12)
            val edges = header.getInt(16)
            val strings = header.getInt(20)
            require(items in 1..1_000_000 && edges in 0..32_000_000 && strings > 0)
            val expected = HEADER.toLong() + items.toLong() * RECORD + (items + 1L) * 4 + edges.toLong() * EDGE + strings
            require(expected == file.length()) { "Invalid model layout" }
            val mapped = FileChannel.open(file.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            var previous = 0
            var previousHash = Long.MIN_VALUE
            for (i in 0..items) {
                val offset = mapped.getInt(HEADER + items * RECORD + i * 4)
                require(offset in previous..edges)
                if (i == 0) require(offset == 0)
                previous = offset
                if (i < items) {
                    val row = HEADER + i * RECORD
                    val hash = mapped.getLong(row + 32)
                    require(hash >= previousHash) { "Unsorted model index" }
                    previousHash = hash
                    for (column in 0..3) require(mapped.getInt(row + column * 4) in 0 until strings)
                }
            }
            require(previous == edges)
            return RecommendationModel(mapped)
        }
    }
}
