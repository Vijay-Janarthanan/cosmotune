package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitNotice
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitTalkButton
import com.cosmotune.ui.orbit.orbitActionFill
import com.cosmotune.ui.orbit.orbitActionInk
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitTalking
import org.jetbrains.compose.resources.stringResource

/**
 * The voice row under the pages in Orbit (board batch-a, section 3): the big round mic ("Tap to talk", peach) with its two lines, and
 * the voices chip at the end (every voice on or silenced on this device). Everyone joins muted, and the mic is one tap from live
 * with no host involved.
 *
 * Until voice calls exist ([RoomUiState.voiceAvailable] false) the mic is still there, so the row does not change shape the day voice
 * ships, but a tap only explains that voice is coming: it never calls [RoomActions.setMicLive] and never shows a live state, so
 * nobody believes the room can hear them. When the permission was refused for good ([RoomUiState.micBlocked]) the mic looks
 * switched off and a tap opens the system settings instead, with a notice above the row saying why. The mode comes from [roomMicMode].
 */
@Composable
internal fun RoomVoiceBar(state: RoomUiState, actions: RoomActions, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var explaining by remember { mutableStateOf(false) }
    val mode = roomMicMode(state)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mode == RoomMicMode.BLOCKED) {
            OrbitNotice(stringResource(Res.string.cosmo_room_mic_blocked_note), fonts, look, Modifier.fillMaxWidth().testTag(RoomTags.MIC_BLOCKED), icon = RoomIcons.MicOff) {
                OrbitPillButton(stringResource(Res.string.cosmo_room_open_settings), actions::openMicSettings, fonts, look, Modifier.testTag(RoomTags.MIC_SETTINGS), height = 36.dp)
            }
        }
        if (explaining && mode == RoomMicMode.UNAVAILABLE) {
            OrbitNotice(stringResource(Res.string.cosmo_room_voice_coming), fonts, look, Modifier.fillMaxWidth().testTag(RoomTags.VOICE_NOTE)) {
                OrbitRoundButton(CosmoIcons.Close, stringResource(Res.string.cosmo_room_dismiss), look.ink, { explaining = false }, size = 36.dp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MicButton(
                mode = mode,
                // Your own level shows only while the room hears you speaking; the host puts you in speakingUids then.
                meterLevel = if (mode == RoomMicMode.LIVE && state.selfUid in state.speakingUids) state.micLevel else null,
                onClick = {
                    when (mode) {
                        RoomMicMode.UNAVAILABLE -> explaining = true
                        RoomMicMode.BLOCKED -> actions.openMicSettings()
                        RoomMicMode.MUTED -> actions.setMicLive(true)
                        RoomMicMode.LIVE -> actions.setMicLive(false)
                    }
                },
                fonts = fonts,
                look = look,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            val voicesLabel = stringResource(if (state.deafened) Res.string.cosmo_room_undeafen else Res.string.cosmo_room_deafen)
            OrbitChip(
                if (state.deafened) "Voices off" else "Voices on", fonts, look,
                onClick = { actions.setDeafened(!state.deafened) },
                modifier = Modifier.testTag(RoomTags.DEAFEN).semantics { contentDescription = voicesLabel },
                selected = state.deafened,
                leading = { Icon(if (state.deafened) RoomIcons.HeadphonesOff else RoomIcons.Volume, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * The microphone: a 64 dp disc and its two lines in one button. Peach while muted ("Tap to talk": it is the one thing to do here),
 * green while live, the quiet chip while voice is not offered or the mic is blocked. Its state is written beside it so it is not told
 * by colour alone, and a screen reader hears the state and what a tap will do ("Microphone muted, tap to unmute"). While live and
 * speaking, a small three-bar meter of [meterLevel] sits on the disc's corner.
 */
@Composable
private fun MicButton(mode: RoomMicMode, meterLevel: Float?, onClick: () -> Unit, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val description = stringResource(
        when (mode) {
            RoomMicMode.UNAVAILABLE -> Res.string.cosmo_room_mic_soon_action
            RoomMicMode.BLOCKED -> Res.string.cosmo_room_mic_blocked_action
            RoomMicMode.LIVE -> Res.string.cosmo_room_mic_live_action
            RoomMicMode.MUTED -> Res.string.cosmo_room_mic_muted_action
        },
    )
    val label = stringResource(
        when (mode) {
            RoomMicMode.UNAVAILABLE -> Res.string.cosmo_room_mic_soon
            RoomMicMode.BLOCKED -> Res.string.cosmo_room_mic_blocked
            RoomMicMode.LIVE -> Res.string.cosmo_room_mic_live
            RoomMicMode.MUTED -> Res.string.cosmo_room_mic_muted
        },
    )
    val (title, subtitle) = when (mode) {
        RoomMicMode.MUTED -> "Tap to talk" to "You join muted · your mic is off"
        RoomMicMode.LIVE -> "You are talking" to "The room hears you · tap to mute"
        RoomMicMode.UNAVAILABLE -> "Voice soon" to "Talk to the room in the chat for now"
        RoomMicMode.BLOCKED -> "Microphone blocked" to "Allow it in the system settings"
    }
    val fill = when (mode) {
        RoomMicMode.MUTED -> orbitActionFill(look)
        RoomMicMode.LIVE -> orbitTalking(look)
        else -> look.chip
    }
    val iconInk = when (mode) {
        RoomMicMode.MUTED -> orbitActionInk(look)
        RoomMicMode.LIVE -> look.ground
        else -> look.dim
    }
    OrbitTalkButton(
        icon = if (mode == RoomMicMode.LIVE || mode == RoomMicMode.MUTED) RoomIcons.Mic else RoomIcons.MicOff,
        fill = fill,
        iconInk = iconInk,
        title = title,
        subtitle = subtitle,
        fonts = fonts,
        look = look,
        titleInk = if (mode == RoomMicMode.LIVE) orbitTalking(look) else look.ink,
        badge = if (meterLevel != null) ({ MicMeter(meterLevel, look) }) else null,
        modifier = modifier
            .cosmoFocusRing(interaction, 32.dp)
            .clip(RoundedCornerShape(32.dp))
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) { haptics.play(Haptic.Select); onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = label
            }
            .testTag(RoomTags.MIC),
    )
}

/**
 * Three short bars on a disc of the ground, lit in the green from the left as [level] rises ([roomMicMeterBars]). It follows the
 * level the host feeds; nothing animates on its own, so reduced motion needs nothing more. Decorative: the state already says the
 * room hears you.
 */
@Composable
private fun BoxScope.MicMeter(level: Float, look: OrbitLook) {
    val lit = roomMicMeterBars(level)
    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .size(METER_DP.dp)
            .clip(CircleShape)
            .background(look.ground)
            .border(2.dp, orbitTalking(look), CircleShape)
            .clearAndSetSemantics { testTagProperty = RoomTags.MIC_METER },
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (bar in 0..2) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(METER_BAR_DP[bar].dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (bar < lit) orbitTalking(look) else look.ink.copy(alpha = 0.18f)),
            )
        }
    }
}

private const val METER_DP = 24
private val METER_BAR_DP = intArrayOf(6, 10, 8)
