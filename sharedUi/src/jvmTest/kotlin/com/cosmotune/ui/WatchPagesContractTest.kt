package com.cosmotune.ui

import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.ui.watch.asSummary
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.WatchKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Watch's pages promise a person who presses things: nothing drawn as a button does nothing. */
class WatchPagesContractTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun read(path: String) = File(root, path).readText().replace(Regex("""\s+"""), " ")

    @Test fun switchingLiveChannelsReplacesThePlayerAboveTheGuide() {
        val first = Channel(WatchKey.channel("test", "first"), "test", "First")
        val second = Channel(WatchKey.channel("test", "second"), "test", "Second")
        try {
            WatchNav.go(WatchTab.LIVE)
            WatchNav.playChannel(PlayTarget.Live(first), first.asSummary())
            WatchNav.playChannel(PlayTarget.Live(second), second.asSummary())
            assertEquals(1, WatchNav.stack.value.size)
            assertTrue(WatchNav.back())
            assertTrue(WatchNav.stack.value.isEmpty())
            assertEquals(WatchTab.LIVE, WatchNav.tab.value)
        } finally {
            WatchNav.go(WatchTab.HOME)
        }
    }

    @Test fun theCastArePicturesNotButtonsThatDoNothing() {
        val title = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchTitlePage.kt")
        assertTrue("OrbitPortrait(person.name, person.photoUrl, 96.dp, fonts, look, onClick = null)" in title, "a cast portrait has no click")
        assertFalse(Regex("""OrbitPortrait\([^)]*onClick = \{\s*\}""").containsMatchIn(title), "no portrait with an empty click")
        val portrait = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/orbit/OrbitBrowse.kt")
        assertTrue("onClick: (() -> Unit)?," in portrait, "the portrait's click may be left out")
        assertTrue(".then(if (onClick != null) Modifier.longPressMenuClickable(onClick = onClick, onLongClick = onLongClick)" in portrait, "and then it is not clickable")
    }

    @Test fun muteAndAudioHaveTheirOwnIcons() {
        val player = read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchPlayerPage.kt")
        assertTrue("WatchRoundButton(CosmoIcons.Volume, if (state.muted) \"Unmute\" else \"Mute\"" in player, "Mute is the speaker")
        assertTrue("WatchRoundButton(CosmoIcons.Waveform, \"Audio\"" in player, "the round Audio button is the waveform")
        assertTrue("WatchCardChip(\"Audio\", ink, fonts, onClick = { audio = true }, icon = CosmoIcons.Waveform" in player, "the Audio chip is the waveform")
        assertFalse(Regex("""CosmoIcons\.Volume, "Audio"|"Audio"[^)]*icon = CosmoIcons\.Volume""").containsMatchIn(player), "Audio never borrows the speaker")
    }

    @Test fun fullScreenIsTheSharedPlayerAndKeepsTheCountdownAndSkipIntroWithTheControlsHidden() {
        val src = File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchPlayerPage.kt").readText()
        val screen = SourceContract.functionBody(src, "WatchPlayerScreen").replace(Regex("""\s+"""), " ")
        assertTrue("cinema -> com.cosmotune.ui.fullscreen.FullScreenWindow({ cinema = false }, landscape = aspect >= 1f) {" in screen, "the host's full window, a wide film held sideways")
        val full = SourceContract.functionBody(src, "WatchFullScreen").replace(Regex("""\s+"""), " ")
        assertTrue("com.cosmotune.ui.fullscreen.FullScreenPlayer(" in full, "the one plain player Now Playing and Video use")
        assertTrue("transport = com.cosmotune.ui.fullscreen.FullScreenTransport.Skip" in full, "back and ahead 10 s round play")
        for (menu in listOf("\"Captions\"", "\"Audio\"", "\"Sources\"")) assertTrue(menu in full, "$menu is in the ⋯ menu")
        // The overlay is drawn whether the controls show or not: the countdown and Skip intro are always a tap away.
        val overlay = full.substringAfter("overlay = { shown ->")
        assertTrue(Regex("""state\.nextEpisode != null && state\.nextEpisodeInMs != null.*NextEpisodeRow\(state, controls,""").containsMatchIn(overlay), "the countdown row with Play now and Cancel")
        assertTrue("state.skipIntervals.firstOrNull { position() in it.startMs until it.endMs }" in overlay && "controls.seekTo(skip.endMs)" in overlay, "Skip intro")
        assertTrue("val lift = if (shown) 92.dp else 24.dp" in overlay, "above the seek bar while the controls show")
        assertTrue("holdControls = failed" in full, "a failure keeps the controls up")
    }

    // ---- the redesign (Video and Watch board, frames 6 to 12) ---------------------------------------------------------------

    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/"

    @Test fun thePhoneHasFourTabsAndTheProfileMenuHoldsPluginsAndSettings() {
        assertEquals(listOf(WatchTab.HOME, WatchTab.LIVE, WatchTab.MY_LIST, WatchTab.DOWNLOADS), com.cosmotune.ui.watch.WATCH_PHONE_TABS)
        assertEquals(setOf(WatchTab.PLUGINS, WatchTab.SETTINGS), com.cosmotune.ui.watch.WATCH_MENU_PAGES)
        val app = read(dir + "WatchApp.kt")
        assertFalse("WATCH_PHONE_TABS.forEach { t -> OrbitSelectChip(" in app, "the tabs are no longer chips in the head")
        assertTrue("com.cosmotune.ui.shell.CosmoNavShell(" in app && "selectedIndex = WATCH_PHONE_TABS.indexOf(lit)," in app && "onTabSelected = { WatchNav.go(WATCH_PHONE_TABS[it]) }," in app,
            "the four tabs stand in the shared bottom bar (or the rail from 600 dp)")
        assertTrue("navigation = navShown," in app && "val navShown = showNavigation && top !is WatchRoute.Play" in app, "on every page but the player")
        assertFalse("WatchTab.entries.forEach" in app, "no chip for every page any more")
        for (entry in listOf("OrbitMenuItem(\"Plugins\"", "OrbitMenuItem(\"Settings\"")) assertTrue(entry in app, "the profile menu has $entry")
        assertFalse("OrbitMenuItem(\"Downloads\"" in app, "Downloads is a tab now, not in the menu")
        assertTrue("showNavigation && tab in WATCH_MENU_PAGES -> ({ WatchBackHeader(" in app, "the menu's pages come back with a back button")
    }

    @Test fun searchIsTheRoundButtonBesideThePillAndPressedAgainClearsTheBox() {
        val app = read(dir + "WatchApp.kt")
        assertTrue("com.cosmotune.ui.shell.CosmoNavSearch(\"Search\", selected = tab == WatchTab.SEARCH, onClick = WatchNav::tapSearch)" in app)
        assertTrue("search = search," in app && "onHeightChanged = { dockPx = it }, search = search," in app, "the bar and the rail both carry it")
        assertFalse(WatchTab.SEARCH in com.cosmotune.ui.watch.WATCH_PHONE_TABS, "no tab is lit while Search is open")
        try {
            WatchNav.go(WatchTab.HOME)
            WatchNav.tapSearch()
            assertEquals(WatchTab.SEARCH, WatchNav.tab.value)
            val before = WatchNav.searchAgain.value
            WatchNav.tapSearch()
            assertEquals(before + 1, WatchNav.searchAgain.value, "pressed again on Search: the page clears and focuses its box")
        } finally {
            WatchNav.go(WatchTab.HOME)
        }
        val page = read(dir + "WatchSearchPage.kt")
        assertTrue("LaunchedEffect(again) { if (again != seenAgain) { seenAgain = again; text = \"\"; focus.request() } }" in page)
        assertTrue("CosmoSearchPage(" in page && "repository.find(query, year)" in page, "the shared page, asking the sources with the filters")
        assertTrue("onClick = { open(s) }" in page && "actions.open(s)" in page, "a result opens its page")
        assertTrue("if (sources.size > 1) add(CosmoSearchFilter(\"source\"" in page, "Source only with more than one source on")
    }

    @Test fun theStatusBarBandIsSolidOverTheBar() {
        val app = read(dir + "WatchApp.kt")
        assertTrue("height(with(density) { bar.toDp() }).then(Modifier.cosmoTopGround(shown = { 1f }))" in app, "solid over the bar in the app's own ground, fading only below it")
        assertFalse("OrbitStatusBarScrim(" in app, "not the see-through scrim")
    }

    @Test fun backFromPluginsReturnsToWatchSettings() {
        try {
            WatchNav.go(WatchTab.PLUGINS)
            assertTrue(WatchNav.back())
            assertEquals(WatchTab.SETTINGS, WatchNav.tab.value)
            assertTrue(WatchNav.back())
            assertEquals(WatchTab.HOME, WatchNav.tab.value)
        } finally {
            WatchNav.go(WatchTab.HOME)
        }
    }

    @Test fun homeHasContinueWatchingFirstAndOnlyAllFilmsAndSeries() {
        val home = read(dir + "WatchHomePage.kt")
        assertTrue("listOf(HomeFilter.ALL to \"All\", HomeFilter.FILMS to \"Films\", HomeFilter.SERIES to \"Series\")" in home, "Live TV is its own tab, not a filter")
        assertTrue(home.indexOf("item(key = \"watch:continue\")") in 0 until home.indexOf("item(key = \"watch:featured\")"), "Continue watching before the featured card")
        assertTrue("height(if (wide) 250.dp else 220.dp)" in home, "the featured card is about 220 dp on a phone")
        assertFalse("WatchCardChip(if (inList)" in home, "My list is a round action, not a chip")
        assertTrue("WatchNav.go(WatchTab.LIVE)" in home && "items.take(if (wide) 6 else 3)" in home, "Live now: three rows and See all")
        assertTrue("progress = watched[s.key]" in home, "posters in the rows show how far they were watched")
    }

    @Test fun aTitlePageHasOneMainActionAndASeriesAlwaysPlays() {
        val title = read(dir + "WatchTitlePage.kt")
        assertFalse(Regex("""WatchCardChip\([^)]*Resume""").containsMatchIn(title), "no Resume chip beside the bite")
        assertTrue("val playable = !series || nextUp != null || firstEpisode != null" in title, "a series not started plays its first episode")
        assertTrue("series && first != null && page != null -> actions.playEpisode(page, first)" in title)
        assertTrue("WatchCardActions(cardActions, ink, fill, fonts" in title, "the other actions as one row")
        assertFalse("OrbitMenuItem(if (inList) \"Remove from My list\"" in title, "My list is not in the menu twice")
        assertTrue(title.indexOf("item(key = \"title:sources\")") in 0 until title.indexOf("item(key = \"title:tabs\")"), "Play from above the tabs")
        assertTrue("private const val PLAY_FROM_SHOWN = 3" in title, "the best three copies, then Show all")
        assertFalse("Text(\"New\"" in title, "no New under every episode")
    }

    @Test fun whatPlayInTheBiteWillDoIsSaidUnderTheTitle() {
        val series = WatchKey("s:tmdb:1")
        val first = com.cosmotune.watch.model.Episode(WatchKey("e:tmdb:1:1:1"), series, 1, 1, "Pilot")
        assertEquals("Play Season 1, Episode 1 · 24 episodes", com.cosmotune.ui.watch.playLine(true, null, first, null, 24))
        assertEquals(null, com.cosmotune.ui.watch.playLine(true, null, null, null, null))
    }

    @Test fun closingThePlayerKeepsTheFilmInTheSmallPlayer() {
        val page = read(dir + "WatchPlayerPage.kt")
        assertTrue("if (!keepsPlaying && (!route.attached || returned)" in page, "where Watch draws the small player the page does not stop the film")
        val app = read(dir + "WatchApp.kt")
        assertTrue("val mini = keepsPlaying && top !is WatchRoute.Play" in app && "WatchMiniPlayer(keptPlayback" in app, "the capsule shows over every other page")
        assertTrue("miniPlayer = if (mini && keptPlayback != null) ({ WatchMiniPlayer(keptPlayback, fonts, look, docked = true) }) else null," in app,
            "on the phone it stands in the dock, above the bottom bar")
        assertTrue("onDispose { if (keptPlayback != null && !pageUnderPlayer) stopWhenLeaving(keptPlayback) }" in app, "leaving Watch stops it")
        val mini = read(dir + "WatchMiniPlayer.kt")
        assertTrue("WatchNav.showPlaying(target, summary)" in mini, "a tap reopens the player on what plays")
        assertTrue("onClick = { player.stop() }" in mini, "close stops the film")
    }

    @Test fun watchSettingsAreTheSharedSettingsRowsWithPluginsInside() {
        val settings = read(dir + "WatchSettingsPage.kt")
        assertTrue("CosmoSettingsGroup(header = \"Sources and add-ons\")" in settings, "Sources and add-ons first")
        assertTrue("CosmoSettingsRow(Icons.Rounded.Extension, \"Plugins\"" in settings && "onClick = { WatchNav.go(WatchTab.PLUGINS) }" in settings, "Plugins is a row of it")
        for (group in listOf("Profiles", "Display", "Connected services")) assertTrue("header = \"$group\"" in settings, "the $group group")
        assertFalse("fun WatchSettingsPage(" in read(dir + "WatchPluginsPage.kt"), "the hand-built page is gone")
    }

    @Test fun myListHasItsTabsWithCountsSortSelectAndUndo() {
        val list = read(dir + "WatchListPages.kt")
        assertTrue("\"My list · \${list.size}\"" in list && "\"Playlists · \${playlists.size}\"" in list && "\"Downloads · \${kept.size}\"" in list)
        assertTrue("MyListSort.entries" in list && "WatchCardChip(\"Select\"" in list)
        assertTrue("LocalOrbitUndo.current" in list && "gone.forEach { actions.setInMyList(it, true) }" in list && "CosmoToast(" !in list,
            "removing says so on the window's Undo snackbar, not the old toast")
        assertTrue("progress = progress?.fraction" in list, "posters show how far they were watched")
    }

    @Test fun liveTvOnThePhoneHasThreeDropdownsAndALogoColumn() {
        val live = read(dir + "WatchLivePage.kt")
        for (name in listOf("\"Country\"", "\"Language\"", "\"Category\"")) assertTrue("FacetChip($name" in live, "the $name dropdown")
        assertTrue("val channelWidth = if (wide) 220.dp else 68.dp" in live, "the phone's channel column is the logo")
        assertTrue("GuideList(window, now, fonts, look, clock, playChannel)" in live, "and the guide can be a list")
    }
}
