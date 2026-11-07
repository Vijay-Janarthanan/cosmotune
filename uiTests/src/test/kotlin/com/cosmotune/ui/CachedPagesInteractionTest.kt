package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.HomeFilter
import com.cosmotune.watch.WatchHome
import com.cosmotune.watch.WatchRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaSourceConfig
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.reading.data.ReadingRepository
import com.cosmotune.reading.ui.ReadApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import com.cosmotune.listening.ListeningStore
import com.cosmotune.listening.ReleaseNotice
import com.cosmotune.ui.listening.CosmoReleasesPage
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

/**
 * Every mode's main pages keep what they loaded: leaving a mode (its page leaves the composition, as a mode switch does) and
 * coming back draws the content on the first frame, without the skeleton and without asking the network again inside the
 * freshness window.
 */
class CachedPagesInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val evening = 1_762_461_600_000L

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null); val previousUi = uiField.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi); WatchNav.go(WatchTab.HOME) }
    }

    /** Leaves the page (another mode on screen) and comes back, as the mode switcher does. */
    private fun leaveAndReturn(shown: androidx.compose.runtime.MutableState<Boolean>) {
        shown.value = false
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Another mode").fetchSemanticsNodes().isNotEmpty())
        shown.value = true
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = try {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        compose.onRoot(useUnmergedTree = true).printToLog("CachedPages")
        println(compose.onRoot(useUnmergedTree = true).printToString())
        throw e
    }

    private fun assertShows(text: String) =
        assertTrue(compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(), "\"$text\" is on the page")

    private fun assertNoTag(tag: String) =
        assertTrue(compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no $tag on the page")

    /** The fake's home, counting its calls. */
    private class CountingWatch(private val fake: WatchRepository) : WatchRepository by fake {
        val homeCalls = AtomicInteger()
        override suspend fun home(filter: HomeFilter): WatchHome = fake.home(filter).also { homeCalls.incrementAndGet() }
    }

    @Test fun watchHomeShowsItsRowsAtOnceOnReturn() = hosts {
        WatchNav.go(WatchTab.HOME)
        val repository = CountingWatch(FakeWatchRepository(now = { evening }))
        val shown = mutableStateOf(true)
        compose.setContent {
            PodioTestTheme {
                Box(Modifier.size(1004.dp, 2400.dp)) {
                    if (shown.value) ProvideWatch(repository = repository, clock = { evening }) { WatchApp(showNavigation = false) }
                    else Text("Another mode")
                }
            }
        }
        waitForText("The Long Night Shift")
        assertEquals(1, repository.homeCalls.get())

        leaveAndReturn(shown)
        assertShows("The Long Night Shift")
        assertNoTag("watch:home:loading")
        assertEquals(1, repository.homeCalls.get(), "no second fetch inside the freshness window")
    }

    @Test fun podioRadioShowsItsStationsAtOnceOnReturn() {
        val stationCalls = AtomicInteger()
        val radio = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/json/stations") -> {
                        stationCalls.incrementAndGet()
                        MockResponse().setBody("""[{"stationuuid":"station-one","name":"Cached Radio","country":"India","language":"Tamil","url_resolved":"https://audio.example/live"}]""")
                    }
                    request.path.orEmpty().startsWith("/0.2") -> MockResponse().setBody("""{"data":[]}""")
                    request.path.orEmpty().startsWith("/json") -> MockResponse().setBody("[]")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        radio.use {
            val base = radio.url("/").toString().trimEnd('/')
            val catalogue = PodioCatalogue(radioMirrors = listOf("$base/"), appleBase = base, fyydBase = base)
            val file = temporary.newFile("podio.json")
            val shown = mutableStateOf(true)
            compose.setContent { PodioTestTheme { Box(Modifier.size(1200.dp, 820.dp)) {
                if (shown.value) PodioApp(file, PodioPlayback(), { _, _ -> }, {}, {}, {}, {}, directory = catalogue) else Text("Another mode")
            } } }
            waitForText("Cached Radio")
            val calls = stationCalls.get()

            leaveAndReturn(shown)
            assertShows("Cached Radio")
            assertNoTag("podio:loading")
            assertEquals(calls, stationCalls.get(), "no second fetch inside the freshness window")
        }
    }

    @Test fun readLibraryShowsTodaysTrendingAtOnceOnReturn() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val json = """{"numFound":1,"docs":[{"key":"/works/OL200W","title":"A cached atlas","author_name":["Mira Sen"],"ebook_access":"no_ebook"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = ReadingRepository(temporary.newFolder(), client = client)
        val shown = mutableStateOf(true)
        compose.setContent { PodioTestTheme { Box(Modifier.size(900.dp, 800.dp)) {
            if (shown.value) ReadApp(repository = repository) else Text("Another mode")
        } } }
        waitForText("A cached atlas")
        val asked = calls.get()

        leaveAndReturn(shown)
        assertShows("A cached atlas")
        assertEquals(asked, calls.get(), "no second fetch inside the freshness window")
    }

    @Test fun mangaBrowseShowsAPopularShelfAtOnceOnReturn() {
        val seriesCalls = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/api/v1/series?") || request.path.orEmpty() == "/api/v1/series" -> {
                        seriesCalls.incrementAndGet()
                        MockResponse().setBody("""{"content":[{"id":"s1","name":"Series","metadata":{"title":"A cached series","language":"en"}}],"last":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val store = MangaStore(temporary.newFolder())
            kotlinx.coroutines.runBlocking {
                store.update { it.copy(sources = it.sources + MangaSourceConfig("komga:cached", "komga", "Cached shelf", baseUrl = server.url("/").toString(), settings = mapOf("apiKey" to "key"))) }
            }
            val repository = MangaRepository(store)
            val shown = mutableStateOf(true)
            compose.setContent { PodioTestTheme { Box(Modifier.size(900.dp, 800.dp)) {
                if (shown.value) MangaApp(repository = repository) else Text("Another mode")
            } } }
            compose.onNode(hasText("Browse") and isSelectable()).performClick()
            waitForText("A cached series")
            val asked = seriesCalls.get()

            leaveAndReturn(shown)
            compose.onNode(hasText("Browse") and isSelectable()).performClick()
            compose.waitForIdle()
            assertShows("A cached series")
            assertEquals(asked, seriesCalls.get(), "no second fetch inside the freshness window")
        }
    }

    @Test fun musicReleasesShowTheInboxAtOnceOnReturn() {
        val file = File(temporary.newFolder(), "listening.json")
        ListeningStore(file).apply {
            watchArtist("UCfixture000000", "Fixture Artist")
            update { it.copy(inbox = listOf(ReleaseNotice("MPREcached", "UCfixture000000", "Fixture Artist", "A cached album", discoveredAt = evening, single = false))) }
        }
        val shown = mutableStateOf(true)
        compose.setContent { PodioTestTheme { Box(Modifier.size(400.dp, 800.dp)) {
            if (shown.value) CosmoReleasesPage(file = file, listState = rememberLazyListState(), contentPadding = PaddingValues(),
                onOpenRelease = { _, _ -> }, onManage = {}, lookup = { emptyList() }, now = { evening })
            else Text("Another mode")
        } } }
        waitForText("A cached album")

        // The file can no longer be read while the page is away: what is drawn on return can only be the inbox it showed.
        shown.value = false
        compose.waitForIdle()
        file.writeText("{ not json")
        shown.value = true
        compose.waitForIdle()
        assertShows("A cached album")
    }
}
