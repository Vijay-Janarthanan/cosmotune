package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitOfflineBanner
import com.cosmotune.ui.kit.KitSkeletonRows
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The test tags of the room screen's parts, shared by the UI tests and anything a host wants to find. */
object RoomTags {
    const val TAB_NOW_PLAYING = "room:tab:now-playing"
    const val TAB_CHAT = "room:tab:chat"
    const val TAB_PEOPLE = "room:tab:people"
    const val PAGE_NOW_PLAYING = "room:page:now-playing"
    const val PAGE_CHAT = "room:page:chat"
    const val PAGE_PEOPLE = "room:page:people"
    const val WIDE_PANEL = "room:wide-panel"
    const val UNREAD = "room:unread"
    const val BANNER_RECONNECTING = "room:banner:reconnecting"
    const val BANNER_MESSAGE = "room:banner:message"
    const val CONNECTING = "room:connecting"
    const val REMOVED = "room:removed"
    const val ENDED = "room:ended"
    const val AVATARS = "room:avatars"
    const val MIC = "room:mic"
    const val DEAFEN = "room:deafen"
    const val LEAVE = "room:leave"
    const val VOICE_NOTE = "room:voice-note"
    const val MIC_METER = "room:mic-meter"
    const val MIC_BLOCKED = "room:mic-blocked"
    const val MIC_SETTINGS = "room:mic-settings"
    const val BANNER_VOICE = "room:banner:voice"
    const val BANNER_VOICE_DISMISS = "room:banner:voice-dismiss"
    const val SEEK = "room:seek"
    const val PLAY_PAUSE = "room:play-pause"
    const val PREVIOUS = "room:previous"
    const val NEXT = "room:next"
    const val HOST_ONLY_HINT = "room:host-only-hint"
    const val ASK_TO_PLAY = "room:ask-to-play"
    const val QUEUE_CLEAR = "room:queue-clear"
    const val VIDEO = "room:video"
    const val KIND = "room:kind"
    const val LIVE_BADGE = "room:live-badge"
    const val LIVE_NOTE = "room:live-note"
    const val PLAYBACK_OPTIONS = "room:playback-options"
    const val COMPOSER = "room:composer"
    const val SEND = "room:send"
    const val HOST_ONLY_SWITCH = "room:host-only-switch"
    const val LEAVE_ROOM = "room:leave-room"
    fun queueRow(index: Int) = "room:queue:$index"
    fun queueRemove(index: Int) = "room:queue-remove:$index"
    fun playNow(messageId: String) = "room:play-now:$messageId"
    fun member(uid: String) = "room:member:$uid"
    fun removeMember(uid: String) = "room:remove-member:$uid"
    fun confirmRemove(uid: String) = "room:confirm-remove:$uid"
    /** The speaking ring on [uid]'s face; there is one on every face of theirs that shows (the strip, People, the chat). */
    fun speaking(uid: String) = "room:speaking:$uid"
    fun memberVoice(uid: String) = "room:member-voice:$uid"
    fun memberMute(uid: String) = "room:member-mute:$uid"
    fun memberVolume(uid: String) = "room:member-volume:$uid"
    fun memberConnecting(uid: String) = "room:member-connecting:$uid"
}

/** The pages of the phone's pager, in order; the wide layout's right panel shows the last two as tabs. */
internal enum class RoomPage { NOW_PLAYING, CHAT, PEOPLE }

/**
 * The room: Now Playing, Chat and People, with the voice bar (design section 8). One piece of shared UI for the phone and the
 * desktop; everything it shows comes from [state] and everything it does goes through [actions], so it holds no network or player
 * code and can be rendered in a test from a fake state.
 *
 * [wide] is true on a desktop or tablet window, where Chat and People sit beside Now Playing instead of on pages of their own.
 *
 * The screen paints its own ground and pads itself for the system bars and the keyboard (the chat composer has to stay above it), so
 * a host draws it edge to edge without padding of its own.
 */
@Composable
fun RoomScreen(
    state: RoomUiState,
    actions: RoomActions,
    wide: Boolean,
    modifier: Modifier = Modifier,
    /**
     * What the Now Playing page shows in place of the artwork when the room is playing a video: the platform's own video surface for
     * the item (each device plays it itself). Null draws the artwork, which is right for music, radio and podcasts.
     */
    media: (@Composable () -> Unit)? = null,
) {
    val palette = LocalCosmoColors.current.palette
    // The draft outlives the pager's pages (an off-screen page is disposed), so switching to People and back keeps a half-typed line.
    val draft = remember { mutableStateOf("") }
    Box(modifier.fillMaxSize().background(Color(palette.backgroundBottom)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val room = state.room
        when {
            // The two ends come first: a removed device may still hold the last room it saw, and must not keep drawing it.
            state.connection == RoomConnection.REMOVED -> RoomFinished(
                title = stringResource(Res.string.cosmo_room_removed_title),
                message = stringResource(Res.string.cosmo_room_removed_message),
                tag = RoomTags.REMOVED,
                onClose = actions::leave,
            )
            state.connection == RoomConnection.ENDED -> RoomFinished(
                title = stringResource(Res.string.cosmo_room_ended_title),
                message = stringResource(Res.string.cosmo_room_ended_message),
                tag = RoomTags.ENDED,
                onClose = actions::leave,
            )
            room == null -> RoomConnecting(state.connection, actions::leave)
            wide -> WideRoom(state, room, actions, draft, media)
            else -> PhoneRoom(state, room, actions, draft, media)
        }
    }
}

@Composable
private fun PhoneRoom(state: RoomUiState, room: RoomState, actions: RoomActions, draft: MutableState<String>, media: (@Composable () -> Unit)?) {
    val pager = rememberPagerState { RoomPage.entries.size }
    val scope = rememberCoroutineScope()
    val chatShown = pager.currentPage == RoomPage.CHAT.ordinal
    ChatSeenEffect(chatShown, state.unreadChat, actions)
    Column(Modifier.fillMaxSize()) {
        RoomHeader(state, room, actions, onPeople = { scope.launch { pager.animateScrollToPage(RoomPage.PEOPLE.ordinal) } })
        RoomBanners(state, actions)
        RoomTabs(
            pages = RoomPage.entries,
            selected = RoomPage.entries[pager.targetPage],
            unread = if (chatShown) 0 else state.unreadChat,
            onSelect = { page -> scope.launch { pager.animateScrollToPage(page.ordinal) } },
            modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
        )
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
            when (RoomPage.entries[page]) {
                RoomPage.NOW_PLAYING -> RoomNowPlayingPage(state, room, actions, wide = false, media = media)
                RoomPage.CHAT -> RoomChatPage(state, room, actions, draft)
                RoomPage.PEOPLE -> RoomPeoplePage(state, room, actions)
            }
        }
        RoomVoiceBar(state, actions)
    }
}

@Composable
private fun WideRoom(state: RoomUiState, room: RoomState, actions: RoomActions, draft: MutableState<String>, media: (@Composable () -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    var panel by remember { mutableStateOf(RoomPage.CHAT) }
    ChatSeenEffect(panel == RoomPage.CHAT, state.unreadChat, actions)
    Column(Modifier.fillMaxSize()) {
        RoomHeader(state, room, actions, onPeople = { panel = RoomPage.PEOPLE })
        RoomBanners(state, actions)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // The player keeps the larger share; the voice bar sits under it, where the desktop's player bar would be.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { RoomNowPlayingPage(state, room, actions, wide = true, media = media) }
                RoomVoiceBar(state, actions)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(palette.textPrimary).copy(alpha = 0.08f)))
            Column(Modifier.widthIn(min = 320.dp, max = 420.dp).fillMaxHeight().testTag(RoomTags.WIDE_PANEL)) {
                RoomTabs(
                    pages = listOf(RoomPage.CHAT, RoomPage.PEOPLE),
                    selected = panel,
                    unread = if (panel == RoomPage.CHAT) 0 else state.unreadChat,
                    onSelect = { panel = it },
                    modifier = Modifier.padding(horizontal = CosmoSpacing.margin, vertical = 8.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (panel) {
                        RoomPage.PEOPLE -> RoomPeoplePage(state, room, actions)
                        else -> RoomChatPage(state, room, actions, draft)
                    }
                }
            }
        }
    }
}

/**
 * Tells the host the chat was seen: when the chat comes into view, and again whenever lines arrive while it is in view (the host
 * counts them as unread until told). Nothing more is sent while the count stays at zero.
 */
@Composable
private fun ChatSeenEffect(chatShown: Boolean, unread: Int, actions: RoomActions) {
    val seen = remember { BooleanArray(1) }
    LaunchedEffect(chatShown, unread) {
        if (!chatShown) { seen[0] = false; return@LaunchedEffect }
        if (!seen[0] || unread > 0) { seen[0] = true; actions.chatSeen() }
    }
}

/** The room's code with copy and share, and the faces of the people in it (a tap goes to People). */
@Composable
private fun RoomHeader(state: RoomUiState, room: RoomState, actions: RoomActions, onPeople: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        Modifier.fillMaxWidth().padding(start = CosmoSpacing.margin, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.cosmo_room_code_label).uppercase(), style = type.overline, color = Color(palette.textSecondary), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.code,
                    style = type.titleLarge.tabular(),
                    color = Color(palette.textPrimary),
                    maxLines = 1,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.width(4.dp))
                CosmoIconButton(RoomIcons.Copy, stringResource(Res.string.cosmo_room_copy_code), { actions.copyInviteCode(room.code) }, size = 40.dp)
                CosmoIconButton(CosmoIcons.Share, stringResource(Res.string.cosmo_room_share_invite), { actions.shareInvite(room.code) }, size = 40.dp)
            }
        }
        AvatarStrip(state, room.members, onPeople)
    }
}

/**
 * Overlapping faces, host first, with "+N" past five, each ringed while its person speaks; one button that names everyone for a
 * screen reader.
 */
@Composable
private fun AvatarStrip(state: RoomUiState, members: List<RoomMember>, onClick: () -> Unit) {
    val selfUid = state.selfUid
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val (shown, more) = roomAvatarStrip(members, selfUid)
    val names = sortRoomMembers(members, selfUid).joinToString(", ") { it.name }
    val label = stringResource(Res.string.cosmo_room_people_strip, names)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(CosmoRadii.rLg))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 8.dp)
            .testTag(RoomTags.AVATARS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Each face overlaps the one before by a third, ringed in the ground colour so the edges read. Stacked in one box by start
        // padding (not offset), so the strip is exactly as wide as what it draws.
        Box {
            shown.forEachIndexed { index, member ->
                RoomAvatar(
                    member.avatarUrl,
                    AVATAR_STRIP_DP,
                    member.uid,
                    speaking = state.showsSpeaking(member.uid),
                    modifier = Modifier.padding(start = (AVATAR_STEP_DP * index).dp),
                    ground = Color(palette.backgroundBottom),
                )
            }
            if (more > 0) {
                Box(
                    Modifier
                        .padding(start = (AVATAR_STEP_DP * shown.size).dp)
                        .size(AVATAR_STRIP_DP.dp)
                        .clip(CircleShape)
                        .background(Color(palette.surfaceHigh)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$more", style = type.tabLabel.tabular(), color = Color(palette.textPrimary), maxLines = 1)
                }
            }
        }
    }
}

/** The thin notices over the pages: the connection coming back, the last action's message and a voice problem, each with a dismiss. */
@Composable
private fun RoomBanners(state: RoomUiState, actions: RoomActions) {
    if (state.connection == RoomConnection.RECONNECTING) {
        KitOfflineBanner(
            Modifier.testTag(RoomTags.BANNER_RECONNECTING).semantics { liveRegion = LiveRegionMode.Polite },
            text = stringResource(Res.string.cosmo_room_reconnecting),
        )
    }
    val message = state.message
    if (!message.isNullOrBlank()) RoomNotice(message, RoomTags.BANNER_MESSAGE, actions::dismissMessage)
    val voiceMessage = state.voiceMessage
    if (!voiceMessage.isNullOrBlank()) {
        RoomNotice(voiceMessage, RoomTags.BANNER_VOICE, actions::dismissVoiceMessage, icon = RoomIcons.MicOff, dismissTag = RoomTags.BANNER_VOICE_DISMISS)
    }
}

/** One notice: an accent dot (or a small [icon] for a voice problem), the text in up to three lines, and Dismiss. */
@Composable
private fun RoomNotice(text: String, tag: String, onDismiss: () -> Unit, icon: ImageVector? = null, dismissTag: String? = null) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .background(Color(palette.surface))
            .padding(start = if (icon != null) 10.dp else 14.dp, end = 4.dp)
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color(palette.accentStrong), modifier = Modifier.size(18.dp))
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(palette.accent)))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = type.meta,
            color = Color(palette.textPrimary),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        CosmoIconButton(
            CosmoIcons.Close,
            stringResource(Res.string.cosmo_room_dismiss),
            onDismiss,
            size = 40.dp,
            modifier = if (dismissTag != null) Modifier.testTag(dismissTag) else Modifier,
        )
    }
}

/**
 * The segmented switch between the room's pages: one `surface` pill holding equal segments, the chosen one the inverse neutral (as
 * the kit's chips are). Chat carries the unread count while another page is open. Each segment is a tab with a 48 dp target.
 */
@Composable
internal fun RoomTabs(pages: List<RoomPage>, selected: RoomPage, unread: Int, onSelect: (RoomPage) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val unreadLabel = stringResource(Res.string.cosmo_room_unread, unread)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(palette.surface)).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (page in pages) {
            val isSelected = page == selected
            val badge = if (page == RoomPage.CHAT) roomUnreadBadge(unread) else null
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color(palette.textPrimary) else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(page) })
                    .testTag(page.tabTag)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(page.label),
                    style = type.control,
                    color = Color(if (isSelected) palette.backgroundBottom else palette.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .heightIn(min = 18.dp)
                            .widthIn(min = 18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(palette.accent))
                            .padding(horizontal = 5.dp)
                            .testTag(RoomTags.UNREAD)
                            .semantics { contentDescription = unreadLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badge, style = type.caption.tabular(), color = Color(palette.onAccent), maxLines = 1)
                    }
                }
            }
        }
    }
}

private val RoomPage.label
    get() = when (this) {
        RoomPage.NOW_PLAYING -> Res.string.cosmo_room_tab_now_playing
        RoomPage.CHAT -> Res.string.cosmo_room_tab_chat
        RoomPage.PEOPLE -> Res.string.cosmo_room_tab_people
    }

private val RoomPage.tabTag
    get() = when (this) {
        RoomPage.NOW_PLAYING -> RoomTags.TAB_NOW_PLAYING
        RoomPage.CHAT -> RoomTags.TAB_CHAT
        RoomPage.PEOPLE -> RoomTags.TAB_PEOPLE
    }

/**
 * Before the room's first state arrives: the shape of the people list shimmering under "Joining the room…" (or the reconnect line
 * when a rejoin is under way), and a way out so a join that never lands does not trap anyone.
 */
@Composable
private fun RoomConnecting(connection: RoomConnection, onLeave: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val text = stringResource(if (connection == RoomConnection.RECONNECTING) Res.string.cosmo_room_reconnecting else Res.string.cosmo_room_connecting)
    Column(
        Modifier.fillMaxSize().padding(vertical = 32.dp).testTag(RoomTags.CONNECTING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = type.titleMedium,
            color = Color(palette.textPrimary),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(16.dp))
        KitSkeletonRows(count = 3, art = KitArt.ROUND, modifier = Modifier.widthIn(max = 480.dp))
        Spacer(Modifier.weight(1f))
        KitButton(stringResource(Res.string.cosmo_room_leave), onLeave, kind = KitButtonKind.QUIET, icon = RoomIcons.Leave)
    }
}

/** Removed or ended: the whole screen says so, with one button that closes the room and goes back where the person came from. */
@Composable
private fun RoomFinished(title: String, message: String, tag: String, onClose: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(CosmoIcons.Together, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(48.dp))
        Text(title, style = type.headline, color = Color(palette.textPrimary), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = type.bodyText, color = Color(palette.textSecondary), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(4.dp))
        KitButton(stringResource(Res.string.cosmo_close), onClose)
    }
}

private const val AVATAR_STRIP_DP = 30
private const val AVATAR_STEP_DP = 20
