package com.cosmotune.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.ui.kit.KitActionRow
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeDiscoveryControls(onRefresh: () -> Unit, onCreatePlaylist: (() -> Unit)? = null) {
    val selected by DiscoveryPreferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val countries = DiscoveryPreferences.countries
    val languages = DiscoveryPreferences.languages
    fun save(value: com.cosmotune.music.data.recommendations.DiscoveryRegion) {
        if (saving) return
        saving = true; error = false
        scope.launch {
            try { withContext(Dispatchers.IO) { DiscoveryPreferences.save(value) }; onRefresh() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { saving = false }
        }
    }
    val colors = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KitActionRow {
            DiscoveryPicker("home:country", "Region", selected.countryLabel, countries, !saving) { save(selected.copy(country = it)) }
            DiscoveryPicker("home:language", "Language", languages.find { it.first == selected.musicLanguage }?.second ?: selected.languageLabel, languages, !saving) { save(selected.copy(musicLanguage = it)) }
            if (onCreatePlaylist != null) KitButton("Make my playlist", onCreatePlaylist, Modifier.testTag("home:make-playlist"), kind = KitButtonKind.QUIET, dense = true)
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin))
        Text("${selected.countryLabel} charts" + if (selected.musicLanguage.isBlank()) " · Choose a language to explore its music" else " · ${selected.languageLabel} discovery appears first",
            style = type.meta, color = Color(colors.textSecondary), modifier = Modifier.padding(horizontal = CosmoSpacing.margin))
        if (error) Text("Could not save discovery preferences. Try again.", style = type.meta, color = Color(colors.danger), modifier = Modifier.padding(horizontal = CosmoSpacing.margin))
    }
}

/** A tonal pill that names its choice ("Region: India") and opens its list. */
@Composable
private fun DiscoveryPicker(tag: String, title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KitButton("$title: $value", { expanded = true }, Modifier.testTag(tag), kind = KitButtonKind.TONAL, enabled = enabled, dense = true)
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("$tag:$id"),
                onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
