package com.cosmotune.video.downloads

import com.cosmotune.video.player.LocalBundle
import java.io.File

/**
 * [toLocalBundle] with the sidecar read from beside the download (Android and desktop alike): a missing or unreadable file gives
 * the files alone. Reads a small file: call it off the main thread. Null until the entry is done.
 */
fun DownloadEntry.readLocalBundle(): LocalBundle? {
    val sidecar = sidecarPath()?.let { path -> runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull() }
    return toLocalBundle(sidecar)
}
