package com.cosmotune.podio

import java.nio.file.Files
import kotlin.test.*

class PodioShowRulesTest {
    private val a = PodcastShow("a", "Show A", "Host", "https://publisher.example/a")
    private val b = PodcastShow("b", "Show B", "Host", "https://publisher.example/b")
    private fun episode(id: String, show: PodcastShow, published: String = "") = PodcastEpisode(id, "Episode $id", show.title, "https://publisher.example/$id.mp3", published = published)
    private fun found(id: String, show: PodcastShow, published: String = "") = PodioRefresh.Found(episode(id, show, published), show)
    private fun item(id: String, show: PodcastShow) = PodioItem("podio:episode:$id", "Episode $id", show.title, "https://publisher.example/$id.mp3")

    @Test fun theVoiceBoostFollowsTheAppUnlessTheShowChooses() {
        val known = mapOf("a" to listOf("podio:episode:a1"), "b" to listOf("podio:episode:b1"))
        var saved = PodioShowRules.change(PodioSaved(shows = listOf(a, b), knownEpisodes = known), "a") { it.copy(voiceBoost = true) }
        assertEquals(PodioShowRules.VOICE_BOOST_GAIN, PodioShowRules.gainFor(saved, "podio:episode:a1"))
        assertEquals(1f, PodioShowRules.gainFor(saved, "podio:episode:b1"))
        saved = PodioShowRules.change(saved.copy(voiceBoost = true), "a") { it.copy(voiceBoost = false) }
        assertEquals(1f, PodioShowRules.gainFor(saved, "podio:episode:a1"))
        assertEquals(PodioShowRules.VOICE_BOOST_GAIN, PodioShowRules.gainFor(saved, "podio:episode:b1"))
        assertEquals(1f, PodioShowRules.gainFor(saved, "podio:radio:x"), "never a station")
    }

    @Test fun aShowAtEveryDefaultKeepsNoEntryAndFollowsTheAppsChoices() {
        var saved = PodioSaved(shows = listOf(a, b), autoDownloadNew = true)
        saved = PodioShowRules.change(saved, "a") { it.copy(autoDownload = false, skipIntroSeconds = 9_999) }
        assertEquals(PodioShowRules.MAX_SKIP_SECONDS, saved.showSettings.getValue("a").skipIntroSeconds)
        assertFalse(PodioShowRules.autoDownload(saved, "a"))
        assertTrue(PodioShowRules.autoDownload(saved, "b"), "a show without its own choice follows the app")
        saved = PodioShowRules.change(saved, "a") { PodioShowSettings() }
        assertTrue(saved.showSettings.isEmpty())
    }

    @Test fun aRefreshDownloadsQueuesAndAnnouncesByEachShowsChoice() {
        val saved = PodioShowRules.change(PodioShowRules.change(PodioSaved(shows = listOf(a, b)), "a") { it.copy(autoDownload = true, autoQueue = true) }, "b") { it.copy(notify = false) }
        val news = listOf(found("a2", a, "2026-11-02"), found("a1", a, "2026-11-01"), found("b1", b))
        val plan = PodioShowRules.afterRefresh(news, saved)
        assertEquals(listOf("a2", "a1"), plan.download.map { it.episode.id })
        assertEquals(listOf("a1", "a2"), plan.queue.map { it.episode.id }, "oldest first into Up next")
        assertEquals(listOf("a2", "a1"), plan.notify.map { it.episode.id })
    }

    @Test fun applyingARefreshPutsTheEpisodesInUpNext() {
        val file = Files.createTempDirectory("podio-rules").resolve("library.json").toFile()
        val library = PodioLibrary(file)
        library.update { PodioShowRules.change(it.copy(shows = listOf(a)), "a") { s -> s.copy(autoQueue = true) } }
        PodioShowRules.apply(listOf(found("a1", a)), library)
        assertEquals(listOf("a1"), library.queue().state.value.entries.map { it.episode.id })
        library.update { it.copy(shows = emptyList()) }
        assertTrue(library.state.value.showSettings.isEmpty(), "an unfollowed show's settings go with it")
    }

    @Test fun theShowOfAnEpisodeComesFromTheQueueTheRefreshOrItsTitle() {
        val saved = PodioSaved(shows = listOf(a, b), knownEpisodes = mapOf("b" to listOf("podio:episode:b1")))
        val queue = PodioQueueOrder.play(PodioQueueState(), PodioQueueEntry(episode("q", a), a))
        assertEquals("a", PodioShowRules.showOf("podio:episode:q", saved, queue))
        assertEquals("b", PodioShowRules.showOf("podio:episode:b1", saved))
        assertEquals("b", PodioShowRules.showOf("podio:episode:x", saved, showTitle = "show b"))
        assertNull(PodioShowRules.showOf("podio:episode:x", saved, showTitle = "Other"))
    }

    @Test fun theIntroIsSkippedOnlyFromTheStartAndTheOutroOnce() {
        val skipper = PodioSkipper()
        val id = "podio:episode:e"
        assertEquals(30_000L, skipper.check(id, 0, 3_600_000, true, 30_000, 60_000))
        assertNull(skipper.check(id, 30_000, 3_600_000, true, 30_000, 60_000), "once per episode")
        assertNull(skipper.check(id, 3_500_000, 3_600_000, true, 30_000, 60_000))
        assertEquals(3_599_750L, skipper.check(id, 3_545_000, 3_600_000, true, 30_000, 60_000))
        assertNull(skipper.check(id, 3_546_000, 3_600_000, true, 30_000, 60_000))
        // A resumed episode keeps its place.
        val resumed = PodioSkipper()
        assertNull(resumed.check(id, 600_000, 3_600_000, true, 30_000, 0))
        // Paused, nothing moves; a station is never skipped.
        assertNull(PodioSkipper().check(id, 0, 3_600_000, false, 30_000, 0))
        assertNull(PodioSkipper().check("podio:radio:x", 0, 0, true, 30_000, 0))
        // A trailer shorter than its skips plays whole.
        assertNull(PodioSkipper().check(id, 0, 50_000, true, 30_000, 30_000))
    }

    @Test fun playedDownloadsGoWhenChosenAndOnlyTheNewestAreKept() {
        val known = mapOf("a" to listOf("podio:episode:a3", "podio:episode:a2", "podio:episode:a1"))
        val downloads = listOf(item("a1", a), item("a2", a), item("a3", a), item("b1", b))
        var saved = PodioSaved(shows = listOf(a, b), knownEpisodes = known, completedEpisodes = setOf("podio:episode:b1"),
            progress = mapOf("podio:episode:b1" to 1L))
        assertTrue(PodioRetention.toRemove(downloads, saved, null).isEmpty(), "nothing goes unless chosen")
        saved = saved.copy(deletePlayedDownloads = true)
        assertEquals(listOf("podio:episode:b1"), PodioRetention.toRemove(downloads, saved, null).map { it.id })
        assertTrue(PodioRetention.toRemove(downloads, saved, "podio:episode:b1").isEmpty(), "never the playing one")
        saved = PodioShowRules.change(saved.copy(deletePlayedDownloads = false), "a") { it.copy(keepDownloads = 1) }
        assertEquals(listOf("podio:episode:a1", "podio:episode:a2"), PodioRetention.toRemove(downloads, saved, null).map { it.id }.sorted())
        // A started episode always stays.
        saved = saved.copy(progress = saved.progress + ("podio:episode:a1" to 5_000L))
        assertEquals(listOf("podio:episode:a2"), PodioRetention.toRemove(downloads, saved, null).map { it.id })
        assertEquals(listOf("podio:episode:b1"), PodioRetention.played(downloads, saved, null).map { it.id })
    }
}
