package com.cosmotune.video.shorts

import com.cosmotune.video.feed.SubscriptionFeed
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Shorts pager's queue (spec 5.15, "Where the queue comes from"; plan task Y-31): the seed first (the card's Short and the other
 * Shorts of the list it was in), then by origin:
 *
 * - [ShortsOrigin.Home]: the feed's Shorts, newest first; the Shorts of the related lists of the last [RECENT_WATCHES] watched
 *   videos; the Shorts of the kiosk Home has selected (`VideoSettings.homeKiosk`); then the related Shorts of the queue's own Shorts,
 *   one after the other (the current one first), until none of them brings a new one;
 * - [ShortsOrigin.Subscriptions]: the feed's Shorts, newest first;
 * - [ShortsOrigin.Channel]: the channel's Shorts tab, page after page;
 * - [ShortsOrigin.Search]: the search's Shorts (`SearchFilter.SHORTS`), page after page;
 * - [ShortsOrigin.Related]: the related Shorts of the Short, then the related Shorts of the queue's Shorts as for Home.
 *
 * Only Shorts are queued ([ShortsFilter.isShort]: marked by the source, or a vertical thumbnail of a minute or less; never a live
 * stream), a Short already handed out is skipped on every later page, and a page with no next token is the end. A page holds about
 * [PAGE_SIZE] Shorts and costs at most [CALLS_PER_PAGE] source calls (the pager asks for more three pages before its end, so a
 * page may come back short, with a token, when the calls ran out first).
 *
 * The related lists, the kiosk and the history's are a bonus: one that fails is skipped. The channel's tab and the search are the
 * queue itself: when one fails before anything else was found the call throws (the pager offers "Try again"); after the seed it
 * returns what it has with a token that asks for the same page again.
 *
 * A token names the stage and the source's own page; what was handed out lives in a session of the engine (the [SESSIONS] most
 * recent are kept). A token whose session was forgotten still continues, without the memory of the earlier pages.
 */
class ShortsQueueEngine(
    private val source: VideoSource,
    private val feed: SubscriptionFeed,
    private val library: VideoLibrary,
    private val settings: VideoSettingsRepository,
) : ShortsFeed {
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, Session>()
    private var counter = 0L

    override suspend fun start(origin: ShortsOrigin, seed: List<VideoItem>): ShortsPage = mutex.withLock {
        val session = newSession(origin, null)
        val out = ArrayList<VideoItem>()
        for (item in seed) session.offer(item, out)
        fill(session, Cursor(stagesOf(origin).first(), null), out)
    }

    override suspend fun more(origin: ShortsOrigin, token: PageToken): ShortsPage = mutex.withLock {
        val parsed = parse(token, origin) ?: return@withLock ShortsPage(emptyList(), null)
        val session = sessions[parsed.first]?.takeIf { it.origin == origin } ?: newSession(origin, parsed.first)
        fill(session, parsed.second, ArrayList())
    }

    // ---- the stages ------------------------------------------------------------------------------------------------------

    /** One step of a queue: where it is, and the source's own page (or an index) inside it. */
    private data class Cursor(val stage: Stage, val inner: String?)

    /** What a step did: where the next one starts (null at the end) and how many source calls it made. */
    private class Step(val next: Cursor?, val calls: Int)

    private class Session(val id: String, val origin: ShortsOrigin) {
        val seen = HashSet<String>()

        /** Every Short handed out, in order: the related lists of the queue are read from these. */
        val queued = ArrayList<VideoItem>()

        /** Whose related list has been read already. */
        val expanded = HashSet<String>()

        /** The next of [queued] whose related list the chain reads. */
        var chainAt = 0

        fun offer(item: VideoItem, out: MutableList<VideoItem>) {
            if (!isQueueable(item) || !seen.add(item.id)) return
            out += item
            queued += item
        }
    }

    /** Runs steps from [from] until the page holds [PAGE_SIZE] Shorts, the calls are spent, or the queue has ended. */
    private suspend fun fill(session: Session, from: Cursor, out: MutableList<VideoItem>): ShortsPage {
        var cursor: Cursor? = from
        var calls = 0
        while (cursor != null && out.size < PAGE_SIZE && calls < CALLS_PER_PAGE) {
            val step = try {
                step(session, cursor, out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoSourceError) {
                // only the queue's own source gets here (the bonus stages swallow their failures)
                if (out.isEmpty()) throw e
                return ShortsPage(out.toList(), token(session, cursor))
            }
            calls += step.calls
            cursor = step.next
        }
        return ShortsPage(out.toList(), cursor?.let { token(session, it) })
    }

    private suspend fun step(session: Session, at: Cursor, out: MutableList<VideoItem>): Step = when (at.stage) {
        Stage.FEED -> {
            val shorts = feed.state.value.items.filter(::isQueueable).sortedByDescending { it.uploadedAtMs ?: Long.MIN_VALUE }
            var index = at.inner?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            while (index < shorts.size && out.size < PAGE_SIZE) session.offer(shorts[index++], out)
            Step(if (index < shorts.size) Cursor(Stage.FEED, index.toString()) else after(session, Stage.FEED), calls = 0)
        }
        Stage.HISTORY -> {
            val watched = library.history.value.take(RECENT_WATCHES)
            val index = at.inner?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (index >= watched.size) {
                Step(after(session, Stage.HISTORY), calls = 0)
            } else {
                relatedShorts(watched[index].item.id)?.forEach { session.offer(it, out) }
                Step(Cursor(Stage.HISTORY, (index + 1).toString()), calls = 1)
            }
        }
        Stage.KIOSK -> {
            bonus { source.kiosk(settings.settings.value.homeKiosk) }?.items?.forEach { session.offer(it, out) }
            Step(after(session, Stage.KIOSK), calls = 1)
        }
        Stage.SOURCE -> {
            val page: Page<ListItem> = when (val origin = session.origin) {
                is ShortsOrigin.Channel -> source.channelTab(origin.channelId, ChannelTab.SHORTS, at.inner?.let(::PageToken))
                is ShortsOrigin.Search -> source.search(origin.query, SearchFilter.SHORTS, at.inner?.let(::PageToken))
                else -> Page(emptyList(), null)
            }
            for (entry in page.items) if (entry is ListItem.Video) session.offer(entry.item, out)
            Step(page.next?.let { Cursor(Stage.SOURCE, it.value) } ?: after(session, Stage.SOURCE), calls = 1)
        }
        Stage.RELATED -> {
            val id = (session.origin as? ShortsOrigin.Related)?.videoId
            if (id == null) {
                Step(after(session, Stage.RELATED), calls = 0)
            } else {
                session.expanded += id
                relatedShorts(id)?.forEach { session.offer(it, out) }
                Step(after(session, Stage.RELATED), calls = 1)
            }
        }
        Stage.CHAIN -> {
            while (session.chainAt < session.queued.size && session.queued[session.chainAt].id in session.expanded) session.chainAt++
            if (session.chainAt >= session.queued.size) {
                Step(null, calls = 0)                       // nothing in the queue brings a new Short: the end
            } else {
                val id = session.queued[session.chainAt++].id
                session.expanded += id
                relatedShorts(id)?.forEach { session.offer(it, out) }
                Step(Cursor(Stage.CHAIN, null), calls = 1)
            }
        }
    }

    /** The Shorts of the related list of [videoId], or null when the source could not give it (skipped, as a bonus). */
    private suspend fun relatedShorts(videoId: String): List<VideoItem>? =
        bonus { source.details(videoId) }?.related?.mapNotNull { (it as? ListItem.Video)?.item }

    /** [block], or null when it fails: the stages that only add to a queue never stop it. */
    private suspend fun <T> bonus(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The stage after [stage] for the session's origin, at its start, or null when [stage] was the last. */
    private fun after(session: Session, stage: Stage): Cursor? {
        val stages = stagesOf(session.origin)
        return stages.getOrNull(stages.indexOf(stage) + 1)?.let { Cursor(it, null) }
    }

    // ---- sessions and tokens ---------------------------------------------------------------------------------------------

    private fun newSession(origin: ShortsOrigin, id: String?): Session {
        counter += 1
        val session = Session(id ?: "q$counter", origin)
        sessions.remove(session.id)
        sessions[session.id] = session
        while (sessions.size > SESSIONS) sessions.remove(sessions.keys.first())
        return session
    }

    private fun token(session: Session, cursor: Cursor) = PageToken("${session.id}$SEP${cursor.stage.name}$SEP${cursor.inner.orEmpty()}")

    /** The session id and the cursor [token] holds, or null when it is not one of this engine's tokens for [origin]. */
    private fun parse(token: PageToken, origin: ShortsOrigin): Pair<String, Cursor>? {
        val parts = token.value.split(SEP, limit = 3)
        if (parts.size != 3 || parts[0].isEmpty()) return null
        val stage = Stage.entries.firstOrNull { it.name == parts[1] } ?: return null
        if (stage !in stagesOf(origin)) return null
        return parts[0] to Cursor(stage, parts[2].ifEmpty { null })
    }

    internal enum class Stage { FEED, HISTORY, KIOSK, SOURCE, RELATED, CHAIN }

    companion object {
        /** About this many Shorts per page. */
        const val PAGE_SIZE = 10

        /** At most this many source calls per page (spec 5.3 reads up to three pages to fill a first screen of Shorts). */
        const val CALLS_PER_PAGE = 3

        /** Home reads the related lists of this many of the most recent watches. */
        const val RECENT_WATCHES = 3

        /** The queues remembered at once. */
        const val SESSIONS = 8

        private const val SEP = "|"

        /** The stages of each origin, in order (the seed always comes before them). */
        internal fun stagesOf(origin: ShortsOrigin): List<Stage> = when (origin) {
            ShortsOrigin.Home -> listOf(Stage.FEED, Stage.HISTORY, Stage.KIOSK, Stage.CHAIN)
            ShortsOrigin.Subscriptions -> listOf(Stage.FEED)
            is ShortsOrigin.Channel -> listOf(Stage.SOURCE)
            is ShortsOrigin.Search -> listOf(Stage.SOURCE)
            is ShortsOrigin.Related -> listOf(Stage.RELATED, Stage.CHAIN)
        }

        /** Whether [item] may join a Shorts queue: a Short ([ShortsFilter.isShort]) that is not live or upcoming. */
        internal fun isQueueable(item: VideoItem): Boolean = ShortsFilter.isShort(item) && !item.isLive && !item.isUpcoming
    }
}
