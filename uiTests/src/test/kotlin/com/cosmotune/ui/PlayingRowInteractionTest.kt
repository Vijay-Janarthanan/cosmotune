package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.components.SongRow
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.blocks.CosmoSongRow
import com.cosmotune.ui.library.CosmoHistory
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * BC-30, rendered: a list holding a song and its namesake (the music video, an add-on's other tier) lights the row that was
 * played and no other, and a song row shows the tier its source offered only where the list asks for it (the add-on search).
 */
class PlayingRowInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val track = Song("track", "Same name", "Same artist", null, durationText = "3:01")
    private val video = Song("video", "Same name", "Same artist", null, durationText = "4:02")

    private fun withRowHosts(block: () -> Unit) {
        val installedHost = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = installedHost.get(null)
        val installedUi = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousUi = installedUi.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        val host = java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                else -> error("A song row unexpectedly requested ${method.name}")
            }
        } as PlayerHost
        PlayerPlatform.install(host)
        try { block() } finally { installedHost.set(null, previous); installedUi.set(null, previousUi) }
    }

    @Composable
    private fun NewLook(content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(400f, FontFamily.Default, FontFamily.Default)
        MaterialTheme {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                Box(Modifier.width(400.dp).fillMaxHeight()) { content() }
            }
        }
    }

    @Test fun historyLightsOnlyTheRowThatWasPlayed() = withRowHosts {
        compose.setContent {
            NewLook {
                CosmoHistory(
                    state = UiState.Success(listOf(track, video)),
                    listState = rememberLazyListState(),
                    onSongClick = { _, _ -> },
                    onSongLongPress = {},
                    onSongSwipe = {},
                    onRetry = {},
                    contentPadding = PaddingValues(0.dp),
                    currentSong = video,
                    isPlaying = true,
                )
            }
        }
        // The row that is playing carries the equaliser ("Now playing"); the namesake above it does not.
        compose.onAllNodes(hasContentDescription("Now playing")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Now playing") and hasText("4:02")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Now playing") and hasText("3:01")).assertCountEquals(0)
    }

    @Test fun theNewLookRowShowsTheOfferedTierOnlyWhenAsked() = withRowHosts {
        val atmos = Song("src:a::tidal:2", "Atmos mix", "Singer", null, sourceQuality = "DOLBY")
        val hiRes = Song("src:a::tidal:3", "Hi-res mix", "Singer", null, sourceQuality = "LOSSLESS", sourceHiRes = true)
        val plain = Song("src:a::tidal:4", "Plain mix", "Singer", null, sourceQuality = "DOLBY")
        compose.setContent {
            NewLook {
                Column {
                    CosmoSongRow(song = atmos, onClick = {}, showSourceQuality = true)
                    CosmoSongRow(song = hiRes, onClick = {}, showSourceQuality = true)
                    CosmoSongRow(song = plain, onClick = {})
                }
            }
        }
        compose.onAllNodesWithText("Dolby Atmos", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Hi-Res Lossless", substring = true).assertCountEquals(1)
        compose.onAllNodes(hasText("Plain mix", substring = true) and hasText("Dolby Atmos", substring = true)).assertCountEquals(0)
    }

    @Test fun theClassicRowShowsTheOfferedTierOnlyWhenAsked() = withRowHosts {
        val lossless = Song("src:a::qobuz:1", "Lossless mix", "Singer", null, sourceQuality = "LOSSLESS")
        val unlabelled = Song("src:a::qobuz:2", "Low mix", "Singer", null, sourceQuality = "LOW")
        compose.setContent {
            MaterialTheme {
                Column {
                    SongRow(song = lossless, onClick = {}, showSourceQuality = true)
                    SongRow(song = unlabelled, onClick = {}, showSourceQuality = true)
                    SongRow(song = lossless.copy(videoId = "src:a::qobuz:3", title = "Unasked"), onClick = {})
                }
            }
        }
        compose.onAllNodesWithText("Lossless", substring = false, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Singer", substring = false, useUnmergedTree = true).assertCountEquals(2)
    }
}
