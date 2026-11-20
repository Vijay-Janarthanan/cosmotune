package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.music.data.NerdStats
import com.cosmotune.theme.OnePalette
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The measured quality in the song card's time row (the Now Playing board, B1: "1:12 · FLAC 24/96 · 3:48"), for the video and
 * the cover alike: it shows only once the decoder has measured the song that plays, and a tap on it opens the audio details.
 */
class RecordAudioDetailsTest {
    private data class Shown(val label: Rect?, val opened: Boolean)

    private fun render(measuredFor: String): Shown {
        val owner = object : LifecycleOwner {
            override val lifecycle = object : Lifecycle() {
                override val currentState = State.DESTROYED
                override fun addObserver(observer: LifecycleObserver) {}
                override fun removeObserver(observer: LifecycleObserver) {}
            }
        }
        val colors = CosmoColors(CosmoPaletteState(OnePalette.of(true), true))
        val fonts = OrbitFonts(FontFamily.Default, FontFamily.Default, FontFamily.Default)
        var label: Rect? = null
        var opened = false
        val previous = NerdStats.current.value
        NerdStats.current.value = NerdStats.Snapshot("audio/flac", null, 96_000, 2, 24, mediaId = measuredFor)
        val scene = ImageComposeScene(width = 360, height = 120, density = Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalCosmoColors provides colors) {
                Box(Modifier.onGloballyPositioned { position -> label = position.boundsInRoot().takeIf { it.height > 0f } }) {
                    RecordAudioDetails("song", fonts, Color.White) { opened = true }
                }
            }
        }
        try {
            scene.render()
            label?.center?.let { point ->
                scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = 1_000L, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = 1_016L, type = PointerType.Touch)
                scene.render()
            }
            return Shown(label, opened)
        } finally {
            scene.close()
            NerdStats.current.value = previous
        }
    }

    @Test fun theMeasuredQualityIsATapTargetThatOpensTheAudioDetails() {
        val shown = render("song")
        val label = assertNotNull(shown.label)
        assertTrue(label.height >= 32f, "a target a finger can hit")
        assertTrue(shown.opened, "a tap opens the audio details")
    }

    @Test fun nothingIsClaimedForAnotherTracksMeasurement() {
        assertNull(render("previous-song").label)
    }
}
