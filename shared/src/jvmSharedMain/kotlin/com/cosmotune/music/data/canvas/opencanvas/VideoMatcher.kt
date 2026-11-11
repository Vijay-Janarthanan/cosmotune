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

    private val SOUNDTRACK_NOTE = Regex("(?i)\\s*[(\\[][^)\\]]*(?:soundtrack|motion picture|\\bost\\b|original score|from the film)[^)\\]]*[)\\]]|\\s+-\\s+(?:original\\s+)?(?:motion picture\\s+)?(?:soundtrack|ost)\\s*$")

    /** The film behind an album title: "Sakkarakatti (Original Motion Picture Soundtrack)" is the film "Sakkarakatti". */
    fun cleanFilmName(album: String): String = album.replace(SOUNDTRACK_NOTE, "").trim().ifEmpty { album.trim() }

    /**
     * Single-number film names require an entire token, never a digit inside a year or resolution. So do two-letter
     * names ("DC", "Ko"): "Hangova - Music Video | DC | Lokesh Kanagaraj" names the film, a "dc" inside a longer word
     * does not. A one-letter name is never context.
     */
    fun hasFilmContext(candidateTitle: String, album: String): Boolean {
        val film = filmKey(cleanFilmName(album))
        if (film.isNotEmpty() && (film.all(Char::isDigit) || film.length == 2)) {
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
    fun hasPublisherEvidence(candidate: VideoCandidate, title: String, artist: String, album: String? = null): Boolean =
        publisherRejection(candidate, title, artist, album) == null

    /**
     * Why [candidate] is not a publisher's video of the song, or null when it is. The title must name the song itself:
     * a film's other songs carry the film's name too, so "Hangova - Music Video | DC" is never evidence for "Ain't
     * Nobody" from the same film. Then a verified channel must stand behind it: the credited artist's own, a label or
     * TV channel ("Sun TV", "Sony Music South", "T-Series"), or any verified channel that names the film.
     */
    fun publisherRejection(candidate: VideoCandidate, title: String, artist: String, album: String? = null): String? {
        talkOverUpload(candidate, title, artist)?.let { return "a reaction, review or cover upload (\"$it\")" }
        if (!candidate.isOfficialMusicVideo && !candidate.isVerifiedChannel && !candidate.linkedVerifiedPublisher) return "channel not verified"
        if (!namesSong(candidate, title)) return "title does not name the song"
        val filmMatches = album?.let { hasFilmContext(candidate.title, it) } == true
        val artistMatches = isCreditedArtistChannel(candidate, artist)
        val channel = compact(candidate.channelTitle)
        if (!artistMatches && listOf("dance", "fitness", "choreo", "reaction", "fan", "edits", "status").any { channel.contains(it) }) return "fan, dance or reaction channel"
        val publisherRole = PUBLISHER_ROLES.any { channel.contains(it) }
        return if (artistMatches || publisherRole || candidate.isVerifiedChannel && !candidate.isOfficialMusicVideo && filmMatches) null
            else "verified channel is neither the artist, a label nor tied to the film"
    }

    /** Channel-name words of the labels, studios and TV channels that publish film songs ("Sun TV", "T-Series", "Sun Pictures"). */
    private val PUBLISHER_ROLES = listOf("music", "records", "labels", "films", "movies", "vevo", "entertainment",
        "tv", "series", "pictures", "studios", "cinemas")

    /** Whether the video's title names the song itself (a search variant, or its transliteration), not only its film. */
    fun namesSong(candidate: VideoCandidate, title: String): Boolean {
        val name = compact(candidate.title)
        val loose = looseKey(candidate.title)
        return searchTitleVariants(title).any { variant ->
            compact(variant).let { it.length >= 3 && name.contains(it) } || looseKey(variant).let { it.length >= 3 && loose.contains(it) }
        }
    }

    /**
     * Why a search result is not among the song's eligible videos, or null when it is. The expanded search logs it for
     * every result it leaves out, so an empty "eligible" list says which filter took each one out.
     */
    fun rejection(candidate: VideoCandidate, title: String, artist: String, durationSec: Long = 0L, album: String? = null): String? {
        val publisher = publisherRejection(candidate, title, artist, album)
        if (publisher != null && !hasFallbackEvidence(candidate, title, artist, album)) return publisher
        val score = scoreCandidate(candidate, title, artist, durationSec) +
            if (!album.isNullOrBlank() && hasFilmContext(candidate.title, album)) 80 else 0
        return if (score > 0) null else "ranked out (${scoreNote(candidate, title, artist, durationSec)})"
    }

    private fun scoreNote(candidate: VideoCandidate, title: String, artist: String, durationSec: Long): String {
        talkOverUpload(candidate, title, artist)?.let { return "a reaction, review or cover upload (\"$it\")" }
        val titleNorm = candidate.title.lowercase()
        NEGATIVE_KEYWORDS.firstOrNull { it.containsMatchIn(titleNorm) && !it.containsMatchIn(title.lowercase()) && !it.containsMatchIn(artist.lowercase()) }
            ?.let { negative -> return "says \"${negative.find(titleNorm)?.value?.trim()}\"" }
        if (candidate.durationSec in 1 until MIN_SONG_VIDEO_SEC) return "a ${candidate.durationSec}s clip"
        if (durationSec >= 120L && candidate.durationSec in 1 until durationSec / 2) return "far shorter than the song"
        return "another edition, or the length is far off"
    }

    /**
     * What may stand in for a publisher's video when there is none: an upload by anyone that names the song and says
     * it is the video, with the artist or the film beside it. Dance, reaction and status uploads stay out. The audio
     * check in `CanvasSession` still has to line the upload up with the song, so a wrong cut is dropped there.
     */
    fun hasFallbackEvidence(candidate: VideoCandidate, title: String, artist: String, album: String? = null): Boolean {
        if (!advertisesVideo(candidate.title)) return false
        if (talkOverUpload(candidate, title, artist) != null) return false
        val channel = compact(candidate.channelTitle)
        if (listOf("dance", "fitness", "choreo", "reaction", "edits", "status").any { channel.contains(it) }) return false
        if (!namesSong(candidate, title)) return false
        val name = compact(candidate.title)
        val artistNamed = creditedArtists(artist).any { it.length >= 4 && (name.contains(it) || channel.contains(it)) }
        return artistNamed || album?.let { hasFilmContext(candidate.title, it) } == true
    }

    /**
     * Words of uploads that carry the song under someone talking over it or performing it again: reactions ("Producer
     * Reacts", "first time hearing"), reviews and breakdowns, covers, podcasts, live streams and vlogs. The song's audio
     * plays inside them, so they line up with it; their picture is a person with headphones, never the music video.
     */
    private val TALK_OVER_TITLE = listOf(
        "react", "reacts", "reacting", "reaction", "reactions", "rxn", "reax", "reaxn", "review", "reviews", "reviewing",
        "breakdown", "explained", "explaining", "explanation", "decoded", "decoding", "analysis", "first time", "1st time",
        "hearing", "listening to", "cover", "covers", "cover song", "dance cover", "podcast", "live stream", "livestream",
        "vlog", "vlogs", "commentary", "watch along", "watchalong", "public opinion", "public review", "public talk",
        "honest opinion", "roast",
    ).map { Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(it) + "($|[^\\p{L}\\p{N}])") }

    /** The same in a channel's name, read without spaces ("ProducerReacts", "Tamil Reviews", "Pod Cast"). */
    private val TALK_OVER_CHANNEL = listOf("react", "review", "podcast", "vlog", "rxn", "commentary", "breakdown", "livestream")
    private val COVER_CHANNEL = Regex("(^|[^\\p{L}\\p{N}])covers?($|[^\\p{L}\\p{N}])")

    /**
     * The word that makes [candidate] a reaction, review, cover or similar upload, or null. A word the song's own title or
     * credit contains ("Cover Me", an artist called "The Reviewers") does not count against it.
     */
    fun talkOverUpload(candidate: VideoCandidate, title: String = "", artist: String = ""): String? {
        val own = "${title.lowercase()} ${artist.lowercase()}"
        val titleNorm = candidate.title.lowercase()
        TALK_OVER_TITLE.firstOrNull { it.containsMatchIn(titleNorm) && !it.containsMatchIn(own) }
            ?.let { return it.find(titleNorm)?.value?.trim() }
        val channelNorm = candidate.channelTitle.lowercase()
        val channel = compact(candidate.channelTitle)
        val ownKey = compact(own)
        TALK_OVER_CHANNEL.firstOrNull { channel.contains(it) && !ownKey.contains(it) }?.let { return it }
        if (COVER_CHANNEL.containsMatchIn(channelNorm) && !COVER_CHANNEL.containsMatchIn(own)) return "cover"
        return null
    }

    /**
     * The longest stretch of video the song has nothing for, inside the song: between two segments the video jumps
     * ahead by this much. A reaction stops the song to talk, so its map has such holes; a music video's edits cut the
     * song shorter (the jump goes back) or pause briefly.
     */
    fun longestTalkGapMs(segments: List<com.opencanvas.core.sync.SyncSegment>): Long =
        segments.sortedBy { it.songStartMs }.zipWithNext { a, b -> b.offsetMs - a.offsetMs }.maxOrNull()?.coerceAtLeast(0L) ?: 0L

    /** Longer than this inside the song is talk over it, not an edit of the video. */
    const val TALK_GAP_MS = 10_000L

    /** A music video runs about the song's length; beyond this either way it is suspect. */
    const val LENGTH_TOLERANCE = 0.25

    /** Coverage at which a much longer upload is still believed (a long cinematic intro with the whole song in it). */
    const val LONG_UPLOAD_COVERAGE = 0.97

    /** Coverage at which a publisher's upload is believed with film scenes or dialogue before, inside or after the song. */
    const val PUBLISHER_COVERAGE = 0.80

    /** A publisher's edit may replay a little of the video; more than this backwards is a scramble, not the song in order. */
    const val MAX_BACKWARD_JUMP_MS = 30_000L

    /** Whether the map plays the song through the video in order: no overlapping parts, no long jump back. */
    fun inOrder(segments: List<com.opencanvas.core.sync.SyncSegment>): Boolean = segments.sortedBy { it.songStartMs }.zipWithNext { a, b ->
        b.songStartMs >= a.songEndMs - 1_000L && b.offsetMs - a.offsetMs >= -MAX_BACKWARD_JUMP_MS
    }.all { it }

    /**
     * Why a measured match must still not be shown, or null when it may. No video is better than a wrong one.
     *
     * A reaction or similar upload never is. For anyone else's upload, long stretches of something else between parts of
     * the song (talk over it) or a length far from the song's (without matching nearly all of it) rule it out. A
     * publisher's own upload of the song (label, studio or TV channel, the song named) is different: Indian film "Video
     * Song" uploads carry film scenes and dialogue before, inside and after the song, so with [PUBLISHER_COVERAGE] of the
     * song matched, in order, both are allowed.
     */
    fun alignedButWrong(candidate: VideoCandidate, songTitle: String, artist: String, songDurationSec: Long,
        segments: List<com.opencanvas.core.sync.SyncSegment>, coverage: Double, album: String? = null): String? {
        talkOverUpload(candidate, songTitle, artist)?.let { return "a reaction, review or cover upload (\"$it\"), never the music video" }
        val gap = longestTalkGapMs(segments)
        if (hasPublisherEvidence(candidate, songTitle, artist, album)) {
            if (!inOrder(segments)) return "the publisher's video plays the song out of order: another cut"
            if (coverage >= PUBLISHER_COVERAGE) return null
            if (gap > TALK_GAP_MS) return "the publisher's video matches only ${(coverage * 100).toInt()}% of the song around a ${gap / 1000}s break"
        } else if (gap > TALK_GAP_MS) return "the video stops the song for ${gap / 1000}s between its parts (talk over it), not a music video"
        if (songDurationSec > 0L && candidate.durationSec > 0L) {
            val ratio = candidate.durationSec.toDouble() / songDurationSec
            if ((ratio > 1 + LENGTH_TOLERANCE || ratio < 1 - LENGTH_TOLERANCE) && !(coverage >= LONG_UPLOAD_COVERAGE && gap <= TALK_GAP_MS / 2))
                return "${candidate.durationSec}s against the song's ${songDurationSec}s with ${(coverage * 100).toInt()}% matched: not the song's video"
        }
        return null
    }

    /** Transliterations differ ("Marudaani" / "Marudhani", "Chinnama" / "Chinnamma"): no h, no doubled letters. */
    private fun looseKey(text: String): String = compact(text).replace("h", "").replace(Regex("(.)\\1+"), "$1")

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
    private val distinctVersion = Regex("(?i)\\b(?:reprise|(?:female|male|duet|solo)\\s+(?:version|vocals?))\\b")
    private val gameEdit = Regex("(?i)\\b(?:gameplay|bgmi|amv|gmv)\\b")

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
        val expectedEditions = distinctVersion.findAll(trackTitleNorm).map { it.value.substringBefore(' ') }.toSet()
        val videoEditions = distinctVersion.findAll(titleNorm).map { it.value.substringBefore(' ') }.toSet()
        if (videoEditions != expectedEditions || gameEdit.containsMatchIn(titleNorm) && !gameEdit.containsMatchIn(trackTitleNorm)) return -1_000
        // Never the music video, even as the only result: someone talking over the song, or performing it again.
        if (talkOverUpload(candidate, expectedTitle, expectedArtist) != null) return -1_000

        // 1. Verified-artist channel
        if (candidate.isOfficialMusicVideo) {
            score += 60
        }

        // A clip is not a song: Shorts-length videos never carry the whole audio.
        // Nor is a teaser: under half the song's length, it would end long before the song does.
        if (candidate.durationSec in 1 until MIN_SONG_VIDEO_SEC ||
            expectedDurationSec >= 120L && candidate.durationSec in 1 until expectedDurationSec / 2 ||
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
