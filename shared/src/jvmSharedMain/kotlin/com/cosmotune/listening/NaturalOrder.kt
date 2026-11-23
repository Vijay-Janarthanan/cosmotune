package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import java.text.Normalizer
import java.util.Locale

/**
 * Natural order for names in the library: numbers inside a name compare by their value ("Track 2" before "Track 10"), letters
 * without regard to case or accents, and a leading "The " or "A " is not ignored (names stay where they are written).
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val x = fold(a)
        val y = fold(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                val endX = digitsEnd(x, i)
                val endY = digitsEnd(y, j)
                val numX = x.substring(i, endX).trimStart('0')
                val numY = y.substring(j, endY).trimStart('0')
                if (numX.length != numY.length) return numX.length - numY.length
                val byDigits = numX.compareTo(numY)
                if (byDigits != 0) return byDigits
                // Equal values: fewer leading zeros first, so "1" sits before "01".
                val zeros = (endX - i) - (endY - j)
                if (zeros != 0) return zeros
                i = endX
                j = endY
            } else {
                if (cx != cy) return cx - cy
                i++
                j++
            }
        }
        val rest = (x.length - i) - (y.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun digitsEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).trim()

    private val MARKS = Regex("""\p{M}+""")
}

/** The songs on the device grouped by the folder each file sits in, folders and songs in natural order. */
object LocalFolders {
    data class Folder(val path: String, val label: String, val songs: List<Song>)

    fun of(songs: List<Song>): List<Folder> =
        songs.filter { it.localPath != null }
            .groupBy { LocalLibraryRules.folderOf(it.localPath!!) ?: "" }
            .map { (path, inside) ->
                Folder(path, LocalLibraryRules.label(path).ifEmpty { "Device" }, inside.sortedWith(compareBy(NaturalOrder) { fileName(it) }))
            }
            .sortedWith(compareBy(NaturalOrder) { it.path })

    /** A song's file name without its folder, which is what a folder lists by. */
    /** Windows' path separator. */
    private val BACKSLASH = 92.toChar()

    fun fileName(song: Song): String = song.localPath?.replace(BACKSLASH, '/')?.substringAfterLast('/')?.ifBlank { null } ?: song.title
}
