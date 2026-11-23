package com.cosmotune.ui.live

import com.cosmotune.watch.live.SpotlightClock
import com.cosmotune.watch.live.SpotlightWindows
import com.cosmotune.watch.model.IncidentType
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveFocus
import com.cosmotune.watch.model.LiveInterest
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.MatchIncident
import com.cosmotune.watch.model.WatchOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The words and lists of the Spotlight (Canvas repo, design/cosmotune/search-spotlight/board.html, section 4), apart from the drawing
 * so they can be checked: what a person follows, the tabs and what each lists, the strip's one line, and the times.
 */

/** Whether [this] is one the person follows: one of their [teams] plays (by name or short name) or it is in one of their [competitions]. */
fun LiveEvent.followedBy(teams: List<String>, competitions: List<String>): Boolean {
    if (!isMatch) return false
    val team = this.teams.any { t -> teams.any { f -> f.equals(t.name, ignoreCase = true) || (t.shortName != null && f.equals(t.shortName, ignoreCase = true)) } }
    val competition = competitions.any { c -> c.equals(competitionId, ignoreCase = true) || c.equals(competition, ignoreCase = true) }
    return team || competition
}

/** What the repository needs to know: whether shows are followed, how closely the person looks, the refresh count, and the follows. */
internal fun liveInterest(sports: Set<LiveEventKind>, teams: List<String>, competitions: List<String> = emptyList(), focus: LiveFocus = LiveFocus.STRIP,
    refresh: Int = 0): LiveInterest = LiveInterest(shows = LiveEventKind.SHOW in sports, focus = focus, refresh = refresh) { it.followedBy(teams, competitions) }

/** The tabs of the sheet, in order. The country's tab is named after the country ("India"). */
enum class SpotlightTab { TOP, WORLD, COUNTRY, FOOTBALL, CRICKET, COMING }

/**
 * What the strip and the sheet are made of, for the sports shown ([sports]) and the follows: matches live, coming up and just finished
 * (follows first, then the big ones), the world's stories (most outlets first), Wikipedia's picks, the country's own stories, the live
 * shows, and what is coming (big matches and major events).
 */
class SpotlightLists(
    private val events: List<LiveEvent>,
    private val sports: Set<LiveEventKind>,
    private val teams: List<String>,
    private val competitions: List<String>,
    private val nowMs: Long,
) {
    fun followed(e: LiveEvent): Boolean = e.followedBy(teams, competitions)

    private val matches = events.filter { it.isMatch && it.kind in sports }
    private fun firstFollowed(list: List<LiveEvent>) = list.sortedBy { if (followed(it)) 0 else 1 }

    fun live(kind: LiveEventKind? = null): List<LiveEvent> = firstFollowed(matches.filter { it.isLive && (kind == null || it.kind == kind) })
    fun upcoming(kind: LiveEventKind? = null): List<LiveEvent> =
        firstFollowed(matches.filter { (kind == null || it.kind == kind) && SpotlightWindows.upcoming(it, nowMs) }.sortedBy { it.startMs })
    fun finished(kind: LiveEventKind? = null): List<LiveEvent> =
        firstFollowed(matches.filter { (kind == null || it.kind == kind) && SpotlightWindows.justFinished(it, nowMs) }.sortedByDescending { SpotlightWindows.endOf(it) })

    val world: List<LiveEvent> = events.filter { it.kind == LiveEventKind.NEWS && !it.local && it.source != "Wikipedia" }
    val editors: List<LiveEvent> = events.filter { it.kind == LiveEventKind.NEWS && it.source == "Wikipedia" }
    val country: List<LiveEvent> = events.filter { it.kind == LiveEventKind.NEWS && it.local }
    val countryName: String? = country.firstNotNullOfOrNull { it.competition }
    val shows: List<LiveEvent> = if (LiveEventKind.SHOW in sports) events.filter { it.kind == LiveEventKind.SHOW && it.isLive } else emptyList()
    val majorEvents: List<LiveEvent> = events.filter { it.kind == LiveEventKind.EVENT && SpotlightWindows.upcoming(it, nowMs) }.sortedBy { it.startMs }

    /** Top's matches: the followed ones in play, then the other big ones in play. */
    val topMatches: List<LiveEvent> get() = live().filter { followed(it) || it.major }

    /** Top: live matches (follows first), the live shows, then the five stories most outlets carry. */
    val top: List<LiveEvent> get() = topMatches + shows + world.take(5)

    /** Coming up: the big matches of the next week (follows first, a few) and the major events of the next 60 days. */
    val coming: List<LiveEvent> get() = upcoming().filter { followed(it) || it.major }.take(8) + majorEvents

    val isEmpty: Boolean get() = matches.isEmpty() && world.isEmpty() && country.isEmpty() && shows.isEmpty() && majorEvents.isEmpty()

    /** The tabs with something in them (Top always, while anything is). */
    fun tabs(): List<SpotlightTab> = buildList {
        if (isEmpty) return@buildList
        add(SpotlightTab.TOP)
        if (world.isNotEmpty() || editors.isNotEmpty()) add(SpotlightTab.WORLD)
        if (country.isNotEmpty()) add(SpotlightTab.COUNTRY)
        if (LiveEventKind.FOOTBALL in sports && matches.any { it.kind == LiveEventKind.FOOTBALL }) add(SpotlightTab.FOOTBALL)
        if (LiveEventKind.CRICKET in sports && matches.any { it.kind == LiveEventKind.CRICKET }) add(SpotlightTab.CRICKET)
        if (coming.isNotEmpty()) add(SpotlightTab.COMING)
    }

    fun tabName(tab: SpotlightTab): String = when (tab) {
        SpotlightTab.TOP -> "Top"
        SpotlightTab.WORLD -> "World"
        SpotlightTab.COUNTRY -> countryName ?: "Your country"
        SpotlightTab.FOOTBALL -> "Football"
        SpotlightTab.CRICKET -> "Cricket"
        SpotlightTab.COMING -> "Coming up"
    }

    /**
     * The one thing the strip shows: a followed match in play, else the biggest world story, else a big match in play, else the
     * next big match.
     */
    fun headline(): LiveEvent? = live().firstOrNull(::followed) ?: world.firstOrNull() ?: topMatches.firstOrNull() ?: upcoming().firstOrNull { it.major }

    /** How many more the sheet has beyond the strip's one: Top's others (at least the stories it would show). */
    fun moreThan(item: LiveEvent?): Int = (top.count { it.id != item?.id }).coerceAtLeast(0)
}

// ---- words -------------------------------------------------------------------------------------------------------------------

/** A player's surname as cards say it: "Bukayo Saka" is "Saka", "Virgil van Dijk" is "van Dijk", "Raphinha" stays. */
fun surname(name: String): String {
    val parts = name.trim().split(Regex("\\s+"))
    if (parts.size <= 1) return name.trim()
    val particles = setOf("van", "von", "de", "da", "di", "del", "der", "dos", "le", "la", "mac", "ten", "ter")
    val start = parts.indexOfFirst { it.lowercase() in particles }.takeIf { it in 1 until parts.size - 1 } ?: (parts.size - 1)
    return parts.drop(start).joinToString(" ")
}

/** One line under a football card: a side's goals or a card. */
data class ScorerLine(val side: Int, val goal: Boolean, val red: Boolean, val text: String, val minutes: List<String>)

/**
 * The lines under a football card: each side's scorers with their minutes ("Saka 12', 54'", "Palmer 33' (pen)", "Yoro 70' (og)"),
 * then the red cards, then the yellow ones; in the order they happened.
 */
fun scorerLines(event: LiveEvent): List<ScorerLine> {
    val goals = event.incidents.filter { it.isGoal }.groupBy { it.side to surname(it.player) }.map { (key, list) ->
        val minutes = list.map { i -> i.minute + when (i.type) { IncidentType.PENALTY_GOAL -> " (pen)"; IncidentType.OWN_GOAL -> " (og)"; else -> "" } }
        ScorerLine(key.first, goal = true, red = false, text = "${key.second} ${minutes.joinToString(", ")}", minutes = list.map { it.minute })
    }
    val cards = event.incidents.filter { it.type == IncidentType.RED || it.type == IncidentType.YELLOW }.sortedBy { if (it.type == IncidentType.RED) 0 else 1 }.map { i ->
        ScorerLine(i.side, goal = false, red = i.type == IncidentType.RED, text = "${surname(i.player)} ${i.minute}", minutes = listOf(i.minute))
    }
    return goals + cards
}

/** The latest goal ("Saka 54'"), for the strip. */
fun latestGoal(event: LiveEvent): MatchIncident? = event.incidents.lastOrNull { it.isGoal }

/** A football score as the strip says it: "Arsenal 2–1 Chelsea"; before kick-off "Arsenal v Chelsea". */
fun scoreLine(event: LiveEvent): String {
    val a = event.teams.getOrNull(0); val b = event.teams.getOrNull(1)
    if (a == null || b == null) return event.title
    if (event.kind == LiveEventKind.CRICKET) {
        val bat = event.teams.firstOrNull { it.batting } ?: a
        val other = event.teams.first { it !== bat }
        val score = bat.score?.substringAfterLast("& ")?.substringBefore(" (")
        return if (score.isNullOrBlank()) "${a.name} v ${b.name}" else "${bat.name} $score v ${other.name}"
    }
    return if (a.score != null && b.score != null) "${a.name} ${a.score}–${b.score} ${b.name}" else "${a.name} v ${b.name}"
}

/** The strip's one line: its mono label ("67'", "LIVE", "WORLD") and its words. */
data class StripLine(val label: String, val text: String, val inPlay: Boolean)

fun stripLine(item: LiveEvent, nowMs: Long): StripLine = when {
    item.kind == LiveEventKind.FOOTBALL && item.isLive -> {
        val goal = latestGoal(item)
        StripLine(SpotlightClock.pill(item, nowMs) ?: "Live", scoreLine(item) + (goal?.let { " · ${surname(it.player)} ${it.minute}" } ?: ""), SpotlightClock.inPlay(item))
    }
    item.kind == LiveEventKind.CRICKET && item.isLive ->
        StripLine(SpotlightClock.pill(item, nowMs) ?: "Live", scoreLine(item) + (item.statusText?.let { " · $it" } ?: ""), SpotlightClock.inPlay(item))
    item.isMatch -> StripLine(if (item.status == LiveEventStatus.UPCOMING) inText(item.startMs, nowMs) ?: "Soon" else "Result", scoreLine(item), false)
    item.kind == LiveEventKind.NEWS -> StripLine(if (item.local) (item.competition ?: "News") else "World", item.title, false)
    item.kind == LiveEventKind.EVENT -> StripLine("Coming", item.title, false)
    else -> StripLine(if (item.isLive) "Live" else "Show", item.title, item.isLive)
}

/** The strip read out whole, for a screen reader and a check: "67', Arsenal 2–1 Chelsea · Saka 54', 8 more in Spotlight". */
fun stripDescription(item: LiveEvent?, more: Int, nowMs: Long): String {
    if (item == null) return "Open Spotlight"
    val line = stripLine(item, nowMs)
    return "${line.label}, ${line.text}" + if (more > 0) ", $more more in Spotlight" else ""
}

/** "BBC · Guardian · Al Jazeera · 40 min": a story's first three outlets and how old it is. */
fun outletsLine(event: LiveEvent, nowMs: Long): String =
    (event.outlets.ifEmpty { listOf(event.source) }.take(3) + listOfNotNull(event.startMs?.let { agoText(it, nowMs) })).joinToString(" · ")

/** "5 OUTLETS" when two or more carry it; nothing for one. */
fun outletsTag(event: LiveEvent): String? = event.outlets.size.takeIf { it >= 2 }?.let { "$it OUTLETS" }

/** "now", "40 min", "3 h", "2 days". */
fun agoText(ms: Long, nowMs: Long): String {
    val m = ((nowMs - ms) / 60_000L).coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "$m min"
        m < 48 * 60 -> "${m / 60} h"
        else -> "${m / (24 * 60)} days"
    }
}

/** "in 12 min", "in 2 h", "in 12 days"; "now" once it is due; null when unknown. */
fun inText(startMs: Long?, nowMs: Long): String? {
    if (startMs == null) return null
    val m = (startMs - nowMs) / 60_000L
    return when {
        m <= 0 -> "now"
        m < 60 -> "in $m min"
        m < 36 * 60 -> "in ${m / 60} h"
        else -> "in ${(m + 12 * 60) / (24 * 60)} days"
    }
}

/** "Today 19:30", "Tomorrow 19:30", "Sat 8 Nov 19:30" in the viewer's own time; "Starting" once the start has passed. */
fun startText(startMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (startMs == null) return "Time to be confirmed"
    if (startMs <= nowMs) return "Starting"
    val start = Instant.ofEpochMilli(startMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val time = start.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    return when (ChronoUnit.DAYS.between(today, start.toLocalDate())) {
        0L -> "Today $time"
        1L -> "Tomorrow $time"
        else -> start.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) + " $time"
    }
}

/** An event's day, without a time ("Sun 29 Nov"), for an election or a summit. */
fun dayText(ms: Long, zone: ZoneId = ZoneId.of("UTC")): String =
    Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))

/** A coming row's line: "Champions League · Emirates Stadium · Today 20:00 · in 2 h"; an event's "Spain · Sun 29 Nov · in 6 days". */
fun comingLine(event: LiveEvent, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = when (event.kind) {
    LiveEventKind.EVENT -> listOfNotNull(event.competition, event.startMs?.let { dayText(it) }, inText(event.startMs, nowMs)).joinToString(" · ")
    else -> listOfNotNull(event.competition, event.venue, startText(event.startMs, nowMs, zone), inText(event.startMs, nowMs)).joinToString(" · ")
}

/** "Updated just now", "Updated 12 s ago", "Updated 3 min ago"; nothing when no time is known. */
fun updatedText(updatedAtMs: Long, nowMs: Long): String? {
    if (updatedAtMs <= 0) return null
    val s = ((nowMs - updatedAtMs) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "Updated just now"
        s < 60 -> "Updated $s s ago"
        s < 3600 -> "Updated ${s / 60} min ago"
        else -> "Updated ${s / 3600} h ago"
    }
}

/** A side's initials for its mark when it has no crest: its short name, or the first letters of its words ("Hull City" is "HC"). */
fun initials(team: LiveTeam): String = team.shortName?.takeIf { it.isNotBlank() }?.take(4)
    ?: team.name.split(' ').filter { it.isNotEmpty() && it.first().isLetter() }.take(2).joinToString("") { it.take(1) }.uppercase(Locale.ROOT).ifEmpty { "?" }

/** A cricket side's score split into its runs and what is in brackets: "287/6 (78.2 ov)" is "287/6" and "78.2 ov". */
fun cricketScore(score: String?): Pair<String, String?>? {
    val s = score?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val open = s.lastIndexOf('(')
    if (open < 0) return s to null
    val inside = s.substring(open + 1).substringBefore(')').trim().let { if (it.endsWith("ov") || it.isEmpty()) it else "$it ov" }
    return s.substring(0, open).trim() to inside.ifEmpty { null }
}

/** The channels (or live videos) that carry [event], each once: "DD News", "DD News, DD National". */
fun watchNames(event: LiveEvent): String = event.watchOptions.map { it.channel?.name ?: it.label }.distinct().joinToString(", ")

/** How free streams are named under a card, the channel said once: "DD Sports · free"; a YouTube live keeps its own label. */
fun watchLine(event: LiveEvent): String? {
    if (event.watchOptions.isEmpty()) return null
    val names = watchNames(event)
    return if (event.watchOptions.any { it.channel != null }) "$names · free" else names
}

/** The first free stream of [event], or null. */
fun firstWatch(event: LiveEvent): WatchOption? = event.watchOptions.firstOrNull()

/** The kinds as Settings names them. */
fun LiveEventKind.label(): String = when (this) {
    LiveEventKind.CRICKET -> "Cricket"
    LiveEventKind.FOOTBALL -> "Football"
    LiveEventKind.SHOW -> "Live shows"
    LiveEventKind.NEWS -> "News"
    LiveEventKind.EVENT -> "Events"
}

/** A goal to tell the person about: in a match they follow, one the last answer did not have. */
data class GoalAlert(val eventId: String, val title: String, val text: String)

/**
 * The goals in followed matches that [after] has and [before] did not: "Goal · Arsenal 2–1 Chelsea", "Saka 54'". Nothing for a
 * match not in [before] (just opened: what was already scored is not news).
 */
fun newGoals(before: List<LiveEvent>, after: List<LiveEvent>, followed: (LiveEvent) -> Boolean): List<GoalAlert> {
    val old = before.associateBy { it.id }
    return after.filter { it.kind == LiveEventKind.FOOTBALL && it.isLive && followed(it) }.mapNotNull { e ->
        val was = old[e.id] ?: return@mapNotNull null
        val goalsBefore = was.incidents.count { it.isGoal }
        val goalsNow = e.incidents.count { it.isGoal }
        fun total(x: LiveEvent) = x.teams.sumOf { it.score?.toIntOrNull() ?: 0 }
        // A goal taken back (a review) is not a goal.
        if (goalsNow <= goalsBefore && total(e) <= total(was)) return@mapNotNull null
        val goal = latestGoal(e)
        GoalAlert(e.id, "Goal · ${scoreLine(e)}", goal?.let { "${surname(it.player)} ${it.minute}" + if (it.type == IncidentType.OWN_GOAL) " (og)" else "" } ?: e.competition.orEmpty())
    }
}
