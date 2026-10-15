package com.cosmotune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_cover_colours
import com.music.bitchord.sharedui.resources.cosmo_cover_colours_summary
import com.music.bitchord.sharedui.resources.cosmo_new_look
import com.music.bitchord.sharedui.resources.cosmo_new_look_summary
import org.jetbrains.compose.resources.stringResource

/** The look switches, drawn by the settings page's own private row ([row] is its SettingsToggle). */
@Composable
internal fun CosmoLookDesktopToggles(row: @Composable (String, String, Boolean, (Boolean) -> Unit) -> Unit) {
    val settings = rememberCosmoLookSettings()
    val newLook by settings.newLook.collectAsState()
    val coverColours by settings.coverColours.collectAsState()
    row(stringResource(Res.string.cosmo_new_look), stringResource(Res.string.cosmo_new_look_summary), newLook, settings::setNewLook)
    if (newLook) {
        row(stringResource(Res.string.cosmo_cover_colours), stringResource(Res.string.cosmo_cover_colours_summary), coverColours, settings::setCoverColours)
    }
}
