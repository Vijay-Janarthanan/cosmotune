package com.cosmotune.music.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Where the player gets its lyrics.
 *
 * Sources tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.offered]; a [hidden][LyricsSource.hidden] one is
 * never asked, whatever the saved settings say:
 *
 *  - [LrcRed] — Apple Music TTML filed by ISRC, per syllable.
 *  - [BetterLyrics] — Apple Music TTML from another host, and QQ Music's
 *    karaoke timings through its Portato route.
 *  - [YouTubeMusicLyrics] — the playing song's own Lyrics tab: timed lines
 *    where YouTube has them, plain text where it does not.
 *  - [LrcLib] — line-synced only, matched by name, so it still answers for a
 *    music-video upload or a local file.
 *
 * Every enabled source is asked *at the same time*, but their answers are
 * taken in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending
 * ahead of it. Asked one after another instead, a miss on each source would
 * cost its own round trip before the next was even tried. Run together, a miss
 * costs whatever the slowest one needed to still be waited on took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist. Plain text
 * is only ever the fallback: see [pick].
 *
 * A downloaded or local file's own tags may name the recording, which is what
 * the [isrc] parameter is for: [LrcRed] files its documents by ISRC, so a known
 * one costs a single request and cannot come back as the wrong edit.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* timed has
     * come back: off, the highest-priority timed answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        isrc: String? = null,
        /** Called from the provider job itself. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
    ): Result? = coroutineScope {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        // Every source but YouTube Music is asked for a name, and YouTube's is
        // not the name anyone catalogued. Cleaned once, here, rather than by
        // whichever source thought to do it for itself.
        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()
        val recording = isrc?.takeIf { it.isNotBlank() }

        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            source to async(Dispatchers.IO) {
                onSourceStarted?.invoke(source)
                try {
                    val found = fetch(source, videoId, searchTitle, searchArtist, durationMs, album, recording)
                        ?.let { result(source, it) }
                    onSourceResult?.invoke(source, found)
                    found
                } catch (cancelled: CancellationException) {
                    onSourceCancelled?.invoke(source)
                    throw cancelled
                } catch (_: Exception) {
                    onSourceResult?.invoke(source, null)
                    null
                }
            }
        }

        try {
            pick(racing, prioritizeSyllableSync, durationMs)
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
        }
    }

    /** How a source's answer is timed, judged on what it actually holds rather than what the source claims. */
    internal enum class Timing { NONE, LINE, WORD }

    /**
     * How far past the end of the track a line may start before the timing is
     * taken to belong to another recording. A few seconds of slack, because
     * YouTube reports whole seconds and a fade-out can carry a last word.
     */
    private const val PAST_THE_END_MS = 5_000L

    /**
     * What [lines] really are. Timing that is not there in practice counts as
     * none: fewer than two lines with a real timestamp is plain text with a
     * stray stamp (or a provider that wrote every line at zero), and lines
     * that start after the track has ended were timed against some other
     * recording. Either would beat an honest plain answer and then scroll out
     * of step, which is worse than not scrolling at all.
     */
    internal fun timing(lines: List<LyricLine>, durationMs: Long): Timing {
        val stamped = lines.filter { it.timeMs > 0 }
        if (stamped.size < 2) return Timing.NONE
        if (durationMs > 0 && stamped.maxOf { it.timeMs } > durationMs + PAST_THE_END_MS) return Timing.NONE
        return if (lines.any { it.isWordSynced }) Timing.WORD else Timing.LINE
    }

    /**
     * Takes the answers in [racing]'s order and settles on one: a word-timed
     * answer wins outright; a line-timed one wins too unless
     * [prioritizeSyllableSync] says to hold out for words; plain text is only
     * ever the fallback. So a timed answer always beats an untimed one,
     * whichever source sits higher or answered first. An answer with fewer
     * than two lines of words is a stray caption, not a song's lyrics, and is
     * passed over.
     */
    internal suspend fun pick(
        racing: List<Pair<LyricsSource, Deferred<Result?>>>,
        prioritizeSyllableSync: Boolean,
        durationMs: Long = 0L,
    ): Result? {
        var lineSynced: Result? = null
        var plain: Result? = null
        for ((_, job) in racing) {
            val found = runCatching { job.await() }.getOrNull() ?: continue
            if (found.lines.count { !it.isGap } < 2) continue
            when (timing(found.lines, durationMs)) {
                Timing.WORD -> return found
                Timing.LINE -> {
                    if (!prioritizeSyllableSync) return found
                    if (lineSynced == null) lineSynced = found
                }
                // Kept only as a fallback, wherever it sits in the order:
                // a timed answer from any source further down still beats it.
                Timing.NONE -> if (plain == null) plain = found
            }
        }
        return lineSynced ?: plain
    }

    /** One source's answer for one track; internal so the live probe asks exactly what the app asks. */
    internal suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
    ): List<LyricLine>? = when (source) {
        LyricsSource.LRC_RED -> LrcRed.lyrics(title, artist, durationMs, isrc)
        LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
        LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
        LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
        LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())
}
