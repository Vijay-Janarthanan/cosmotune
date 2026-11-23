package com.cosmotune.watch

import java.io.File

/** Watch's disk cache as files: its size, and trimming it to a limit, oldest first. */
object WatchCacheFiles {
    fun size(dir: File?): Long = dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    /** Deletes the least recently written files until the cache holds at most [limitBytes] (0 empties it). */
    fun trim(dir: File?, limitBytes: Long) {
        val files = dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limitBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
