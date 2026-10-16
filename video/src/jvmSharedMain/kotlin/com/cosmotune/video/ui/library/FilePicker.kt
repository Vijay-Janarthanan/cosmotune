package com.cosmotune.video.ui.library

import androidx.compose.runtime.Composable
import com.cosmotune.video.importers.SqliteRows
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A document the person picked to import (spec 8.2): its [name] as the platform shows it, its [size] in bytes when the platform
 * knows it, the [file] itself when the document is a plain file (the desktop), and [open], which gives a fresh stream from the start
 * of the document each time it is called (the caller closes it). Nothing is read until [open] is called, so a page reads off the main
 * thread.
 */
class PickedFile(val name: String, val size: Long?, val file: File?, val open: () -> InputStream)

/** A document the person chose to save to (spec 8.1 export): its [name] as the platform shows it, and [open] to write it (the caller closes the stream). */
class CreatedFile(val name: String, val open: () -> OutputStream)

/**
 * Opens and creates documents through the platform's own picker (plan task Y-22): the Android document picker
 * (`rememberLauncherForActivityResult`: open a document, create a document) and the desktop's `java.awt.FileDialog`.
 *
 * - [open] asks for a document of one of [mimeTypes] (Android filters by them; the desktop by the file extensions they name, and
 *   shows every file when they name none it knows). The picked document comes back through `onOpened` of [rememberFilePicker].
 * - [create] asks where to save a new document, offering [suggestedName] of type [mime]; the chosen place comes back through
 *   `onCreated`, and the page writes it.
 *
 * Cancelling either picker calls nothing. This is the brief's shape with the two callbacks' arguments named: `onOpened(name, bytes)`
 * is [PickedFile] (`name`, `open`), `onCreated(OutputStream.() -> Unit)` is [CreatedFile] (`open` gives the stream to write).
 */
expect class FilePickerLauncher {
    /** Shows the picker for a document of one of [mimeTypes]. */
    fun open(mimeTypes: List<String>)

    /** Shows the picker that saves a new document named [suggestedName], of type [mime]. */
    fun create(suggestedName: String, mime: String)
}

/** The file picker of the screen being drawn; [onOpened] and [onCreated] are called on the main thread, each time a picker returns a document. */
@Composable
expect fun rememberFilePicker(onOpened: (PickedFile) -> Unit, onCreated: (CreatedFile) -> Unit): FilePickerLauncher

/**
 * What an import needs of the platform besides the picker (spec 8.2): [cacheDir], the app's cache folder, where a picked archive is
 * copied for random access and the NewPipe database is extracted (both deleted when the import has read them), and [sqlite], the
 * platform's SQLite reader (`AndroidSqliteRows` on Android, `JdbcSqliteRows` on the desktop).
 */
class ImportPlatform(val cacheDir: File, val sqlite: (path: String) -> SqliteRows)

/** The import platform of the screen being drawn. */
@Composable
expect fun rememberImportPlatform(): ImportPlatform
