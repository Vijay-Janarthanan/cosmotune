package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

/** VLC and mpv where they are installed (the usual folders, then the PATH), then the system's default player. */
@Composable internal actual fun rememberExternalPlayers(): List<ExternalPlayer> = remember {
    val windows = System.getProperty("os.name").startsWith("Windows", true)
    val mac = System.getProperty("os.name").startsWith("Mac", true)
    fun find(names: List<String>, fixed: List<String>): File? =
        fixed.map(::File).firstOrNull { it.isFile && it.canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile && it.canExecute() }
    val vlc = find(if (windows) listOf("vlc.exe") else listOf("vlc"),
        if (windows) listOf("C:/Program Files/VideoLAN/VLC/vlc.exe", "C:/Program Files (x86)/VideoLAN/VLC/vlc.exe") else if (mac) listOf("/Applications/VLC.app/Contents/MacOS/VLC") else emptyList())
    val mpv = find(if (windows) listOf("mpv.exe") else listOf("mpv"), if (mac) listOf("/opt/homebrew/bin/mpv", "/usr/local/bin/mpv") else emptyList())
    buildList {
        vlc?.let { exe -> add(ExternalPlayer("VLC") { stream, title ->
            val args = mutableListOf(exe.absolutePath, "--meta-title=$title")
            stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { args += "--http-user-agent=${it.value}" }
            stream.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { args += "--http-referrer=${it.value}" }
            ProcessBuilder(args + stream.url).start()
        }) }
        mpv?.let { exe -> add(ExternalPlayer("mpv") { stream, title ->
            val args = mutableListOf(exe.absolutePath, "--force-media-title=$title")
            if (stream.headers.isNotEmpty()) args += "--http-header-fields=" + stream.headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" }
            ProcessBuilder(args + stream.url).start()
        }) }
        if (java.awt.Desktop.isDesktopSupported()) add(ExternalPlayer("Default player") { stream, _ -> java.awt.Desktop.getDesktop().browse(java.net.URI(stream.url)) })
    }
}
