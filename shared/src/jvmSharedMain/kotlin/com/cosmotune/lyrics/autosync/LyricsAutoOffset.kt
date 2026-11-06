package com.cosmotune.lyrics.autosync

import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricSyllable
import com.cosmotune.settings.Extras
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lyrics auto offset: listens to the song, finds where it is sung, and moves timed lyrics so each line lands on its singing
 * ([VocalActivityAnalyzer] and [LyricsAligner], ported from Mochy Music). The offset is saved per song and per set of lyrics
 * (a fingerprint of their line times), so another provider's lyrics for the same song start from nothing. The manual lyrics
 * offset is untouched and still applies on top: this moves the lines themselves, before the player's clock reads them.
 *
 * The platforms supply the audio: [audioReady] says how to read the playing song's PCM, and the lyrics screen calls
 * [sync] with what it shows. When both are known, the switch in Extras is on and nothing is saved yet, the song is read once
 * on a quiet thread of its own and the result kept.
 */
object LyricsAutoOffset {

    /** Reads a song's audio from the start, calling [feed] with planar float blocks; false when it cannot be read. */
    fun interface AudioReader {
        suspend fun read(feed: (left: FloatArray, right: FloatArray?, count: Int, sampleRate: Int, presentationUs: Long) -> Unit): Boolean
    }

    sealed interface State {
        val videoId: String
        data class Listening(override val videoId: String) : State
        data class Synced(override val videoId: String, val offsetMs: Int, val sure: Boolean, val driftMs: Int?) : State
        data class Failed(override val videoId: String, val reason: String) : State
    }

    private val stateFlow = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = stateFlow.asStateFlow()

    private val readers = object : LinkedHashMap<String, AudioReader>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioReader>?) = size > 4
    }
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<AudioReader>>()
    private val running = ConcurrentHashMap<String, Deferred<Int?>>()

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyrics-auto-offset").apply { priority = Thread.NORM_PRIORITY - 2; isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile private var file: File? = null
    private val saved = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false

    /** Where saved offsets live; each app calls this once at start. */
    fun configure(directory: File) {
        file = File(directory, "lyrics-auto-offsets.tsv")
        loaded = false
    }

    /** How to read [videoId]'s audio, from the player; replaces what was said before for the same song. */
    fun audioReady(videoId: String, reader: AudioReader) {
        synchronized(readers) { readers[videoId] = reader }
        waiting.remove(videoId)?.complete(reader)
    }

    /** The saved offset for these [lines] of [videoId], if one was measured. Positive means the lyrics ran late. */
    fun savedOffset(videoId: String, lines: List<LyricLine>): Int? {
        load()
        return saved[key(videoId, lines)]?.takeIf { it != FAILED }
    }

    /** [lines] moved by their saved offset, or unchanged. */
    fun applied(videoId: String, lines: List<LyricLine>): List<LyricLine> =
        savedOffset(videoId, lines)?.let { shift(lines, it) } ?: lines

    /** Forgets every saved offset (the Extras button), so songs are listened to again. */
    fun forgetAll() {
        load()
        saved.clear()
        persist()
    }

    /**
     * The offset for [lines] of [videoId]: the saved one, or a fresh measurement when the switch is on and the song can be
     * read within [waitForAudioMs]. Null when the lyrics have no timing, the measurement is unsure or it failed; a failure
     * is remembered so the same song and lyrics are not decoded again.
     */
    suspend fun sync(videoId: String, lines: List<LyricLine>, force: Boolean = false, waitForAudioMs: Long = 30_000): Int? {
        if (lines.none { it.timeMs > 0 } || lines.count { !it.isGap } < 4) return null
        load()
        val key = key(videoId, lines)
        if (!force) {
            saved[key]?.let { return it.takeIf { value -> value != FAILED } }
            if (!Extras.settings.autoLyricsOffset.value) return null
        }
        val job = running.computeIfAbsent(key) {
            scope.async {
                try {
                    measure(videoId, key, lines, waitForAudioMs, force)
                } finally {
                    running.remove(key)
                }
            }
        }
        return job.await()
    }

    private suspend fun measure(videoId: String, key: String, lines: List<LyricLine>, waitForAudioMs: Long, asked: Boolean): Int? {
        val reader = synchronized(readers) { readers[videoId] }
            ?: withTimeoutOrNull(waitForAudioMs) { waiting.computeIfAbsent(videoId) { CompletableDeferred() }.await() }
            ?: return null // the audio never became readable; try again another time
        stateFlow.value = State.Listening(videoId)
        val analyzer = VocalActivityAnalyzer()
        var samples = 0L
        val read = runCatching {
            reader.read { left, right, count, rate, presentationUs ->
                if (samples < MAX_SECONDS * rate) analyzer.feed(left, right, count, rate, presentationUs)
                samples += count
            }
        }.getOrDefault(false)
        val vocals = if (read) analyzer.finish() else null
        if (vocals == null) {
            stateFlow.value = State.Failed(videoId, "Too little of the song came through to hear the singing.")
            return null
        }
        val alignment = LyricsAligner.align(lines, vocals, MAX_OFFSET_MS)
        // Applied on its own only when the match is clear; asked for by hand, a best guess is worth hearing.
        if (alignment == null || alignment.confidence < (if (asked) MIN_CONFIDENCE else SURE_CONFIDENCE)) {
            remember(key, FAILED)
            stateFlow.value = State.Failed(videoId, "The singing did not match these lyrics clearly enough.")
            return null
        }
        remember(key, alignment.offsetMs)
        stateFlow.value = State.Synced(videoId, alignment.offsetMs, alignment.confidence >= SURE_CONFIDENCE, alignment.driftMs)
        return alignment.offsetMs
    }

    /**
     * [lines] with every time moved earlier by [offsetMs] (later for a negative one): words, syllables, the end of the
     * singing and the background vocals with them. Nothing moves before zero.
     */
    fun shift(lines: List<LyricLine>, offsetMs: Int): List<LyricLine> {
        if (offsetMs == 0) return lines
        fun move(time: Long) = (time - offsetMs).coerceAtLeast(0L)
        fun moveLine(line: LyricLine): LyricLine = line.copy(
            timeMs = if (line.timeMs > 0 || offsetMs < 0) move(line.timeMs) else line.timeMs,
            words = line.words.map { word ->
                word.copy(
                    startMs = move(word.startMs),
                    endMs = move(word.endMs),
                    syllables = word.syllables.map { it.moved(::move) },
                )
            },
            sungUntilMs = line.sungUntilMs?.let(::move),
            background = line.background?.let(::moveLine),
            timingSource = line.timingSource?.let(::moveLine),
        )
        return lines.map(::moveLine)
    }

    private fun LyricSyllable.moved(move: (Long) -> Long) = copy(startMs = move(startMs), endMs = move(endMs))

    /** The song and a fingerprint of the lyric timing, so other lyrics for the same song are measured on their own. */
    fun key(videoId: String, lines: List<LyricLine>): String {
        var hash = 1125899906842597L
        lines.forEach { line -> hash = 31 * hash + line.timeMs; hash = 31 * hash + line.text.length }
        return "$videoId:${java.lang.Long.toHexString(hash)}"
    }

    private fun remember(key: String, offset: Int) {
        saved[key] = offset
        while (saved.size > MAX_SAVED) saved.keys.firstOrNull()?.let(saved::remove)
        persist()
    }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                file?.takeIf { it.isFile }?.readLines()?.forEach { row ->
                    val tab = row.indexOf('\t')
                    if (tab > 0) row.substring(tab + 1).toIntOrNull()?.let { saved[row.substring(0, tab)] = it }
                }
            }
            loaded = true
        }
    }

    private fun persist() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(saved.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    /** The same range the manual offset has, about. */
    private const val MAX_OFFSET_MS = 10_000
    /** At most this much of a song is listened to. */
    private const val MAX_SECONDS = 15 * 60L
    private const val MAX_SAVED = 3_000
    /** Mochy's thresholds: below the first a match is too unsure to save; from the second it is a confident match. */
    private const val MIN_CONFIDENCE = 0.3f
    private const val SURE_CONFIDENCE = 0.55f
    /** Remembered for lyrics that did not match, so they are not decoded again. */
    private const val FAILED = Int.MIN_VALUE
}
