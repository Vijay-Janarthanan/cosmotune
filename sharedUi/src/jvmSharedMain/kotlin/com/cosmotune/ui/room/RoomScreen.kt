package com.cosmotune.ui.room

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitNotice
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitRoomLabel
import com.cosmotune.ui.orbit.orbitRoomWash
import com.cosmotune.ui.orbit.orbitTalking
import com.cosmotune.ui.theme.LocalCosmoColors
import androidx.compose.ui.graphics.Color
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
    const val MORE = "room:more"
    const val CLOSE = "room:close"
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

/** The pages of the phone's pager, in order; the wide layout's panel shows the last two as tabs. */
internal enum class RoomPage { NOW_PLAYING, CHAT, PEOPLE }

/** The page's side gutter: 16 dp on a phone, 28 on a wide window (the board's). */
internal fun roomSide(wide: Boolean): Dp = if (wide) 28.dp else 16.dp

/**
 * The room in Orbit (board batch-a, section 3): Now Playing, Chat and People, with "Tap to talk" under them. One piece of shared UI
 * for the phone and the desktop; everything it shows comes from [state] and everything it does goes through [actions], so it holds
 * no network or player code and can be rendered in a test from a fake state.
 *
 * At the top, the way back ([onClose], when the host gives one), the mono "LIVE ROOM · code · n here" and the "more" menu (copy the
 * code, share an invite, leave); the pages are chips. [wide] is a desktop or tablet window: Now Playing, the people as discs and the
 * mic on the left, and Chat or People in a panel of their own on the right, picked by the chips at the top end. On a phone the three
 * are pages to swipe between.
 *
 * The screen paints its own ground (the page's, with the room's indigo wash from the top) and pads itself for the system bars and
 * the keyboard (the chat composer has to stay above it), so a host draws it edge to edge without padding of its own.
 */
@Composable
fun RoomScreen(
    state: RoomUiState,
    actions: RoomActions,
    wide: Boolean,
    modifier: Modifier = Modifier,
    /**
     * What the Now Playing page shows over its card when the room is playing a video: the platform's own video surface for the item
     * (each device plays it itself). Null draws the card's disc alone, which is right for music, radio and podcasts.
     */
    media: (@Composable () -> Unit)? = null,
    /** Puts the room away and goes back to the page it was opened over (the room stays joined); null draws no way back. */
    onClose: (() -> Unit)? = null,
) {
    val look = orbitLook()
    // The draft outlives the pager's pages (an off-screen page is disposed), so switching to People and back keeps a half-typed line.
    val draft = remember { mutableStateOf("") }
    Box(modifier.fillMaxSize().background(look.ground).background(orbitRoomWash(look)).windowInsetsPadding(WindowInsets.safeDrawing)) {
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
            wide -> WideRoom(state, room, actions, draft, media, onClose)
            else -> PhoneRoom(state, room, actions, draft, media, onClose)
        }
    }
}

@Composable
private fun PhoneRoom(state: RoomUiState, room: RoomState, actions: RoomActions, draft: MutableState<String>, media: (@Composable () -> Unit)?, onClose: (() -> Unit)?) {
    val pager = rememberPagerState { RoomPage.entries.size }
    val scope = rememberCoroutineScope()
    val chatShown = pager.currentPage == RoomPage.CHAT.ordinal
    ChatSeenEffect(chatShown, state.unreadChat, actions)
    val side = roomSide(false)
    Column(Modifier.fillMaxSize()) {
        RoomHeader(room, actions, side, onClose) {}
        RoomBanners(state, actions, side)
        RoomTabs(
            pages = RoomPage.entries,
            selected = RoomPage.entries[pager.targetPage],
            unread = if (chatShown) 0 else state.unreadChat,
            onSelect = { page -> scope.launch { pager.animateScrollToPage(page.ordinal) } },
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = side, vertical = 6.dp),
        )
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
            when (RoomPage.entries[page]) {
                RoomPage.NOW_PLAYING -> RoomNowPlayingPage(
                    state, room, actions, wide = false, media = media,
                    onPeople = { scope.launch { pager.animateScrollToPage(RoomPage.PEOPLE.ordinal) } },
                )
                RoomPage.CHAT -> RoomChatPage(state, room, actions, draft, side)
                RoomPage.PEOPLE -> RoomPeoplePage(state, room, actions, side)
            }
        }
        RoomVoiceBar(state, actions, Modifier.padding(horizontal = side, vertical = 10.dp))
    }
}

@Composable
private fun WideRoom(state: RoomUiState, room: RoomState, actions: RoomActions, draft: MutableState<String>, media: (@Composable () -> Unit)?, onClose: (() -> Unit)?) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var panel by remember { mutableStateOf(RoomPage.CHAT) }
    ChatSeenEffect(panel == RoomPage.CHAT, state.unreadChat, actions)
    val side = roomSide(true)
    Column(Modifier.fillMaxSize()) {
        RoomHeader(room, actions, side, onClose) {
            RoomTabs(
                pages = listOf(RoomPage.CHAT, RoomPage.PEOPLE),
                selected = panel,
                unread = if (panel == RoomPage.CHAT) 0 else state.unreadChat,
                onSelect = { panel = it },
            )
        }
        RoomBanners(state, actions, side)
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = side, end = side, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // Now Playing keeps the larger share; the mic sits under it, where the desktop's player bar would be.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    RoomNowPlayingPage(state, room, actions, wide = true, media = media, onPeople = { panel = RoomPage.PEOPLE })
                }
                RoomVoiceBar(state, actions, Modifier.padding(top = 12.dp))
            }
            Column(
                Modifier.widthIn(min = 320.dp, max = 440.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp)).background(look.row)
                    .testTag(RoomTags.WIDE_PANEL),
            ) {
                Text(
                    stringResource(panel.label),
                    style = fonts.section,
                    color = look.ink,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 4.dp).semantics { heading() },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (panel) {
                        RoomPage.PEOPLE -> RoomPeoplePage(state, room, actions, 14.dp)
                        else -> RoomChatPage(state, room, actions, draft, 14.dp)
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

/**
 * The top of the room: the way back ([onClose]), the live dot and "LIVE ROOM · code · n here" in mono, then [end] (the wide panel's
 * chips) and the "more" button with copy, share and leave.
 */
@Composable
private fun RoomHeader(room: RoomState, actions: RoomActions, side: Dp, onClose: (() -> Unit)?, end: @Composable () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var more by remember { mutableStateOf(false) }
    val copy = stringResource(Res.string.cosmo_room_copy_code)
    val share = stringResource(Res.string.cosmo_room_share_invite)
    val leave = stringResource(Res.string.cosmo_room_leave_room)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = side, end = side, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null) {
            OrbitRoundButton(CosmoIcons.ChevronDown, "Close the room", look.ink, onClose, Modifier.testTag(RoomTags.CLOSE))
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.size(7.dp).clip(CircleShape).background(orbitTalking(look)))
        Spacer(Modifier.width(8.dp))
        Text(
            "LIVE ROOM · ${room.code} · ${room.members.size} HERE",
            style = fonts.label,
            color = orbitRoomLabel(look),
            maxLines = 1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        end()
        Spacer(Modifier.width(8.dp))
        Box {
            OrbitRoundButton(CosmoIcons.More, "More", look.ink, { more = true }, Modifier.testTag(RoomTags.MORE))
            OrbitMenu(
                open = more,
                onDismiss = { more = false },
                items = listOf(
                    OrbitMenuItem(copy, detail = room.code, icon = RoomIcons.Copy) { actions.copyInviteCode(room.code) },
                    OrbitMenuItem(share, icon = CosmoIcons.Share) { actions.shareInvite(room.code) },
                    OrbitMenuItem(leave, icon = RoomIcons.Leave) { actions.leave() },
                ),
                fonts = fonts,
                look = look,
                header = "Room ${room.code}",
            )
        }
    }
}

/** The quiet notices over the pages: the connection coming back, the last action's message and a voice problem, each with a dismiss. */
@Composable
private fun RoomBanners(state: RoomUiState, actions: RoomActions, side: Dp) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val modifier = Modifier.fillMaxWidth().padding(horizontal = side, vertical = 4.dp)
    if (state.connection == RoomConnection.RECONNECTING) {
        OrbitNotice(
            stringResource(Res.string.cosmo_room_reconnecting), fonts, look,
            modifier.testTag(RoomTags.BANNER_RECONNECTING).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val message = state.message
    if (!message.isNullOrBlank()) RoomNotice(message, RoomTags.BANNER_MESSAGE, actions::dismissMessage, modifier, fonts, look)
    val voiceMessage = state.voiceMessage
    if (!voiceMessage.isNullOrBlank()) {
        RoomNotice(voiceMessage, RoomTags.BANNER_VOICE, actions::dismissVoiceMessage, modifier, fonts, look, icon = RoomIcons.MicOff, dismissTag = RoomTags.BANNER_VOICE_DISMISS)
    }
}

/** One notice: [OrbitNotice] with a round Dismiss at its end. */
@Composable
internal fun RoomNotice(
    text: String,
    tag: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    fonts: OrbitFonts,
    look: OrbitLook,
    icon: ImageVector? = null,
    dismissTag: String? = null,
) {
    OrbitNotice(text, fonts, look, modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }, icon = icon) {
        OrbitRoundButton(
            CosmoIcons.Close,
            stringResource(Res.string.cosmo_room_dismiss),
            look.ink,
            onDismiss,
            if (dismissTag != null) Modifier.testTag(dismissTag) else Modifier,
            size = 36.dp,
        )
    }
}

/**
 * The switch between the room's pages as Orbit chips (rule 7): the page that shows is solid ink with ground-coloured words. Chat
 * carries the unread count while another page is open. Each chip is a tab.
 */
@Composable
internal fun RoomTabs(pages: List<RoomPage>, selected: RoomPage, unread: Int, onSelect: (RoomPage) -> Unit, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val palette = LocalCosmoColors.current.palette
    val unreadLabel = stringResource(Res.string.cosmo_room_unread, unread)
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (page in pages) {
            val isSelected = page == selected
            val badge = if (page == RoomPage.CHAT) roomUnreadBadge(unread) else null
            OrbitChip(
                stringResource(page.label), fonts, look,
                onClick = { onSelect(page) },
                modifier = Modifier.testTag(page.tabTag).semantics { this.selected = isSelected; role = Role.Tab },
                selected = isSelected,
                leading = null,
                trailing = if (badge == null) null else {
                    {
                        Box(
                            Modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(CircleShape).background(look.accent)
                                .padding(horizontal = 5.dp).testTag(RoomTags.UNREAD).semantics { contentDescription = unreadLabel },
                            contentAlignment = Alignment.Center,
                        ) { Text(badge, style = fonts.label.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = Color(palette.onAccent), maxLines = 1) }
                    }
                },
            )
        }
    }
}

internal val RoomPage.label
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
 * Before the room's first state arrives: "Joining the room…" (or the reconnect line when a rejoin is under way) over the shape of the
 * page, quiet rows of the row's fill, and a way out so a join that never lands does not trap anyone.
 */
@Composable
private fun RoomConnecting(connection: RoomConnection, onLeave: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val text = stringResource(if (connection == RoomConnection.RECONNECTING) Res.string.cosmo_room_reconnecting else Res.string.cosmo_room_connecting)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp).testTag(RoomTags.CONNECTING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("LISTEN TOGETHER", style = fonts.label, color = orbitRoomLabel(look))
        Text(
            text,
            style = fonts.section,
            color = look.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(24.dp))
        Box(Modifier.widthIn(max = 600.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(34.dp)).background(look.row))
        Spacer(Modifier.height(12.dp))
        repeat(3) {
            Box(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(vertical = 4.dp).height(62.dp).clip(RoundedCornerShape(22.dp)).background(look.row))
        }
        Spacer(Modifier.weight(1f))
        OrbitPillButton(stringResource(Res.string.cosmo_room_leave), onLeave, fonts, look, icon = RoomIcons.Leave)
    }
}

/** Removed or ended: the whole screen says so, with one button that closes the room and goes back where the person came from. */
@Composable
private fun RoomFinished(title: String, message: String, tag: String, onClose: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(CosmoIcons.Together, contentDescription = null, tint = look.dim, modifier = Modifier.size(48.dp))
        Text(title, style = fonts.section, color = look.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = fonts.body, color = look.dim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(4.dp))
        OrbitPillButton(stringResource(Res.string.cosmo_close), onClose, fonts, look, primary = true)
    }
}
