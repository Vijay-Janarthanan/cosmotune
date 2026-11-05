package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomState
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitDivider
import com.cosmotune.ui.kit.KitGroup
import com.cosmotune.ui.kit.KitSettingsRow
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.kit.KitTileTint
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import org.jetbrains.compose.resources.stringResource

/**
 * Who is in the room, the invite, the host's settings and the way out. Removing someone asks first, in the row itself: a dialog
 * would cover the list the host is looking at, and the question names the person so the wrong row is hard to confirm.
 *
 * Once voice exists, tapping someone else opens their voice panel under the row (one open at a time): mute them for you only, and
 * their volume on this device. Neither is sent to the room.
 */
@Composable
internal fun RoomPeoplePage(state: RoomUiState, room: RoomState, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var confirming by remember { mutableStateOf<String?>(null) }
    var voiceOpen by remember { mutableStateOf<String?>(null) }
    val members = sortRoomMembers(room.members, state.selfUid)
    LazyColumn(Modifier.fillMaxSize().testTag(RoomTags.PAGE_PEOPLE), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "members") {
            KitGroup(title = stringResource(Res.string.cosmo_room_members_count, members.size, room.meta.maxMembers)) {
                members.forEachIndexed { index, member ->
                    if (index > 0) KitDivider()
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
                    )
                }
            }
        }
        item(key = "invite") {
            KitGroup(title = stringResource(Res.string.cosmo_room_invite)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(room.code, style = type.headline.tabular(), color = Color(palette.textPrimary), maxLines = 1, modifier = Modifier.weight(1f))
                    CosmoIconButton(RoomIcons.Copy, stringResource(Res.string.cosmo_room_copy_code), { actions.copyInviteCode(room.code) })
                    CosmoIconButton(CosmoIcons.Share, stringResource(Res.string.cosmo_room_share_invite), { actions.shareInvite(room.code) })
                }
                Text(
                    stringResource(Res.string.cosmo_room_invite_hint),
                    style = type.meta,
                    color = Color(palette.textSecondary),
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
        }
        if (state.isHost) {
            item(key = "host") {
                KitGroup(title = stringResource(Res.string.cosmo_room_host_settings)) {
                    val hostOnly = room.meta.hostOnlyControl
                    KitSettingsRow(
                        title = stringResource(Res.string.cosmo_room_host_only_control),
                        summary = stringResource(Res.string.cosmo_room_host_only_control_summary),
                        icon = CosmoIcons.Together,
                        tile = KitTileTint.PURPLE,
                        onClick = { actions.setHostOnlyControl(!hostOnly) },
                        trailing = {
                            CosmoSwitch(hostOnly, actions::setHostOnlyControl, modifier = Modifier.testTag(RoomTags.HOST_ONLY_SWITCH))
                        },
                    )
                }
            }
        }
        item(key = "leave") {
            KitButton(
                stringResource(Res.string.cosmo_room_leave_room),
                actions::leave,
                kind = KitButtonKind.QUIET,
                icon = RoomIcons.Leave,
                modifier = Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 20.dp).testTag(RoomTags.LEAVE_ROOM),
            )
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
 * One person: their face with a presence dot (connected or away) and the speaking ring, their name with Host and You, the state of
 * their microphone (or that you muted them), and for the host a remove button that turns the row into a question. With voice on,
 * tapping someone else opens [MemberVoicePanel] under the row; "Connecting voice…" shows while their link is being made.
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
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val presence = stringResource(if (member.connected) Res.string.cosmo_room_connected else Res.string.cosmo_room_away)
    val optionsLabel = stringResource(Res.string.cosmo_room_voice_options, member.name)
    val openState = stringResource(if (voiceOpen) Res.string.cosmo_room_voice_options_open else Res.string.cosmo_room_voice_options_closed)
    Column(Modifier.fillMaxWidth().testTag(RoomTags.member(member.uid))) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(
                    if (voice.enabled) {
                        Modifier
                            .clickable(role = Role.Button, onClickLabel = optionsLabel, onClick = onToggleVoice)
                            .semantics { stateDescription = openState }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                RoomAvatar(member.avatarUrl, 40, member.uid, voice.speaking)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(if (member.connected) palette.accent else palette.textTertiary))
                        .border(2.dp, Color(palette.surface), CircleShape)
                        .semantics { contentDescription = presence },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(member.name, style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (member.isHost) Tag(stringResource(Res.string.cosmo_room_host), strong = true)
                    if (isSelf) Tag(stringResource(Res.string.cosmo_room_you), strong = false)
                }
                Text(presence, style = type.meta, color = Color(palette.textSecondary), maxLines = 1)
                if (voice.connecting) {
                    Text(
                        stringResource(Res.string.cosmo_room_voice_connecting),
                        style = type.caption,
                        color = Color(palette.textTertiary),
                        maxLines = 1,
                        modifier = Modifier.testTag(RoomTags.memberConnecting(member.uid)),
                    )
                }
            }
            MemberMicIcon(member.micLive, voice)
            if (canRemove) {
                CosmoIconButton(
                    RoomIcons.RemovePerson,
                    stringResource(Res.string.cosmo_room_remove_member, member.name),
                    onAskRemove,
                    size = 40.dp,
                    tint = Color(palette.textSecondary),
                    modifier = Modifier.testTag(RoomTags.removeMember(member.uid)),
                )
            }
        }
        if (voiceOpen && voice.enabled) MemberVoicePanel(member, voice, actions)
        if (confirming) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 10.dp).testTag(RoomTags.confirmRemove(member.uid)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.cosmo_room_remove_confirm, member.name), style = type.bodyText, color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
                KitTextButton(stringResource(Res.string.cosmo_room_cancel), onCancel)
                KitButton(stringResource(Res.string.cosmo_room_remove), onRemove, dense = true)
            }
        }
    }
}

/**
 * The microphone glyph at the row's end. Muted for you wins (a crossed speaker: their own mic may still be live, you just do not hear
 * it), then speaking (the accent mic), then their own mic state. Each is said in words.
 */
@Composable
private fun MemberMicIcon(micLive: Boolean, voice: MemberVoice) {
    val palette = LocalCosmoColors.current.palette
    val (icon, label, tint) = when {
        voice.mutedHere -> Triple(RoomIcons.MutedHere, Res.string.cosmo_room_muted_for_you, palette.textSecondary)
        voice.speaking -> Triple(RoomIcons.Mic, Res.string.cosmo_room_speaking, palette.accent)
        micLive -> Triple(RoomIcons.Mic, Res.string.cosmo_room_member_mic_live, palette.accentStrong)
        else -> Triple(RoomIcons.MicOff, Res.string.cosmo_room_member_mic_muted, palette.textTertiary)
    }
    Icon(icon, contentDescription = stringResource(label), tint = Color(tint), modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
}

/**
 * Someone's voice on this device: "Mute for me" (a switch row, like the host's settings) and their volume, 0 to 100 %, which is
 * heard as the slider moves. The slider follows the finger while dragging and the host's value otherwise. Nothing here reaches the
 * room. The volume stays set while they are muted, for when they are unmuted.
 */
@Composable
private fun MemberVoicePanel(member: RoomMember, voice: MemberVoice, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var dragging by remember(member.uid) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: voice.volume
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .background(Color(palette.surfaceHigh).copy(alpha = 0.5f))
            .testTag(RoomTags.memberVoice(member.uid)),
    ) {
        KitSettingsRow(
            title = stringResource(Res.string.cosmo_room_mute_for_me),
            summary = stringResource(Res.string.cosmo_room_mute_for_me_summary, member.name),
            icon = RoomIcons.MutedHere,
            onClick = { actions.setMemberMuted(member.uid, !voice.mutedHere) },
            trailing = {
                CosmoSwitch(voice.mutedHere, { actions.setMemberMuted(member.uid, it) }, modifier = Modifier.testTag(RoomTags.memberMute(member.uid)))
            },
        )
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RoomIcons.Volume, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(Res.string.cosmo_room_volume), style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 1, modifier = Modifier.weight(1f))
            // The slider tells a screen reader its own value; the figure is for the eye.
            Text(
                "${roomVolumePercent(shown)}%",
                style = type.meta.tabular(),
                color = Color(palette.textSecondary),
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
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
            modifier = Modifier.padding(horizontal = 10.dp).testTag(RoomTags.memberVolume(member.uid)),
        )
    }
}

/** A small label pill beside a name: Host in the accent's container, You on the quiet surface. */
@Composable
private fun Tag(text: String, strong: Boolean) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Text(
        text,
        style = type.tabLabel,
        color = Color(if (strong) palette.onAccentContainer else palette.textSecondary),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(if (strong) palette.accentContainer else palette.surfaceHigh))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
