package com.cosmotune.watch

import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchMarkWatchedTest {
    private val now = 1_762_461_600_000L
    private val series = TitleSummary(WatchKey.tmdbSeries(1399), TitleType.SERIES, "Harbour Town", ids = ExternalIds(tmdb = 1399))
    private val episode = WatchKey.tmdbEpisode(1399, 2, 4)

    private fun repo(): DefaultWatchRepository {
        val dir = Files.createTempDirectory("watch-mark").toFile().also { it.deleteOnExit() }
        return DefaultWatchRepository(WatchConfig(OkHttpClient(), dir, null, now = { now }, timeoutMs = 2_000),
            WatchStore(File(dir, "watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> emptyList() })
    }

    @Test fun markingAnEpisodeWatchedTakesItOutOfContinueWatching() = runBlocking<Unit> {
        val repository = repo()
        repository.saveProgress(WatchProgress(episode, 600_000, 2_400_000, now - 1_000, series, "Low Water"))
        assertEquals(1, repository.continueWatching.value.size)
        repository.setWatched(episode, series, watched = true, atMs = now)
        val saved = repository.progress(episode)!!
        assertTrue(saved.completed)
        assertEquals(2_400_000, saved.positionMs)
        assertEquals("Low Water", saved.episodeName)
        assertTrue(repository.continueWatching.value.isEmpty())
    }

    @Test fun aTitleNeverStartedCanBeMarkedAndUnmarked() = runBlocking<Unit> {
        val repository = repo()
        val film = WatchKey.tmdbFilm(603)
        repository.setWatched(film, null, watched = true, atMs = now)
        assertTrue(repository.progress(film)!!.watched)
        repository.setWatched(film, null, watched = false, atMs = now)
        assertNull(repository.progress(film))
    }
}
