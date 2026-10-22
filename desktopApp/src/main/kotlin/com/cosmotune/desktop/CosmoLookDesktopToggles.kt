package com.cosmotune.desktop

import androidx.compose.foundation.layout.Box
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
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.settings.CosmoLookPreviewDialog
import com.cosmotune.ui.settings.CosmoSettingsInsets
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.CosmoSettingsSegmented
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_cover_colours
import com.cosmotune.music.sharedui.resources.cosmo_cover_colours_summary
import com.cosmotune.music.sharedui.resources.cosmo_intensity
import com.cosmotune.music.sharedui.resources.cosmo_intensity_balanced
import com.cosmotune.music.sharedui.resources.cosmo_intensity_calm
import com.cosmotune.music.sharedui.resources.cosmo_intensity_rich
import com.cosmotune.music.sharedui.resources.cosmo_intensity_summary
import com.cosmotune.music.sharedui.resources.cosmo_look_preview
import com.cosmotune.music.sharedui.resources.cosmo_new_look
import com.cosmotune.music.sharedui.resources.cosmo_new_look_summary
import org.jetbrains.compose.resources.stringResource

/**
 * The look switches, drawn by the settings page's own private row ([row] is its SettingsToggle), the colour intensity (a row with
 * its segmented control stacked under it, while the look is on; it follows "Colours follow the cover"), and the entry to the look
 * preview.
 */
@Composable
internal fun CosmoLookDesktopToggles(row: @Composable (String, String, Boolean, (Boolean) -> Unit) -> Unit) {
    val settings = rememberCosmoLookSettings()
    val newLook by settings.newLook.collectAsState()
    val coverColours by settings.coverColours.collectAsState()
    val intensity by settings.intensity.collectAsState()
    row(stringResource(Res.string.cosmo_new_look), stringResource(Res.string.cosmo_new_look_summary), newLook, settings::setNewLook)
    if (newLook) {
        row(stringResource(Res.string.cosmo_cover_colours), stringResource(Res.string.cosmo_cover_colours_summary), coverColours, settings::setCoverColours)
        CosmoSettingsRow(
            icon = CosmoIcons.Equaliser,
            title = stringResource(Res.string.cosmo_intensity),
            subtitle = stringResource(Res.string.cosmo_intensity_summary),
            enabled = coverColours,
        )
        CosmoSettingsSegmented(
            options = listOf(
                stringResource(Res.string.cosmo_intensity_calm),
                stringResource(Res.string.cosmo_intensity_balanced),
                stringResource(Res.string.cosmo_intensity_rich),
            ),
            selectedIndex = intensity,
            onSelect = settings::setIntensity,
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 12.dp),
            enabled = coverColours,
            compact = true,
        )
    }
    var previewOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        CosmoButton(
            text = stringResource(Res.string.cosmo_look_preview),
            onClick = { previewOpen = true },
            kind = CosmoButtonKind.TONAL,
            size = CosmoButtonSize.SMALL,
        )
    }
    if (previewOpen) CosmoLookPreviewDialog(onDismiss = { previewOpen = false })
}
