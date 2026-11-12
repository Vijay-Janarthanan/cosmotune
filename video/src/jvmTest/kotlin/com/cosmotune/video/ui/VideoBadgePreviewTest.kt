package com.cosmotune.video.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.video.model.PlaylistRef
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.blocks.LocalVideoNowMs
import com.cosmotune.video.ui.blocks.VideoCard
import com.cosmotune.video.ui.blocks.VideoRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Cards with the length on the picture (12:04, 1:02:33, LIVE) and a Mix card, drawn to `build/screens/`, light and dark. */
class VideoBadgePreviewTest {
    @BeforeTest fun mainDispatcher() = Dispatchers.setMain(RenderThread)

    @AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()

    private val v = FakeVideoSource.videos
    private val short = v[0].copy(durationSec = 724)
    private val long = v[1].copy(durationSec = 3753)
    private val live = v[2].copy(durationSec = null, isLive = true)
    private val mix = VideoItem(
        "list:RDx", "Mix - Quiet Evenings | Lantern Hour", null, "Maple Choir, The Rowers and more \u00b7 Playlist", null, v[3].thumbnailUrl,
        null, null, null, null, playlist = PlaylistRef("RDx", "Mix", v[3].id),
    )

    private fun scene(dark: Boolean) = PreviewKit.render(
        "video-lists-badges-phone-${if (dark) "dark" else "light"}", 390, 1500, dark,
        providers = arrayOf(LocalVideoNowMs provides FakeVideoSource.NOW_MS), outDir = "build/screens",
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
            VideoCard(short, {}, {})
            VideoCard(mix, {}, {})
            VideoCard(live, {}, {})
            VideoRow(long, {}, {})
            VideoRow(short, {}, {})
        }
    }

    @Test fun lightBadges() = scene(false)

    @Test fun darkBadges() = scene(true)
}
