package com.cosmotune.watch.model

import kotlinx.serialization.Serializable

/** What a [LiveEvent] is: a cricket match, a football match, or a big live show (a DD or official YouTube live special). */
enum class LiveEventKind { CRICKET, FOOTBALL, SHOW }

/** Where a [LiveEvent] stands now. */
enum class LiveEventStatus { LIVE, UPCOMING, FINISHED }

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
)

/**
 * A free stream our own plugins can play for a [LiveEvent]: DD Sports (Prasar Bharati) or an official broadcaster's YouTube
 * live. Hand [target] to `WatchPlayer.open(target, null)`. Pay services are never listed; an event no free stream carries has
 * no watch option and shows its score only.
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
 * One event of the "Live now" strip: a match in play, about to start or just finished, or a live special. Scores come from
 * the free score sources the build is set up with (see WATCH_INTERFACES.md); a source that is not set up is left out quietly.
 */
@Serializable
data class LiveEvent(
    /** Stable while the event lasts: `cricket:<source>:<id>`, `football:<source>:<id>`, `show:yt:<videoId>`. */
    val id: String,
    val kind: LiveEventKind,
    /** "India v Australia, 2nd T20I", "Arsenal v Chelsea", "Republic Day Parade 2027". */
    val title: String,
    /** "IPL 2026", "Premier League", "ICC Women's World Cup"; null for a show. */
    val competition: String? = null,
    /** Home (or batting first) side first; empty for a show. */
    val teams: List<LiveTeam> = emptyList(),
    /** The whole score on one line: "IND 182/4 (18.2) · AUS 179/8 (20)", "2 – 1"; null before the start. */
    val scoreText: String? = null,
    /** The source's own status line: "India need 12 runs from 10 balls", "67'", "Half time", "Starts 19:30". */
    val statusText: String? = null,
    val status: LiveEventStatus,
    /** Epoch ms of the scheduled start; null when unknown. */
    val startMs: Long? = null,
    /** Countries involved, ISO alpha-2 lower case, for ranking by the viewer's country ("in" for an India match or an IPL game). */
    val countries: List<String> = emptyList(),
    /** Languages of a show, ISO 639 ("tam"), for ranking; empty for a match. */
    val languages: List<String> = emptyList(),
    val imageUrl: String? = null,
    /** Free streams our plugins can play, best first; empty when no free stream carries it. */
    val watchOptions: List<WatchOption> = emptyList(),
    /** Who the data came from, for the attribution line ("CricketData.org", "football-data.org", "TheSportsDB", "YouTube"). */
    val source: String,
    /** When this entry was last read from its source. */
    val updatedAtMs: Long = 0,
) {
    val isLive: Boolean get() = status == LiveEventStatus.LIVE
}
