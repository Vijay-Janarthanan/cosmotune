package com.cosmotune.ui

import com.cosmotune.ui.live.LiveNowMemoryStore
import com.cosmotune.ui.live.LiveNowSettings
import com.cosmotune.ui.live.SpotlightLists
import com.cosmotune.ui.live.SpotlightTab
import com.cosmotune.ui.live.agoText
import com.cosmotune.ui.live.cricketScore
import com.cosmotune.ui.live.followedBy
import com.cosmotune.ui.live.inText
import com.cosmotune.ui.live.newGoals
import com.cosmotune.ui.live.outletsLine
import com.cosmotune.ui.live.outletsTag
import com.cosmotune.ui.live.scorerLines
import com.cosmotune.ui.live.startText
import com.cosmotune.ui.live.stripLine
import com.cosmotune.ui.live.surname
import com.cosmotune.ui.live.updatedText
import com.cosmotune.ui.live.watchLine
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.IncidentType
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.MatchIncident
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.model.WatchOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The words and lists of the Spotlight (Canvas repo, design/cosmotune/search-spotlight/board.html, section 4) on the fake repository's
 * events, and the places it is drawn: the strip in the desktop's top bar of every mode, under each phone header, in the TV's chips row.
 */
class LiveNowTextTest {
    private val evening = 1_762_461_600_000L
    private val events = runBlocking { FakeWatchRepository().liveEvents().first() }
    private val all = LiveNowSettings.followedKinds
    private val arsenal get() = events.first { it.title == "Arsenal v Chelsea" }

    @Test fun theSheetHasItsTabsAndTopPutsFollowedLiveMatchesFirst() {
        val lists = SpotlightLists(events, all, emptyList(), emptyList(), evening)
        assertEquals(listOf(SpotlightTab.TOP, SpotlightTab.WORLD, SpotlightTab.COUNTRY, SpotlightTab.FOOTBALL, SpotlightTab.CRICKET, SpotlightTab.COMING), lists.tabs())
        assertEquals("India", lists.tabName(SpotlightTab.COUNTRY), "the country's tab is named after it")
        assertEquals(listOf("Arsenal v Chelsea", "India v New Zealand, 2nd Test"), lists.topMatches.map { it.title })
        val followed = SpotlightLists(events, all, listOf("New Zealand"), emptyList(), evening)
        assertEquals("India v New Zealand, 2nd Test", followed.topMatches.first().title, "a followed match comes first")
        assertEquals("India v New Zealand, 2nd Test", followed.headline()?.title, "the strip: a followed live match")
        assertEquals("Climate talks end without a deal on fossil fuels", lists.headline()?.title, "nothing followed: the biggest world story")
        assertTrue(lists.world.none { it.local }, "the country's stories are not in World")
        assertEquals(listOf("Real Madrid v Liverpool", "2026 Spanish general election"), lists.coming.map { it.title }, "Coming up: big matches, then major events")
        assertEquals(listOf("Barcelona v Sevilla"), lists.finished(LiveEventKind.FOOTBALL).map { it.title })
        val cricketOff = SpotlightLists(events, setOf(LiveEventKind.FOOTBALL), emptyList(), emptyList(), evening)
        assertFalse(SpotlightTab.CRICKET in cricketOff.tabs(), "a sport turned off has no tab")
        assertTrue(cricketOff.shows.isEmpty(), "live shows off")
    }

    @Test fun followsAreTeamsByNameOrShortNameAndCompetitions() {
        assertTrue(arsenal.followedBy(listOf("arsenal"), emptyList()))
        assertTrue(arsenal.followedBy(listOf("CHE"), emptyList()))
        assertTrue(arsenal.followedBy(emptyList(), listOf("eng.1")))
        assertFalse(arsenal.followedBy(listOf("Liverpool"), listOf("esp.1")))
        assertFalse(events.first { it.kind == LiveEventKind.NEWS }.followedBy(listOf("India"), emptyList()), "a story is never a follow")
    }

    @Test fun theStripSaysTheMinuteTheScoreAndTheLatestScorerAndNeverLiveForNews() {
        val line = stripLine(arsenal, evening)
        assertEquals("67'", line.label)
        assertEquals("Arsenal 2–1 Chelsea · Saka 54'", line.text)
        assertTrue(line.inPlay)
        assertEquals("68'", stripLine(arsenal, evening + 61_000).label, "the minute goes on between reads")
        val story = stripLine(events.first { it.kind == LiveEventKind.NEWS && !it.local }, evening)
        assertEquals("World", story.label)
        assertFalse(story.inPlay, "news never has the live dot")
        val test = stripLine(events.first { it.kind == LiveEventKind.CRICKET }, evening)
        assertEquals("India 287/6 v New Zealand · India lead by 197 runs", test.text)
    }

    @Test fun scorersGoUnderTheirSideWithTheirMinutes() {
        val lines = scorerLines(arsenal)
        assertEquals(listOf("Saka 12', 54'"), lines.filter { it.goal && it.side == 0 }.map { it.text })
        assertEquals(listOf("Palmer 33' (pen)"), lines.filter { it.goal && it.side == 1 }.map { it.text })
        assertEquals(listOf("Rice 41'"), lines.filter { !it.goal }.map { it.text })
        assertEquals("van Dijk", surname("Virgil van Dijk"))
        assertEquals("Raphinha", surname("Raphinha"))
        assertEquals("287/6" to "78.2 ov", cricketScore("287/6 (78.2 ov)"))
    }

    @Test fun storiesSayHowManyOutletsCarryThem() {
        val story = events.first { it.title.startsWith("Climate") }
        assertEquals("5 OUTLETS", outletsTag(story))
        assertEquals("BBC · Guardian · Al Jazeera · 40 min", outletsLine(story, evening))
        assertNull(outletsTag(events.first { it.local }), "one outlet: no tag")
    }

    @Test fun timesAreSaidPlainly() {
        assertEquals("in 2 h", inText(evening + 150 * 60_000L, evening))
        assertEquals("in 12 days", inText(evening + 12 * 86_400_000L, evening))
        assertEquals("in 12 min", inText(evening + 12 * 60_000L, evening))
        assertEquals("40 min", agoText(evening - 40 * 60_000L, evening))
        assertEquals("Updated 12 s ago", updatedText(evening - 12_000, evening))
        assertEquals("Updated just now", updatedText(evening, evening))
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals("Today 02:40", startText(evening + 30 * 60_000L, evening, zone))
        assertEquals("Starting", startText(evening - 1, evening, zone))
    }

    @Test fun aGoalInAFollowedMatchIsToldOnceAndNotOnOpening() {
        val before = listOf(arsenal)
        val goal = arsenal.copy(teams = arsenal.teams.mapIndexed { i, t -> if (i == 1) t.copy(score = "2") else t },
            incidents = arsenal.incidents + MatchIncident(1, "71'", "Nicolas Jackson", IncidentType.GOAL))
        val follows: (LiveEvent) -> Boolean = { it.followedBy(listOf("Chelsea"), emptyList()) }
        val alerts = newGoals(before, listOf(goal), follows)
        assertEquals("Goal · Arsenal 2–2 Chelsea", alerts.single().title)
        assertEquals("Jackson 71'", alerts.single().text)
        assertTrue(newGoals(emptyList(), listOf(goal), follows).isEmpty(), "just opened: what was already scored is not news")
        assertTrue(newGoals(before, listOf(goal)) { false }.isEmpty(), "not followed: nothing told")
        assertTrue(newGoals(listOf(goal), listOf(arsenal), follows).isEmpty(), "a goal taken back is not a goal")
    }

    @Test fun aChannelIsNamedOnceUnderACard() {
        fun channel(name: String) = Channel(WatchKey.channel("dd", name), "dd", name)
        fun option(name: String) = WatchOption(name, PlayTarget.Live(channel(name)), channel(name), "DD")
        fun show(vararg names: String) = LiveEvent("show:yt:x", LiveEventKind.SHOW, "Sringaar Aarti", statusText = "Live", status = LiveEventStatus.LIVE,
            watchOptions = names.map(::option), source = "YouTube")
        assertEquals("DD News · free", watchLine(show("DD News")))
        assertEquals("DD News, DD National · free", watchLine(show("DD News", "DD National")))
        assertEquals(null, watchLine(show()))
    }

    @Test fun choicesFollowsAndRemindersAreKept() {
        val store = LiveNowMemoryStore()
        val settings = LiveNowSettings(store)
        settings.setPillOn(false)
        settings.setTeam("India", true)
        settings.setCompetition("eng.1", true)
        settings.setSport(LiveEventKind.SHOW, false)
        settings.learn(events)
        val upcoming = events.first { it.status == LiveEventStatus.UPCOMING && it.isMatch }
        settings.toggleReminder(upcoming)
        val again = LiveNowSettings(store)
        assertFalse(again.pillOn.value)
        assertEquals(listOf("India"), again.teams.value)
        assertEquals(listOf("eng.1"), again.competitions.value)
        assertEquals(setOf(LiveEventKind.CRICKET, LiveEventKind.FOOTBALL), again.sports.value)
        assertTrue(again.knownTeams.value.any { it.name == "Chelsea" && it.competition == "Premier League" }, "the sides seen are kept for Settings' search")
        assertTrue(again.knownCompetitions.value.any { it.id == "24469" }, "a cricket series seen is offered too")
        assertTrue(again.knownCompetitions.value.any { it.id == "eng.1" && it.name == "Premier League" }, "ESPN's football competitions are always offered")
        assertEquals(listOf(upcoming.id), again.reminders.value.map { it.id })
        assertTrue(again.takeDue(upcoming.startMs!! - 60 * 60_000L).isEmpty(), "an hour early: not yet")
        assertEquals(listOf(upcoming.id), again.takeDue(upcoming.startMs!! - 4 * 60_000L).map { it.id }, "five minutes before: due, and taken out")
    }

    @Test fun theStripIsInEveryModesTopBar() {
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

    @Test fun nothingIsAskedWhileTheWindowIsAwayAndEveryHostOpensMatches() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        fun read(path: String) = File(root, path).readText().replace(Regex("""\s+"""), " ")
        val live = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/live/LiveNow.kt")
        assertTrue("val onScreen = foreground && shown" in live, "on screen: the lifecycle and the host's own word")
        assertTrue("LaunchedEffect(host, interest, onScreen) { if (!onScreen) return@LaunchedEffect" in live, "collected only while on screen")
        assertTrue("host.events(interest).collect" in live, "for what is followed and how closely it is looked at")
        assertTrue("LookingAt(host, LiveFocus.SHEET)" in live && "LookingAt(host, LiveFocus.DETAIL)" in live, "the sheet and a match read more often while open")
        assertTrue("shown = com.cosmotune.music.desktop.DesktopWindowVisibility.visible" in read("desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopChrome.kt"),
            "the desktop's window in the tray stops the asking")
        for (host in listOf("app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt", "desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopChrome.kt",
            "tv/src/main/java/com/cosmotune/music/ui/tv/TvApp.kt")) {
            val text = read(host)
            assertTrue(Regex("""events = \{ interest -> [^}]*liveEvents\(interest\) }""").containsMatchIn(text), "$host passes what is followed")
            assertTrue(Regex("""detail = \{ event -> [^}]*matchDetail\(event\) }""").containsMatchIn(text), "$host opens a match's detail")
        }
    }
}
