@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cosmotune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.ic_logo
import com.cosmotune.settings.AppIcon
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import org.jetbrains.compose.resources.painterResource

/** The title and line of the row below, for the hosts' search. */
object AppIconText {
    const val TITLE = "App icon"
    const val LINE_PHONE = "The icon on your home screen. It can take a moment to change there."
    const val LINE_DESKTOP = "The icon of the window, the taskbar and the tray."
}

/**
 * Look and colours, App icon: each icon as a small tile with its name under it; the chosen one carries a ring. [line] says where
 * the icon shows on this host, and [onChosen] lets the host apply it (the phone switches its launcher entry).
 */
@Composable
fun CosmoAppIconSetting(line: String, onChosen: (AppIcon) -> Unit = {}, modifier: Modifier = Modifier) {
    val settings = rememberCosmoLookSettings()
    val chosen by settings.appIcon.collectAsState()
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth()) {
        CosmoSettingsRow(icon = CosmoIcons.Grid, title = AppIconText.TITLE, subtitle = line)
        FlowRow(
            Modifier.fillMaxWidth().padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon.entries.forEach { icon ->
                val selected = icon == chosen
                Column(
                    Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = selected, role = Role.RadioButton) {
                            if (!selected) {
                                settings.setAppIcon(icon)
                                onChosen(icon)
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .border(2.dp, if (selected) look.accent else Color.Transparent, RoundedCornerShape(16.dp))
                            .padding(4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(icon.ground))
                            .border(1.dp, look.chip, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_logo),
                            contentDescription = null,
                            tint = Color(icon.lit),
                            modifier = Modifier.size(width = 30.dp, height = 20.dp),
                        )
                    }
                    Text(
                        icon.label,
                        style = fonts.body,
                        color = if (selected) look.ink else look.dim,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
