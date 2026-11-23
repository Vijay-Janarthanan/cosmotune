package com.cosmotune.watch.live

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.str
import com.cosmotune.watch.model.BatRow
import com.cosmotune.watch.model.BowlRow
import com.cosmotune.watch.model.CricketDetail
import com.cosmotune.watch.model.InningsCard
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.PartnershipRow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Cricket from ESPN's public API (keyless, unofficial): the live cricket header finds every match in play, about to start or just
 * done; internationals and the big leagues are kept (and the big countries' own first-class and T20 competitions, not counted as
 * big). For a match in play (the big ones first) its `summary` adds the batters at the crease and the session; opening a match reads its scorecard.
 */
class EspnCricket(
    private val http: WatchHttp,
    private val header: String = HEADER,
    private val base: String = BASE,
    /** How many matches in play have their batters read each time (each `summary` is 60 to 300 KB). */
    private val enrich: Int = 3,
) : ScoreSource {
    override val name = "ESPN cricket"

    override suspend fun events(nowMs: Long, fresh: Boolean): List<LiveEvent> {
        val root = http.getJson(header, ttlMs = if (fresh) 0L else LIVE_TTL, staleOk = false)
        val events = parseHeader(root, nowMs)
        val live = events.filter { it.isLive }.sortedByDescending { it.major }.take(enrich).map { it.id }.toSet()
        if (live.isEmpty()) return events
        return coroutineScope {
            events.map { e ->
                if (e.id !in live) async { e } else async {
                    runCatching { http.getJson(summaryUrl(e) ?: return@async e, ttlMs = if (fresh) 0L else LIVE_TTL, staleOk = false) }.getOrNull()
                        ?.let { enrich(e, it) } ?: e
                }
            }.awaitAll()
        }
    }

    private fun summaryUrl(e: LiveEvent): String? {
        val league = e.competitionId ?: return null
        return "$base/$league/summary?event=${e.id.substringAfterLast(':')}"
    }

    /** The scorecard of a match of this source. */
    suspend fun detail(event: LiveEvent): CricketDetail? {
        val url = summaryUrl(event) ?: return null
        return parseSummary(http.getJson(url, ttlMs = if (event.isLive) LIVE_TTL else HOUR, staleOk = !event.isLive))
    }

    companion object {
        const val HEADER = "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket"
        const val BASE = "https://site.api.espn.com/apis/site/v2/sports/cricket"
        private const val HOUR = 3_600_000L
        private const val LIVE_TTL = 10_000L

        /** The header's matches: internationals, the big leagues and the big countries' own competitions; the rest is left out. */
        fun parseHeader(root: JsonElement, nowMs: Long): List<LiveEvent> = root.list("sports").flatMap { it.list("leagues") }.flatMap { league ->
            league.list("events").mapNotNull { e -> runCatching { match(e, league, nowMs) }.getOrNull() }
        }

        private fun match(e: JsonElement, league: JsonElement, nowMs: Long): LiveEvent? {
            val id = e.str("id") ?: return null
            val leagueId = league.str("id") ?: return null
            val leagueName = league.str("name") ?: e.child("group").str("abbreviation") ?: ""
            val competitors = e.list("competitors")
            if (competitors.size != 2) return null
            val names = competitors.map { it.str("displayName") ?: return null }
            val international = e.child("class").str("internationalClassId").let { it != null && it != "0" }
            val nations = names.map(SportPlaces::nation)
            val big = SportPlaces.cricketLeague(leagueName)
            val domestic = SportPlaces.cricketDomestic(leagueName)
            val major = (international && nations.all { it != null }) || big != null
            if (!major && domestic == null) return null
            val state = e.str("status")
            val status = when (state) { "in" -> LiveEventStatus.LIVE; "post" -> LiveEventStatus.FINISHED; "pre" -> LiveEventStatus.UPCOMING; else -> return null }
            val full = e.child("fullStatus")
            val battingId = full.str("battingTeamId")?.takeIf { it != "0" }
            val teams = competitors.mapIndexed { i, c ->
                LiveTeam(names[i], c.str("abbreviation") ?: c.str("name"), c.str("logo"), c.str("score"), nations[i]?.takeIf { it.isNotEmpty() },
                    batting = status == LiveEventStatus.LIVE && battingId != null && c.str("id") == battingId, id = c.str("id"), colour = c.str("color"))
            }
            val card = e.child("class").str("generalClassCard")
            val day = full.int("dayNumber")?.takeIf { it > 0 && status == LiveEventStatus.LIVE && card in MULTI_DAY }
            val statusLine = full.str("longSummary") ?: full.str("summary") ?: e.str("summary")
            val countries = (nations.filterNotNull() + listOfNotNull(big?.second, domestic?.second)).filter { it.isNotEmpty() }.distinct()
            val matchTitle = e.str("title")?.takeIf { it.isNotBlank() }
            return LiveEvent(
                id = "cricket:espn:$leagueId:$id", kind = LiveEventKind.CRICKET,
                title = names.joinToString(" v ") + (matchTitle?.let { ", $it" } ?: ""), competition = leagueName.ifBlank { null }, teams = teams,
                scoreText = teams.filter { !it.score.isNullOrBlank() }.joinToString(" · ") { "${it.shortName ?: it.name} ${it.score}" }.ifBlank { null },
                statusText = statusLine?.takeUnless { it.equals("Live", true) || it.equals("Scheduled", true) || it.equals("Result", true) },
                status = status, startMs = utcMs(e.str("date")), countries = countries, source = "ESPN", updatedAtMs = nowMs, link = e.str("link"),
                competitionId = leagueId, venue = e.str("location"), format = listOfNotNull(card?.let(::formatName), day?.let { "Day $it" }).joinToString(" · ").ifBlank { null },
                major = major, endMs = utcMs(e.str("endDate"))?.let { if (status == LiveEventStatus.FINISHED) minOf(it, nowMs) else it },
            )
        }

        private val MULTI_DAY = setOf("Test", "First-class")

        private fun formatName(card: String): String = when (card) { "Twenty20" -> "T20"; "Twenty20 International" -> "T20I"; else -> card }

        /** A live match's card: the batters at the crease and the session, and the status line the summary has, from its `summary`. */
        fun enrich(e: LiveEvent, summary: JsonElement): LiveEvent {
            val comp = summary.child("header").list("competitions").firstOrNull()
            val status = comp.child("status")
            val session = status.str("session")?.replace(Regex("\\s+"), " ")?.trim()
            val line = status.str("summary")?.takeUnless { it.equals("Live", true) }
            val card = comp.child("class").str("generalClassCard")?.let(::formatName)
            return e.copy(
                batting = batters(comp) ?: e.batting,
                statusText = line ?: e.statusText,
                format = listOfNotNull(card, session).joinToString(" · ").ifBlank { e.format },
            )
        }

        /** "Smith 6*, Philippe 45*", from the latest ball: the batter on strike first. */
        private fun batters(comp: JsonElement?): String? {
            val latest = commentaries(comp).firstOrNull() ?: return null
            val who = listOf("batsman", "otherBatsman").mapNotNull { k ->
                val b = latest.child(k) ?: return@mapNotNull null
                val name = b.child("athlete").str("lastName") ?: b.child("athlete").str("displayName")?.substringAfterLast(' ') ?: return@mapNotNull null
                "$name ${b.int("totalRuns") ?: 0}*"
            }
            return who.joinToString(", ").ifBlank { null }
        }

        private fun commentaries(comp: JsonElement?): List<JsonElement> {
            val c = comp.child("commentaries")
            val list = (c as? JsonObject)?.values?.toList() ?: c.let { (it as? kotlinx.serialization.json.JsonArray)?.toList() }.orEmpty()
            return list.sortedByDescending { it.long("sequence") ?: it.long("id") ?: 0L }
        }

        /** A match's scorecard from its `summary`: each innings (cards where the source has them), the last balls and the notes. */
        fun parseSummary(root: JsonElement): CricketDetail {
            val comp = root.child("header").list("competitions").firstOrNull()
            val cards = root.list("matchcards")
            val byInnings = cards.groupBy { it.str("inningsNumber")?.toIntOrNull() ?: 0 }
            // An innings the source scores but has no card for: its total from the side's line scores.
            val fromLines = comp.list("competitors").flatMap { c ->
                val team = c.child("team").str("displayName") ?: c.child("team").str("name") ?: ""
                c.list("linescores").filter { it.bool("isBatting") == true }.mapNotNull { l ->
                    val n = l.int("period") ?: return@mapNotNull null
                    val runs = l.int("runs") ?: 0; val wickets = l.int("wickets") ?: 0
                    val overs = l.double("overs")?.let { o -> if (o == Math.floor(o)) o.toLong().toString() else "%.1f".format(Locale.ROOT, o) }
                    val desc = l.str("description")
                    val total = buildString {
                        append(runs); if (wickets < 10) append("/").append(wickets)
                        if (desc.equals("declared", true)) append("d")
                        overs?.let { append(" (").append(it).append(" ov)") }
                    }
                    n to (team to total)
                }
            }.toMap()
            val numbers = (byInnings.keys.filter { it > 0 } + fromLines.keys).distinct().sorted()
            val innings = numbers.map { n ->
                val list = byInnings[n].orEmpty()
                val bat = list.firstOrNull { it.str("headline") == "Batting" }
                val bowl = list.firstOrNull { it.str("headline") == "Bowling" }
                val parts = list.firstOrNull { it.str("headline") == "Partnerships" }
                val team = bat?.str("teamName") ?: parts?.str("teamName") ?: fromLines[n]?.first ?: ""
                val total = bat?.let { b -> listOfNotNull(b.str("runs"), b.str("total")).joinToString(" ").ifBlank { null } } ?: fromLines[n]?.second
                InningsCard(
                    n, team, total, bat?.str("extras"),
                    bat?.list("playerDetails").orEmpty().mapNotNull { p ->
                        val name = p.str("playerName") ?: return@mapNotNull null
                        val runs = p.str("runs") ?: return@mapNotNull null // did not bat
                        BatRow(name, p.str("dismissal").orEmpty(), runs, p.str("ballsFaced").orEmpty(), p.str("fours").orEmpty(), p.str("sixes").orEmpty())
                    },
                    bowl?.list("playerDetails").orEmpty().mapNotNull { p ->
                        BowlRow(p.str("playerName") ?: return@mapNotNull null, p.str("overs").orEmpty(), p.str("maidens").orEmpty(), p.str("conceded").orEmpty(),
                            p.str("wickets").orEmpty(), p.str("economyRate").orEmpty())
                    },
                    parts?.list("playerDetails").orEmpty().mapNotNull { p ->
                        PartnershipRow(p.str("partnershipWicketName") ?: return@mapNotNull null, "${p.str("player1Name").orEmpty()} ${p.str("player1Runs").orEmpty()}".trim(),
                            "${p.str("player2Name").orEmpty()} ${p.str("player2Runs").orEmpty()}".trim(), p.str("partnershipRuns").orEmpty(), p.str("partnershipOvers").orEmpty())
                    },
                )
            }
            val balls = commentaries(comp).take(12).mapNotNull { c ->
                val text = c.str("shortText") ?: return@mapNotNull null
                val over = c.child("over").double("actual")?.let { "%.1f".format(Locale.ROOT, it) }
                listOfNotNull(over, text).joinToString(" ")
            }
            val notes = root.list("notes").filter { it.str("type") in setOf("toss", "closeofplay", "matchnote") }.mapNotNull { it.str("text") }.takeLast(8)
            return CricketDetail(innings, balls, notes, batters(comp))
        }
    }
}
