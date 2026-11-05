package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomMeta
import com.cosmotune.room.RoomState
import com.cosmotune.ui.room.RoomActions
import com.cosmotune.ui.room.RoomScreen
import com.cosmotune.ui.room.RoomTags
import com.cosmotune.ui.room.RoomUiState
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The room screen from a fake state: its pages, the request card, the voice bar, the composer and the wide layout. */
class RoomScreenInteractionTest {
    val compose = createComposeRule()

    // The pager and the chat list animate while the clock is driven; the harness can race itself on a slow runner (see MangaInteractionTest).
    @get:Rule val rules: RuleChain = RuleChain.outerRule(RetryOnComposeHarnessRace()).around(compose)

    /** Every call the screen makes, in order, as "name(argument)". */
    private class Recorder : RoomActions {
        val calls = mutableListOf<String>()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seekTo($positionMs)" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun playTrack(track: PartyTrack) { calls += "playTrack(${track.videoId})" }
        override fun removeFromQueue(videoId: String) { calls += "removeFromQueue($videoId)" }
        override fun clearQueue() { calls += "clearQueue" }
        override fun sendChat(text: String) { calls += "sendChat($text)" }
        override fun askToPlay() { calls += "askToPlay" }
        override fun setMicLive(live: Boolean) { calls += "setMicLive($live)" }
        override fun setDeafened(deafened: Boolean) { calls += "setDeafened($deafened)" }
        override fun setHostOnlyControl(enabled: Boolean) { calls += "setHostOnlyControl($enabled)" }
        override fun removeMember(uid: String) { calls += "removeMember($uid)" }
        override fun copyInviteCode(code: String) { calls += "copyInviteCode($code)" }
        override fun shareInvite(code: String) { calls += "shareInvite($code)" }
        override fun leave() { calls += "leave" }
        override fun dismissMessage() { calls += "dismissMessage" }
        override fun chatSeen() { calls += "chatSeen" }
    }

    private val playing = PartyTrack("v-now", "Kannalane", "A. R. Rahman", durationMs = 300_000)
    private val upNext = PartyTrack("v-next", "Munbe Vaa", "A. R. Rahman", durationMs = 240_000)
    private val asked = PartyTrack("v-asked", "Nenjukkul Peidhidum", "Harris Jayaraj", durationMs = 280_000)

    private fun member(seat: Int, uid: String, name: String, host: Boolean = false, mic: Boolean = false, connected: Boolean = true) =
        RoomMember(seat, uid, name, null, "device-$uid", 0, 0, micLive = mic, isHost = host, connected = connected)

    /** A host (Mira), Anu and you (Ravi): a song playing with one more queued, and a chat with a request card. */
    private fun room(hostOnly: Boolean = false) = RoomState(
        code = "K7Q2MX",
        meta = RoomMeta(createdAtMs = 0, hostUid = "mira", maxMembers = 5, hostOnlyControl = hostOnly),
        members = listOf(member(0, "mira", "Mira", host = true, mic = true), member(1, "anu", "Anu", connected = false), member(2, "ravi", "Ravi")),
        playback = PartyPlayback(track = playing, isPlaying = true, queueIndex = 0, queueLength = 2, startedBy = "mira", startedByName = "Mira"),
        queue = PartyQueue(index = 0, items = listOf(playing, upNext)),
        chat = listOf(
            RoomChatMessage("m1", "anu", "Anu", "text", "This one is lovely", null, 1_000),
            RoomChatMessage("m2", "anu", "Anu", "text", "Can we have mine next?", null, 2_000),
            RoomChatMessage("m3", "anu", "Anu", "request", "", asked, 3_000),
            RoomChatMessage("m4", "mira", "Mira", "system", "Mira skipped", null, 4_000),
        ),
        serverNowMs = 5_000,
    )

    private fun state(selfUid: String = "ravi", hostOnly: Boolean = false, voice: Boolean = false, unread: Int = 0, connection: RoomConnection = RoomConnection.LIVE) =
        RoomUiState(room = room(hostOnly), connection = connection, selfUid = selfUid, positionMs = 90_000, micLive = false, voiceAvailable = voice, unreadChat = unread)

    @Composable
    private fun NewLook(wide: Boolean, content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(if (wide) 1200f else 400f, FontFamily.Default, FontFamily.Default)
        MaterialTheme {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                Box(if (wide) Modifier.size(1200.dp, 800.dp) else Modifier.size(400.dp, 900.dp)) { content() }
            }
        }
    }

    private fun show(state: RoomUiState, wide: Boolean = false): Recorder {
        val actions = Recorder()
        compose.setContent { NewLook(wide) { RoomScreen(state, actions, wide) } }
        return actions
    }

    @Test fun thePhoneHasThreePagesThatSwitchAndTheVoiceBarStays() {
        val actions = show(state(unread = 3))
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).assertExists()
        compose.onNodeWithText("Kannalane", substring = false).assertExists()
        compose.onNodeWithText("Started by Mira").assertExists()
        compose.onNodeWithTag(RoomTags.UNREAD, useUnmergedTree = true).assertExists()
        assertTrue("chatSeen" !in actions.calls, "the chat was not opened yet")

        compose.onNodeWithTag(RoomTags.TAB_CHAT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.TAB_CHAT).assertIsSelected()
        compose.onNodeWithTag(RoomTags.PAGE_CHAT).assertIsDisplayed()
        compose.onNodeWithText("Can we have mine next?").assertExists()
        compose.onNodeWithText("Anu asks: play this").assertExists()
        compose.onNodeWithTag(RoomTags.UNREAD, useUnmergedTree = true).assertDoesNotExist()
        assertTrue("chatSeen" in actions.calls, "opening the chat tells the host it was seen")
        compose.onNodeWithTag(RoomTags.MIC).assertIsDisplayed()

        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.PAGE_PEOPLE).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.member("mira")).assertExists()
        compose.onNodeWithText("Host", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("You", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Away", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RoomTags.MIC).assertIsDisplayed()

        compose.onNodeWithTag(RoomTags.TAB_NOW_PLAYING).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).assertIsDisplayed()
    }

    @Test fun playNowPlaysTheRequestWhenControlIsOpen() {
        val actions = show(state())
        compose.onNodeWithTag(RoomTags.TAB_CHAT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.playNow("m3")).assertIsEnabled().performClick()
        assertTrue("playTrack(v-asked)" in actions.calls, "Play now plays the asked-for song: ${actions.calls}")
    }

    @Test fun playNowAndTheTransportAreDisabledWhenOnlyTheHostControls() {
        val actions = show(state(hostOnly = true))
        compose.onNodeWithTag(RoomTags.PLAY_PAUSE).assertIsNotEnabled()
        compose.onNodeWithTag(RoomTags.NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(RoomTags.HOST_ONLY_HINT).assertExists()
        compose.onNodeWithTag(RoomTags.TAB_CHAT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.playNow("m3")).assertIsNotEnabled().performClick()
        compose.onNodeWithText("Only the host can control the music here").assertExists()
        assertTrue(actions.calls.none { it.startsWith("playTrack") || it == "next" || it == "pause" }, "nothing reached the music: ${actions.calls}")
    }

    @Test fun theHostStillControlsAHostOnlyRoom() {
        val actions = show(state(selfUid = "mira", hostOnly = true))
        compose.onNodeWithTag(RoomTags.PLAY_PAUSE).assertIsEnabled().performClick()
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).performScrollToNode(hasTestTag(RoomTags.queueRow(1)))
        compose.onNodeWithTag(RoomTags.queueRemove(0)).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.queueRemove(1)).assertExists()
        compose.onNodeWithTag(RoomTags.queueRow(1)).performClick()
        assertEquals(listOf("pause", "playTrack(v-next)"), actions.calls.filter { it != "chatSeen" })
    }

    @Test fun theMicExplainsInsteadOfTogglingUntilVoiceExists() {
        val actions = show(state(voice = false))
        compose.onNodeWithTag(RoomTags.MIC)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Microphone, voice calls are coming soon")))
            .performClick()
        compose.onNodeWithTag(RoomTags.VOICE_NOTE).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.MIC).performClick()
        assertTrue(actions.calls.none { it.startsWith("setMicLive") }, "the mic never claims to go live: ${actions.calls}")
    }

    @Test fun theMicTogglesOnceVoiceExists() {
        val actions = show(state(voice = true))
        compose.onNodeWithTag(RoomTags.MIC)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Microphone muted, tap to unmute")))
            .performClick()
        compose.onNodeWithTag(RoomTags.VOICE_NOTE).assertDoesNotExist()
        assertTrue("setMicLive(true)" in actions.calls, "${actions.calls}")
        compose.onNodeWithTag(RoomTags.DEAFEN).performClick()
        assertTrue("setDeafened(true)" in actions.calls, "${actions.calls}")
    }

    @Test fun sendPostsTheTypedLineAndClearsTheField() {
        val actions = show(state())
        compose.onNodeWithTag(RoomTags.TAB_CHAT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(RoomTags.COMPOSER).performTextInput("  Next one is mine  ")
        compose.onNodeWithTag(RoomTags.SEND).assertIsEnabled().performClick()
        assertTrue("sendChat(Next one is mine)" in actions.calls, "${actions.calls}")
        compose.onNodeWithTag(RoomTags.SEND).assertIsNotEnabled()
    }

    @Test fun theWideLayoutShowsNowPlayingBesideChatAndPeople() {
        val actions = show(state(), wide = true)
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.WIDE_PANEL).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.PAGE_CHAT).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.TAB_NOW_PLAYING).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.MIC).assertIsDisplayed()
        assertTrue("chatSeen" in actions.calls, "the wide chat is open from the start")
        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.onNodeWithTag(RoomTags.PAGE_PEOPLE).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).assertIsDisplayed()
    }

    @Test fun theHostRemovesSomeoneOnlyAfterConfirming() {
        val actions = show(state(selfUid = "mira"), wide = true)
        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.onNodeWithTag(RoomTags.removeMember("mira")).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.removeMember("anu")).performClick()
        assertTrue(actions.calls.none { it.startsWith("removeMember") }, "a tap only asks")
        compose.onNodeWithText("Remove Anu from the room?").assertExists()
        compose.onNodeWithText("Remove", substring = false).performClick()
        assertTrue("removeMember(anu)" in actions.calls, "${actions.calls}")
        compose.onNodeWithTag(RoomTags.PAGE_PEOPLE).performScrollToNode(hasTestTag(RoomTags.HOST_ONLY_SWITCH))
        compose.onNodeWithTag(RoomTags.HOST_ONLY_SWITCH).performClick()
        assertTrue("setHostOnlyControl(true)" in actions.calls, "${actions.calls}")
    }

    /** Not an assertion: the pages as pictures under build/room-review, dark and light, for a person to look at. */
    @Test fun reviewScreenshots() {
        var dark by mutableStateOf(true)
        var wide by mutableStateOf(false)
        val actions = Recorder()
        compose.setContent {
            val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
                .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(dark), dark))
            val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
                .newInstance(if (wide) 1200f else 400f, FontFamily.Default, FontFamily.Default)
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                    Box((if (wide) Modifier.size(1200.dp, 800.dp) else Modifier.size(400.dp, 860.dp)).testTag("room:root")) {
                        RoomScreen(state(hostOnly = !wide, unread = 2).copy(message = if (wide) "Only the host can skip here" else null), actions, wide)
                    }
                }
            }
        }
        fun shot(name: String) {
            compose.waitForIdle()
            val file = File("../build/room-review/$name.png").apply { parentFile.mkdirs() }
            ImageIO.write(compose.onNodeWithTag("room:root").captureToImage().toAwtImage(), "png", file)
        }
        for (theme in listOf(true, false)) {
            dark = theme; wide = false
            val suffix = if (theme) "dark" else "light"
            compose.onNodeWithTag(RoomTags.TAB_NOW_PLAYING).performClick(); shot("phone-now-playing-$suffix")
            compose.onNodeWithTag(RoomTags.TAB_CHAT).performClick(); shot("phone-chat-$suffix")
            compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick(); shot("phone-people-$suffix")
            wide = true; shot("wide-$suffix")
        }
    }

    @Test fun theInBetweenStatesAreDrawn() {
        var current by mutableStateOf(RoomUiState(room = null, connection = RoomConnection.CONNECTING, selfUid = "ravi", positionMs = 0))
        val actions = Recorder()
        compose.setContent { NewLook(false) { RoomScreen(current, actions, wide = false) } }
        compose.onNodeWithTag(RoomTags.CONNECTING).assertIsDisplayed()
        compose.onNodeWithText("Joining the room…").assertExists()

        current = state(connection = RoomConnection.RECONNECTING).copy(message = "Only the host can skip here")
        compose.waitForIdle()
        compose.onNodeWithText("Reconnecting… your music keeps playing").assertExists()
        compose.onNodeWithTag(RoomTags.BANNER_MESSAGE).assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertTrue("dismissMessage" in actions.calls, "${actions.calls}")

        current = state(connection = RoomConnection.REMOVED)
        compose.waitForIdle()
        compose.onNodeWithText("You were removed from this room").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        current = state(connection = RoomConnection.ENDED)
        compose.waitForIdle()
        compose.onNodeWithText("The room has ended").assertIsDisplayed()
        assertEquals(listOf("leave"), actions.calls.filter { it == "leave" })
    }
}
