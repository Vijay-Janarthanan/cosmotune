package com.cosmotune.watch

import com.cosmotune.watch.live.EspnCricket
import com.cosmotune.watch.live.EspnFootball
import com.cosmotune.watch.live.MajorEvents
import com.cosmotune.watch.live.NewsFeeds
import com.cosmotune.watch.live.OpenLigaDb
import com.cosmotune.watch.model.IncidentType
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.MatchPhase
import com.cosmotune.watch.model.TimelineKind
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Spotlight's parsers on answers fetched on 23 November 2026 (trimmed, under `resources/spotlight`). */
class SpotlightSourcesTest {
    private val now = Instant.parse("2026-11-23T05:00:00Z").toEpochMilli()
    private val premierLeague = EspnFootball.League("eng.1", "Premier League", "gb")

    private fun text(name: String) = javaClass.getResource("/spotlight/$name")!!.readText()
    private fun json(name: String) = Json.parseToJsonElement(text(name))

    // ---- football -----------------------------------------------------------------------------------------------------------

    @Test fun aFootballBoardGivesFullNamesCrestsScorersAndCards() {
        val events = EspnFootball.parseScoreboard(json("espn-football-scoreboard.json"), premierLeague, now)
        assertEquals(listOf("Hull City v Brighton & Hove Albion", "Liverpool v Manchester United"), events.map { it.title }, "home side first, full names")
        val liverpool = events[1]
        assertEquals(LiveEventStatus.FINISHED, liverpool.status)
        assertEquals("football:espn:eng.1:401878755", liverpool.id)
        assertEquals(listOf("4", "0"), liverpool.teams.map { it.score })
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/364.png", liverpool.teams[0].logoUrl, "the crest, not initials")
        assertEquals("LIV", liverpool.teams[0].shortName)
        assertEquals("Full time", liverpool.statusText)
        assertEquals(MatchPhase.FULL_TIME, liverpool.clock?.phase)
        assertEquals("Anfield", liverpool.venue)
        assertEquals("Premier League", liverpool.competition)
        assertTrue(liverpool.major)
        val goals = liverpool.incidents.filter { it.isGoal }
        assertEquals(4, goals.size)
        assertEquals("Dominik Szoboszlai" to "10'", goals[0].player to goals[0].minute)
        assertTrue(goals.all { it.side == 0 }, "every goal Liverpool's")
        val red = liverpool.incidents.single { it.type == IncidentType.RED }
        assertEquals(1 to "Leny Yoro", red.side to red.player)
        assertTrue(liverpool.incidents.any { it.type == IncidentType.YELLOW && it.side == 0 })
    }

    @Test fun aMatchInPlayIsLiveWithItsClockAndHalfTimeIsSaidAsIs() {
        val live = text("espn-football-scoreboard.json")
            .replace("\"STATUS_FULL_TIME\"", "\"STATUS_SECOND_HALF\"").replace("\"state\": \"post\"", "\"state\": \"in\"")
            .replace("\"displayClock\": \"90'+6'\"", "\"displayClock\": \"67'\"").replace("\"displayClock\": \"90'+3'\"", "\"displayClock\": \"67'\"")
            .replace("\"clock\": 5400.0", "\"clock\": 3990.0")
        val match = EspnFootball.parseScoreboard(Json.parseToJsonElement(live), premierLeague, now)[1]
        assertEquals(LiveEventStatus.LIVE, match.status)
        assertEquals(MatchPhase.SECOND_HALF, match.clock?.phase)
        assertEquals(3990.0, match.clock?.seconds)
        assertEquals(now, match.clock?.readAtMs)

        assertEquals(MatchPhase.HALF_TIME, EspnFootball.phaseOf("in", "STATUS_HALFTIME", "HT", 1))
        assertEquals(MatchPhase.FIRST_HALF, EspnFootball.phaseOf("in", "STATUS_FIRST_HALF", "23'", 1))
        assertEquals(MatchPhase.SECOND_HALF, EspnFootball.phaseOf("in", "STATUS_IN_PROGRESS", "67'", 2), "an unknown name falls back on the period")
        assertEquals(MatchPhase.EXTRA_TIME, EspnFootball.phaseOf("in", "STATUS_EXTRA_TIME", "98'", 3))
        assertEquals(MatchPhase.PENALTIES, EspnFootball.phaseOf("in", "STATUS_SHOOTOUT", "PEN", 5))
        assertEquals(MatchPhase.FULL_TIME, EspnFootball.phaseOf("post", "STATUS_FINAL_AET", "AET", 4))
        assertEquals(MatchPhase.POSTPONED, EspnFootball.phaseOf("post", "STATUS_POSTPONED", "Postponed", 0))
        assertEquals(MatchPhase.PRE, EspnFootball.phaseOf("pre", "STATUS_SCHEDULED", "Tue 8:00 PM", 0))
    }

    @Test fun matchesComingUpHaveTheirKickOffAndVenueAndNoScore() {
        val ucl = EspnFootball.League("uefa.champions", "Champions League", "")
        val events = EspnFootball.parseScoreboard(json("espn-football-upcoming.json"), ucl, now)
        assertEquals(3, events.size)
        val arsenal = events.single { it.title == "Arsenal v Borussia Dortmund" }
        assertEquals(LiveEventStatus.UPCOMING, arsenal.status)
        assertEquals(Instant.parse("2026-11-24T20:00:00Z").toEpochMilli(), arsenal.startMs)
        assertEquals("Emirates Stadium", arsenal.venue)
        assertTrue(arsenal.teams.all { it.score == null })
        assertNull(arsenal.scoreText)
    }

    @Test fun aMatchSummaryGivesTheTimelineWithAssistsTheStatsAndTheLineUps() {
        val detail = EspnFootball.parseSummary(json("espn-football-summary.json"))
        assertEquals(TimelineKind.RED, detail.timeline.first().kind)
        val wirtz = detail.timeline.first { it.kind == TimelineKind.GOAL && it.player == "Florian Wirtz" }
        assertEquals("13'" to "Rio Ngumoha", wirtz.minute to wirtz.other, "the assist")
        assertEquals(0, wirtz.side)
        assertEquals("2–0", detail.timeline.single { it.kind == TimelineKind.HALF_TIME }.text)
        assertEquals("4–0", detail.timeline.single { it.kind == TimelineKind.FULL_TIME }.text)
        val sub = detail.timeline.first { it.kind == TimelineKind.SUB }
        assertEquals("Harry Maguire" to "Bryan Mbeumo", sub.player to sub.other)
        assertEquals(1, sub.side)
        val possession = detail.stats.first()
        assertEquals("Possession", possession.label)
        assertEquals("64%" to "36%", possession.home to possession.away)
        assertTrue(possession.homeShare in 0.63f..0.65f)
        assertEquals(listOf("Possession", "Shots", "On target", "Corners", "Fouls", "Offsides"), detail.stats.map { it.label })
        assertEquals(2, detail.lineups.size)
        assertEquals("4-2-3-1", detail.lineups[0].formation)
        assertEquals("Liverpool", detail.lineups[0].team)
        assertEquals(11, detail.lineups[0].starters.size)
        assertEquals("Alisson Becker", detail.lineups[0].starters.first().name, "in formation order, the keeper first")
    }

    @Test fun openLigaDbStandsInWithItsGoals() {
        val bayern = OpenLigaDb.parse(json("openligadb-bl1.json"), now).first()
        assertEquals("FC Bayern München v 1. FC Köln", bayern.title)
        assertEquals(LiveEventStatus.FINISHED, bayern.status)
        assertEquals("1 – 1", bayern.scoreText)
        assertEquals(1 to "M. Moore", bayern.incidents.first().side to bayern.incidents.first().player)
        assertNull(bayern.teams.first().logoUrl, "an SVG crest is not drawn")
    }

    // ---- cricket ------------------------------------------------------------------------------------------------------------

    @Test fun theCricketHeaderKeepsInternationalsBigLeaguesAndKnownDomesticCricket() {
        val events = EspnCricket.parseHeader(json("espn-cricket-header.json"), now)
        assertTrue(events.none { e -> e.teams.any { it.name.contains("Region") } }, "regional first-class cricket is left out")
        val test = events.single { it.title == "New Zealand v India, 1st Test" }
        assertEquals(LiveEventStatus.FINISHED, test.status)
        assertEquals(listOf("368 & 239/5 (120 ov)", "720/9d"), test.teams.map { it.score })
        assertEquals("Match drawn", test.statusText)
        assertEquals("Test", test.format)
        assertEquals("Basin Reserve, Wellington", test.venue)
        assertTrue(test.major)
        assertEquals(listOf("nz", "in"), test.countries)
        val shield = events.first { it.competition == "Sheffield Shield" && it.isLive }
        assertFalse(shield.major, "domestic: listed, not big")
        val ilt20 = events.single { it.competition == "International League T20" }
        assertEquals(LiveEventStatus.UPCOMING, ilt20.status)
        assertTrue(ilt20.major)
        assertTrue(events.any { it.title.contains("South Africa") && it.status == LiveEventStatus.UPCOMING })
    }

    @Test fun aLiveCricketSummaryGivesTheBattersTheLastBallsAndTheInnings() {
        val summary = json("espn-cricket-summary-live.json")
        val detail = EspnCricket.parseSummary(summary)
        assertEquals("Smith 6*, Philippe 45*", detail.batters)
        assertEquals("30.6 Neser to Smith, no run", detail.recentBalls.first())
        assertEquals(listOf("Queensland", "New South Wales"), detail.innings.map { it.team })
        assertEquals("99/1 (31 ov)", detail.innings[1].total)
        assertEquals("MG Neser", detail.innings[1].bowling.first().name)
        val header = EspnCricket.parseHeader(json("espn-cricket-header.json"), now).first { it.id.endsWith(":1544876") }
        val enriched = EspnCricket.enrich(header, summary)
        assertEquals("Smith 6*, Philippe 45*", enriched.batting)
        assertEquals("NSW trail by 179 runs", enriched.statusText)
        assertEquals("First-class · Day 2", enriched.format)
    }

    @Test fun aFinishedCricketSummaryGivesTheBattingBowlingAndPartnershipCards() {
        val detail = EspnCricket.parseSummary(json("espn-cricket-summary-result.json"))
        val third = detail.innings.single { it.batting.isNotEmpty() }
        assertEquals("New Zealand", third.team)
        assertEquals("157 (4 wkts; 71 ovs)", third.total)
        assertEquals("DP Conway" to "65", third.batting[1].name to third.batting[1].runs)
        assertTrue(third.batting.none { it.runs.isEmpty() }, "those who did not bat are left out")
        assertTrue(third.batting.any { it.notOut })
        assertEquals("JJ Bumrah", third.bowling.first().name)
        assertEquals("2nd" to "60", third.partnerships[1].wicket to third.partnerships[1].runs)
        assertEquals(3, detail.innings.size, "the innings with no card come from the line scores")
    }

    // ---- news ---------------------------------------------------------------------------------------------------------------

    private fun world() = listOf("bbc-world.xml" to "BBC", "guardian-world.xml" to "Guardian", "aljazeera.xml" to "Al Jazeera", "france24.xml" to "France 24", "dw-world.xml" to "DW")
        .flatMap { (file, outlet) -> NewsFeeds.parseRss(text(file), outlet, now) }

    @Test fun everyFeedIsReadWithItsPicture() {
        val items = world()
        listOf("BBC", "Guardian", "Al Jazeera", "France 24", "DW").forEach { outlet -> assertTrue(items.any { it.outlet == outlet }, "$outlet read") }
        val bbc = items.first { it.outlet == "BBC" && it.title.startsWith("COP31") }
        assertTrue(bbc.imageUrl!!.contains("/ace/standard/800/"), "the larger BBC picture")
        assertEquals("Hopes for an agreement on tackling climate change collapsed after a row about mentioning fossil fuels", bbc.summary)
        assertTrue(items.first { it.outlet == "Guardian" }.imageUrl!!.startsWith("https://i.guim.co.uk/"))
        assertTrue(items.first { it.outlet == "France 24" }.imageUrl!!.startsWith("https://s.france24.com/"))
        assertTrue(items.all { it.summary?.contains("<") != true }, "no HTML left in a summary")
        assertTrue(NewsFeeds.parseRss("""<!DOCTYPE rss [<!ENTITY a SYSTEM "file:///private">]><rss/>""", "x", now).isEmpty())
        assertTrue(NewsFeeds.parseRss("not xml at all", "x", now).isEmpty())
    }

    @Test fun theSameStoryIsOneStoryRankedByHowManyOutletsCarryIt() {
        val stories = NewsFeeds.rank(NewsFeeds.group(world()), emptyList())
        val top = stories.first()
        assertTrue(top.items.all { it.title.contains("cop31", ignoreCase = true) }, "COP31 leads: ${top.items.map { it.title }}")
        assertTrue(top.outlets.size >= 4, "carried by ${top.outlets}")
        val ship = stories.first { s -> s.items.any { it.title.contains("Benghazi") } }
        assertTrue(ship.outlets.size >= 3, "the Benghazi ship by ${ship.outlets}")
        assertFalse(NewsFeeds.sameStory(NewsFeeds.words("Russian strike kills 2 in Kyiv"), NewsFeeds.words("Russian ship attacked near Benghazi in suspected drone attack")))
        val events = NewsFeeds.toEvents(stories, emptyList(), now)
        assertTrue(events.none { it.isLive }, "news is never live")
        assertTrue(events.all { it.kind == LiveEventKind.NEWS && it.status == LiveEventStatus.FEATURED })
        assertEquals(top.outlets.size, events.first().outlets.size)
        assertTrue(events.first().major)
        assertNotNull(events.first().imageUrl)
    }

    @Test fun wikipediasInTheNewsIsReadAndMarksMajorStories() {
        val itn = NewsFeeds.parseItn(json("wikipedia-featured.json"))
        assertEquals(4, itn.size)
        assertTrue(itn[0].text.startsWith("The video game Grand Theft Auto VI achieves"), itn[0].text)
        assertEquals("https://en.wikipedia.org/wiki/Grand_Theft_Auto_VI", itn[0].link)
        assertTrue(NewsFeeds.itnEvents(itn, now).all { it.source == "Wikipedia" && it.major && !it.isLive })
    }

    @Test fun theCountrysNewsIsItsOwnWithThePublisherTakenOffTheHeadline() {
        val local = NewsFeeds.parseCountry(text("google-news-in.xml"), "India", now)
        assertTrue(local.isNotEmpty())
        assertTrue(local.all { it.local && it.competition == "India" && !it.isLive })
        assertTrue(local.all { it.link!!.startsWith("https://news.google.com/") })
        assertTrue(local.none { it.title.endsWith(" - " + it.source) }, "the publisher is the outlet, not part of the headline")
    }

    // ---- coming events ------------------------------------------------------------------------------------------------------

    @Test fun wikidataGivesElectionsAndSummitsWithAPageAndADay() {
        val events = MajorEvents.parse(json("wikidata-major-events.json"), now)
        assertEquals(listOf("2026 Spanish general election", "2026 Bahraini general election", "2026 Gambian presidential election", "2026 G20 Doral summit"),
            events.map { it.title }, "no nameless item, no page-less one, no year-only date, no \"next\" one")
        assertEquals("Spain", events[0].competition)
        assertEquals("Election", events[0].statusText)
        assertEquals("Summit", events[3].statusText)
        assertEquals(Instant.parse("2026-11-29T00:00:00Z").toEpochMilli(), events[0].startMs)
        assertTrue(events.all { it.kind == LiveEventKind.EVENT && it.status == LiveEventStatus.UPCOMING && it.link!!.startsWith("https://en.wikipedia.org/") })
        assertTrue(MajorEvents.query(now, now + 60 * 86_400_000L).contains("\"2026-11-23T00:00:00Z\"^^xsd:dateTime"))
    }
}
