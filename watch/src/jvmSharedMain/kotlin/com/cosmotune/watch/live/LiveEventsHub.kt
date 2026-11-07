package com.cosmotune.watch.live

import com.cosmotune.video.model.VideoItem
import com.cosmotune.watch.WatchPlace
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchOption
import com.cosmotune.watch.plugins.youtube.OfficialLivePlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A channel of our own free plugins (DD, Live on YouTube) as the events strip sees it: what it has live and scheduled now.
 * [allDay] says whether its live videos are events in themselves (false: DD National, DD Sports), or only the extra ones beside
 * its 24x7 stream are (true: DD News).
 */
data class LiveChannelNow(val channel: Channel, val badge: String, val allDay: Boolean, val lives: List<VideoItem>, val upcoming: List<VideoItem>)

/**
 * The "Live now" strip's events: the score [sources] (each asked in parallel, a failing or slow one left out), the big live
 * shows of [liveChannels] (a DD or official YouTube live special), and for each match in play the free streams that carry it
 * (a live video on DD Sports or another of [liveChannels] whose title names both sides). Kept for the window the strip shows
 * (live; upcoming in the next 24 hours; started within the last 16 hours and finished), ranked live, upcoming, finished, the
 * viewer's country and language first. Polled about every minute while something is live, every ten minutes otherwise, and
 * only while collected; each source also keeps its own answer for its polite interval.
 */
class LiveEventsHub(
    private val sources: () -> List<ScoreSource>,
    private val liveChannels: suspend () -> List<LiveChannelNow>,
    private val place: () -> WatchPlace,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 15_000,
    private val livePollMs: Long = 60_000,
    private val idlePollMs: Long = 10 * 60_000L,
    private val limit: Int = 40,
) {
    private val lock = Mutex()
    @Volatile private var last: List<LiveEvent>? = null

    /** The events at once (the last answer when there is one), then after every poll while collected. */
    fun flow(): Flow<List<LiveEvent>> = channelFlow {
        last?.let { send(it) }
        while (true) {
            val list = current()
            send(list)
            delay(if (list.any { it.isLive }) livePollMs else idlePollMs)
        }
    }.distinctUntilChanged()

    /** Asks every source now (each keeps its own cache) and ranks the answer. Never throws. */
    suspend fun current(): List<LiveEvent> = lock.withLock {
        val t = now()
        val (matches, channels) = coroutineScope {
            val m = sources().map { s -> async { runCatching { withTimeoutOrNull(timeoutMs) { s.events(t) } }.getOrNull().orEmpty() } }
            val c = async { runCatching { withTimeoutOrNull(timeoutMs) { liveChannels() } }.getOrNull().orEmpty() }
            m.awaitAll().flatten() to c.await()
        }
        val used = mutableSetOf<String>()
        val withOptions = dedupe(matches).filter { inWindow(it, t) }.map { e ->
            if (e.status != LiveEventStatus.LIVE) e else {
                val options = channels.flatMap { c -> c.lives.filter { carries(it.title, e.teams) }.map { v -> c to v } }
                    .map { (c, v) -> used += v.id; option(c, v) }.distinctBy { it.target.key }
                e.copy(watchOptions = options)
            }
        }
        val shows = mergeSameShow(channels.flatMap { shows(it, t) }.filter { it.id.removePrefix("show:yt:") !in used && inWindow(it, t) })
        val ranked = rank(withOptions + shows, place())
        ranked.take(limit).also { last = it }
    }

    /** Two services can list the same match: the one with a running score wins (football-data.org over TheSportsDB). */
    private fun dedupe(events: List<LiveEvent>): List<LiveEvent> {
        fun sameMatch(e: LiveEvent) = e.kind.name + "|" + (if (e.teams.size == 2) e.teams.joinToString("|") { NameMatch.normalise(it.name) } else NameMatch.normalise(e.title)) +
            "|" + ((e.startMs ?: 0) / (6 * HOUR))
        return events.groupBy(::sameMatch).values.map { group -> group.maxBy { (if (it.scoreText != null) 2 else 0) + (if (it.source != "TheSportsDB") 1 else 0) } }
    }

    /**
     * The same special shown on two channels (DD News and DD National both carry a ceremony) is one event with both watch options,
     * not two events of one title.
     */
    private fun mergeSameShow(shows: List<LiveEvent>): List<LiveEvent> =
        shows.groupBy { it.status to NameMatch.normalise(it.title) }.values.map { group ->
            if (group.size == 1) group.first()
            else group.first().copy(watchOptions = group.flatMap { it.watchOptions }.distinctBy { it.target.key })
        }

    private fun inWindow(e: LiveEvent, t: Long): Boolean = when (e.status) {
        LiveEventStatus.LIVE -> true
        LiveEventStatus.UPCOMING -> e.startMs == null || e.startMs in (t - HOUR)..(t + 24 * HOUR)
        LiveEventStatus.FINISHED -> e.startMs != null && e.startMs >= t - 16 * HOUR
    }

    private fun option(c: LiveChannelNow, v: VideoItem): WatchOption =
        if (c.lives.firstOrNull()?.id == v.id) WatchOption(c.channel.name, PlayTarget.Live(c.channel), c.channel, c.badge)
        else WatchOption("${c.channel.name} · live", PlayTarget.Item(WatchKey.item(c.channel.pluginId, v.id), c.channel.pluginId, v.id, OfficialLivePlugin.cleanTitle(v.title)), c.channel, c.badge)

    /**
     * The live specials of a channel: every live video of a channel that is not on all day; beside a 24x7 stream, the others
     * (a channel's own "24x7" stream is never a special); and what it has scheduled within the day.
     */
    private fun shows(c: LiveChannelNow, t: Long): List<LiveEvent> {
        val main = if (c.allDay) c.lives.firstOrNull { looksAllDay(it.title) } ?: c.lives.firstOrNull() else null
        val live = c.lives.filter { it.id != main?.id && !looksAllDay(it.title) }.map { v ->
            show(c, v, LiveEventStatus.LIVE, t, listOf(option(c, v)))
        }
        val upcoming = if (c.allDay) emptyList() else c.upcoming.filter { it.uploadedAtMs != null }.map { v -> show(c, v, LiveEventStatus.UPCOMING, t, emptyList()) }
        return live + upcoming
    }

    private fun show(c: LiveChannelNow, v: VideoItem, status: LiveEventStatus, t: Long, options: List<WatchOption>) = LiveEvent(
        id = "show:yt:${v.id}", kind = LiveEventKind.SHOW, title = OfficialLivePlugin.cleanTitle(v.title),
        statusText = if (status == LiveEventStatus.LIVE) "Live" else "On ${c.channel.name}", status = status,
        startMs = v.uploadedAtMs, countries = c.channel.countries, languages = c.channel.languages, imageUrl = v.thumbnailUrl,
        watchOptions = options, source = "YouTube", updatedAtMs = t,
    )

    companion object {
        private const val HOUR = 3_600_000L

        private fun looksAllDay(title: String) = Regex("24\\s*[x×*]\\s*7", RegexOption.IGNORE_CASE).containsMatchIn(title)

        /**
         * Whether a live video's [title] is the match of [teams]: both sides named (their name, the name without "Women",
         * or their short name as a whole word).
         */
        fun carries(title: String, teams: List<LiveTeam>): Boolean {
            if (teams.size < 2) return false
            val t = " " + NameMatch.normalise(title) + " "
            return teams.all { team ->
                val names = listOfNotNull(team.name, team.name.replace(Regex("(?i)\\s+women$"), ""), team.shortName).map { NameMatch.normalise(it) }.filter { it.length >= 2 }
                names.any { " $it " in t }
            }
        }

        /** Live, then upcoming, then finished; inside each the viewer's country and language first; upcoming by start, the rest newest first. */
        fun rank(events: List<LiveEvent>, place: WatchPlace): List<LiveEvent> = events.sortedWith(
            compareBy<LiveEvent>({ it.status.ordinal }, { local(it, place) }, { if (it.watchOptions.isEmpty()) 1 else 0 },
                { if (it.status == LiveEventStatus.UPCOMING) it.startMs ?: Long.MAX_VALUE else -(it.startMs ?: 0) }, { it.title }),
        )

        /** 0 the viewer's country and language, 1 their country, 2 their language, 3 the rest. */
        fun local(e: LiveEvent, place: WatchPlace): Int {
            val country = e.countries.any(place::isCountry) || e.teams.any { it.country?.let(place::isCountry) == true }
            val language = e.languages.any(place::isLanguage)
            return when { country && language -> 0; country -> 1; language -> 2; else -> 3 }
        }
    }
}
