package com.cosmotune.video.alerts

import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.library.AutoBackup
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Where automatic backups go: the desktop's Documents folder, the phone's Downloads (see the platforms' own folder). */
interface BackupFolder {
    fun list(): List<String>
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/** A plain folder on disk (the desktop's `Documents/Cosmotune backups`). */
class FileBackupFolder(private val dir: File) : BackupFolder {
    override fun list(): List<String> = dir.list()?.toList().orEmpty()

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val part = File(dir, "$name.part")
        part.writeBytes(bytes)
        val target = File(dir, name)
        if (!part.renameTo(target)) {
            target.delete()
            part.renameTo(target)
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    companion object {
        /** `~/Documents/Cosmotune backups`. */
        fun desktop(): FileBackupFolder = FileBackupFolder(File(File(System.getProperty("user.home"), "Documents"), "Cosmotune backups"))
    }
}

/**
 * Writes the library to [folder] when the listener's automatic backup is due, keeps the newest [AutoBackup.KEEP] backups there and
 * notes the time. True when a backup was written. A failure is left for the next check.
 */
suspend fun runAutoBackupIfDue(graph: VideoGraph, folder: BackupFolder, nowMs: Long = System.currentTimeMillis()): Boolean {
    val settings = graph.settings.settings.value
    if (!AutoBackup.isDue(settings.autoBackupDays, settings.autoBackupLastMs, nowMs)) return false
    return try {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        folder.write(AutoBackup.fileName(today), graph.library.exportJson().encodeToByteArray())
        AutoBackup.toDelete(folder.list()).forEach { runCatching { folder.delete(it) } }
        graph.settings.update { it.copy(autoBackupLastMs = nowMs) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }
}
