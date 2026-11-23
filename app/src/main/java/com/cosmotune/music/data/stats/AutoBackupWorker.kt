package com.cosmotune.music.data.stats

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cosmotune.listening.AutoBackup
import com.cosmotune.ui.settings.cosmoLookSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Your data's automatic backups on the phone: while a schedule is on, a WorkManager job looks once a day whether one is due
 * ([AutoBackup.due]), writes the same file as Export data ([Backup.exportTo]) into the folder the listener granted, and removes
 * this job's own older files past the newest [AutoBackup.KEEP]. Nothing is scheduled while the setting is off.
 */
object AutoBackupScheduler {
    private const val DAILY = "cosmotune-auto-backup"

    fun init(context: Context) {
        val app = context.applicationContext
        val look = cosmoLookSettings(app)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            look.autoBackupDays.collect { days ->
                val work = WorkManager.getInstance(app)
                if (days <= 0) {
                    work.cancelUniqueWork(DAILY)
                } else {
                    val daily = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
                    work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
                }
            }
        }
    }

    /** The folder's name as the listener sees it ("Backups" from `primary:Documents/Backups`), or null for none. */
    fun folderLabel(tree: String): String? = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':').substringAfterLast('/').ifBlank { "Internal storage" }
    }.getOrNull()

    /** Writes a backup now into [tree] and prunes the old ones; answers whether it was written. */
    suspend fun backUp(context: Context, tree: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val treeUri = Uri.parse(tree)
        val folder = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val resolver = context.contentResolver
        val name = AutoBackup.fileName(nowMs)
        val existing = children(context, treeUri)
        existing[name]?.let { DocumentsContract.deleteDocument(resolver, it) }
        val target = DocumentsContract.createDocument(resolver, folder, "application/json", name) ?: return false
        if (Backup.exportTo(context, target).isFailure) return false
        val after = children(context, treeUri)
        AutoBackup.toRemove(after.keys.toList()).forEach { old -> after[old]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } } }
        copyToWebDav(context, target, name)
        return true
    }

    /** The same file on the listener's WebDAV server too, when they asked for that; a failure there leaves the folder's copy standing. */
    private suspend fun copyToWebDav(context: Context, written: Uri, name: String) {
        if (runCatching { com.cosmotune.settings.Extras.settings.backupToWebDav.value }.getOrDefault(false) != true) return
        val settings = com.cosmotune.music.data.settings.AppSettings
        val url = settings.webdavUrl.value
        if (!com.cosmotune.music.data.webdav.WebDavConfig.isConfigured(url)) return
        val bytes = runCatching { context.contentResolver.openInputStream(written)?.use { it.readBytes() } }.getOrNull() ?: return
        com.cosmotune.music.data.webdav.WebDavBackup.upload(url, settings.webdavUsername.value, settings.webdavPassword.value, name, bytes)
    }

    private fun children(context: Context, tree: Uri): Map<String, Uri> {
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = LinkedHashMap<String, Uri>()
        context.contentResolver.query(listing, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    found[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        return found
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val look = cosmoLookSettings(applicationContext)
        val days = look.autoBackupDays.value
        val tree = look.autoBackupFolder.value
        val now = System.currentTimeMillis()
        if (tree.isBlank() || !AutoBackup.due(days, look.autoBackupLast(), now)) return Result.success()
        val written = runCatching { AutoBackupScheduler.backUp(applicationContext, tree, now) }.getOrDefault(false)
        if (written) look.setAutoBackupLast(now)
        return if (written) Result.success() else Result.retry()
    }
}
