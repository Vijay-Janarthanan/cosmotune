package com.cosmotune.watch

import com.cosmotune.watch.meta.IntroSkips
import kotlinx.serialization.json.*
import kotlin.test.*

class IntroSkipsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun theIntroDbOpenEndsRunFromTheStartOrToTheEnd() {
        val data = json("""{"tmdb_id":603,"type":"movie","intro":[{"start_ms":null,"end_ms":40000}],"recap":[{"start_ms":40000,"end_ms":95000}],"credits":[{"start_ms":7726802,"end_ms":null}]}""")
        val skips = IntroSkips.parseTheIntroDb(data, 8_160_000)
        assertEquals(listOf("Skip intro", "Skip recap", "Skip credits"), skips.map { it.label })
        assertEquals(0L, skips[0].startMs)
        assertEquals(8_160_000L, skips[2].endMs)
    }

    @Test fun theIntroDbDropsTimesOutsideThisCopy() {
        // A shorter cut: the credits would start after this copy ends, and an intro longer than five minutes is not an intro.
        val data = json("""{"intro":[{"start_ms":0,"end_ms":400000}],"credits":[{"start_ms":3000000,"end_ms":null}],"preview":[{"start_ms":10,"end_ms":5}]}""")
        assertTrue(IntroSkips.parseTheIntroDb(data, 2_500_000).isEmpty())
    }

    @Test fun introDbGivesOneIntroInMilliseconds() {
        val data = json("""{"imdb_id":"tt0944947","season":1,"episode":2,"start_sec":6,"end_sec":105,"start_ms":6000,"end_ms":105000,"confidence":1}""")
        assertEquals(listOf(com.cosmotune.watch.model.WatchSkip(6_000, 105_000, "Skip intro")), IntroSkips.parseIntroDb(data, 3_000_000))
        assertTrue(IntroSkips.parseIntroDb(json("""{"error":"Not found."}"""), 3_000_000).isEmpty())
    }

    @Test fun addressesAskForTheEpisodeOrTheFilm() {
        assertEquals("https://api.theintrodb.org/v2/media?tmdb_id=1399&season=1&episode=2", IntroSkips.theIntroDbUrl(1399, 1, 2))
        assertEquals("https://api.theintrodb.org/v2/media?tmdb_id=603", IntroSkips.theIntroDbUrl(603, null, null))
        assertEquals("https://api.introdb.app/intro?imdb_id=tt0944947&season=1&episode=2", IntroSkips.introDbUrl("tt0944947", 1, 2))
        assertNull(IntroSkips.introDbUrl("not-an-id", 1, 2))
    }

    @Test fun mergedSkipsKeepTheFirstSourceWhereTheyOverlap() {
        val anime = listOf(com.cosmotune.watch.model.WatchSkip(0, 90_000, "Skip opening"))
        val db = listOf(com.cosmotune.watch.model.WatchSkip(5_000, 80_000, "Skip intro"), com.cosmotune.watch.model.WatchSkip(1_300_000, 1_440_000, "Skip credits"))
        assertEquals(listOf("Skip opening", "Skip credits"), IntroSkips.merge(anime, db).map { it.label })
    }
}
