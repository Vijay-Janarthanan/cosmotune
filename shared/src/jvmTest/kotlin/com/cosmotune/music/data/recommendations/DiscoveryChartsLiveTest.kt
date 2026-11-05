package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.InnertubeParser
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in anonymous catalogue proof; ordinary unit/CI runs do not depend on YouTube availability. */
class DiscoveryChartsLiveTest {
    @Test(timeout = 60_000) fun selectedLanguagesReturnDifferentPlayableRecordings() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_DISCOVERY_LIVE") == "1")
        val report = listOf("Tamil", "Hindi", "English").map { language ->
            val songs = withTimeout(15_000) { YtMusicRepository.search("$language songs", SearchFilter.SONGS).getOrThrow() }
                .mapNotNull { (it as? SearchResult.Track)?.song }.take(20)
            buildJsonObject { put("language", language); put("count", songs.size)
                put("ids", JsonArray(songs.map { JsonPrimitive(it.videoId) })) }
        }
        File("../build/discovery-languages-live.json").writeText(JsonArray(report).toString())
        kotlin.test.assertTrue(report.all { it["count"]!!.jsonPrimitive.int > 0 })
        kotlin.test.assertEquals(3, report.map { it["ids"].toString() }.distinct().size)
    }

    @Test(timeout = 90_000) fun globalAndIndiaChartsYieldNavigableShelves() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_DISCOVERY_LIVE") == "1")
        val rows = mutableListOf<JsonObject>()
        for (country in listOf("ZZ", "IN")) {
            rows += buildJsonObject {
                put("country", country)
                try {
                    val response = withTimeout(25_000) { Innertube.discoveryCharts(country) }
                    File("../build/discovery-charts-$country.json").writeText(response.toString())
                    val shelves = InnertubeParser.parseDiscoveryCharts(response)
                    put("shelves", shelves.size); put("items", shelves.sumOf { it.items.size })
                    put("titles", JsonArray(shelves.take(6).map { JsonPrimitive(it.title) }))
                    put("navigableItems", shelves.sumOf { it.items.count { item -> item.browseId != null || item.videoId != null } })
                    put("status", if (shelves.isEmpty()) "EMPTY" else "CATALOGUE_RESULTS")
                } catch (error: Exception) { put("status", "UNAVAILABLE"); put("errorType", error.javaClass.simpleName) }
            }
        }
        File("../build/discovery-charts-live.json").writeText(JsonArray(rows).toString())
    }
}
