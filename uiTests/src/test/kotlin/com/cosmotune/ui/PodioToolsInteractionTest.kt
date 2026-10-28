package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.*
import com.cosmotune.podio.ui.*
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PodioToolsInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeFileCallbacksDownloadsAndOfflineEpisodesUseTheSameIdentity() {
        MockWebServer().use { server ->
            val xml = """<rss><channel><title>Stories</title><item><guid>one</guid><title>First story</title><duration>600</duration><enclosure url="https://publisher.example/one.mp3"/></item></channel></rss>"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path.orEmpty().startsWith("/feed")) xml else if (request.path.orEmpty().startsWith("/json/")) "[]" else "{}")
            }
            val catalogue = PodioCatalogue(radioMirrors = listOf(server.url("/").toString()),
                appleBase = server.url("/").toString().trimEnd('/'), fyydBase = server.url("/").toString().trimEnd('/'))
            val file = temporary.newFile("native-tools.json")
            val offline = mutableStateOf(emptyList<PodioItem>())
            val downloadCalls = CopyOnWriteArrayList<PodioItem>()
            val played = CopyOnWriteArrayList<PodioItem>()
            val exports = CopyOnWriteArrayList<String>()
            val shares = CopyOnWriteArrayList<String>()
            var imported = 0
            compose.setContent { MaterialTheme { Box(Modifier.size(1200.dp, 820.dp)) {
                PodioApp(file, PodioPlayback(), { item, _ -> played += item }, {}, {}, {}, directory = catalogue,
                    onDownloadEpisode = { downloadCalls += it }, downloadedEpisodes = offline.value,
                    onImportOpmlFile = { imported++; """<opml><body><outline text="Stories" xmlUrl="${server.url("/feed")}"/></body></opml>""" },
                    onExportOpmlFile = { exports += it; true }, onShareOpmlFile = { shares += it })
            } } }
            compose.onNodeWithText("Podcasts").performClick()
            compose.onNodeWithText("Import OPML").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Stories").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, imported)
            compose.onNodeWithText("Export OPML").performClick()
            compose.waitUntil(5_000) { exports.isNotEmpty() }
            assertEquals(server.url("/feed").toString(), PodioOpml.decode(exports.single()).single().feed)
            compose.onNodeWithText("Share OPML").performClick()
            compose.waitUntil(5_000) { shares.isNotEmpty() }
            compose.onNodeWithText("Stories").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First story").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Download episode").performClick()
            compose.runOnIdle { assertEquals(1, downloadCalls.size) }
            val episode = downloadCalls.single()
            val local = temporary.newFile("episode.mp3")
            compose.runOnIdle { offline.value = listOf(episode.copy(downloadedUri = local.toURI().toString(), downloadedPath = local.absolutePath)) }
            compose.onNodeWithContentDescription("Episode downloaded").assertIsNotEnabled()
            compose.onNodeWithText("Downloaded", substring = false).performClick()
            compose.onNodeWithText("Downloaded episodes").assertIsDisplayed()
            compose.onNodeWithText("First story").performClick()
            compose.runOnIdle {
                assertEquals(episode.id, played.single().song().videoId)
                assertEquals(local.toURI().toString(), played.single().song().localUri)
            }
        }
    }

    @Test fun subscriptionsOlderEpisodesResumeAndTheBackgroundTimerAreReachable() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(when {
                    request.path.orEmpty().startsWith("/json/") -> "[]"
                    request.path.orEmpty().startsWith("/feed") -> """<rss xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>Stories</title><atom:link rel="next" href="/older"/>
                        <item><guid>one</guid><title>First story</title><duration>600</duration><enclosure url="https://publisher.example/one.mp3" type="audio/mpeg"/></item></channel></rss>"""
                    request.path.orEmpty().startsWith("/older") -> """<rss><channel><title>Stories</title><item><guid>two</guid><title>Older story</title><enclosure url="https://publisher.example/two.mp3" type="audio/mpeg"/></item></channel></rss>"""
                    else -> "{}"
                })
            }
            val catalogue = PodioCatalogue(radioMirrors = listOf(server.url("/").toString()),
                appleBase = server.url("/").toString().trimEnd('/'), fyydBase = server.url("/").toString().trimEnd('/'))
            val file = temporary.newFile("tools.json")
            val library = PodioLibrary(file)
            val first = PodioCatalogue.parseFeed("""<rss><channel><title>Stories</title><item><guid>one</guid><title>First story</title><enclosure url="https://publisher.example/one.mp3"/></item></channel></rss>""", server.url("/feed").toString()).episodes.single()
            library.recordProgress(first.item().id, 85_000)
            val playback = mutableStateOf(PodioPlayback("podio:episode:playing", "A podcast", "Host", playing = true))
            val resumed = CopyOnWriteArrayList<Long>()
            val timers = CopyOnWriteArrayList<Int?>()
            compose.setContent { MaterialTheme { Box(Modifier.size(1200.dp, 820.dp)) {
                PodioApp(file, playback.value, { _, position -> resumed += position }, {}, {}, {}, directory = catalogue,
                    onSleepTimer = { timers += it }, onSleepAfterEpisode = {})
            } } }
            compose.onNodeWithText("Podcasts").performClick()
            compose.onNodeWithText("Import OPML").performClick()
            compose.onNodeWithText("OPML document").performTextInput("""<opml><body><outline text="Stories" xmlUrl="${server.url("/feed")}"/></body></opml>""")
            compose.onNodeWithText("Import subscriptions").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Stories").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Stories").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First story").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Resume at 1:25", substring = true).assertExists()
            compose.onNodeWithText("First story").performClick()
            compose.runOnIdle { assertEquals(listOf(85_000L), resumed.toList()) }
            compose.onNodeWithTag("podio:older-episodes").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Older story").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Older story").assertExists()
            compose.onNodeWithText("Sleep timer").performClick()
            compose.onNodeWithText("30 minutes").performClick()
            compose.runOnIdle { assertEquals(listOf(30), timers.toList()) }
            compose.onNodeWithText("Export OPML").assertIsEnabled()
            assertEquals("Stories", library.state.value.shows.single().title)
        }
    }
}
