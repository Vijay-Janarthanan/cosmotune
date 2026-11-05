package com.cosmotune.ui.room

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.podio.PodcastEpisode
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.ui.placeLine
import java.net.URI
import java.security.MessageDigest

/*
 * What "Play in room" hands the room, built on a plain JVM so it is tested without a screen: a Video-mode video, a Podio station and a
 * Podio episode each become a PartyTrack of their own kind, within the room's limits (an id of at most 64 characters, titles of at most
 * 200, links of at most 500 and always https). The platform hosts build the same items through these, so a station started from the
 * desktop and one started from the phone are the same item to everyone.
 */

/** The room's limits on an item, as the database rules hold them. */
const val ROOM_ITEM_ID_MAX = 64
const val ROOM_ITEM_TEXT_MAX = 200
const val ROOM_ITEM_URL_MAX = 500

/** The prefixes Podio's own ids carry, kept on the room's id so a device can hand the item straight back to its Podio player. */
const val ROOM_RADIO_PREFIX = "podio:radio:"
const val ROOM_EPISODE_PREFIX = "podio:episode:"

/**
 * [raw] as a room id: itself when it fits in [ROOM_ITEM_ID_MAX], otherwise [prefix] (when [raw] starts with it) and the SHA-256 of
 * the rest in hex, cut to fit. The same input always gives the same id, so two devices agree on it without talking.
 */
fun roomItemId(raw: String, prefix: String = ""): String {
    if (raw.length <= ROOM_ITEM_ID_MAX) return raw
    val head = if (prefix.isNotEmpty() && raw.startsWith(prefix) && prefix.length < ROOM_ITEM_ID_MAX - 16) prefix else ""
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.removePrefix(head).toByteArray()).joinToString("") { "%02x".format(it) }
    return head + digest.take(ROOM_ITEM_ID_MAX - head.length)
}

/**
 * [raw] as a link another member's device may fetch: an https address with a host, at most [ROOM_ITEM_URL_MAX] long, or null. A
 * link is never cut short (a shortened address points somewhere else); a plain-http stream is refused, which hides the button.
 */
fun roomHttpsUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.length > ROOM_ITEM_URL_MAX || !value.regionMatches(0, "https://", 0, 8, ignoreCase = true)) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return "https://" + value.substring(8)
}

/** Artwork the room can carry: an http(s) address that fits, else none (the others draw the quiet surface). */
private fun roomArtwork(raw: String?): String? = raw?.trim()?.takeIf {
    it.length <= ROOM_ITEM_URL_MAX && (it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true))
}

private fun String.fit() = trim().take(ROOM_ITEM_TEXT_MAX)

/**
 * A Video-mode video for the room: [videoId] is the YouTube video id, [channel] the channel's name. Null without an id. A live stream
 * or an unknown length passes null for [durationMs].
 */
fun roomVideoItem(videoId: String, title: String, channel: String, thumbnailUrl: String?, durationMs: Long?): PartyTrack? {
    val id = videoId.trim().takeIf { it.isNotEmpty() } ?: return null
    return PartyTrack(
        videoId = roomItemId(id),
        title = title.fit(),
        artist = channel.fit(),
        thumbnailUrl = roomArtwork(thumbnailUrl),
        durationMs = durationMs?.takeIf { it > 0L },
        kind = PartyTrack.KIND_VIDEO,
    )
}

/**
 * A live station for the room: [stationId] is the station's stable id (Radio Browser's uuid), [place] the city and country line, [streamUrl]
 * the stream. Null when the stream is not https, so no button offers a station the other devices would refuse.
 */
fun roomStationItem(stationId: String, name: String, place: String, streamUrl: String?, artworkUrl: String?): PartyTrack? {
    val id = stationId.trim().takeIf { it.isNotEmpty() } ?: return null
    val url = roomHttpsUrl(streamUrl) ?: return null
    val full = if (id.startsWith(ROOM_RADIO_PREFIX)) id else ROOM_RADIO_PREFIX + id
    return PartyTrack(
        videoId = roomItemId(full, ROOM_RADIO_PREFIX),
        title = name.fit(),
        artist = place.fit(),
        thumbnailUrl = roomArtwork(artworkUrl),
        durationMs = null,
        kind = PartyTrack.KIND_RADIO,
        url = url,
    )
}

/**
 * A podcast episode for the room: [episodeId] is the episode's stable id (Podio's, from the feed's guid; hashed when longer than the
 * room takes), [show] the show's name, [enclosureUrl] the audio. Null when the audio is not https.
 */
fun roomEpisodeItem(episodeId: String, title: String, show: String, enclosureUrl: String?, artworkUrl: String?, durationMs: Long?): PartyTrack? {
    val id = episodeId.trim().takeIf { it.isNotEmpty() } ?: return null
    val url = roomHttpsUrl(enclosureUrl) ?: return null
    val full = if (id.startsWith(ROOM_EPISODE_PREFIX)) id else ROOM_EPISODE_PREFIX + id
    return PartyTrack(
        videoId = roomItemId(full, ROOM_EPISODE_PREFIX),
        title = title.fit(),
        artist = show.fit(),
        thumbnailUrl = roomArtwork(artworkUrl),
        durationMs = durationMs?.takeIf { it > 0L },
        kind = PartyTrack.KIND_PODCAST,
        url = url,
    )
}

/** A Podio station as a room item. */
fun RadioStation.roomItem(): PartyTrack? = roomStationItem(id, name, placeLine().ifBlank { country }, url, artwork)

/** A Podio episode as a room item; [artworkFallback] is the show's art for an episode with none of its own. */
fun PodcastEpisode.roomItem(artworkFallback: String? = null): PartyTrack? =
    roomEpisodeItem(id, title, show, url, artwork.ifBlank { artworkFallback.orEmpty() }, durationSeconds.takeIf { it > 0L }?.times(1000L))

/**
 * Whatever Podio is playing, as a room item: a live item is a station, anything else an episode. Its id already carries Podio's prefix,
 * which the room keeps, so the device that receives it can make the same [PodioItem] back from the item's videoId, title, artist and url.
 */
fun PodioItem.roomItem(): PartyTrack? =
    if (live) roomStationItem(id, title, author, url, artwork)
    else roomEpisodeItem(id, title, author, url, artwork, durationSeconds.takeIf { it > 0L }?.times(1000L))

/** What a "Play in room" button says, from where this person stands. */
enum class RoomPlayLabel {
    /** In a room and allowed to start things: the item plays for everyone now. */
    PLAY_IN_ROOM,

    /** In a room the host keeps to themselves: the button is drawn disabled and says why. */
    HOST_ONLY,

    /** Not in a room: the host app opens the make-or-join sheet first. A video says watch, the rest listen. */
    WATCH_TOGETHER,
    LISTEN_TOGETHER,
}

/** The label for a launcher's state; [video] picks "Watch together" over "Listen together" outside a room. */
fun roomPlayLabel(inRoom: Boolean, canControl: Boolean, video: Boolean): RoomPlayLabel = when {
    inRoom && canControl -> RoomPlayLabel.PLAY_IN_ROOM
    inRoom -> RoomPlayLabel.HOST_ONLY
    video -> RoomPlayLabel.WATCH_TOGETHER
    else -> RoomPlayLabel.LISTEN_TOGETHER
}
