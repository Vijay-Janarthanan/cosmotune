package com.cosmotune.music.desktop

import coil3.PlatformContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopArtworkCacheTest {
    @Test
    fun theDecodedCacheHasABoundedHeapBudget() {
        assertTrue(DesktopArtworkCache.budgetBytes in 32L * 1024 * 1024..128L * 1024 * 1024)
    }

    @Test
    fun nearbyCoverSizesReuseTheSameCdnUrlAndDecodeSize() = runBlocking {
        val url = "https://lh3.googleusercontent.com/cover=w72-h72"
        val small = DesktopArtworkCache.request(PlatformContext.INSTANCE, url, 110)
        val large = DesktopArtworkCache.request(PlatformContext.INSTANCE, url, 159)
        assertEquals("https://lh3.googleusercontent.com/cover=w160-h160", small.data)
        assertEquals(small.data, large.data)
        assertEquals(coil3.size.Size(160, 160), small.sizeResolver.size())
        assertEquals(small.sizeResolver.size(), large.sizeResolver.size())
    }
}
