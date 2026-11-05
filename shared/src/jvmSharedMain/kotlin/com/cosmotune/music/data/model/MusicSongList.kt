package com.cosmotune.music.data.model

import java.text.Normalizer
import java.util.Locale

enum class MusicListOrder { DEFAULT, REVERSED, TITLE, TITLE_DESC, ARTIST, ALBUM, DURATION, ADDED, ADDED_DESC }

/** Local view only: retains occurrence identity and never rewrites a provider's playlist. */
object MusicSongList {
    fun rows(songs: List<Song>, query: String = "", order: MusicListOrder = MusicListOrder.DEFAULT,
        downloadedOnly: Boolean = false, downloadedIds: Set<String> = emptySet()): List<IndexedValue<Song>> {
        val terms = key(query).split(' ').filter(String::isNotEmpty)
        val filtered = songs.withIndex().filter { (_, song) ->
            (!downloadedOnly || song.videoId in downloadedIds || !song.localPath.isNullOrBlank() || !song.localUri.isNullOrBlank()) &&
                (terms.isEmpty() || key("${song.title} ${song.artist} ${song.albumName.orEmpty()}").let { haystack -> terms.all(haystack::contains) })
        }
        return when (order) {
            MusicListOrder.DEFAULT -> filtered
            MusicListOrder.REVERSED -> filtered.asReversed()
            MusicListOrder.TITLE -> filtered.sortedBy { key(it.value.title) }
            MusicListOrder.TITLE_DESC -> filtered.sortedByDescending { key(it.value.title) }
            MusicListOrder.ARTIST -> filtered.sortedWith(compareBy<IndexedValue<Song>> { key(it.value.artist) }.thenBy { key(it.value.title) })
            MusicListOrder.ALBUM -> filtered.sortedWith(compareBy<IndexedValue<Song>> { key(it.value.albumName.orEmpty()) }.thenBy { it.index })
            MusicListOrder.DURATION -> filtered.sortedBy { it.value.durationMillis().takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE }
            MusicListOrder.ADDED -> filtered.sortedWith(compareBy<IndexedValue<Song>> { it.value.localDateAddedSeconds ?: Long.MAX_VALUE }.thenBy { it.index })
            MusicListOrder.ADDED_DESC -> filtered.sortedWith(compareByDescending<IndexedValue<Song>> { it.value.localDateAddedSeconds ?: Long.MIN_VALUE }.thenByDescending { it.index })
        }
    }

    fun occurrence(row: IndexedValue<Song>) = row.value.setVideoId?.let { "entry:$it" } ?: "${row.value.videoId}:${row.index}"

    /** Fold Latin accents, preserve Indic vowel signs, and allow words across title/credits/album. */
    internal fun key(value: String): String = buildString {
        var latin = false
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).codePoints().forEach { cp ->
            when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() ->
                    if (!latin) appendCodePoint(cp)
                else -> if (Character.isLetterOrDigit(cp)) {
                    appendCodePoint(cp)
                    latin = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
                } else { append(' '); latin = false }
            }
        }
    }.trim().replace(Regex("\\s+"), " ")
}
