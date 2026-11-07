package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.*
import com.cosmotune.podio.ui.*
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class PodioInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun server(feedFailures: Int = 0, feedDelayMs: Long = 0,
        feedCalls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
        feedAddress: String? = null) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.requestUrl.toString()
                val feed = request.path.orEmpty().startsWith("/feed")
                if (feed && feedCalls.incrementAndGet() <= feedFailures) return MockResponse().setResponseCode(503)
                val body = when {
                    request.path.orEmpty().startsWith("/json/stations") -> """[{"stationuuid":"station-one","name":"Test Radio","country":"India","language":"Tamil","url_resolved":"https://audio.example/live","geo_lat":13.08,"geo_long":80.27}]"""
                    request.path.orEmpty().startsWith("/search") -> """{"results":[{"collectionName":"Test podcast","artistName":"A host","feedUrl":"${feedAddress ?: url("/feed")}"}]}"""
                    request.path.orEmpty().startsWith("/0.2") -> """{"data":[]}"""
                    request.path.orEmpty().startsWith("/feed") -> """<rss><channel><title>Test podcast</title><item><guid>episode-one</guid><title>A real episode</title><enclosure url="https://audio.example/episode.mp3" type="audio/mpeg"/></item></channel></rss>"""
                    else -> "{}"
                }
                return MockResponse().setBody(body).apply { if (feed) setBodyDelay(feedDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
        }
    }

    /** Real directories/publishers use different origins; a cancelled radio call must not share their test socket. */
    private inner class Fixture(feedFailures: Int = 0, feedDelayMs: Long = 0,
        feedCalls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()) : AutoCloseable {
        private val radio = server()
        private val publisher = server(feedFailures, feedDelayMs, feedCalls)
        private val apple = server(feedAddress = publisher.url("/feed").toString())
        private val fyyd = server()
        val catalogue = PodioCatalogue(radioMirrors = listOf(radio.url("/").toString()),
            appleBase = apple.url("/").toString().trimEnd('/'), fyydBase = fyyd.url("/").toString().trimEnd('/'))
        override fun close() { listOf(radio, publisher, apple, fyyd).forEach { it.close() } }
    }
    @Test fun radioFavouritesPodcastBrowsingAndEpisodePlayUseTheRealPage() {
        Fixture().use { fixture ->
            val file = temporary.newFile("library.json")
            val playing = mutableStateOf(PodioPlayback())
            val played = mutableListOf<PodioItem>()
            val directory = fixture.catalogue
            compose.setContent { PodioTestTheme { Box(Modifier.size(1200.dp, 820.dp)) {
                PodioApp(file, playing.value, onPlay = { item, _ -> played += item; playing.value = PodioPlayback(item.id, item.title, item.author, playing = true, rate = 1.5f) },
                    onToggle = {}, onSeek = {}, onSpeed = {}, onMusic = {},
                    directory = directory)
            } } }
            // Search is the heading's round button; its page lists every station of the directory's answer.
            compose.onNodeWithContentDescription("Search").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test Radio").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Save favourite").performClick()
            // The UI publishes the saved state only after the rename commits. Reopening the
            // destination repeatedly while it is being replaced can itself deny the Windows move.
            try {
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Remove favourite").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                println("Podio fixture requests: $requests")
                println(runCatching { compose.onNodeWithTag("podio:root").printToString() }.getOrNull())
                throw error
            }
            assertEquals(1, PodioLibrary(file).state.value.stations.size)
            snapshot("podio-desktop.png")
            compose.onNodeWithText("Test Radio").performClick()
            compose.runOnIdle { assertTrue(played.single().live) }
            podcastsTab().performSemanticsAction(SemanticsActions.OnClick) { it() }
            podcastsTab().assertIsSelected()
            waitForDirectory()
            compose.onNodeWithText("Test podcast").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("A real episode").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("A real episode").performClick()
            compose.runOnIdle { assertEquals("https://audio.example/episode.mp3", played.last().url); assertFalse(played.last().live) }
            compose.onNodeWithText("1.5×").assertIsDisplayed()
            snapshot("podio-before-follow.png")
            compose.onNodeWithText("Follow").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Follow").fetchSemanticsNodes().isEmpty() }
            assertEquals("Test podcast", PodioLibrary(file).state.value.shows.single().title)
            snapshot("podio-podcast.png")
        }
    }
    @Test fun thePhoneLayoutKeepsBothSectionsAndTheFeedEntryUsable() {
        Fixture().use { fixture ->
            val file = temporary.newFile("phone.json")
            val directory = fixture.catalogue
            compose.setContent { PodioTestTheme { Box(Modifier.size(412.dp, 892.dp)) {
                PodioApp(file, PodioPlayback(), { _, _ -> }, {}, {}, {}, {}, directory = directory)
            } } }
            // The phone's Radio page: the stations as discs, the Globe chip, the dock's four labelled tabs.
            compose.onNodeWithTag("podio:radio").assertExists()
            compose.onNodeWithText("Globe").assertExists()
            listOf("Radio", "Podcasts", "Following", "Downloads").forEach { compose.onNode(podioTab(it)).assertExists() }
            snapshot("podio-phone-radio.png")
            compose.onNodeWithContentDescription("Search").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test Radio").fetchSemanticsNodes().isNotEmpty() }
            snapshot("podio-phone.png")
            compose.onNodeWithContentDescription("Close search").performClick()
            podcastsTab().performClick()
            waitForDirectory()
            snapshot("podio-phone-podcasts.png")
            compose.onNodeWithContentDescription("Add by RSS address").performClick()
            compose.onNodeWithText("Podcast feed or show link").assertExists()
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    @Test fun episodesListTheFollowedShowsAndTheGlobeCanFillThePhone() {
        Fixture().use { fixture ->
            val file = temporary.newFile("episodes.json")
            val feed = runBlocking { fixture.catalogue.podcasts("Test") }.first { it.title == "Test podcast" }
            PodioLibrary(file).update { it.copy(shows = listOf(feed)) }
            val played = mutableListOf<PodioItem>()
            compose.setContent { PodioTestTheme { Box(Modifier.size(412.dp, 892.dp)) {
                PodioApp(file, PodioPlayback(), { item, _ -> played += item }, {}, {}, {}, {}, directory = fixture.catalogue)
            } } }
            // The Globe chip opens the globe on the whole phone: the dock steps aside, the sheet of stations stays over its foot.
            compose.onNodeWithText("Globe").performClick()
            compose.onNodeWithTag("podio:radio-full").assertExists()
            compose.onNodeWithTag("podio:sheet").assertExists()
            compose.onNode(podioTab("Following")).assertDoesNotExist()
            compose.onNodeWithContentDescription("Close full screen map").performClick()
            // Following: the newest episodes of the followed shows.
            compose.onNode(podioTab("Following")).performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("A real episode").fetchSemanticsNodes().isNotEmpty() }
            snapshot("podio-phone-episodes.png")
            compose.onNodeWithText("A real episode").performClick()
            compose.runOnIdle { assertEquals("https://audio.example/episode.mp3", played.single().url) }
            // Following keeps the followed show as a record (its title over its publisher) under the new episodes.
            compose.onNode(hasText("Test podcast") and hasText("A host")).assertExists()
            snapshot("podio-phone-library.png")
        }
    }

    @Test fun failedFeedsRetryTheSameAddressAndLeavingCancelsALateRefresh() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Fixture(feedFailures = 1, feedDelayMs = 400, feedCalls = calls).use { fixture ->
            val directory = fixture.catalogue
            val file = temporary.newFile("retry.json")
            compose.setContent { PodioTestTheme { Box(Modifier.size(1200.dp, 820.dp)) {
                PodioApp(file, PodioPlayback(), { _, _ -> }, {}, {}, {}, {}, directory = directory)
            } } }
            podcastsTab().performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitForDirectory()
            compose.onNodeWithText("Test podcast").performSemanticsAction(SemanticsActions.OnClick) { it() }
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn't load this podcast feed. Please retry.").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                println("Podio feed calls: ${calls.get()}, fixture requests: $requests")
                println(runCatching { compose.onNodeWithTag("podio:root").printToString() }.getOrNull())
                throw error
            }
            compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("A real episode").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, calls.get())
            compose.onNodeWithContentDescription("Refresh").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { calls.get() == 3 }
            compose.onNode(podioTab("Radio")).performSemanticsAction(SemanticsActions.OnClick) { it() }
            // Let the delayed response finish: it must not replace the newly selected Radio page.
            Thread.sleep(500)
            compose.waitForIdle()
            compose.onNodeWithTag("podio:show").assertDoesNotExist()
            compose.onNodeWithTag("podio:radio").assertIsDisplayed()
        }
    }
    /** The Podcasts tab of the dock or the rail (the page's own title says "Podcasts" too). */
    private fun podcastsTab() = compose.onNode(podioTab("Podcasts") and hasAnyAncestor(hasTestTag("podio:root")) and !hasAnyAncestor(hasTestTag("podio:search")))

    private fun snapshot(name: String) {
        val path = File("build/podio-previews/$name").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("podio:root").captureToImage().toAwtImage(), "png", path)
    }

    private fun waitForDirectory() {
        try {
            // The catalogue's bounded HTTP call is 15 seconds; allow it to settle before the UI deadline.
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Test podcast").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            println("Podio fixture requests: $requests")
            println(runCatching { compose.onNodeWithTag("podio:root").printToString() }.getOrNull())
            throw error
        }
    }
}
