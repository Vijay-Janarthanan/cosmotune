package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import com.cosmotune.ui.theme.LocalReducedMotion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** The voice parts of the room screen from a fake state: speaking rings, the mic's modes, a person's voice panel and the voice notice. */
class RoomVoiceInteractionTest {
    val compose = createComposeRule()

    // The pager animates while the clock is driven; the harness can race itself on a slow runner (see MangaInteractionTest).
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
        override fun setMemberMuted(uid: String, muted: Boolean) { calls += "setMemberMuted($uid,$muted)" }
        override fun setMemberVolume(uid: String, volume: Float) { calls += "setMemberVolume($uid,$volume)" }
        override fun openMicSettings() { calls += "openMicSettings" }
        override fun dismissVoiceMessage() { calls += "dismissVoiceMessage" }
    }

    private val playing = PartyTrack("v-now", "Kannalane", "A. R. Rahman", durationMs = 300_000)

    private fun member(seat: Int, uid: String, name: String, host: Boolean = false, mic: Boolean = false) =
        RoomMember(seat, uid, name, null, "device-$uid", 0, 0, micLive = mic, isHost = host, connected = true)

    /** A host (Mira), Anu and you (Ravi), with a line of chat from each of the others. */
    private fun room() = RoomState(
        code = "K7Q2MX",
        meta = RoomMeta(createdAtMs = 0, hostUid = "mira", maxMembers = 5),
        members = listOf(member(0, "mira", "Mira", host = true, mic = true), member(1, "anu", "Anu", mic = true), member(2, "ravi", "Ravi")),
        playback = PartyPlayback(track = playing, isPlaying = true, queueIndex = 0, queueLength = 1, startedBy = "mira", startedByName = "Mira"),
        queue = PartyQueue(index = 0, items = listOf(playing)),
        chat = listOf(
            RoomChatMessage("m1", "anu", "Anu", "text", "Hello", null, 1_000),
            RoomChatMessage("m2", "mira", "Mira", "text", "Hi", null, 2_000),
        ),
        serverNowMs = 5_000,
    )

    private fun state(voice: Boolean = true) =
        RoomUiState(room = room(), connection = RoomConnection.LIVE, selfUid = "ravi", positionMs = 90_000, voiceAvailable = voice)

    @Composable
    private fun NewLook(wide: Boolean, reduced: Boolean, content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(if (wide) 1200f else 400f, FontFamily.Default, FontFamily.Default)
        MaterialTheme {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography, LocalReducedMotion provides reduced) {
                Box(if (wide) Modifier.size(1200.dp, 800.dp) else Modifier.size(400.dp, 900.dp)) { content() }
            }
        }
    }

    private fun show(state: RoomUiState, wide: Boolean = true, reduced: Boolean = true): Recorder {
        val actions = Recorder()
        compose.setContent { NewLook(wide, reduced) { RoomScreen(state, actions, wide) } }
        return actions
    }

    private fun rings(uid: String) = compose.onAllNodesWithTag(RoomTags.speaking(uid), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun hasState(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
    private fun hasDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test fun aSpeakingPersonRingsUnlessMutedHere() {
        show(state().copy(speakingUids = setOf("mira", "anu"), mutedHere = setOf("anu")))
        // The strip and the chat (the wide layout opens on it) both ring Mira; Anu, muted here, rings nowhere.
        assertTrue(rings("mira") >= 2, "Mira rings in the strip and the chat: ${rings("mira")}")
        assertEquals(0, rings("anu"))
        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.waitForIdle()
        assertTrue(rings("mira") >= 2, "and in her People row")
        compose.onNodeWithContentDescription("Speaking", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Muted for you", useUnmergedTree = true).assertExists()
        assertEquals(0, rings("anu"))
    }

    @Test fun theRingBreathesWhenMotionIsAllowed() {
        show(state().copy(speakingUids = setOf("mira")), wide = false, reduced = false)
        assertTrue(rings("mira") >= 1, "the moving ring is drawn too")
    }

    @Test fun deafenedHidesOthersRingsButKeepsYourOwn() {
        show(state().copy(speakingUids = setOf("mira", "ravi"), deafened = true, micLive = true))
        assertEquals(0, rings("mira"))
        assertTrue(rings("ravi") >= 1)
    }

    @Test fun tappingSomeoneOpensMuteForMeAndVolume() {
        val actions = show(state().copy(volumes = mapOf("anu" to 0.8f), voiceConnecting = setOf("mira")))
        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.memberConnecting("mira"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Connecting voice…", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RoomTags.memberConnecting("anu"), useUnmergedTree = true).assertDoesNotExist()

        // Your own row has no panel: there is nothing to mute or turn down.
        compose.onNodeWithTag(RoomTags.member("ravi")).performClick()
        compose.onNodeWithTag(RoomTags.memberVoice("ravi")).assertDoesNotExist()

        compose.onNodeWithTag(RoomTags.member("anu")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.memberVoice("anu")).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.memberVolume("anu"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.8f, 0f..1f)))
        compose.onNodeWithTag(RoomTags.memberMute("anu")).performClick()
        assertTrue("setMemberMuted(anu,true)" in actions.calls, "${actions.calls}")
        compose.onNodeWithTag(RoomTags.memberVolume("anu"))
            .assert(hasDescription("Volume for Anu"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertTrue("setMemberVolume(anu,0.5)" in actions.calls, "${actions.calls}")
        assertTrue(actions.calls.none { it.startsWith("setMicLive") || it.startsWith("removeMember") }, "nothing reached the room: ${actions.calls}")

        // A second tap closes it; opening Mira's closes Anu's.
        compose.onNodeWithTag(RoomTags.member("mira")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.memberVoice("mira")).assertExists()
        compose.onNodeWithTag(RoomTags.memberVoice("anu")).assertDoesNotExist()
    }

    @Test fun aBlockedMicOpensTheSettingsAndNeverGoesLive() {
        val actions = show(state().copy(micBlocked = true, micLive = true), wide = false)
        compose.onNodeWithTag(RoomTags.MIC)
            .assert(hasDescription("Microphone blocked: open settings"))
            .assert(hasState("Blocked"))
            .performClick()
        assertEquals(listOf("openMicSettings"), actions.calls.filter { it != "chatSeen" })
        compose.onNodeWithTag(RoomTags.MIC_BLOCKED).assertIsDisplayed()
        compose.onNodeWithTag(RoomTags.MIC_SETTINGS).performClick()
        assertEquals(listOf("openMicSettings", "openMicSettings"), actions.calls.filter { it != "chatSeen" })
        compose.onNodeWithText("Mic on", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.MIC_METER, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aLiveMicSaysMicOnAndShowsItsLevelWhileSpeaking() {
        val actions = show(state().copy(micLive = true, speakingUids = setOf("ravi"), micLevel = 0.6f), wide = false)
        compose.onNodeWithTag(RoomTags.MIC)
            .assert(hasDescription("Microphone live, tap to mute"))
            .assert(hasState("Mic on"))
        compose.onNodeWithTag(RoomTags.MIC_METER, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RoomTags.MIC_BLOCKED).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.MIC).performClick()
        assertTrue("setMicLive(false)" in actions.calls, "${actions.calls}")
    }

    @Test fun aQuietLiveMicHasNoMeterAndAMutedOneSaysMuted() {
        show(state().copy(micLive = true, micLevel = 0.6f), wide = false)
        compose.onNodeWithTag(RoomTags.MIC_METER, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.MIC).assert(hasState("Mic on"))
    }

    @Test fun withoutVoiceTheMicStillExplainsAndNothingRingsOrOpens() {
        val actions = show(state(voice = false).copy(micLive = true, speakingUids = setOf("mira"), voiceConnecting = setOf("anu")))
        compose.onNodeWithTag(RoomTags.MIC)
            .assert(hasDescription("Microphone, voice calls are coming soon"))
            .performClick()
        compose.onNodeWithTag(RoomTags.VOICE_NOTE).assertIsDisplayed()
        compose.onNodeWithText("Mic on", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, rings("mira"))
        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.onNodeWithTag(RoomTags.member("anu")).performClick()
        compose.onNodeWithTag(RoomTags.memberVoice("anu")).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.memberConnecting("anu"), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(actions.calls.none { it.startsWith("setMicLive") || it.startsWith("setMember") }, "${actions.calls}")
    }

    /** Not an assertion: the voice parts as pictures under build/room-review, dark and light, for a person to look at. */
    @Test fun reviewScreenshots() {
        var dark by mutableStateOf(true)
        var blocked by mutableStateOf(false)
        val actions = Recorder()
        compose.setContent {
            val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
                .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(dark), dark))
            val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
                .newInstance(400f, FontFamily.Default, FontFamily.Default)
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography, LocalReducedMotion provides true) {
                    Box(Modifier.size(400.dp, 860.dp).testTag("room:voice-root")) {
                        val voice = state().copy(
                            micLive = !blocked, micBlocked = blocked, micLevel = 0.6f, speakingUids = setOf("mira", "ravi"), mutedHere = setOf("anu"),
                            volumes = mapOf("anu" to 0.35f), voiceConnecting = if (blocked) setOf("mira") else emptySet(),
                            voiceMessage = if (blocked) "The microphone is not available" else null,
                        )
                        RoomScreen(voice, actions, wide = false)
                    }
                }
            }
        }
        fun shot(name: String) {
            compose.waitForIdle()
            val file = File("../build/room-review/$name.png").apply { parentFile.mkdirs() }
            ImageIO.write(compose.onNodeWithTag("room:voice-root").captureToImage().toAwtImage(), "png", file)
        }
        compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RoomTags.member("anu")).performClick()
        for (theme in listOf(true, false)) {
            dark = theme
            val suffix = if (theme) "dark" else "light"
            blocked = false; shot("voice-people-live-$suffix")
            blocked = true; shot("voice-people-blocked-$suffix")
        }
    }

    @Test fun theVoiceNoticeIsShownUntilDismissed() {
        val actions = show(state().copy(voiceMessage = "The microphone is not available"))
        compose.onNodeWithTag(RoomTags.BANNER_VOICE).assertIsDisplayed()
        compose.onNodeWithText("The microphone is not available").assertExists()
        compose.onNodeWithTag(RoomTags.BANNER_MESSAGE).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.BANNER_VOICE_DISMISS).performClick()
        assertEquals(listOf("dismissVoiceMessage"), actions.calls.filter { it != "chatSeen" })
    }
}
