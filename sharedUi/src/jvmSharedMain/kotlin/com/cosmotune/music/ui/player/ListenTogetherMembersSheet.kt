package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.orbit.OrbitOptionRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState

/**
 * Who's in the party right now, from the same button that used to drop
 * straight into Settings.
 *
 * A [PlayerDrawer] rather than the settings page itself: the settings page
 * (party size, server, activity log) is a lot to land on for a listener who
 * only wanted to see who else is here. [onManage] is the escape hatch to that
 * page for the host actions this drawer doesn't have room for — resizing the
 * party, switching servers, leaving.
 */
@Composable
internal fun ListenTogetherMembersSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by PlayerPlatform.host.party.collectAsStateWithLifecycle()

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.listen_together),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.members.forEach { member ->
                MemberDrawerRow(member = member, isYou = member.memberId == state.you?.memberId)
            }
        }

        Spacer(Modifier.height(12.dp))
        ManageRow(code = state.code, onClick = onManage)
    }
}

/** One member: their picture as a disc (their initial on a quiet one without), their name and their part. Checked for the one that's you. */
@Composable
private fun MemberDrawerRow(member: PartyMember, isYou: Boolean) {
    val look = orbitLook()
    val fonts = orbitFonts()
    // "Away" rather than "offline": the slot is still theirs, and the
    // server holds it through a grace period precisely so a tunnel or
    // a locked screen does not read to everyone else as leaving.
    val subtitle = when {
        isYou && member.isHost -> stringResource(Res.string.listen_together_host)
        isYou -> stringResource(Res.string.listen_together_you)
        !member.connected -> stringResource(Res.string.listen_together_away)
        member.isHost -> stringResource(Res.string.listen_together_host)
        else -> null
    }
    OrbitOptionRow(
        title = member.displayName,
        subtitle = subtitle,
        icon = null,
        onClick = null,
        checked = isYou,
        discContent = {
            Box(Modifier.size(40.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                if (member.avatarUrl != null) {
                    AsyncImage(
                        model = member.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                    )
                } else {
                    Text(text = member.displayName.take(1).uppercase(), style = fonts.title, color = look.ink)
                }
            }
        },
    )
}

/** Drills into the full Listen Together settings page — party size, server, leaving. */
@Composable
private fun ManageRow(code: String?, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    OrbitOptionRow(
        title = stringResource(Res.string.listen_together_manage),
        subtitle = code?.let { stringResource(Res.string.listen_together_in_party, it) },
        icon = Icons.Rounded.Settings,
        onClick = {
            haptics.play(Haptic.Select)
            onClick()
        },
        chevron = true,
    )
}
