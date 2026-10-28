package com.opencanvas.core.matcher


/**
 * A video found by searching for a song's music video.
 *
 * @property isOfficialMusicVideo True when the channel carries YouTube's verified-artist badge.
 */
data class VideoCandidate(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val durationSec: Long,
    val isOfficialMusicVideo: Boolean = false,
    val isVerifiedChannel: Boolean = false,
    val channelId: String? = null,
    val linkedVerifiedPublisher: Boolean = false,
)

object VideoMatcher {
    private val recordingVersion = Regex("(?i)\\b(?:live|remix|mix|remaster(?:ed)?|acoustic|instrumental|karaoke|cover|version|edit|demo|reprise|duet|solo|male|female|mono|stereo|tamil|telugu|hindi|kannada|malayalam|bengali|punjabi|marathi|english|slowed|sped)\\b|\\d")

    /** Search-only subtitles; never change the recording identity or discard edition/language markers. */
    fun searchTitleVariants(title: String): List<String> {
        val base = title.replace(Regex("\\(([^)]*)\\)")) { match ->
            val subtitle = match.groupValues[1].trim()
            val words = Regex("\\p{L}+").findAll(subtitle).count()
            if (words >= 3 && !recordingVersion.containsMatchIn(subtitle)) " " else match.value
        }.replace(Regex("\\s+"), " ").trim()
        return listOf(title, base).filter(String::isNotBlank).distinct()
    }

    /** Single-number film names require an entire token, never a digit inside a year or resolution. */
    fun hasFilmContext(candidateTitle: String, album: String): Boolean {
        val film = filmKey(album)
        if (film.isNotEmpty() && film.all(Char::isDigit)) {
            return Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(film)}($|[^\\p{L}\\p{N}])").containsMatchIn(candidateTitle.lowercase())
        }
        return film.length >= 3 && filmKey(candidateTitle).contains(film)
    }

    /** A different verified artist is not evidence for the requested singer. */
    fun isCreditedArtistChannel(candidate: VideoCandidate, artist: String): Boolean {
        val channel = compact(candidate.channelTitle)
        return creditedArtists(artist).any { it.isNotEmpty() &&
            (channel == it || channel == it + "vevo" || channel == it + "official") }
    }

    /** Audio correlation also matches fan dances; independent publisher evidence is required. */
    fun hasPublisherEvidence(candidate: VideoCandidate, title: String, artist: String, album: String? = null): Boolean {
        if (!candidate.isOfficialMusicVideo && !candidate.isVerifiedChannel && !candidate.linkedVerifiedPublisher) return false
        val name = compact(candidate.title)
        val titleMatches = searchTitleVariants(title).any { variant -> compact(variant).let { it.length >= 3 && name.contains(it) } }
        val filmMatches = album?.let { hasFilmContext(candidate.title, it) } == true
        val artistMatches = isCreditedArtistChannel(candidate, artist)
        val channel = compact(candidate.channelTitle)
        if (!artistMatches && listOf("dance", "fitness", "choreo", "reaction", "fan", "edits", "status").any { channel.contains(it) }) return false
        val publisherRole = listOf("music", "records", "labels", "films", "movies", "vevo", "entertainment").any { channel.contains(it) }
        return (titleMatches || filmMatches) && (artistMatches || publisherRole ||
            candidate.isVerifiedChannel && !candidate.isOfficialMusicVideo && filmMatches)
    }

    /** Network corroboration is reserved for plausible publisher videos, not arbitrary fan uploads. */
    fun needsPublisherCorroboration(candidate: VideoCandidate, title: String, artist: String, album: String?): Boolean =
        !candidate.isOfficialMusicVideo && !candidate.isVerifiedChannel && !candidate.linkedVerifiedPublisher &&
            candidate.channelId?.matches(Regex("UC[A-Za-z0-9_-]{22}")) == true && candidate.durationSec >= MIN_SONG_VIDEO_SEC &&
            advertisesVideo(candidate.title) && scoreCandidate(candidate, title, artist) > 0 &&
            hasPublisherEvidence(candidate.copy(linkedVerifiedPublisher = true), title, artist, album)

    /** A video shorter than this is a clip, never the whole song. */
    private const val MIN_SONG_VIDEO_SEC = 90L

    /** Videos that carry the song but not the music video: matched as whole words/phrases. */
    private val NEGATIVE_KEYWORDS = listOf(
        "lyric", "lyrics", "lyrical", "audio", "visualizer", "visualiser", "reaction", "live", "concert", "cover", "karaoke", "instrumental",
        "slowed", "reverb", "sped up", "nightcore", "bass boosted", "1 hour", "10 hours", "parody",
        "behind the scenes", "making of", "teaser", "trailer", "performance", "choreography",
        "dance practice", "interview", "awards", "tour", "tutorial", "b-side", "grammy", "grammys", "talent",
        "tonight", "late late show", "snl", "saturday night live", "unplugged", "acoustic", "mashup", "full album",
        "full movie", "full film", "complete movie", "full length movie",
        "full song", "jukebox", "audio song", "episode", "sketch", "shoot", "making", "highlight", "recap", "sing along", "with me", "remix", "edit", "slowed", "sped up", "extended",
    ).map { Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(it) + "($|[^\\p{L}\\p{N}])") } +
        Regex("\\s@\\s") // "Artist 'Song' @ Some Show": a stage performance

    private val FAN_CHANNEL = Regex("(^|[^\\p{L}\\p{N}])fans?([^\\p{L}\\p{N}]|$)")

    private val POSITIVE_KEYWORDS = listOf(
        "official video", "official music video", "music video", "official mv", "official m/v", "video song",
    )

    /** Whether the title itself says it is the music video ("video song", "official video", "MV"). */
    fun saysVideo(title: String): Boolean = title.lowercase().let { t -> POSITIVE_KEYWORDS.any { t.contains(it) } }

    fun advertisesVideo(title: String): Boolean = saysVideo(title) ||
        Regex("(?i)(\\bvideo\\b|\\b[248]k\\b|\\bmv\\b|\\bm/v\\b)").containsMatchIn(title)

    /**
     * Scores how likely a candidate video is the true visual music video for a given track.
     * Higher score = better match.
     */
    fun scoreCandidate(
        candidate: VideoCandidate,
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Long = 0L,
    ): Int {
        var score = 0
        val titleNorm = candidate.title.lowercase()
        val artistNorm = expectedArtist.lowercase()
        val trackTitleNorm = expectedTitle.lowercase()
        val channelNorm = candidate.channelTitle.lowercase()

        // 1. Verified-artist channel
        if (candidate.isOfficialMusicVideo) {
            score += 60
        }

        // A clip is not a song: Shorts-length videos never carry the whole audio.
        if (candidate.durationSec in 1 until MIN_SONG_VIDEO_SEC ||
            candidate.durationSec > maxOf(900L, expectedDurationSec * 2 + 180)) score -= 300

        // 2. Channel credibility: a credited artist's own channel is the strongest hint there is
        // ("The Kid LAROI, Justin Bieber" is two artists; either one's channel counts)
        val credited = creditedArtists(expectedArtist)
        val channelKey = compact(candidate.channelTitle)
        if (credited.any { it.isNotEmpty() && (channelKey == it || channelKey == it + "vevo" || channelKey == it + "official") }) {
            score += 80
        }
        if (credited.any { it.length >= 3 && channelKey.contains(it) } || channelNorm.contains(artistNorm)) score += 35
        // Fan-made videos are not the artist's: re-uploads, edits and "fan music videos"
        if (FAN_CHANNEL.containsMatchIn(channelNorm)) score -= 100
        if (channelNorm.endsWith("vevo") || channelNorm.contains("vevo")) score += 30
        if (channelNorm.endsWith(" - topic")) score += 10

        // 3. Positive title cues
        if (POSITIVE_KEYWORDS.any { titleNorm.contains(it) }) score += 70
        if (searchTitleVariants(expectedTitle).any { variant -> compact(candidate.title).contains(compact(variant)) || variant.lowercase().split(Regex("\\s+"))
                .filter { it.length >= 3 }.takeIf { it.size >= 2 }?.all { compact(candidate.title).contains(compact(it)) } == true }) score += 30

        // 4. Negative title penalties (not for a word the song's own title or artist contains)
        for (neg in NEGATIVE_KEYWORDS) {
            if (neg.containsMatchIn(titleNorm) && !neg.containsMatchIn(trackTitleNorm) && !neg.containsMatchIn(artistNorm)) {
                // Not the music video (audio upload, lyric video, live take, ...): ranked out for good
                score -= 300
            }
        }

        // 5. Duration (if expected duration known): a music video runs a little longer than the song
        if (expectedDurationSec > 0L && candidate.durationSec > 0L) {
            val extraSec = candidate.durationSec - expectedDurationSec
            when {
                extraSec in -15L..150L -> score += 20
                extraSec < -45L || extraSec > 300L -> score -= 60
            }
        }

        return score
    }

    /** The individual artists of a credit line, each reduced to letters and digits. */
    private fun creditedArtists(credit: String): List<String> =
        credit.split(Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bx\b|\band\b)\s*""", RegexOption.IGNORE_CASE))
            .map(::compact)
            .filter { it.isNotEmpty() }

    /** Lower-case letters and digits only, so "The Weeknd", "TheWeeknd" and "the-weeknd" compare equal. */
    private fun compact(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** Film transliterations vary: "Raaja Paarvai" and "Raja Parvai" name the same release. */
    private fun filmKey(text: String): String = compact(text.replace(Regex("\\bson\\s+of\\b", RegexOption.IGNORE_CASE), "s/o"))
        .replace(Regex("([aeiou])\\1+"), "$1")

    /**
     * Picks the best official music video among candidates.
     */
    fun pickBest(
        candidates: List<VideoCandidate>,
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Long = 0L,
    ): VideoCandidate? {
        if (candidates.isEmpty()) return null
        return rank(candidates, expectedTitle, expectedArtist, expectedDurationSec, 1).firstOrNull()
    }

    /**
     * The [limit] most likely music videos, best first. Candidates with a non-positive score (clips,
     * lyric videos, live takes, covers, ...) are dropped, so the list may be shorter or empty.
     */
    fun rank(
        candidates: List<VideoCandidate>,
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Long = 0L,
        limit: Int = 3,
        album: String? = null,
    ): List<VideoCandidate> = candidates
        .map { it to (scoreCandidate(it, expectedTitle, expectedArtist, expectedDurationSec) +
            if (!album.isNullOrBlank() && hasFilmContext(it.title, album)) 80 else 0) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}
