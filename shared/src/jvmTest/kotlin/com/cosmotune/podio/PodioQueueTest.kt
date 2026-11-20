package com.cosmotune.podio

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

class PodioQueueTest {
    private val show = PodcastShow("show", "Show", "Host", "https://publisher.example/feed")
    private fun entry(id: String, seconds: Long = 600) = PodioQueueEntry(PodcastEpisode(id, "Episode $id", "Show", "https://publisher.example/$id.mp3", durationSeconds = seconds), show)
    private fun ids(state: PodioQueueState) = state.entries.map { it.episode.id }

    @Test fun playNextGoesAfterTheOtherPlayNextEpisodesAndAheadOfUpNext() {
        var state = PodioQueueState()
        state = PodioQueueOrder.add(state, entry("a"))
        state = PodioQueueOrder.add(state, entry("b"))
        state = PodioQueueOrder.playNext(state, entry("c"))
        state = PodioQueueOrder.playNext(state, entry("d"))
        assertEquals(listOf("c", "d", "a", "b"), ids(state))
        assertEquals(listOf("c", "d"), state.playNext.map { it.episode.id })
        // Play next on an episode already in Up next moves it, never twice.
        state = PodioQueueOrder.playNext(state, entry("b"))
        assertEquals(listOf("c", "d", "b", "a"), ids(state))
        assertEquals(3, state.playNext.size)
    }

    @Test fun addingKeepsAQueuedEpisodeWhereItIsAndNeverQueuesThePlayingOne() {
        var state = PodioQueueOrder.play(PodioQueueState(), entry("now"))
        state = PodioQueueOrder.add(state, entry("now"))
        state = PodioQueueOrder.playNext(state, entry("now"))
        assertTrue(state.entries.isEmpty())
        state = PodioQueueOrder.playNext(state, entry("a"))
        state = PodioQueueOrder.add(state, entry("a"))
        assertEquals(listOf("a"), ids(state))
        assertTrue(state.entries.single().playNext)
    }

    @Test fun movesStayInsideTheirGroup() {
        var state = PodioQueueState()
        listOf("a", "b", "c").forEach { state = PodioQueueOrder.add(state, entry(it)) }
        listOf("x", "y").forEach { state = PodioQueueOrder.playNext(state, entry(it)) }
        assertEquals(listOf("x", "y", "a", "b", "c"), ids(state))
        assertEquals(listOf("x", "y", "b", "c", "a"), ids(PodioQueueOrder.move(state, 2, 4)))
        // An Up next episode dragged up stops at the top of its own group.
        assertEquals(listOf("x", "y", "c", "a", "b"), ids(PodioQueueOrder.move(state, 4, 0)))
        // A Play next episode dragged down stops at the end of its own group.
        assertEquals(listOf("y", "x", "a", "b", "c"), ids(PodioQueueOrder.move(state, 0, 4)))
        assertSame(state, PodioQueueOrder.move(state, 9, 0))
    }

    @Test fun removeRestoreClearAndShuffle() {
        var state = PodioQueueState()
        listOf("a", "b", "c", "d").forEach { state = PodioQueueOrder.add(state, entry(it)) }
        state = PodioQueueOrder.playNext(state, entry("p"))
        val removed = state.entries[2]
        val without = PodioQueueOrder.remove(state, removed.id)
        assertEquals(listOf("p", "a", "c", "d"), ids(without))
        assertEquals(ids(state), ids(PodioQueueOrder.restore(without, removed, 2)))
        assertEquals(listOf("a", "b", "c", "d"), ids(PodioQueueOrder.clearPlayNext(state)))
        assertTrue(PodioQueueOrder.clear(state).entries.isEmpty())
        val shuffled = PodioQueueOrder.shuffle(state, Random(7))
        assertEquals("p", shuffled.entries.first().episode.id)
        assertEquals(setOf("a", "b", "c", "d"), shuffled.upNext.map { it.episode.id }.toSet())
        assertEquals(3000, state.seconds)
    }

    @Test fun thePlayerMovingOnTakesTheEpisodeOutOfTheQueue() {
        var state = PodioQueueOrder.play(PodioQueueState(), entry("now"))
        listOf("a", "b").forEach { state = PodioQueueOrder.add(state, entry(it)) }
        state = PodioQueueOrder.started(state, entry("a").id)
        assertEquals("a", state.current?.episode?.id)
        assertEquals(listOf("b"), ids(state))
        // An episode from elsewhere leaves the queue as it is.
        assertSame(state, PodioQueueOrder.started(state, "podio:episode:elsewhere"))
    }

    @Test fun theQueueIsKeptOnTheDiskAndSharedByEveryReaderOfTheFile() {
        val directory = Files.createTempDirectory("podio-queue").toFile()
        val library = PodioLibrary(java.io.File(directory, "library.json"))
        val queue = library.queue()
        queue.update { PodioQueueOrder.play(it, entry("now")) }
        queue.update { PodioQueueOrder.add(it, entry("a")) }
        queue.update { PodioQueueOrder.playNext(it, entry("b")) }
        queue.update { it.copy(keepGoing = true) }
        assertTrue(java.io.File(directory, "queue.json").isFile)
        // The playback side's tracker and the page's queue are one store: what one changes the other sees.
        val other = PodioQueue.besides(java.io.File(directory, "library.json"))
        assertEquals(listOf("b", "a"), ids(other.state.value))
        other.started(entry("b").id)
        assertEquals("b", queue.state.value.current?.episode?.id)
        other.started("podio:radio:station")
        assertEquals("b", queue.state.value.current?.episode?.id)
        // After a restart (a fresh read of the file) the order, the current episode and Keep going come back.
        val restored = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(PodioQueueState.serializer(), java.io.File(directory, "queue.json").readText())
        assertEquals(listOf("a"), ids(restored))
        assertEquals("b", restored.current?.episode?.id)
        assertTrue(restored.keepGoing)
    }

    @Test fun theProgressTrackerTellsTheQueueWhenThePlayerMovesOn() {
        val directory = Files.createTempDirectory("podio-tracker-queue").toFile()
        val library = PodioLibrary(java.io.File(directory, "library.json"))
        val queue = library.queue()
        queue.update { PodioQueueOrder.add(PodioQueueOrder.play(it, entry("now")), entry("next")) }
        var clock = 0L
        val tracker = PodioProgressTracker(library, clockMs = { clock })
        tracker.observe(entry("now").id, 1_000, playing = true, durationMs = 600_000)
        clock += 1_000
        tracker.observe(entry("next").id, 0, playing = true, loading = true)
        assertEquals("now", queue.state.value.current?.episode?.id)
        tracker.observe(entry("next").id, 500, playing = true, durationMs = 600_000)
        assertEquals("next", queue.state.value.current?.episode?.id)
        assertTrue(queue.state.value.entries.isEmpty())
        tracker.close()
    }
}

class PodioRefreshTest {
    private val show = PodcastShow("show", "Show", "Host", "https://publisher.example/feed")
    private val other = PodcastShow("other", "Other", "Host", "https://publisher.example/other")
    private fun episode(id: String, date: String = "") = PodcastEpisode(id, "Episode $id", "Show", "https://publisher.example/$id.mp3", published = date)

    @Test fun aShowSeenForTheFirstTimeOnlyLearnsItsEpisodes() {
        val first = PodioRefresh.diff(emptyMap(), listOf(show to listOf(episode("a"), episode("b"))))
        assertTrue(first.found.isEmpty())
        assertEquals(listOf("podio:episode:a", "podio:episode:b"), first.known["show"])
        val second = PodioRefresh.diff(first.known, listOf(show to listOf(episode("c"), episode("a"), episode("b")), other to listOf(episode("z"))))
        assertEquals(listOf("c"), second.found.map { it.episode.id })
        assertEquals(show, second.found.single().show)
        assertEquals(listOf("podio:episode:z"), second.known["other"])
    }

    @Test fun whatThePageSawIsLearntOnlyForShowsTheRefreshKnows() {
        val known = mapOf("show" to listOf("podio:episode:a"))
        val learnt = PodioRefresh.learn(known, listOf(show to listOf(episode("b")), other to listOf(episode("z"))))
        assertEquals(listOf("podio:episode:b", "podio:episode:a"), learnt["show"])
        assertNull(learnt["other"])
        // A later refresh with the whole feed announces only what neither of them had.
        val later = PodioRefresh.diff(learnt, listOf(show to listOf(episode("c"), episode("b"), episode("a"))))
        assertEquals(listOf("c"), later.found.map { it.episode.id })
    }

    @Test fun aFailedShowKeepsWhatWasKnownOfIt() {
        val known = mapOf("show" to listOf("podio:episode:a"), "other" to listOf("podio:episode:z"))
        val result = PodioRefresh.diff(known, listOf(other to listOf(episode("z"))))
        assertTrue(result.found.isEmpty())
        assertEquals(listOf("podio:episode:a"), result.known["show"])
    }

    @Test fun newestFirstUsesTheDatesAndKeepsTheFeedOrderOtherwise() {
        val sorted = PodioRefresh.newestFirst(listOf(episode("old", "Mon, 02 Nov 2026 10:00:00 GMT"), episode("undated"), episode("new", "2026-11-19T08:00:00Z")))
        assertEquals(listOf("new", "old", "undated"), sorted.map { it.id })
    }

    @Test fun theNotificationNamesTheCountAndTheFirstEpisodes() {
        val found = (1..7).map { PodioRefresh.Found(episode("e$it"), show) }
        val (title, lines) = PodioRefresh.notificationText(found)
        assertEquals("7 new episodes", title)
        assertEquals(5, lines.size)
        assertEquals("Show · Episode e1", lines.first())
        assertEquals("1 new episode", PodioRefresh.notificationText(found.take(1)).first)
    }

    @Test fun libraryKeepsRefreshMemoryOnlyForFollowedShows() {
        val file = Files.createTempDirectory("podio-refresh").resolve("library.json").toFile()
        val library = PodioLibrary(file)
        library.update { it.copy(shows = listOf(show), knownEpisodes = mapOf("show" to (1..100).map { n -> "podio:episode:$n" }, "gone" to listOf("x")),
            speedByShow = mapOf("show" to 1.5f, "gone" to 2f)) }
        assertEquals(setOf("show"), library.state.value.knownEpisodes.keys)
        assertEquals(PodioRefresh.KNOWN_PER_SHOW, library.state.value.knownEpisodes.getValue("show").size)
        assertEquals(mapOf("show" to 1.5f), library.state.value.speedByShow)
        assertFalse(library.state.value.autoDownloadNew)
        assertTrue(library.state.value.refreshInBackground)
    }
}
