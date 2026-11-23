package com.cosmotune.ui

import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.pages.SmartDownloads
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartDownloadsTest {
    private fun song(id: String) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null)
    private val ranked = (1..10).map { song("s$it") }

    @Test
    fun `the top songs not on the device are downloaded`() {
        val plan = SmartDownloads.plan(ranked, 3, onDevice = { it == "s2" }, kept = emptySet())
        assertEquals(listOf("s1", "s3"), plan.download.map { it.videoId })
        assertEquals(emptyList(), plan.release)
    }

    @Test
    fun `a fetched song is released only once it falls well out of the top`() {
        // Count 4: songs down to 6th place (4 x 1.5) stay; the 8th goes.
        val plan = SmartDownloads.plan(ranked, 4, onDevice = { true }, kept = setOf("s6", "s8"))
        assertEquals(listOf("s8"), plan.release)
    }

    @Test
    fun `turning it off releases everything it fetched`() {
        val plan = SmartDownloads.plan(emptyList(), 0, onDevice = { true }, kept = setOf("s1"))
        assertEquals(listOf("s1"), plan.release)
        assertEquals(emptyList(), plan.download)
    }

    @Test
    fun `songs already fetched are not fetched again`() {
        val plan = SmartDownloads.plan(ranked, 2, onDevice = { false }, kept = setOf("s1"))
        assertEquals(listOf("s2"), plan.download.map { it.videoId })
    }
}
