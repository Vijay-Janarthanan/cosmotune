package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.music.data.NerdStats
import com.cosmotune.theme.OnePalette
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import dev.chrisbanes.haze.HazeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordAudioDetailsTest {
    private data class Layout(val record: Rect, val details: Rect?, val card: Rect, val opened: Boolean)

    private fun render(height: Int, measuredFor: String, kind: PhoneMedia = PhoneMedia.RECORD): Layout {
        val owner = object : LifecycleOwner {
            override val lifecycle = object : Lifecycle() {
                override val currentState = State.DESTROYED
                override fun addObserver(observer: LifecycleObserver) {}
                override fun removeObserver(observer: LifecycleObserver) {}
            }
        }
        val colors = CosmoColors(CosmoPaletteState(OnePalette.of(true), true))
        val fonts = OrbitFonts(FontFamily.Default, FontFamily.Default, FontFamily.Default)
        val look = OrbitLook(true, Color.White, Color.LightGray, Color.DarkGray, Color.DarkGray, Color.Cyan, Color.Black)
        var record = Rect.Zero
        var details: Rect? = null
        var card = Rect.Zero
        var opened = false
        val previous = NerdStats.current.value
        NerdStats.current.value = NerdStats.Snapshot("audio/flac", null, 48_000, 2, 24, mediaId = measuredFor)
        val scene = ImageComposeScene(width = 360, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalCosmoColors provides colors) {
                NowPlayingPhone(
                    HazeState(), kind, 16f / 9,
                    topRow = { Box(it.height(40.dp)) },
                    media = { Box(it.onGloballyPositioned { position -> record = position.boundsInRoot() }) },
                    card = { Box(it.height(160.dp).onGloballyPositioned { position -> card = position.boundsInRoot() }) },
                    chips = { Box(it.height(44.dp)) },
                    recordDetails = {
                        Box(Modifier.onGloballyPositioned { position -> details = position.boundsInRoot().takeIf { it.height > 0f } }) {
                            RecordAudioDetails("song", fonts, look) { opened = true }
                        }
                    },
                )
            }
        }
        try {
            scene.render()
            details?.center?.let { point ->
                scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = 1_000L, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = 1_016L, type = PointerType.Touch)
                scene.render()
            }
            return Layout(record, details, card, opened)
        } finally {
            scene.close()
            NerdStats.current.value = previous
        }
    }

    @Test fun sparePortraitSpaceFitsTheClickablePillWithoutShrinkingTheRecord() {
        val tall = render(760, "song")
        val short = render(620, "song")
        val pill = assertNotNull(tall.details)
        assertTrue(pill.height >= 44f)
        assertTrue(tall.record.bottom <= pill.top && pill.bottom <= tall.card.top)
        assertTrue(tall.opened)
        assertNull(short.details)
        assertEquals(tall.record.height, short.record.height)
    }

    @Test fun artworkPillHidesForAnotherTrackOrTheVideoLayout() {
        assertNull(render(760, "previous-song").details)
        assertNull(render(760, "song", PhoneMedia.WHOLE).details)
    }
}
