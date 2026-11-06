package com.cosmotune.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.sheets.CosmoSheetSurface
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import kotlin.test.assertEquals
import kotlin.test.assertFails
import org.junit.Rule
import org.junit.Test

/**
 * The Orbit sheet's surface ([CosmoSheetSurface], under every SurfaceSheetSwitch, OrbitPickerSheet and OrbitSheetHost of the phone)
 * scrolls what it holds. Home's "Start a room" crashed the app because the room landing it opened scrolled itself as well ("Vertically
 * scrollable component was measured with an infinity maximum height"), and the playlist reorder sheet's lazy list had the same
 * fault. These open the surface with content shaped as those sheets are now: a tall landing with no scroll of its own still reaches
 * its last button on a short window, a lazy list with a height of its own lays out, and the nested scroll is the crash it was.
 */
class SheetSurfaceScrollInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun NewLook(content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(400f, FontFamily.Default, FontFamily.Default)
        MaterialTheme {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                Box(Modifier.width(400.dp).height(360.dp)) { content() }
            }
        }
    }

    @Test fun aTallLandingWithoutItsOwnScrollReachesItsLastButtonOnAShortWindow() {
        var created = 0
        compose.setContent {
            NewLook {
                CosmoSheetSurface {
                    // The landing as RoomEntrySheets now hands it in the new look: tall, and no verticalScroll of its own.
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(900.dp))
                        Text("Create a room", Modifier.testTag("create").clickable { created++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("create").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun aLazyListWithItsOwnHeightLaysOutInsideTheSheet() {
        compose.setContent {
            NewLook {
                CosmoSheetSurface {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        items(200) { i -> Text("Song $i", Modifier.height(48.dp).testTag("row$i")) }
                    }
                }
            }
        }
        compose.onNodeWithTag("row0").assertExists()
    }

    @Test fun aSecondScrollInsideTheSheetIsTheCrashHomesRoomCardHad() {
        assertFails {
            compose.setContent {
                NewLook {
                    CosmoSheetSurface {
                        // The landing as commit a86ea44c handed it: scrolling itself inside the scrolling surface.
                        Column(Modifier.verticalScroll(rememberScrollState())) { Text("Create a room") }
                    }
                }
            }
            compose.waitForIdle()
        }
    }
}
