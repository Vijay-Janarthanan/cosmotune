package com.cosmotune.watch

import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStreamListTest {
    @Test fun filteringKeepsRankAndUsesProviderIdentity() {
        val first = stream("a", 1080)
        val second = stream("b", 1080)
        val third = stream("a", 720)
        val unknown = stream("b", null)
        val streams = listOf(first, second, third, unknown)
        assertEquals(listOf("1080p", "720p", "Unknown"), WatchStreamFilter.qualities(streams))
        assertEquals(listOf(first, second), WatchStreamFilter(quality = "1080p").applyTo(streams))
        assertEquals(listOf(first, third), WatchStreamFilter(providerId = "a").applyTo(streams))
        assertEquals(listOf(second), WatchStreamFilter("1080p", "b").applyTo(streams))
        assertEquals(emptyList(), WatchStreamFilter("720p", "b").applyTo(streams))
    }

    @Test fun metadataDistinguishesStreamDurationTitleRuntimeAndLive() {
        val source = stream("a", 1080)
        assertEquals("Size unknown · Duration unknown", source.watchMetadata())
        assertEquals("Size unknown · Title runtime 90 min", source.watchMetadata(titleRuntimeMin = 90))
        val measured = source.copy(sizeBytes = 1_610_612_736L, durationMs = 3_723_000L)
        assertEquals("1.5 GB · 1:02:03", measured.watchMetadata(titleRuntimeMin = 90))
        assertEquals("1.5 GB · 1:02:04", measured.watchMetadata(playingDurationMs = 3_724_000L))
        assertEquals("1.5 GB · Live", measured.copy(isLive = true).watchMetadata())
    }

    private fun stream(provider: String, height: Int?) = Stream(
        StreamSource(provider, "Provider", "P"), "Copy", url = "https://example.test/copy", protocol = StreamProtocol.PROGRESSIVE, height = height,
    )
}
