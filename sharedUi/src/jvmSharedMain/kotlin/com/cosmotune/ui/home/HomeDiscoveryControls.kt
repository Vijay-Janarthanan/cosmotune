package com.cosmotune.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
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
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DiscoveryPicker("home:country", "Region", selected.countryLabel, countries, !saving) { save(selected.copy(country = it)) }
            DiscoveryPicker("home:language", "Language", languages.find { it.first == selected.musicLanguage }?.second ?: selected.languageLabel, languages, !saving) { save(selected.copy(musicLanguage = it)) }
            if (onCreatePlaylist != null) OutlinedButton(onClick = onCreatePlaylist, modifier = Modifier.testTag("home:make-playlist")) {
                Text("Make my playlist")
            }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("${selected.countryLabel} charts" + if (selected.musicLanguage.isBlank()) " · Choose a language to explore its music" else " · ${selected.languageLabel} discovery appears first",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error) Text("Could not save discovery preferences. Try again.", color = MaterialTheme.colorScheme.error)
    }
    }
}

@Composable
private fun DiscoveryPicker(tag: String, title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag(tag)) {
            Column { Text(title, style = MaterialTheme.typography.labelSmall); Text(value) }
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 8.dp).size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("$tag:$id"),
                onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
