package com.cosmotune.video.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cosmotune.video.player.ExternalStream
import java.awt.Desktop
import java.io.File
import java.net.URI

/** The desktop: VLC or mpv when one is installed (on the PATH or in its usual folder), else the default app for the address. */
@Composable
internal actual fun rememberExternalPlayer(): ExternalPlayer = remember { DesktopExternalPlayer }

private object DesktopExternalPlayer : ExternalPlayer {
    override fun open(stream: ExternalStream, title: String): Boolean {
        val player = findPlayer()
        if (player != null) {
            val args = when {
                player.name.startsWith("vlc", ignoreCase = true) -> listOf(player.path, "--meta-title=$title", stream.url)
                else -> listOf(player.path, "--force-media-title=$title", stream.url)
            }
            if (runCatching { ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.isSuccess) return true
        }
        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(stream.url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun findPlayer(): File? {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val names = if (windows) listOf("vlc.exe", "mpv.exe") else listOf("vlc", "mpv")
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        for (name in names) path.map { File(it, name) }.firstOrNull { it.canExecute() }?.let { return it }
        val known = if (windows) {
            listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")).flatMap {
                listOf(File(it, "VideoLAN\\VLC\\vlc.exe"), File(it, "mpv\\mpv.exe"))
            }
        } else {
            listOf(File("/Applications/VLC.app/Contents/MacOS/VLC"), File("/usr/bin/vlc"), File("/usr/bin/mpv"), File("/opt/homebrew/bin/mpv"))
        }
        return known.firstOrNull { it.isFile }
    }
}
