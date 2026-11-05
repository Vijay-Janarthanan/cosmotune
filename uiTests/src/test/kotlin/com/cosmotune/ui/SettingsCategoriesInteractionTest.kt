package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.settings.SettingsCategories
import com.cosmotune.ui.settings.SettingsCategory
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SettingsCategoriesInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val categories = listOf(
        SettingsCategory.ALL to "All settings", SettingsCategory.SOURCES to "Sources",
        SettingsCategory.PLAYBACK to "Playback", SettingsCategory.APPEARANCE to "Appearance",
        SettingsCategory.DOWNLOADS to "Downloads", SettingsCategory.LISTENING to "Listening tools",
        SettingsCategory.ACCOUNT to "Accounts", SettingsCategory.ABOUT to "About",
    )

    @Test fun everyDestinationFitsAPhoneAtLargeTextWithoutHorizontalScrolling() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        SettingsCategories(categories, SettingsCategory.ALL, {})
                    }
                }
            }
        }
        categories.forEach { (category, _) ->
            compose.onNodeWithTag("settings:category:${category.name}")
                .assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        }
    }

    @Test fun sourceAndAllDestinationsHaveOneSelectedStateAndReportStableIds() {
        var last: SettingsCategory? = null
        compose.setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf(SettingsCategory.ALL) }
                Box(Modifier.size(700.dp, 420.dp)) {
                    SettingsCategories(categories, selected, { selected = it; last = it })
                }
            }
        }
        compose.onNodeWithTag("settings:category:SOURCES").performClick().assertIsSelected()
        compose.onNodeWithTag("settings:category:ALL").assertIsNotSelected()
        compose.runOnIdle { assertEquals(SettingsCategory.SOURCES, last) }
        compose.onNodeWithTag("settings:category:ALL").performClick().assertIsSelected()
        compose.onNodeWithTag("settings:category:SOURCES").assertIsNotSelected()
        compose.runOnIdle { assertEquals(SettingsCategory.ALL, last) }
    }
}
