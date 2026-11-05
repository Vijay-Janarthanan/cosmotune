package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.*
import com.cosmotune.ui.blocks.MusicListControls
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class MusicListControlsInteractionTest {
    @Composable private fun KitTheme(content: @Composable () -> Unit) {
        val colors = remember { com.cosmotune.ui.theme.CosmoColors::class.java.getConstructor(com.cosmotune.ui.theme.CosmoPaletteState::class.java)
            .newInstance(com.cosmotune.ui.theme.CosmoPaletteState(com.cosmotune.ui.theme.CosmoBrandPalettes.of(true), true)) }
        val type = com.cosmotune.ui.theme.rememberCosmoType()
        MaterialTheme { CompositionLocalProvider(com.cosmotune.ui.theme.LocalCosmoColors provides colors, com.cosmotune.ui.theme.LocalCosmoType provides type, content = content) }
    }

    @get:Rule val compose = createComposeRule()
    private val songs = listOf(Song("z", "Zulu", "Singer", null), Song("a", "Alpha", "Singer", null))

    @Test fun orderAndDownloadedFiltersActuallyChangeTheSameVisibleQueueAndCanBeCleared() {
        val order = mutableStateOf(MusicListOrder.DEFAULT)
        val downloaded = mutableStateOf(false)
        var shown = emptyList<IndexedValue<Song>>()
        compose.setContent { KitTheme {
            shown = MusicSongList.rows(songs, order = order.value, downloadedOnly = downloaded.value, downloadedIds = setOf("z"))
            Column(Modifier.width(360.dp)) {
                MusicListControls(shown.size, songs.size, false, order.value, { order.value = it }, downloaded.value,
                    { downloaded.value = it }, onReset = if (downloaded.value) { { downloaded.value = false } } else null)
                shown.forEach { Text(it.value.title) }
            }
        } }
        compose.onNodeWithTag("music:list:order").performClick()
        compose.onNodeWithText("Title A–Z", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(listOf(1, 0), shown.map { it.index }) }
        compose.onNodeWithTag("music:list:downloaded").performClick()
        compose.onNodeWithText("Alpha", substring = false).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("z"), shown.map { it.value.videoId }) }
        compose.onNodeWithText("Clear filters").performClick()
        compose.runOnIdle { assertEquals(listOf("a", "z"), shown.map { it.value.videoId }) }
    }

    @Test fun selectionActionsStayReachableAtLargeTextAndOnlyPlayTheSelectedVisibleRows() {
        val selecting = mutableStateOf(false)
        val selected = mutableStateOf(emptyList<Song>())
        var played = emptyList<Song>()
        compose.setContent { KitTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            Box(Modifier.size(360.dp, 650.dp)) {
                MusicListControls(2, 2, true, MusicListOrder.DEFAULT, {}, false, {}, null,
                    selecting = selecting.value, selectionCount = selected.value.size,
                    onSelecting = { selecting.value = !selecting.value }, onSelectVisible = { selected.value = songs },
                    onClearSelection = { selected.value = emptyList() }, onPlaySelection = { played = selected.value })
            }
        } } }
        compose.onNodeWithTag("music:list:select").assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        compose.onNodeWithText("Select shown").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Play selected").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(songs, played) }
        compose.onNodeWithText("Clear selection").performScrollTo().performClick()
        compose.onNodeWithText("Play selected").assertDoesNotExist()
    }
}
