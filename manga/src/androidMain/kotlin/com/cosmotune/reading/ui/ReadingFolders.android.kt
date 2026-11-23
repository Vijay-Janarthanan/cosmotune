package com.cosmotune.reading.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.reading.data.ReadingFolder
import com.cosmotune.reading.data.ReadingFolderFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal actual fun rememberReadingFolders(): ReadingFolderAccess {
    val context = LocalContext.current
    var waiting by remember { mutableStateOf<((ReadingFolder) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val done = waiting
        waiting = null
        if (uri != null && done != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Books" } }.getOrDefault("Books")
            done(ReadingFolder(uri.toString(), name))
        }
    }
    return remember(picker, context) {
        object : ReadingFolderAccess {
            override fun pick(onPicked: (ReadingFolder) -> Unit) { waiting = onPicked; picker.launch(null) }
            override suspend fun list(folder: ReadingFolder): List<ReadingFolderFile> = withContext(Dispatchers.IO) {
                val tree = Uri.parse(folder.location)
                val resolver = context.contentResolver
                val files = ArrayList<ReadingFolderFile>()
                val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to 0))
                while (pending.isNotEmpty() && files.size < 5_000) {
                    val (document, depth) = pending.removeFirst()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document)
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 8) pending += id to depth + 1; continue }
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            files += ReadingFolderFile(uri.toString(), name, cursor.getLong(3), cursor.getLong(4)) {
                                requireNotNull(resolver.openInputStream(uri)) { "This book file could not be opened." }
                            }
                        }
                    } ?: throw IllegalStateException("The folder “${folder.name}” can no longer be read. Add it again.")
                }
                files
            }
        }
    }
}
