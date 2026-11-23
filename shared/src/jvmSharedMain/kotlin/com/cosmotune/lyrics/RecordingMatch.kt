package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.artistForLyricsSearch
import com.cosmotune.music.data.lyrics.forLyricsSearch
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Conservative fallback matching: a nearby duration alone does not identify a recording. */
internal object RecordingMatch {
    /**
     * What a candidate is to the playing song: the same recording; the same song and artist at a length more than
     * [MAX_DURATION_GAP_MS] away (another edit, or a video with an intro), whose words fit but whose timing does not; or not it.
     */
    enum class Verdict { SAME, OTHER_LENGTH, NO }

    fun accepts(
        title: String,
        artist: String,
        durationMs: Long,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationMs: Long?,
        /** More names the song is credited to, as an upload lists them after pipes ([uploadCredits]). */
        credits: Collection<String> = emptyList(),
    ): Boolean = judge(title, artist, durationMs, candidateTitle, candidateArtist, candidateDurationMs, credits) == Verdict.SAME

    fun judge(
        title: String,
        artist: String,
        durationMs: Long,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationMs: Long?,
        credits: Collection<String> = emptyList(),
    ): Verdict {
        val wantedTitle = titleKey(title)
        if (wantedTitle.isEmpty() || wantedTitle != candidateTitle?.let(::titleKey)) return Verdict.NO
        val wantedArtists = artistKeys(artist) + credits.flatMap(::artistKeys)
        if (wantedArtists.isEmpty() || artistKeys(candidateArtist.orEmpty()).none { it in wantedArtists }) {
            return Verdict.NO
        }
        // Video intros, radio edits and dubbed recordings can have the same composer.
        // Unknown duration cannot establish that a timed document fits a known recording.
        if (durationMs <= 0) return Verdict.SAME
        if (candidateDurationMs == null || candidateDurationMs <= 0) return Verdict.NO
        return if (abs(candidateDurationMs - durationMs) <= MAX_DURATION_GAP_MS) Verdict.SAME else Verdict.OTHER_LENGTH
    }

    private fun titleKey(value: String): String = normalize(
        value.forLyricsSearch().replace(FROM_SOUNDTRACK, " ")
            .replace(LYRICAL_PACKAGING, " ").substringBefore(" | "),
    )

    private fun artistKeys(value: String): Set<String> = value.artistForLyricsSearch()
        .split(ARTIST_SEPARATOR).map(::normalize).filter(String::isNotEmpty).toSet()

    internal fun normalize(value: String): String {
        // Fold Latin accents without discarding Tamil/Telugu vowel signs and other
        // combining marks that distinguish different names in non-Latin scripts.
        val folded = buildString {
            Normalizer.normalize(value, Normalizer.Form.NFC).forEach { char ->
                if (Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN) {
                    append(Normalizer.normalize(char.toString(), Normalizer.Form.NFD)
                        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() })
                } else append(char)
            }
        }
        return folded.lowercase(Locale.ROOT).replace(PUNCTUATION, " ").trim().replace(SPACES, " ")
    }

    private const val MAX_DURATION_GAP_MS = 3_000L
    private val FROM_SOUNDTRACK = Regex("""[(\[]\s*from\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val LYRICAL_PACKAGING = Regex("""[(\[]\s*lyrical\s*[)\]]""", RegexOption.IGNORE_CASE)
    private val ARTIST_SEPARATOR = Regex("""\s*(?:,|&|;|\band\b|\bfeat\.?\b|\bft\.?\b)\s*""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}\p{M}]+""")
    private val SPACES = Regex("""\s+""")
}
