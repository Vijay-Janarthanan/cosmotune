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

    private val http by lazy { UpdateDownload.client(DesktopExtras.httpProxyUrl) }

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

    private suspend fun fetch(url: String, into: File, onProgress: (Float) -> Unit) = UpdateDownload.fetch(http, url, into, onProgress)

    private fun handOver(pkg: File, info: DesktopUpdateChecker.UpdateInfo) {
        val pid = ProcessHandle.current().pid()
        val log = File(updatesDir, "install.log")
        when (val install = updater.install) {
            is DesktopInstall.WindowsInstalled -> {
                // Each update leaves what each step did in update-steps.log, beside the installer's own log.
                val steps = File(updatesDir, "update-steps.log").apply { delete() }
                WindowsUpdateScript.launch(WindowsUpdateScript.text(pid, pkg, install, resultFile, log, steps), updatesDir)
            }
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
