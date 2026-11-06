package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.lyrics.LyricsSource
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.LyricsProviderState
import com.cosmotune.music.ui.player.AudioOutputDevice
import com.cosmotune.music.ui.player.AudioOutputForm
import com.cosmotune.music.ui.player.AudioOutputKind
import com.cosmotune.music.ui.player.AudioOutputSheet
import com.cosmotune.music.ui.player.CastReceiverList
import com.cosmotune.music.ui.player.CastReceiverUi
import com.cosmotune.music.ui.player.CastUi
import com.cosmotune.music.ui.player.ListenTogetherMembersSheet
import com.cosmotune.music.ui.player.LyricsOffsetSheet
import com.cosmotune.music.ui.player.LyricsProviderSheet
import com.cosmotune.music.ui.player.LyricsShareAction
import com.cosmotune.music.ui.player.LyricsShareDrawer
import com.cosmotune.music.ui.player.OutputFormatUi
import com.cosmotune.music.ui.player.PartyUi
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerSettingsSource
import com.cosmotune.music.ui.player.SystemVolume
import com.cosmotune.music.ui.player.WatchOutputUi
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.AudioDetailsSheet
import com.cosmotune.ui.nowplaying.PlayerVisualsContent
import com.cosmotune.ui.orbit.OrbitPopupCard
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the Now Playing sheets and popups in Orbit (Play on, Audio details, the lyrics source, the lyrics
 * offset, the cast picker, Share lyrics, Listen Together and Visuals), drawn offscreen at a phone's size in the dark and the light
 * palettes and written under build/old-screens/npsheets for a person to look at. Each is checked only for having been drawn.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NowPlayingSheetsVisualReviewTest {
    private val out = File("../build/old-screens/npsheets").apply { mkdirs() }

    private val palettes = listOf(true, false)

    private class Volume : SystemVolume {
        override val level: StateFlow<Float> = MutableStateFlow(0.62f)
        override fun set(level: Float) {}
        override fun refresh() {}
    }

    private fun hosts(block: () -> Unit) {
        val field = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = field.get(null)
        val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getLyricsOffsetMs" -> MutableStateFlow(300)
                "getLyricsSourceOrder" -> MutableStateFlow(emptyList<LyricsSource>())
                else -> MutableStateFlow(false)
            }
        } as PlayerSettingsSource
        val outputs = listOf(
            AudioOutputDevice(1, "", AudioOutputKind.PHONE),
            AudioOutputDevice(2, "Pixel Buds Pro", AudioOutputKind.BLUETOOTH, isActive = true, form = AudioOutputForm.BUDS),
            AudioOutputDevice(3, "", AudioOutputKind.WIRED),
        )
        val party = PartyUi(
            inParty = true,
            members = listOf(
                PartyMember("m1", displayName = "Vijay", isHost = true, connected = true),
                PartyMember("m2", displayName = "Arun", connected = true),
                PartyMember("m3", displayName = "Meera", connected = false),
            ),
            you = PartyMember("m1", displayName = "Vijay", isHost = true, connected = true),
            code = "K7Q2PX",
        )
        val volume = Volume()
        val host = java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            when (method.name) {
                "getSettings" -> settings
                "rememberAudioOutputs" -> outputs
                "getCastState" -> MutableStateFlow(CastUi(supported = true))
                "getWatchOutput" -> MutableStateFlow(WatchOutputUi(available = true, name = "Galaxy Watch"))
                "getVolume" -> volume
                "getOutputFormat" -> MutableStateFlow(OutputFormatUi("24-bit PCM · 48 kHz"))
                "getParty" -> MutableStateFlow(party)
                "refreshWatchOutput", "ListeningDeviceEntry", "selectAudioOutput", "selectWatchOutput", "showMessage" -> null
                "rememberRemoteArtworkUrl" -> null
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost
        PlayerPlatform.install(host)
        try { block() } finally { field.set(null, previous) }
    }

    /**
     * A lifecycle that has already ended, so `collectAsStateWithLifecycle` keeps each flow's current value and starts no collector:
     * the collector would move to the Main dispatcher, which this test classpath does not have, and a still frame needs none.
     */
    private object Still : LifecycleOwner {
        override val lifecycle: Lifecycle = object : Lifecycle() {
            override val currentState: State get() = State.DESTROYED
            override fun addObserver(observer: LifecycleObserver) {}
            override fun removeObserver(observer: LifecycleObserver) {}
        }
    }

    /** Draws [content] over the player's ground at 390 by 844 dp, lets the drawers slide up, and writes the frame as [name]. */
    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        val colors = CosmoColors(palette)
        val scene = ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCosmoColors provides colors, LocalCosmoType provides rememberCosmoType(), LocalReducedMotion provides true, LocalLifecycleOwner provides Still,
                ) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        try {
            var nanos = 0L
            var image = scene.render(nanos)
            repeat(60) { nanos += 16_000_000L; image = scene.render(nanos) }
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("no PNG for $name")
            val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 0, "$name was drawn")
        } finally {
            scene.close()
        }
    }

    private val haze = HazeState()
    private val song = Song("v1", "Golden Hour", "Mira and the Coast", null, durationText = "4:05")

    @Test fun playOn() = hosts {
        for (dark in palettes) shoot("play-on", dark) { AudioOutputSheet(haze, "Vijay Janarthanan", {}, {}, {}) }
    }

    @Test fun audioDetails() = hosts {
        for (dark in palettes) shoot("audio-details", dark) { AudioDetailsSheet(song, haze, {}, {}) }
    }

    @Test fun lyricsSource() = hosts {
        val states = mapOf(
            LyricsSource.entries[0] to LyricsProviderState.FOUND,
            LyricsSource.entries[1] to LyricsProviderState.FETCHING,
            LyricsSource.entries[2] to LyricsProviderState.NOT_FOUND,
        )
        for (dark in palettes) shoot("lyrics-source", dark) { LyricsProviderSheet(haze, LyricsSource.entries[0], states, {}, {}) }
    }

    @Test fun lyricsOffset() = hosts {
        for (dark in palettes) shoot("lyrics-offset", dark) { LyricsOffsetSheet(haze, {}) }
    }

    @Test fun listenTogether() = hosts {
        for (dark in palettes) shoot("listen-together", dark) { ListenTogetherMembersSheet(haze, {}, {}) }
    }

    @Test fun cast() = hosts {
        val receivers = listOf(
            CastReceiverUi("a", "Living room TV", connecting = false, connected = true),
            CastReceiverUi("b", "Kitchen speaker", connecting = true, connected = false),
            CastReceiverUi("c", "Bedroom", connecting = false, connected = false),
        )
        for (dark in palettes) {
            for ((name, list) in listOf("cast" to receivers, "cast-searching" to emptyList())) {
                shoot(name, dark) {
                    OrbitPopupCard("Cast", "Play on a Chromecast or Google Cast speaker", "Done", {}) {
                        CastReceiverList(
                            receivers = list, casting = list.isNotEmpty(), searching = "Looking for devices…",
                            searchingHint = "Your phone and the receiver need to be on the same Wi-Fi network.",
                            connectingLabel = "Connecting…", connectedLabel = "Casting", stopLabel = "Stop casting",
                            onPick = {}, onStop = {},
                            spinner = { size -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(size), color = com.cosmotune.ui.orbit.orbitLook().ink, strokeWidth = 2.dp) },
                        )
                    }
                }
            }
        }
    }

    @Test fun shareLyrics() = hosts {
        for (dark in palettes) shoot("share-lyrics", dark) {
            LyricsShareDrawer(
                hazeState = haze,
                title = "Share lyrics",
                description = "The lines you picked, laid out on a card with the cover.",
                ratio = 1.3f,
                extras = listOf(
                    LyricsShareAction("Make it a story", CosmoIcons.Expand) {},
                    LyricsShareAction("Save as .lrc", CosmoIcons.Download, done = true, doneLabel = "Saved!") {},
                    LyricsShareAction("Save as video", CosmoIcons.Download) {},
                ),
                pair = listOf(
                    LyricsShareAction("Save", CosmoIcons.Download) {},
                    LyricsShareAction("Share", CosmoIcons.Share, primary = true) {},
                ),
                onDismiss = {},
            ) { frame ->
                // A stand-in for the rendered card: the picture the phone draws is a bitmap of the picked lines.
                Box(frame.background(Color(0xFF5E2A1C))) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (line in listOf("We were golden", "in the hour before dark", "and the coast kept time")) {
                            Text(line, color = Color(0xFFFFEDE2), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    @Test fun visuals() = hosts {
        for (dark in palettes) shoot("visuals", dark) { PlayerVisualsContent(song, {}) }
    }
}
