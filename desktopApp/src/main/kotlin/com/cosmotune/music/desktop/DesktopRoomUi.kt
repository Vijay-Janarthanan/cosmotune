package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.listentogether.JamInvite
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.room.RoomCode
import com.cosmotune.room.RoomControl
import com.cosmotune.room.RoomException
import com.cosmotune.room.RoomPreview
import com.cosmotune.ui.bridge.CircularProgressIndicator
import com.cosmotune.ui.room.RoomLauncher
import com.cosmotune.ui.room.RoomScreen
import com.cosmotune.ui.theme.CosmoRadii
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listen together on the desktop: a room on Firebase. Before a room is joined this is where one is made or found by its code; once
 * joined it is the shared room screen, drawn wide in the main window area, with Leave always in reach.
 */
@Composable
internal fun DesktopRoomPanel(autoplayEnabled: Boolean, onDismiss: () -> Unit) {
    val joined by DesktopRooms.joined.collectAsState()
    if (joined) DesktopRoomView() else DesktopRoomLanding(autoplayEnabled, onDismiss)
}

/**
 * The joined room: the shared screen, fed from [DesktopRooms] and refreshed about once a second for the playhead. [onClose] puts it
 * away and goes back to the page it was opened over (the room stays joined); null where the room is a page of its own.
 *
 * While the room plays a video, the screen shows Video mode's own picture of it, and the screen's playback options open Video's
 * player settings for this device.
 */
@Composable
internal fun DesktopRoomView(onClose: (() -> Unit)? = null) {
    val room by DesktopRooms.room.collectAsState()
    val connection by DesktopRooms.connection.collectAsState()
    val local by DesktopRooms.local.collectAsState()
    val voice by DesktopRooms.voiceUi.collectAsState()
    var positionMs by remember { mutableStateOf(DesktopRooms.positionMs()) }
    var picking by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val actions = remember { DesktopRoomActions(openSongPicker = { picking = true }, playbackOptions = { options = true }) }
    val item = room?.playback?.track

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = DesktopRooms.positionMs()
            delay(1_000)
        }
    }
    DisposableEffect(Unit) {
        DesktopRooms.setViewVisible(true)
        onDispose { DesktopRooms.setViewVisible(false) }
    }

    Box(Modifier.fillMaxSize()) {
        // The shared screen draws the room's own top: the way back ([onClose]), the code, and copy, share and leave in its menu.
        RoomScreen(
            state = DesktopRooms.uiState(room, connection, local, voice, positionMs),
            actions = actions,
            wide = true,
            modifier = Modifier.fillMaxSize(),
            media = rememberRoomVideoMedia(item),
            onClose = onClose,
        )
        // Video's settings menu opens at the top end, under the room's own top (it is a popup aligned to the box that holds it).
        if (options && item?.kind == PartyTrack.KIND_VIDEO) {
            Box(Modifier.fillMaxWidth().padding(top = 56.dp, end = 28.dp), contentAlignment = Alignment.TopEnd) {
                Box { DesktopRoomVideoOptions(onDismiss = { options = false }) }
            }
        }
    }
    // Options asked for while the room plays a song, a station or an episode: there are none of this device's own to open.
    LaunchedEffect(options, item?.kind) {
        if (options && item?.kind != PartyTrack.KIND_VIDEO) options = false
    }

    if (picking) {
        DesktopRoomSongPicker(
            onPick = { track ->
                picking = false
                DesktopRooms.askToPlay(track)
            },
            onDismiss = { picking = false },
        )
    }
}

/** Making a room or finding one by its code. A code is looked up first, so the person sees who is inside before joining. */
@Composable
private fun DesktopRoomLanding(autoplayEnabled: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(DesktopRooms.DEFAULT_SIZE) }
    var preview by remember { mutableStateOf<RoomPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending by DesktopRooms.pendingItem.collectAsState()

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 920) {
        Column(desktopPanelBody(cardMax = 760.dp, cardMin = 560.dp, fill = true)) {
            Row(
                Modifier.fillMaxWidth().padding(start = panelInset(28.dp), end = panelInset(18.dp), top = 22.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(DesktopStrings["listen_together", "Listen together"], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        pending?.let { "Start a room or join one, and “${it.title.ifBlank { "this" }}” plays for everyone" }
                            ?: "Start a room or join friends with a six-character code",
                        color = DesktopSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!LocalDesktopPanelIsPage.current) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
            }
            if (!LocalDesktopPanelIsPage.current) HorizontalDivider(color = DesktopCardEdge)

            val found = preview
            if (found != null) {
                RoomJoinConfirmation(
                    preview = found,
                    busy = busy,
                    error = error,
                    onBack = { preview = null; error = null },
                    onJoin = {
                        busy = true
                        error = null
                        scope.launch {
                            DesktopRooms.join(found.code).onFailure { error = DesktopRoomPlayback.errorMessage(it) }
                            busy = false
                        }
                    },
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        RoomChoiceCard("Start a room", "Friends join with the code. Everyone can play, pause and skip unless you choose otherwise.", Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Room size", fontWeight = FontWeight.Medium)
                                    Text("${DesktopRooms.MIN_SIZE}–${DesktopRooms.MAX_SIZE} people", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                                RoomStepper("−", !busy && size > DesktopRooms.MIN_SIZE) { size-- }
                                Text("$size", modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
                                RoomStepper("+", !busy && size < DesktopRooms.MAX_SIZE) { size++ }
                            }
                            Spacer(Modifier.height(16.dp))
                            RoomPrimaryButton("Create room", busy, true) {
                                busy = true
                                error = null
                                scope.launch {
                                    DesktopRooms.create(size, autoplayEnabled).onFailure { error = DesktopRoomPlayback.errorMessage(it) }
                                    busy = false
                                }
                            }
                        }
                        RoomChoiceCard("Join a room", "Enter the code first. You'll see who is inside before joining.", Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = {
                                    code = (JamInvite.parse(it) ?: RoomCode.normalize(it)).take(JamInvite.CODE_LENGTH)
                                    error = null
                                },
                                label = { Text("Room code") },
                                placeholder = { Text("ABC234") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 5.sp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(18.dp))
                            RoomPrimaryButton("Look up room", busy, code.length == JamInvite.CODE_LENGTH) {
                                if (!RoomCode.isValid(code)) {
                                    error = "That isn't a room code. Codes are six letters and digits."
                                    return@RoomPrimaryButton
                                }
                                busy = true
                                error = null
                                scope.launch {
                                    DesktopRooms.peek(code)
                                        .onSuccess { found ->
                                            if (found == null) error = DesktopRoomPlayback.errorMessage(RoomException(RoomException.Reason.NOT_FOUND, ""))
                                            else preview = found
                                        }
                                        .onFailure { error = DesktopRoomPlayback.errorMessage(it) }
                                    busy = false
                                }
                            }
                        }
                    }
                }
                error?.let { item { RoomNotice(it, destructive = true) } }
                item {
                    RoomNotice(
                        "Rooms use a guest identity on this device, or your Google account if you signed in under Account & sync. " +
                            "Joining a room does not turn on library sync.",
                        destructive = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomJoinConfirmation(preview: RoomPreview, busy: Boolean, error: String?, onBack: () -> Unit, onJoin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = panelInset(36.dp), vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Groups, null, tint = DesktopAccent, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            if (preview.hostName.isBlank()) "Join this room?" else "Join ${preview.hostName}'s room?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text("${preview.memberCount} of ${preview.maxMembers} people · ${preview.code}", color = DesktopSecondary)
        if (preview.memberNames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                preview.memberNames.joinToString(", ").ifBlank { "Someone" },
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (preview.isFull) {
            Spacer(Modifier.height(10.dp))
            Text("This room is full.", color = DesktopDestructive)
        }
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = DesktopDestructive) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            RoomPrimaryButton("Join room", busy, !preview.isFull, Modifier.width(190.dp), onJoin)
        }
    }
}

/**
 * "Ask to play": a song found with the desktop's own search, sent to the room as a request card. It plays for nobody until someone
 * presses Play now on it.
 */
@Composable
private fun DesktopRoomSongPicker(onPick: (PartyTrack) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // A pause in typing searches, as does Enter.
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(450)
        submitted = query
    }
    LaunchedEffect(submitted) {
        if (submitted.isBlank()) return@LaunchedEffect
        searching = true
        failed = false
        DesktopSearchClient.search(submitted, SearchFilter.SONGS)
            .onSuccess { found ->
                results = found.mapNotNull {
                    when (it) {
                        is SearchResult.Track -> it.song
                        is SearchResult.TopTrack -> it.song
                        is SearchResult.Browse -> null
                    }
                }.distinctBy(Song::videoId).take(30)
            }
            .onFailure { failed = true }
        searching = false
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 640, popup = true) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask to play", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The room sees your request and anyone can play it.", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            Spacer(Modifier.height(14.dp))
            DesktopSearchField(query = query, onQueryChange = { query = it }, onSearch = { submitted = query }, placeholder = "Search for a song")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    searching && results.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
                    failed -> Text("Search did not work just now. Try again.", color = DesktopSecondary, modifier = Modifier.align(Alignment.Center))
                    results.isEmpty() -> Text(
                        if (submitted.isBlank()) "Find the song to ask for" else "Nothing found",
                        color = DesktopSecondary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(results, key = Song::videoId) { song ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(CosmoRadii.rSm))
                                    .clickable { onPick(song.toPartyTrack(DesktopRoomPlayback.durationMsOf(song.durationText) ?: 0L)) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DesktopArtwork(song.thumbnailUrl, Modifier.size(42.dp).clip(RoundedCornerShape(CosmoRadii.rSm)), px = 120)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                song.durationText?.let { Text(it, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The desktop's [RoomLauncher], which every page and mode reads through [com.cosmotune.ui.room.LocalRoomLauncher]: the room icon in
 * the top bar, and "Play in room" on Video's watch page and Podio's stations and episodes. Its numbers are snapshot state (from
 * [DesktopRooms.summary]), so whatever reads them follows the room; one instance for the window, so the static local never changes.
 */
internal class DesktopRoomLauncher(
    private val summary: State<DesktopRoomPlayback.LauncherSummary>,
    private val open: () -> Unit,
    private val askToJoin: () -> Unit,
) : RoomLauncher {
    override val inRoom: Boolean get() = summary.value.inRoom
    override val canControl: Boolean get() = summary.value.canControl
    override val memberCount: Int get() = summary.value.memberCount
    override val unreadChat: Int get() = summary.value.unreadChat

    override fun playInRoom(item: PartyTrack) {
        when (DesktopRoomPlayback.launch(summary.value)) {
            DesktopRoomPlayback.Launch.JOIN_FIRST -> {
                DesktopRooms.startWhenJoined(item)
                askToJoin()
            }
            DesktopRoomPlayback.Launch.REFUSED ->
                DesktopPlayerHost.showMessage(DesktopRoomPlayback.errorMessage(RoomException(RoomException.Reason.HOST_ONLY, "")))
            // The same control as the room screen's "Play now"; every device, this one too, opens it in its own player. A video
            // already playing here ("Watch together" on its own watch page) goes on from where it is rather than from the top.
            DesktopRoomPlayback.Launch.PLAY -> {
                val here = when (item.kind) {
                    PartyTrack.KIND_VIDEO -> DesktopRoomVideo.local()
                    // A film already playing in Watch here ("Watch together" from its player) goes on from where it is.
                    PartyTrack.KIND_WATCH -> DesktopRoomWatch.local().takeIf { !item.isLive }
                    else -> null
                }?.takeIf { it.videoId == item.videoId }
                DesktopRooms.control(RoomControl.SetTrack(item, positionMs = here?.positionMs?.coerceAtLeast(0) ?: 0))
            }
        }
    }

    override fun openRoom() = open()
}

/** The window's [DesktopRoomLauncher]: [open] brings the room up over the page, [askToJoin] opens the make-or-join panel. */
@Composable
internal fun rememberDesktopRoomLauncher(open: () -> Unit, askToJoin: () -> Unit): RoomLauncher {
    val summary = DesktopRooms.summary.collectAsState()
    val latestOpen = rememberUpdatedState(open)
    val latestAsk = rememberUpdatedState(askToJoin)
    return remember { DesktopRoomLauncher(summary, { latestOpen.value() }, { latestAsk.value() }) }
}

/**
 * The player's short notices ([DesktopPlayerHost.showMessage]: an item this device cannot play, a refused room control) on any page
 * and in any mode. The player sheet shows its own while it is open, so the window draws this only while it is not.
 */
@Composable
internal fun DesktopNoticeToast(modifier: Modifier = Modifier) {
    val message by DesktopPlayerHost.messages.collectAsState()
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            DesktopPlayerHost.messages.value = null
        }
    }
    val text = message ?: return
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun RoomChoiceCard(title: String, subtitle: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.desktopCardInset(RoundedCornerShape(CosmoRadii.rLg)).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun RoomStepper(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(DesktopCardInsetFill).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.White else DesktopSecondary) }
}

@Composable
private fun RoomPrimaryButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = DesktopAccent, contentColor = Color.Black),
        modifier = modifier.height(46.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
        else Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RoomNotice(text: String, destructive: Boolean) {
    Text(
        text,
        color = if (destructive) DesktopDestructive else DesktopSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().desktopCardInset(RoundedCornerShape(CosmoRadii.rMd)).padding(14.dp),
    )
}
