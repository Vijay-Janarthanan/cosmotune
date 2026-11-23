package com.cosmotune.listening

import com.cosmotune.music.data.model.Song

/**
 * The listener's rules for the songs on their device (Gramophone's): songs shorter than [minSeconds] are hidden (voice notes, intros,
 * jingles), and so is everything in a [hiddenFolders] folder or any folder under it. A song whose length is not known is kept.
 */
data class LocalLibraryRules(val minSeconds: Int = 0, val hiddenFolders: Set<String> = emptySet()) {
    val active: Boolean get() = minSeconds > 0 || hiddenFolders.isNotEmpty()

    fun keeps(song: Song): Boolean {
        if (minSeconds > 0) {
            val seconds = lengthSeconds(song.durationText)
            if (seconds != null && seconds in 1 until minSeconds) return false
        }
        val folder = song.localPath?.let(::folderOf) ?: return true
        return hiddenFolders.none { hidden -> folder == hidden || folder.startsWith("$hidden/") }
    }

    fun apply(songs: List<Song>): List<Song> = if (active) songs.filter(::keeps) else songs

    companion object {
        /** The lengths the setting offers, in seconds; 0 is off. */
        val MIN_CHOICES = listOf(0, 30, 60, 120)

        /** The folder a file sits in, with forward slashes and no trailing one, so Windows and Android paths compare alike. */
        fun folderOf(path: String): String? = path.replace('\\', '/').substringBeforeLast('/', "").trimEnd('/').takeIf { it.isNotEmpty() }

        /** Every folder [songs] come from, sorted, for the settings list of folders to hide. */
        fun folders(songs: List<Song>): List<String> = songs.mapNotNull { it.localPath?.let(::folderOf) }.distinct().sorted()

        /** A folder as the settings show it: its last two parts ("Music/Voice notes"). */
        fun label(folder: String): String = folder.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/").ifEmpty { folder }

        /** "3:21" or "1:02:03" as seconds; null when there is no length. */
        fun lengthSeconds(text: String?): Int? {
            val parts = text?.trim()?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (parts.isEmpty() || parts.size > 3) return null
            return parts.fold(0) { total, part -> total * 60 + part }
        }
    }
}
