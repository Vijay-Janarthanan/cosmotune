package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.audio.SongWaveforms
import com.cosmotune.settings.Extras
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerScrub
import com.cosmotune.music.ui.player.SystemVolume
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop player's wide song card with the waveform seek line (taller than the plain line, with its marks under it): the line
 * and its times sit under the artists, never over them, and nothing runs past the card's foot. Drawn for real, read from the
 * card's own layout.
 */
@OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class NowPlayingWideCardRoomTest {
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private class FakeVolume : SystemVolume {
        override val level: StateFlow<Float> = MutableStateFlow(1f)
        override fun set(level: Float) = Unit
        override fun refresh() = Unit
    }

    private val shell = CosmoShellState(CosmoLookSettings(MapStore()), dark = true)

    // Not an 11-letter video id, so the line never asks the network for timed comments.
    private val id = "wide-card-wave"

    private fun transport() = NowPlayingTransport(
        song = Song(videoId = id, title = "Levitating", artist = "Dua Lipa", thumbnailUrl = null, fromAutoplay = false), coverUrl = null,
        isPlaying = false, position = PlaybackPosition().apply { report(60_000L); advancing = false }, durationMs = 200_000L,
        scrub = PlayerScrub(), liked = false, shuffle = false, repeatMode = 0, hasPrevious = true, hasNext = true, onPlayPause = {},
        onPrevious = {}, onNext = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {}, onSeekFraction = {}, onOpenArtist = {},
    )

    private val buttons = listOf(
        CardButton(CosmoIcons.Shuffle, "Shuffle", {}, movable = true),
        CardButton(CosmoIcons.Repeat, "Repeat", {}, movable = true),
        CardButton(CosmoIcons.Previous, "Previous", {}),
        CardButton(CosmoIcons.Next, "Next", {}),
        CardButton(CosmoIcons.Heart, "Like", {}),
    )

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }


    @Test
    fun theWaveformLineSitsUnderTheArtistsAndInsideTheCard() = check(withLyricLine = false)

    @Test
    fun withTheLiveLyricLineToo() = check(withLyricLine = true)

    private fun check(withLyricLine: Boolean) {
        var fraction = 0f
        while (fraction <= 1f) { SongWaveforms.record(id, fraction, 0.3f + 0.5f * (fraction * 7 % 1f)); fraction += 0.002f }
        assertNotNull(SongWaveforms.bars(id), "the song has a waveform")
        // The waveform line is off by default; this card is checked with it turned on.
        Extras.settings.setWaveformSeek(true)
        val scene = ImageComposeScene(width = 960, height = 400, density = Density(1f)) {
            CompositionLocalProvider(LocalCosmoColors provides shell.colors) {
                Box(Modifier.fillMaxSize().background(Color(0xFF14110F))) {
                    NowPlayingSongCard(
                        transport(), orbitFonts(), orbitLook(), buttons, wideCardSize(Modifier.width(887.dp)).testTag("test:card"),
                        wide = true, volume = true, volumeSource = FakeVolume(),
                        // The measured quality is a pill at least 32 dp tall (RecordAudioDetails), as in the player.
                        quality = { ink -> Box(Modifier.heightIn(min = 32.dp)) { androidx.compose.material3.Text("Opus 141", color = ink) } },
                        lyricLine = if (withLyricLine) { ink -> androidx.compose.material3.Text("A line being sung", color = ink) } else null,
                    )
                }
            }
        }
        try {
            scene.render()
            scene.render(100_000_000L)
            val nodes = scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.all() }
            fun bounds(test: (SemanticsNode) -> Boolean): Rect = nodes.first(test).boundsInRoot
            fun text(part: String): Rect = bounds { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { part in it.text } }
            val above = if (withLyricLine) text("A line being sung") else text("Dua Lipa")
            val seek = bounds { it.config.getOrNull(SemanticsProperties.TestTag) == "nowplaying:card:seek" }
            val wave = bounds { it.config.getOrNull(SemanticsProperties.TestTag) == "nowplaying:card:waveform" }
            val card = bounds { it.config.getOrNull(SemanticsProperties.TestTag) == "test:card" }
            assertTrue(wave.height > 20f, "the waveform line is drawn (${wave.height} px)")
            assertTrue(seek.top >= above.bottom, "the seek line (top ${seek.top}) is under the words above it (bottom ${above.bottom})")
            assertTrue(seek.bottom <= card.bottom, "the times (bottom ${seek.bottom}) stay inside the card (bottom ${card.bottom})")
        } finally {
            scene.close()
            Extras.settings.setWaveformSeek(false)
        }
    }
}
