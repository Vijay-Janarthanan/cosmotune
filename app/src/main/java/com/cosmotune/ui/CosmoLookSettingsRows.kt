package com.cosmotune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.cosmo_cover_colours
import com.music.bitchord.sharedui.resources.cosmo_cover_colours_summary
import com.music.bitchord.sharedui.resources.cosmo_new_look
import com.music.bitchord.sharedui.resources.cosmo_new_look_summary
import com.music.bitchord.ui.screens.RowDivider
import com.music.bitchord.ui.screens.SettingsRow
import org.jetbrains.compose.resources.stringResource

/**
 * The two switches of the new look, drawn with the settings page's own row so they sit in its Appearance group
 * like their neighbours. "Colours follow the cover" is only live while "New look" is on.
 */
@Composable
internal fun CosmoLookSettingsRows() {
    val settings = rememberCosmoLookSettings()
    val newLook by settings.newLook.collectAsStateWithLifecycle()
    val coverColours by settings.coverColours.collectAsStateWithLifecycle()
    Column {
        SettingsRow(
            icon = Icons.Rounded.Palette,
            title = stringResource(SharedRes.string.cosmo_new_look),
            subtitle = stringResource(SharedRes.string.cosmo_new_look_summary),
            trailing = {
                Switch(
                    checked = newLook,
                    onCheckedChange = settings::setNewLook,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            onClick = { settings.setNewLook(!newLook) },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.ColorLens,
            title = stringResource(SharedRes.string.cosmo_cover_colours),
            subtitle = stringResource(SharedRes.string.cosmo_cover_colours_summary),
            enabled = newLook,
            trailing = {
                Switch(
                    checked = coverColours,
                    onCheckedChange = settings::setCoverColours,
                    enabled = newLook,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            onClick = { settings.setCoverColours(!coverColours) },
        )
    }
}
