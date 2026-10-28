package com.cosmotune.music.data.sources

import com.cosmotune.music.data.model.ArtistIdentity
import com.cosmotune.music.data.model.Song
import java.text.Normalizer
import kotlin.math.abs

/** One recording policy for the public JioSaavn adapters on Android and desktop. */
object JioSaavnMatcher {
    fun queries(target: TrackMatcher.Target): List<String> {
        val title = TrackMatcher.searchableTitle(target.title, target.artist)
        if (title.isBlank()) return emptyList()
        val names = ArtistIdentity.names(target.artist).filterNot(ArtistIdentity::isPlaceholder)
        val album = releaseName(target.album) ?: releaseNameFromTitle(target.title)
        // Ask for recording identity first. A musician's spelling must not determine whether the
        // catalogue's original album is ever found. At most three searches, with no alias table.
        return buildList {
            if (!album.isNullOrBlank()) add("$title $album".lowercase())
            add(title)
            names.firstOrNull()?.let { add("$title $it".lowercase()) }
        }.distinct()
    }

    /** Primary/featured performers and credited music/singers, never actors or a lyricist alone. */
    fun creditNames(primary: List<String>, featured: List<String>, recording: List<Pair<String, String>>): List<String> =
        (primary + featured + recording.filter { it.second.lowercase() in RECORDING_ROLES }.map { it.first })
            .map(String::trim).filterNot(ArtistIdentity::isPlaceholder).distinctBy { it.lowercase() }

    fun sharesCredit(target: TrackMatcher.Target, candidate: Song): Boolean =
        TrackMatcher.sharesArtist(target.artist, credited(candidate).artist)

    /** Recording evidence can establish identity even when neither provider names the same person. */
    fun sameRecording(target: TrackMatcher.Target, candidate: Song): Boolean =
        ranked(listOf(candidate), target).isNotEmpty()

    fun ranked(candidates: List<Song>, target: TrackMatcher.Target): List<Song> {
        val wanted = TrackMatcher.parseTitle(target.title, target.artist)
        if (wanted.core.isBlank()) return emptyList()
        val scored = candidates.mapNotNull { candidate ->
            val got = TrackMatcher.parseTitle(candidate.title, candidate.artist)
            val wantedKey = key(wanted.core)
            val gotKey = key(got.core)
            val exactTitle = wantedKey == gotKey
            val albumMarks = TrackMatcher.albumVersionMarkers(candidate.albumName)
            val versions = got.versions + (albumMarks intersect NO_VOCAL_TAKES) + (albumMarks intersect wanted.versions)
            if (wanted.versions != versions) return@mapNotNull null
            if (target.isExplicit != null && candidate.isExplicit != null && target.isExplicit != candidate.isExplicit)
                return@mapNotNull null
            val actual = TrackMatcher.secondsOf(candidate.durationText)
            val drift = if (target.durationSec != null && actual != null) abs(target.durationSec - actual) else null
            // Video intros and abridged uploads cannot be exchanged automatically for album audio.
            if (drift != null && drift > 30) return@mapNotNull null
            val albumMatches = sameAlbum(target, candidate)
            val shared = sharesCredit(target, candidate)
            // A shortened title or minor romanisation difference needs BOTH the exact release
            // and a runtime within two seconds. Never fuzzy-match a singer, or use a substring
            // plus a shared composer to cross into another language/re-recording.
            if (!exactTitle && !(albumMatches && drift != null && drift <= 2 && closeTitle(wantedKey, gotKey)))
                return@mapNotNull null
            val albumEvidence = albumMatches && (drift == null || drift <= 5)
            if (!albumEvidence && !shared) return@mapNotNull null
            Triple(candidate, 100 + (if (exactTitle) 60 else 0) + (if (albumEvidence) 100 else 0) + (if (shared) 25 else 0) +
                (drift?.let { (20 - it).coerceAtLeast(0) } ?: 0), exactTitle)
        }
        val eligible = if (scored.any { it.third }) scored.filter { it.third } else scored
        if (eligible.isNotEmpty() && eligible.none { it.third } && eligible.map {
                key(TrackMatcher.parseTitle(it.first.title, it.first.artist).core)
            }.distinct().size > 1) return emptyList()
        val albumRows = eligible.filter { sameAlbum(target, it.first) }
        if (target.durationSec == null && albumRows.size > 1) {
            val durations = albumRows.mapNotNull { TrackMatcher.secondsOf(it.first.durationText) }
            // Same album/title with different performances and no known runtime: metadata is
            // insufficient. Refuse the ambiguous release instead of guessing from a search rank.
            if (durations.isNotEmpty() && durations.max() - durations.min() > 5) return emptyList()
            val first = credited(albumRows.first().first).artist
            if (albumRows.drop(1).any { !TrackMatcher.sharesArtist(first, credited(it.first).artist) }) return emptyList()
        }
        return eligible.sortedByDescending { it.second }.map { it.first }
    }

    private fun closeTitle(first: String, second: String): Boolean {
        // This is a guarded romanisation repair. A vowel sign in an Indian-script title carries
        // identity and must never be treated as a spelling edit or dropped as punctuation.
        if (!(first + second).all { it in 'a'..'z' || it in '0'..'9' }) return false
        if (minOf(first.length, second.length) < 6) return false
        if (first.startsWith(second) || second.startsWith(first)) return true
        val limit = if (minOf(first.length, second.length) < 10) 1 else 2
        if (abs(first.length - second.length) > limit) return false
        var previous = IntArray(second.length + 1) { it }
        first.forEachIndexed { row, letter ->
            val next = IntArray(second.length + 1)
            next[0] = row + 1
            second.forEachIndexed { column, other ->
                next[column + 1] = minOf(previous[column + 1] + 1, next[column] + 1,
                    previous[column] + if (letter == other) 0 else 1)
            }
            previous = next
        }
        return previous.last() <= limit
    }

    fun sameAlbum(target: TrackMatcher.Target, candidate: Song): Boolean {
        val wanted = releaseName(target.album) ?: releaseNameFromTitle(target.title) ?: return false
        val actual = releaseName(candidate.albumName) ?: releaseNameFromTitle(candidate.title) ?: return false
        return key(wanted) == key(actual)
    }

    private fun releaseNameFromTitle(value: String): String? =
        FROM.find(unescape(value))?.groupValues?.get(1)?.let(::releaseName)

    private fun releaseName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val text = unescape(value)
        val film = FROM.find(text)?.groupValues?.get(1) ?: text
        return EDITION.replace(film, " ").trim(' ', '"', '\'', '“', '”').takeIf { key(it).isNotBlank() }
    }

    private fun unescape(value: String) = value.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    private fun key(value: String): String {
        var latinBase = false
        val folded = buildString {
            Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD).forEach { character ->
                if (character.isLetterOrDigit()) {
                    append(character)
                    latinBase = Character.UnicodeScript.of(character.code) == Character.UnicodeScript.LATIN
                } else if (!latinBase && Character.getType(character) in MARKS) append(character)
            }
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFC)
    }

    private fun credited(song: Song): Song = song.copy(artist =
        (ArtistIdentity.names(song.artist) + song.artists.map { it.name }).distinct().joinToString(", "))

    private val RECORDING_ROLES = setOf("singer", "music", "primary_artists", "featured_artists")
    private val MARKS = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    private val NO_VOCAL_TAKES = setOf("instrumental", "karaoke", "vocals", "acapella", "acappella", "backing", "stems", "stem")
    private val FROM = Regex("""[\(\[]\s*from\s+[\"'“]?(.*?)[\"'”]?\s*[\)\]]""", RegexOption.IGNORE_CASE)
    private val EDITION = Regex("""\s*[\(\[]\s*(?:original\s+(?:motion\s+picture|movie|film)\s+(?:soundtrack|score)|original\s+soundtrack|soundtrack)\s*[\)\]]\s*""", RegexOption.IGNORE_CASE)
}
