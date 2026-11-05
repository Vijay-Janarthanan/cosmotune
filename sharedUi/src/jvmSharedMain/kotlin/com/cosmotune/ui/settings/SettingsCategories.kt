package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    READ,
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
