package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.*
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.pages.CosmoReleasePage
import com.cosmotune.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Exercises the actual new-look release page, not a stand-in controls-only list. */
class ReleaseListInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        Song("same", "Zulu", "Singer", null, setVideoId = "first", durationText = "3:01"),
        Song("alpha", "Alpha", "Singer", null, setVideoId = "middle", durationText = "3:02"),
        Song("same", "Zulu", "Singer", null, setVideoId = "last", durationText = "3:03"),
    )
    private var played = emptyList<Song>()
    private var start = -1
    private var shuffled = emptyList<Song>()
    private var more = emptyList<Song>()

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null); val previousUi = uiField.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = setOf("same")
        })
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader,
            arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                else -> error("Release page unexpectedly requested ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi) }
    }

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
                .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true)) }
            val typography = remember { CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
                .newInstance(360f, FontFamily.Default, FontFamily.Default) }
            MaterialTheme(colorScheme = colors.palette.toColorScheme(MaterialTheme.colorScheme),
                typography = typography.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography,
                    LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.size(360.dp, 850.dp)) {
                        CosmoReleasePage(page = DetailPage("release", "Fixture album", "Album", null,
                            songs = UiState.Success(rows), type = BrowseType.ALBUM),
                            songs = rows, currentSong = null,
                            isPlaying = false, listState = rememberLazyListState(), contentPadding = PaddingValues(),
                            onSongClick = { songs, index -> played = songs; start = index }, onSongLongPress = {},
                            onSongSwipe = {}, onShuffle = { shuffled = it }, onMore = { more = it },
                            onArtistClick = { _, _ -> }, onAddSuggested = {}, onToggleLibrary = null,
                            downloadedIds = AppUi.host.downloadedIds())
                    }
                }
            }
        }
    }

    @Test fun sortedRowPlaybackAndSelectionRetainTheExactDuplicateOccurrence() = hosts {
        show()
        compose.onNodeWithTag("music:list:order").performScrollTo().performClick()
        compose.onNodeWithText("Title A–Z", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("music:release:row:entry:last").performScrollTo()
        compose.onNodeWithTag("music:release:row:entry:last").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("middle", "first", "last"), played.map { it.setVideoId })
            assertEquals(2, start)
        }
        compose.onNodeWithTag("music:list:select").performScrollTo().performClick()
        compose.onNodeWithTag("music:release:row:entry:last").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Play selected").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("last"), played.map { it.setVideoId }); assertEquals(0, start) }
        compose.onNodeWithText("More", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("last"), more.map { it.setVideoId }) }
    }

    @Test fun downloadedFilteringAndHeaderActionsUseTheVisibleQueueAtTwoHundredPercentText() = hosts {
        show(fontScale = 2f)
        compose.onNodeWithTag("music:list:downloaded").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("music:release:row:entry:middle").assertDoesNotExist()
        compose.onNodeWithTag("orbit:card:play").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("first", "last"), played.map { it.setVideoId }) }
        compose.onNodeWithContentDescription("Shuffle").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("first", "last"), shuffled.map { it.setVideoId }) }
        compose.onNodeWithTag("music:release:more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("first", "last"), more.map { it.setVideoId }) }
        compose.onNodeWithTag("music:list:order").performScrollTo().performClick()
        compose.onNodeWithText("Oldest added").assertDoesNotExist()
        compose.onNodeWithText("Newest added").assertDoesNotExist()
        compose.onNodeWithText("Default order", useUnmergedTree = true).performClick()
        val out = File("../build/video-playback-review/release-list-phone-large-text.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", out)
    }
}
