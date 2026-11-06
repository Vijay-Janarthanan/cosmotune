package com.cosmotune.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.kit.KitButtonKind
import org.jetbrains.compose.resources.stringResource

/** The tags of the room's entry points elsewhere in the app, for the UI tests and anything a host wants to find. */
object RoomEntryTags {
    const val ENTRY = "room:entry"
    const val ENTRY_COUNT = "room:entry:count"
    const val ENTRY_UNREAD = "room:entry:unread"
    const val PLAY_IN_ROOM = "room:play-in-room"
}

/**
 * The room icon every page's dock shows while a room is joined: the room's glyph with the number of people and an unread dot. One tap
 * opens the room screen at once. [compact] is the size of a bottom tab bar or a dock (the glyph alone in a 48 dp target, the count on
 * its corner); otherwise it is the desktop sidebar's tonal pill with the glyph, "Room" and the count in a row. Draws nothing outside a
 * room.
 */
@Composable
fun RoomEntryButton(launcher: RoomLauncher, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (!launcher.inRoom) return
    val fonts = orbitFonts()
    val look = orbitLook()
    val count = launcher.memberCount.coerceAtLeast(0)
    val unread = launcher.unreadChat > 0
    val label = when {
        count == 1 && unread -> stringResource(Res.string.cosmo_room_entry_label_one_unread)
        count == 1 -> stringResource(Res.string.cosmo_room_entry_label_one)
        unread -> stringResource(Res.string.cosmo_room_entry_label_unread, count)
        else -> stringResource(Res.string.cosmo_room_entry_label, count)
    }
    val interaction = remember { MutableInteractionSource() }
    val ink = look.ink
    // One button to a screen reader, read as one sentence; the badge and the dot inside it are only drawn.
    val button = Modifier
        .minimumInteractiveComponentSize()
        .cosmoFocusRing(interaction, 24.dp)
        .clip(RoundedCornerShape(50))
        .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = launcher::openRoom)
        .semantics(mergeDescendants = true) { contentDescription = label }
        .testTag(RoomEntryTags.ENTRY)
    if (compact) {
        Box(modifier.then(button).size(48.dp), contentAlignment = Alignment.Center) {
            Box {
                Icon(RoomIcons.Room, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                // The count sits on the glyph's top corner, ringed in the ground so it reads over the glyph's line.
                CountBadge(count, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-8).dp))
                if (unread) UnreadDot(Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 3.dp))
            }
        }
    } else {
        Row(
            modifier
                .then(button)
                .heightIn(min = 48.dp)
                .background(look.chip, RoundedCornerShape(50))
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(RoomIcons.Room, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(Res.string.cosmo_room_entry), style = fonts.chip, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            CountBadge(count)
            if (unread) {
                Spacer(Modifier.width(6.dp))
                UnreadDot()
            }
        }
    }
}

/** The number of people: the solid ink of a chosen chip, so it never competes with the accent's unread dot. */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(
        modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .border(2.dp, look.ground, RoundedCornerShape(50))
            .padding(1.dp)
            .clip(RoundedCornerShape(50))
            .background(look.ink)
            .padding(horizontal = 4.dp)
            .testTag(RoomEntryTags.ENTRY_COUNT),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = fonts.label.copy(letterSpacing = 0.sp), color = look.ground, maxLines = 1)
    }
}

@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    val look = orbitLook()
    Box(
        modifier
            .size(10.dp)
            .border(2.dp, look.ground, CircleShape)
            .padding(1.dp)
            .clip(CircleShape)
            .background(look.accent)
            .testTag(RoomEntryTags.ENTRY_UNREAD),
    )
}

/**
 * "Play in room" for [item] on a page elsewhere in the app (a watch page, a station, an episode), through [LocalRoomLauncher]: nothing
 * without a launcher or without an item (a station whose stream is not https has none). In a room it plays the item for everyone; when
 * the host keeps control it is drawn disabled and says why; outside a room it reads "Watch together" or "Listen together" and the
 * host app asks to make or join a room first. An Orbit pill ([kind] primary is the peach one, the rest the quiet chip), [dense] for
 * an action row.
 */
@Composable
fun RoomPlayButton(item: PartyTrack?, modifier: Modifier = Modifier, dense: Boolean = false, kind: KitButtonKind = KitButtonKind.TONAL) {
    val launcher = LocalRoomLauncher.current ?: return
    if (item == null) return
    val label = roomPlayLabel(launcher.inRoom, launcher.canControl, video = item.kind == PartyTrack.KIND_VIDEO)
    val text = stringResource(
        when (label) {
            RoomPlayLabel.PLAY_IN_ROOM -> Res.string.cosmo_room_play_in_room
            RoomPlayLabel.HOST_ONLY -> Res.string.cosmo_room_host_only_start
            RoomPlayLabel.WATCH_TOGETHER -> Res.string.cosmo_room_watch_together
            RoomPlayLabel.LISTEN_TOGETHER -> Res.string.cosmo_room_listen_together
        },
    )
    OrbitPillButton(
        text,
        { launcher.playInRoom(item) },
        orbitFonts(),
        orbitLook(),
        modifier = modifier.testTag(RoomEntryTags.PLAY_IN_ROOM),
        primary = kind == KitButtonKind.PRIMARY,
        icon = RoomIcons.Room,
        enabled = label != RoomPlayLabel.HOST_ONLY,
        height = if (dense) 36.dp else 44.dp,
    )
}
