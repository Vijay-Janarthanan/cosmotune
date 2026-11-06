package com.cosmotune.music.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cosmotune.music.R
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.room.PhoneRooms
import com.cosmotune.music.data.room.RoomPartyState
import com.cosmotune.music.data.room.voice.PhoneRoomVoice
import com.cosmotune.music.playback.toPartyTrack
import com.cosmotune.music.ui.components.PillTextField
import com.cosmotune.ui.bridge.AlertDialog
import com.cosmotune.ui.bridge.ModalBottomSheet
import com.cosmotune.ui.room.RoomScreen
import com.cosmotune.ui.sheets.CosmoDialogBody
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.room.RoomUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The room, full screen over the app while it is open. Back, and the shared screen's own way back, minimise it (the person stays in
 * the room, and the room icon brings it back); Leave, in the screen's "more" menu and on People, asks first in an Orbit dialog. [media] is the picture of the room's video when it is one;
 * [onPlaybackOptions] opens this device's own playback options for the room's item.
 */
@Composable
fun RoomHost(
    wide: Boolean,
    onMinimise: () -> Unit,
    modifier: Modifier = Modifier,
    media: (@Composable () -> Unit)? = null,
    onPlaybackOptions: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room by PhoneRooms.room.collectAsStateWithLifecycle()
    val connection by PhoneRooms.connection.collectAsStateWithLifecycle()
    val local by PhoneRooms.local.collectAsStateWithLifecycle()
    val voice by PhoneRooms.voice.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(PhoneRooms.positionMs()) }
    var confirmLeave by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val currentOptions = rememberUpdatedState(onPlaybackOptions)
    val actions = remember(context) {
        PhoneRoomActions(
            context,
            onAskToPlay = { picking = true },
            onLeaveRequested = { confirmLeave = true },
            onPlaybackOptions = { currentOptions.value() },
        )
    }

    // The shared playhead, about once a second while the screen is up; nothing ticks while it is closed.
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = PhoneRooms.positionMs()
            delay(1_000)
        }
    }
    BackHandler(onBack = onMinimise)

    val selfUid = PhoneRooms.selfUid
    val state = PhoneRoomVoice.applyTo(
        RoomUiState(
            room = room,
            connection = connection,
            selfUid = selfUid,
            positionMs = positionMs,
            message = local.message,
            unreadChat = RoomPartyState.unread(room?.chat.orEmpty(), selfUid, local.seenChatAtMs),
        ),
        voice,
        context::getString,
    )

    Box(modifier.fillMaxSize()) {
        // The shared screen draws the way back (it minimises the room) and the leave in its "more" menu; leaving asks first.
        RoomScreen(state = state, actions = actions, wide = wide, modifier = Modifier.fillMaxSize(), media = media, onClose = onMinimise)
        if (confirmLeave) {
            CosmoDialogCard(onDismiss = { confirmLeave = false }, maxWidth = 420.dp) {
                CosmoDialogBody(
                    overline = stringResource(R.string.room_leave),
                    title = stringResource(R.string.room_leave_title),
                    // Alone, there is nobody to carry on: leaving closes the room.
                    message = stringResource(if ((room?.members?.size ?: 0) <= 1) R.string.listen_together_leave_alone else R.string.listen_together_leave_subtitle),
                    confirmLabel = stringResource(R.string.room_leave),
                    onConfirm = {
                        confirmLeave = false
                        onMinimise()
                        scope.launch { PhoneRooms.leave() }
                    },
                    dismissLabel = stringResource(R.string.cancel),
                    onDismiss = { confirmLeave = false },
                )
            }
        }
    }

    if (picking) {
        AskToPlaySheet(
            onPick = { song ->
                picking = false
                PhoneRooms.askToPlay(song.toPartyTrack(0L))
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * "Ask to play": a song search, and the pick goes into the chat as a request card anyone may play. The same catalogue search the
 * Search tab runs, songs only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskToPlaySheet(onPick: (Song) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val search: () -> Unit = {
        val term = query.trim()
        if (term.isNotEmpty() && !searching) {
            searching = true
            scope.launch {
                results = YtMusicRepository.search(term, SearchFilter.SONGS).getOrDefault(emptyList())
                    .filterIsInstance<SearchResult.Track>()
                    .map { it.song }
                    .take(30)
                searching = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.room_ask_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(12.dp))
            PillTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                placeholder = stringResource(R.string.room_ask_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Spacer(Modifier.size(8.dp))
            when {
                searching -> Text(
                    stringResource(R.string.listen_together_server_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                results?.isEmpty() == true -> Text(
                    stringResource(R.string.room_ask_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(results.orEmpty(), key = { it.videoId }) { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(song) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}
