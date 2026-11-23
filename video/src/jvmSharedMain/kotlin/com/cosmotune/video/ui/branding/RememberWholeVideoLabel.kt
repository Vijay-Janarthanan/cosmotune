package com.cosmotune.video.ui.branding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_whole_exclusive
import com.cosmotune.video.resources.video_whole_selfpromo
import com.cosmotune.video.resources.video_whole_sponsor
import com.cosmotune.media.segments.SegmentCategory
import com.cosmotune.media.segments.SegmentProvider
import com.cosmotune.media.segments.TtlCache
import com.cosmotune.media.segments.WholeVideoLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.stringResource

/**
 * The words a card or the watch page adds to its meta line when SponsorBlock labels the whole of [item] ("Sponsored",
 * "Self-promotion", "Exclusive access"), or null. Looked up like DeArrow's branding: only for an item that stays on screen, at most
 * four lookups at once, the answers kept ten minutes.
 */
@Composable
fun rememberWholeVideoLabel(item: VideoItem): String? {
    val provider = WholeVideoGate.provider ?: return null
    val wanted = WholeVideoGate.wanted
    val category = rememberWholeVideoCategory(item, provider, wanted)
    return when (category) {
        SegmentCategory.SPONSOR -> stringResource(Res.string.video_whole_sponsor)
        SegmentCategory.SELF_PROMO -> stringResource(Res.string.video_whole_selfpromo)
        SegmentCategory.EXCLUSIVE_ACCESS -> stringResource(Res.string.video_whole_exclusive)
        else -> null
    }
}

/**
 * Hands the graph's SponsorBlock client and the settings to the cards while Video is open (the cards are also drawn where no graph is,
 * in the renders, and then say nothing).
 */
@Composable
internal fun WholeVideoGate() {
    val graph = LocalVideoGraph.current
    val settings by graph.settings.settings.collectAsState()
    val wanted = remember(settings) { WholeVideoLabel.categories(settings) }
    androidx.compose.runtime.SideEffect {
        WholeVideoGate.provider = graph.segments
        WholeVideoGate.wanted = wanted
    }
}

/** What [WholeVideoGate] hands over, as snapshot state. */
internal object WholeVideoGate {
    var provider: SegmentProvider? by mutableStateOf(null)
    var wanted: Set<SegmentCategory> by mutableStateOf(emptySet())
}

@Composable
private fun rememberWholeVideoCategory(item: VideoItem, provider: SegmentProvider, wanted: Set<SegmentCategory>): SegmentCategory? {
    val skip = wanted.isEmpty() || item.isPlaylistCard || item.isLive || item.isUpcoming
    var known by remember(item.id, provider) { mutableStateOf(WholeVideoLookups.known(item.id)) }
    if (!skip) {
        LaunchedEffect(item.id, provider) {
            if (WholeVideoLookups.known(item.id) == null) {
                delay(BrandingLookups.SETTLE_MS)
                WholeVideoLookups.lookup(provider, item.id)
            }
            known = WholeVideoLookups.known(item.id)
        }
    }
    if (skip) return null
    return known?.labels?.let { WholeVideoLabel.ORDER.firstOrNull { c -> c in it && c in wanted } }
}

/** The whole-video lookups of every list: one cache, at most [BrandingLookups.MAX_IN_FLIGHT] at once. */
internal object WholeVideoLookups {
    /** The labels found for one video (empty: none, or the lookup failed). */
    class Answer(val labels: Set<SegmentCategory>)

    private val semaphore = Semaphore(BrandingLookups.MAX_IN_FLIGHT)
    private val answers = TtlCache<String, Answer>(BrandingLookups.CACHE_MS, 1024, System::currentTimeMillis)

    fun known(videoId: String): Answer? = answers.get(videoId)

    /** Asks for every category that can label a whole video, so a change of the settings needs no new lookup. */
    suspend fun lookup(provider: SegmentProvider, videoId: String) {
        semaphore.withPermit {
            if (answers.get(videoId) != null) return
            val labels = try {
                val segments = provider.segments(videoId, WholeVideoLabel.ORDER.toSet())
                WholeVideoLabel.ORDER.filter { WholeVideoLabel.of(segments, setOf(it)) != null }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
            answers.put(videoId, Answer(labels))
        }
    }
}
