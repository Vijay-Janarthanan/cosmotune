package com.cosmotune.music.data.lyrics

import com.cosmotune.cache.FileCacheDisk
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** One lyrics lookup per song for every caller, kept on the disk with its source, "no lyrics" only briefly. */
class LyricsStoreTest {
    private val folder = Files.createTempDirectory("lyrics-store").toFile()
    private var now = 1_762_461_600_000L
    private val asked = AtomicInteger()

    private val lines = listOf(
        LyricLine(1_000, "Kannaana kanne", words = listOf(LyricWord(1_000, 1_600, "Kannaana", listOf(LyricSyllable(1_000, 1_300, 0, 4), LyricSyllable(1_300, 1_600, 4, 8))), LyricWord(1_700, 2_200, "kanne")),
            background = LyricLine(1_800, "kanne", sungUntilMs = 2_400), singer = "Sid Sriram"),
        LyricLine(4_000, "En meedhu saaya vaa", alignment = LyricAlignment.End),
    )
    private val song = LyricsStore.Request("vid00000001", "Kannaana Kanne", "Sid Sriram", 252_000, "Viswasam",
        order = listOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED), sources = setOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED))

    @BeforeTest fun setUp() {
        LyricsStore.reset()
        LyricsStore.configure(FileCacheDisk(folder))
        LyricsStore.clock = { now }
    }

    @AfterTest fun tearDown() {
        LyricsStore.reset()
        LyricsStore.configure(null)
        folder.deleteRecursively()
    }

    private fun answering(result: LyricsRepository.Result?, failed: Boolean = false, gate: CompletableDeferred<Unit>? = null) {
        LyricsStore.lookup = { _, listener ->
            asked.incrementAndGet()
            listener.onStarted?.invoke(LyricsSource.LRCLIB)
            listener.onStarted?.invoke(LyricsSource.LRC_RED)
            gate?.await()
            if (failed) listener.failed.set(true)
            listener.onResult?.invoke(LyricsSource.LRCLIB, result)
            listener.onCancelled?.invoke(LyricsSource.LRC_RED)
            result
        }
    }

    private fun savedOnDisk() = runBlocking {
        withTimeout(5_000) { while (folder.listFiles().isNullOrEmpty()) yield() }
    }

    @Test fun thePlayerAndTheServiceShareOneLookupAndTheLateOneHearsEveryProvider() = runBlocking<Unit> {
        // Memory only, so neither caller waits on a disk read and the second surely joins while the first is still asking.
        LyricsStore.configure(null)
        val gate = CompletableDeferred<Unit>()
        answering(LyricsRepository.Result(LyricsSource.LRCLIB, lines), gate = gate)
        val service = async { LyricsStore.lyrics(song) }
        while (asked.get() == 0) yield()
        val heard = mutableListOf<String>()
        val player = async {
            LyricsStore.lyrics(song.copy(durationMs = 252_400),
                onSourceStarted = { heard += "started ${it.name}" },
                onSourceResult = { s, r -> heard += "result ${s.name} ${r != null}" },
                onSourceCancelled = { heard += "cancelled ${it.name}" })
        }
        yield()
        gate.complete(Unit)
        assertEquals(lines, service.await()?.lines)
        assertEquals(lines, player.await()?.lines)
        assertEquals(1, asked.get(), "one lookup for both")
        assertEquals(listOf("started LRCLIB", "started LRC_RED", "result LRCLIB true", "cancelled LRC_RED"), heard,
            "the player joining late hears what each provider did, in order")
    }

    @Test fun theAnswerIsKeptOnTheDiskWithItsSourceForTheNextPlay() = runBlocking<Unit> {
        answering(LyricsRepository.Result(LyricsSource.LRCLIB, lines))
        LyricsStore.lyrics(song)
        savedOnDisk()

        // The next start: nothing in memory, the disk only.
        LyricsStore.reset()
        LyricsStore.clock = { now }
        answering(null)
        var reported: LyricsSource? = null
        val again = LyricsStore.lyrics(song.copy(durationMs = 251_000), onSourceResult = { s, _ -> reported = s })
        assertEquals(LyricsSource.LRCLIB, again?.source)
        assertEquals(lines, again?.lines, "word timing, syllables, the background vocal and the singer come back whole")
        assertEquals(LyricsSource.LRCLIB, reported, "the provider drawer is told where they came from")
        assertEquals(1, asked.get(), "nothing asked at the second play")

        assertTrue(LyricsStore.lyrics(song.copy(durationMs = 262_000)) == null && asked.get() == 2, "another length is another recording: asked")
        assertTrue(LyricsStore.lyrics(song.copy(order = listOf(LyricsSource.LRC_RED, LyricsSource.LRCLIB))) == null && asked.get() == 3,
            "another source order asks again")

        now += LyricsStore.FOUND_FOR_MS
        answering(LyricsRepository.Result(LyricsSource.LRC_RED, lines))
        assertEquals(LyricsSource.LRC_RED, LyricsStore.lyrics(song)?.source, "a month on, asked again")
    }

    @Test fun noLyricsIsRememberedOnlyBrieflyAndNotAfterAFailure() = runBlocking<Unit> {
        answering(null)
        assertNull(LyricsStore.lyrics(song))
        assertNull(LyricsStore.lyrics(song))
        assertEquals(1, asked.get(), "a miss is believed for a while")
        now += LyricsStore.NONE_FOR_MS
        assertNull(LyricsStore.lyrics(song))
        assertEquals(2, asked.get(), "then asked again")

        answering(null, failed = true)
        val other = song.copy(videoId = "vid00000002")
        assertNull(LyricsStore.lyrics(other))
        assertNull(LyricsStore.lyrics(other))
        assertEquals(4, asked.get(), "a provider that failed is no answer: asked again at once")
    }

    @Test fun theNextSongIsLookedUpAheadAndARejectedAnswerIsForgotten() = runBlocking<Unit> {
        answering(LyricsRepository.Result(LyricsSource.LRCLIB, lines))
        LyricsStore.prefetch(song)
        withTimeout(5_000) { while (LyricsStore.kept(song) == null) yield() }
        assertEquals(lines, LyricsStore.lyrics(song)?.lines)
        assertEquals(1, asked.get(), "the song starting finds its lyrics ready")
        savedOnDisk()

        LyricsStore.forget(song.videoId)
        withTimeout(5_000) { while (!folder.listFiles().isNullOrEmpty()) yield() }
        val state = "kept=${LyricsStore.kept(song)} running=${LyricsStore.running()} files=${folder.listFiles()?.map { it.name }}"
        LyricsStore.lyrics(song)
        assertEquals(2, asked.get(), "lyrics rejected as the wrong ones are asked for again ($state)")
    }
}
