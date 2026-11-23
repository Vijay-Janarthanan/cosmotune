package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import java.text.Normalizer
import java.util.Locale

/**
 * What the listener asked never to be played for them: artists they blocked, and explicit songs when they hide those. AutoPlay
 * and radio (both platforms, through [com.cosmotune.music.data.YtMusicRepository.radio]) and Home's shelves leave such songs
 * out; a song the listener picks themselves still plays. The settings publish here
 * ([com.cosmotune.settings.CosmoLookSettings]), so the network layer needs no settings of its own.
 */
object MusicBlocks {

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile var hideExplicit: Boolean = false
        private set

    fun update(artists: Set<String>, hideExplicit: Boolean) {
        blocked = artists.map(::key).filter { it.isNotEmpty() }.toSet()
        this.hideExplicit = hideExplicit
    }

    /** Whether [song] may be played on the listener's behalf. */
    fun allows(song: Song): Boolean {
        if (hideExplicit && song.isExplicit == true) return false
        val names = blocked
        if (names.isEmpty()) return true
        return artistsOf(song).none { key(it) in names }
    }

    /** The names credited on [song]: its artist list, or its byline split at the usual joins. */
    fun artistsOf(song: Song): List<String> {
        val refs = song.artists.map { it.name }.filter { it.isNotBlank() }
        if (refs.isNotEmpty()) return refs
        return song.artist.split(JOINS).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** One spelling for a name: lower case, accents and spacing folded, so "Beyoncé" and "beyonce " are the same artist. */
    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace(SPACES, " ").trim()

    private val JOINS = Regex("\\s*(?:,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\bwith\\b|•)\\s*", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
}
