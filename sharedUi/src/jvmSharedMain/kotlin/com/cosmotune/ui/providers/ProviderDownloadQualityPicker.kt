package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.downloads.ProviderDownloadChoice
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.SourceTrackKeys
import com.cosmotune.providers.ProviderHub
import com.cosmotune.providers.ProviderManifest
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.components.CosmoIcons

/** Returns an interceptor: true means this request is being reviewed in the shared Orbit picker. */
@Composable
fun rememberProviderDownloadQualityPicker(onDownload: (Song, ProviderDownloadChoice?) -> Unit): (Song) -> Boolean {
    var requested by remember { mutableStateOf<Pair<Song, ProviderManifest>?>(null) }
    val currentDownload by rememberUpdatedState(onDownload)
    requested?.let { (song, manifest) ->
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { requested = null }, title = "Download quality · ${manifest.title}",
            content = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CosmoActionRow(CosmoIcons.Download, "Use download settings", menu = true,
                        onClick = { requested = null; currentDownload(song, null) })
                    manifest.qualityOptions.forEach { option ->
                        CosmoActionRow(CosmoIcons.Download, option.label.ifBlank { option.id }, menu = true,
                            onClick = { requested = null; currentDownload(song, ProviderDownloadChoice(manifest.name, option.id)) })
                    }
                }
            }, confirmLabel = "Cancel", onConfirm = { requested = null },
        )
    }
    return remember {
        { song: Song ->
            val provider = SourceTrackKeys.parse(song.videoId)?.first?.takeIf { it.startsWith("sflx-") }?.removePrefix("sflx-")
            val installed = ProviderHub.state.value.manager?.store?.state?.value?.installed?.find { it.enabled && it.manifest.name == provider }
            if (installed != null && installed.manifest.qualityOptions.isNotEmpty()) {
                requested = song to installed.manifest
                true
            } else false
        }
    }
}
