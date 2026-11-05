package com.cosmotune.music.playback

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.podio.YouTubePodcasts
import com.cosmotune.ui.room.roomItemId

/**
 * Which of this phone's players plays a room item, and what it is called there, as plain functions. A room plays songs, Video-mode
 * videos, Podio stations and Podio episodes; each device plays the item in its own player and nothing is streamed between them.
 *
 * Podio plays through Music's service (the episode and station players are the music player with a `podio:` item), so songs,
 * stations and episodes share one player and [PartySync] follows them all; a video is Video mode's player, followed by
 * Video's phone host. Whichever player the room item is not for stops while it plays.
 */
internal object RoomKinds {

    enum class Player { MUSIC, VIDEO }

    /** The player that plays [track] here. A kind this version does not know is the music player's, which then sits it out. */
    fun playerOf(track: PartyTrack): Player = if (track.kind == PartyTrack.KIND_VIDEO) Player.VIDEO else Player.MUSIC

    fun onMusicPlayer(track: PartyTrack): Boolean = playerOf(track) == Player.MUSIC

    /**
     * The id the item has on its player: a song or a video by its catalogue id, a station or an episode by the `podio:` id the Podio
     * player gives it. A sender that put only the bare id in [PartyTrack.videoId] is given the prefix here.
     */
    fun mediaIdOf(track: PartyTrack): String = when (track.kind) {
        PartyTrack.KIND_RADIO -> track.videoId.takeIf { it.startsWith(PODIO) } ?: "$RADIO${track.videoId}"
        PartyTrack.KIND_PODCAST -> track.videoId.takeIf { it.startsWith(PODIO) } ?: "$EPISODE${track.videoId}"
        else -> track.videoId
    }

    /**
     * Whether this phone can play [track] at all, before trying: a kind it knows; for a station or an episode an https address (a
     * YouTube-hosted episode is found by its id alone); for a video, Video mode switched on ([videoAvailable]).
     */
    fun playableHere(track: PartyTrack, videoAvailable: Boolean): Boolean = when (track.kind) {
        PartyTrack.KIND_MUSIC -> true
        PartyTrack.KIND_VIDEO -> videoAvailable
        PartyTrack.KIND_RADIO -> isHttps(track.url)
        PartyTrack.KIND_PODCAST -> isHttps(track.url) || YouTubePodcasts.videoId(mediaIdOf(track)) != null
        else -> false
    }

    fun isHttps(url: String?): Boolean = url != null && url.length > HTTPS.length && url.regionMatches(0, HTTPS, 0, HTTPS.length, ignoreCase = true)

    /**
     * The id a player item goes by in the room: a station's or an episode's Podio id, hashed to fit when it is longer than the room
     * takes ([roomItemId], the same rule "Play in room" uses), so a device compares its own item with the room's on equal terms.
     */
    fun roomIdOf(mediaId: String): String = when (kindOfMediaId(mediaId)) {
        PartyTrack.KIND_RADIO -> roomItemId(mediaId, RADIO)
        PartyTrack.KIND_PODCAST -> roomItemId(mediaId, EPISODE)
        else -> mediaId
    }

    /** The kind a player item is, by the id it was given: a song unless it is one of Podio's. */
    fun kindOfMediaId(mediaId: String): String = when {
        mediaId.startsWith(RADIO) -> PartyTrack.KIND_RADIO
        mediaId.startsWith(EPISODE) -> PartyTrack.KIND_PODCAST
        else -> PartyTrack.KIND_MUSIC
    }

    /**
     * The part of the room's running order the music player can hold: the unbroken run of music-player items around [index], and
     * where [index] is in it. A video in the queue is Video mode's, so the music player's queue stops before it, and reaching the
     * end of the run is when the room is moved on (see [PartyFollow.Step.Ended]). An index outside [items] gives an empty run.
     */
    fun musicRun(items: List<PartyTrack>, index: Int): Pair<List<PartyTrack>, Int> {
        if (index !in items.indices || !onMusicPlayer(items[index])) return emptyList<PartyTrack>() to -1
        var start = index
        while (start > 0 && onMusicPlayer(items[start - 1])) start--
        var end = index
        while (end < items.lastIndex && onMusicPlayer(items[end + 1])) end++
        return items.subList(start, end + 1) to (index - start)
    }

    /** Whether the running order holds anything the music player cannot: its own queue is then only a part of the room's. */
    fun mixed(items: List<PartyTrack>): Boolean = items.any { !onMusicPlayer(it) }

    private const val PODIO = "podio:"
    private const val RADIO = "podio:radio:"
    private const val EPISODE = "podio:episode:"
    private const val HTTPS = "https://"
}
