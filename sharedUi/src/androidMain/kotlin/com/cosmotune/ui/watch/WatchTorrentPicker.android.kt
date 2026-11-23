package com.cosmotune.ui.watch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable internal actual fun rememberTorrentFilePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= 4 shl 20 }
        }.getOrNull()?.let { latest.value(it) }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) } }
}
