package com.cosmotune.manga.data

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** An image the listener blanked out in the reader (an ad, a logo, a banner): the series it was first seen in, and when. */
@Serializable
data class MangaHiddenImage(val series: String, val hiddenAtMs: Long)

/**
 * Kotatsu's erase: a page image hidden once stays blank wherever the same image comes back (the same bytes under any name), until it
 * is shown again from Settings › Manga › Hidden images.
 */
object MangaHiddenImages {
    const val LIMIT = 500

    fun mark(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hide(state: MangaLibraryState, mark: String, series: String, nowMs: Long): MangaLibraryState {
        val next = state.hiddenImages + (mark to MangaHiddenImage(series.take(200), nowMs))
        val kept = if (next.size <= LIMIT) next else next.entries.sortedByDescending { it.value.hiddenAtMs }.take(LIMIT).associate { it.key to it.value }
        return state.copy(hiddenImages = kept)
    }

    fun show(state: MangaLibraryState, mark: String): MangaLibraryState = state.copy(hiddenImages = state.hiddenImages - mark)
}
