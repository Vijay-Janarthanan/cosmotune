package com.cosmotune.manga.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.data.platformMangaFileProtection
import java.io.File
import java.io.InputStream
import android.provider.DocumentsContract
import androidx.compose.runtime.rememberCoroutineScope
import com.cosmotune.manga.data.MangaFolderImport
import com.cosmotune.manga.data.MangaImageImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal actual fun rememberMangaRepository(): Result<MangaRepository> {
    val context = LocalContext.current.applicationContext
    return remember(context) { runCatching {
        val store = MangaStore(File(context.filesDir, "manga"), platformMangaFileProtection())
        MangaRepository(store) { config, parser -> com.cosmotune.manga.extensions.MangaExtensions(context, store, parser).source(config) }
    } }
}
@Composable internal actual fun rememberMangaImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Comic.cbz"
            latest.value(name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this document" } }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) } }
}
@Composable internal actual fun MangaBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable internal actual fun rememberMangaFolderImport(onPicked: (Result<MangaFolderImport>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val resolver = context.contentResolver
                val documentId = DocumentsContract.getTreeDocumentId(tree)
                val images = ArrayList<MangaImageImport>()
                val folders = ArrayList<Pair<String, String>>()
                // A series folder holds its pages, or one subfolder per chapter (one level).
                fun scan(parentId: String, chapter: String) {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1)
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (chapter.isEmpty() && folders.size <= 1_000) folders += cursor.getString(0) to name
                            } else if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif", "gif")) {
                                require(images.size < 10_000) { "Folder contains too many images" }
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                                images += MangaImageImport(name, chapter) { requireNotNull(resolver.openInputStream(uri)) }
                            }
                        }
                    }
                }
                scan(documentId, "")
                if (images.isEmpty()) folders.forEach { (id, name) -> scan(id, name) }
                MangaFolderImport(documentId.substringAfterLast('/').substringAfterLast(':'), images)
            } }
            latest.value(result)
        }
    }
    return remember(launcher) { { launcher.launch(null) } }
}
