package com.cosmotune.watch.model

import kotlinx.serialization.Serializable

/**
 * What a [LiveEvent] is: a cricket match, a football match, a big live show (a DD or official YouTube live special), a news story,
 * or a major event that is coming (an election, a summit).
 */
enum class LiveEventKind { CRICKET, FOOTBALL, SHOW, NEWS, EVENT }

/** Where a [LiveEvent] stands now. A news story is [FEATURED]: it is never live. */
enum class LiveEventStatus { LIVE, UPCOMING, FINISHED, FEATURED }

/** One side of a match. [score] is that side's own score text ("182/4 (18.2 ov)", "2"), null before it starts. */
@Serializable
data class LiveTeam(
    val name: String,
    /** "IND", "MCI"; null when the source gives none. */
    val shortName: String? = null,
    val logoUrl: String? = null,
    val score: String? = null,
    /** ISO 3166-1 alpha-2 lower case for a national side ("in"), null for a club or franchise. */
    val country: String? = null,
    /** Cricket: this side is batting now (the innings in progress is theirs). False when unknown, and for other sports. */
    val batting: Boolean = false,
    /** The source's own id of the side (ESPN's team id), to tie a goal or a card to its side; null when unknown. */
    val id: String? = null,
    /** The side's colour as the source gives it ("d11317"), for the initials when there is no crest. */
    val colour: String? = null,
)

/** Where a football match stands, as its clock reads it. */
enum class MatchPhase { PRE, FIRST_HALF, HALF_TIME, SECOND_HALF, BREAK, EXTRA_TIME, PENALTIES, FULL_TIME, POSTPONED }

/**
 * A football match's clock when it was read ([readAtMs]): its [phase], ESPN's running clock in seconds ([seconds]) and what it
 * showed ([display], "67'", "45'+2'"). Between two reads the minute keeps going on its own (`SpotlightClock`), until half-time or
 * full time.
 */
@Serializable
data class MatchClock(val phase: MatchPhase, val seconds: Double? = null, val display: String? = null, val period: Int = 0, val readAtMs: Long = 0)

/** What happened to a side in a football match, for the card and the timeline. */
enum class IncidentType { GOAL, PENALTY_GOAL, OWN_GOAL, YELLOW, RED, MISSED_PENALTY }

/** A goal or a card: the [side] it counts for (0 home, 1 away; an own goal counts for the other side), the minute and who. */
@Serializable
data class MatchIncident(val side: Int, val minute: String, val player: String, val type: IncidentType, val assist: String? = null) {
    val isGoal: Boolean get() = type == IncidentType.GOAL || type == IncidentType.PENALTY_GOAL || type == IncidentType.OWN_GOAL
}

/**
 * One event of the Spotlight: a match in play, about to start or just finished, a live special, a news story or a major event
 * coming up. Scores come from ESPN's public scoreboards, news from the world's public feeds, events from Wikidata; a source that
 * fails is left out quietly.
 */
@Serializable
data class LiveEvent(
    /** Stable while the event lasts: `cricket:espn:<league>:<id>`, `football:espn:<league>:<id>`, `show:yt:<videoId>`, `news:<key>`. */
    val id: String,
    val kind: LiveEventKind,
    /** "India v Australia, 2nd T20I", "Arsenal v Chelsea", "Republic Day Parade 2027", a headline. */
    val title: String,
    /** "IPL 2026", "Premier League", "ICC Women's World Cup"; the country of a local story; null for a show. */
    val competition: String? = null,
    /** Home (or batting first) side first; empty for a show. */
    val teams: List<LiveTeam> = emptyList(),
    /** The whole score on one line: "IND 182/4 (18.2) · AUS 179/8 (20)", "2 – 1"; null before the start. */
    val scoreText: String? = null,
    /** The source's own status line: "India need 12 runs from 10 balls", "67'", "Half-time", a story's summary. */
    val statusText: String? = null,
    val status: LiveEventStatus,
    /** Epoch ms of the scheduled start (a story's publication); null when unknown. */
    val startMs: Long? = null,
    /** Countries involved, ISO alpha-2 lower case, for ranking by the viewer's country ("in" for an India match or an IPL game). */
    val countries: List<String> = emptyList(),
    /** Languages of a show, ISO 639 ("tam"), for ranking; empty for a match. */
    val languages: List<String> = emptyList(),
    val imageUrl: String? = null,
    /** Free streams our plugins can play, best first; empty when no free stream carries it. */
    val watchOptions: List<WatchOption> = emptyList(),
    /** Who the data came from, for the attribution line ("ESPN", "BBC", "Wikidata", "YouTube"). */
    val source: String,
    /** When this entry was last read from its source. */
    val updatedAtMs: Long = 0,
    val link: String? = null,
    /** The source's id of the competition ("eng.1", a cricket series id), for follows and the match's detail. */
    val competitionId: String? = null,
    /** Football: the clock as read, for the minute that keeps going between reads. */
    val clock: MatchClock? = null,
    /** Football: goals and cards, in the order they happened. */
    val incidents: List<MatchIncident> = emptyList(),
    /** "Anfield", "Basin Reserve, Wellington". */
    val venue: String? = null,
    /** Cricket: the format and the day ("Test · Day 3", "T20"). */
    val format: String? = null,
    /** Cricket: the batters in ("Gill 104*, Jadeja 38*"). */
    val batting: String? = null,
    /** News: the outlets that carry the story, the first one's headline shown. */
    val outlets: List<String> = emptyList(),
    /** A big one: a curated league or an international (a match), a story Wikipedia's editors list as in the news. */
    val major: Boolean = false,
    /** News: a story of the viewer's own country (its own tab), not the world's. */
    val local: Boolean = false,
    /** Epoch ms of the end, when known (a Test's last day, a match's full time); used for "Just finished". */
    val endMs: Long? = null,
) {
    val isLive: Boolean get() = status == LiveEventStatus.LIVE
    val isMatch: Boolean get() = kind == LiveEventKind.FOOTBALL || kind == LiveEventKind.CRICKET
}

/**
 * A free stream our own plugins can play for a [LiveEvent]: DD Sports (Prasar Bharati) or an official broadcaster's YouTube
 * live. Hand [target] to `WatchPlayer.open(target, null)`. Pay services are never listed; an event no free stream carries has
 * no watch option.
 */
@Serializable
data class WatchOption(
    /** "DD Sports", "Live on YouTube · DD News". */
    val label: String,
    val target: PlayTarget,
    /** The channel, when the option is a channel of Live TV (for its logo); null for a single YouTube live video. */
    val channel: Channel? = null,
    /** The plugin badge ("DD", "YT LIVE"). */
    val badge: String? = null,
)

/**
 * How closely the person is looking at the Spotlight, for how often it is read again: the strip alone, the sheet open, or a
 * match's detail open.
 */
enum class LiveFocus { STRIP, SHEET, DETAIL }

/**
 * What the one looking at the Spotlight follows: [follows] says whether an event is one of theirs (its sport, teams or competition;
 * it is listed first), [shows] whether live shows are among the sports followed. [focus] is how closely they look (the cadence of
 * the reads), [refresh] a count that, when it goes up, asks for fresh scores at once (a pull or the refresh button).
 */
class LiveInterest(
    val shows: Boolean = true,
    val focus: LiveFocus = LiveFocus.STRIP,
    val refresh: Int = 0,
    val follows: (LiveEvent) -> Boolean = { true },
) {
    companion object {
        /** Every event, shows included. */
        val ALL = LiveInterest()
    }
}

// ---- a match's detail ----------------------------------------------------------------------------------------------------

/** What a timeline line is. */
enum class TimelineKind { GOAL, PENALTY_GOAL, OWN_GOAL, MISSED_PENALTY, YELLOW, RED, SUB, HALF_TIME, FULL_TIME }

/** One line of a football match's timeline: minute, what, the side (0 home, 1 away), who, and the assist or the player off. */
data class TimelineEntry(val minute: String, val kind: TimelineKind, val side: Int? = null, val player: String? = null, val other: String? = null, val text: String? = null)

/** One stat as paired bars: "Possession" 64% and 36%, [homeShare] the home side's part of the bar. */
data class StatPair(val label: String, val home: String, val away: String, val homeShare: Float)

data class LineupPlayer(val name: String, val number: String? = null, val position: String? = null, val subbedIn: Boolean = false, val subbedOut: Boolean = false)

data class Lineup(val team: String, val formation: String?, val starters: List<LineupPlayer>, val bench: List<LineupPlayer>)

data class FootballDetail(val timeline: List<TimelineEntry>, val stats: List<StatPair>, val lineups: List<Lineup>, val attendance: Int? = null)

data class BatRow(val name: String, val dismissal: String, val runs: String, val balls: String, val fours: String, val sixes: String) {
    val notOut: Boolean get() = dismissal.equals("not out", ignoreCase = true)
}

data class BowlRow(val name: String, val overs: String, val maidens: String, val runs: String, val wickets: String, val economy: String)

data class PartnershipRow(val wicket: String, val first: String, val second: String, val runs: String, val overs: String)

/** One innings: its [team], [total] ("157 (4 wkts; 71 ovs)"), and the batting, bowling and partnership cards the source has. */
data class InningsCard(val number: Int, val team: String, val total: String?, val extras: String?, val batting: List<BatRow>, val bowling: List<BowlRow>,
    val partnerships: List<PartnershipRow>)

/** A cricket match's scorecard: each innings with what the source gives for it, the last balls ("Neser to Smith, no run") and notes. */
data class CricketDetail(val innings: List<InningsCard>, val recentBalls: List<String>, val notes: List<String>, val batters: String? = null)

/** A match's detail (football or cricket), read when its sheet is opened. */
data class MatchDetail(val eventId: String, val football: FootballDetail? = null, val cricket: CricketDetail? = null, val updatedAtMs: Long = 0)
