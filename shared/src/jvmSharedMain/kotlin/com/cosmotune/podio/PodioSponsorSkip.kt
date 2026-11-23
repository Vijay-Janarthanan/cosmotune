package com.cosmotune.podio

import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.segments.SponsorBlockClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skipping sponsors in a YouTube podcast episode (Metrolist's idea, SponsorBlock's community segments as Video mode uses them):
 * paid promotion, self-promotion and "subscribe" reminders are skipped while Player options > Skip sponsors in podcasts is on.
 */
object PodioSponsorSkip {
    val categories = setOf(SegmentCategory.SPONSOR, SegmentCategory.SELF_PROMO, SegmentCategory.INTERACTION)

    /** Where to jump to from [positionMs]: the end of the skippable segment it is in (unless that end is under a second away). */
    fun target(segments: List<SkipSegment>, positionMs: Long): Long? =
        segments.firstOrNull { it.action == SegmentAction.SKIP && positionMs >= it.startMs && positionMs < it.endMs - 1_000 }?.endMs

    /**
     * Watches the playing item while it runs on [scope]: [playing] answers the item id and position (on the player's thread),
     * [seek] moves the player. Segments are looked up once per episode.
     */
    fun watch(scope: CoroutineScope, enabled: () -> Boolean, playing: () -> Pair<String?, Long>?, seek: (Long) -> Unit,
        client: SponsorBlockClient = SponsorBlockClient()): Job = scope.launch {
        var forId: String? = null
        var segments = emptyList<SkipSegment>()
        while (isActive) {
            delay(500)
            if (!enabled()) continue
            val (id, position) = playing() ?: continue
            val video = YouTubePodcasts.videoId(id) ?: continue
            if (id != forId) {
                forId = id
                segments = withContext(Dispatchers.IO) { runCatching { client.segments(video, categories) }.getOrDefault(emptyList()) }
            }
            target(segments, position)?.let(seek)
        }
    }
}
