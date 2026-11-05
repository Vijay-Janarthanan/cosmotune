package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomMeta
import com.cosmotune.room.RoomState
import com.cosmotune.ui.room.LocalRoomLauncher
import com.cosmotune.ui.room.RoomActions
import com.cosmotune.ui.room.RoomEntryButton
import com.cosmotune.ui.room.RoomEntryTags
import com.cosmotune.ui.room.RoomLauncher
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.RoomScreen
import com.cosmotune.ui.room.RoomTags
import com.cosmotune.ui.room.RoomUiState
import com.cosmotune.ui.room.roomStationItem
import com.cosmotune.ui.room.roomVideoItem
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The room as a watch party: a video with the platform's surface, a live station, a podcast, the dock's room icon and "Play in room". */
class RoomWatchPartyInteractionTest {
    val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RetryOnComposeHarnessRace()).around(compose)

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
        override fun openPlaybackOptions() { calls += "openPlaybackOptions" }
    }

    private class FakeLauncher(
        override val inRoom: Boolean,
        override val canControl: Boolean = true,
        override val memberCount: Int = 0,
        override val unreadChat: Int = 0,
    ) : RoomLauncher {
        val calls = mutableListOf<String>()
        override fun playInRoom(item: PartyTrack) { calls += "playInRoom(${item.kind}:${item.videoId})" }
        override fun openRoom() { calls += "openRoom" }
    }

    private val video = PartyTrack("dQw4w9WgXcQ", "A video", "A channel", durationMs = 213_000, kind = PartyTrack.KIND_VIDEO)
    private val station = PartyTrack("podio:radio:s1", "Radio City", "Chennai, India", kind = PartyTrack.KIND_RADIO, url = "https://s.example/live")
    private val podcast = PartyTrack("podio:episode:e1", "Episode 12", "The Show", durationMs = 1_800_000, kind = PartyTrack.KIND_PODCAST, url = "https://e.example/12.mp3")

    private fun member(seat: Int, uid: String, name: String, host: Boolean = false) =
        RoomMember(seat, uid, name, null, "device-$uid", 0, 0, micLive = false, isHost = host, connected = true)

    private fun state(track: PartyTrack, queue: List<PartyTrack> = listOf(track), index: Int = 0) = RoomUiState(
        room = RoomState(
            code = "K7Q2MX",
            meta = RoomMeta(createdAtMs = 0, hostUid = "mira", maxMembers = 5, hostOnlyControl = false),
            members = listOf(member(0, "mira", "Mira", host = true), member(1, "ravi", "Ravi")),
            playback = PartyPlayback(track = track, isPlaying = true, queueIndex = index, queueLength = queue.size, startedBy = "mira", startedByName = "Mira"),
            queue = PartyQueue(index = index, items = queue),
            chat = emptyList(),
            serverNowMs = 5_000,
        ),
        connection = RoomConnection.LIVE,
        selfUid = "ravi",
        positionMs = 60_000,
    )

    @Composable
    private fun NewLook(wide: Boolean = false, content: @Composable () -> Unit) {
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

    private fun show(state: RoomUiState, wide: Boolean = false, media: (@Composable () -> Unit)? = null): Recorder {
        val actions = Recorder()
        compose.setContent { NewLook(wide) { RoomScreen(state, actions, wide, media = media) } }
        return actions
    }

    @Test fun aLiveStationHasNoSeekBarAndSaysItIsLive() {
        val actions = show(state(station))
        compose.onNodeWithTag(RoomTags.LIVE_BADGE).assertExists()
        compose.onNodeWithText("LIVE", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Live radio").assertExists()
        compose.onNodeWithText("Everyone hears the station live").assertExists()
        compose.onNodeWithTag(RoomTags.SEEK).assertDoesNotExist()
        compose.onNodeWithText("1:00").assertDoesNotExist()
        // Alone in the queue, a station has nothing either side of it.
        compose.onNodeWithTag(RoomTags.PREVIOUS).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.NEXT).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.PLAYBACK_OPTIONS).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.PLAY_PAUSE).assertIsEnabled().performClick()
        assertTrue("pause" in actions.calls, "${actions.calls}")
    }

    @Test fun aStationInTheMiddleOfTheQueueKeepsPreviousAndNext() {
        val actions = show(state(station, queue = listOf(podcast, station, video), index = 1))
        compose.onNodeWithTag(RoomTags.PREVIOUS).assertExists()
        compose.onNodeWithTag(RoomTags.NEXT).assertExists().performClick()
        compose.onNodeWithTag(RoomTags.SEEK).assertDoesNotExist()
        assertTrue("next" in actions.calls, "${actions.calls}")
    }

    @Test fun aVideoDrawsTheMediaSlotAndOpensThisDevicesOptions() {
        val actions = show(state(video)) { Box(Modifier.fillMaxSize().testTag("fake:video-surface")) }
        compose.onNodeWithTag(RoomTags.VIDEO).assertExists()
        compose.onNodeWithTag("fake:video-surface").assertExists()
        compose.onNodeWithText("Video").assertExists()
        compose.onNodeWithTag(RoomTags.SEEK).assertExists()
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).performScrollToNode(hasTestTag(RoomTags.PLAYBACK_OPTIONS))
        compose.onNodeWithTag(RoomTags.PLAYBACK_OPTIONS).performClick()
        assertTrue("openPlaybackOptions" in actions.calls, "${actions.calls}")
        compose.onNodeWithTag(RoomTags.NEXT).performClick()
        assertTrue("next" in actions.calls, "the shared transport still drives the room: ${actions.calls}")
    }

    @Test fun theWideLayoutDrawsTheVideoToo() {
        show(state(video), wide = true) { Box(Modifier.fillMaxSize().testTag("fake:video-surface")) }
        compose.onNodeWithTag("fake:video-surface").assertExists()
        compose.onNodeWithTag(RoomTags.WIDE_PANEL).assertExists()
    }

    @Test fun aVideoWithoutASurfaceShowsItsArtwork() {
        show(state(video))
        compose.onNodeWithTag(RoomTags.VIDEO).assertExists()
        compose.onNodeWithTag("fake:video-surface").assertDoesNotExist()
    }

    @Test fun aPodcastSeeksAndHasPlaybackOptions() {
        val actions = show(state(podcast))
        compose.onNodeWithText("Podcast episode").assertExists()
        compose.onNodeWithTag(RoomTags.SEEK).assertExists()
        compose.onNodeWithTag(RoomTags.LIVE_BADGE).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.PAGE_NOW_PLAYING).performScrollToNode(hasTestTag(RoomTags.PLAYBACK_OPTIONS))
        compose.onNodeWithTag(RoomTags.PLAYBACK_OPTIONS).performClick()
        assertEquals(listOf("openPlaybackOptions"), actions.calls.filter { it == "openPlaybackOptions" })
    }

    @Test fun musicSaysNothingAboutItsKind() {
        show(state(PartyTrack("v1", "Kannalane", "A. R. Rahman", durationMs = 300_000)))
        compose.onNodeWithTag(RoomTags.KIND).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.PLAYBACK_OPTIONS).assertDoesNotExist()
        compose.onNodeWithTag(RoomTags.SEEK).assertExists()
    }

    @Test fun theRoomIconIsHiddenOutsideARoom() {
        compose.setContent { NewLook { Column { RoomEntryButton(FakeLauncher(inRoom = false)); RoomEntryButton(FakeLauncher(inRoom = false), compact = true) } } }
        compose.onNodeWithTag(RoomEntryTags.ENTRY).assertDoesNotExist()
    }

    @Test fun theRoomIconShowsTheCountAndUnreadAndOpensTheRoom() {
        val dock = FakeLauncher(inRoom = true, memberCount = 3, unreadChat = 2)
        val sidebar = FakeLauncher(inRoom = true, memberCount = 3, unreadChat = 2)
        compose.setContent {
            NewLook {
                Column {
                    RoomEntryButton(dock, Modifier.testTag("dock"), compact = true)
                    RoomEntryButton(sidebar, Modifier.testTag("sidebar"))
                }
            }
        }
        for ((tag, launcher) in listOf("dock" to dock, "sidebar" to sidebar)) {
            compose.onNodeWithTag(tag)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Room, 3 people, unread messages. Open")))
                .performClick()
            assertEquals(listOf("openRoom"), launcher.calls, tag)
        }
        assertEquals(2, compose.onAllNodesWithTag(RoomEntryTags.ENTRY_COUNT, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText("3", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithTag(RoomEntryTags.ENTRY_UNREAD, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Room", useUnmergedTree = true).assertExists()
    }

    @Test fun theRoomIconHasNoDotWhenEverythingIsRead() {
        compose.setContent { NewLook { RoomEntryButton(FakeLauncher(inRoom = true, memberCount = 1), compact = true) } }
        compose.onNodeWithTag(RoomEntryTags.ENTRY)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Room, 1 person. Open")))
        compose.onNodeWithTag(RoomEntryTags.ENTRY_UNREAD, useUnmergedTree = true).assertDoesNotExist()
    }

    private var shownLauncher by mutableStateOf<RoomLauncher?>(null)
    private var shownItem by mutableStateOf<PartyTrack?>(null)
    private var playButtonSet = false

    /** Shows [RoomPlayButton] for [item] under [launcher]; a second call swaps them in place (the rule takes one content per test). */
    private fun showPlayButton(launcher: RoomLauncher?, item: PartyTrack?) {
        shownLauncher = launcher
        shownItem = item
        if (!playButtonSet) {
            playButtonSet = true
            compose.setContent { NewLook { CompositionLocalProvider(LocalRoomLauncher provides shownLauncher) { RoomPlayButton(shownItem) } } }
        }
        compose.waitForIdle()
    }

    private val videoItem = roomVideoItem("dQw4w9WgXcQ", "A video", "A channel", null, 213_000)
    private val stationItem = roomStationItem("s1", "Radio City", "Chennai, India", "https://s.example/live", null)

    @Test fun playInRoomPlaysTheItemForEveryone() {
        val launcher = FakeLauncher(inRoom = true, canControl = true)
        showPlayButton(launcher, videoItem)
        compose.onNodeWithTag(RoomEntryTags.PLAY_IN_ROOM).assertIsEnabled()
        compose.onNodeWithText("Play in room", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RoomEntryTags.PLAY_IN_ROOM).performClick()
        assertEquals(listOf("playInRoom(video:dQw4w9WgXcQ)"), launcher.calls)
    }

    @Test fun playInRoomIsDisabledWhenOnlyTheHostStartsThings() {
        val launcher = FakeLauncher(inRoom = true, canControl = false)
        showPlayButton(launcher, stationItem)
        compose.onNodeWithTag(RoomEntryTags.PLAY_IN_ROOM).assertIsNotEnabled().performClick()
        compose.onNodeWithText("Only the host can start things here", useUnmergedTree = true).assertExists()
        assertTrue(launcher.calls.isEmpty(), "${launcher.calls}")
    }

    @Test fun outsideARoomItAsksToWatchOrListenTogether() {
        val launcher = FakeLauncher(inRoom = false)
        showPlayButton(launcher, videoItem)
        compose.onNodeWithText("Watch together", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RoomEntryTags.PLAY_IN_ROOM).performClick()
        assertEquals(listOf("playInRoom(video:dQw4w9WgXcQ)"), launcher.calls, "the host opens the make-or-join sheet")

        showPlayButton(launcher, stationItem)
        compose.onNodeWithText("Listen together", useUnmergedTree = true).assertExists()
    }

    @Test fun nothingIsDrawnWithoutALauncherOrAnItem() {
        showPlayButton(null, videoItem)
        compose.onNodeWithTag(RoomEntryTags.PLAY_IN_ROOM).assertDoesNotExist()
        // A plain-http stream has no item, so no button offers it.
        showPlayButton(FakeLauncher(inRoom = true), roomStationItem("s2", "Old", "", "http://s.example/live", null))
        compose.onNodeWithTag(RoomEntryTags.PLAY_IN_ROOM).assertDoesNotExist()
    }
}
