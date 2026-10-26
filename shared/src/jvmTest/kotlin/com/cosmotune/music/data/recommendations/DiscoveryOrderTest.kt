package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.HomeShelf
import kotlin.test.*

class DiscoveryOrderTest {
    @Test fun selectedLanguageAndRegionalChartsLeadRegardlessOfResponseOrder() {
        val core = HomeShelf("Personal music", emptyList())
        val provider = HomeShelf("Provider", emptyList(), sourceId = "sflx-fixture")
        val india = HomeShelf("India charts", emptyList(), sourceId = "discovery:charts:IN")
        val tamil = HomeShelf("Discover Tamil", emptyList(), sourceId = "discovery:language:ta")
        for (incoming in listOf(listOf(core, provider, india, tamil), listOf(india, core, tamil, provider))) {
            assertEquals(listOf(tamil, india, provider, core), DiscoveryFeed.order(incoming))
        }
        assertTrue(DiscoveryPreferences.languages.any { it.first == "en" })
        assertTrue(DiscoveryPreferences.languages.any { it.first == "ta" })
        assertTrue(DiscoveryPreferences.languages.size <= 14)
    }
}
