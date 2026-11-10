package com.cosmotune.ui

import com.cosmotune.ui.live.LiveNowLists
import com.cosmotune.ui.live.LiveNowMemoryStore
import com.cosmotune.ui.live.LiveNowSettings
import com.cosmotune.ui.live.battingSide
import com.cosmotune.ui.live.cardLabel
import com.cosmotune.ui.live.cricketScore
import com.cosmotune.ui.live.pillDescription
import com.cosmotune.ui.live.rowTitle
import com.cosmotune.ui.live.startText
import com.cosmotune.ui.live.watchLine
import com.cosmotune.ui.live.watchNames
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchOption
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The words and the choices of "Live now" (Canvas repo, design/cosmotune/live-strip/board.html) on the fake repository's events,
 * and the places it is drawn: the pill in the desktop's top bar of every mode, under each phone header, in the TV's chips row.
 */
class LiveNowTextTest {
    private val evening = 1_762_461_600_000L
    private val events = runBlocking { FakeWatchRepository().liveEvents().first() }

    @Test fun thePillReadsTheBattingSideFirst() {
        val match = events.first { it.kind == LiveEventKind.CRICKET && it.isLive }
        assertEquals("IND", battingSide(match.teams)?.shortName, "the side with fewer overs bowled is batting")
        assertEquals("Live: IND 176/4 18.2 ov vs AUS, 2 more live", pillDescription(match, 2))
        assertEquals("176/4" to "18.2 ov", cricketScore("176/4 (18.2 ov)"))
        assertEquals("Live · 2nd T20I · Australia tour of India", cardLabel(match))
        assertEquals("CSK", battingSide(listOf(LiveTeam("Chennai", "CSK", score = "201/5 (20 ov)"), LiveTeam("Mumbai", "MI")))?.shortName)
    }

    @Test fun aChannelIsNamedOnceUnderACard() {
        fun channel(name: String) = Channel(WatchKey.channel("dd", name), "dd", name)
        fun option(name: String) = WatchOption(name, PlayTarget.Live(channel(name)), channel(name), "DD")
        fun show(vararg names: String) = LiveEvent("show:yt:x", LiveEventKind.SHOW, "Sringaar Aarti", statusText = "Live", status = LiveEventStatus.LIVE,
            watchOptions = names.map(::option), source = "YouTube")
        assertEquals("DD News · free", watchLine(show("DD News")))
        assertEquals("DD News, DD National · free", watchLine(show("DD News", "DD National")), "one show on two channels is one line with both")
        assertEquals("DD News, DD National", watchNames(show("DD News", "DD National", "DD News")))
        assertEquals(null, watchLine(show()))
    }

    @Test fun rowsAndTimes() {
        val football = events.first { it.kind == LiveEventKind.FOOTBALL && it.isLive }
        assertEquals("Arsenal 2 – 1 Chelsea", rowTitle(football))
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals("Today 04:40", startText(evening + 150 * 60_000L, evening, zone))
        assertEquals("Tomorrow 04:40", startText(evening + (150 + 24 * 60) * 60_000L, evening, zone))
        assertEquals("Starting", startText(evening - 1, evening, zone))
    }

    @Test fun followingSportsAndTeams() {
        val settings = LiveNowSettings(LiveNowMemoryStore())
        val all = LiveNowLists.of(events, settings.sports.value, settings.teams.value)
        assertEquals(3, all.live.size, "the live match, the live show and the live football")
        assertEquals(1, all.upcoming.size)
        assertEquals(1, all.finished.size)
        settings.setTeam("Chelsea", true)
        settings.setSport(LiveEventKind.SHOW, false)
        val chelsea = LiveNowLists.of(events, settings.sports.value, settings.teams.value)
        assertEquals(listOf("Arsenal v Chelsea"), chelsea.live.map { it.title }, "only the followed team's match, no show")
        settings.setSport(LiveEventKind.FOOTBALL, false)
        settings.setSport(LiveEventKind.CRICKET, false)
        assertTrue(LiveNowLists.of(events, settings.sports.value, settings.teams.value).isEmpty, "nothing followed: the pill hides")
    }

    @Test fun spotlightPrioritisesMatchesAndRotatesInsteadOfPromotingRoutineShows() {
        val followed = LiveNowSettings.followedKinds
        val show = events.first { it.kind == LiveEventKind.SHOW }
        val match = events.first { it.kind == LiveEventKind.CRICKET && it.isLive }
        fun featured(kind: LiveEventKind) = LiveEvent(kind.name, kind, kind.name, status = LiveEventStatus.FEATURED, source = "Test")
        val news = featured(LiveEventKind.NEWS)
        val podcast = featured(LiveEventKind.PODCAST)
        assertEquals(null, LiveNowLists.of(listOf(show), followed, emptyList()).headline(0))
        assertEquals(match, LiveNowLists.of(listOf(show, news, match), followed, emptyList()).headline(0))
        val idle = LiveNowLists.of(listOf(show, news, podcast), followed, emptyList())
        assertEquals(news, idle.headline(0))
        assertEquals(podcast, idle.headline(180_000))
    }

    @Test fun choicesAndRemindersAreKept() {
        val store = LiveNowMemoryStore()
        val settings = LiveNowSettings(store)
        settings.setPillOn(false)
        settings.setTeam("India", true)
        settings.setSport(LiveEventKind.SHOW, false)
        val upcoming = events.first { it.status == LiveEventStatus.UPCOMING }
        settings.toggleReminder(upcoming)
        val again = LiveNowSettings(store)
        assertFalse(again.pillOn.value)
        assertEquals(listOf("India"), again.teams.value)
        assertEquals(setOf(LiveEventKind.CRICKET, LiveEventKind.FOOTBALL), again.sports.value)
        assertEquals(listOf(upcoming.id), again.reminders.value.map { it.id })
        assertTrue(again.takeDue(upcoming.startMs!! - 60 * 60_000L).isEmpty(), "an hour early: not yet")
        assertEquals(listOf(upcoming.id), again.takeDue(upcoming.startMs!! - 4 * 60_000L).map { it.id }, "five minutes before: due, and taken out")
        assertTrue(LiveNowSettings(store).reminders.value.isEmpty())
    }

    @Test fun thePillIsInEveryModesTopBar() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        fun read(path: String) = File(root, path).readText()
        assertTrue("DesktopLiveNowPill()" in read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopChrome.kt"), "the desktop's bar, kept by every mode")
        assertTrue("LiveNowStrip(" in read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchApp.kt"), "Watch's phone header")
        assertTrue("LiveNowStrip(" in read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/podio/ui/PodioChrome.kt"), "Podio's phone header")
        assertTrue("LiveNowStrip(" in read("manga/src/jvmSharedMain/kotlin/com/cosmotune/manga/ui/ReadKit.kt"), "Manga's and Read's phone header")
        assertTrue("LiveNowStrip(" in read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/home/OrbitHome.kt"), "Music's home, under the phone's bar")
        assertTrue("LiveNowStrip(" in read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/home/VideoHome.kt"), "Video's home, under the phone's bar")
        assertTrue("LocalLiveNowInPage provides" in read("app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt"), "the phone puts it under each page's bar")
        assertTrue("TvLiveChip(" in read("tv/src/main/java/com/cosmotune/music/ui/tv/TvApp.kt"), "the TV's chips row")
    }
}
