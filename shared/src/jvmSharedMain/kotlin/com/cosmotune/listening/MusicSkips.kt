package com.cosmotune.listening

import com.cosmotune.media.segments.SegmentAction
import com.cosmotune.media.segments.SegmentCategory
import com.cosmotune.media.segments.SkipSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Skipping the parts of a music video that are not the song (the idea from th-ch's YouTube Music, see NOTICE.md): the
 * talking before the music starts, a sponsor read, a plug at the end, as SponsorBlock's listeners marked them. Read only,
 * keyless; the player's position ticks ask [tick] where to jump.
 */
class MusicSkipper(
    private val scope: CoroutineScope,
    private val fetch: suspend (videoId: String, durationMs: Long?) -> List<SkipSegment>,
) {
    private var mediaId: String? = null
    @Volatile private var segments: List<SkipSegment> = emptyList()
    private val done = HashSet<String>()
    private var job: Job? = null

    /** Where to jump from [positionMs] in the song [mediaId], or null to play on. Each part is skipped once per play. */
    @Synchronized
    fun tick(mediaId: String?, positionMs: Long, durationMs: Long?): SkipSegment? {
        if (mediaId != this.mediaId) {
            this.mediaId = mediaId
            segments = emptyList(); done.clear(); job?.cancel()
            if (mediaId != null) job = scope.launch { runCatching { fetch(mediaId, durationMs?.takeIf { it > 0 }) }.getOrNull()?.let { segments = it } }
        }
        val segment = MusicSkips.target(positionMs, segments, done) ?: return null
        done += segment.uuid
        return segment
    }
}

object MusicSkips {
    /** The parts a listener of music wants gone. */
    val CATEGORIES = setOf(SegmentCategory.MUSIC_OFFTOPIC, SegmentCategory.SPONSOR, SegmentCategory.SELF_PROMO)

    /** A jump shorter than this is not worth the gap it makes. */
    const val MIN_LENGTH_MS = 2_000L

    /** The part [positionMs] is in, unless it was skipped already, is too short, or is nearly over. */
    fun target(positionMs: Long, segments: List<SkipSegment>, done: Set<String>): SkipSegment? = segments.firstOrNull {
        it.action == SegmentAction.SKIP && it.category in CATEGORIES && it.uuid !in done &&
            it.endMs - it.startMs >= MIN_LENGTH_MS && positionMs >= it.startMs && positionMs < it.endMs - 1_000
    }

    /** What the player says when it skips. */
    fun message(segment: SkipSegment): String = when (segment.category) {
        SegmentCategory.MUSIC_OFFTOPIC -> "Skipped a part without music"
        SegmentCategory.SPONSOR -> "Skipped a sponsor message"
        else -> "Skipped a plug"
    }
}
