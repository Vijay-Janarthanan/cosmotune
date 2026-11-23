package com.cosmotune.reading.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cosmotune.music.data.model.Song

/** One file of an audiobook as the app's player takes it: [url] is an http(s) address, a file path or an Android document address. */
class ReadingAudioItem(val id: String, val title: String, val author: String, val url: String, val artwork: String?) {
    /** The player's song for it: played from [url] directly, named as an audiobook. */
    fun song(): Song {
        val path = url.takeIf { !it.contains("://") && !it.startsWith("content:") }
        return Song(videoId = id, title = title, artist = author, thumbnailUrl = artwork,
            localUri = if (path != null) java.io.File(path).toURI().toString() else url, localPath = path, albumName = "Read · Audiobook")
    }
}

/**
 * The app's player, lent to Read for audiobooks: they play there (in the background, on the lock screen, with Music's small
 * player), and Read follows what it plays. The host sets the fields below as the player changes and gives the ways to control it.
 */
class ReadingAudioHost(
    val play: (items: List<ReadingAudioItem>, index: Int, positionMs: Long) -> Unit,
    val toggle: () -> Unit,
    val seek: (Long) -> Unit,
    val speed: (Float) -> Unit,
) {
    var nowId by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var rate by mutableFloatStateOf(1f)

    /** Updates the fields from the player in one go. */
    fun update(nowId: String?, playing: Boolean, loading: Boolean, positionMs: Long, durationMs: Long, rate: Float) {
        this.nowId = nowId; this.playing = playing; this.loading = loading; this.positionMs = positionMs; this.durationMs = durationMs; this.rate = rate
    }

    companion object {
        /** The player's id for [bookId]'s track [index]. */
        fun itemId(bookId: String, index: Int) = "read:audio:$bookId:$index"
        /** The book and track an id of [itemId]'s names, or null for anything else the player holds. */
        fun parse(id: String?): Pair<String, Int>? {
            if (id == null || !id.startsWith("read:audio:")) return null
            val rest = id.removePrefix("read:audio:")
            return rest.substringBeforeLast(':') to (rest.substringAfterLast(':').toIntOrNull() ?: return null)
        }
    }
}

/** The player Read can use for audiobooks, from the host; null where there is none (Read then says listening needs the app). */
val LocalReadingAudio = staticCompositionLocalOf<ReadingAudioHost?> { null }
