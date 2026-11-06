package com.cosmotune.ui.watch

import androidx.compose.runtime.staticCompositionLocalOf
import com.cosmotune.room.RoomState
import com.cosmotune.watch.WatchDownloads
import com.cosmotune.watch.WatchRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/*
 * The smaller things the app hosts hand Watch's screens beyond the repository and the player: the downloads, sharing add-on
 * packs in a room's chat, and (on the desktop) making Cosmotune the app that opens install links. Each is null where the host
 * has none, and the screens then leave its part out.
 */

/** Watch's downloads on this device; null hides the download buttons and the Downloads page says so. */
val LocalWatchDownloads = staticCompositionLocalOf<WatchDownloads?> { null }

/** An add-on pack a member offered in the room's chat ([WatchRoom.packOffer]). */
data class WatchPackOffer(val id: String, val from: String, val link: String, val mine: Boolean)

/** The joined room as the Plugins page sees it: whether there is one, the packs offered in its chat, and sending one. */
class WatchRoomPacks(
    val inRoom: StateFlow<Boolean>,
    val offers: StateFlow<List<WatchPackOffer>>,
    /** This member's name in the room, for "Vijay shares their Watch add-ons". */
    val selfName: () -> String,
    val send: (String) -> Unit,
) {
    companion object {
        /** Reads the packs out of a room's state ([RoomState.chat]), the newest first; one per sender and link. */
        fun of(room: StateFlow<RoomState?>, selfUid: () -> String, send: (String) -> Unit, scope: CoroutineScope): WatchRoomPacks {
            val inRoom = room.map { it != null }.stateIn(scope, SharingStarted.Eagerly, room.value != null)
            val offers = room.map { offersIn(it, selfUid()) }.stateIn(scope, SharingStarted.Eagerly, offersIn(room.value, selfUid()))
            val name = { room.value?.members?.firstOrNull { it.uid == selfUid() }?.name?.takeIf { it.isNotBlank() } ?: "A member" }
            return WatchRoomPacks(inRoom, offers, name, send)
        }

        fun offersIn(room: RoomState?, self: String): List<WatchPackOffer> = room?.chat.orEmpty().asReversed().mapNotNull { m ->
            WatchRoom.packIn(m.text)?.let { WatchPackOffer(m.id, m.name.ifBlank { "A member" }, it, m.uid == self) }
        }.distinctBy { it.from to it.link }
    }
}

val LocalWatchRoomPacks = staticCompositionLocalOf<WatchRoomPacks?> { null }

/**
 * Making Cosmotune the app that opens `stremio://` and `cosmotune://watch` links (the desktop on Windows and Linux): [isSet] reads
 * whether it already is, [set] makes it so when the person presses the button and returns the problem, or null.
 */
class WatchLinkHandler(val isSet: () -> Boolean, val set: () -> String?)

val LocalWatchLinkHandler = staticCompositionLocalOf<WatchLinkHandler?> { null }
