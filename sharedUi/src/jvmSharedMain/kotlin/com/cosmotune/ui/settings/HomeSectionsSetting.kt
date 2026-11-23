package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

const val HOME_SECTIONS_TITLE = "Home sections"
const val HOME_SECTIONS_LINE = "Choose what Home shows"

/** How many shelves show before "Show all". */
private const val SHELVES_SHOWN = 6

/**
 * Look and colours, Home sections (the upstream's Content preferences): a switch for the top card, the chips (quick links and moods)
 * and each shelf Home has shown, so a section the listener never uses can go. Turning one off takes it off Home at once.
 */
@Composable
fun HomeSectionsSetting(modifier: Modifier = Modifier) {
    val settings = rememberCosmoLookSettings()
    val hidden by settings.hiddenHome.collectAsState()
    val shelves by settings.homeShelves.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth()) {
        CosmoSettingsRow(icon = CosmoIcons.Home, title = HOME_SECTIONS_TITLE, subtitle = HOME_SECTIONS_LINE)
        Column(
            Modifier.padding(start = CosmoSettingsInsets.side, end = CosmoSettingsInsets.side, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fixed = listOf(CosmoLookSettings.HOME_PICK_UP to "Pick up where you left off", CosmoLookSettings.HOME_CHIPS to "Quick links and moods")
            val listed = fixed + shelves.map { it to it }
            (if (showAll) listed else listed.take(SHELVES_SHOWN + fixed.size)).forEach { (id, title) ->
                OrbitSwitchRow(
                    title = title,
                    checked = id !in hidden,
                    onChange = { shown -> settings.setHomeSectionHidden(id, !shown) },
                    description = "Show $title on Home",
                )
            }
            if (listed.size > SHELVES_SHOWN + fixed.size) {
                OrbitActionPill(if (showAll) "Show fewer" else "Show all ${listed.size}", { showAll = !showAll }, fonts, look)
            }
        }
    }
}
