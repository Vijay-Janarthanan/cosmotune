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
}
