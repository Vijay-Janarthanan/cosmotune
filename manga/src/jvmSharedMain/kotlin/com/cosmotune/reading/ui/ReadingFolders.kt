package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable
import com.cosmotune.reading.data.ReadingFolder
import com.cosmotune.reading.data.ReadingFolderFile

/** A folder of books on this device: picking one (kept across restarts) and listing the book files in it and its subfolders. */
internal interface ReadingFolderAccess {
    /** Opens the system's folder picker; [onPicked] hears the folder chosen. */
    fun pick(onPicked: (ReadingFolder) -> Unit)
    /** The files under [folder], or an error when it can no longer be read (moved, or the permission withdrawn). */
    suspend fun list(folder: ReadingFolder): List<ReadingFolderFile>
}

@Composable internal expect fun rememberReadingFolders(): ReadingFolderAccess
