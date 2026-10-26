package com.cosmotune.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeDiscoveryControls(onRefresh: () -> Unit, onCreatePlaylist: (() -> Unit)? = null) {
    val selected by DiscoveryPreferences.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val countries = remember { listOf("ZZ" to "Global") + Locale.getISOCountries().map {
        it to Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH)
    }.sortedBy { it.second } }
    val languages = remember { listOf("" to "Any language") + Locale.getISOLanguages().map {
        it to Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)
    }.distinctBy { it.first }.sortedBy { it.second } }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DiscoveryPicker("home:country", "Charts · ${selected.countryLabel}", countries, !saving) { save(selected.copy(country = it)) }
            DiscoveryPicker("home:language", "Music · ${selected.languageLabel}", languages, !saving) { save(selected.copy(musicLanguage = it)) }
            if (onCreatePlaylist != null) OutlinedButton(onClick = onCreatePlaylist, modifier = Modifier.testTag("home:make-playlist")) {
                Text("Make my playlist")
            }
        }
        Text("Charts follow country. Music language guides discovery and supported provider feeds; it does not change the app language.", style = MaterialTheme.typography.bodySmall)
        if (error) Text("Could not save discovery preferences. Try again.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DiscoveryPicker(tag: String, title: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(title) }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("$tag:$id"),
                onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
