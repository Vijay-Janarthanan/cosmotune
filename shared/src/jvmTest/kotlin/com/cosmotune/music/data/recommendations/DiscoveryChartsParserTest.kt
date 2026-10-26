package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.innertube.InnertubeParser
import kotlinx.serialization.json.*
import kotlin.test.*

class DiscoveryChartsParserTest {
    @Test fun regionalPlaylistChartsSurviveWithoutReintroducingUnnavigableVideoCards() {
        val response = Json.parseToJsonElement("""{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Video charts"}]}}},"contents":[
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Trending 20 India"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLregional-chart"}}}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Unknown video feed"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"FEunsupported"}}}}
          ]}}
        ]}}}}]}}}""").jsonObject
        assertTrue(InnertubeParser.parseHome(response).isEmpty())
        val charts = InnertubeParser.parseDiscoveryCharts(response).single()
        assertEquals("Trending charts", charts.title)
        assertEquals(listOf("VLregional-chart"), charts.items.map { it.browseId })
    }
}
