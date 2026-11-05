package com.cosmotune.video.home

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LibraryCodec
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import kotlin.test.*

class HomeDiscoveryTest {
    private val sample = FakeVideoSource.videos.first()
    @Test fun interestWindowIncludesDistinctCreatorsAndRemainsBounded() {
        val history = (1..12).map { HistoryEntry(sample.copy(id = "recent-$it", channelId = "same"), 100L + it, 30_000, 60_000, 1) } +
            (1..4).map { HistoryEntry(sample.copy(id = "other-$it", channelId = "creator-$it"), it.toLong(), 30_000, 60_000, 1) }
        val interests = HomePlanner.interestWatches(history)
        assertEquals(8, interests.size)
        assertEquals(8, interests.distinctBy { it.item.id }.size)
        assertEquals(5, interests.map { it.item.channelId }.distinct().size)
    }
    @Test fun feedbackIsLocalPersistentBoundedAndWorksWithoutSingerOrLanguageGuesses() {
        val settings = VideoSettings(homeAccountEnabled = false, homeHiddenVideos = setOf("hide-one"), homeHiddenChannels = setOf("blocked"))
        val restored = LibraryCodec.decodeSettings(LibraryCodec.encodeSettings(settings))
        assertEquals(settings, restored)
        val rows = listOf(sample.copy(id = "hide-one", channelId = null), sample.copy(id = "two", channelId = "blocked"), sample.copy(id = "keep", channelId = null))
        assertEquals(listOf("keep"), HomePlanner.feedback(rows, restored.homeHiddenVideos, restored.homeHiddenChannels).map { it.id })
        val many = restored.copy(homeHiddenVideos = (1..1500).map { "id-$it" }.toSet(), homeHiddenChannels = (1..500).map { "channel-$it" }.toSet())
        val bounded = LibraryCodec.decodeSettings(LibraryCodec.encodeSettings(many))
        assertEquals(1000, bounded.homeHiddenVideos.size); assertEquals(300, bounded.homeHiddenChannels.size)
        assertFalse("id-1" in bounded.homeHiddenVideos); assertTrue("id-1500" in bounded.homeHiddenVideos)
    }
    @Test fun localRankingPromotesAnUnwatchedFreshCandidateWithoutDroppingCreatorsOrDuplicates() {
        val watched = sample.copy(id = "watched", channelId = "a", uploadedAtMs = 1)
        val fresh = sample.copy(id = "fresh", channelId = "b", uploadedAtMs = 900_000)
        val other = sample.copy(id = "other", channelId = "c")
        val ranked = HomePlanner.rankForHome(listOf(watched, fresh, fresh, other),
            listOf(HistoryEntry(watched, 999_000, 60_000, 60_000, 1)), 1_000_000)
        assertEquals("fresh", ranked.first().id)
        assertEquals(setOf("watched", "fresh", "other"), ranked.map { it.id }.toSet())
        assertEquals(3, ranked.size)
    }
}
