package com.cosmotune.watch

import com.cosmotune.watch.meta.TmdbMetadata
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.plugins.servers.JellyfinPlugin
import com.cosmotune.watch.plugins.stremio.StremioClient
import com.cosmotune.watch.plugins.stremio.StremioManifest
import com.cosmotune.watch.plugins.stremio.StremioPlugin
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How Watch search's filters reach each kind of source: Stremio manifests' extras, TMDB discover, Jellyfin, and the repository. */
class WatchSourceQueryTest {
    /** Cinemeta's shape: "top" takes genre (with options), search and skip; "year" needs a genre whose options are years. */
    private val cinemeta = """{"id":"com.linvo.cinemeta","name":"Cinemeta","resources":["catalog","meta"],"types":["movie","series"],"catalogs":[
        {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action","Comedy","Sci-Fi"]},{"name":"search"},{"name":"skip"}]},
        {"type":"movie","id":"year","name":"New","extra":[{"name":"genre","options":["2026","2025","1999","1995","1990"],"isRequired":true},{"name":"skip"}]},
        {"type":"series","id":"top","name":"Popular","extra":[{"name":"genre","options":["Action","Comedy"]},{"name":"search"},{"name":"skip"}]}]}"""
    private val searchOnly = """{"id":"x.search","name":"Search only","resources":["catalog"],"types":["movie"],"catalogs":[
        {"type":"movie","id":"s","name":"Search","extra":[{"name":"search","isRequired":true}]}]}"""
    private val anime = """{"id":"x.anime","name":"Anime","resources":["catalog"],"types":["anime"],"catalogs":[
        {"type":"anime","id":"trending","name":"Trending","extra":[{"name":"search"},{"name":"genre"},{"name":"skip"}]}]}"""

    private fun manifest(json: String) = StremioManifest.parse(Json.parseToJsonElement(json))

    @Test fun aManifestsExtrasSayWhichFiltersItCanApply() {
        val m = manifest(cinemeta)
        assertEquals(listOf("Action", "Comedy", "Sci-Fi"), m.catalogs.first().options["genre"], "the genre options are read")
        val sciFi = StremioPlugin.queryPlan(m, WatchQuery("", genre = WatchGenre.SCI_FI), 2026)
        assertEquals(FilterSupport.SENT, sciFi.ability.genre)
        assertEquals(listOf("movie/top" to mapOf("genre" to "Sci-Fi")), sciFi.asks.map { "${it.catalog.type}/${it.catalog.id}" to it.extra },
            "browsing a genre asks the list that offers it, in the add-on's spelling; the series list lacks Sci-Fi")
        val text = StremioPlugin.queryPlan(m, WatchQuery("batman", genre = WatchGenre.ACTION), 2026)
        assertEquals(setOf(mapOf("genre" to "Action", "search" to "batman")), text.asks.map { it.extra }.toSet(), "search and genre together")
        assertEquals(2, text.asks.size, "films and series")
        val western = StremioPlugin.queryPlan(m, WatchQuery("", genre = WatchGenre.WESTERN), 2026)
        assertEquals(FilterSupport.NONE, western.ability.genre, "a genre it does not offer leaves it out")
        assertTrue(western.ability.answers)

        val plain = StremioPlugin.queryPlan(manifest(searchOnly), WatchQuery("batman", genre = WatchGenre.ACTION), 2026)
        assertEquals(FilterSupport.NONE, plain.ability.genre, "no genre extra: cannot filter by genre")
        assertEquals(QueryAbility.SILENT, StremioPlugin.queryPlan(manifest(searchOnly), WatchQuery("", genre = WatchGenre.ACTION), 2026).ability,
            "nothing to browse without text: not asked, not counted")
        assertEquals(QueryAbility.SILENT, StremioPlugin.queryPlan(manifest(searchOnly), WatchQuery("x", kind = WatchKind.SERIES), 2026).ability)
    }

    @Test fun animeComesFromAnimeListsAndYearsFromYearLists() {
        val a = StremioPlugin.queryPlan(manifest(anime), WatchQuery("naruto", kind = WatchKind.ANIME), 2026)
        assertEquals(FilterSupport.SENT, a.ability.anime)
        assertEquals(listOf("anime"), a.asks.map { it.catalog.type })
        assertEquals(FilterSupport.NONE, StremioPlugin.queryPlan(manifest(cinemeta), WatchQuery("naruto", kind = WatchKind.ANIME), 2026).ability.anime)

        val m = manifest(cinemeta)
        assertTrue(StremioPlugin.isYearCatalog(m.catalogs[1]) && !StremioPlugin.isYearCatalog(m.catalogs[0]))
        val nineties = StremioPlugin.queryPlan(m, WatchQuery("", years = WatchYears.NINETIES), 2026)
        assertEquals(FilterSupport.SENT, nineties.ability.year)
        assertEquals(listOf("1999", "1995", "1990"), nineties.asks.map { it.extra.getValue("genre") }, "each year of the decade the list has")
        val withGenre = StremioPlugin.queryPlan(m, WatchQuery("", genre = WatchGenre.ACTION, years = WatchYears.NINETIES), 2026)
        assertEquals(FilterSupport.CHECKED, withGenre.ability.year, "with a genre too, years are checked on the genre's list")
    }

    @Test fun anAddOnIsAskedWithTheExtrasInItsPath() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/manifest.json", cinemeta)
            s.json("/catalog/movie/top/genre=Sci-Fi", """{"metas":[{"id":"tt1","type":"movie","name":"Quiet Orbit","releaseInfo":"2020","genres":["Sci-Fi"]}]}""")
            val plugin = StremioPlugin("cinemeta", "Cinemeta", "IMDB", StremioClient(s.http(), s.url("/manifest.json")), setOf(Capability.CATALOG, Capability.SEARCH))
            val found = plugin.find(WatchQuery("", genre = WatchGenre.SCI_FI), 2026)
            assertEquals(listOf("Quiet Orbit"), found.map { it.name })
            assertTrue(s.paths().any { it.startsWith("/catalog/movie/top/genre=Sci-Fi.json") }, s.paths().toString())
        }
    }

    @Test fun tmdbBrowsesWithDiscover() {
        val p = TmdbMetadata.discoverParams(WatchQuery("", genre = WatchGenre.SCI_FI, years = WatchYears.TEENS, language = WatchLanguage.KOREAN, sort = WatchSort.RATED),
            TitleType.FILM, 2026)!!
        assertEquals("878", p["with_genres"])
        assertEquals("2010-01-01", p["primary_release_date.gte"])
        assertEquals("2019-12-31", p["primary_release_date.lte"])
        assertEquals("ko", p["with_original_language"])
        assertEquals("vote_average.desc", p["sort_by"])
        assertEquals("200", p["vote_count.gte"], "highest rated needs enough votes")
        val series = TmdbMetadata.discoverParams(WatchQuery("", genre = WatchGenre.SCI_FI, years = WatchYears.THIS_YEAR), TitleType.SERIES, 2026)!!
        assertEquals("10765", series["with_genres"])
        assertEquals("2026-01-01", series["first_air_date.gte"])
        assertEquals(null, TmdbMetadata.discoverParams(WatchQuery("", genre = WatchGenre.HORROR), TitleType.SERIES, 2026), "TMDB has no horror series")
        val animeP = TmdbMetadata.discoverParams(WatchQuery("", kind = WatchKind.ANIME), TitleType.SERIES, 2026)!!
        assertEquals("16", animeP["with_genres"])
        assertEquals("ja", animeP["with_original_language"])
        assertEquals("popularity.desc", TmdbMetadata.discoverParams(WatchQuery(""), TitleType.FILM, 2026)!!["sort_by"])
        assertEquals("name.asc", TmdbMetadata.discoverParams(WatchQuery("", sort = WatchSort.NAME), TitleType.SERIES, 2026)!!["sort_by"])
    }

    @Test fun jellyfinFiltersOnTheServer() {
        val q = JellyfinPlugin.itemsQuery(WatchQuery("dark", kind = WatchKind.FILMS, genre = WatchGenre.SCI_FI, years = WatchYears.NOUGHTIES, sort = WatchSort.NEWEST), 2026)
        assertTrue("SearchTerm=dark" in q && "IncludeItemTypes=Movie" in q, q)
        assertTrue("Genres=Sci-fi%7CScience%20Fiction" in q || "Genres=Sci-fi%7CScience+Fiction" in q, q)
        assertTrue("MinPremiereDate=2000-01-01T00:00:00Z" in q && "MaxPremiereDate=2009-12-31T23:59:59Z" in q, q)
        assertTrue("SortBy=PremiereDate" in q, q)
        val browse = JellyfinPlugin.itemsQuery(WatchQuery("", genre = WatchGenre.DRAMA), 2026)
        assertTrue("SearchTerm" !in browse && "SortBy=DateCreated" in browse, browse)
    }

    private class Catalogue(override val id: String, private val titles: List<TitleSummary>) : WatchPlugin {
        override val kind = PluginKind.INTERNET_ARCHIVE
        override val displayName = id
        override val badge = id
        override val capabilities = setOf(Capability.CATALOG, Capability.SEARCH)
        override suspend fun search(query: String, type: TitleType?) = titles.filter { it.name.contains(query, true) }
    }

    @Test fun theRepositoryAsksWhoCanFilterCountsTheRestAndBrowsesWithAnEmptyBox() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/manifest.json", cinemeta)
            s.json("/catalog/movie/top/genre=Action&search=batman", """{"metas":[{"id":"tt10","type":"movie","name":"The Batman","releaseInfo":"2022","imdbRating":"7.8","genres":["Action"]},
                {"id":"tt11","type":"movie","name":"Batman Begins","releaseInfo":"2005","imdbRating":"8.2","genres":["Action","Drama"]}]}""")
            s.json("/catalog/series/top/genre=Action&search=batman", """{"metas":[]}""")
            s.json("/catalog/movie/top/genre=Action", """{"metas":[{"id":"tt20","type":"movie","name":"Mad Max","releaseInfo":"2015","genres":["Action"]}]}""")
            s.json("/catalog/series/top/genre=Action", """{"metas":[]}""")
            val archive = Catalogue("arc", listOf(TitleSummary(WatchKey.film("arc", "1"), TitleType.FILM, "Batman serial", 1943)))
            val dir = Files.createTempDirectory("watch-q").toFile().also { it.deleteOnExit() }
            val repo = DefaultWatchRepository(
                WatchConfig(OkHttpClient(), dir, null, tmdbKey = null, timeoutMs = 3_000, endpoints = Endpoints(cinemeta = s.url("/manifest.json"))),
                WatchStore(File(dir, "watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> listOf(archive) })

            val plain = repo.find(WatchQuery("batman"), 2026)
            assertTrue(plain.titles.any { it.name == "Batman serial" }, "no filter: every source answers")
            val action = repo.find(WatchQuery("batman", genre = WatchGenre.ACTION, sort = WatchSort.RATED), 2026)
            assertEquals(listOf("Batman Begins", "The Batman"), action.titles.map { it.name }, "the archive cannot filter by genre; highest rated first")
            assertEquals("1 source can't filter by genre", action.leftOutNote)
            val browse = repo.find(WatchQuery("", genre = WatchGenre.ACTION), 2026)
            assertEquals(listOf("Mad Max"), browse.titles.map { it.name }, "an empty box with a genre browses it")
            assertEquals(null, browse.leftOutNote, "the archive has nothing to browse, so it is not counted")
            assertEquals(listOf("meta", "arc"), repo.searchSources().map { it.id })
            assertEquals("Cinemeta", repo.searchSources().first().name)
            val onlyArchive = repo.find(WatchQuery("batman", sources = setOf("arc")), 2026)
            assertEquals(listOf("Batman serial"), onlyArchive.titles.map { it.name })

            repo.setRecentSearches(WatchSearchRules.remember(repo.recentSearches.value, "batman"))
            repo.setRecentSearches(WatchSearchRules.remember(repo.recentSearches.value, "Ghibli"))
            repo.setRecentSearches(WatchSearchRules.remember(repo.recentSearches.value, "Batman"))
            assertEquals(listOf("Batman", "Ghibli"), repo.recentSearches.value)
            repo.setPreferences(repo.preferences.value.copy(searchSort = WatchSort.NEWEST))
            assertEquals(WatchSort.NEWEST, repo.preferences.value.searchSort)
            val other = repo.addProfile("Kids")!!
            repo.selectProfile(other)
            assertEquals(emptyList(), repo.recentSearches.value, "each viewer has their own recent searches")
            assertEquals(WatchSort.BEST, repo.preferences.value.searchSort, "and their own sort")
        }
    }

    @Test fun channelsStayOnlyWithoutGenreOrYearAndInTheLanguage() {
        val tamil = Channel(WatchKey.channel("iptv", "sun"), "iptv", "Sun", languages = listOf("tam"))
        val english = Channel(WatchKey.channel("pluto", "news"), "pluto", "News", languages = listOf("eng"))
        assertEquals(listOf(tamil, english), WatchSearchRules.channelsFor(WatchQuery("s"), listOf(tamil, english)))
        assertEquals(listOf(tamil), WatchSearchRules.channelsFor(WatchQuery("s", language = WatchLanguage.TAMIL), listOf(tamil, english)))
        assertEquals(listOf(english), WatchSearchRules.channelsFor(WatchQuery("s", sources = setOf("pluto")), listOf(tamil, english)))
        assertTrue(WatchSearchRules.channelsFor(WatchQuery("s", genre = WatchGenre.DRAMA), listOf(tamil)).isEmpty())
        assertTrue(WatchSearchRules.channelsFor(WatchQuery("s", kind = WatchKind.FILMS), listOf(tamil)).isEmpty())
    }

    @Test fun recentSearchesKeepTheNewestOnceEach() {
        assertEquals(listOf("dune", "batman"), WatchSearchRules.remember(listOf("batman"), " dune "))
        assertEquals(listOf("Batman"), WatchSearchRules.remember(listOf("batman"), "Batman"))
        assertEquals(listOf("batman"), WatchSearchRules.remember(listOf("batman"), "x"), "a letter is no search")
        assertEquals(8, WatchSearchRules.remember((1..8).map { "q$it" }, "new").size)
    }
}
