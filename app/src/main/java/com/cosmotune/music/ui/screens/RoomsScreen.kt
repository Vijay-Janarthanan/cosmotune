package com.cosmotune.music.ui.screens

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.R
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.room.PhoneRooms
import com.cosmotune.music.data.room.RoomPartyState
import com.cosmotune.music.ui.room.PhoneRoomActions
import com.cosmotune.music.ui.room.shareRoomInvite
import com.cosmotune.room.RoomCode
import com.cosmotune.ui.bridge.ModalBottomSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Listen together on rooms: create one, or type a code (or open an invite link), see who is inside, and join. Once in, the room
 * itself is the full-screen room screen ([onOpenRoom]); this page then says which room and offers the way back in, the invite
 * and Leave.
 *
 * No account is needed: the room joins as a guest, or as the person's Google sign-in when they have made one in Account & sync.
 * Library sync is not turned on by any of this. The sheets are the ones the party server's screen used, which is now unreachable
 * (see [ListenTogetherScreen]).
 */
@Composable
fun RoomsScreen(
    inviteCode: String?,
    onInviteHandled: () -> Unit,
    onOpenRoom: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code by PhoneRooms.code.collectAsStateWithLifecycle()
    val local by PhoneRooms.local.collectAsStateWithLifecycle()
    val party by ListenTogether.state.collectAsStateWithLifecycle()
    val entry = rememberRoomEntry()

    // A link names a room; the confirm sheet is still what joins it. Already in that room: nothing to do but show it.
    LaunchedEffect(inviteCode) {
        val invited = inviteCode?.let(RoomCode::normalize) ?: return@LaunchedEffect
        onInviteHandled()
        if (invited == code) onOpenRoom() else {
            entry.codeInput = invited
            entry.sheet = PartySheet.JoinCode
            entry.lookUp(invited)
        }
    }

    RoomEntrySheets(entry, landing = false, onJoined = onOpenRoom, onDismiss = {})

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.listen_together),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        val joined = code
        if (joined == null) {
            PartyLanding(
                avatarUrl = null,
                enabled = !entry.busy,
                onCreate = {
                    entry.failure = null
                    entry.sheet = PartySheet.Create
                },
                onJoin = {
                    entry.failure = null
                    entry.sheet = PartySheet.JoinCode
                },
            )
            Text(
                text = stringResource(R.string.room_guest_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
            )
        } else {
            SettingsGroup(
                header = stringResource(R.string.listen_together_code),
                footer = stringResource(R.string.listen_together_listening, party.members.size, party.maxMembers),
            ) {
                SettingsRow(
                    icon = Icons.Rounded.Groups,
                    title = stringResource(R.string.room_open),
                    subtitle = stringResource(R.string.room_chip, joined),
                    onClick = onOpenRoom,
                )
                SettingsRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(R.string.copy_code),
                    onClick = { PhoneRoomActions(context, {}, {}).copyInviteCode(joined) },
                )
                SettingsRow(
                    icon = Icons.Rounded.Share,
                    title = stringResource(R.string.listen_together_share_link),
                    onClick = { shareRoomInvite(context, joined) },
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    title = stringResource(R.string.room_leave),
                    subtitle = stringResource(if (party.members.size <= 1) R.string.listen_together_leave_alone else R.string.listen_together_leave_subtitle),
                    onClick = { scope.launch { PhoneRooms.leave() } },
                )
            }
        }

        // The last thing that went wrong outside a sheet: a room that ended, or a removal.
        if (entry.sheet == null) {
            (entry.failure ?: local.message)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = GROUP_INSET + 4.dp, end = GROUP_INSET + 4.dp, top = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * What the create and join sheets hold while one is open, so that the Rooms page and the room icon's "make or join a room first"
 * (anywhere in the app) are the same sheets. Both doors in, a typed code and a tapped link, look the room up first and show who is
 * inside before taking a seat.
 */
internal class RoomEntry(private val scope: CoroutineScope) {
    var sheet by mutableStateOf<PartySheet?>(null)
    var codeInput by mutableStateOf("")
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var nickname by mutableStateOf(runCatching { ListenTogether.nickname() }.getOrDefault(""))
    var maxMembers by mutableIntStateOf(DEFAULT_ROOM_SIZE)

    fun lookUp(typed: String) {
        busy = true
        failure = null
        scope.launch {
            PhoneRooms.peek(typed)
                .onSuccess { sheet = PartySheet.Confirm(RoomPartyState.preview(it), null) }
                .onFailure { failure = PhoneRooms.messageOf(it) }
            busy = false
        }
    }

    fun close() {
        sheet = null
        failure = null
    }
}

@Composable
internal fun rememberRoomEntry(): RoomEntry {
    val scope = rememberCoroutineScope()
    return remember(scope) { RoomEntry(scope) }
}

/**
 * The open sheet of [entry], if any. With [landing] the sheet is up even before a choice is made, and shows the landing's two doors
 * (make a room, join one) first: the way in from a page that is not the Rooms page. [onJoined] runs once a seat is taken; [onDismiss]
 * when the sheet is put away without one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomEntrySheets(entry: RoomEntry, landing: Boolean, onJoined: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val code by PhoneRooms.code.collectAsStateWithLifecycle()
    // Opened whole, never half way: half way left the landing's main action ("Create a room") under the gesture bar.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val open = entry.sheet
    if (open == null && !landing) return
    val dismiss = {
        entry.close()
        onDismiss()
    }
    val joined = {
        entry.codeInput = ""
        entry.sheet = null
        onJoined()
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        when (open) {
            null -> PartyLanding(
                avatarUrl = null,
                enabled = !entry.busy,
                onCreate = {
                    entry.failure = null
                    entry.sheet = PartySheet.Create
                },
                onJoin = {
                    entry.failure = null
                    entry.sheet = PartySheet.JoinCode
                },
                // On a short window the landing scrolls inside the sheet rather than pushing its buttons off the foot.
                modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
                compact = true,
            )

            PartySheet.Create -> CreatePartySheet(
                avatarUrl = null,
                nickname = entry.nickname,
                onNicknameChange = {
                    entry.nickname = it.take(40)
                    runCatching { ListenTogether.setNickname(entry.nickname) }
                },
                maxMembers = entry.maxMembers,
                onMaxMembersChange = { entry.maxMembers = it.coerceIn(2, MAX_ROOM_SIZE) },
                busy = entry.busy,
                enabled = code == null,
                error = entry.failure,
                maxSize = MAX_ROOM_SIZE,
                sizeSubtitle = stringResource(R.string.room_size_subtitle),
                onCreate = {
                    entry.busy = true
                    entry.failure = null
                    scope.launch {
                        PhoneRooms.create(entry.maxMembers)
                            .onSuccess { joined() }
                            .onFailure { entry.failure = PhoneRooms.messageOf(it) }
                        entry.busy = false
                    }
                },
            )

            PartySheet.JoinCode -> JoinPartySheet(
                // Typed charitably: case, spaces and dashes do not matter.
                code = entry.codeInput,
                onCodeChange = { typed ->
                    entry.codeInput = RoomCode.normalize(typed).take(ListenTogether.CODE_LENGTH)
                    entry.failure = null
                },
                busy = entry.busy,
                enabled = code == null,
                error = entry.failure,
                onSubmit = { entry.lookUp(entry.codeInput) },
            )

            is PartySheet.Confirm -> JoinConfirmSheet(
                preview = open.preview,
                avatarUrl = null,
                nickname = entry.nickname,
                onNicknameChange = {
                    entry.nickname = it.take(40)
                    runCatching { ListenTogether.setNickname(entry.nickname) }
                },
                busy = entry.busy,
                error = entry.failure,
                onDismiss = dismiss,
                onJoin = {
                    entry.busy = true
                    entry.failure = null
                    scope.launch {
                        // Already in another room: that one is left first, since a device is in one room at a time.
                        if (code != null) PhoneRooms.leave()
                        PhoneRooms.join(open.preview.code)
                            .onSuccess { joined() }
                            .onFailure { entry.failure = PhoneRooms.messageOf(it) }
                        entry.busy = false
                    }
                },
            )

            PartySheet.Invite -> Unit
        }
    }
}

/** A room holds this many unless its creator chooses otherwise. */
private const val DEFAULT_ROOM_SIZE = 5

/** Seats are numbered and the rules refuse a seat at or above the room's size; eight is the ceiling the room client allows. */
private const val MAX_ROOM_SIZE = 8
