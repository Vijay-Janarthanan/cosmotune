package com.cosmotune.audio

import com.cosmotune.listening.LocalLibraryAccess
import com.cosmotune.listening.LocalLibraryFile
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 / EBU R128 integrated loudness: K-weighting, 400 ms blocks with 75 % overlap,
 * an absolute gate at -70 LUFS and a relative gate 10 LU below the absolute-gated level.
 * Written for this project from the published recommendation; no third-party code.
 */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {
    init { require(sampleRate in 8_000..768_000 && channels in 1..8) { "Unsupported audio layout" } }

    private val weights = DoubleArray(channels) { channelWeight(it, channels) }
    private val shelf = Array(channels) { Biquad.shelf(sampleRate) }
    private val highPass = Array(channels) { Biquad.highPass(sampleRate) }
    private val hop = max(1, sampleRate / 10)
    private val hopEnergy = DoubleArray(channels)
    private var hopFill = 0
    private val recent = ArrayDeque<Double>()
    private val blocks = ArrayList<Double>()
    var samplePeak = 0.0
        private set
    var frames = 0L
        private set

    /** Interleaved float samples; [count] is the number of floats, not frames. */
    fun add(samples: FloatArray, count: Int) {
        var i = 0
        val end = minOf(count, samples.size)
        while (i + channels <= end) {
            for (c in 0 until channels) {
                val raw = samples[i + c].toDouble()
                if (!raw.isFinite()) continue
                val magnitude = kotlin.math.abs(raw)
                if (magnitude > samplePeak) samplePeak = magnitude
                val weighted = highPass[c].step(shelf[c].step(raw))
                hopEnergy[c] += weighted * weighted
            }
            i += channels
            frames++
            if (++hopFill == hop) closeHop()
        }
    }

    private fun closeHop() {
        var sum = 0.0
        for (c in 0 until channels) { sum += weights[c] * hopEnergy[c] / hop; hopEnergy[c] = 0.0 }
        hopFill = 0
        recent.addLast(sum)
        if (recent.size > 4) recent.removeFirst()
        if (recent.size == 4) {
            if (blocks.size >= MAX_BLOCKS) return
            blocks += recent.sum() / 4.0
        }
    }

    /** Null when every block is below the absolute gate (silence or near silence). */
    fun integratedLufs(): Double? {
        val absolute = blocks.filter { lufs(it) > ABSOLUTE_GATE }
        if (absolute.isEmpty()) return null
        val relative = lufs(absolute.average()) - 10.0
        val gated = absolute.filter { lufs(it) > relative }
        if (gated.isEmpty()) return null
        return lufs(gated.average())
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        companion object {
            /** BS.1770 stage 1 pre-filter, derived for any sample rate (identical to the 48 kHz table). */
            fun shelf(rate: Int): Biquad {
                val k = tan(PI * 1681.974450955533 / rate)
                val vh = 10.0.pow(3.999843853973347 / 20.0)
                val vb = vh.pow(0.4996667741545416)
                val q = 0.7071752369554196
                val a0 = 1.0 + k / q + k * k
                return Biquad((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                    2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
            }
            /** BS.1770 stage 2 RLB high-pass. */
            fun highPass(rate: Int): Biquad {
                val k = tan(PI * 38.13547087602444 / rate)
                val q = 0.5003270373238773
                val a0 = 1.0 + k / q + k * k
                return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
            }
        }
    }

    companion object {
        const val ABSOLUTE_GATE = -70.0
        /** About 55 hours of 100 ms hops; analysis stops measuring new blocks beyond it. */
        private const val MAX_BLOCKS = 2_000_000
        fun lufs(meanSquare: Double): Double = -0.691 + 10.0 * log10(meanSquare)

        /** BS.1770 channel weights for the usual FL FR FC LFE BL BR layout; LFE is excluded. */
        internal fun channelWeight(index: Int, channels: Int): Double = when {
            channels >= 6 && index == 3 -> 0.0
            channels >= 5 && index >= 4 -> 1.41
            else -> 1.0
        }
    }
}

/**
 * Highest frequency still carrying real energy. Lossy encoders usually remove everything above
 * a fixed cutoff; a lossless container with that shape suggests a lossy source. Evidence only.
 */
class SpectralCutoffMeter(val sampleRate: Int, val channels: Int) {
    private val size = 4096
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    private val frame = DoubleArray(size)
    private val power = DoubleArray(size / 2)
    private var fill = 0
    private var skip = 0
    private val stride = max(0, sampleRate / 2 - size)
    var frames = 0
        private set

    fun add(samples: FloatArray, count: Int) {
        var i = 0
        val end = minOf(count, samples.size)
        while (i + channels <= end) {
            if (skip > 0) { skip--; i += channels; continue }
            var mono = 0.0
            for (c in 0 until channels) mono += samples[i + c]
            frame[fill++] = (mono / channels).takeIf { it.isFinite() } ?: 0.0
            i += channels
            if (fill == size) { analyse(); fill = 0; skip = stride }
        }
    }

    private fun analyse() {
        if (frames >= 600) return
        val re = DoubleArray(size) { frame[it] * window[it] }
        val im = DoubleArray(size)
        fft(re, im)
        for (k in power.indices) power[k] += re[k] * re[k] + im[k] * im[k]
        frames++
    }

    /** Null when the measured audio is silent or too short to judge. */
    fun cutoffHz(): Int? {
        if (frames == 0) return null
        val binHz = sampleRate.toDouble() / size
        val bandBins = max(1, (250.0 / binHz).toInt())
        fun level(from: Int): Double {
            var sum = 0.0
            for (k in from until minOf(power.size, from + bandBins)) sum += power[k]
            return 10.0 * log10(sum / bandBins / frames + 1e-30)
        }
        val reference = ((1000.0 / binHz).toInt() until (4000.0 / binHz).toInt() step bandBins).map(::level).average()
        if (reference < -100.0) return null
        var bin = power.size - bandBins
        while (bin > 0) {
            if (level(bin) > reference - CUTOFF_DROP_DB) return ((bin + bandBins) * binHz).toInt().coerceAtMost(sampleRate / 2)
            bin -= bandBins
        }
        return null
    }

    companion object {
        const val CUTOFF_DROP_DB = 60.0

        internal fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j or bit
                if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
            }
            var length = 2
            while (length <= n) {
                val angle = -2.0 * PI / length
                for (start in 0 until n step length) for (k in 0 until length / 2) {
                    val wr = cos(angle * k); val wi = sin(angle * k)
                    val a = start + k; val b = a + length / 2
                    val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                }
                length = length shl 1
            }
        }
    }
}

/** What a platform decoder actually measured, independently of file names and tags. */
interface LocalPcmStream : Closeable {
    val sampleRate: Int
    val channels: Int
    val codec: String?
    val bitsPerSample: Int?
    val bitrate: Long?
    /** Tags the container states, such as `genre`; never trusted for quality. */
    val tags: Map<String, String> get() = emptyMap()
    /** Next interleaved block, or null at the end. Only the first [count] floats are valid. */
    fun read(): FloatArray?
    val count: Int
}

/** Opens local audio for analysis. Throws UnsupportedOperationException when a format is unavailable. */
fun interface LocalPcmDecoder {
    fun open(song: Song, file: LocalLibraryFile): LocalPcmStream
}

/** Pure-Kotlin RIFF/WAVE PCM reader used on every host and in tests; other formats need a platform decoder. */
object WavPcmDecoder : LocalPcmDecoder {
    override fun open(song: Song, file: LocalLibraryFile): LocalPcmStream = open(file.open())

    fun open(raw: InputStream): LocalPcmStream {
        val input = java.io.BufferedInputStream(raw, 64 * 1024)
        try {
            fun bytes(n: Int) = ByteArray(n).also { b -> var at = 0; while (at < n) { val r = input.read(b, at, n - at); if (r < 0) throw UnsupportedOperationException("Not a WAV file"); at += r } }
            fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val header = bytes(12)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") throw UnsupportedOperationException("Not a WAV file")
            var format = -1; var channels = 0; var rate = 0; var bits = 0
            while (true) {
                val chunk = bytes(8)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = le(chunk).getInt(4).toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    require(size in 16..4096) { "Invalid WAV format chunk" }
                    val body = le(bytes(size.toInt()))
                    format = body.getShort(0).toInt() and 0xFFFF
                    channels = body.getShort(2).toInt(); rate = body.getInt(4); bits = body.getShort(14).toInt()
                    if (format == 0xFFFE && size >= 26) format = body.getShort(24).toInt() and 0xFFFF
                    if (size % 2 == 1L) input.read()
                } else if (id == "data") {
                    if (format != 1 && format != 3) throw UnsupportedOperationException("Compressed WAV needs a platform decoder")
                    if (channels !in 1..8 || rate !in 8_000..768_000 || bits !in setOf(8, 16, 24, 32) || (format == 3 && bits != 32))
                        throw UnsupportedOperationException("Unsupported WAV layout")
                    return WavStream(input, rate, channels, bits, format == 3, size)
                } else {
                    var left = size + (size and 1)
                    while (left > 0) { val skipped = input.skip(left); if (skipped <= 0) { if (input.read() < 0) throw UnsupportedOperationException("Truncated WAV file"); left-- } else left -= skipped }
                }
            }
        } catch (e: Exception) { input.close(); throw e }
    }

    private class WavStream(
        private val input: InputStream, override val sampleRate: Int, override val channels: Int,
        override val bitsPerSample: Int, private val float: Boolean, private var remaining: Long,
    ) : LocalPcmStream {
        override val codec = if (float) "PCM float" else "PCM"
        override val bitrate = sampleRate.toLong() * channels * bitsPerSample
        private val width = bitsPerSample / 8
        private val raw = ByteArray(width * channels * 4096)
        private val out = FloatArray(channels * 4096)
        override var count = 0
            private set

        override fun read(): FloatArray? {
            if (remaining <= 0) return null
            val wanted = minOf(raw.size.toLong(), remaining).toInt() / (width * channels) * (width * channels)
            if (wanted == 0) return null
            var have = 0
            while (have < wanted) { val r = input.read(raw, have, wanted - have); if (r < 0) break; have += r }
            remaining -= have
            if (have < width * channels) { remaining = 0; return null }
            val samples = have / width
            val buffer = ByteBuffer.wrap(raw, 0, have).order(ByteOrder.LITTLE_ENDIAN)
            for (s in 0 until samples) out[s] = when {
                float -> buffer.getFloat(s * 4)
                width == 1 -> ((raw[s].toInt() and 0xFF) - 128) / 128f
                width == 2 -> buffer.getShort(s * 2) / 32768f
                width == 3 -> ((raw[s * 3].toInt() and 0xFF) or ((raw[s * 3 + 1].toInt() and 0xFF) shl 8) or (raw[s * 3 + 2].toInt() shl 16)) / 8388608f
                else -> (buffer.getInt(s * 4) / 2147483648.0).toFloat()
            }
            count = samples
            return out
        }

        override fun close() = input.close()
    }
}

@Serializable
data class LibraryAudioAnalysis(
    val id: String,
    val location: String,
    val bytes: Long? = null,
    val modified: Long? = null,
    /** Integrated loudness (BS.1770-4); null for silence. */
    val integratedLufs: Double? = null,
    val samplePeak: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val codec: String? = null,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitsPerSample: Int? = null,
    val bitrate: Long? = null,
    val cutoffHz: Int? = null,
    val genre: String? = null,
    val analysedAt: Long = 0,
    /** Album and album artist as the file or library names them; the album-gain grouping key. */
    val album: String? = null,
    val albumArtist: String? = null,
    /** ReplayGain 2.0 album gain (reference -18 LUFS) across the analysed tracks sharing [albumKey]. */
    val albumGainDb: Double? = null,
    /** The highest sample peak among those tracks. */
    val albumPeak: Double? = null,
) {
    /** Album plus album artist, case-folded; null when the file names no album. */
    val albumKey: String? get() = album?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
        (albumArtist?.trim().orEmpty() + "\u0000" + name).lowercase(java.util.Locale.ROOT)
    }

    /** ReplayGain 2.0 track gain (reference -18 LUFS). */
    val replayGainDb: Double? get() = integratedLufs?.let { REPLAYGAIN_REFERENCE_LUFS - it }

    /**
     * The figure playback treats like YouTube's stated loudness (dB above the -14 LUFS stream
     * reference), limited so the resulting boost cannot push the measured sample peak past
     * -0.1 dBFS. The normalizer's peak limiter remains as the final safety stage.
     */
    val playbackLoudnessDb: Double? get() {
        val measured = integratedLufs ?: return null
        val relative = measured - PLAYBACK_REFERENCE_LUFS
        if (samplePeak <= 0.0) return relative
        return max(relative, 20.0 * log10(samplePeak) + 0.1)
    }

    val lossless: Boolean get() = codec?.lowercase(java.util.Locale.ROOT)?.let { c ->
        LOSSLESS_CODECS.any { c.contains(it) }
    } == true

    /** A lossless decode whose spectrum stops well below the Nyquist limit. Not proof of anything. */
    val possibleLossySource: Boolean get() = lossless && sampleRate >= 44_100 && cutoffHz != null && cutoffHz < 19_000

    companion object {
        const val REPLAYGAIN_REFERENCE_LUFS = -18.0
        const val PLAYBACK_REFERENCE_LUFS = -14.0
        private val LOSSLESS_CODECS = listOf("flac", "alac", "pcm", "wavpack", "ape", "tta", "dsd", "truehd", "mlp")
    }
}

/**
 * ReplayGain 2.0 album gain. Each album's loudness is the duration-weighted energy mean of its
 * tracks' gated integrated loudness: the per-track gated blocks are not kept, so this pools the
 * tracks' energy rather than re-gating every block together (the two usually agree within a few
 * tenths of a LU). Tracks without an album or a measurement are left without an album gain.
 */
object AlbumGain {
    fun apply(entries: Collection<LibraryAudioAnalysis>): List<LibraryAudioAnalysis> {
        val albums = entries.filter { it.albumKey != null && it.integratedLufs != null && it.durationSeconds > 0 }.groupBy { it.albumKey!! }
        val gains = albums.mapValues { (_, tracks) ->
            val seconds = tracks.sumOf { it.durationSeconds }
            val energy = tracks.sumOf { it.durationSeconds * 10.0.pow(it.integratedLufs!! / 10.0) } / seconds
            val lufs = 10.0 * log10(energy)
            ((LibraryAudioAnalysis.REPLAYGAIN_REFERENCE_LUFS - lufs) * 100).toLong() / 100.0 to tracks.maxOf { it.samplePeak }
        }
        return entries.map { entry ->
            val gain = entry.albumKey?.takeIf { entry.integratedLufs != null }?.let(gains::get)
            entry.copy(albumGainDb = gain?.first, albumPeak = gain?.second)
        }
    }
}

@Serializable
private data class AnalysisFile(val version: Int = 1, val entries: List<LibraryAudioAnalysis> = emptyList())

/** Local-only measurements; never contains stream URLs or credentials. Atomic replacement on every save. */
class LibraryAnalysisStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun read(): Map<String, LibraryAudioAnalysis> {
        if (!file.isFile) return emptyMap()
        require(file.length() <= MAX_BYTES) { "Library analysis is too large. Keep this file for recovery." }
        val parsed = json.decodeFromString<AnalysisFile>(file.readText(Charsets.UTF_8))
        require(parsed.version == 1) { "This library analysis needs a newer version of Cosmotune." }
        return parsed.entries.associateBy { it.location }
    }

    @Synchronized
    fun write(entries: Collection<LibraryAudioAnalysis>) {
        val kept = entries.sortedByDescending { it.analysedAt }.distinctBy { it.location }.take(MAX_ENTRIES)
        val raw = json.encodeToString(AnalysisFile(entries = kept)).toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_BYTES) { "Library analysis is full." }
        file.absoluteFile.parentFile.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".cosmotune-analysis-", ".tmp")
        try {
            java.io.FileOutputStream(temp.toFile()).use { out -> out.write(raw); out.fd.sync() }
            try { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        private const val MAX_BYTES = 16 * 1024 * 1024
        fun beside(listeningFile: File) = File(listeningFile.absoluteFile.parentFile, "cosmotune-library-analysis.json")
    }
}

/**
 * Measured local loudness for playback on both hosts. Streams keep the provider's own figure;
 * only local files measured by a library scan are found here.
 */
object LibraryLoudness {
    @Volatile private var byId: Map<String, LibraryAudioAnalysis> = emptyMap()

    fun publish(entries: Collection<LibraryAudioAnalysis>) {
        byId = entries.filter { it.integratedLufs != null }.sortedBy { it.analysedAt }.associateBy { it.id }
    }

    /** Loads [file] off the calling thread's critical path; a damaged file simply leaves local tracks unmeasured. */
    fun attach(file: File) {
        Thread({ runCatching { publish(LibraryAnalysisStore(file).read().values) } }, "library-loudness").apply { isDaemon = true }.start()
    }

    fun analysisFor(id: String): LibraryAudioAnalysis? = byId[id]
    fun loudnessDbFor(id: String): Double? = byId[id]?.playbackLoudnessDb
}

data class LibraryAnalysisProgress(val checked: Int, val total: Int, val measured: Int)
data class LibraryAnalysisResult(
    val entries: List<LibraryAudioAnalysis>, val measured: Int, val reused: Int, val unsupported: Int, val failed: Int,
)

/**
 * Decodes each readable local file once and records loudness, decoded format and spectral cutoff.
 * Unchanged files (same location, size and modification time) reuse their earlier measurement.
 * Decoding stops at [maxSecondsPerTrack] so one damaged or endless file cannot stall the scan.
 */
suspend fun analyseLocalLibrary(
    songs: List<Song>,
    access: LocalLibraryAccess,
    decoder: LocalPcmDecoder,
    store: LibraryAnalysisStore,
    now: () -> Long = System::currentTimeMillis,
    maxSecondsPerTrack: Int = 3 * 60 * 60,
    progress: (LibraryAnalysisProgress) -> Unit = {},
): LibraryAnalysisResult = withContext(Dispatchers.IO) {
    require(songs.size <= 10_000) { "Analyse at most 10,000 library entries at a time" }
    val selected = songs.filter { it.localPath != null || it.localUri != null }.distinctBy { it.videoId to (it.localPath ?: it.localUri) }
    val previous = store.read()
    val results = previous.toMutableMap()
    var measured = 0; var reused = 0; var unsupported = 0; var failed = 0
    selected.forEachIndexed { index, song ->
        currentCoroutineContext().ensureActive()
        try {
            val file = access.inspect(song)
            val known = results[file.identity]
            if (known != null && known.bytes == file.bytes && known.modified == file.modified && known.id == song.videoId) {
                reused++
            } else {
                decoder.open(song, file).use { stream ->
                    val loudness = LoudnessMeter(stream.sampleRate, stream.channels)
                    val spectrum = SpectralCutoffMeter(stream.sampleRate, stream.channels)
                    val limit = maxSecondsPerTrack.toLong() * stream.sampleRate
                    while (loudness.frames < limit) {
                        currentCoroutineContext().ensureActive()
                        val block = stream.read() ?: break
                        loudness.add(block, stream.count)
                        spectrum.add(block, stream.count)
                    }
                    results[file.identity] = LibraryAudioAnalysis(
                        id = song.videoId, location = file.identity, bytes = file.bytes, modified = file.modified,
                        integratedLufs = loudness.integratedLufs()?.let { (it * 100).toLong() / 100.0 },
                        samplePeak = loudness.samplePeak, durationSeconds = loudness.frames.toDouble() / stream.sampleRate,
                        codec = stream.codec?.take(64), sampleRate = stream.sampleRate, channels = stream.channels,
                        bitsPerSample = stream.bitsPerSample, bitrate = stream.bitrate, cutoffHz = spectrum.cutoffHz(),
                        genre = stream.tags.entries.firstOrNull { it.key.equals("genre", true) }?.value?.trim()?.take(80)?.takeIf { it.isNotBlank() },
                        analysedAt = now(),
                        album = (stream.tags.entries.firstOrNull { it.key.equals("album", true) }?.value ?: song.albumName)?.trim()?.take(500)?.takeIf { it.isNotBlank() },
                        albumArtist = (stream.tags.entries.firstOrNull { it.key.equals("album_artist", true) || it.key.equals("albumartist", true) || it.key.equals("album artist", true) }?.value
                            ?: song.artist)?.trim()?.take(500)?.takeIf { it.isNotBlank() },
                    )
                    measured++
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: UnsupportedOperationException) { unsupported++ }
        catch (_: Exception) { failed++ }
        progress(LibraryAnalysisProgress(index + 1, selected.size, measured))
    }
    val withAlbums = AlbumGain.apply(results.values).associateBy { it.location }
    results.clear(); results.putAll(withAlbums)
    store.write(results.values)
    LibraryLoudness.publish(results.values)
    val ids = selected.mapTo(HashSet()) { it.videoId }
    LibraryAnalysisResult(results.values.filter { it.id in ids }.sortedBy { it.location }, measured, reused, unsupported, failed)
}
