package com.cosmotune.music.desktop

import com.cosmotune.listening.AutoBackup
import com.cosmotune.listening.ListeningStore
import com.cosmotune.ui.settings.cosmoLookSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Automatic backups on the desktop (Settings, Storage, Back up automatically): the listening backup (the same file as Listening
 * tools > Backup > Export: sessions, smart playlists, the release inbox) written into the chosen folder when one is due, looked
 * at when Cosmotune starts and every few hours while it runs. Only this job's own older files are removed, past the newest
 * [AutoBackup.KEEP].
 */
internal object DesktopAutoBackup {

    /** Runs for as long as the app does (a LaunchedEffect at the top of the window). */
    suspend fun keepUp() {
        while (true) {
            runCatching { backUpIfDue() }
            delay(CHECK_MS)
        }
    }

    /** Writes one when it is due; answers whether it did. */
    suspend fun backUpIfDue(now: Long = System.currentTimeMillis()): Boolean {
        val look = cosmoLookSettings()
        val folder = look.autoBackupFolder.value.takeIf { it.isNotBlank() }?.let(::File) ?: return false
        if (!AutoBackup.due(look.autoBackupDays.value, look.autoBackupLast(), now)) return false
        return backUp(folder, now).also { if (it) look.setAutoBackupLast(now) }
    }

    suspend fun backUp(folder: File, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            folder.mkdirs()
            val text = ListeningStore(com.cosmotune.desktop.desktopListeningFile()).export()
            val target = File(folder, AutoBackup.fileName(now))
            val partial = File(folder, target.name + ".part")
            partial.writeText(text, Charsets.UTF_8)
            if (target.exists()) target.delete()
            check(partial.renameTo(target))
            AutoBackup.toRemove(folder.list()?.toList().orEmpty()).forEach { File(folder, it).delete() }
            // The same file on the listener's WebDAV server too, when they asked for that; a failure there leaves this copy standing.
            val share = DesktopNetworkShares.webDav()
            if (com.cosmotune.settings.Extras.settings.backupToWebDav.value && share.configured) {
                com.cosmotune.music.data.webdav.WebDavBackup.upload(share.url, share.username, share.password, target.name, text.toByteArray(Charsets.UTF_8))
                    .onFailure { DesktopTrackLog.log("backup: the WebDAV copy failed: ${it.message}") }
            }
            true
        }.getOrDefault(false)
    }

    fun chooseFolder(current: String): File? = runCatching {
        val chooser = javax.swing.JFileChooser(current.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("user.home")))
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Folder for automatic backups"
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
        chooser.selectedFile
    }.getOrNull()

    private const val CHECK_MS = 3L * 60 * 60 * 1000
}
