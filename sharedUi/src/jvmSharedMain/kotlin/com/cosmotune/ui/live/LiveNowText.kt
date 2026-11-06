package com.cosmotune.ui.live

import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.WatchOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The words and numbers of "Live now" (Canvas repo, design/cosmotune/live-strip/board.html), apart from the drawing so they can be
 * checked: which events a person follows, the pill's short score, a row's title and the "Coming up" time.
 */

/** Whether [event] is one the person follows: its sport is on and, when teams are chosen, one of them plays (a show has no teams). */
fun LiveEvent.followedBy(sports: Set<LiveEventKind>, teams: List<String>): Boolean {
    if (kind !in sports) return false
    if (teams.isEmpty() || kind == LiveEventKind.SHOW) return true
    return this.teams.any { t -> teams.any { f -> f.equals(t.name, ignoreCase = true) || f.equals(t.shortName, ignoreCase = true) } } ||
        teams.any { f -> title.contains(f, ignoreCase = true) }
}

/** What the pill and the panel are made of: the followed events in the repository's order, split by where they stand. */
class LiveNowLists(val live: List<LiveEvent>, val upcoming: List<LiveEvent>, val finished: List<LiveEvent>) {
    val top: LiveEvent? get() = live.firstOrNull()
    val isEmpty: Boolean get() = live.isEmpty() && upcoming.isEmpty() && finished.isEmpty()

    companion object {
        fun of(events: List<LiveEvent>, sports: Set<LiveEventKind>, teams: List<String>, kind: LiveEventKind? = null): LiveNowLists {
            val shown = events.filter { it.followedBy(sports, teams) && (kind == null || it.kind == kind) }
            return LiveNowLists(
                shown.filter { it.status == LiveEventStatus.LIVE },
                shown.filter { it.status == LiveEventStatus.UPCOMING },
                shown.filter { it.status == LiveEventStatus.FINISHED },
            )
        }
    }
}

/** A team's name as short as the pill wants it: its short name, or the first word cut to four letters. */
fun LiveTeam.short(): String = shortName?.takeIf { it.isNotBlank() } ?: name.split(' ').first().take(4).uppercase(Locale.ROOT)

/** A cricket score split into its runs and its overs: "176/4 (18.2 ov)" is "176/4" and "18.2 ov". */
fun cricketScore(score: String?): Pair<String, String?>? {
    val s = score?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val open = s.indexOf('(')
    if (open < 0) return s to null
    val overs = s.substring(open + 1).substringBefore(')').trim().let { if (it.endsWith("ov")) it else "$it ov" }
    return s.substring(0, open).trim() to overs
}

private fun oversOf(score: String?): Double? = cricketScore(score)?.second?.removeSuffix("ov")?.trim()?.toDoubleOrNull()

/**
 * The side batting now, as well as a score can tell: the only one with a score, or the one with fewer overs bowled (the second
 * innings), the later one when they are level.
 */
fun battingSide(teams: List<LiveTeam>): LiveTeam? {
    val scored = teams.filter { !it.score.isNullOrBlank() }
    if (scored.size <= 1) return scored.firstOrNull()
    val a = scored[0]; val b = scored[1]
    val oa = oversOf(a.score); val ob = oversOf(b.score)
    return if (oa != null && ob != null && oa < ob) a else b
}

/** The pill's words, in parts so the score can be drawn in the narrow face: lead side, its score, the overs or minute, the other side. */
class PillScore(val lead: LiveTeam?, val score: String, val detail: String?, val other: LiveTeam?, val title: String?)

fun pillScore(event: LiveEvent): PillScore = when (event.kind) {
    LiveEventKind.CRICKET -> {
        val bat = battingSide(event.teams)
        val other = event.teams.firstOrNull { it !== bat }
        val parts = cricketScore(bat?.score)
        if (bat == null || parts == null) PillScore(event.teams.getOrNull(0), "", event.statusText, event.teams.getOrNull(1), null)
        else PillScore(bat, parts.first, parts.second, other, null)
    }
    LiveEventKind.FOOTBALL -> {
        val home = event.teams.getOrNull(0); val away = event.teams.getOrNull(1)
        val score = if (home?.score != null && away?.score != null) "${home.score}–${away.score}" else event.scoreText?.replace(" – ", "–").orEmpty()
        PillScore(home, score, event.statusText, away, null)
    }
    LiveEventKind.SHOW -> PillScore(null, "", null, null, event.title)
}

/** The pill read out whole, for a screen reader and for a check: "IND 176/4 18.2 ov vs AUS, 3 more live". */
fun pillDescription(event: LiveEvent, more: Int): String {
    val p = pillScore(event)
    val main = p.title ?: when (event.kind) {
        LiveEventKind.FOOTBALL -> listOfNotNull(p.lead?.short(), p.score.ifBlank { null }, p.other?.short(), p.detail).joinToString(" ")
        else -> listOfNotNull(p.lead?.short(), p.score.ifBlank { null }, p.detail, "vs", p.other?.short()).joinToString(" ")
    }
    return "Live: $main" + if (more > 0) ", $more more live" else ""
}

/** A row's title: "Arsenal 2 – 1 Chelsea" for a match with a score, "Chennai Super Kings v Mumbai Indians" before it, a show's title. */
fun rowTitle(event: LiveEvent): String {
    val a = event.teams.getOrNull(0); val b = event.teams.getOrNull(1)
    if (a == null || b == null) return event.title
    return when {
        event.kind == LiveEventKind.FOOTBALL && a.score != null && b.score != null -> "${a.name} ${a.score} – ${b.score} ${b.name}"
        else -> "${a.name} v ${b.name}"
    }
}

/** The mono label over the colour card: "Live · 2nd T20I · Australia tour of India". */
fun cardLabel(event: LiveEvent): String {
    val match = event.title.substringAfter(", ", "").takeIf { it.isNotBlank() && event.kind != LiveEventKind.SHOW }
    val where = when (event.status) { LiveEventStatus.LIVE -> "Live"; LiveEventStatus.UPCOMING -> "Coming up"; LiveEventStatus.FINISHED -> "Result" }
    return listOfNotNull(where, match, event.competition ?: if (event.kind == LiveEventKind.SHOW) "Live show" else null).joinToString(" · ")
}

/** How a free stream is named under a card: "on DD Sports · free" for a channel, the option's own label for a YouTube live. */
fun watchLine(option: WatchOption): String = if (option.channel != null) "on ${option.label} · free" else option.label

/** "Today 19:30", "Tomorrow 19:30", "Sat 8 Nov 19:30"; "Starting" once the start has passed. */
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

/** "updated 20 s ago", "updated 3 min ago", or nothing when no time is known. */
fun updatedText(updatedAtMs: Long, nowMs: Long): String? {
    if (updatedAtMs <= 0) return null
    val s = ((nowMs - updatedAtMs) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "updated just now"
        s < 60 -> "updated $s s ago"
        s < 3600 -> "updated ${s / 60} min ago"
        else -> "updated ${s / 3600} h ago"
    }
}

/** The kinds as the filter chips and Settings name them. */
fun LiveEventKind.label(): String = when (this) { LiveEventKind.CRICKET -> "Cricket"; LiveEventKind.FOOTBALL -> "Football"; LiveEventKind.SHOW -> "Shows" }

/** The teams Settings offers as chips before any event has named one: the internationals and the clubs most followed here. */
val SUGGESTED_TEAMS = listOf("India", "Australia", "England", "Pakistan", "CSK", "MI", "RCB", "KKR", "Arsenal", "Chelsea", "Liverpool", "Real Madrid", "Barcelona")
