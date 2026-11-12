package com.cosmotune.desktop.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.desktop.DesktopSingleInstance
import com.cosmotune.music.desktop.DesktopExtras
import com.cosmotune.music.desktop.DesktopExternalLinks
import com.cosmotune.music.desktop.DesktopQuit
import com.cosmotune.music.desktop.DesktopTrackLog
import com.cosmotune.music.desktop.DesktopUpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File

/**
 * The app's one [DesktopUpdater]: this copy's kind of install, GitHub's latest release, a streamed download into the state folder's
 * `updates`, and the hand-over to the platform's script ([WindowsUpdateScript], [MacUpdateScript]) followed by quitting the way the
 * tray's Quit does. A failed install left by the previous run's script is read once, at start.
 */
internal object DesktopUpdates {
    private val stateDir: File by lazy { DesktopSingleInstance.stateDirectory().toFile() }
    private val updatesDir: File get() = File(stateDir, "updates")
    private val resultFile: File get() = File(stateDir, "update-result.txt")

    private val http by lazy {
        HttpClient(CIO) {
            engine { DesktopExtras.httpProxyUrl?.let { proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(it)) } }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    val updater: DesktopUpdater by lazy {
        DesktopUpdater(
            check = { DesktopUpdateChecker.check() },
            install = DesktopInstall.detect(System.getProperty("jpackage.app-path"), System.getProperty("os.name").orEmpty()),
            osArch = System.getProperty("os.arch").orEmpty(),
            fetch = ::fetch,
            updatesDir = updatesDir,
            openPage = { DesktopExternalLinks.open(it) },
            handOver = ::handOver,
        ).also { u -> UpdateResult.take(resultFile, DesktopUpdateChecker.currentVersion)?.let(u::reportFailure) }
    }

    private suspend fun fetch(url: String, into: File, onProgress: (Float) -> Unit) {
        http.prepareGet(url).execute { response ->
            check(response.status.isSuccess()) { "The download stopped (HTTP ${response.status.value}). Try again in a moment." }
            val total = response.contentLength()?.takeIf { it > 0 }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            into.outputStream().use { out ->
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) { if (channel.isClosedForRead) break else continue }
                    out.write(buffer, 0, read)
                    done += read
                    onProgress(total?.let { (done.toFloat() / it).coerceIn(0f, 1f) } ?: -1f)
                }
            }
            if (total != null) check(done == total) { "The connection dropped. Check your internet and try again." }
        }
    }

    private fun handOver(pkg: File, info: DesktopUpdateChecker.UpdateInfo) {
        val pid = ProcessHandle.current().pid()
        val log = File(updatesDir, "install.log")
        when (val install = updater.install) {
            is DesktopInstall.WindowsInstalled -> WindowsUpdateScript.launch(WindowsUpdateScript.text(pid, pkg, install, resultFile, log), updatesDir)
            is DesktopInstall.MacBundle -> MacUpdateScript.launch(MacUpdateScript.text(pid, pkg, install, resultFile, log), updatesDir)
            else -> error("This copy of Cosmotune updates through the download page")
        }
        DesktopTrackLog.log("update: handing over to install ${info.version}")
        DesktopQuit.now("Installing the ${info.version} update")
    }
}

/** The top bar's way back to an update that was put off; nothing unless there is one. */
@Composable
internal fun DesktopUpdatePill(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val updater = DesktopUpdates.updater
    val offer by updater.offer.collectAsState()
    val postponed by updater.postponed.collectAsState()
    if (offer != null && postponed) UpdatePill(onClick = updater::reopen, modifier = modifier)
}
