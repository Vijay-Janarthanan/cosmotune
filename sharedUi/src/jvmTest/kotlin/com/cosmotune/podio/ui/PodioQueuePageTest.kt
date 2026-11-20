package com.cosmotune.podio.ui

import com.cosmotune.podio.PodcastChapter
import com.cosmotune.podio.PodcastEpisode
import com.cosmotune.podio.PodcastShow
import com.cosmotune.podio.PodioQueueEntry
import com.cosmotune.podio.PodioQueueOrder
import com.cosmotune.podio.PodioQueueState
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class PodioQueuePageTest {
    private val show = PodcastShow("show", "Show", "Host", "https://publisher.example/feed", artwork = "https://publisher.example/show.jpg")
    private fun entry(id: String, seconds: Long = 1800) = PodioQueueEntry(PodcastEpisode(id, "Episode $id", "Show", "https://publisher.example/$id.mp3", durationSeconds = seconds), show)

    @Test fun theQueuePageGroupsLikeMusicsQueueWithIndexesIntoTheQueue() {
        var state = PodioQueueOrder.play(PodioQueueState(), entry("now"))
        state = PodioQueueOrder.add(state, entry("a"))
        state = PodioQueueOrder.add(state, entry("b"))
        state = PodioQueueOrder.playNext(state, entry("p"))
        val tracks = podioQueueTracks(state)
        assertEquals("podio:episode:now", tracks.nowPlaying?.song?.videoId)
        assertEquals(listOf(0), tracks.user.map { it.timelineIndex })
        assertEquals(listOf(1, 2), tracks.context.map { it.timelineIndex })
        assertEquals(listOf("podio:episode:a", "podio:episode:b"), tracks.context.map { it.song.videoId })
        // The rows keep their keys through a move, so a drag is not ended by its own swap.
        val moved = podioQueueTracks(PodioQueueOrder.move(state, 1, 2))
        assertEquals(tracks.context.map { it.key }.reversed(), moved.context.map { it.key })
        // An episode without its own picture shows its show's.
        assertEquals("https://publisher.example/show.jpg", tracks.user.single().song.thumbnailUrl)
        assertEquals("3 episodes · 1 h 30 min", queueLabel(state))
    }

    @Test fun chaptersSayWhichIsOnAndWherePreviousGoes() {
        val chapters = listOf(PodcastChapter(0, "Cold open"), PodcastChapter(60_000, "One"), PodcastChapter(300_000, "Two"))
        assertEquals(1, PodioFormat.chapterAt(chapters, 61_000))
        assertEquals(-1, PodioFormat.chapterAt(emptyList(), 5_000))
        // Inside the first three seconds of a chapter, previous goes to the one before; later, to its own start.
        assertEquals(0, PodioFormat.previousChapterStart(chapters, 61_000))
        assertEquals(60_000, PodioFormat.previousChapterStart(chapters, 90_000))
        assertEquals(0, PodioFormat.previousChapterStart(chapters, 2_000))
    }

    @Test fun speedsAndDaysAreWordedAsTheBoardWritesThem() {
        assertEquals("1×", PodioFormat.speed(1f))
        assertEquals("1.25×", PodioFormat.speed(1.25f))
        assertEquals("0.75×", PodioFormat.speed(0.75f))
        assertEquals("8 min ago", PodioFormat.ago(0, 8 * 60_000))
        val today = LocalDate.of(2026, 11, 20)
        fun at(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).plusHours(10).toInstant().toEpochMilli()
        val days = PodioFormat.byDay(listOf(at(today), at(today.minusDays(1)), null, at(today)), today, ZoneOffset.UTC) { it }
        assertEquals(listOf("Today" to 2, "Yesterday" to 1, "Earlier" to 1), days.map { it.first to it.second.size })
    }
}
