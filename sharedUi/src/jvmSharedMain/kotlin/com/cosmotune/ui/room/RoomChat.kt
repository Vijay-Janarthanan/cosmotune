package com.cosmotune.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomState
import com.cosmotune.ui.orbit.OrbitCardAction
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitInputPill
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRequestCard
import com.cosmotune.ui.orbit.orbitActionFill
import com.cosmotune.ui.orbit.orbitActionInk
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource

/**
 * The room's chat in Orbit (board batch-a, section 3): runs of lines under one face and name with the mono time, system lines in the
 * middle, a song request as a small colour card with "Play it", and the input pill at the bottom with "Ask for a song". The list is
 * laid out from the bottom (newest first, reversed), so it opens on the latest line and a new line is brought into view by scrolling
 * to the first item. [side] is the gutter.
 */
@Composable
internal fun RoomChatPage(state: RoomUiState, room: RoomState, actions: RoomActions, draft: MutableState<String>, side: Dp) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val entries = remember(room.chat) { groupRoomChat(room.chat).asReversed() }
    val list = rememberLazyListState()
    // A new line from anyone brings the list back to the bottom; keyed on the newest id so an unchanged chat does not scroll.
    LaunchedEffect(room.chat.lastOrNull()?.id) { if (entries.isNotEmpty()) list.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().testTag(RoomTags.PAGE_CHAT)) {
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(RoomIcons.Chat, contentDescription = null, tint = look.dim, modifier = Modifier.size(36.dp))
                    Text(stringResource(Res.string.cosmo_room_chat_empty), style = fonts.body, color = look.dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is RoomChatEntry.Run -> ChatRun(entry, room.member(entry.uid)?.avatarUrl, state.showsSpeaking(entry.uid), fonts, look)
                        is RoomChatEntry.System -> SystemLine(entry.message, fonts, look)
                        is RoomChatEntry.Request -> RequestCard(entry.message, room.member(entry.message.uid)?.avatarUrl, state.canControl, actions, fonts, look)
                    }
                }
            }
        }
        Composer(draft, actions, fonts, look, Modifier.padding(start = side, end = side, top = 6.dp, bottom = 10.dp))
    }
}

/** One sender's run of lines beside their face, which rings while they speak, so a voice can be matched to a name. */
@Composable
private fun ChatRun(run: RoomChatEntry.Run, avatarUrl: String?, speaking: Boolean, fonts: OrbitFonts, look: OrbitLook) {
    val type = LocalCosmoType.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        RoomAvatar(avatarUrl, 32, run.uid, speaking, Modifier.padding(top = 2.dp), name = run.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.name, style = fonts.title.copy(fontSize = 13.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(formatRoomChatTime(run.atMs), style = fonts.label, color = look.dim, maxLines = 1)
            }
            for (message in run.messages) {
                Text(message.text, style = type.forText(fonts.body.copy(fontSize = 14.sp, lineHeight = 19.sp), message.text), color = look.ink)
            }
        }
    }
}

/** "Ravi skipped": small, centred, in the dim ink, with its time. */
@Composable
private fun SystemLine(message: RoomChatMessage, fonts: OrbitFonts, look: OrbitLook) {
    Text(
        message.text + "  ·  " + formatRoomChatTime(message.atMs),
        style = fonts.body.copy(fontSize = 12.5.sp),
        color = look.dim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/**
 * Someone asked for a song: their face and name with the time, and under it the song as a small colour card ([OrbitRequestCard]),
 * "Anu asks: play this" over its title, with "Play it". Play it plays it in the room for everyone, so it follows the same rule as the
 * transport and says why when it is greyed out.
 */
@Composable
private fun RequestCard(message: RoomChatMessage, avatarUrl: String?, canControl: Boolean, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    val ref = message.ref ?: return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        RoomAvatar(avatarUrl, 32, message.uid, false, Modifier.padding(top = 2.dp), name = message.name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.name, style = fonts.title.copy(fontSize = 13.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(formatRoomChatTime(message.atMs), style = fonts.label, color = look.dim, maxLines = 1)
            }
            OrbitRequestCard(
                label = stringResource(Res.string.cosmo_room_request_asks, message.name),
                title = ref.title,
                subtitle = ref.artist,
                cover = ref.thumbnailUrl,
                key = ref.videoId,
                fonts = fonts,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { fill, ink ->
                OrbitCardAction("Play it", fill, ink, fonts, { actions.playTrack(ref) }, Modifier.testTag(RoomTags.playNow(message.id)), enabled = canControl)
            }
            if (!canControl) HostOnlyHint(Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * The input pill: the line being written, "Ask for a song" while it is empty, and Send. The field stops at [ROOM_CHAT_MAX_CHARS] and
 * counts down over the last fifty, so a long line is never refused after it was typed. Enter sends on a keyboard (Shift+Enter is a
 * new line); the phone's keyboard shows Send.
 */
@Composable
private fun Composer(draft: MutableState<String>, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    val hint = stringResource(Res.string.cosmo_room_chat_hint)
    val send: () -> Unit = {
        roomChatToSend(draft.value)?.let { text ->
            actions.sendChat(text)
            draft.value = ""
        }
    }
    val left = ROOM_CHAT_MAX_CHARS - draft.value.length
    val sendable = roomChatToSend(draft.value) != null
    Column(modifier.fillMaxWidth()) {
        if (left <= 50) {
            Text(
                stringResource(Res.string.cosmo_room_chat_left, left),
                style = fonts.label,
                color = if (left <= 10) Color(palette.danger) else look.dim,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
        OrbitInputPill(
            look,
            Modifier.fillMaxWidth(),
            field = {
                if (draft.value.isEmpty()) Text(hint, style = fonts.body.copy(fontSize = 14.sp), color = look.dim, maxLines = 1)
                BasicTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it.take(ROOM_CHAT_MAX_CHARS) },
                    textStyle = fonts.body.copy(fontSize = 14.sp, color = look.ink),
                    cursorBrush = SolidColor(look.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && !event.isShiftPressed) {
                                if (event.type == KeyEventType.KeyDown) send()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag(RoomTags.COMPOSER)
                        .semantics { contentDescription = hint },
                )
            },
            trailing = {
                if (draft.value.isEmpty()) AskChip(actions, fonts, look, Modifier)
                SendButton(sendable, send, fonts, look)
            },
        )
    }
}

/** Send: a 36 dp round button, the one peach action while there is something to send, quiet and disabled while there is not. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit, @Suppress("UNUSED_PARAMETER") fonts: OrbitFonts, look: OrbitLook) {
    val label = stringResource(Res.string.cosmo_room_send)
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(if (enabled) orbitActionFill(look) else look.chip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(RoomTags.SEND),
        contentAlignment = Alignment.Center,
    ) {
        Icon(RoomIcons.Send, contentDescription = null, tint = if (enabled) orbitActionInk(look) else look.dim, modifier = Modifier.size(18.dp))
    }
}
