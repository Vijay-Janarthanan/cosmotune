package com.cosmotune.video.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.cosmotune.video.importers.JdbcSqliteRows
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The desktop's file picker (plan task Y-22): the system's own dialog through `java.awt.FileDialog`, modal on the window that has the
 * focus. Opening filters by the file extensions the MIME types name (`json`, `zip`, `csv`, `opml`, `xml`), and shows every file when
 * they name none of these; saving offers the suggested name. The picked file is handed over as a file, so an archive is read in place.
 */
actual class FilePickerLauncher internal constructor(
    private val opened: State<(PickedFile) -> Unit>,
    private val created: State<(CreatedFile) -> Unit>,
) {
    actual fun open(mimeTypes: List<String>) {
        val dialog = dialog(FileDialog.LOAD)
        val extensions = extensionsOf(mimeTypes)
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
            // Windows' dialog ignores the filter above and reads a pattern in the name field instead
            if (IS_WINDOWS) dialog.file = extensions.joinToString(";") { "*.$it" }
        }
        dialog.isVisible = true
        val name = dialog.file ?: return
        val file = File(dialog.directory.orEmpty(), name)
        if (!file.isFile) return
        opened.value(PickedFile(file.name, file.length(), file) { FileInputStream(file) })
    }

    actual fun create(suggestedName: String, mime: String) {
        val dialog = dialog(FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return
        val file = File(dialog.directory.orEmpty(), name)
        created.value(CreatedFile(file.name) { FileOutputStream(file) })
    }

    private fun dialog(mode: Int): FileDialog = when (val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow) {
        is Frame -> FileDialog(owner, "", mode)
        is Dialog -> FileDialog(owner, "", mode)
        else -> FileDialog(null as Frame?, "", mode)
    }

    internal companion object {
        private val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        /** The extensions the specific [mimeTypes] name; the generic ones (any, any text, raw bytes) name none. */
        fun extensionsOf(mimeTypes: List<String>): Set<String> = mimeTypes.flatMapTo(LinkedHashSet()) { mime ->
            when (mime.lowercase()) {
                "application/json" -> listOf("json")
                "application/zip", "application/x-zip-compressed" -> listOf("zip")
                "text/csv", "text/comma-separated-values" -> listOf("csv")
                "text/x-opml" -> listOf("opml")
                "text/xml", "application/xml" -> listOf("xml", "opml")
                else -> emptyList()
            }
        }
    }
}

@Composable
actual fun rememberFilePicker(onOpened: (PickedFile) -> Unit, onCreated: (CreatedFile) -> Unit): FilePickerLauncher {
    val opened = rememberUpdatedState(onOpened)
    val created = rememberUpdatedState(onCreated)
    return remember { FilePickerLauncher(opened, created) }
}

/** The desktop's import platform: a folder of the system's temporary files, and `sqlite-jdbc` (which the desktop already ships). */
@Composable
actual fun rememberImportPlatform(): ImportPlatform = remember {
    ImportPlatform(File(System.getProperty("java.io.tmpdir"), CACHE_FOLDER), ::JdbcSqliteRows)
}

private const val CACHE_FOLDER = "cosmotune-video-import"
