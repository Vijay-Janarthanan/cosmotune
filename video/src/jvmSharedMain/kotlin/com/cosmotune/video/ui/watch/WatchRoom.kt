package com.cosmotune.video.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.sharedui.resources.cosmo_room_host_only_start
import com.cosmotune.music.sharedui.resources.cosmo_room_listen_together
import com.cosmotune.music.sharedui.resources.cosmo_room_play_in_room
import com.cosmotune.music.sharedui.resources.cosmo_room_watch_together
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.room.LocalRoomLauncher
import com.cosmotune.ui.room.RoomEntryTags
import com.cosmotune.ui.room.RoomPlayLabel
import com.cosmotune.ui.room.roomPlayLabel
import com.cosmotune.ui.room.roomVideoItem
import com.cosmotune.video.model.VideoItem
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/** The room's track for [item], under its original title (the others in the room may not use DeArrow's). */
@Composable
internal fun rememberRoomItem(item: VideoItem): PartyTrack? = remember(item.id, item.title, item.channelName, item.thumbnailUrl, item.durationSec) {
    roomVideoItem(item.id, item.title, item.channelName.orEmpty(), item.thumbnailUrl, item.durationSec?.times(1000L))
}

/**
 * "Watch together" as a chip on a colour card (Orbit board B): the video for everyone in the room, each device playing it in its own
 * player, through [LocalRoomLauncher]. In a room it reads "Play in room" (drawn faded when the host keeps the controls); outside one
 * the host app asks to make or join a room first. Nothing where the host offers no rooms. [ink] is the card's.
 */
@Composable
internal fun VideoRoomChip(item: VideoItem, ink: Color, fonts: OrbitFonts, modifier: Modifier = Modifier, solid: Boolean = false, onSolid: Color = Color(0xFF1B1416)) {
    val launcher = LocalRoomLauncher.current ?: return
    val track = rememberRoomItem(item) ?: return
    val label = roomPlayLabel(launcher.inRoom, launcher.canControl, video = true)
    val text = stringResource(
        when (label) {
            RoomPlayLabel.PLAY_IN_ROOM -> SharedRes.string.cosmo_room_play_in_room
            RoomPlayLabel.HOST_ONLY -> SharedRes.string.cosmo_room_host_only_start
            RoomPlayLabel.WATCH_TOGETHER -> SharedRes.string.cosmo_room_watch_together
            RoomPlayLabel.LISTEN_TOGETHER -> SharedRes.string.cosmo_room_listen_together
        },
    )
    WatchCardChip(
        text, ink, fonts, { launcher.playInRoom(track) }, modifier.testTag(RoomEntryTags.PLAY_IN_ROOM),
        icon = CosmoIcons.Together, enabled = label != RoomPlayLabel.HOST_ONLY, solid = solid, onSolid = onSolid,
    )
}
