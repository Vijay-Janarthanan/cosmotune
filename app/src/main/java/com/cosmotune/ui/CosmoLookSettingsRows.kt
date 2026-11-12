package com.cosmotune.ui

import com.cosmotune.music.sharedui.resources.cosmo_amoled
import com.cosmotune.music.sharedui.resources.cosmo_amoled_summary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.cosmotune.music.ui.screens.RowDivider
import com.cosmotune.music.ui.screens.SettingsRow
import org.jetbrains.compose.resources.stringResource

/**
 * The look's settings, drawn with the settings page's own rows so they sit in its Appearance group like their neighbours: "Colours
 * follow the cover", the AMOLED black, the colour intensity (Calm, Balanced, Rich, a segmented control stacked under its row), and
 * the entry to the "Preview the look" style sheet. The intensity is only live while the colours follow the cover.
 */
@Composable
internal fun CosmoLookSettingsRows() {
    val settings = rememberCosmoLookSettings()
    val coverColours by settings.coverColours.collectAsStateWithLifecycle()
    val intensity by settings.intensity.collectAsStateWithLifecycle()
    val amoled by settings.amoled.collectAsStateWithLifecycle()
    var previewOpen by remember { mutableStateOf(false) }
    val intensityLive = coverColours
    Column {
        SettingsRow(
            icon = Icons.Rounded.ColorLens,
            title = stringResource(SharedRes.string.cosmo_cover_colours),
            subtitle = stringResource(SharedRes.string.cosmo_cover_colours_summary),
            trailing = { CosmoSwitch(checked = coverColours, onCheckedChange = settings::setCoverColours) },
            onClick = { settings.setCoverColours(!coverColours) },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.DarkMode,
            title = stringResource(SharedRes.string.cosmo_amoled),
            subtitle = stringResource(SharedRes.string.cosmo_amoled_summary),
            trailing = { CosmoSwitch(checked = amoled, onCheckedChange = settings::setAmoled) },
            onClick = { settings.setAmoled(!amoled) },
        )
        RowDivider()
        Column {
            SettingsRow(
                icon = CosmoIcons.Equaliser,
                title = stringResource(SharedRes.string.cosmo_intensity),
                subtitle = stringResource(SharedRes.string.cosmo_intensity_summary),
                enabled = intensityLive,
            )
            // `.row2.stack`: under the row, in line with its text (70 dp from the start), the segments sharing the width.
            CosmoSettingsSegmented(
                options = listOf(
                    stringResource(SharedRes.string.cosmo_intensity_calm),
                    stringResource(SharedRes.string.cosmo_intensity_balanced),
                    stringResource(SharedRes.string.cosmo_intensity_rich),
                ),
                selectedIndex = intensity,
                onSelect = settings::setIntensity,
                modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 12.dp),
                enabled = intensityLive,
                compact = true,
            )
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
