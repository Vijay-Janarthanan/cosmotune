package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.TelemetryProvenance
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.recommendations.*
import com.cosmotune.music.data.sources.*
import com.cosmotune.music.ui.player.*
import com.cosmotune.ui.home.*
import com.cosmotune.ui.nowplaying.AudioDetailsSheet
import com.cosmotune.ui.nowplaying.AudioFormatLine
import com.cosmotune.ui.theme.*
import dev.chrisbanes.haze.HazeState
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AudioDiscoveryInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(600f, FontFamily.Default, FontFamily.Default)
        MaterialTheme { CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
            Box(Modifier.size(600.dp, 760.dp).testTag("audio:test")) { content() }
        } }
    }

    @Test fun tappingMeasuredStatsShowsThisRecordingAndLiveProviderOffers() {
        val installed = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousHost = installed.get(null)
        val previousStats = NerdStats.current.value
        val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) { "getReduceDynamicBlur" -> MutableStateFlow(true); else -> error(method.name) }
        } as PlayerSettingsSource
        val host = java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            when (method.name) { "getSettings" -> settings; else -> error(method.name) }
        } as PlayerHost
        PlayerPlatform.install(host)
        val song = Song("fixture-song", "Fixture recording", "Singer", null, durationText = "3:00")
        val ticket = AudioSourceComparisons.start(TrackMatcher.targetOf(song), "flac", "Fixture lossless")
        AudioSourceComparisons.finish(ticket, AudioSourceComparisons.Status.AVAILABLE, listOf(StreamFormat("flac", 900, 96000, 24)))
        NerdStats.current.value = NerdStats.Snapshot("audio/flac", 900, 96000, 2, 24,
            sourceName = "Fixture lossless", sourceId = "flac", bitrateProvenance = TelemetryProvenance.MEASURED)
        var pipeline = false
        try {
            compose.setContent { Theme {
                var open by remember { mutableStateOf(false) }
                AudioFormatLine(Modifier.testTag("audio:stats"), onClick = { open = true })
                if (open) AudioDetailsSheet(song, remember { HazeState() }, { open = false }, { pipeline = true })
            } }
            compose.onNodeWithTag("audio:stats").assertHasClickAction().performClick()
            compose.onNodeWithText("Audio details").assertExists()
            compose.onNodeWithText("Source: Fixture lossless").assertExists()
            val later = AudioSourceComparisons.start(TrackMatcher.targetOf(song), "aac", "Fixture AAC")
            AudioSourceComparisons.finish(later, AudioSourceComparisons.Status.AVAILABLE, listOf(StreamFormat("aac", 320, 44100)))
            compose.waitUntil { compose.onAllNodesWithText("Fixture AAC").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Offered: AAC · 44.1 kHz · 320 kbps").assertExists()
            compose.onNodeWithText("Decoder, processing and output device").performScrollTo().performClick()
            assertTrue(pipeline)
            val output = File("../build/audio-discovery-review/audio-details.png").apply { parentFile.mkdirs() }
            ImageIO.write(compose.onNodeWithTag("audio:test").captureToImage().toAwtImage(), "png", output)
        } finally { NerdStats.current.value = previousStats; installed.set(null, previousHost) }
    }

    @Test fun globalCountryAndMusicLanguageCanBeSelectedIndependently() {
        DiscoveryPreferences.configure(temporary.newFile("preferences.json").apply { delete() })
        var refreshed = 0
        compose.setContent { MaterialTheme { HomeDiscoveryControls({ refreshed++ }) } }
        compose.onNodeWithTag("home:country").performClick()
        compose.onNodeWithTag("home:country:ZZ").performClick()
        compose.waitUntil { DiscoveryPreferences.state.value.country == "ZZ" && refreshed == 1 }
        compose.onNodeWithTag("home:language").performClick()
        compose.onNodeWithTag("home:language:ta").performScrollTo().performClick()
        compose.waitUntil { DiscoveryPreferences.state.value.musicLanguage == "ta" && refreshed == 2 }
        assertEquals("ZZ", DiscoveryPreferences.state.value.country)
    }

    @Test fun anOfflinePlaylistPreviewSavesRealSourceQualifiedTracks() {
        val seed = Song("src:sflx-fixture::one", "First song", "Singer", null)
        val other = Song("local:two", "Second song", "Other singer", null)
        var saved = emptyList<Song>()
        var closed = false
        compose.setContent { MaterialTheme { PersonalPlaylistDialog(listOf(seed, other, seed), seed, { closed = true }) { name, songs ->
            assertEquals("Made for me", name); saved = songs
        } } }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag("playlist:generate").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("2 songs ready. Small libraries may produce fewer songs than requested.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist:save").performClick()
        compose.waitUntil { closed }
        assertEquals(listOf(seed.videoId, other.videoId), saved.map { it.videoId })
    }
}
