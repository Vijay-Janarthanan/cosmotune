package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.playback.EqUserPresets
import com.cosmotune.settings.Extras
import com.cosmotune.settings.ExtrasSettings
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * The equaliser's Your presets, on the phone and the desktop: the curves the listener saved by name as chips (a tap puts one
 * on, the cross beside the chosen one removes it), and Save this curve with a name field under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqUserPresetsSection(
    bands: List<Float>,
    onApply: (List<Float>) -> Unit,
    modifier: Modifier = Modifier,
    side: Dp = 16.dp,
    enabled: Boolean = true,
    settings: ExtrasSettings = Extras.settings,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val presets by settings.eqUserPresets.collectAsState()
    val chosen = remember(presets, bands) { EqUserPresets.matching(presets, bands) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(horizontal = side).testTag("equaliser:your-presets")) {
        Text("Your presets", style = fonts.label, color = look.dim)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in presets) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OrbitChip(preset.name, fonts, look, onClick = { if (enabled) onApply(preset.bands) }, selected = preset == chosen, leading = null)
                    if (preset == chosen) {
                        CosmoIconButton(CosmoIcons.Close, "Remove ${preset.name}", { settings.setEqUserPresets(EqUserPresets.remove(presets, preset.name)) }, size = 36.dp)
                    }
                }
            }
            if (!naming) {
                OrbitPillButton("Save this curve", { naming = true; name = chosen?.name.orEmpty() }, fonts, look, enabled = enabled, icon = CosmoIcons.Plus, height = 40.dp)
            }
        }
        if (presets.isEmpty() && !naming) {
            Spacer(Modifier.height(8.dp))
            Text("Shape the bands, then save the curve here to come back to it.", style = fonts.body, color = look.dim)
        }
        if (naming) {
            Spacer(Modifier.height(8.dp))
            OrbitInputField(name, { name = it.take(EqUserPresets.MAX_NAME) }, "Name this curve", fonts, look, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbitPillButton("Save", {
                    settings.setEqUserPresets(EqUserPresets.save(presets, name, bands))
                    naming = false
                }, fonts, look, primary = true, enabled = name.isNotBlank(), height = 40.dp)
                OrbitPillButton("Cancel", { naming = false }, fonts, look, height = 40.dp)
            }
        }
    }
}
