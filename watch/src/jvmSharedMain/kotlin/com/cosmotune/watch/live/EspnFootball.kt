package com.cosmotune.watch.live

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.str
import com.cosmotune.watch.model.FootballDetail
import com.cosmotune.watch.model.IncidentType
import com.cosmotune.watch.model.LineupPlayer
import com.cosmotune.watch.model.Lineup
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.MatchClock
import com.cosmotune.watch.model.MatchIncident
import com.cosmotune.watch.model.MatchPhase
import com.cosmotune.watch.model.StatPair
import com.cosmotune.watch.model.TimelineEntry
import com.cosmotune.watch.model.TimelineKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Football from ESPN's public site API (keyless, unofficial): each followed league's scoreboard (the current matchday, and
 * yesterday's for the results of the last day), with the clock, scorers and cards; a match's `summary` for its detail sheet.
 * A league with a match in play or about to kick off is read again every 10 s at most (ESPN's own cache), the others every five
 * minutes. Every field is optional: a match the board cannot describe is left out. When ESPN's Bundesliga fails,
 * [openLigaDb] stands in for it.
 */
class EspnFootball(
    private val http: WatchHttp,
    private val base: String = BASE,
    private val leagues: List<League> = LEAGUES,
    private val openLigaDb: OpenLigaDb? = null,
) : ScoreSource {
    /** A competition ESPN has a board for: its [slug] ("eng.1"), the name the Spotlight uses, and its country ("" for a cup of several). */
    data class League(val slug: String, val name: String, val country: String)

    override val name = "ESPN football"

    /** Per league, the next kick-off and whether something is in play, from its last board: how soon it is read again. */
    private val hot = ConcurrentHashMap<String, Pair<Boolean, Long?>>()

    override suspend fun events(nowMs: Long, fresh: Boolean): List<LiveEvent> = coroutineScope {
        val yesterday = Instant.ofEpochMilli(nowMs - DAY).atZone(ZoneOffset.UTC).format(DAY_FORMAT)
        val boards = leagues.map { league ->
            // Each league on its own: one that fails is left out (a failing child would take the others down with it).
            async {
                runCatching {
                    val today = runCatching { board(league, null, nowMs, fresh) }
                    val before = runCatching { board(league, yesterday, nowMs, false) }.getOrDefault(emptyList())
                    val main = today.getOrElse { error ->
                        if (league.slug == "ger.1" && openLigaDb != null) runCatching { openLigaDb.events(nowMs) }.getOrNull() ?: throw error else throw error
                    }
                    main + before
                }
            }
        }.awaitAll()
        if (boards.all { it.isFailure }) throw boards.first().exceptionOrNull()!!
        boards.mapNotNull { it.getOrNull() }.flatten().distinctBy { it.id }
    }

    private suspend fun board(league: League, date: String?, nowMs: Long, fresh: Boolean): List<LiveEvent> {
        val url = "$base/${league.slug}/scoreboard" + (date?.let { "?dates=$it" } ?: "")
        val ttl = when {
            date != null -> HOUR
            fresh -> 0L
            isHot(league.slug, nowMs) -> LIVE_TTL
            else -> IDLE_TTL
        }
        val root = http.getJson(url, ttlMs = ttl, staleOk = false)
        val events = parseScoreboard(root, league, nowMs)
        if (date == null) hot[league.slug] = events.any { it.isLive } to events.filter { it.status == LiveEventStatus.UPCOMING }.mapNotNull { it.startMs }.minOrNull()
        return events
    }

    private fun isHot(slug: String, nowMs: Long): Boolean {
        val (live, next) = hot[slug] ?: return false
        return live || (next != null && nowMs >= next - 10 * 60_000L)
    }

    /** The detail of a match of this source ([LiveEvent.competitionId] is its league): timeline, stats, line-ups. */
    suspend fun detail(event: LiveEvent, nowMs: Long): FootballDetail? {
        val slug = event.competitionId ?: return null
        val id = event.id.substringAfterLast(':')
        val ttl = if (event.isLive) LIVE_TTL else HOUR
        return parseSummary(http.getJson("$base/$slug/summary?event=$id", ttlMs = ttl, staleOk = !event.isLive))
    }

    companion object {
        const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val LIVE_TTL = 10_000L
        private const val IDLE_TTL = 5 * 60_000L
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

        val LEAGUES = listOf(
            League("eng.1", "Premier League", "gb"),
            League("esp.1", "La Liga", "es"),
            League("ita.1", "Serie A", "it"),
            League("ger.1", "Bundesliga", "de"),
            League("fra.1", "Ligue 1", "fr"),
            League("uefa.champions", "Champions League", ""),
            League("uefa.europa", "Europa League", ""),
            League("usa.1", "MLS", "us"),
            League("ind.1", "Indian Super League", "in"),
            League("fifa.world", "World Cup", ""),
            League("fifa.worldq.uefa", "World Cup qualifiers", ""),
            League("fifa.worldq.conmebol", "World Cup qualifiers", ""),
            League("fifa.worldq.afc", "World Cup qualifiers", ""),
            League("fifa.friendly", "International friendly", ""),
        )

        /** Where a match stands, from ESPN's status: its state (`pre`, `in`, `post`), its name and detail, and the period. */
        fun phaseOf(state: String?, name: String?, detail: String?, period: Int?): MatchPhase {
            val n = name.orEmpty().uppercase()
            val d = detail.orEmpty().uppercase()
            if (n.contains("POSTPONED") || n.contains("CANCELED") || n.contains("CANCELLED") || n.contains("ABANDONED") || n.contains("SUSPENDED")) return MatchPhase.POSTPONED
            return when (state) {
                "pre" -> MatchPhase.PRE
                "post" -> MatchPhase.FULL_TIME
                "in" -> when {
                    n.contains("HALFTIME") || d == "HT" || d == "HALFTIME" -> MatchPhase.HALF_TIME
                    n.contains("SHOOTOUT") || d.contains("PEN") -> MatchPhase.PENALTIES
                    n.contains("END_OF_REGULATION") || n.contains("END_OF_EXTRA") || n.contains("END_PERIOD") -> MatchPhase.BREAK
                    n.contains("EXTRA_TIME") || n.contains("OVERTIME") || (period ?: 0) in 3..4 -> MatchPhase.EXTRA_TIME
                    n.contains("SECOND_HALF") || period == 2 -> MatchPhase.SECOND_HALF
                    (period ?: 0) >= 5 -> MatchPhase.PENALTIES
                    else -> MatchPhase.FIRST_HALF
                }
                else -> MatchPhase.PRE
            }
        }

        /** A board's matches, home side first; a postponed or cancelled one is left out. */
        fun parseScoreboard(root: JsonElement, league: League, nowMs: Long): List<LiveEvent> =
            root.list("events").mapNotNull { e -> runCatching { match(e, league, nowMs) }.getOrNull() }

        private fun match(e: JsonElement, league: League, nowMs: Long): LiveEvent? {
            val id = e.str("id") ?: return null
            val comp = e.list("competitions").firstOrNull() ?: return null
            val status = comp.child("status") ?: e.child("status")
            val type = status.child("type")
            val state = type.str("state")
            val period = status.int("period")
            val phase = phaseOf(state, type.str("name"), type.str("detail") ?: type.str("shortDetail"), period)
            if (phase == MatchPhase.POSTPONED) return null
            val competitors = comp.list("competitors").sortedBy { if (it.str("homeAway") == "away") 1 else 0 }
            if (competitors.size != 2) return null
            val started = phase != MatchPhase.PRE
            val teams = competitors.map { c ->
                val t = c.child("team")
                val name = t.str("displayName") ?: t.str("name") ?: return null
                LiveTeam(name, t.str("abbreviation"), t.str("logo"), c.str("score").takeIf { started }, SportPlaces.nation(name)?.takeIf { it.isNotEmpty() && league.country.isEmpty() },
                    id = t.str("id") ?: c.str("id"), colour = t.str("color"))
            }
            val incidents = comp.list("details").mapNotNull { d -> incident(d, teams) }
            val listStatus = when (phase) { MatchPhase.PRE -> LiveEventStatus.UPCOMING; MatchPhase.FULL_TIME -> LiveEventStatus.FINISHED; else -> LiveEventStatus.LIVE }
            val detail = type.str("detail").orEmpty().uppercase()
            val finalText = when {
                detail.contains("PEN") -> "After penalties"
                detail.contains("AET") || detail.contains("EXTRA") -> "After extra time"
                else -> "Full time"
            }
            val clock = MatchClock(phase, status.double("clock"), status.str("displayClock"), period ?: 0, nowMs)
            val start = utcMs(e.str("date") ?: comp.str("date"))
            return LiveEvent(
                id = "football:espn:${league.slug}:$id", kind = LiveEventKind.FOOTBALL, title = "${teams[0].name} v ${teams[1].name}", competition = league.name,
                teams = teams, scoreText = if (started) "${teams[0].score ?: 0} – ${teams[1].score ?: 0}" else null,
                statusText = when (phase) { MatchPhase.FULL_TIME -> finalText; MatchPhase.HALF_TIME -> "Half-time"; else -> null },
                status = listStatus, startMs = start, countries = (listOf(league.country) + teams.mapNotNull { it.country }).filter { it.isNotEmpty() }.distinct(),
                source = "ESPN", updatedAtMs = nowMs, link = "https://www.espn.com/soccer/match/_/gameId/$id", competitionId = league.slug,
                clock = clock, incidents = incidents, venue = comp.child("venue").str("fullName"), major = true,
                endMs = if (phase == MatchPhase.FULL_TIME) start?.plus(115 * 60_000L) else null,
            )
        }

        /** A goal or a card of a board's `details`; anything else (a shoot-out kick, a substitution) is null. */
        private fun incident(d: JsonElement, teams: List<LiveTeam>): MatchIncident? {
            if (d.bool("shootout") == true) return null
            val teamId = d.child("team").str("id")
            val side = teams.indexOfFirst { it.id != null && it.id == teamId }.takeIf { it >= 0 } ?: return null
            val text = d.child("type").str("text").orEmpty()
            val type = when {
                d.bool("ownGoal") == true -> IncidentType.OWN_GOAL
                d.bool("scoringPlay") == true && d.bool("penaltyKick") == true -> IncidentType.PENALTY_GOAL
                d.bool("scoringPlay") == true -> IncidentType.GOAL
                d.bool("redCard") == true -> IncidentType.RED
                d.bool("yellowCard") == true -> IncidentType.YELLOW
                text.contains("Penalty", true) && (text.contains("Miss", true) || text.contains("Saved", true)) -> IncidentType.MISSED_PENALTY
                else -> return null
            }
            val who = d.list("athletesInvolved").firstOrNull()
            val player = who.str("displayName") ?: who.str("shortName") ?: return null
            return MatchIncident(side, d.child("clock").str("displayValue").orEmpty(), player, type)
        }

        /** A match's `summary`: the timeline (goals with the assist, cards, substitutions, half-time), the team stats and the line-ups. */
        fun parseSummary(root: JsonElement): FootballDetail {
            val competitors = root.child("header").list("competitions").firstOrNull().list("competitors").sortedBy { if (it.str("homeAway") == "away") 1 else 0 }
            val ids = competitors.map { it.child("team").str("id") ?: it.str("id") }
            fun sideOf(e: JsonElement?): Int? = e.child("team").str("id")?.let { id -> ids.indexOf(id).takeIf { it >= 0 } }
            var home = 0; var away = 0
            val timeline = root.list("keyEvents").mapNotNull { k ->
                val text = k.child("type").str("text").orEmpty()
                val minute = k.child("clock").str("displayValue").orEmpty()
                val names = k.list("participants").mapNotNull { it.child("athlete").str("displayName") }
                val side = sideOf(k)
                val line = k.str("text")
                when {
                    k.bool("scoringPlay") == true -> {
                        val kind = when {
                            text.contains("Own Goal", true) -> TimelineKind.OWN_GOAL
                            text.contains("Penalty", true) -> TimelineKind.PENALTY_GOAL
                            else -> TimelineKind.GOAL
                        }
                        if (side == 0) home++ else if (side == 1) away++
                        val assist = line?.let { Regex("""Assisted by ([^.]+?)(?: with| following|\.)""").find(it)?.groupValues?.get(1)?.trim() } ?: names.getOrNull(1)
                        TimelineEntry(minute, kind, side, names.firstOrNull(), assist.takeIf { kind == TimelineKind.GOAL }, line)
                    }
                    text.contains("Penalty", true) && (text.contains("Miss", true) || text.contains("Saved", true)) ->
                        TimelineEntry(minute, TimelineKind.MISSED_PENALTY, side, names.firstOrNull(), null, line)
                    text.contains("Red Card", true) -> TimelineEntry(minute, TimelineKind.RED, side, names.firstOrNull(), null, line)
                    text.contains("Yellow Card", true) -> TimelineEntry(minute, TimelineKind.YELLOW, side, names.firstOrNull(), null, line)
                    text.equals("Substitution", true) -> TimelineEntry(minute, TimelineKind.SUB, side, names.getOrNull(0), names.getOrNull(1), line)
                    text.equals("Halftime", true) -> TimelineEntry(minute, TimelineKind.HALF_TIME, null, null, null, "$home–$away")
                    text.contains("End Regular Time", true) || text.equals("Full Time", true) || text.contains("End of Game", true) ->
                        TimelineEntry(minute, TimelineKind.FULL_TIME, null, null, null, "$home–$away")
                    else -> null
                }
            }
            val teamStats = root.child("boxscore").list("teams").sortedBy { t -> ids.indexOf(t.child("team").str("id")).let { if (it < 0) 9 else it } }
            val stats = if (teamStats.size != 2) emptyList() else STATS.mapNotNull { (key, label) ->
                fun value(t: JsonElement) = t.list("statistics").firstOrNull { it.str("name") == key }?.str("displayValue")
                val a = value(teamStats[0]) ?: return@mapNotNull null
                val b = value(teamStats[1]) ?: return@mapNotNull null
                val x = a.toDoubleOrNull() ?: 0.0; val y = b.toDoubleOrNull() ?: 0.0
                val share = if (x + y <= 0.0) 0.5f else (x / (x + y)).toFloat()
                if (key == "possessionPct") StatPair(label, "${Math.round(x)}%", "${Math.round(y)}%", share) else StatPair(label, a, b, share)
            }
            val lineups = root.list("rosters").sortedBy { if (it.str("homeAway") == "away") 1 else 0 }.map { r ->
                val players = r.list("roster").map { p ->
                    p to LineupPlayer(p.child("athlete").str("displayName").orEmpty(), p.str("jersey"), p.child("position").str("abbreviation"),
                        p.bool("subbedIn") == true, p.bool("subbedOut") == true)
                }.filter { it.second.name.isNotEmpty() }
                Lineup(r.child("team").str("displayName").orEmpty(), r.str("formation"),
                    players.filter { it.first.bool("starter") == true }.sortedBy { it.first.int("formationPlace") ?: 99 }.map { it.second },
                    players.filter { it.first.bool("starter") != true }.map { it.second })
            }.filter { it.starters.isNotEmpty() }
            return FootballDetail(timeline, stats, lineups, root.child("gameInfo").int("attendance")?.takeIf { it > 0 })
        }

        private val STATS = listOf("possessionPct" to "Possession", "totalShots" to "Shots", "shotsOnTarget" to "On target",
            "wonCorners" to "Corners", "foulsCommitted" to "Fouls", "offsides" to "Offsides")
    }
}

/**
 * OpenLigaDB (keyless, community kept): the Bundesliga's current matchday with its goals (scorer, minute, penalty, own goal).
 * No clock and no cards; it stands in only when ESPN's Bundesliga board fails.
 */
class OpenLigaDb(private val http: WatchHttp, private val base: String = "https://api.openligadb.de") {
    suspend fun events(nowMs: Long): List<LiveEvent> = parse(http.getJson("$base/getmatchdata/bl1", ttlMs = 60_000L, staleOk = false), nowMs)

    companion object {
        fun parse(root: JsonElement, nowMs: Long): List<LiveEvent> = (root as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { m ->
            val id = m.int("matchID") ?: return@mapNotNull null
            val start = utcMs(m.str("matchDateTimeUTC")) ?: return@mapNotNull null
            val finished = m.bool("matchIsFinished") == true
            val status = when { finished -> LiveEventStatus.FINISHED; start <= nowMs -> LiveEventStatus.LIVE; else -> LiveEventStatus.UPCOMING }
            val goals = m.list("goals")
            var last = 0 to 0
            val incidents = goals.mapNotNull { g ->
                val score = (g.int("scoreTeam1") ?: return@mapNotNull null) to (g.int("scoreTeam2") ?: return@mapNotNull null)
                val side = if (score.first > last.first) 0 else 1
                last = score
                val type = when { g.bool("isOwnGoal") == true -> IncidentType.OWN_GOAL; g.bool("isPenalty") == true -> IncidentType.PENALTY_GOAL; else -> IncidentType.GOAL }
                MatchIncident(side, g.int("matchMinute")?.let { "$it'" }.orEmpty(), g.str("goalGetterName") ?: return@mapNotNull null, type)
            }
            val result = m.list("matchResults").maxByOrNull { it.int("resultOrderID") ?: 0 }
            val score = if (status == LiveEventStatus.UPCOMING) null else (result?.int("pointsTeam1") ?: last.first) to (result?.int("pointsTeam2") ?: last.second)
            val teams = listOf("team1", "team2").mapIndexed { i, key ->
                val t = m.child(key)
                LiveTeam(t.str("teamName") ?: return@mapNotNull null, t.str("shortName"), t.str("teamIconUrl")?.takeUnless { it.endsWith(".svg", true) },
                    score?.let { if (i == 0) it.first.toString() else it.second.toString() })
            }
            LiveEvent(
                id = "football:oldb:bl1:$id", kind = LiveEventKind.FOOTBALL, title = "${teams[0].name} v ${teams[1].name}", competition = "Bundesliga",
                teams = teams, scoreText = score?.let { "${it.first} – ${it.second}" }, statusText = if (finished) "Full time" else null, status = status,
                startMs = start, countries = listOf("de"), source = "OpenLigaDB", updatedAtMs = nowMs, competitionId = "ger.1",
                clock = MatchClock(if (finished) MatchPhase.FULL_TIME else if (status == LiveEventStatus.LIVE) MatchPhase.FIRST_HALF else MatchPhase.PRE, readAtMs = nowMs)
                    .takeIf { status != LiveEventStatus.LIVE },
                incidents = incidents, major = true, endMs = if (finished) start + 115 * 60_000L else null,
            )
        }
    }
}
