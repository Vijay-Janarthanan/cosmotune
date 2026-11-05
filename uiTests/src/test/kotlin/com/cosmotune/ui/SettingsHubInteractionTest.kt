package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.settings.SettingsCategory
import com.cosmotune.ui.settings.SettingsCategoryEntry
import com.cosmotune.ui.settings.SettingsCategoryRail
import com.cosmotune.ui.settings.SettingsHub
import com.cosmotune.ui.settings.SettingsItem
import com.cosmotune.ui.settings.SettingsSearchResults
import com.cosmotune.ui.settings.icon
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The settings home, its search results and the desktop's category list, rendered. */
class SettingsHubInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val entries = SettingsCategory.pages.map { SettingsCategoryEntry(it, "Title ${it.name}", "Summary ${it.name}", it.icon) }

    @Composable
    private fun NewLook(content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(400f, FontFamily.Default, FontFamily.Default)
        MaterialTheme {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                Box(Modifier.size(400.dp, 900.dp)) { content() }
            }
        }
    }

    @Test fun everyCategoryIsOnTheHomeWithItsSummaryAndOpensItsPage() {
        var opened: SettingsCategory? = null
        compose.setContent {
            NewLook {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHub(entries = entries, onOpen = { opened = it })
                }
            }
        }
        for (page in SettingsCategory.pages) {
            compose.onNodeWithTag("settings:hub:${page.name}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Summary ${page.name}").assertIsDisplayed()
        }
        compose.onNodeWithTag("settings:hub:LYRICS").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SettingsCategory.LYRICS, opened) }
    }

    @Test fun theAccountCardStandsInForTheAccountRow() {
        compose.setContent {
            NewLook {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsHub(entries = entries, onOpen = {}, accountCard = { androidx.compose.material3.Text("The account card") })
                }
            }
        }
        compose.onNodeWithText("The account card").assertIsDisplayed()
        compose.onNodeWithTag("settings:hub:ACCOUNT").assertDoesNotExist()
        compose.onNodeWithTag("settings:hub:PLAYBACK").assertIsDisplayed()
    }

    @Test fun aResultSaysWhereItLivesAndPickingItReportsIt() {
        val item = SettingsItem("PLAYBACK:Equalizer", "Equalizer", SettingsCategory.PLAYBACK, summary = "Tone and balance", group = "Sound & effects")
        var picked: SettingsItem? = null
        compose.setContent {
            NewLook { SettingsSearchResults(results = listOf(item), query = "eq", onPick = { picked = it }) }
        }
        compose.onNodeWithText("Equalizer").assertIsDisplayed()
        compose.onNodeWithText("Tone and balance", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sound & effects", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings:result:PLAYBACK:Equalizer").performClick()
        compose.runOnIdle { assertEquals(item, picked) }
    }

    @Test fun noResultsSaySo() {
        compose.setContent {
            NewLook { SettingsSearchResults(results = emptyList(), query = "zzz", onPick = {}, emptyMessage = "Nothing for zzz") }
        }
        compose.onNodeWithTag("settings:results:empty").assertIsDisplayed()
        compose.onNodeWithText("Nothing for zzz").assertIsDisplayed()
    }

    @Test fun theRailMarksOneCategory() {
        var selected = SettingsCategory.PLAYBACK
        compose.setContent {
            NewLook { SettingsCategoryRail(entries = entries.take(5), selected = SettingsCategory.PLAYBACK, onSelect = { selected = it }) }
        }
        compose.onNodeWithTag("settings:rail:PLAYBACK").assertIsSelected()
        compose.onNodeWithTag("settings:rail:SOURCES").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(SettingsCategory.SOURCES, selected) }
    }
}
