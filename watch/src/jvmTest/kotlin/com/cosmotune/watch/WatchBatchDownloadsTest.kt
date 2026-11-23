package com.cosmotune.watch

import com.cosmotune.watch.model.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okio.Buffer

class WatchBatchDownloadsTest {
    private val series = WatchKey.series("test", "own:series")
    private val summary = TitleSummary(series, TitleType.SERIES, "Fixture series")
    private val title = Title(summary, seasons = listOf(Season(series, 1, "Season 1", 2), Season(series, 2, "Season 2", 1)))
    private val source = StreamSource("test", "Fixture", "TEST")
    private fun episode(season: Int, number: Int) = Episode(WatchKey.episode("test", "own:series", season, number), series, season, number, "Episode $number")
    private fun target(season: Int, number: Int) = PlayTarget.EpisodeOf(episode(season, number).key, ExternalIds(), summary.name, season, number)
    private fun stream(url: String, height: Int = 720) = Stream(source, "${height}p", url = url, protocol = StreamProtocol.PROGRESSIVE, height = height)
    private fun repo(resolve: suspend (PlayTarget) -> List<Stream>): WatchRepository = object : WatchRepository by FakeWatchRepository() {
        override suspend fun episodes(series: WatchKey, season: Int) = if (season == 1) listOf(episode(1, 1), episode(1, 2), episode(1, 2)) else listOf(episode(2, 1))
        override fun targetOf(series: Title, episode: Episode): PlayTarget = target(episode.season, episode.number)
        override suspend fun sourcesNow(target: PlayTarget, hint: StreamHint?) = SourcesState(target, resolve(target), emptySet())
        override suspend fun progress(key: WatchKey): WatchProgress? = if (key == episode(1, 1).key) WatchProgress(key, 0, 0, 1, watched = true) else null
    }

    @Test fun pausedSelectionSurvivesRestartAndResolvesFreshWithoutDuplicateEpisodes(): Unit = runBlocking {
        val directory = Files.createTempDirectory("watch-batch").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestServer().use { server ->
                server.text("/1.mp4", "episode one")
                server.text("/2.mp4", "episode two")
                val resolving = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val first = WatchDownloads(directory, OkHttpClient(), scope = firstScope)
                first.bindRepository(repo { resolving.complete(Unit); gate.await(); emptyList() })
                val id = first.batches.enqueue(title, WatchBatchSelection.SEASON, 1, maxHeight = 720)
                withTimeout(5_000) { resolving.await() }
                first.batches.pause(id)
                firstScope.coroutineContext[Job]!!.cancelAndJoin()
                val persisted = directory.resolve("episode-jobs.json").readText()
                assertFalse(persisted.contains("http"), "addresses and headers are never serialized into jobs")
                val restored = WatchDownloads(directory, OkHttpClient(), scope = secondScope)
                restored.bindRepository(repo { t -> listOf(stream(server.url("/${(t as PlayTarget.EpisodeOf).episode}.mp4"))) })
                assertEquals(WatchBatchStatus.PAUSED, restored.batches.items.value.single().status)
                assertEquals(2, restored.batches.items.value.single().episodes.size)
                restored.batches.resume(id)
                val complete = withTimeout(10_000) { restored.batches.items.first { it.single().status == WatchBatchStatus.DONE } }.single()
                assertEquals(2, complete.completed)
                assertEquals(setOf(episode(1, 1).key, episode(1, 2).key), restored.items.value.map { it.key }.toSet())
                assertEquals(2, server.requests.size)
                assertFailsWith<IllegalArgumentException> { restored.batches.enqueue(title, WatchBatchSelection.SEASON, 1) }
            }
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun unwatchedAcrossSeasonsSkipsCompletedAndRetriesOnlyFailedDirectCopies() = runBlocking {
        val directory = Files.createTempDirectory("watch-batch-failed").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestServer().use { server ->
                server.text("/episode.mp4", "fixture episode")
                var available = false
                val requests = mutableListOf<WatchKey>()
                val downloads = WatchDownloads(directory, OkHttpClient(), scope = scope)
                downloads.bindRepository(repo { t ->
                    synchronized(requests) { requests += t.key }
                    if (available) listOf(stream(server.url("/episode.mp4")))
                    else listOf(stream(server.url("/live.m3u8")).copy(protocol = StreamProtocol.HLS))
                })
                val id = downloads.batches.enqueue(title, WatchBatchSelection.UNWATCHED, 1)
                val failed = withTimeout(10_000) { downloads.batches.items.first { it.single().status == WatchBatchStatus.FAILED } }.single()
                assertEquals(listOf(episode(1, 2).key, episode(2, 1).key), failed.episodes.map { it.target.key })
                assertTrue(server.requests.isEmpty(), "HLS never reaches the direct downloader")
                available = true
                downloads.batches.resume(id)
                withTimeout(10_000) { downloads.batches.items.first { it.single().status == WatchBatchStatus.DONE } }
                assertEquals(2, downloads.items.value.size)
                assertFalse(episode(1, 1).key in requests)
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun pauseJoinsTheWriterAndResumeUsesRealRanges() = runBlocking {
        val directory = Files.createTempDirectory("watch-pause").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val body = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        try {
            TestServer().use { server ->
                server.on("/episode.mp4") { request ->
                    val parts = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                    val start = parts[0].toInt(); val end = minOf(parts[1].toInt(), body.lastIndex)
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${body.size}")
                        .setBody(Buffer().write(body.copyOfRange(start, end + 1))).throttleBody(64 * 1024, 10, TimeUnit.MILLISECONDS)
                }
                val downloads = WatchDownloads(directory, OkHttpClient(), scope = scope)
                val media = stream(server.url("/episode.mp4")).copy(sizeBytes = body.size.toLong())
                val started = assertNotNull(downloads.start(target(1, 1), summary, media))
                val part = directory.resolve(started.fileName + ".part")
                withTimeout(5_000) { while (part.length() < 64 * 1024) delay(5) }
                downloads.pause(started.id)
                val paused = downloads.items.value.single()
                assertEquals(WatchDownload.Status.PAUSED, paused.status)
                assertTrue(paused.bytes in 1 until body.size.toLong())
                assertTrue(downloads.retry(started.id))
                val done = withTimeout(10_000) { downloads.items.first { it.single().status == WatchDownload.Status.DONE } }.single()
                assertContentEquals(body, downloads.fileOf(done).readBytes())
                assertTrue(server.requests.any { it.getHeader("Range")?.startsWith("bytes=${paused.bytes}-") == true })
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun interruptedBatchResolvesAgainAfterRestartAndNeverAppendsADifferentAddress() = runBlocking {
        val directory = Files.createTempDirectory("watch-batch-interrupted").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val body = ByteArray(1024 * 1024) { 7 }
        try {
            TestServer().use { server ->
                server.on("/old.mp4") { MockResponse().setBody(Buffer().write(ByteArray(body.size) { 3 })).throttleBody(32 * 1024, 20, TimeUnit.MILLISECONDS) }
                server.on("/fresh.mp4") { MockResponse().setBody(Buffer().write(body)) }
                val first = WatchDownloads(directory, OkHttpClient(), scope = firstScope)
                first.bindRepository(repo { listOf(stream(server.url("/old.mp4")).copy(sizeBytes = body.size.toLong())) })
                first.batches.enqueue(title, WatchBatchSelection.CUSTOM, 1, setOf(episode(1, 2).key))
                val started = withTimeout(5_000) { first.items.first { it.isNotEmpty() } }.single()
                val part = directory.resolve(started.fileName + ".part")
                withTimeout(5_000) { while (part.length() < 32 * 1024) delay(5) }
                firstScope.coroutineContext[Job]!!.cancelAndJoin()
                assertTrue(part.length() in 1 until body.size.toLong())
                val restored = WatchDownloads(directory, OkHttpClient(), scope = secondScope)
                restored.bindRepository(repo { listOf(stream(server.url("/fresh.mp4")).copy(sizeBytes = body.size.toLong())) })
                withTimeout(10_000) { restored.batches.items.first { it.single().status == WatchBatchStatus.DONE } }
                val done = restored.items.value.single()
                assertContentEquals(body, restored.fileOf(done).readBytes())
                assertEquals("bytes=0-${body.lastIndex}", server.requests.first { it.path == "/fresh.mp4" }.getHeader("Range"))
            }
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun qualityAndReleaseSelectionCannotPickAnUnknownOrWrongEpisode() {
        val good = stream("https://cdn.test/a.mp4").copy(filename = "Show.S01E02.720p.mp4")
        val wrong = good.copy(filename = "Show.S01E03.1080p.mp4", height = 1080)
        val pack = good.copy(filename = "Show.S01E02-E03.mp4", height = 1080)
        assertEquals(good, WatchBatchDownloads.choose(listOf(wrong, pack, good), 1080, null, target(1, 2)))
        assertNull(WatchBatchDownloads.choose(listOf(good.copy(height = null)), 720, null))
        assertNull(WatchBatchDownloads.choose(listOf(good), null, StreamHint(pluginId = "different")))
        assertEquals(series, episode(1, 2).key.seriesKey, "colon-containing add-on IDs retain series identity")
    }

    @Test fun customSelectionIsExactAndStorageFailureMakesNoMediaRequest() = runBlocking {
        val directory = Files.createTempDirectory("watch-batch-storage").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestServer().use { server ->
                val downloads = WatchDownloads(directory, OkHttpClient(), scope = scope, freeBytes = { 1 })
                downloads.bindRepository(repo { listOf(stream(server.url("/episode.mp4"))) })
                downloads.batches.enqueue(title, WatchBatchSelection.CUSTOM, 1, setOf(episode(1, 2).key, episode(2, 1).key))
                val failed = withTimeout(10_000) { downloads.batches.items.first { it.single().status == WatchBatchStatus.FAILED } }.single()
                assertEquals(listOf(episode(1, 2).key), failed.episodes.map { it.target.key })
                assertTrue(failed.episodes.single().error.orEmpty().contains("free space"))
                assertTrue(server.requests.isEmpty())
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun twoAtOnceFetchesTheNextEpisodeWhileTheFirstStillDownloads() = runBlocking {
        val directory = Files.createTempDirectory("watch-batch-parallel").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            TestServer().use { server ->
                server.text("/episode.mp4", "fixture episode")
                val asked = java.util.concurrent.atomic.AtomicInteger()
                val both = CompletableDeferred<Unit>()
                val downloads = WatchDownloads(directory, OkHttpClient(), scope = scope)
                val base = repo { t ->
                    if (asked.incrementAndGet() >= 2) both.complete(Unit)
                    // Each episode waits until a second one is being fetched beside it: one at a time would never get there.
                    withTimeout(5_000) { both.await() }
                    listOf(stream(server.url("/episode.mp4?" + t.key.value)))
                }
                downloads.bindRepository(object : WatchRepository by base {
                    override val preferences = kotlinx.coroutines.flow.MutableStateFlow(WatchPreferences(parallelDownloads = 2))
                })
                downloads.batches.enqueue(title, WatchBatchSelection.ALL, 1)
                val done = withTimeout(15_000) { downloads.batches.items.first { it.single().status in setOf(WatchBatchStatus.DONE, WatchBatchStatus.FAILED) } }.single()
                assertEquals(WatchBatchStatus.DONE, done.status, done.episodes.joinToString { "${it.status} ${it.error}" })
                assertEquals(3, downloads.items.value.size)
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }
}
