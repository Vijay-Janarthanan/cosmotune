package com.cosmotune.music.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the desktop saves downloaded songs and episodes (Settings > Downloads > Download folder): the listener's own folder, on
 * any drive, or Music/Cosmotune and Podcasts/Cosmotune in the home folder when none is chosen. Songs go straight into the chosen
 * folder and episodes into a Podcasts folder inside it.
 */
internal object DesktopDownloadFolder {
    private val persistence = DesktopPersistence()

    /** The chosen folder, or null for the usual ones. */
    fun chosen(): Path? = persistence.string(KEY, "").takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

    fun set(folder: Path?) = persistence.saveString(KEY, folder?.toAbsolutePath()?.toString().orEmpty())

    /** The folder a download goes into. */
    fun forDownload(podcast: Boolean): Path {
        val base = chosen()?.takeIf { Files.isDirectory(it) }
        return when {
            base != null && podcast -> base.resolve("Podcasts")
            base != null -> base
            else -> Path.of(System.getProperty("user.home"), if (podcast) "Podcasts" else "Music", com.cosmotune.CosmoConfig.APP_NAME)
        }
    }

    /** How the settings row names the folder. */
    fun label(): String = chosen()?.toString() ?: Path.of(System.getProperty("user.home"), "Music", com.cosmotune.CosmoConfig.APP_NAME).toString()

    /** Asks for a folder with the system's chooser; null when the listener cancels. */
    fun choose(): Path? = runCatching {
        val chooser = javax.swing.JFileChooser((chosen() ?: Path.of(System.getProperty("user.home"))).toFile())
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Download folder"
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
        chooser.selectedFile?.toPath()
    }.getOrNull()

    private const val KEY = "music_download_folder"
}
