package com.cosmotune.ui.watch

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

internal actual fun subtitleFontFamily(path: String): FontFamily? =
    runCatching { File(path).takeIf { it.isFile }?.let { FontFamily(Font(it)) } }.getOrNull()

@Composable internal actual fun rememberFontFilePicker(onPicked: (String, ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Font"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= 20 shl 20 }
            if (bytes != null) latest.value(name, bytes)
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("font/ttf", "font/otf", "font/collection", "application/x-font-ttf", "application/octet-stream", "*/*")) } }
}
