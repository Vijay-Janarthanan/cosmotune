package com.cosmotune.watch

import com.cosmotune.watch.meta.AnimeCandidate
import com.cosmotune.watch.meta.AnimeMatch
import com.cosmotune.watch.meta.AnimeSeason
import com.cosmotune.watch.model.EpisodeKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimeMatchTest {
    private val titan = listOf(
        AnimeCandidate(16498, listOf("Shingeki no Kyojin", "Attack on Titan"), 2013, 25, false),
        AnimeCandidate(25777, listOf("Shingeki no Kyojin Season 2", "Attack on Titan Season 2"), 2017, 12, false),
        AnimeCandidate(35760, listOf("Shingeki no Kyojin Season 3", "Attack on Titan Season 3"), 2018, 12, false),
    )

    @Test fun theFirstSeasonIsTheEntryOfThatNameAndYear() {
        assertEquals(AnimeSeason(16498), AnimeMatch.pick(titan, "Attack on Titan", 1, 2013, 0, 2013))
    }

    @Test fun aLaterSeasonIsTheEntryThatBeganThatYear() {
        assertEquals(AnimeSeason(25777), AnimeMatch.pick(titan, "Attack on Titan", 2, 2017, 25, 2013))
        assertEquals(AnimeSeason(35760), AnimeMatch.pick(titan, "Attack on Titan", 3, 2018, 37, 2013))
    }

    @Test fun aLongShowKeptAsOneEntryCountsOnFromTheSeasonsBefore() {
        val onePiece = listOf(AnimeCandidate(21, listOf("One Piece"), 1999, null, true), AnimeCandidate(459, listOf("One Piece Film"), 2000, 1, false))
        assertEquals(AnimeSeason(21, 61), AnimeMatch.pick(onePiece, "One Piece", 2, 2001, 61, 1999))
        val ended = listOf(AnimeCandidate(269, listOf("Bleach"), 2004, 366, false))
        assertEquals(AnimeSeason(269, 300), AnimeMatch.pick(ended, "Bleach", 9, 2010, 300, 2004))
        assertNull(AnimeMatch.pick(ended, "Bleach", 17, 2022, 366, 2004), "Thousand-Year Blood War is past the old entry's end")
    }

    @Test fun anotherShowIsNeverPicked() {
        assertNull(AnimeMatch.pick(titan, "Demon Slayer", 1, 2019, 0, 2019))
    }

    @Test fun readsJikanSearchAndEpisodeMarks() {
        val search = Json.parseToJsonElement("""{"data":[{"mal_id":20,"title":"Naruto","title_english":"Naruto","year":null,
            "aired":{"from":"2002-10-03T00:00:00+00:00"},"episodes":220,"airing":false,"titles":[{"type":"Default","title":"Naruto"}]}]}""")
        assertEquals(listOf(AnimeCandidate(20, listOf("Naruto"), 2002, 220, false)), AnimeMatch.candidates(search))
        val episodes = Json.parseToJsonElement("""{"pagination":{"has_next_page":false},"data":[
            {"mal_id":25,"title":"A","filler":false,"recap":false},{"mal_id":26,"title":"B","filler":true,"recap":false},
            {"mal_id":27,"title":"C","filler":false,"recap":true}]}""")
        assertEquals(mapOf(26 to EpisodeKind.FILLER, 27 to EpisodeKind.RECAP), AnimeMatch.episodeKinds(episodes))
    }
}
