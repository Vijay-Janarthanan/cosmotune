package com.cosmotune.watch

import com.cosmotune.watch.LiveVideoSource.Companion.video
import com.cosmotune.watch.live.CricketDataScores
import com.cosmotune.watch.live.FootballDataScores
import com.cosmotune.watch.live.LiveChannelNow
import com.cosmotune.watch.live.LiveEventsHub
import com.cosmotune.watch.live.LiveScoreKeys
import com.cosmotune.watch.live.ScoreSource
import com.cosmotune.watch.live.SportsDbScores
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.plugins.youtube.PrasarBharatiPlugin
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveEventsTest {
    private val now = 1_762_461_600_000L // 2025-11-06T20:40:00Z in the tests' own clock
    private val india = WatchPlace("in", "ta")

    private val cricket = """{"apikey":"k","status":"success","data":[
        {"id":"m1","name":"India vs Australia, 2nd T20I, Australia tour of India, 2025","matchType":"t20","status":"India need 4 runs in 10 balls",
         "venue":"Chennai","date":"2025-11-06","dateTimeGMT":"2025-11-06T17:30:00","teams":["India","Australia"],
         "teamInfo":[{"name":"India","shortname":"IND","img":"https://g.cricapi.com/iapi/1.png"},{"name":"Australia","shortname":"AUS","img":"https://g.cricapi.com/iapi/2.png"}],
         "score":[{"r":179,"w":8,"o":20,"inning":"Australia Inning 1"},{"r":176,"w":4,"o":18.2,"inning":"India Inning 1"}],"matchStarted":true,"matchEnded":false},
        {"id":"m2","name":"Chennai Super Kings vs Mumbai Indians, 12th Match, Indian Premier League 2026","matchType":"t20","status":"Match not started",
         "dateTimeGMT":"2025-11-07T14:00:00","teams":["Chennai Super Kings","Mumbai Indians"],"matchStarted":false,"matchEnded":false},
        {"id":"m3","name":"Mumbai vs Saurashtra, Elite Group A, Ranji Trophy","matchType":"test","status":"Day 2: Stumps","dateTimeGMT":"2025-11-05T04:00:00",
         "teams":["Mumbai","Saurashtra"],"matchStarted":true,"matchEnded":false},
        {"id":"m4","name":"England Women vs Pakistan Women, 1st ODI","matchType":"odi","status":"England Women won by 5 wkts","dateTimeGMT":"2025-11-06T09:00:00",
         "teams":["England Women","Pakistan Women"],"score":[{"r":201,"w":10,"o":48.1,"inning":"Pakistan Women Inning 1"},{"r":202,"w":5,"o":41,"inning":"England Women Inning 1"}],
         "matchStarted":true,"matchEnded":true}]}"""

    private val football = """{"matches":[
        {"id":11,"utcDate":"2025-11-06T19:30:00Z","status":"IN_PLAY","minute":67,"competition":{"name":"Premier League","code":"PL","emblem":"https://crests/PL.png"},
         "homeTeam":{"name":"Arsenal FC","shortName":"Arsenal","tla":"ARS","crest":"https://crests/57.png"},"awayTeam":{"name":"Chelsea FC","shortName":"Chelsea","tla":"CHE"},
         "score":{"fullTime":{"home":2,"away":1}}},
        {"id":12,"utcDate":"2025-11-06T16:00:00Z","status":"FINISHED","competition":{"name":"Primera Division","code":"PD"},
         "homeTeam":{"shortName":"Real Madrid","tla":"RMA"},"awayTeam":{"shortName":"Barça","tla":"FCB"},"score":{"fullTime":{"home":1,"away":1}}},
        {"id":13,"utcDate":"2025-11-07T19:00:00Z","status":"POSTPONED","competition":{"name":"Serie A","code":"SA"},"homeTeam":{"shortName":"Inter"},"awayTeam":{"shortName":"Milan"},"score":{"fullTime":{}}}
    ]}"""

    @Test fun cricketDataKeepsInternationalsAndMajorLeaguesWithTheirScores() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/v1/currentMatches", cricket)
            val events = CricketDataScores(s.http(), "my key", s.url("/v1").removeSuffix("/")).events(now)
            assertEquals(listOf("India v Australia", "Chennai Super Kings v Mumbai Indians", "England Women v Pakistan Women"), events.map { it.title },
                "a domestic first-class match is left out")
            val live = events[0]
            assertEquals(LiveEventStatus.LIVE, live.status)
            assertEquals("2nd T20I, Australia tour of India, 2025", live.competition)
            assertEquals("IND 176/4 (18.2) · AUS 179/8 (20)", live.scoreText)
            assertEquals("176/4 (18.2 ov)", live.teams.first { it.name == "India" }.score)
            assertEquals("in", live.teams.first().country)
            assertEquals(listOf(true, false), live.teams.map { it.batting }, "India's innings is the one in progress")
            assertTrue(events[2].teams.none { it.batting }, "nobody bats in a finished match")
            assertEquals(listOf("in", "au"), live.countries)
            assertEquals("India need 4 runs in 10 balls", live.statusText)
            assertEquals(1_762_450_200_000L, live.startMs)
            val ipl = events[1]
            assertEquals(LiveEventStatus.UPCOMING, ipl.status)
            assertNull(ipl.statusText, "\"Match not started\" says nothing the start time does not")
            assertEquals(listOf("in"), ipl.countries)
            assertEquals(LiveEventStatus.FINISHED, events[2].status)
            assertEquals("201 (48.1 ov)", events[2].teams[1].score, "all out shows the runs only")
            assertTrue(s.paths().single().contains("apikey=my%20key"))
        }
    }

    @Test fun cricketDataRefusalIsAnError() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/v1/currentMatches", """{"status":"failure","reason":"Invalid API Key"}""")
            val result = runCatching { CricketDataScores(s.http(), "bad", s.url("/v1").removeSuffix("/")).events(now) }
            assertEquals("Invalid API Key", result.exceptionOrNull()?.message)
        }
    }

    @Test fun footballDataReadsTheRunningScoreAndSendsTheKeyAsAHeader() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/v4/matches", football)
            val events = FootballDataScores(s.http(), "fd-key", s.url("/v4").removeSuffix("/")).events(now)
            assertEquals(listOf("Arsenal v Chelsea", "Real Madrid v Barça"), events.map { it.title }, "a postponed match is left out")
            assertEquals("2 – 1", events[0].scoreText)
            assertEquals("67'", events[0].statusText)
            assertEquals(LiveEventStatus.LIVE, events[0].status)
            assertEquals(listOf("gb"), events[0].countries)
            assertEquals("https://crests/57.png", events[0].teams[0].logoUrl)
            assertEquals("Full time", events[1].statusText)
            val request = s.requests.single()
            assertEquals("fd-key", request.getHeader("X-Auth-Token"))
            assertEquals("/v4/matches?dateFrom=2025-11-05&dateTo=2025-11-07", request.path)
        }
    }

    @Test fun theSportsDbGivesEachLeaguesNextMatchAndLastResult() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/json/123/eventsnextleague.php", """{"events":[{"idEvent":"e1","strHomeTeam":"Everton","strAwayTeam":"Coventry City","strLeague":"English Premier League",
                "strTimestamp":"2025-11-06T22:00:00","strStatus":"NS","intHomeScore":null,"intAwayScore":null}]}""")
            s.json("/json/123/eventspastleague.php", """{"events":[{"idEvent":"e0","strHomeTeam":"Newcastle United","strAwayTeam":"Everton","strLeague":"English Premier League",
                "strTimestamp":"2025-11-06T12:00:00","strStatus":"FT","intHomeScore":"0","intAwayScore":"1"}]}""")
            val events = SportsDbScores(s.http(), SportsDbScores.FREE_KEY, listOf(SportsDbScores.League(4328, LiveEventKind.FOOTBALL, "gb")), s.url("/json").removeSuffix("/")).events(now)
            assertEquals(setOf("Everton v Coventry City", "Newcastle United v Everton"), events.map { it.title }.toSet())
            assertEquals("0 – 1", events.first { it.status == LiveEventStatus.FINISHED }.scoreText)
            assertNull(events.first { it.status == LiveEventStatus.UPCOMING }.scoreText)
            assertTrue(s.paths().all { it.endsWith("?id=4328") })
        }
    }

    private fun event(id: String, status: LiveEventStatus, countries: List<String> = emptyList(), startMs: Long? = now, teams: List<LiveTeam> = emptyList(),
        kind: LiveEventKind = LiveEventKind.FOOTBALL) = LiveEvent(id, kind, id, teams = teams, status = status, startMs = startMs, countries = countries, source = "test")

    private class Fixed(val list: List<LiveEvent>, val fail: Boolean = false) : ScoreSource {
        override val name = "fixed"
        var asked = 0
        override suspend fun events(nowMs: Long): List<LiveEvent> { asked++; if (fail) error("down"); return list }
    }

    @Test fun theHubOffersDdSportsForTheMatchItCarriesAndRanksByCountry() = runBlocking<Unit> {
        val ddSports = PrasarBharatiPlugin({ null }).let { p -> p.channelOf(p.official(PrasarBharatiPlugin.DD_SPORTS)!!) }
        val match = event("India v Australia", LiveEventStatus.LIVE, listOf("in", "au"), teams = listOf(LiveTeam("India", "IND"), LiveTeam("Australia", "AUS")), kind = LiveEventKind.CRICKET)
        val other = event("Arsenal v Chelsea", LiveEventStatus.LIVE, listOf("gb"), teams = listOf(LiveTeam("Arsenal"), LiveTeam("Chelsea")))
        val later = event("Inter Kashi v SC Delhi", LiveEventStatus.UPCOMING, listOf("in"), startMs = now + 3_600_000)
        val sooner = event("Everton v Coventry", LiveEventStatus.UPCOMING, listOf("gb"), startMs = now + 1_800_000)
        val tooFar = event("Next week", LiveEventStatus.UPCOMING, startMs = now + 7 * 86_400_000L)
        val old = event("Yesterday's", LiveEventStatus.FINISHED, startMs = now - 30 * 3_600_000L)
        val done = event("Real Madrid v Barcelona", LiveEventStatus.FINISHED, listOf("es"), startMs = now - 5 * 3_600_000L)
        val live = listOf(LiveChannelNow(ddSports, "DD", false,
            lives = listOf(video("ddsLive0001", "LIVE - India vs Australia | 2nd T20I | DD Sports", "x", live = true)), upcoming = emptyList()))
        val failing = Fixed(emptyList(), fail = true)
        val hub = LiveEventsHub({ listOf(Fixed(listOf(other, match, later, sooner, tooFar, old, done)), failing) }, { live }, { india }, { now })
        val events = hub.current()
        assertEquals(listOf("India v Australia", "Arsenal v Chelsea", "Inter Kashi v SC Delhi", "Everton v Coventry", "Real Madrid v Barcelona"), events.map { it.title },
            "live, upcoming, finished; India first in each; out of the window left out; a failing source left out")
        val option = events[0].watchOptions.single()
        assertEquals("DD Sports", option.label)
        assertIs<PlayTarget.Live>(option.target)
        assertEquals(ddSports.key, option.target.key)
        assertEquals(emptyList(), events[1].watchOptions, "no free stream carries it: score only")
        assertTrue(events.none { it.kind == LiveEventKind.SHOW }, "the DD Sports video carrying the match is not also a show")
        assertEquals(1, failing.asked)
        assertEquals(events, hub.flow().first(), "the flow emits the last answer at once")
    }

    @Test fun ddLiveSpecialsAreShowsButA24x7StreamIsNot() = runBlocking<Unit> {
        val dd = PrasarBharatiPlugin({ null })
        val national = dd.channelOf(dd.official("DDNational")!!)
        val news = dd.channelOf(dd.official("DDNews")!!)
        val channels = listOf(
            LiveChannelNow(national, "DD", false, listOf(video("natLive0001", "LIVE: Republic Day Parade 2026", "x", live = true)),
                listOf(video("natUpcom001", "Beating Retreat", "x", upcoming = true, atMs = now + 2 * 3_600_000))),
            LiveChannelNow(news, "DD", true, listOf(video("news24x7001", "DD News 24x7 LIVE", "x", live = true), video("newsSpec001", "PM addresses the nation", "x", live = true)), emptyList()),
        )
        val events = LiveEventsHub({ emptyList() }, { channels }, { india }, { now }).current()
        assertEquals(setOf("Republic Day Parade 2026", "PM addresses the nation"), events.take(2).map { it.title }.toSet(), "the 24x7 stream is not a show")
        assertEquals("Beating Retreat", events[2].title)
        assertTrue(events.all { it.kind == LiveEventKind.SHOW && it.countries == listOf("in") })
        assertIs<PlayTarget.Live>(events.first { it.title.startsWith("Republic") }.watchOptions.single().target, "DD National's main live is the channel itself")
        val special = events.first { it.title.startsWith("PM") }.watchOptions.single().target
        assertIs<PlayTarget.Item>(special)
        assertEquals("newsSpec001", special.itemId)
        assertEquals("dd", special.pluginId)
        assertEquals(emptyList(), events[2].watchOptions, "nothing to play before it starts")
        assertEquals(now + 2 * 3_600_000, events[2].startMs)
    }

    @Test fun carriesNeedsBothSides() {
        val teams = listOf(LiveTeam("India Women", "INDW"), LiveTeam("Australia Women", "AUSW"))
        assertTrue(LiveEventsHub.carries("LIVE | India vs Australia | Women's World Cup", teams))
        assertTrue(!LiveEventsHub.carries("LIVE | India vs England", teams))
        assertTrue(!LiveEventsHub.carries("Indian Super League highlights", listOf(LiveTeam("India"), LiveTeam("Australia"))), "a word inside another is not a side")
    }

    @Test fun theRepositoryUsesTheKeyedServicesAndFallsBackWithoutKeys() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/v1/currentMatches", cricket)
            s.json("/json/123/eventsnextleague.php", """{"events":[]}""")
            s.json("/json/123/eventspastleague.php", """{"events":[]}""")
            val dir = Files.createTempDirectory("watch-live").toFile().also { it.deleteOnExit() }
            val endpoints = Endpoints(cricketData = s.url("/v1").removeSuffix("/"), footballData = s.url("/v4").removeSuffix("/"), theSportsDb = s.url("/json").removeSuffix("/"))
            val source = LiveVideoSource()
            val sports = PrasarBharatiPlugin.CHANNELS.first { it.slug == PrasarBharatiPlugin.DD_SPORTS }
            source.live[sports.youTubeId] = listOf(video("ddsLive0001", "LIVE - India vs Australia, 2nd T20I", sports.youTubeId, live = true))
            val repo = DefaultWatchRepository(
                WatchConfig(OkHttpClient(), dir, null, now = { now }, endpoints = endpoints, place = { india }, timeoutMs = 5_000,
                    scoreKeys = LiveScoreKeys(cricketData = "c-key")),
                WatchStore(File(dir, "watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> listOf(PrasarBharatiPlugin({ source }, { now })) })
            val events = repo.liveEventsNow()
            assertEquals("India v Australia", events.first().title)
            assertEquals("DD Sports", events.first().watchOptions.single().label)
            val paths = s.paths()
            assertTrue(paths.any { it.startsWith("/v1/currentMatches") })
            assertTrue(paths.none { it.startsWith("/v4/") }, "no football key: football-data.org is never asked")
            assertTrue(paths.any { it.startsWith("/json/123/eventsnextleague.php?id=4328") }, "TheSportsDB fills in football")
            assertTrue(paths.none { it.contains("id=4460") }, "cricket has its key: no TheSportsDB cricket")
            repo.setPluginEnabled("dd", false)
            assertEquals(emptyList(), repo.liveEventsNow().first().watchOptions, "DD switched off: no DD option")
            repo.close()
        }
    }

    @Test fun keysAreReadTrimmedAndBlankMeansNone() {
        val keys = LiveScoreKeys(" abc ", "  ", null)
        assertEquals("abc", keys.cricket)
        assertNull(keys.football)
        assertEquals(SportsDbScores.FREE_KEY, keys.sportsDb)
        assertEquals("THESPORTSDB_API_KEY", LiveScoreKeys.THESPORTSDB)
    }
}
