package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.unit.dp
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource

/**
 * The bar under every page (design section 7): deafen at the start, the big microphone in the middle, Leave at the end. Everyone
 * joins muted, and the mic is one tap from live with no host involved.
 *
 * Until voice calls exist ([RoomUiState.voiceAvailable] false) the mic is still there, so the bar does not change shape the day voice
 * ships, but a tap only explains that voice is coming: it never calls [RoomActions.setMicLive] and never shows a live state, so
 * nobody believes the room can hear them. When the permission was refused for good ([RoomUiState.micBlocked]) the mic looks
 * switched off and a tap opens the system settings instead, with a line above the bar saying why. The mode comes from [roomMicMode].
 */
@Composable
internal fun RoomVoiceBar(state: RoomUiState, actions: RoomActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var explaining by remember { mutableStateOf(false) }
    val mode = roomMicMode(state)
    Column(Modifier.fillMaxWidth()) {
        if (mode == RoomMicMode.BLOCKED) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CosmoSpacing.margin, vertical = 4.dp)
                    .clip(RoundedCornerShape(CosmoRadii.rMd))
                    .background(Color(palette.surface))
                    .padding(start = 14.dp, end = 4.dp)
                    .testTag(RoomTags.MIC_BLOCKED)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.cosmo_room_mic_blocked_note),
                    style = type.meta,
                    color = Color(palette.textPrimary),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                KitTextButton(stringResource(Res.string.cosmo_room_open_settings), actions::openMicSettings, Modifier.testTag(RoomTags.MIC_SETTINGS))
            }
        }
        if (explaining && mode == RoomMicMode.UNAVAILABLE) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CosmoSpacing.margin, vertical = 4.dp)
                    .clip(RoundedCornerShape(CosmoRadii.rMd))
                    .background(Color(palette.surface))
                    .padding(start = 14.dp, end = 4.dp)
                    .testTag(RoomTags.VOICE_NOTE)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.cosmo_room_voice_coming),
                    style = type.meta,
                    color = Color(palette.textPrimary),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                CosmoIconButton(CosmoIcons.Close, stringResource(Res.string.cosmo_room_dismiss), { explaining = false }, size = 40.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(palette.textPrimary).copy(alpha = 0.08f)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                CosmoIconButton(
                    if (state.deafened) RoomIcons.HeadphonesOff else RoomIcons.Headphones,
                    stringResource(if (state.deafened) Res.string.cosmo_room_undeafen else Res.string.cosmo_room_deafen),
                    { actions.setDeafened(!state.deafened) },
                    on = state.deafened,
                    modifier = Modifier.testTag(RoomTags.DEAFEN),
                )
            }
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
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                KitButton(
                    stringResource(Res.string.cosmo_room_leave),
                    actions::leave,
                    kind = KitButtonKind.QUIET,
                    icon = RoomIcons.Leave,
                    dense = true,
                    modifier = Modifier.testTag(RoomTags.LEAVE),
                )
            }
        }
    }
}

/**
 * The microphone: a 64 dp circle, the accent when live and the quiet `surfaceHigh` otherwise, with its state written under it so it
 * is not told by colour alone ("Mic on", "Muted", "Blocked", "Voice soon"). A screen reader hears the state and what a tap will do
 * ("Microphone muted, tap to unmute"). While live and speaking, a small three-bar meter of [meterLevel] sits on the circle's corner.
 */
@Composable
private fun MicButton(mode: RoomMicMode, meterLevel: Float?, onClick: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val live = mode == RoomMicMode.LIVE
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
    Column(
        Modifier
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button) { haptics.play(Haptic.Select); onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = label
            }
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(RoomTags.MIC),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(MIC_DP.dp)) {
            Box(
                Modifier
                    .size(MIC_DP.dp)
                    .cosmoFocusRing(interaction, (MIC_DP / 2).dp)
                    .clip(CircleShape)
                    .background(Color(if (live) palette.accent else palette.surfaceHigh)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (live) RoomIcons.Mic else RoomIcons.MicOff,
                    contentDescription = null,
                    tint = Color(
                        when (mode) {
                            RoomMicMode.LIVE -> palette.onAccent
                            RoomMicMode.MUTED -> palette.textPrimary
                            else -> palette.textTertiary
                        },
                    ),
                    modifier = Modifier.size(28.dp),
                )
            }
            if (meterLevel != null) MicMeter(meterLevel, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(2.dp))
        // Already said by the button's state description; read once, not twice.
        Text(
            label,
            style = type.caption,
            color = Color(if (live) palette.accentStrong else palette.textSecondary),
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * Three short bars on a `surface` disc ringed in the ground colour, lit in the accent from the left as [level] rises
 * ([roomMicMeterBars]). It follows the level the host feeds; nothing animates on its own, so reduced motion needs nothing more.
 * Decorative: "Mic on" already says the room hears you.
 */
@Composable
private fun MicMeter(level: Float, modifier: Modifier) {
    val palette = LocalCosmoColors.current.palette
    val lit = roomMicMeterBars(level)
    Row(
        modifier
            .size(METER_DP.dp)
            .clip(CircleShape)
            .background(Color(palette.surface))
            .border(2.dp, Color(palette.backgroundBottom), CircleShape)
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
                    .background(if (bar < lit) Color(palette.accent) else Color(palette.textPrimary).copy(alpha = 0.18f)),
            )
        }
    }
}

private const val MIC_DP = 64
private const val METER_DP = 24
private val METER_BAR_DP = intArrayOf(6, 10, 8)
