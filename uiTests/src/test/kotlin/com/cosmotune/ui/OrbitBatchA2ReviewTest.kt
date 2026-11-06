package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomMeta
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitSheetChip
import com.cosmotune.ui.room.RoomActions
import com.cosmotune.ui.room.RoomScreen
import com.cosmotune.ui.room.RoomTags
import com.cosmotune.ui.room.RoomUiState
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.CosmoDialogBody
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.sheets.CosmoSheetChips
import com.cosmotune.ui.sheets.CosmoSheetHeader
import com.cosmotune.ui.sheets.CosmoSheetSurface
import com.cosmotune.ui.sheets.CosmoToast
import com.cosmotune.ui.shell.CosmoBottomChrome
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Not an assertion: the room, the phone's dock and small player, a song's sheet, a dialog, a menu and the toasts (board batch-a,
 * sections 3 and 4) drawn offscreen at phone and wide sizes in the dark and the light palettes, written under build/orbit-a2-review
 * for a person to look at.
 */
class OrbitBatchA2ReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {
            it.color = java.awt.Color(r, g, b); it.fillRect(0, 0, 320, 320)
            it.color = java.awt.Color(minOf(r + 70, 255), minOf(g + 70, 255), minOf(b + 70, 255)); it.fillOval(50, 50, 220, 220)
            it.dispose()
        }
        return temporary.newFile("$name.png").also { ImageIO.write(image, "png", it) }.path
    }

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = playerField.get(null)
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previous) }
    }

    private val palettes = listOf(
        "dark" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true),
        "light" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(false, false), false),
    )

    private fun page(palette: CosmoPaletteState, width: Dp, height: Dp, content: @Composable () -> Unit) {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(width, height).testTag("a2:review").background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
    }

    private fun capture(name: String, popup: Boolean = false) {
        compose.waitForIdle()
        val file = File("../build/orbit-a2-review/$name.png").apply { parentFile.mkdirs() }
        val node = if (popup) compose.onAllNodes(isRoot())[1] else compose.onNodeWithTag("a2:review")
        ImageIO.write(node.captureToImage().toAwtImage(), "png", file)
    }

    private class Quiet : RoomActions {
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun next() {}
        override fun previous() {}
        override fun playTrack(track: PartyTrack) {}
        override fun removeFromQueue(videoId: String) {}
        override fun clearQueue() {}
        override fun sendChat(text: String) {}
        override fun askToPlay() {}
        override fun setMicLive(live: Boolean) {}
        override fun setDeafened(deafened: Boolean) {}
        override fun setHostOnlyControl(enabled: Boolean) {}
        override fun removeMember(uid: String) {}
        override fun copyInviteCode(code: String) {}
        override fun shareInvite(code: String) {}
        override fun leave() {}
        override fun dismissMessage() {}
        override fun chatSeen() {}
    }

    private fun roomState(art: List<String>, hostOnly: Boolean = false): RoomUiState {
        val playing = PartyTrack("v-now", "Golden Hour", "Mira and the Coast", art[0], durationMs = 245_000)
        val next = PartyTrack("v-next", "Low Tide Letters", "Harbour Lights", art[1], durationMs = 212_000)
        val third = PartyTrack("v-3", "Lantern Season", "Mira and the Coast", art[2], durationMs = 198_000)
        fun member(seat: Int, uid: String, name: String, host: Boolean = false, mic: Boolean = false, connected: Boolean = true) =
            RoomMember(seat, uid, name, null, "device-$uid", 0, 0, micLive = mic, isHost = host, connected = connected)
        val room = RoomState(
            code = "K7Q2PX",
            meta = RoomMeta(createdAtMs = 0, hostUid = "you", maxMembers = 6, hostOnlyControl = hostOnly),
            members = listOf(member(0, "you", "Vijay", host = true), member(1, "arun", "Arun", mic = true), member(2, "meera", "Meera"), member(3, "dev", "Dev", connected = false)),
            playback = PartyPlayback(track = playing, isPlaying = true, queueIndex = 0, queueLength = 3, startedBy = "arun", startedByName = "Arun"),
            queue = PartyQueue(index = 0, items = listOf(playing, next, third)),
            chat = listOf(
                RoomChatMessage("m1", "arun", "Arun", "text", "this one hits at sunset", null, 1_000),
                RoomChatMessage("m2", "meera", "Meera", "request", "", next, 2_000),
                RoomChatMessage("m3", "dev", "Dev", "system", "Dev skipped", null, 3_000),
                RoomChatMessage("m4", "meera", "Meera", "text", "next one is mine", null, 4_000),
            ),
            serverNowMs = 5_000,
        )
        return RoomUiState(
            room = room, connection = RoomConnection.LIVE, selfUid = if (hostOnly) "meera" else "you", positionMs = 112_000, voiceAvailable = true,
            speakingUids = setOf("arun"), mutedHere = setOf("meera"), unreadChat = 2,
        )
    }

    @Test fun theRoom() = hosts {
        val art = listOf(cover("a", 168, 92, 40), cover("b", 30, 120, 116), cover("c", 120, 60, 140))
        for ((name, palette) in palettes) {
            page(palette, 1240.dp, 760.dp) { RoomScreen(roomState(art), Quiet(), wide = true, onClose = {}) }
            capture("room-wide-$name")
            compose.onNodeWithTag(RoomTags.MORE).performClick()
            capture("room-menu-$name", popup = true)
            page(palette, 390.dp, 844.dp) { RoomScreen(roomState(art), Quiet(), wide = false, onClose = {}) }
            capture("room-phone-now-$name")
            compose.onNodeWithTag(RoomTags.TAB_CHAT).performClick()
            capture("room-phone-chat-$name")
            compose.onNodeWithTag(RoomTags.TAB_PEOPLE).performClick()
            compose.onNodeWithTag(RoomTags.member("arun")).performClick()
            capture("room-phone-people-$name")
            page(palette, 390.dp, 844.dp) { RoomScreen(roomState(art, hostOnly = true), Quiet(), wide = false) }
            capture("room-phone-guest-$name")
        }
    }

    @Test fun theDockSheetsDialogsAndToasts() = hosts {
        val art = cover("d", 170, 90, 30)
        val song = Song("v1", "Golden Hour", "Mira and the Coast", art, durationText = "4:05")
        val tabs = listOf(
            CosmoTab("Home", CosmoIcons.Home, CosmoIcons.HomeFill),
            CosmoTab("Search", CosmoIcons.Search, CosmoIcons.Search),
            CosmoTab("Library", CosmoIcons.Library, CosmoIcons.Library),
            CosmoTab("Room", CosmoIcons.Together, CosmoIcons.Together),
        )
        for ((name, palette) in palettes) {
            page(palette, 390.dp, 844.dp) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp).padding(top = 40.dp)) {
                        androidx.compose.material3.Text("Good evening.", color = Color(LocalCosmoColors.current.palette.textPrimary))
                    }
                    CosmoBottomChrome(
                        tabs = tabs, selectedIndex = 0, onTabSelected = {}, song = song, isPlaying = true, isLoading = false,
                        position = PlaybackPosition().apply { report(112_000) }, durationMs = 245_000, onPlayPause = {}, onNext = {}, onExpand = {},
                        controlsLocked = false, onBlockedControl = {}, hazeState = null, onHeightChanged = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                        notices = { CosmoToast("Added to Evening drive", actionLabel = "Undo", actionIcon = CosmoIcons.Previous, onAction = {}) },
                    )
                }
            }
            capture("dock-phone-$name")
            page(palette, 390.dp, 844.dp) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
                    CosmoSheetSurface(Modifier.align(Alignment.BottomCenter)) {
                        CosmoSheetHeader(title = song.title, subtitle = "Mira and the Coast · 4:05", imageUrl = art)
                        CosmoSheetChips(listOf(
                            OrbitSheetChip("Play next", CosmoIcons.Play, "Play next", primary = true) {},
                            OrbitSheetChip("Queue", CosmoIcons.Queue, "Add to queue") {},
                            OrbitSheetChip(null, CosmoIcons.Heart, "Like") {},
                        ))
                        CosmoActionRow(CosmoIcons.Plus, "Add to playlist") {}
                        CosmoActionRow(CosmoIcons.Together, "Start radio") {}
                        CosmoActionRow(CosmoIcons.Together, "Play in room") {}
                        CosmoActionRow(CosmoIcons.Download, "Download", value = "4.1 MB") {}
                        CosmoActionRow(CosmoIcons.Library, "Go to album") {}
                        CosmoActionRow(CosmoIcons.User, "Go to artist") {}
                        CosmoActionRow(CosmoIcons.Share, "Share") {}
                    }
                }
            }
            capture("sheet-phone-$name")
            page(palette, 760.dp, 620.dp) {
                Box(Modifier.fillMaxSize()) {
                    CosmoDialogCard(onDismiss = {}, maxWidth = 420.dp) {
                        CosmoDialogBody(
                            overline = "Delete download",
                            title = "Remove “Golden Hour” from this device?",
                            message = "It stays in your library and plays online.",
                            confirmLabel = "Remove", onConfirm = {}, dismissLabel = "Keep", onDismiss = {},
                        )
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CosmoToast("Couldn't reach the server", actionLabel = "Retry", onAction = {}, alert = true)
                        CosmoToast("Added to Evening drive", actionLabel = "Undo", actionIcon = CosmoIcons.Previous, onAction = {})
                        CosmoToast("Plays next")
                    }
                }
            }
            capture("dialog-toasts-$name")
        }
    }
}
