package com.cosmotune.watch.live

import com.cosmotune.video.model.VideoItem
import com.cosmotune.watch.WatchPlace
import com.cosmotune.watch.meta.NameMatch
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveInterest
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchOption
import com.cosmotune.watch.plugins.youtube.OfficialLivePlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A channel of our own free plugins (DD, Live on YouTube) as the Spotlight sees it: what it has live and scheduled now.
 * [allDay] says whether its live videos are events in themselves (false: DD National, DD Sports), or only the extra ones beside
 * its 24x7 stream are (true: DD News).
 */
data class LiveChannelNow(val channel: Channel, val badge: String, val allDay: Boolean, val lives: List<VideoItem>, val upcoming: List<VideoItem>)

/**
 * The Spotlight's events: the score [sources] (each asked in parallel; one that fails keeps its last good answer, with the time it
 * was read, so a match whose source stopped answering shows as behind), the big live shows of [liveChannels] (a DD or official
 * YouTube live special), for each match in play the free streams that carry it, the news ([news]) and the major events coming
 * ([events], slow the first time, so it is sent when it comes). Kept for the windows of [SpotlightWindows], matches ranked live,
 * upcoming, finished, the big ones and the viewer's country first. Read again on [SpotlightSchedule]'s cadence for how closely the
 * person looks ([LiveInterest.focus]), only while collected.
 */
class LiveEventsHub(
    private val sources: () -> List<ScoreSource>,
    private val liveChannels: suspend () -> List<LiveChannelNow>,
    private val place: () -> WatchPlace,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 15_000,
    private val news: suspend (WatchPlace, Long) -> List<LiveEvent> = { _, _ -> emptyList() },
    private val events: suspend (Long) -> List<LiveEvent> = { emptyList() },
    private val eventsTimeoutMs: Long = 30_000,
) {
    private val lock = Mutex()
    @Volatile private var last: Pair<WatchPlace, List<LiveEvent>>? = null
    /** Each score source's last good answer, by name. */
    private val lastGood = ConcurrentHashMap<String, List<LiveEvent>>()
    @Volatile private var coming: List<LiveEvent>? = null
    @Volatile private var comingAt = 0L
    @Volatile private var refreshSeen = 0

    /**
     * The events at once (the last answer when there is one), then after every read while collected, on [SpotlightSchedule]'s
     * cadence. A [LiveInterest.refresh] higher than the last one seen reads the scores fresh.
     */
    fun flow(interest: LiveInterest = LiveInterest.ALL): Flow<List<LiveEvent>> = channelFlow {
        last?.takeIf { it.first == place() }?.let { send(it.second) }
        var fresh = interest.refresh > refreshSeen
        refreshSeen = maxOf(refreshSeen, interest.refresh)
        while (true) {
            val needEvents = coming == null || now() - comingAt > EVENTS_EVERY_MS
            val list = current(interest, fresh = fresh, awaitEvents = false)
            fresh = false
            send(list)
            if (needEvents) launch {
                if (readEvents()) send(current(interest, awaitEvents = false, reuseScores = true))
            }
            delay(SpotlightSchedule.nextPollMs(list, now(), interest.focus) ?: SpotlightSchedule.IDLE_MS)
        }
    }.distinctUntilChanged()

    /** Reads the coming events (elections, summits); true when they changed. */
    private suspend fun readEvents(): Boolean {
        val read = runCatching { withTimeoutOrNull(eventsTimeoutMs) { events(now()) } }.getOrNull() ?: return false
        comingAt = now()
        val changed = read != coming
        coming = read
        return changed
    }

    /** How long to wait before the next read for [interest] (kept for callers that ask). */
    internal fun pollAfter(list: List<LiveEvent>, interest: LiveInterest): Long =
        SpotlightSchedule.nextPollMs(list, now(), interest.focus) ?: SpotlightSchedule.IDLE_MS

    /**
     * Asks every source now and ranks the answer. Never throws. The live channels are asked only when [interest] can use them: with
     * the scores when it follows shows, after them when a followed match is in play (for the free streams that carry it). [awaitEvents]
     * waits for the coming events when none are known yet (tests, a one-off read); [reuseScores] sends the last scores again.
     */
    suspend fun current(interest: LiveInterest = LiveInterest.ALL, fresh: Boolean = false, awaitEvents: Boolean = true, reuseScores: Boolean = false): List<LiveEvent> = lock.withLock {
        val t = now()
        val viewer = place()
        if (awaitEvents && coming == null) readEvents()
        val (matches, channels, stories) = coroutineScope {
            val m = if (reuseScores) emptyList() else sources().map { s -> async { s.name to read(s, t, fresh) } }
            val askChannels: suspend () -> List<LiveChannelNow> = { runCatching { withTimeoutOrNull(timeoutMs) { liveChannels() } }.getOrNull().orEmpty() }
            val early = if (interest.shows) async { askChannels() } else null
            val n = async { runCatching { withTimeoutOrNull(timeoutMs) { news(viewer, t) } }.getOrNull().orEmpty() }
            m.awaitAll().forEach { (name, list) -> if (list != null) lastGood[name] = list }
            val found = lastGood.values.flatten()
            val c = early?.await()
                ?: if (found.any { it.status == LiveEventStatus.LIVE && interest.follows(it) }) askChannels() else emptyList()
            Triple(found, c, n.await())
        }
        val used = mutableSetOf<String>()
        val withOptions = dedupe(matches).filter { SpotlightWindows.shown(it, t) }.map { e ->
            if (e.status != LiveEventStatus.LIVE) e else {
                val options = channels.flatMap { c -> c.lives.filter { carries(it.title, e.teams) }.map { v -> c to v } }
                    .map { (c, v) -> used += v.id; option(c, v) }.distinctBy { it.target.key }
                e.copy(watchOptions = options)
            }
        }
        val shows = mergeSameShow(channels.flatMap { shows(it, t) }.filter { it.id.removePrefix("show:yt:") !in used && SpotlightWindows.shown(it, t) })
        val upcoming = coming.orEmpty().filter { SpotlightWindows.upcoming(it, t) }
        (rank(withOptions + shows, viewer) + stories + upcoming).also { last = viewer to it }
    }

    /** One source's answer, or null when it failed or was too slow (its last good answer then stands). */
    private suspend fun read(s: ScoreSource, t: Long, fresh: Boolean): List<LiveEvent>? = try {
        withTimeoutOrNull(timeoutMs) { s.events(t, fresh) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Two sources can list the same match: the one with a running score and more said about it wins. */
    private fun dedupe(events: List<LiveEvent>): List<LiveEvent> {
        fun sameMatch(e: LiveEvent) = e.kind.name + "|" + (if (e.teams.size == 2) e.teams.joinToString("|") { NameMatch.normalise(it.name) } else NameMatch.normalise(e.title)) +
            "|" + ((e.startMs ?: 0) / (6 * HOUR))
        return events.groupBy(::sameMatch).values.map { group -> group.maxBy { (if (it.scoreText != null) 4 else 0) + (if (it.source == "ESPN") 2 else 0) + it.incidents.size.coerceAtMost(1) } }
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
        /** The coming events are asked again twice a day. */
        private const val EVENTS_EVERY_MS = 12 * HOUR

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

        /** Live, then upcoming, then finished; inside each the big ones, then the viewer's country and language; upcoming by start, the rest newest first. */
        fun rank(events: List<LiveEvent>, place: WatchPlace): List<LiveEvent> = events.sortedWith(
            compareBy<LiveEvent>({ it.status.ordinal }, { it.kind == LiveEventKind.SHOW }, { if (it.major) 0 else 1 }, { local(it, place) }, { if (it.watchOptions.isEmpty()) 1 else 0 },
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
