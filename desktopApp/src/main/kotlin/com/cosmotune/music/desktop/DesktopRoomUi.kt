package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitDoorCard
import com.cosmotune.ui.orbit.OrbitFace
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitNotice
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitPlainDoor
import com.cosmotune.ui.orbit.orbitRoomDoor
import com.cosmotune.ui.room.RoomLauncher
import com.cosmotune.ui.room.RoomScreen
import com.cosmotune.ui.sheets.CosmoDialogHeading
import com.cosmotune.ui.sheets.CosmoToast
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

/**
 * Making a room or finding one by its code, in Orbit as the phone's Start a room: the heading in the wide face, then two doors side by
 * side, the room's own indigo card (its size and Create room) and a quiet card for a code (the field and Look up room), and the
 * notices under them. A code is looked up first, so the person sees who is inside before joining.
 */
@Composable
private fun DesktopRoomLanding(autoplayEnabled: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(DesktopRooms.DEFAULT_SIZE) }
    var preview by remember { mutableStateOf<RoomPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending by DesktopRooms.pendingItem.collectAsState()
    val fonts = orbitFonts()
    val look = orbitLook()

    fun lookUp() {
        if (!RoomCode.isValid(code)) {
            error = "That isn't a room code. Codes are six letters and digits."
            return
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

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 920) {
        Column(desktopPanelBody(cardMax = 760.dp, cardMin = 560.dp, fill = true)) {
            Row(
                Modifier.fillMaxWidth().padding(start = panelInset(28.dp), end = panelInset(18.dp), top = 26.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CosmoDialogHeading(
                    overline = null,
                    title = DesktopStrings["listen_together", "Listen together"],
                    message = pending?.let { "Start a room or join one, and “${it.title.ifBlank { "this" }}” plays for everyone" }
                        ?: "Start a room or join friends with a six-character code",
                    modifier = Modifier.weight(1f),
                )
                if (!LocalDesktopPanelIsPage.current) {
                    Spacer(Modifier.width(12.dp))
                    OrbitRoundButton(CosmoIcons.Close, "Close", look.ink, onDismiss)
                }
            }

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
                contentPadding = PaddingValues(horizontal = panelInset(28.dp), vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val room = orbitRoomDoor(com.cosmotune.ui.orbit.orbitLook())
                        OrbitDoorCard(
                            label = "New room",
                            title = "Start a room",
                            text = "Friends join with the code. Everyone can play, pause and skip unless you choose otherwise.",
                            colors = room,
                            orbits = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Room size", style = fonts.title, color = room.ink)
                                    Text("${DesktopRooms.MIN_SIZE}–${DesktopRooms.MAX_SIZE} people", style = fonts.body, color = room.dim)
                                }
                                OrbitRoundButton(Icons.Rounded.Remove, "One fewer", room.ink, { size-- }, enabled = !busy && size > DesktopRooms.MIN_SIZE, onInk = room.fill)
                                Text("$size", style = fonts.section, color = room.ink, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                                OrbitRoundButton(Icons.Rounded.Add, "One more", room.ink, { size++ }, enabled = !busy && size < DesktopRooms.MAX_SIZE, onInk = room.fill)
                            }
                            Spacer(Modifier.height(20.dp))
                            RoomPrimaryButton("Create room", busy, true) {
                                busy = true
                                error = null
                                scope.launch {
                                    DesktopRooms.create(size, autoplayEnabled).onFailure { error = DesktopRoomPlayback.errorMessage(it) }
                                    busy = false
                                }
                            }
                        }
                        val plain = orbitPlainDoor(look)
                        OrbitDoorCard(
                            label = "Have a code",
                            title = "Join a room",
                            text = "Enter the code first. You'll see who is inside before joining.",
                            colors = plain,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            OrbitLabel("Room code", fonts, plain.dim, Modifier.padding(start = 4.dp, bottom = 8.dp))
                            OrbitTextField(
                                value = code,
                                onValueChange = {
                                    code = (JamInvite.parse(it) ?: RoomCode.normalize(it)).take(JamInvite.CODE_LENGTH)
                                    error = null
                                },
                                placeholder = "ABC234",
                                fonts = fonts,
                                look = look,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                onSubmit = { if (code.length == JamInvite.CODE_LENGTH && !busy) lookUp() },
                                textStyle = TextStyle(fontFamily = fonts.wide, fontSize = 20.sp, letterSpacing = 5.sp),
                            )
                            Spacer(Modifier.height(20.dp))
                            RoomPrimaryButton("Look up room", busy, code.length == JamInvite.CODE_LENGTH, onClick = ::lookUp)
                        }
                    }
                }
                error?.let { item { OrbitNotice(it, fonts, look, icon = Icons.Rounded.ErrorOutline) } }
                item {
                    OrbitNotice(
                        "Rooms use a guest identity on this device, or your Google account if you signed in under Account & sync. " +
                            "Joining a room does not turn on library sync.",
                        fonts, look,
                    )
                }
            }
        }
    }
}

/** Who is inside a room found by its code: their faces, the room's line and Join room (Back goes to the doors again). */
@Composable
private fun RoomJoinConfirmation(preview: RoomPreview, busy: Boolean, error: String?, onBack: () -> Unit, onJoin: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(
        Modifier.fillMaxSize().padding(horizontal = panelInset(36.dp), vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val names = preview.memberNames.filter { it.isNotBlank() }
        if (names.isEmpty()) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(orbitRoomDoor(com.cosmotune.ui.orbit.orbitLook()).fill), contentAlignment = Alignment.Center) {
                Icon(CosmoIcons.Together, null, tint = orbitRoomDoor(com.cosmotune.ui.orbit.orbitLook()).ink, modifier = Modifier.size(32.dp))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                names.take(6).forEach { name -> OrbitFace(url = null, name = name, key = name, size = 56.dp) }
            }
        }
        Spacer(Modifier.height(18.dp))
        OrbitLabel("${preview.memberCount} of ${preview.maxMembers} people · ${preview.code}", fonts, look.dim)
        Spacer(Modifier.height(10.dp))
        Text(
            if (preview.hostName.isBlank()) "Join this room?" else "Join ${preview.hostName}'s room?",
            style = fonts.heroTitle,
            color = look.ink,
            textAlign = TextAlign.Center,
        )
        if (names.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(names.joinToString(", "), style = fonts.body, color = look.dim, textAlign = TextAlign.Center)
        }
        if (preview.isFull) {
            Spacer(Modifier.height(10.dp))
            Text("This room is full.", style = fonts.body, color = MaterialTheme.colorScheme.error)
        }
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, style = fonts.body, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OrbitPillButton("Back", onBack, fonts, look, enabled = !busy, height = 46.dp)
            RoomPrimaryButton("Join room", busy, !preview.isFull, Modifier.width(190.dp), onJoin)
        }
    }
}

/**
 * "Ask to play": a song found with the desktop's own search, sent to the room as a request card. It plays for nobody until someone
 * presses Play now on it. In Orbit: the dialog's heading, the search pill and the results as the kit's song rows.
 */
@Composable
private fun DesktopRoomSongPicker(onPick: (PartyTrack) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val fonts = orbitFonts()
    val look = orbitLook()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

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
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CosmoDialogHeading(
                    overline = null,
                    title = "Ask to play",
                    message = "The room sees your request and anyone can play it.",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OrbitRoundButton(CosmoIcons.Close, "Close", look.ink, onDismiss)
            }
            Spacer(Modifier.height(16.dp))
            OrbitSearchPill(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { submitted = query },
                placeholder = "Search for a song",
                fonts = fonts,
                look = look,
                focusRequester = focus,
                height = 52.dp,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    searching && results.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = look.accent, strokeWidth = 2.dp)
                    failed -> Text("Search did not work just now. Try again.", style = fonts.body, color = look.dim, modifier = Modifier.align(Alignment.Center))
                    results.isEmpty() -> Text(
                        if (submitted.isBlank()) "Find the song to ask for" else "Nothing found",
                        style = fonts.body,
                        color = look.dim,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results, key = Song::videoId) { song ->
                            OrbitSongRow(
                                title = song.title,
                                subtitle = song.artist,
                                cover = song.thumbnailUrl,
                                fonts = fonts,
                                look = look,
                                onClick = { onPick(song.toPartyTrack(DesktopRoomPlayback.durationMsOf(song.durationText) ?: 0L)) },
                                modifier = Modifier.fillMaxWidth(),
                                height = 62.dp,
                                trailingText = song.durationText,
                            )
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
    // The Orbit toast: the ink pill with the ground's words, rising into place for each new notice.
    key(text) { CosmoToast(text, modifier) }
}

/**
 * The one action of a door or of the confirmation: the Orbit dialog's peach pill, 46 dp high; while [busy] its word gives way to a
 * small spinner and it holds still.
 */
@Composable
private fun RoomPrimaryButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(modifier, contentAlignment = Alignment.Center) {
        OrbitPillButton(if (busy) "" else label, onClick, fonts, look, Modifier.fillMaxWidth(), primary = true, enabled = enabled && !busy, height = 46.dp)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = look.ink, strokeWidth = 2.dp)
    }
}
