package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The pages of Settings, shared by phone and desktop, in the order the settings home lists them. [ALL] is not a page: it is the
 * settings home itself (the list of categories and the search field). Every setting lives on exactly one of the other pages; a
 * page may carry a link to a setting that lives elsewhere. Choosing a category never becomes a search term.
 */
enum class SettingsCategory {
    ALL,
    ACCOUNT,
    PLAYBACK,
    SOURCES,
    DOWNLOADS,
    LYRICS,
    APPEARANCE,
    VIDEO,
    WATCH,
    READ,
    MANGA,
    PODIO,
    LISTENING,
    INTEGRATIONS,
    PRIVACY,
    ABOUT,
    ;

    companion object {
        /** Every page of Settings, in the order of the settings home (everything but [ALL]). */
        val pages: List<SettingsCategory> = entries.filter { it != ALL }
    }
}

/** Opens the common Settings surface at a section without changing the active mode. */
val LocalOpenCommonSettings = staticCompositionLocalOf<((SettingsCategory) -> Unit)?> { null }

@Composable
fun SettingsModeTabs(selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit, modifier: Modifier = Modifier) {
    val activeTab = remember { BringIntoViewRequester() }
    val tabs = listOf(SettingsCategory.ALL, SettingsCategory.PLAYBACK) + SETTINGS_MODE_PAGES
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { category ->
            val isSelected = selected == category || (category == SettingsCategory.ALL && selected !in tabs)
            val label = when (category) {
                SettingsCategory.ALL -> SettingsText.GROUP_GENERAL.text()
                SettingsCategory.PLAYBACK -> "Music"
                else -> category.title()
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(category) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp)
                    .then(if (isSelected) Modifier.bringIntoViewRequester(activeTab) else Modifier)
                    .testTag("settings:mode:${category.name}"),
            )
        }
    }
    LaunchedEffect(selected) { activeTab.bringIntoView() }
}

@Composable
fun SettingsDetailDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val look = com.cosmotune.ui.orbit.orbitLook()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).clip(RoundedCornerShape(22.dp)).background(look.ground)) {
            SettingsPageHeader(title = title, onBack = onClose, modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** Wrapping destinations keep every category visible at phone widths and larger text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsCategories(
    categories: List<Pair<SettingsCategory, String>>,
    selectedCategory: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        categories.forEach { (category, label) ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onSelect(category) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 44.dp).testTag("settings:category:${category.name}"),
            )
        }
    }
}
