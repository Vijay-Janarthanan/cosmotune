package com.cosmotune.video.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.chat.ChatMessage
import com.cosmotune.video.chat.ChatRole
import com.cosmotune.video.chat.ChatState
import com.cosmotune.video.chat.ChatStatus
import com.cosmotune.video.chat.LiveChatFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_chat_failed
import com.cosmotune.video.resources.video_chat_live
import com.cosmotune.video.resources.video_chat_newest
import com.cosmotune.video.resources.video_chat_none
import com.cosmotune.video.resources.video_chat_pinned
import com.cosmotune.video.resources.video_chat_replay
import com.cosmotune.video.resources.video_chat_waiting
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.comments.CommentsCloseButton
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * The chat of a live stream, or the replay of a finished one's, on the watch page: the side column's Chat chip on a wide window,
 * a sheet under the video on the phone. Read only; the newest message is at the bottom and the list follows it while the listener
 * is at the bottom; scrolled up, a "Newest" chip brings them back.
 */

private const val SHEET_HEIGHT_SHARE = 0.7f

/** The chat in the Orbit bottom sheet, opened from the phone's Chat tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveChatSheet(videoId: String, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val listHeight = with(density) { (LocalWindowInfo.current.containerSize.height * SHEET_HEIGHT_SHARE).toDp() }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height(listHeight)) { LiveChatBody(videoId, onDismiss) }
    }
}

/** The chat as the wide page's side column shows it. */
@Composable
internal fun LiveChatPanel(videoId: String, modifier: Modifier = Modifier) {
    Column(modifier) { LiveChatBody(videoId, onDismiss = null) }
}

@Composable
private fun ColumnScope.LiveChatBody(videoId: String, onDismiss: (() -> Unit)?) {
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    val playback = graph.playback
    val feed = remember(videoId, graph) { LiveChatFeed(graph.liveChat, videoId, scope) { playback.positionMs() } }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.close() }
    }
    val state by feed.state.collectAsState()
    ChatHeader(state, onDismiss)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (state.status) {
            ChatStatus.NONE -> CosmoEmptyState(message = stringResource(Res.string.video_chat_none), icon = VideoIcons.Comment)
            ChatStatus.FAILED -> CosmoEmptyState(
                message = stringResource(Res.string.video_chat_failed),
                icon = VideoIcons.Comment,
                actionLabel = stringResource(Res.string.video_retry),
                onAction = feed::retry,
            )
            else -> ChatList(state)
        }
    }
}

@Composable
private fun ChatHeader(state: ChatState, onDismiss: (() -> Unit)?) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (state.replay) Res.string.video_chat_replay else Res.string.video_chat_live),
            style = fonts.section,
            color = look.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onDismiss != null) CommentsCloseButton(onDismiss)
    }
}

@Composable
private fun ChatList(state: ChatState) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (atBottom && state.messages.isNotEmpty()) list.scrollToItem(state.messages.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        state.pinned?.let { pinned ->
            Column(
                Modifier
                    .padding(horizontal = CosmoSpacing.margin)
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(look.accent.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                OrbitLabel(stringResource(Res.string.video_chat_pinned), fonts, look.accent)
                Spacer(Modifier.height(4.dp))
                MessageText(pinned)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Text(
                    text = stringResource(Res.string.video_chat_waiting),
                    style = fonts.body,
                    color = look.dim,
                    modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
                items(state.messages, key = { it.id }) { message -> ChatRow(message) }
            }
            if (!atBottom && state.messages.isNotEmpty()) {
                OrbitSelectChip(
                    title = stringResource(Res.string.video_chat_newest),
                    selected = true,
                    fonts = fonts,
                    look = look,
                    onClick = { scope.launch { list.animateScrollToItem(state.messages.size - 1) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }
    }
}

/** One message: the avatar, then the name (in the accent for the owner and moderators) and the text; a Super Chat sits on its colour. */
@Composable
private fun ChatRow(message: ChatMessage) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val paid = message.paid
    val rowModifier = if (paid != null) {
        Modifier
            .padding(horizontal = CosmoSpacing.margin, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(paid.colorArgb).copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    } else {
        Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 5.dp).fillMaxWidth()
    }
    Row(rowModifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.Start) {
        ChannelAvatar(message.authorPhotoUrl, 24.dp, name = message.author)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (paid != null) OrbitLabel(paid.amount, fonts, look.ink)
            MessageText(message)
        }
    }
}

@Composable
private fun MessageText(message: ChatMessage) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val nameColour = when (message.role) {
        ChatRole.OWNER, ChatRole.MODERATOR -> look.accent
        ChatRole.MEMBER -> look.ink
        else -> look.dim
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = nameColour, fontWeight = fonts.title.fontWeight)) { append(message.author) }
        if (message.text.isNotEmpty()) {
            append("  ")
            append(message.text)
        }
    }
    Text(text = text, style = fonts.body, color = look.ink)
}
