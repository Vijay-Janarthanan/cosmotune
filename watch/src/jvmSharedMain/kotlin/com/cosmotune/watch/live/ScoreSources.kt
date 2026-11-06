package com.cosmotune.watch.live

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.double
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.strings
import com.cosmotune.watch.http.urlEncode
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The free score services' keys, from `local.properties` or the environment (the same way as `TMDB_API_KEY`):
 * - `CRICKETDATA_API_KEY`: CricketData.org (CricAPI), a free key from cricketdata.org (100 calls a day), for live cricket;
 * - `FOOTBALL_DATA_API_KEY`: football-data.org, a free key from football-data.org/client/register (the top 12
 *   competitions, 10 calls a minute), for live football;
 * - `THESPORTSDB_API_KEY`: optional; TheSportsDB's free public test key is used without one. TheSportsDB is the fallback
 *   for a sport whose key is missing: each league's next match and last result, no running score.
 * A blank key leaves that service out; nothing fails.
 */
data class LiveScoreKeys(val cricketData: String? = null, val footballData: String? = null, val theSportsDb: String? = null) {
    val cricket: String? get() = cricketData?.trim()?.takeIf { it.isNotEmpty() }
    val football: String? get() = footballData?.trim()?.takeIf { it.isNotEmpty() }
    val sportsDb: String get() = theSportsDb?.trim()?.takeIf { it.isNotEmpty() } ?: SportsDbScores.FREE_KEY

    companion object {
        const val CRICKETDATA = "CRICKETDATA_API_KEY"
        const val FOOTBALL_DATA = "FOOTBALL_DATA_API_KEY"
        const val THESPORTSDB = "THESPORTSDB_API_KEY"

        /** The keys from system properties (`-Dcosmotune.cricketdata.key`, ...) or the environment. */
        fun fromSystem(): LiveScoreKeys {
            fun read(property: String, env: String) = System.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(env)
            return LiveScoreKeys(read("cosmotune.cricketdata.key", CRICKETDATA), read("cosmotune.footballdata.key", FOOTBALL_DATA),
                read("cosmotune.thesportsdb.key", THESPORTSDB))
        }
    }
}

/** One free score service. [events] answers what it knows now (cached by [WatchHttp] for its own polite interval). */
interface ScoreSource {
    val name: String
    suspend fun events(nowMs: Long): List<LiveEvent>
}

/** Reads `2026-11-06T14:00:00`, `2026-11-06T14:00:00Z` or `2026-11-06T14:00:00+00:00` as UTC epoch ms. */
internal fun utcMs(text: String?): Long? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/** Which countries a match belongs to, for ranking: national sides by name, leagues by where they are played. */
internal object SportPlaces {
    val NATIONS = mapOf(
        "india" to "in", "australia" to "au", "england" to "gb", "pakistan" to "pk", "south africa" to "za", "new zealand" to "nz",
        "sri lanka" to "lk", "bangladesh" to "bd", "west indies" to "", "afghanistan" to "af", "ireland" to "ie", "zimbabwe" to "zw",
        "netherlands" to "nl", "scotland" to "gb", "nepal" to "np", "united arab emirates" to "ae", "uae" to "ae", "oman" to "om",
        "united states of america" to "us", "usa" to "us", "namibia" to "na", "canada" to "ca",
    )

    /** A cricket league (or ICC event) by a word in the match or series name, and the country it is played in ("" for several). */
    val CRICKET_LEAGUES = listOf(
        "indian premier league" to "in", "ipl" to "in", "women's premier league" to "in", "wpl" to "in", "big bash" to "au",
        "pakistan super league" to "pk", "caribbean premier league" to "", "sa20" to "za", "the hundred" to "gb",
        "lanka premier league" to "lk", "bangladesh premier league" to "bd", "international league t20" to "ae", "major league cricket" to "us",
        "icc" to "", "world cup" to "", "champions trophy" to "", "asia cup" to "", "world test championship" to "",
    )

    /** football-data.org competition codes and TheSportsDB league ids to their country ("" for a European or world cup). */
    val FOOTBALL_CODES = mapOf("PL" to "gb", "ELC" to "gb", "PD" to "es", "SA" to "it", "BL1" to "de", "FL1" to "fr", "DED" to "nl",
        "PPL" to "pt", "BSA" to "br", "CL" to "", "EC" to "", "WC" to "", "CLI" to "")

    /** A national side's country ("India Women" is India's), null for a club, a franchise, an A side or an under-19 side. */
    fun nation(team: String): String? {
        val t = team.lowercase().trim().removeSuffix(" women").removeSuffix(" w").trim()
        return NATIONS[t]
    }

    fun cricketLeague(name: String): Pair<String, String>? {
        val n = name.lowercase()
        return CRICKET_LEAGUES.firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(n) }
    }
}

/**
 * CricketData.org (CricAPI v1) `currentMatches`: the matches in play, just finished and about to start, with each innings'
 * runs, wickets and overs and the status line. Only internationals between full and leading associate members and the
 * major leagues (IPL, WPL, Big Bash, PSL, SA20, The Hundred, ICC events) are kept. The free key allows 100 calls a day, so
 * the answer is kept 15 minutes.
 */
class CricketDataScores(private val http: WatchHttp, private val key: String, private val base: String = "https://api.cricapi.com/v1",
    private val ttlMs: Long = 15 * 60_000L) : ScoreSource {
    override val name = "CricketData.org"

    override suspend fun events(nowMs: Long): List<LiveEvent> {
        val root = http.getJson("$base/currentMatches?apikey=${key.urlEncode()}&offset=0", ttlMs = ttlMs)
        if (root.str("status")?.equals("success", true) == false) error(root.str("reason") ?: "CricketData.org refused the request")
        return root.list("data").mapNotNull { m -> match(m, nowMs) }
    }

    private fun match(m: kotlinx.serialization.json.JsonElement, nowMs: Long): LiveEvent? {
        val id = m.str("id") ?: return null
        val fullName = m.str("name") ?: return null
        val teamNames = m.strings("teams").ifEmpty { fullName.substringBefore(',').split(Regex("\\s+vs?\\.?\\s+")).map { it.trim() } }
        if (teamNames.size != 2) return null
        val nations = teamNames.map(SportPlaces::nation)
        val league = SportPlaces.cricketLeague(fullName) ?: m.str("series")?.let(SportPlaces::cricketLeague)
        if (league == null && nations.any { it == null }) return null
        val info = m.list("teamInfo")
        val innings = m.list("score")
        val started = m.bool("matchStarted") == true
        val ended = m.bool("matchEnded") == true
        // The innings listed last is the one in progress while the match is on.
        val battingNow = innings.lastOrNull()?.str("inning")?.let { last -> teamNames.filter { last.startsWith(it, ignoreCase = true) }.maxByOrNull { it.length } }
        val teams = teamNames.mapIndexed { i, team ->
            val ti = info.firstOrNull { it.str("name").equals(team, true) }
            val mine = innings.filter { it.str("inning")?.startsWith(team, ignoreCase = true) == true }
            val score = mine.joinToString(" & ") { inn ->
                val r = inn.int("r") ?: 0; val w = inn.int("w"); val o = inn.double("o")
                buildString { append(r); if (w != null && w < 10) append("/").append(w); if (o != null) append(" (").append(overs(o)).append(" ov)") }
            }.ifBlank { null }
            LiveTeam(team, ti?.str("shortname"), ti?.str("img")?.takeUnless { it.contains("icon512") }, score, nations[i]?.takeIf { it.isNotEmpty() })
        }.map { it.copy(batting = started && !ended && it.name == battingNow) }
        val status = when { ended -> LiveEventStatus.FINISHED; started -> LiveEventStatus.LIVE; else -> LiveEventStatus.UPCOMING }
        val scoreText = teams.filter { it.score != null }.joinToString(" · ") { "${it.shortName ?: it.name} ${it.score!!.replace(" ov)", ")")}" }.ifBlank { null }
        val segments = fullName.split(',').map { it.trim() }
        val countries = (nations.filterNotNull() + listOfNotNull(league?.second)).filter { it.isNotEmpty() }.distinct()
        return LiveEvent(
            id = "cricket:cricapi:$id", kind = LiveEventKind.CRICKET, title = segments.first().replace(Regex("\\s+vs\\s+"), " v "),
            competition = segments.drop(1).joinToString(", ").ifBlank { m.str("matchType")?.uppercase() }, teams = teams, scoreText = scoreText,
            statusText = m.str("status")?.takeUnless { it.equals("Match not started", true) }, status = status,
            startMs = utcMs(m.str("dateTimeGMT")), countries = countries, source = name, updatedAtMs = nowMs,
        )
    }

    /** 18.2 overs stay "18.2"; 20.0 reads "20". */
    private fun overs(o: Double): String = if (o == Math.floor(o)) o.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, o)
}

/**
 * football-data.org v4 `/matches` from yesterday to tomorrow (UTC): the top competitions the free tier covers (Premier
 * League, La Liga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, Championship, Brasileirão, Champions League, the
 * Euros and the World Cup), with the running score. The free tier allows 10 calls a minute; the answer is kept 2 minutes.
 */
class FootballDataScores(private val http: WatchHttp, private val key: String, private val base: String = "https://api.football-data.org/v4",
    private val ttlMs: Long = 2 * 60_000L) : ScoreSource {
    override val name = "football-data.org"

    override suspend fun events(nowMs: Long): List<LiveEvent> {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        val url = "$base/matches?dateFrom=${today.minusDays(1)}&dateTo=${today.plusDays(1)}"
        val root = http.getJson(url, headers = mapOf("X-Auth-Token" to key), ttlMs = ttlMs)
        return root.list("matches").mapNotNull { m ->
            val id = m.int("id") ?: return@mapNotNull null
            val status = when (m.str("status")) {
                "IN_PLAY", "PAUSED", "LIVE" -> LiveEventStatus.LIVE
                "FINISHED", "AWARDED" -> LiveEventStatus.FINISHED
                "SCHEDULED", "TIMED" -> LiveEventStatus.UPCOMING
                else -> return@mapNotNull null // postponed, suspended, cancelled
            }
            val competition = m.child("competition")
            val code = competition.str("code")
            val full = m.child("score").child("fullTime")
            val home = full.int("home"); val away = full.int("away")
            val started = status != LiveEventStatus.UPCOMING && home != null && away != null
            fun team(t: kotlinx.serialization.json.JsonElement?, score: Int?) = LiveTeam(t.str("shortName") ?: t.str("name") ?: "?", t.str("tla"), t.str("crest"),
                score?.takeIf { started }?.toString())
            val teams = listOf(team(m.child("homeTeam"), home), team(m.child("awayTeam"), away))
            LiveEvent(
                id = "football:fd:$id", kind = LiveEventKind.FOOTBALL, title = "${teams[0].name} v ${teams[1].name}", competition = competition.str("name"),
                teams = teams, scoreText = if (started) "$home – $away" else null,
                statusText = when {
                    m.str("status") == "PAUSED" -> "Half time"
                    status == LiveEventStatus.LIVE -> m.int("minute")?.let { "$it'" } ?: "Live"
                    status == LiveEventStatus.FINISHED -> "Full time"
                    else -> null
                },
                status = status, startMs = utcMs(m.str("utcDate")), countries = listOfNotNull(code?.let { SportPlaces.FOOTBALL_CODES[it] }?.takeIf { it.isNotEmpty() }),
                imageUrl = competition.str("emblem"), source = name, updatedAtMs = nowMs,
            )
        }
    }
}

/**
 * TheSportsDB v1 (the free public test key, or the owner's own key): each league's next match and last result, for a sport
 * no keyed service covers. The free key gives one of each per league and no running score, so a match in play shows its
 * status only. Kept 30 minutes; at most two calls per league.
 */
class SportsDbScores(private val http: WatchHttp, private val key: String, private val leagues: List<League>, private val base: String = "https://www.thesportsdb.com/api/v1/json",
    private val ttlMs: Long = 30 * 60_000L) : ScoreSource {
    data class League(val id: Int, val kind: LiveEventKind, val country: String)

    override val name = "TheSportsDB"

    override suspend fun events(nowMs: Long): List<LiveEvent> = coroutineScope {
        leagues.flatMap { league -> listOf("eventsnextleague", "eventspastleague").map { call -> league to call } }.map { (league, call) ->
            async { runCatching { http.getJson("$base/${key.urlEncode()}/$call.php?id=${league.id}", ttlMs = ttlMs).list("events").mapNotNull { event(it, league, nowMs) } }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    private fun event(e: kotlinx.serialization.json.JsonElement, league: League, nowMs: Long): LiveEvent? {
        val id = e.str("idEvent") ?: return null
        val homeName = e.str("strHomeTeam") ?: return null
        val awayName = e.str("strAwayTeam") ?: return null
        val code = e.str("strStatus")?.trim().orEmpty()
        val start = utcMs(e.str("strTimestamp")) ?: e.str("dateEvent")?.let { d -> runCatching { LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
        val homeScore = e.str("intHomeScore"); val awayScore = e.str("intAwayScore")
        val status = when {
            code.uppercase() in FINISHED || code.equals("Match Finished", true) -> LiveEventStatus.FINISHED
            code.uppercase() in LIVE || code.equals("In Progress", true) -> LiveEventStatus.LIVE
            code.uppercase() in NOT_STARTED -> LiveEventStatus.UPCOMING
            homeScore != null && awayScore != null -> LiveEventStatus.FINISHED
            start != null && start > nowMs -> LiveEventStatus.UPCOMING
            else -> return null
        }
        val showScore = status != LiveEventStatus.UPCOMING && homeScore != null && awayScore != null
        val teams = listOf(LiveTeam(homeName, logoUrl = e.str("strHomeTeamBadge"), score = homeScore.takeIf { showScore }),
            LiveTeam(awayName, logoUrl = e.str("strAwayTeamBadge"), score = awayScore.takeIf { showScore }))
        return LiveEvent(
            id = "${if (league.kind == LiveEventKind.CRICKET) "cricket" else "football"}:tsdb:$id", kind = league.kind, title = "$homeName v $awayName",
            competition = e.str("strLeague"), teams = teams,
            scoreText = if (showScore) (if (league.kind == LiveEventKind.FOOTBALL) "$homeScore – $awayScore" else "$homeName $homeScore · $awayName $awayScore") else null,
            statusText = e.str("strResult")?.takeIf { status == LiveEventStatus.FINISHED } ?: when (code.uppercase()) {
                "HT" -> "Half time"; "FT", "AET", "PEN" -> "Full time"; else -> null
            },
            status = status, startMs = start, countries = listOf(league.country).filter { it.isNotEmpty() }, imageUrl = e.str("strThumb"),
            source = name, updatedAtMs = nowMs,
        )
    }

    companion object {
        /** TheSportsDB's published free test key (the old "3" was retired). */
        const val FREE_KEY = "123"
        private val FINISHED = setOf("FT", "AET", "PEN", "AP", "AW", "FINISHED")
        private val LIVE = setOf("1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT")
        private val NOT_STARTED = setOf("NS", "TBD", "NOT STARTED")

        val FOOTBALL = listOf(
            League(4328, LiveEventKind.FOOTBALL, "gb"), // Premier League
            League(4335, LiveEventKind.FOOTBALL, "es"), // La Liga
            League(4332, LiveEventKind.FOOTBALL, "it"), // Serie A
            League(4331, LiveEventKind.FOOTBALL, "de"), // Bundesliga
            League(4334, LiveEventKind.FOOTBALL, "fr"), // Ligue 1
            League(4480, LiveEventKind.FOOTBALL, ""), // Champions League
            League(4791, LiveEventKind.FOOTBALL, "in"), // Indian Super League
        )
        val CRICKET = listOf(League(4460, LiveEventKind.CRICKET, "in")) // Indian Premier League
    }
}
