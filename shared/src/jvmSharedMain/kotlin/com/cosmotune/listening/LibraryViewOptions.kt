package com.cosmotune.listening

import com.cosmotune.audio.LibraryAudioAnalysis
import com.cosmotune.music.data.model.Song

/** The extra columns a library song list can show beside title and artist. */
enum class LibraryColumn(val label: String) {
    ALBUM("Album"), DURATION("Length"), SOURCE("Source"), FORMAT("Format"), QUALITY("Quality"), LOUDNESS("Loudness"), GENRE("Genre"),
}

/** Where a library row's audio lives. */
enum class LibrarySource(val label: String) {
    DEVICE("This device"), NETWORK("Network share"), SERVER("Own server"), STREAM("Streaming");

    companion object {
        fun of(song: Song): LibrarySource {
            val id = song.videoId
            val uri = song.localUri.orEmpty()
            return when {
                id.startsWith("smb:") || uri.startsWith("smb://") || id.startsWith("webdav:") || uri.startsWith("http") -> NETWORK
                song.localPath != null || uri.startsWith("content:") || uri.startsWith("file:") ||
                    id.startsWith("file:") || id.startsWith("content:") || id.startsWith("local:") -> DEVICE
                id.startsWith("src:") && !id.startsWith("src:sflx-") -> SERVER
                else -> STREAM
            }
        }
    }
}

enum class LibraryQualityFilter(val label: String) {
    ANY("Any quality"), LOSSLESS("Lossless"), LOSSY("Lossy"), SUSPECT("Possible lossy source"), UNMEASURED("Not analysed"),
}

/**
 * The listener's column and filter choice for the main library song lists, on both hosts. Quality,
 * format, loudness and genre come from the local audio analysis (Library review → Analyse); rows
 * that were never analysed fall back to what the file name and library entry say.
 */
data class LibraryViewOptions(
    val columns: Set<LibraryColumn> = setOf(LibraryColumn.ALBUM, LibraryColumn.DURATION),
    val source: LibrarySource? = null,
    val quality: LibraryQualityFilter = LibraryQualityFilter.ANY,
    val genre: String? = null,
) {
    val filtering: Boolean get() = source != null || quality != LibraryQualityFilter.ANY || genre != null

    fun apply(songs: List<Song>, analysis: (Song) -> LibraryAudioAnalysis?): List<Song> = songs.filter { song ->
        val measured = analysis(song)
        (source == null || LibrarySource.of(song) == source) &&
            (genre == null || measured?.genre.equals(genre, ignoreCase = true)) &&
            when (quality) {
                LibraryQualityFilter.ANY -> true
                LibraryQualityFilter.LOSSLESS -> measured?.lossless == true
                LibraryQualityFilter.LOSSY -> measured != null && !measured.lossless
                LibraryQualityFilter.SUSPECT -> measured?.possibleLossySource == true
                LibraryQualityFilter.UNMEASURED -> measured == null
            }
    }

    /** The chosen columns' values for one row, in column order; columns with nothing to say are left out. */
    fun cells(song: Song, analysis: LibraryAudioAnalysis?): List<Pair<LibraryColumn, String>> = LibraryColumn.entries.filter { it in columns }.mapNotNull { column ->
        val value = when (column) {
            LibraryColumn.ALBUM -> song.albumName?.takeIf { it.isNotBlank() }
            LibraryColumn.DURATION -> song.durationText?.takeIf { it.isNotBlank() }
                ?: analysis?.durationSeconds?.takeIf { it > 0 }?.toInt()?.let { "%d:%02d".format(it / 60, it % 60) }
            LibraryColumn.SOURCE -> LibrarySource.of(song).label
            LibraryColumn.FORMAT -> analysis?.let { a ->
                listOfNotNull(a.codec?.uppercase(), a.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(java.util.Locale.ROOT, it / 1000.0) },
                    a.bitsPerSample?.let { "$it-bit" }).joinToString(" ").ifBlank { null }
            } ?: (song.localPath ?: song.localUri)?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.uppercase()
            LibraryColumn.QUALITY -> analysis?.let { a -> when { a.possibleLossySource -> "Possible lossy source"; a.lossless -> "Lossless"; else -> "Lossy" } }
            LibraryColumn.LOUDNESS -> analysis?.integratedLufs?.let { "%.1f LUFS".format(java.util.Locale.ROOT, it) }
            LibraryColumn.GENRE -> analysis?.genre
        }
        value?.let { column to it }
    }

    fun encode(): String = listOf(columns.joinToString(",") { it.name }, source?.name.orEmpty(), quality.name, genre.orEmpty().replace('|', ' ')).joinToString("|")

    companion object {
        fun decode(raw: String?): LibraryViewOptions {
            val parts = raw?.split('|') ?: return LibraryViewOptions()
            if (parts.size != 4) return LibraryViewOptions()
            return LibraryViewOptions(
                columns = parts[0].split(',').mapNotNull { name -> LibraryColumn.entries.firstOrNull { it.name == name } }.toSet(),
                source = LibrarySource.entries.firstOrNull { it.name == parts[1] },
                quality = LibraryQualityFilter.entries.firstOrNull { it.name == parts[2] } ?: LibraryQualityFilter.ANY,
                genre = parts[3].trim().takeIf { it.isNotEmpty() }?.take(80),
            )
        }

        /** Genres the analysis found among [songs], most common first. */
        fun genresOf(songs: List<Song>, analysis: (Song) -> LibraryAudioAnalysis?): List<String> =
            songs.mapNotNull { analysis(it)?.genre?.trim()?.takeIf(String::isNotEmpty) }
                .groupingBy { it.lowercase() }.eachCount().entries.sortedByDescending { it.value }
                .mapNotNull { entry -> songs.firstNotNullOfOrNull { s -> analysis(s)?.genre?.trim()?.takeIf { it.lowercase() == entry.key } } }
                .take(40)
    }
}
