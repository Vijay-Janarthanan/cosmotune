package com.cosmotune.ui.providers

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream

@Composable internal actual fun rememberProviderAuthOpen(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { url ->
        require(url.startsWith("https://"))
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } }
}

@Composable internal actual fun rememberProviderPackageImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Provider package"
            latest.value(name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open provider package" } }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } }
}
