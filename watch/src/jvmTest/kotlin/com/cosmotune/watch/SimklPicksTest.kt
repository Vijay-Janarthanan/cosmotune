package com.cosmotune.watch

import com.cosmotune.watch.meta.SimklPicks
import com.cosmotune.watch.model.ExternalIds
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimklPicksTest {
    @Test fun simklsAlsoLikedAreFoundAgainInTheCatalogue() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/search/id", """[{"type":"movie","ids":{"simkl":53536,"slug":"the-matrix"}}]""")
            s.json("/movies/53536", """{"title":"The Matrix","users_recommendations":[{"title":"Dark City","year":1998,"ids":{"simkl":1}},
                {"title":"Unknown Thing","year":2001,"ids":{"simkl":2}},{"title":"Equilibrium","year":2002,"ids":{"simkl":3}}]}""")
            val catalogue = mapOf("Dark City" to 2666, "Equilibrium" to 7299)
            val picks = SimklPicks(s.http(), "client123", s.base).picks(ExternalIds(imdb = "tt0133093"), TitleType.FILM) { name, _, type ->
                catalogue[name]?.let { TitleSummary(WatchKey.tmdbFilm(it), type, name) }
            }
            assertEquals(listOf("Dark City", "Equilibrium"), picks.map { it.name })
            assertTrue(s.requests.all { it.getHeader("simkl-api-key") == "client123" })
            assertEquals("imdb=tt0133093&client_id=client123", s.requests.first().requestUrl!!.query)
        }
    }
}
