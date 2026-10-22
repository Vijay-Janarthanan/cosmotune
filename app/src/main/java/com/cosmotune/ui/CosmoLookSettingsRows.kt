package com.cosmotune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.settings.CosmoLookPreviewDialog
import com.cosmotune.ui.settings.CosmoSettingsInsets
import com.cosmotune.ui.settings.CosmoSettingsSegmented
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_cover_colours
import com.cosmotune.music.sharedui.resources.cosmo_cover_colours_summary
import com.cosmotune.music.sharedui.resources.cosmo_intensity
import com.cosmotune.music.sharedui.resources.cosmo_intensity_balanced
import com.cosmotune.music.sharedui.resources.cosmo_intensity_calm
import com.cosmotune.music.sharedui.resources.cosmo_intensity_rich
import com.cosmotune.music.sharedui.resources.cosmo_intensity_summary
import com.cosmotune.music.sharedui.resources.cosmo_look_preview
import com.cosmotune.music.sharedui.resources.cosmo_look_preview_summary
import com.cosmotune.music.sharedui.resources.cosmo_new_look
import com.cosmotune.music.sharedui.resources.cosmo_new_look_summary
import com.cosmotune.music.ui.screens.ROW_INSET
import com.cosmotune.music.ui.screens.RowDivider
import com.cosmotune.music.ui.screens.SegmentedControl
import com.cosmotune.music.ui.screens.SettingsRow
import com.cosmotune.music.ui.screens.TEXT_INSET
import org.jetbrains.compose.resources.stringResource

/**
 * The look's settings, drawn with the settings page's own rows so they sit in its Appearance group like their neighbours: the two
 * switches ("New look" and "Colours follow the cover"), the colour intensity (Calm, Balanced, Rich, a segmented control stacked under
 * its row), and the entry to the "Preview the look" style sheet. "Colours follow the cover" and the intensity are only live while
 * "New look" is on (the intensity also while the colours follow the cover); the preview works either way. The switches and the
 * intensity control are the look's own while it is on and the old page's while it is off.
 */
@Composable
internal fun CosmoLookSettingsRows() {
    val settings = rememberCosmoLookSettings()
    val newLook by settings.newLook.collectAsStateWithLifecycle()
    val coverColours by settings.coverColours.collectAsStateWithLifecycle()
    val intensity by settings.intensity.collectAsStateWithLifecycle()
    var previewOpen by remember { mutableStateOf(false) }
    val intensityLive = newLook && coverColours
    Column {
        SettingsRow(
            icon = Icons.Rounded.Palette,
            title = stringResource(SharedRes.string.cosmo_new_look),
            subtitle = stringResource(SharedRes.string.cosmo_new_look_summary),
            trailing = { LookSwitch(checked = newLook, onCheckedChange = settings::setNewLook, enabled = true, lookOn = newLook) },
            onClick = { settings.setNewLook(!newLook) },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.ColorLens,
            title = stringResource(SharedRes.string.cosmo_cover_colours),
            subtitle = stringResource(SharedRes.string.cosmo_cover_colours_summary),
            enabled = newLook,
            trailing = { LookSwitch(checked = coverColours, onCheckedChange = settings::setCoverColours, enabled = newLook, lookOn = newLook) },
            onClick = { settings.setCoverColours(!coverColours) },
        )
        RowDivider()
        Column {
            SettingsRow(
                icon = if (newLook) CosmoIcons.Equaliser else Icons.Rounded.Tune,
                title = stringResource(SharedRes.string.cosmo_intensity),
                subtitle = stringResource(SharedRes.string.cosmo_intensity_summary),
                enabled = intensityLive,
            )
            val options = listOf(
                stringResource(SharedRes.string.cosmo_intensity_calm),
                stringResource(SharedRes.string.cosmo_intensity_balanced),
                stringResource(SharedRes.string.cosmo_intensity_rich),
            )
            if (newLook) {
                // `.row2.stack`: under the row, in line with its text (70 dp from the start), the segments sharing the width.
                CosmoSettingsSegmented(
                    options = options,
                    selectedIndex = intensity,
                    onSelect = settings::setIntensity,
                    modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 12.dp),
                    enabled = intensityLive,
                    compact = true,
                )
            } else {
                SegmentedControl(
                    options = options,
                    selectedIndex = intensity,
                    onSelect = settings::setIntensity,
                    modifier = Modifier.alpha(if (intensityLive) 1f else 0.45f).padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
                    enabled = intensityLive,
                )
            }
        }
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Visibility,
            title = stringResource(SharedRes.string.cosmo_look_preview),
            subtitle = stringResource(SharedRes.string.cosmo_look_preview_summary),
            onClick = { previewOpen = true },
        )
    }
    if (previewOpen) CosmoLookPreviewDialog(onDismiss = { previewOpen = false })
}

/** The look's own switch while the look is on, Material's (as the rest of the old page draws it) while it is off. */
@Composable
private fun LookSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean, lookOn: Boolean) {
    if (lookOn) {
        CosmoSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
