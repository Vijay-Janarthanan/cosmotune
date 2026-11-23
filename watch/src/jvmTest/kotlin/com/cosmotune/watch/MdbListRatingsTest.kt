package com.cosmotune.watch

import com.cosmotune.watch.meta.MdbListRatings
import com.cosmotune.watch.model.WatchRating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class MdbListRatingsTest {
    @Test fun eachSourceKeepsItsOwnScaleAndMissingOnesAreLeftOut() {
        val data = Json.parseToJsonElement("""{"title":"The Matrix","ratings":[
            {"source":"imdb","value":8.7,"score":87},{"source":"metacritic","value":73},{"source":"metacriticuser","value":8.9},
            {"source":"trakt","value":85},{"source":"tomatoes","value":83},{"source":"popcorn","value":85},{"source":"tmdb","value":82},
            {"source":"letterboxd","value":4.2},{"source":"rogerebert","value":null},{"source":"myanimelist","value":0}]}""").jsonObject
        assertEquals(listOf(
            WatchRating("IMDb", "8.7/10"), WatchRating("Metacritic", "73/100"), WatchRating("Trakt", "85%"), WatchRating("Rotten Tomatoes", "83%"),
            WatchRating("Audience", "85%"), WatchRating("TMDB", "82%"), WatchRating("Letterboxd", "4.2/5"),
        ), MdbListRatings.parse(data))
        assertEquals(emptyList(), MdbListRatings.parse(Json.parseToJsonElement("""{"response":false,"error":"Invalid API key"}""").jsonObject))
    }
}
