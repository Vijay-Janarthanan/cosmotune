package com.cosmotune.watch

import com.cosmotune.watch.meta.OmdbRatings
import com.cosmotune.watch.plugins.subtitles.SubDl
import com.cosmotune.watch.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WatchMetadataExtrasTest {
    @Test fun ratingSourcesAndScalesArePreservedAndInvalidScoresAreRejected() {
        val ratings = OmdbRatings.parse(Json.parseToJsonElement("""{"Ratings":[{"Source":"Internet Movie Database","Value":"8.7/10"},{"Source":"Rotten Tomatoes","Value":"83%"},{"Source":"Metacritic","Value":"150/100"}]}""").jsonObject)
        assertEquals(listOf(WatchRating("IMDb", "8.7/10"), WatchRating("Rotten Tomatoes", "83%")), ratings)
    }
    @Test fun subtitleSearchNeverHandsAnArchiveOrAnotherEpisodeToThePlayer() {
        val data = Json.parseToJsonElement("""{"subtitles":[{"url":"/subtitle/pack.zip","unpack_files":[{"url":"/subtitle/parent/ep2","season":1,"episode":2,"format":"srt","language":"EN","name":"Episode two"},{"url":"/subtitle/parent/ep3","season":1,"episode":3,"format":"srt","language":"EN"},{"url":"https://attacker.test/secret","season":1,"episode":2,"format":"srt"}]}]}""").jsonObject
        val target = PlayTarget.EpisodeOf(WatchKey.tmdbEpisode(1, 1, 2), ExternalIds(tmdb = 1), "A series", 1, 2)
        val subtitles = SubDl.parse(data, target)
        assertEquals(1, subtitles.size)
        assertEquals("https://dl.subdl.com/subtitle/parent/ep2", subtitles.single().url)
    }
}
