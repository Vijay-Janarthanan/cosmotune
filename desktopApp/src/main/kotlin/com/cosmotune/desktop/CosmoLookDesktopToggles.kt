package com.cosmotune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.settings.CosmoLookPreviewDialog
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_cover_colours
import com.music.bitchord.sharedui.resources.cosmo_cover_colours_summary
import com.music.bitchord.sharedui.resources.cosmo_look_preview
import com.music.bitchord.sharedui.resources.cosmo_new_look
import com.music.bitchord.sharedui.resources.cosmo_new_look_summary
import org.jetbrains.compose.resources.stringResource

/** The look switches, drawn by the settings page's own private row ([row] is its SettingsToggle), and the entry to the look preview. */
@Composable
internal fun CosmoLookDesktopToggles(row: @Composable (String, String, Boolean, (Boolean) -> Unit) -> Unit) {
    val settings = rememberCosmoLookSettings()
    val newLook by settings.newLook.collectAsState()
    val coverColours by settings.coverColours.collectAsState()
    row(stringResource(Res.string.cosmo_new_look), stringResource(Res.string.cosmo_new_look_summary), newLook, settings::setNewLook)
    if (newLook) {
        row(stringResource(Res.string.cosmo_cover_colours), stringResource(Res.string.cosmo_cover_colours_summary), coverColours, settings::setCoverColours)
    }
    var previewOpen by remember { mutableStateOf(false) }
    // A Material button until task P3-6 lands: then it is CosmoButton(kind = TONAL, size = SMALL).
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        FilledTonalButton(onClick = { previewOpen = true }) { Text(stringResource(Res.string.cosmo_look_preview)) }
    }
    if (previewOpen) CosmoLookPreviewDialog(onDismiss = { previewOpen = false })
}
