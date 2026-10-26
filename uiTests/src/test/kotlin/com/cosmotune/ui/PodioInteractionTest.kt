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

class PodioInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path.orEmpty().startsWith("/json/stations") -> """[{"stationuuid":"station-one","name":"Test Radio","country":"India","language":"Tamil","url_resolved":"https://audio.example/live","geo_lat":13.08,"geo_long":80.27}]"""
                    request.path.orEmpty().startsWith("/search") -> """{"results":[{"collectionName":"Test podcast","artistName":"A host","feedUrl":"${url("/feed")}"}]}"""
                    request.path.orEmpty().startsWith("/0.2") -> """{"data":[]}"""
                    request.path.orEmpty().startsWith("/feed") -> """<rss><channel><title>Test podcast</title><item><guid>episode-one</guid><title>A real episode</title><enclosure url="https://audio.example/episode.mp3" type="audio/mpeg"/></item></channel></rss>"""
                    else -> "{}"
                }
                return MockResponse().setBody(body)
            }
        }
    }
    @Test fun radioFavouritesPodcastBrowsingAndEpisodePlayUseTheRealPage() {
        server().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val file = temporary.newFile("library.json")
            val playing = mutableStateOf(PodioPlayback())
            val played = mutableListOf<PodioItem>()
            val directory = PodioCatalogue(radioMirrors = listOf(base), appleBase = base, fyydBase = base)
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(1200.dp, 820.dp)) {
                PodioApp(file, playing.value, onPlay = { item, _ -> played += item; playing.value = PodioPlayback(item.id, item.title, item.author, playing = true) },
                    onToggle = {}, onSeek = {}, onSpeed = {}, onMusic = {},
                    directory = directory)
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test Radio").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Save favourite").performClick()
            compose.waitUntil(5_000) { runCatching { PodioLibrary(file).state.value.stations.size == 1 }.getOrDefault(false) }
            snapshot("podio-desktop.png")
            compose.onNodeWithText("Test Radio").performClick()
            compose.runOnIdle { assertTrue(played.single().live) }
            compose.onNodeWithText("Podcasts").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test podcast").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Test podcast").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("A real episode").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("A real episode").performClick()
            compose.runOnIdle { assertEquals("https://audio.example/episode.mp3", played.last().url); assertFalse(played.last().live) }
            snapshot("podio-before-follow.png")
            compose.onNodeWithText("Follow").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Follow").fetchSemanticsNodes().isEmpty() }
            assertEquals("Test podcast", PodioLibrary(file).state.value.shows.single().title)
            snapshot("podio-podcast.png")
        }
    }
    @Test fun thePhoneLayoutKeepsBothSectionsAndTheFeedEntryUsable() {
        server().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val file = temporary.newFile("phone.json")
            val directory = PodioCatalogue(radioMirrors = listOf(base), appleBase = base, fyydBase = base)
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Box(Modifier.size(412.dp, 892.dp)) {
                PodioApp(file, PodioPlayback(), { _, _ -> }, {}, {}, {}, {}, directory = directory)
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Test Radio").fetchSemanticsNodes().isNotEmpty() }
            snapshot("podio-phone.png")
            compose.onNodeWithText("Podcasts").performClick()
            compose.onNodeWithText("Add link").performClick()
            compose.onNodeWithText("RSS feed or Spotify podcast link").assertExists()
            compose.onNodeWithText("Cancel").performClick()
        }
    }
    private fun snapshot(name: String) {
        val path = File("build/podio-previews/$name").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("podio:root").captureToImage().toAwtImage(), "png", path)
    }
}
