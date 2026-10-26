package com.cosmotune.ui.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal actual fun rememberProviderAuthOpen(manager: com.cosmotune.providers.ProviderManager,
    installed: com.cosmotune.providers.ProviderInstall, onFailure: () -> Unit): (String) -> Unit {
    val latest = rememberUpdatedState(onFailure)
    val window = remember(manager, installed.manifest.name) { java.util.concurrent.atomic.AtomicReference<AutoCloseable?>() }
    androidx.compose.runtime.DisposableEffect(manager, installed.manifest.name) { onDispose { window.getAndSet(null)?.close() } }
    return remember(manager, installed.manifest.name) { { url ->
        require(url.startsWith("https://"))
        val embedded = com.cosmotune.providers.ProviderVerificationWindows.launcher
        if (embedded != null) {
            val target = manager.verificationTarget(installed.manifest.name)
            require(target.url == url)
            window.getAndSet(null)?.close()
            window.set(embedded(manager, installed.manifest.name, target) { latest.value() })
        } else {
            check(java.awt.Desktop.isDesktopSupported()) { "No browser is available" }
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } }
}

@Composable internal actual fun rememberProviderPackageImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    return remember(scope) { { scope.launch {
        val file = withContext(Dispatchers.IO) {
            var selected: File? = null
            SwingUtilities.invokeAndWait {
                val picker = FileDialog(null as Frame?, "Import provider package", FileDialog.LOAD)
                try {
                    picker.setFilenameFilter { _, name -> name.endsWith(".sflx", true) || name.endsWith(".spotiflac-ext", true) || name.endsWith(".zip", true) }
                    picker.isVisible = true
                    picker.file?.let { selected = File(picker.directory, it) }
                } finally { picker.dispose() }
            }
            selected
        }
        if (file != null) latest.value(file.name) { file.inputStream() }
    }; Unit } }
}
