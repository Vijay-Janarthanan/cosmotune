package com.cosmotune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Music Home's discovery row in Orbit: Region and Language as chips that name their choice ("Region: India") and open their list in
 * the Orbit menu, "Make my playlist" as a third chip, and while a choice is saved a small orbit whose moon circles at the row's end.
 * Under it the line that says what Home shows, and a line in the theme's danger colour when a choice could not be saved.
 */
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
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OrbitChipRow(CosmoSpacing.margin) {
            DiscoveryPicker("home:country", "Region", selected.countryLabel, selected.country, countries, !saving, fonts, look) { save(selected.copy(country = it)) }
            DiscoveryPicker(
                "home:language", "Language", languages.find { it.first == selected.musicLanguage }?.second ?: selected.languageLabel,
                selected.musicLanguage, languages, !saving, fonts, look,
            ) { save(selected.copy(musicLanguage = it)) }
            if (onCreatePlaylist != null) {
                OrbitChip(
                    "Make my playlist", fonts, look, onCreatePlaylist, Modifier.testTag("home:make-playlist"),
                    leading = { Icon(CosmoIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            // Saving: Orbit's progress, the ring with its moon going round.
            if (saving) OrbitRing(28.dp, look.ink, progress = null, spinning = true, stroke = 1.5.dp, moon = 3.dp, modifier = Modifier.semantics { contentDescription = "Saving" })
        }
        Text(
            "${selected.countryLabel} charts" + if (selected.musicLanguage.isBlank()) " · Choose a language to explore its music" else " · ${selected.languageLabel} discovery appears first",
            style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = CosmoSpacing.margin),
        )
        if (error) {
            Text(
                "Could not save discovery preferences. Try again.", style = fonts.body, color = Color(LocalCosmoColors.current.palette.danger),
                modifier = Modifier.padding(horizontal = CosmoSpacing.margin),
            )
        }
    }
}

/** A chip that names its choice ("Region: India") with a chevron, and opens its list in the Orbit menu, the chosen one solid ink. */
@Composable
private fun DiscoveryPicker(
    tag: String,
    title: String,
    value: String,
    current: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OrbitChip(
            "$title: $value", fonts, look, onClick = { if (enabled) expanded = true },
            modifier = Modifier.testTag(tag).alpha(if (enabled) 1f else 0.45f),
            trailing = { Icon(CosmoIcons.ChevronDown, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp)) },
            leading = null,
        )
        OrbitMenu(
            open = expanded,
            onDismiss = { expanded = false },
            items = options.map { (id, label) -> OrbitMenuItem(label, selected = id == current, testTag = "$tag:$id") { onSelect(id) } },
            fonts = fonts,
            look = look,
            header = title,
        )
    }
}
