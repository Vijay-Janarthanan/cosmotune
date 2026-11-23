package com.cosmotune.watch

import com.cosmotune.watch.LiveVideoSource.Companion.video
import com.cosmotune.watch.live.LiveChannelNow
import com.cosmotune.watch.live.LiveEventsHub
import com.cosmotune.watch.live.ScoreSource
import com.cosmotune.watch.live.SpotlightClock
import com.cosmotune.watch.live.SpotlightSchedule
import com.cosmotune.watch.live.SpotlightWindows
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveFocus
import com.cosmotune.watch.model.LiveInterest
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.MatchClock
import com.cosmotune.watch.model.MatchPhase
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveEventsTest {
    private val now = 1_762_461_600_000L // 2025-11-06T20:40:00Z in the tests' own clock
    private val minute = 60_000L
    private val hour = 60 * minute
    private val india = WatchPlace("in", "ta")

    private fun event(id: String, status: LiveEventStatus, countries: List<String> = emptyList(), startMs: Long? = now, teams: List<LiveTeam> = emptyList(),
        kind: LiveEventKind = LiveEventKind.FOOTBALL, updatedAtMs: Long = now, major: Boolean = false) =
        LiveEvent(id, kind, id, teams = teams, status = status, startMs = startMs, countries = countries, source = "test", updatedAtMs = updatedAtMs, major = major)

    private class Fixed(var list: List<LiveEvent>, var fail: Boolean = false) : ScoreSource {
        override val name = "fixed"
        var asked = 0
        var freshAsked = 0
        override suspend fun events(nowMs: Long, fresh: Boolean): List<LiveEvent> { asked++; if (fresh) freshAsked++; if (fail) error("down"); return list }
    }

    private class Failing : ScoreSource {
        override val name = "failing"
        var asked = 0
        override suspend fun events(nowMs: Long, fresh: Boolean): List<LiveEvent> { asked++; error("down") }
    }

    // ---- the hub ------------------------------------------------------------------------------------------------------------

    @Test fun theHubOffersDdSportsForTheMatchItCarriesAndRanksBigOnesAndTheCountryFirst() = runBlocking<Unit> {
        val ddSports = PrasarBharatiPlugin({ null }).let { p -> p.channelOf(p.official(PrasarBharatiPlugin.DD_SPORTS)!!) }
        val match = event("India v Australia", LiveEventStatus.LIVE, listOf("in", "au"), teams = listOf(LiveTeam("India", "IND"), LiveTeam("Australia", "AUS")), kind = LiveEventKind.CRICKET, major = true)
        val other = event("Arsenal v Chelsea", LiveEventStatus.LIVE, listOf("gb"), teams = listOf(LiveTeam("Arsenal"), LiveTeam("Chelsea")), major = true)
        val small = event("Mumbai v Saurashtra", LiveEventStatus.LIVE, listOf("in"), kind = LiveEventKind.CRICKET)
        val later = event("Inter Kashi v SC Delhi", LiveEventStatus.UPCOMING, listOf("in"), startMs = now + 3 * hour)
        val sooner = event("Everton v Coventry", LiveEventStatus.UPCOMING, listOf("gb"), startMs = now + hour)
        val tooFar = event("In ten days", LiveEventStatus.UPCOMING, startMs = now + 10 * 24 * hour)
        val old = event("Two days ago", LiveEventStatus.FINISHED, startMs = now - 48 * hour)
        val done = event("Real Madrid v Barcelona", LiveEventStatus.FINISHED, listOf("es"), startMs = now - 5 * hour)
        val live = listOf(LiveChannelNow(ddSports, "DD", false,
            lives = listOf(video("ddsLive0001", "LIVE - India vs Australia | 2nd T20I | DD Sports", "x", live = true)), upcoming = emptyList()))
        val failing = Failing()
        val story = LiveEvent("news:1", LiveEventKind.NEWS, "A story", status = LiveEventStatus.FEATURED, source = "BBC")
        val election = LiveEvent("event:1", LiveEventKind.EVENT, "An election", status = LiveEventStatus.UPCOMING, startMs = now + 12 * 24 * hour, source = "Wikidata")
        val hub = LiveEventsHub({ listOf(Fixed(listOf(other, small, match, later, sooner, tooFar, old, done)), failing) }, { live }, { india }, { now },
            news = { _, _ -> listOf(story) }, events = { listOf(election) })
        val events = hub.current()
        assertEquals(listOf("India v Australia", "Arsenal v Chelsea", "Mumbai v Saurashtra", "Inter Kashi v SC Delhi", "Everton v Coventry", "Real Madrid v Barcelona",
            "A story", "An election"), events.map { it.title },
            "live (big first, India first), upcoming, finished; the news after the matches; the events last; outside the windows left out")
        val option = events[0].watchOptions.single()
        assertEquals("DD Sports", option.label)
        assertIs<PlayTarget.Live>(option.target)
        assertEquals(ddSports.key, option.target.key)
        assertEquals(emptyList(), events[1].watchOptions, "no free stream carries it")
        assertTrue(events.none { it.kind == LiveEventKind.SHOW }, "the DD Sports video carrying the match is not also a show")
        assertEquals(1, failing.asked)
        assertEquals(events, hub.flow().first(), "the flow emits the last answer at once")
    }

    @Test fun aSourceThatFailsKeepsItsLastScoresWhichThenShowAsBehind() = runBlocking<Unit> {
        var t = now
        val source = Fixed(listOf(event("Arsenal v Chelsea", LiveEventStatus.LIVE, updatedAtMs = now)))
        val hub = LiveEventsHub({ listOf(source) }, { emptyList() }, { india }, { t })
        assertEquals("Arsenal v Chelsea", hub.current().single().title)
        source.fail = true
        t = now + 3 * minute
        val kept = hub.current()
        assertEquals("Arsenal v Chelsea", kept.single().title, "the last good answer stands")
        assertEquals(now, kept.single().updatedAtMs, "with the time it was read")
        assertTrue(SpotlightSchedule.isBehind(kept, t), "older than two minutes: the sheet says scores may be behind")
        assertFalse(SpotlightSchedule.isBehind(kept, now + minute))
    }

    @Test fun aRefreshReadsTheScoresFresh() = runBlocking<Unit> {
        val source = Fixed(listOf(event("Arsenal v Chelsea", LiveEventStatus.LIVE)))
        LiveEventsHub({ listOf(source) }, { emptyList() }, { india }, { now }).flow(LiveInterest(refresh = 0)).first()
        assertEquals(0, source.freshAsked)
        LiveEventsHub({ listOf(source) }, { emptyList() }, { india }, { now }).flow(LiveInterest(refresh = 1)).first()
        assertEquals(1, source.freshAsked, "the refresh count went up: fresh")
    }

    @Test fun ddLiveSpecialsAreShowsButA24x7StreamIsNot() = runBlocking<Unit> {
        val dd = PrasarBharatiPlugin({ null })
        val national = dd.channelOf(dd.official("DDNational")!!)
        val news = dd.channelOf(dd.official("DDNews")!!)
        val channels = listOf(
            LiveChannelNow(national, "DD", false, listOf(video("natLive0001", "LIVE: Republic Day Parade 2026", "x", live = true)),
                listOf(video("natUpcom001", "Beating Retreat", "x", upcoming = true, atMs = now + 2 * hour))),
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
        assertEquals(emptyList(), events[2].watchOptions, "nothing to play before it starts")
    }

    @Test fun theSameSpecialOnTwoChannelsIsOneEventWithBothOptions() = runBlocking<Unit> {
        val dd = PrasarBharatiPlugin({ null })
        val national = dd.channelOf(dd.official("DDNational")!!)
        val news = dd.channelOf(dd.official("DDNews")!!)
        val channels = listOf(
            LiveChannelNow(national, "DD", false, listOf(video("natAarti001", "Sringaar Aarti of Prabhu Shri Ram", "x", live = true)), emptyList()),
            LiveChannelNow(news, "DD", true, listOf(video("news24x7001", "DD News 24x7 LIVE", "x", live = true), video("newsAarti001", "Sringaar Aarti of Prabhu Shri Ram", "x", live = true)), emptyList()),
        )
        val aarti = LiveEventsHub({ emptyList() }, { channels }, { india }, { now }).current().single()
        assertEquals(setOf("DD National", "DD News"), aarti.watchOptions.mapNotNull { it.channel?.name }.toSet())
    }

    @Test fun theLiveChannelsAreAskedOnlyWhenWhatIsFollowedCanUseThem() = runBlocking<Unit> {
        val dd = PrasarBharatiPlugin({ null })
        val ddSports = dd.channelOf(dd.official(PrasarBharatiPlugin.DD_SPORTS)!!)
        val match = event("India v Australia", LiveEventStatus.LIVE, listOf("in"), teams = listOf(LiveTeam("India", "IND"), LiveTeam("Australia", "AUS")), kind = LiveEventKind.CRICKET)
        val special = LiveChannelNow(dd.channelOf(dd.official("DDNational")!!), "DD", false, listOf(video("natLive0001", "LIVE: Republic Day Parade 2026", "x", live = true)), emptyList())
        val carrier = LiveChannelNow(ddSports, "DD", false, listOf(video("ddsLive0001", "LIVE - India vs Australia | 2nd T20I | DD Sports", "x", live = true)), emptyList())
        var asked = 0
        val hub = LiveEventsHub({ listOf(Fixed(listOf(match))) }, { asked++; listOf(special, carrier) }, { india }, { now })
        val quiet = hub.current(LiveInterest(shows = false) { it.kind == LiveEventKind.FOOTBALL })
        assertEquals(0, asked, "no shows followed and no followed match in play: YouTube is not asked")
        assertTrue(quiet.none { it.kind == LiveEventKind.SHOW })
        val carried = hub.current(LiveInterest(shows = false) { it.kind == LiveEventKind.CRICKET })
        assertEquals(1, asked, "a followed match in play: asked, for the free streams that carry it")
        assertEquals("DD Sports", carried.first { it.title == "India v Australia" }.watchOptions.single().label)
        hub.current(LiveInterest.ALL)
        assertEquals(2, asked, "shows followed: asked")
    }

    @Test fun carriesNeedsBothSides() {
        val teams = listOf(LiveTeam("India Women", "INDW"), LiveTeam("Australia Women", "AUSW"))
        assertTrue(LiveEventsHub.carries("LIVE | India vs Australia | Women's World Cup", teams))
        assertTrue(!LiveEventsHub.carries("LIVE | India vs England", teams))
        assertTrue(!LiveEventsHub.carries("Indian Super League highlights", listOf(LiveTeam("India"), LiveTeam("Australia"))), "a word inside another is not a side")
    }

    // ---- the cadence --------------------------------------------------------------------------------------------------------

    @Test fun whileAMatchIsLiveTheReadsComeQuicklyTheCloserOneLooks() {
        val live = listOf(event("Arsenal v Chelsea", LiveEventStatus.LIVE))
        assertEquals(30_000L, SpotlightSchedule.nextPollMs(live, now, LiveFocus.SHEET))
        assertEquals(15_000L, SpotlightSchedule.nextPollMs(live, now, LiveFocus.DETAIL))
        assertEquals(60_000L, SpotlightSchedule.nextPollMs(live, now, LiveFocus.STRIP))
        val show = listOf(LiveEvent("show:1", LiveEventKind.SHOW, "A live special", status = LiveEventStatus.LIVE, source = "YouTube"))
        assertEquals(10 * minute, SpotlightSchedule.nextPollMs(show, now, LiveFocus.SHEET), "a live show is not a match: the slow read")
    }

    @Test fun withNothingLiveTheReadsWaitTenMinutesOrUntilTheNextKickOff() {
        assertEquals(10 * minute, SpotlightSchedule.nextPollMs(emptyList(), now, LiveFocus.SHEET))
        val soon = listOf(event("Kick-off in 4 min", LiveEventStatus.UPCOMING, startMs = now + 4 * minute), event("Later", LiveEventStatus.UPCOMING, startMs = now + 3 * hour))
        assertEquals(4 * minute, SpotlightSchedule.nextPollMs(soon, now, LiveFocus.STRIP), "woken at the kick-off so the match turns live on time")
        val far = listOf(event("Tomorrow", LiveEventStatus.UPCOMING, startMs = now + 20 * hour))
        assertEquals(10 * minute, SpotlightSchedule.nextPollMs(far, now, LiveFocus.STRIP))
        val now2 = listOf(event("Kick-off in 1 s", LiveEventStatus.UPCOMING, startMs = now + 1_000))
        assertEquals(5_000L, SpotlightSchedule.nextPollMs(now2, now, LiveFocus.STRIP), "never sooner than five seconds")
        val late = listOf(event("Kicked off 3 min ago, not live yet", LiveEventStatus.UPCOMING, startMs = now - 3 * minute))
        assertEquals(60_000L, SpotlightSchedule.nextPollMs(late, now, LiveFocus.STRIP), "a source late to say so is asked each minute")
        assertEquals(10 * minute, SpotlightSchedule.nextPollMs(listOf(event("Long ago", LiveEventStatus.UPCOMING, startMs = now - 2 * hour)), now, LiveFocus.STRIP))
    }

    @Test fun nothingIsReadWhileTheAppIsNotInFront() {
        val live = listOf(event("Arsenal v Chelsea", LiveEventStatus.LIVE))
        assertNull(SpotlightSchedule.nextPollMs(live, now, LiveFocus.SHEET, foreground = false))
        assertNull(SpotlightSchedule.nextPollMs(emptyList(), now, LiveFocus.STRIP, foreground = false))
    }

    // ---- the minute ---------------------------------------------------------------------------------------------------------

    @Test fun theMinuteKeepsGoingBetweenReadsAndStopsAtHalfTimeAndFullTime() {
        val read = MatchClock(MatchPhase.SECOND_HALF, seconds = 3990.0, display = "67'", period = 2, readAtMs = now)
        assertEquals("67'", SpotlightClock.minute(read, now))
        assertEquals("67'", SpotlightClock.minute(read, now + 29_000))
        assertEquals("68'", SpotlightClock.minute(read, now + 31_000), "66:30 plus 31 s is the 68th minute")
        assertEquals("90'+3'", SpotlightClock.minute(read.copy(seconds = 5390.0), now + 3 * minute), "past 90 it is added time")
        assertEquals("45'+3'", SpotlightClock.minute(MatchClock(MatchPhase.FIRST_HALF, display = "45'+2'", period = 1, readAtMs = now), now + minute + 1_000))
        assertEquals("82'", SpotlightClock.minute(read, now + 2 * hour), "a source that stopped answering does not count on for ever")
        assertNull(SpotlightClock.minute(read.copy(phase = MatchPhase.HALF_TIME), now + 10 * minute))
        val half = event("x", LiveEventStatus.LIVE).copy(clock = read.copy(phase = MatchPhase.HALF_TIME))
        assertEquals("Half-time", SpotlightClock.pill(half, now + 10 * minute))
        assertFalse(SpotlightClock.inPlay(half))
        val over = event("x", LiveEventStatus.FINISHED).copy(clock = read.copy(phase = MatchPhase.FULL_TIME), statusText = "Full time")
        assertEquals("Full time", SpotlightClock.pill(over, now + hour))
        assertEquals("Stumps", SpotlightClock.pill(event("x", LiveEventStatus.LIVE, kind = LiveEventKind.CRICKET).copy(statusText = "Day 2 - Stumps"), now))
        assertEquals("Live", SpotlightClock.pill(event("x", LiveEventStatus.LIVE, kind = LiveEventKind.CRICKET).copy(statusText = "India lead by 197 runs"), now))
        assertNull(SpotlightClock.pill(LiveEvent("n", LiveEventKind.NEWS, "A story", status = LiveEventStatus.FEATURED, source = "BBC"), now), "news never has a live pill")
    }

    // ---- the windows --------------------------------------------------------------------------------------------------------

    @Test fun justFinishedIsTheLastDayAndComingUpIsTheNextWeekOrSixtyDaysForEvents() {
        assertTrue(SpotlightWindows.justFinished(event("x", LiveEventStatus.FINISHED, startMs = now - 20 * hour), now))
        assertFalse(SpotlightWindows.justFinished(event("x", LiveEventStatus.FINISHED, startMs = now - 30 * hour), now))
        val test = event("A Test that ended today", LiveEventStatus.FINISHED, startMs = now - 5 * 24 * hour, kind = LiveEventKind.CRICKET).copy(endMs = now - 2 * hour)
        assertTrue(SpotlightWindows.justFinished(test, now), "a five-day Test counts from its end")
        assertTrue(SpotlightWindows.upcoming(event("x", LiveEventStatus.UPCOMING, startMs = now + 6 * 24 * hour), now))
        assertFalse(SpotlightWindows.upcoming(event("x", LiveEventStatus.UPCOMING, startMs = now + 8 * 24 * hour), now))
        val election = LiveEvent("e", LiveEventKind.EVENT, "An election", status = LiveEventStatus.UPCOMING, startMs = now + 50 * 24 * hour, source = "Wikidata")
        assertTrue(SpotlightWindows.upcoming(election, now))
        assertFalse(SpotlightWindows.upcoming(election.copy(startMs = now + 70 * 24 * hour), now))
    }

    // ---- the repository -----------------------------------------------------------------------------------------------------

    @Test fun theRepositoryReadsEspnWithoutKeysAndOffersDdSports() = runBlocking<Unit> {
        TestServer().use { s ->
            val board = javaClass.getResource("/spotlight/espn-football-scoreboard.json")!!.readText()
            s.json("/soccer/eng.1/scoreboard", board)
            s.json("/soccer/", """{"events":[]}""")
            s.json("/header", javaClass.getResource("/spotlight/espn-cricket-header.json")!!.readText())
            s.json("/cricket/", javaClass.getResource("/spotlight/espn-cricket-summary-live.json")!!.readText())
            s.json("/soccer/eng.1/summary", javaClass.getResource("/spotlight/espn-football-summary.json")!!.readText())
            val dir = Files.createTempDirectory("watch-live").toFile().also { it.deleteOnExit() }
            val endpoints = Endpoints(espnFootball = s.url("/soccer").removeSuffix("/"), espnCricketHeader = s.url("/header"), espnCricket = s.url("/cricket").removeSuffix("/"),
                openLigaDb = s.url("/oldb").removeSuffix("/"))
            val source = LiveVideoSource()
            val sports = PrasarBharatiPlugin.CHANNELS.first { it.slug == PrasarBharatiPlugin.DD_SPORTS }
            source.live[sports.youTubeId] = listOf(video("ddsLive0001", "LIVE - New South Wales vs Queensland, Sheffield Shield", sports.youTubeId, live = true))
            // The fixtures' own day: 23 November 2026.
            val day = java.time.Instant.parse("2026-11-23T05:00:00Z").toEpochMilli()
            val repo = DefaultWatchRepository(
                WatchConfig(OkHttpClient(), dir, null, now = { day }, endpoints = endpoints, place = { india }, timeoutMs = 5_000),
                WatchStore(File(dir, "watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> listOf(PrasarBharatiPlugin({ source }, { day })) })
            val events = repo.liveEventsNow()
            val shield = events.first { it.isLive && it.kind == LiveEventKind.CRICKET && it.id.endsWith(":1544876") }
            assertEquals("DD Sports", shield.watchOptions.single().label)
            assertEquals("Smith 6*, Philippe 45*", shield.batting, "a live match's batters from its summary")
            assertTrue(events.any { it.title == "Liverpool v Manchester United" && it.status == LiveEventStatus.FINISHED }, "yesterday's result")
            assertTrue(events.none { it.kind == LiveEventKind.NEWS }, "no news reached for in a test")
            val paths = s.paths()
            assertTrue(paths.none { it.contains("apikey") || it.contains("X-Auth") }, "no keys anywhere")
            assertTrue(paths.any { it.startsWith("/soccer/eng.1/scoreboard?dates=20261122") }, "yesterday's board for the last day's results")
            val detail = assertNotNull(repo.matchDetail(events.first { it.title == "Liverpool v Manchester United" }))
            assertEquals("4-2-3-1", detail.football?.lineups?.first()?.formation)
            repo.close()
        }
    }
}
