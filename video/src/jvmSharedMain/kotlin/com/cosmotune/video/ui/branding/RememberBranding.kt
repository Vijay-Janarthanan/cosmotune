package com.cosmotune.video.ui.branding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.size.Size
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.segments.Branding
import com.cosmotune.video.segments.BrandingProvider
import com.cosmotune.video.segments.DeArrowTitles
import com.cosmotune.video.segments.TtlCache
import com.cosmotune.video.settings.TitleCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The title a card, a row or the watch page shows for [item] (spec 7.2): with DeArrow titles on, DeArrow's title when it has one
 * (looked up while the item is on screen, see [BrandingLookups]), else the item's own; then the title-case setting
 * ([DeArrowTitles.clean], which also takes DeArrow's `>` markers out). With DeArrow titles off, the item's own title as it is.
 */
@Composable
fun rememberVideoTitle(item: VideoItem): String {
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    val on = settings.deArrowTitles
    val dearrow = rememberBranding(item, wanted = on).branding?.title?.takeIf { on }
    val case = settings.titleCase
    return remember(item.title, dearrow, on, case) { shownTitle(item.title, dearrow, on, case) }
}

/**
 * The picture a card, a row or the watch page shows for [item] (spec 7.2): with DeArrow thumbnails on, DeArrow's picture at its
 * trusted moment when it has one and the picture service answered for it, else the item's own. DeArrow's picture is tried once
 * through the app's image loader (into its disk cache, so the card's own request finds it there): a failure (a 204 when the
 * picture is not made yet, an error) falls back to the item's own picture, the image request's own fallback.
 */
@Composable
fun rememberVideoThumbnail(item: VideoItem): String? {
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    val on = settings.deArrowThumbnails
    val known = rememberBranding(item, wanted = on, checkPicture = on)
    val picture = known.branding?.thumbnailUrl?.takeIf { on && known.pictureOk == true }
    return picture ?: item.thumbnailUrl
}

/** What is known of one video's branding: whether it was looked up, DeArrow's answer (null: none), and whether its picture loads. */
@Immutable
internal data class BrandingState(val known: Boolean, val branding: Branding?, val pictureOk: Boolean?) {
    /** Nothing more to ask: looked up, and the picture tried when [checkPicture] and there is one. */
    fun complete(checkPicture: Boolean): Boolean = known && (!checkPicture || branding?.thumbnailUrl == null || pictureOk != null)

    companion object {
        val Unknown = BrandingState(known = false, branding = null, pictureOk = null)
    }
}

/**
 * DeArrow's branding of [item] while it is composed and [wanted]: what is cached at once, else a lookup that starts once the item
 * has been on screen for [BrandingLookups.SETTLE_MS] (an item that only flashed by while scrolling asks for nothing), queued
 * behind at most [BrandingLookups.MAX_IN_FLIGHT] others, keyed on the video's id. [checkPicture] also tries DeArrow's picture.
 */
@Composable
internal fun rememberBranding(item: VideoItem, wanted: Boolean, checkPicture: Boolean = false): BrandingState {
    val provider = LocalVideoGraph.current.branding
    val context = LocalPlatformContext.current
    var state by remember(item.id, provider) { mutableStateOf(BrandingLookups.state(item.id)) }
    if (wanted) {
        LaunchedEffect(item.id, provider, checkPicture) {
            val now = BrandingLookups.state(item.id)
            if (!now.complete(checkPicture)) {
                delay(BrandingLookups.SETTLE_MS)
                BrandingLookups.lookup(provider, item.id, item.durationSec?.times(1000)) { url -> if (checkPicture) probePicture(context, url) else null }
            }
            state = BrandingLookups.state(item.id)
        }
    }
    return state
}

/**
 * The lookups of DeArrow's branding for every list (spec 7.2: "lists ask at most 4 lookups at a time and only for items on
 * screen"; each lookup reads the answer for a whole hash prefix, about 18 KB): one process-wide cache of the answers (10 minutes
 * here; the client keeps them 24 hours), at most [MAX_IN_FLIGHT] lookups at once (the others wait their turn in order, and leave
 * the queue with the card that asked), and an answer already cached when a waiting lookup gets its turn is not asked again.
 */
internal object BrandingLookups {
    /** At most this many lookups at once (spec 7.2: 4). */
    const val MAX_IN_FLIGHT = 4

    /** How long an item must stay composed before its lookup starts. */
    const val SETTLE_MS = 250L

    /** How long an answer (or "none", or a failure) is kept here. */
    const val CACHE_MS = 10 * 60_000L

    private const val CACHE_ENTRIES = 1024
    private const val PICTURE_ENTRIES = 1024

    /** What DeArrow said about one video (null: nothing worth using, or the lookup failed). */
    private class Answer(val branding: Branding?)

    private val semaphore = Semaphore(MAX_IN_FLIGHT)
    private val answers = TtlCache<String, Answer>(CACHE_MS, CACHE_ENTRIES, System::currentTimeMillis)

    /** DeArrow pictures that loaded (true) or failed (false); the others have not been tried. The oldest go past [PICTURE_ENTRIES]. */
    private val pictures = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > PICTURE_ENTRIES
    }

    /** What is known of [videoId] now. */
    fun state(videoId: String): BrandingState {
        val answer = answers.get(videoId) ?: return BrandingState.Unknown
        return BrandingState(known = true, branding = answer.branding, pictureOk = answer.branding?.thumbnailUrl?.let(::pictureWorks))
    }

    @Synchronized
    private fun pictureWorks(url: String): Boolean? = pictures[url]

    @Synchronized
    private fun keepPicture(url: String, works: Boolean) {
        pictures[url] = works
    }

    /**
     * Looks [videoId] up with a permit: the cached answer, else [provider]'s (a failure counts as "none" for [CACHE_MS]; the client
     * logs it); then, when the answer has a picture not tried yet, [probe] tries it (true: it loads; false: it does not; null: not
     * tried this time).
     */
    suspend fun lookup(provider: BrandingProvider, videoId: String, durationMs: Long?, probe: suspend (String) -> Boolean?) {
        semaphore.withPermit {
            val answer = answers.get(videoId) ?: Answer(fetch(provider, videoId, durationMs)).also { answers.put(videoId, it) }
            val url = answer.branding?.thumbnailUrl
            if (url != null && pictureWorks(url) == null) probe(url)?.let { keepPicture(url, it) }
        }
    }

    private suspend fun fetch(provider: BrandingProvider, videoId: String, durationMs: Long?): Branding? = try {
        provider.branding(videoId, durationMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Forgets everything (tests). */
    @Synchronized
    fun clear() {
        answers.clear()
        pictures.clear()
    }
}

/** The title shown: DeArrow's (cased, markers out) when on and given, the original cased when on, the original as it is when off. */
internal fun shownTitle(original: String, dearrow: String?, on: Boolean, case: TitleCase): String = when {
    !on -> original
    dearrow != null -> DeArrowTitles.clean(dearrow, case)
    case == TitleCase.AS_GIVEN -> original
    else -> DeArrowTitles.clean(original, case)
}

/**
 * Tries DeArrow's picture at [url] through the app's image loader: decoded small (64 x 36), not kept in memory, kept on disk, so
 * the card's own request for the picture is a disk hit. True when it loaded, false when it did not.
 */
private suspend fun probePicture(context: PlatformContext, url: String): Boolean {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(Size(PROBE_WIDTH, PROBE_HEIGHT))
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()
    return SingletonImageLoader.get(context).execute(request) !is ErrorResult
}

private const val PROBE_WIDTH = 64
private const val PROBE_HEIGHT = 36
