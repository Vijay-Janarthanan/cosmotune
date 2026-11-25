package com.cosmotune.video.ui.local

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.local.LocalVideo
import java.io.File

/**
 * The phone's folders. A folder the person picked is a document tree (`OpenDocumentTree`): its right to be read is kept with
 * `takePersistableUriPermission`, and it and every folder and file in it are document addresses under that tree, listed through the
 * documents provider (the player and the pictures read them as `content:` files). A plain path is listed like the desktop's.
 */
private class AndroidLocalFolderAccess(private val context: Context) : LocalFolderAccess {
    private val thumbs = File(context.cacheDir, "local-thumbs").apply { mkdirs() }

    override fun list(folder: String): List<FolderEntry> = runCatching {
        if (folder.startsWith("content:")) listTree(Uri.parse(folder)) else listPath(File(folder))
    }.getOrDefault(emptyList())

    private fun listTree(folder: Uri): List<FolderEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val found = ArrayList<FolderEntry>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = cursor.getString(2).orEmpty()
                val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val isFolder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (!isFolder && !mime.startsWith("video/") && !LocalVideo.isVideoName(name)) continue
                val source = DocumentsContract.buildDocumentUriUsingTree(folder, id).toString()
                found += FolderEntry(source, name, isFolder, modified)
            }
        }
        return found
    }

    private fun listPath(folder: File): List<FolderEntry> = folder.listFiles().orEmpty()
        .filter { !it.name.startsWith(".") && (it.isDirectory || LocalVideo.isVideoName(it.name)) }
        .map { FolderEntry(it.path, it.name, it.isDirectory, it.lastModified()) }

    override fun thumbnail(video: String, widthPx: Int): ImageBitmap? {
        val cached = File(thumbs, thumbFileName(video, modifiedOf(video)))
        if (cached.isFile) BitmapFactory.decodeFile(cached.path)?.let { return it.asImageBitmap() }
        val retriever = MediaMetadataRetriever()
        return try {
            if (video.startsWith("content:")) retriever.setDataSource(context, Uri.parse(video)) else retriever.setDataSource(video)
            val frame = retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime ?: return null
            val scaled = if (frame.width <= widthPx) frame else Bitmap.createScaledBitmap(frame, widthPx, frame.height * widthPx / frame.width, true)
            runCatching { cached.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
            scaled.asImageBitmap()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** When the file last changed, so a new version gets a new picture; 0 when it cannot be told. */
    private fun modifiedOf(video: String): Long = runCatching {
        if (!video.startsWith("content:")) return@runCatching File(video).lastModified()
        context.contentResolver.query(Uri.parse(video), arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private companion object {
        /** A second in: past a fade from black at the start. */
        const val FRAME_AT_US = 1_000_000L
    }
}

@Composable
actual fun rememberLocalFolderAccess(): LocalFolderAccess {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidLocalFolderAccess(context) }
}

@Composable
actual fun rememberLocalFolderStore(): LocalFolderStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { LocalFolderStores.of(videoLibraryDir(LibraryPlatform.Android(context.filesDir))) }
}

@Composable
actual fun rememberFolderPicker(onFolder: (PickedVideo) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val chosen by rememberUpdatedState(onFolder)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            chosen(PickedVideo(folder.toString(), folderName(context, folder, treeId)))
        }
    }
    return remember(launcher) { { runCatching { launcher.launch(null) } } }
}

/** The folder's name as the provider shows it, else the last part of its id ("primary:Download/insta" is "insta"). */
private fun folderName(context: Context, folder: Uri, treeId: String): String {
    val shown = runCatching {
        context.contentResolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return shown?.takeIf { it.isNotBlank() } ?: treeId.substringAfterLast('/').substringAfterLast(':').ifBlank { treeId }
}
