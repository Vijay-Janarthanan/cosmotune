package com.cosmotune.ui.watch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable internal actual fun rememberTextFileSaver(): (name: String, text: String, onDone: (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val waiting = remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val (text, done) = waiting.value ?: return@rememberLauncherForActivityResult
        waiting.value = null
        done(uri != null && runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
        }.getOrDefault(false))
    }
    return remember(launcher) { { name, text, done -> waiting.value = text to done; launcher.launch(name) } }
}

@Composable internal actual fun rememberTextFileOpener(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 32 shl 20) return@use
                    out.write(buffer, 0, n)
                }
                latest.value(out.toString("UTF-8"))
            }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) } }
}
