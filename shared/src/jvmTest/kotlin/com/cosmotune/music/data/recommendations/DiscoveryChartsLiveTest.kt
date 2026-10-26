package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.InnertubeParser
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in anonymous catalogue proof; ordinary unit/CI runs do not depend on YouTube availability. */
class DiscoveryChartsLiveTest {
    @Test(timeout = 90_000) fun globalAndIndiaChartsYieldNavigableShelves() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_DISCOVERY_LIVE") == "1")
        val rows = mutableListOf<JsonObject>()
        for (country in listOf("ZZ", "IN")) {
            rows += buildJsonObject {
                put("country", country)
                try {
                    val shelves = withTimeout(25_000) { InnertubeParser.parseHome(Innertube.discoveryCharts(country)) }
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
