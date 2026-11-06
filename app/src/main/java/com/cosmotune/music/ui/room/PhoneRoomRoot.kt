package com.cosmotune.music.ui.room

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.R
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.room.PhoneRooms
import com.cosmotune.music.data.room.RoomPartyState
import com.cosmotune.music.playback.RoomKinds
import com.cosmotune.music.playback.RoomNotices
import com.cosmotune.music.ui.screens.RoomEntrySheets
import com.cosmotune.music.ui.screens.rememberRoomEntry
import com.cosmotune.room.RoomControl
import com.cosmotune.room.RoomState
import com.cosmotune.ui.room.LocalRoomLauncher
import com.cosmotune.ui.room.RoomEntryButton
import com.cosmotune.ui.room.RoomLauncher
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.settings.CosmoLookSettings

/**
 * The room on the phone, around every mode: the [RoomLauncher] every page reaches through [LocalRoomLauncher] ("Play in room" on a
 * song, a video, a station or an episode; the dock's room icon), the full-screen room over whatever page is open, and the sheet that
 * makes or joins a room when something is played in one before there is one. Minimising the room returns to that page, still in it.
 *
 * Drawn once by the phone's mode host around every mode, so it stays as the mode changes: a room is not Music's, it plays whatever
 * its item is in that item's own player on each device (songs, stations and episodes in the music service, videos in Video mode
 * through [video], which the mode host provides).
 */
@Composable
fun PhoneRoomRoot(video: RoomVideoHost?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val shell = LocalCosmoShell.current
    val code = PhoneRooms.code.collectAsStateWithLifecycle()
    val room = PhoneRooms.room.collectAsStateWithLifecycle()
    val local = PhoneRooms.local.collectAsStateWithLifecycle()
    val mode = shell?.settings?.mode?.collectAsStateWithLifecycle()?.value ?: AppMode.MUSIC
    val open = rememberSaveable { mutableStateOf(false) }
    var starting by rememberSaveable { mutableStateOf(false) }
    // What was played before there was a room: played as soon as the room that was made or joined for it is there.
    var pending by remember { mutableStateOf<PartyTrack?>(null) }
    val launcher = remember(context) {
        PhoneRoomLauncher(context, code, room, local, open, { video }, onPending = { pending = it }, onStart = { starting = true })
    }
    val entry = rememberRoomEntry()

    SideEffect { video?.follow(shell?.settings) }
    val watchContext = androidx.compose.ui.platform.LocalContext.current
    SideEffect { com.cosmotune.music.watch.RoomWatch.follow(watchContext) }
    LaunchedEffect(code.value) { if (code.value == null) open.value = false }
    LaunchedEffect(room.value != null, pending) {
        val item = pending ?: return@LaunchedEffect
        if (room.value != null) {
            pending = null
            launcher.playInRoom(item)
        }
    }
    val currentMode by rememberUpdatedState(mode)
    LaunchedEffect(Unit) {
        // Music shows these on its own strip above the player; the other modes have none, so a short toast says it there.
        PhoneRooms.notices.collect { if (!open.value && currentMode != AppMode.MUSIC) Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        RoomNotices.skipped.collect { track ->
            // A kind from a newer app is never played as a song: the person is asked to update instead.
            val text = when {
                track.needsUpdate -> context.getString(R.string.room_item_needs_update)
                track.kind == PartyTrack.KIND_WATCH -> context.getString(R.string.room_item_not_in_watch, track.title.ifBlank { track.videoId })
                else -> context.getString(R.string.room_item_skipped, track.title.ifBlank { track.videoId })
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val track = room.value?.playback?.track
    val inRoom = code.value != null
    // The room's video drawn in the room screen: Video's own pages step aside meanwhile ([RoomVideoHost.cover]).
    val media = if (inRoom && open.value) video?.media(track) ?: com.cosmotune.music.watch.RoomWatch.media(track) else null
    SideEffect { video?.cover(media != null && mode == AppMode.VIDEO) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalRoomLauncher provides launcher) {
            content()
            if (inRoom && open.value) {
                RoomHost(
                    wide = maxWidth >= 840.dp,
                    onMinimise = { open.value = false },
                    media = media,
                    onPlaybackOptions = {
                        val item = room.value?.playback?.track
                        when {
                            item == null -> Unit
                            // Quality, captions and speed are the watch page's; changing them there never changes the room.
                            RoomKinds.playerOf(item) == RoomKinds.Player.VIDEO -> {
                                open.value = false
                                video?.showWatchPage(item.videoId)
                            }
                            // A station's or an episode's speed and sleep timer are Podio's player.
                            item.kind != PartyTrack.KIND_MUSIC && shell != null && AppModes.available(AppMode.PODIO) -> {
                                open.value = false
                                shell.settings.setMode(AppMode.PODIO)
                            }
                        }
                    },
                )
            } else if (inRoom) {
                RoomDockIcon(launcher)
            }
            if (starting) {
                RoomEntrySheets(
                    entry,
                    landing = true,
                    onJoined = {
                        starting = false
                        open.value = true
                    },
                    onDismiss = {
                        starting = false
                        pending = null
                    },
                )
            }
        }
    }
}

/**
 * The room icon, on every page of every mode while a room is joined. The docks are shared chrome drawn by each mode, so it stands
 * here, at the app's root, just above the dock's top edge at its trailing side (the dock publishes its height on the shell). A page
 * with no dock (a watch page, the old look's bar) has it at the top trailing corner instead.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.RoomDockIcon(launcher: RoomLauncher) {
    val dockPx = LocalCosmoShell.current?.dockHeightPx ?: 0
    val modifier = if (dockPx > 0) {
        val dock = with(LocalDensity.current) { dockPx.toDp() }
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dock + 8.dp)
    } else {
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 6.dp, end = 12.dp)
    }
    // The compact icon is drawn for a bar's own ground; standing over a page it gets a disc of the chrome's surface under it.
    Box(modifier.clip(CircleShape).background(Color(LocalCosmoColors.current.palette.surfaceHigh))) {
        RoomEntryButton(launcher, compact = true)
    }
}

/**
 * The phone's [RoomLauncher]. Its values read the room's state as snapshot state, so a page that shows them follows the room while
 * the launcher itself (and the local that carries it) never changes.
 */
class PhoneRoomLauncher internal constructor(
    private val context: Context,
    private val code: State<String?>,
    private val room: State<RoomState?>,
    private val local: State<PhoneRooms.Local>,
    private val open: MutableState<Boolean>,
    private val video: () -> RoomVideoHost?,
    /** Keeps an item to play as soon as the room is there (made, joined, or still connecting). */
    private val onPending: (PartyTrack) -> Unit,
    /** No room yet: the sheet that makes or joins one. */
    private val onStart: () -> Unit,
) : RoomLauncher {
    override val inRoom: Boolean get() = code.value != null

    override val canControl: Boolean
        get() = room.value?.let { !it.meta.hostOnlyControl || it.meta.hostUid == PhoneRooms.selfUid } == true

    override val memberCount: Int get() = room.value?.members?.size ?: 0

    override val unreadChat: Int
        get() = room.value?.let { RoomPartyState.unread(it.chat, PhoneRooms.selfUid, local.value.seenChatAtMs) } ?: 0

    /** Whether the room screen is up, so a message is not said twice (it shows its own). */
    val isOpen: Boolean get() = open.value

    override fun playInRoom(item: PartyTrack) {
        if (room.value == null) {
            onPending(item)
            if (code.value == null) onStart()
            return
        }
        if (!canControl) {
            PhoneRooms.notify(context.getString(R.string.listen_together_host_only_notice))
            return
        }
        // A video this device is already watching is shared from where it is, not from the start.
        val watching = if (item.kind == PartyTrack.KIND_VIDEO) video()?.positionOf(item.videoId) else null
        PhoneRooms.control(RoomControl.SetTrack(item, positionMs = watching?.coerceAtLeast(0L) ?: 0L))
    }

    override fun openRoom() {
        if (code.value != null) open.value = true else onStart()
    }
}

/**
 * What the room needs of Video mode on the phone, provided by Video's host (it knows Video; Music's code does not): the follower that
 * plays the room's videos in Video's own player, and the picture the room screen shows for one.
 */
interface RoomVideoHost {
    /** Follows the room from now on (once per process), switching to Video mode through [settings] when the room starts a video. */
    fun follow(settings: CosmoLookSettings?)

    /** The room screen's picture for [track] when it is a video this phone can show; null draws the artwork. */
    fun media(track: PartyTrack?): (@Composable () -> Unit)?

    /** The room screen is up and draws the room's video: Video's own pages step aside meanwhile, so one player has one picture. */
    fun cover(covered: Boolean)

    /** Video's watch page for [videoId]: its quality, captions and speed are this device's own. */
    fun showWatchPage(videoId: String)

    /** Where this device is in [videoId], when that is the video on its player; null otherwise. */
    fun positionOf(videoId: String): Long?
}
