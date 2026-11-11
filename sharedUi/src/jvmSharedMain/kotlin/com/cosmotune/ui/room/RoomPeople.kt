package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitTalking
import org.jetbrains.compose.resources.stringResource

/**
 * Who is in the room, the invite, the host's setting and the way out, in Orbit: the people as the kit's rows (their face as the
 * disc, ringed green while they speak; Host and You beside the name; whether they are here; their mic at the end). Removing someone
 * asks first, in the row itself: a dialog would cover the list the host is looking at, and the question names the person so the
 * wrong row is hard to confirm.
 *
 * Once voice exists, tapping someone else opens their voice panel under the row (one open at a time): mute them for you only, and
 * their volume on this device. Neither is sent to the room. [side] is the gutter.
 */
@Composable
internal fun RoomPeoplePage(state: RoomUiState, room: RoomState, actions: RoomActions, side: Dp) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var confirming by remember { mutableStateOf<String?>(null) }
    var voiceOpen by remember { mutableStateOf<String?>(null) }
    val members = sortRoomMembers(room.members, state.selfUid)
    LazyColumn(
        Modifier.fillMaxSize().testTag(RoomTags.PAGE_PEOPLE),
        contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "count") { Label(stringResource(Res.string.cosmo_room_members_count, members.size, room.meta.maxMembers), fonts, look) }
        for (member in members) {
            item(key = "m:${member.uid}") {
                val isSelf = member.uid == state.selfUid
                MemberRow(
                    member = member,
                    isSelf = isSelf,
                    canRemove = state.isHost && !member.isHost && !isSelf,
                    confirming = confirming == member.uid,
                    onAskRemove = { confirming = member.uid },
                    onCancel = { confirming = null },
                    onRemove = { confirming = null; actions.removeMember(member.uid) },
                    voice = MemberVoice(
                        enabled = state.voiceAvailable && !isSelf,
                        speaking = state.showsSpeaking(member.uid),
                        mutedHere = member.uid in state.mutedHere,
                        volume = state.volumeOf(member.uid),
                        connecting = state.voiceAvailable && !isSelf && member.uid in state.voiceConnecting,
                    ),
                    voiceOpen = voiceOpen == member.uid,
                    onToggleVoice = { voiceOpen = if (voiceOpen == member.uid) null else member.uid },
                    actions = actions,
                    fonts = fonts,
                    look = look,
                )
            }
        }
        item(key = "invite") { Invite(room.code, actions, fonts, look) }
        if (state.isHost) {
            item(key = "host") {
                val hostOnly = room.meta.hostOnlyControl
                Column(Modifier.padding(top = 8.dp)) {
                    Label(stringResource(Res.string.cosmo_room_host_settings), fonts, look)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(look.row).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.cosmo_room_host_only_control), style = fonts.title.copy(fontSize = 14.sp), color = look.ink)
                            Text(stringResource(Res.string.cosmo_room_host_only_control_summary), style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim)
                        }
                        Spacer(Modifier.width(12.dp))
                        OrbitSwitch(hostOnly, actions::setHostOnlyControl, look, stringResource(Res.string.cosmo_room_host_only_control), Modifier.testTag(RoomTags.HOST_ONLY_SWITCH))
                    }
                }
            }
        }
        item(key = "leave") {
            OrbitPillButton(
                stringResource(Res.string.cosmo_room_leave_room), actions::leave, fonts, look,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(RoomTags.LEAVE_ROOM),
                icon = RoomIcons.Leave,
            )
        }
    }
}

/** A mono label over a group ("3 OF 5 PEOPLE"). */
@Composable
private fun Label(text: String, fonts: OrbitFonts, look: OrbitLook) {
    Text(text.uppercase(), style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp).semantics { heading() })
}

/** The code to share, in the wide face, with copy and share, and the line that says who may join. */
@Composable
private fun Invite(code: String, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Label(stringResource(Res.string.cosmo_room_invite), fonts, look)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(look.row).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = fonts.heroTitle.copy(letterSpacing = 2.sp), color = look.ink, maxLines = 1, modifier = Modifier.weight(1f))
                OrbitRoundButton(RoomIcons.Copy, stringResource(Res.string.cosmo_room_copy_code), look.ink, { actions.copyInviteCode(code) })
                Spacer(Modifier.width(6.dp))
                OrbitRoundButton(CosmoIcons.Share, stringResource(Res.string.cosmo_room_share_invite), look.ink, { actions.shareInvite(code) })
            }
            Text(stringResource(Res.string.cosmo_room_invite_hint), style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.padding(top = 6.dp, end = 6.dp))
        }
    }
}

/** What a People row shows of someone's voice on this device. [enabled] is false for yourself and while voice is not offered. */
internal data class MemberVoice(
    val enabled: Boolean,
    val speaking: Boolean,
    val mutedHere: Boolean,
    val volume: Float,
    val connecting: Boolean,
)

/**
 * One person as the kit's song row: their face (a dot on its edge for here or away, the green ring while they speak), their name with
 * Host and You, whether they are connected (or that their voice is connecting), the state of their microphone, and for the host a
 * remove button that turns the row into a question. With voice on, tapping someone else opens [MemberVoicePanel] under the row.
 */
@Composable
private fun MemberRow(
    member: RoomMember,
    isSelf: Boolean,
    canRemove: Boolean,
    confirming: Boolean,
    onAskRemove: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    voice: MemberVoice,
    voiceOpen: Boolean,
    onToggleVoice: () -> Unit,
    actions: RoomActions,
    fonts: OrbitFonts,
    look: OrbitLook,
) {
    val presence = stringResource(if (member.connected) Res.string.cosmo_room_connected else Res.string.cosmo_room_away)
    val optionsLabel = stringResource(Res.string.cosmo_room_voice_options, member.name)
    val openState = stringResource(if (voiceOpen) Res.string.cosmo_room_voice_options_open else Res.string.cosmo_room_voice_options_closed)
    val connecting = stringResource(Res.string.cosmo_room_voice_connecting)
    Column(Modifier.fillMaxWidth().testTag(RoomTags.member(member.uid))) {
        OrbitSongRow(
            title = member.name,
            subtitle = presence,
            cover = null,
            fonts = fonts,
            look = look,
            onClick = if (voice.enabled) onToggleVoice else null,
            modifier = Modifier.fillMaxWidth().then(
                if (voice.enabled) Modifier.semantics { contentDescription = optionsLabel; stateDescription = openState } else Modifier,
            ),
            height = 68.dp,
            endPadding = 6.dp,
            discContent = {
                Box {
                    RoomAvatar(member.avatarUrl, 44, member.uid, voice.speaking, name = member.name)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                            .background(if (member.connected) orbitTalking(look) else look.dim)
                            .border(2.dp, look.ground, CircleShape)
                            .semantics { contentDescription = presence },
                    )
                }
            },
            titleContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(member.name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (member.isHost) Tag(stringResource(Res.string.cosmo_room_host), strong = true, fonts, look)
                    if (isSelf) Tag(stringResource(Res.string.cosmo_room_you), strong = false, fonts, look)
                }
            },
            subtitleLeading = if (voice.connecting) {
                { Text(connecting, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = 1, modifier = Modifier.testTag(RoomTags.memberConnecting(member.uid))) }
            } else null,
            trailing = {
                MemberMicIcon(member.micLive, voice, look)
                if (canRemove) {
                    OrbitRoundButton(
                        RoomIcons.RemovePerson,
                        stringResource(Res.string.cosmo_room_remove_member, member.name),
                        look.ink,
                        onAskRemove,
                        Modifier.testTag(RoomTags.removeMember(member.uid)),
                        size = 36.dp,
                    )
                }
            },
        )
        if (voiceOpen && voice.enabled) MemberVoicePanel(member, voice, actions, fonts, look)
        if (confirming) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp).testTag(RoomTags.confirmRemove(member.uid)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.cosmo_room_remove_confirm, member.name), style = fonts.body, color = look.ink, modifier = Modifier.weight(1f).padding(end = 12.dp))
                OrbitPillButton(stringResource(Res.string.cosmo_room_cancel), onCancel, fonts, look, height = 40.dp)
                Spacer(Modifier.width(8.dp))
                OrbitPillButton(stringResource(Res.string.cosmo_room_remove), onRemove, fonts, look, primary = true, height = 40.dp)
            }
        }
    }
}

/**
 * The microphone glyph at the row's end. Muted for you wins (a crossed speaker: their own mic may still be live, you just do not hear
 * it), then speaking (the green mic), then their own mic state. Each is said in words.
 */
@Composable
private fun MemberMicIcon(micLive: Boolean, voice: MemberVoice, look: OrbitLook) {
    val (icon, label, tint) = when {
        voice.mutedHere -> Triple(RoomIcons.MutedHere, Res.string.cosmo_room_muted_for_you, look.dim)
        voice.speaking -> Triple(RoomIcons.Mic, Res.string.cosmo_room_speaking, orbitTalking(look))
        micLive -> Triple(RoomIcons.Mic, Res.string.cosmo_room_member_mic_live, look.ink)
        else -> Triple(RoomIcons.MicOff, Res.string.cosmo_room_member_mic_muted, look.dim.copy(alpha = 0.7f))
    }
    Icon(icon, contentDescription = stringResource(label), tint = tint, modifier = Modifier.padding(horizontal = 10.dp).size(20.dp))
}

/**
 * Someone's voice on this device: "Mute for me" (a switch) and their volume, 0 to 100 %, which is heard as the slider moves. The
 * slider follows the finger while dragging and the host's value otherwise. Nothing here reaches the room. The volume stays set while
 * they are muted, for when they are unmuted.
 */
@Composable
private fun MemberVoicePanel(member: RoomMember, voice: MemberVoice, actions: RoomActions, fonts: OrbitFonts, look: OrbitLook) {
    var dragging by remember(member.uid) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: voice.volume
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(look.chip)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(RoomTags.memberVoice(member.uid)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(RoomIcons.MutedHere, contentDescription = null, tint = look.dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.cosmo_room_mute_for_me), style = fonts.title.copy(fontSize = 14.sp), color = look.ink)
                Text(stringResource(Res.string.cosmo_room_mute_for_me_summary, member.name), style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim)
            }
            OrbitSwitch(voice.mutedHere, { actions.setMemberMuted(member.uid, it) }, look, stringResource(Res.string.cosmo_room_mute_for_me), Modifier.testTag(RoomTags.memberMute(member.uid)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(RoomIcons.Volume, contentDescription = null, tint = look.dim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.cosmo_room_volume), style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, modifier = Modifier.weight(1f))
            // The slider tells a screen reader its own value; the figure is for the eye.
            Text("${roomVolumePercent(shown)}%", style = fonts.label, color = look.dim, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
        }
        CosmoSlider(
            value = shown,
            onValueChange = { value ->
                val volume = roomVolume(value)
                dragging = volume
                actions.setMemberVolume(member.uid, volume)
            },
            onValueChangeFinished = { dragging = null },
            contentDescription = stringResource(Res.string.cosmo_room_volume_for, member.name),
            compact = true,
            ink = look.ink,
            modifier = Modifier.testTag(RoomTags.memberVolume(member.uid)),
        )
    }
}

/** A small pill beside a name: Host in the accent, You on the quiet chip. */
@Composable
private fun Tag(text: String, strong: Boolean, fonts: OrbitFonts, look: OrbitLook) {
    Text(
        text,
        style = fonts.label.copy(fontSize = 10.sp, letterSpacing = 0.6.sp),
        color = if (strong) look.ground else look.ink,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (strong) look.accent else look.chip).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
