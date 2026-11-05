package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitArtwork
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource

/**
 * The room's chat: runs of lines under one face and name, system lines in the middle, request cards with Play now, and the composer
 * at the bottom. The list is laid out from the bottom (newest first, reversed), so it opens on the latest line and a new line is
 * brought into view by scrolling to the first item.
 */
@Composable
internal fun RoomChatPage(state: RoomUiState, room: RoomState, actions: RoomActions, draft: MutableState<String>) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val entries = remember(room.chat) { groupRoomChat(room.chat).asReversed() }
    val list = rememberLazyListState()
    // A new line from anyone brings the list back to the bottom; keyed on the newest id so an unchanged chat does not scroll.
    LaunchedEffect(room.chat.lastOrNull()?.id) { if (entries.isNotEmpty()) list.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().testTag(RoomTags.PAGE_CHAT)) {
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(RoomIcons.Chat, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(40.dp))
                    Text(
                        stringResource(Res.string.cosmo_room_chat_empty),
                        style = type.bodyText,
                        color = Color(palette.textSecondary),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is RoomChatEntry.Run -> ChatRun(entry, room.member(entry.uid)?.avatarUrl, state.showsSpeaking(entry.uid))
                        is RoomChatEntry.System -> SystemLine(entry.message)
                        is RoomChatEntry.Request -> RequestCard(entry.message, state.canControl, actions)
                    }
                }
            }
        }
        Composer(draft, actions)
    }
}

/** One sender's run of lines under their face, which rings while they speak, so a voice can be matched to a name. */
@Composable
private fun ChatRun(run: RoomChatEntry.Run, avatarUrl: String?, speaking: Boolean) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 6.dp)) {
        RoomAvatar(avatarUrl, 36, run.uid, speaking)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.name, style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(formatRoomChatTime(run.atMs), style = type.caption, color = Color(palette.textTertiary), maxLines = 1)
            }
            for (message in run.messages) {
                Text(message.text, style = type.forText(type.bodyText, message.text), color = Color(palette.textPrimary))
            }
        }
    }
}

/** "Ravi skipped": small, centred, in secondary ink, with its time. */
@Composable
private fun SystemLine(message: RoomChatMessage) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        message.text + "  ·  " + formatRoomChatTime(message.atMs),
        style = type.caption,
        color = Color(palette.textSecondary),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

/**
 * Someone asked for a song: its cover and title, "Anu asks: play this" and Play now. Play now plays it in the room for everyone, so
 * it follows the same rule as the transport and says why when it is greyed out.
 */
@Composable
private fun RequestCard(message: RoomChatMessage, canControl: Boolean, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val ref = message.ref ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 6.dp)
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .background(Color(palette.surface))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KitArtwork(ref.thumbnailUrl, KitArt.SQUARE, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.cosmo_room_request_asks, message.name),
                    style = type.meta,
                    color = Color(palette.accentStrong),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(ref.title, style = type.forText(type.rowTitle, ref.title), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ref.artist.isNotBlank()) {
                    Text(ref.artist, style = type.forText(type.meta, ref.artist), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(formatRoomChatTime(message.atMs), style = type.caption, color = Color(palette.textTertiary), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            KitButton(
                stringResource(Res.string.cosmo_room_play_now),
                { actions.playTrack(ref) },
                enabled = canControl,
                dense = true,
                modifier = Modifier.testTag(RoomTags.playNow(message.id)),
            )
            if (!canControl) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.cosmo_room_host_only_hint), style = type.caption, color = Color(palette.textSecondary), modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The line being written and Send. The field stops at [ROOM_CHAT_MAX_CHARS] and counts down over the last fifty, so a long line is
 * never refused after it was typed. Enter sends on a keyboard (Shift+Enter is a new line); the phone's keyboard shows Send.
 */
@Composable
private fun Composer(draft: MutableState<String>, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val hint = stringResource(Res.string.cosmo_room_chat_hint)
    val send: () -> Unit = {
        roomChatToSend(draft.value)?.let { text ->
            actions.sendChat(text)
            draft.value = ""
        }
    }
    val left = ROOM_CHAT_MAX_CHARS - draft.value.length
    Column(Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        if (left <= 50) {
            Text(
                stringResource(Res.string.cosmo_room_chat_left, left),
                style = type.caption,
                color = Color(if (left <= 10) palette.danger else palette.textSecondary),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(CosmoRadii.rLg))
                    .background(Color(palette.surface))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.value.isEmpty()) Text(hint, style = type.bodyText, color = Color(palette.textTertiary), maxLines = 1)
                BasicTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it.take(ROOM_CHAT_MAX_CHARS) },
                    textStyle = type.bodyText.copy(color = Color(palette.textPrimary)),
                    cursorBrush = SolidColor(Color(palette.accent)),
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
            }
            Spacer(Modifier.width(4.dp))
            CosmoIconButton(
                RoomIcons.Send,
                stringResource(Res.string.cosmo_room_send),
                send,
                style = CosmoIconButtonStyle.TONAL,
                size = 48.dp,
                enabled = roomChatToSend(draft.value) != null,
                modifier = Modifier.testTag(RoomTags.SEND),
            )
        }
    }
}
