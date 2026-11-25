package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.HomeShelf
import kotlin.test.*

class DiscoveryOrderTest {
    @Test fun chosenHomeLeadKeepsOtherSectionsAvailable() {
        val tamil = HomeShelf("Discover Tamil", emptyList(), sourceId = "discovery:language:ta")
        val mix = HomeShelf("Daily mix", emptyList(), sourceId = "discovery:mix:daily_mix")
        val following = HomeShelf("Followed releases", emptyList(), sourceId = "discovery:mix:followed_releases")
        val shelves = listOf(tamil, mix, following)
        assertEquals(listOf(mix, tamil, following), DiscoveryFeed.homeOrder(shelves, DiscoveryLead.PERSONAL))
        assertEquals(listOf(following, tamil, mix), DiscoveryFeed.homeOrder(shelves, DiscoveryLead.FOLLOWING))
        assertEquals(shelves, DiscoveryFeed.homeOrder(shelves, DiscoveryLead.REGIONAL))
    }
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
