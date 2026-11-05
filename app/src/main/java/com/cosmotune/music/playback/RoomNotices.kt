package com.cosmotune.music.playback

import com.cosmotune.music.data.listentogether.PartyTrack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the room's players tell the person: an item this device cannot play (a station with no https address, a video with Video mode
 * off, a kind this version does not know) is sat out quietly, and named once. The followers live outside the screen ([PartySync] in
 * the music service), so they say it here and the phone's root shows it.
 */
object RoomNotices {
    private val _skipped = MutableSharedFlow<PartyTrack>(extraBufferCapacity = 4)

    /** The items sat out, one each; the device rejoins the room on the next item. */
    val skipped: SharedFlow<PartyTrack> = _skipped.asSharedFlow()

    fun skipped(track: PartyTrack) {
        _skipped.tryEmit(track)
    }
}
