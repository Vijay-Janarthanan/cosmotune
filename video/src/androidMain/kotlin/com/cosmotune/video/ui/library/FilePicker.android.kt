package com.cosmotune.video.ui.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.importers.AndroidSqliteRows
import java.io.File
import java.io.FileNotFoundException

/**
 * The Android document picker (plan task Y-22): "open a document" for an import (filtered by the MIME types the row gives) and
 * "create a document" for the export, both through `rememberLauncherForActivityResult`, so the system picker returns to this screen
 * even after the activity was recreated meanwhile. A device with no document picker (none ships without one) shows nothing.
 */
actual class FilePickerLauncher internal constructor(
    private val openDocument: ManagedActivityResultLauncher<Array<String>, Uri?>,
    private val createDocument: ManagedActivityResultLauncher<CreateRequest, Uri?>,
) {
    actual fun open(mimeTypes: List<String>) {
        try {
            openDocument.launch(mimeTypes.ifEmpty { listOf(ANY) }.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            // no document picker on this device: nothing to show
        }
    }

    actual fun create(suggestedName: String, mime: String) {
        try {
            createDocument.launch(CreateRequest(suggestedName, mime))
        } catch (e: ActivityNotFoundException) {
            // no document picker on this device: nothing to show
        }
    }

    private companion object {
        const val ANY = "*/*"
    }
}

/** What "create a document" is asked for: the name it offers and the type of the file. */
internal data class CreateRequest(val name: String, val mime: String)

/** `ACTION_CREATE_DOCUMENT` with the type and the name taken per call (the library's `CreateDocument` fixes the type when it is built). */
private object CreateDocumentContract : ActivityResultContract<CreateRequest, Uri?>() {
    override fun createIntent(context: Context, input: CreateRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
actual fun rememberFilePicker(onOpened: (PickedFile) -> Unit, onCreated: (CreatedFile) -> Unit): FilePickerLauncher {
    val context = LocalContext.current.applicationContext
    val opened by rememberUpdatedState(onOpened)
    val created by rememberUpdatedState(onCreated)
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = describe(context, uri)
            opened(PickedFile(name, size, file = null) { context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()) })
        }
    }
    val createDocument = rememberLauncherForActivityResult(CreateDocumentContract) { uri ->
        if (uri != null) {
            val (name, _) = describe(context, uri)
            created(CreatedFile(name) { context.contentResolver.openOutputStream(uri, "w") ?: throw FileNotFoundException(uri.toString()) })
        }
    }
    return remember(openDocument, createDocument) { FilePickerLauncher(openDocument, createDocument) }
}

/** The document's name as the provider shows it (else the last part of its address) and its size when the provider knows it. */
private fun describe(context: Context, uri: Uri): Pair<String, Long?> {
    var name: String? = null
    var size: Long? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
    } catch (e: Exception) {
        // a provider that cannot describe the document: fall back to its address
    }
    return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "") to size
}

/** Android's import platform: the app's cache folder (`cacheDir/video-import`) and Android's own SQLite. */
@Composable
actual fun rememberImportPlatform(): ImportPlatform {
    val context = LocalContext.current.applicationContext
    return remember(context) { ImportPlatform(File(context.cacheDir, CACHE_FOLDER), ::AndroidSqliteRows) }
}

private const val CACHE_FOLDER = "video-import"
