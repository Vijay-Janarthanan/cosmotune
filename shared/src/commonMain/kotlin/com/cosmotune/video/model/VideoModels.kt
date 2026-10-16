package com.cosmotune.video.model

import com.cosmotune.video.design.VideoMetrics
import kotlin.jvm.JvmInline

/**
 * One video as lists show it (spec 4.2, 5.0): a card, a row, a Shorts card. Our own model: no NewPipe type crosses the
 * `source` boundary. [durationSec] is null for live streams and feed items (RSS has no duration, spec 8.3);
 * [uploadedText] is the source's own relative text when it gives no time. [isShort] is set when the source marks the item
 * as a Short (spec 7.4).
 */
data class VideoItem(
    val id: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val channelAvatarUrl: String?,
    val thumbnailUrl: String?,
    val durationSec: Long?,
    val viewCount: Long?,
    val uploadedAtMs: Long?,
    val uploadedText: String?,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val isShort: Boolean = false,
) {
    /**
     * The watched bar's fraction (spec 5.0: "shown from 10 s watched"): [positionMs] over [durationMs] clamped to 0..1, or
     * null when there is no bar to draw (less than [VideoMetrics.WATCHED_MIN_MS] watched, or no known duration). A
     * [durationMs] of 0 or less falls back to the item's own [durationSec]. The value goes straight to the blocks'
     * `watchedFraction` parameter.
     */
    fun watchedFraction(positionMs: Long, durationMs: Long): Float? {
        if (positionMs < VideoMetrics.WATCHED_MIN_MS) return null
        val total = effectiveDurationMs(durationMs) ?: return null
        return (positionMs.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** [durationMs] when positive, else the item's own duration in milliseconds, else null. */
    internal fun effectiveDurationMs(durationMs: Long): Long? = when {
        durationMs > 0 -> durationMs
        durationSec != null && durationSec > 0 -> durationSec * 1000
        else -> null
    }
}

/** A channel as search results, rows and the channel header show it (spec 5.3, 5.5). [handle] is the "@name" form. */
data class ChannelItem(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val subscriberCount: Long?,
    val videoCount: Long?,
    val description: String?,
    val verified: Boolean = false,
    val handle: String? = null,
)

/** A remote playlist as rows and the playlist page show it (spec 5.6). */
data class PlaylistItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val ownerName: String?,
    val ownerId: String?,
    val videoCount: Long?,
)

/** One entry of a mixed list: search results, a channel tab, the related videos of a watch page (spec 5.3, 5.4, 5.5). */
sealed interface ListItem {
    /** A video (or a Short, `item.isShort`). */
    data class Video(val item: VideoItem) : ListItem

    /** A channel. */
    data class Channel(val item: ChannelItem) : ListItem

    /** A playlist. */
    data class Playlist(val item: PlaylistItem) : ListItem
}

/** The next page of a list (spec 4.2): opaque; the source adapter encodes its own paging in [value]. */
@JvmInline
value class PageToken(val value: String)

/** One page of a list and the token of the next one, or null at the end (spec 4.2). */
data class Page<T>(val items: List<T>, val next: PageToken?)

/**
 * One of the source's charts (spec 5.2): ids "music", "gaming", "movies", "podcasts", "live"; [name] is the name the
 * source gives. The removed "Trending" kiosk is never offered.
 */
data class Kiosk(val id: String, val name: String)

/**
 * The search filters (spec 5.3). The source has no Shorts filter: for [SHORTS] the adapter keeps the Short items of an
 * [ALL] search, reading up to three pages to fill the first screen.
 */
enum class SearchFilter { ALL, VIDEOS, SHORTS, CHANNELS, PLAYLISTS }

/** The tabs of a channel page (spec 5.5); About is drawn from [ChannelPage] and needs no tab. */
enum class ChannelTab { VIDEOS, SHORTS, LIVE, PLAYLISTS }

/** A channel's header (spec 5.5): the channel, its banner, the tabs it has, its join date as text and its links. */
data class ChannelPage(
    val channel: ChannelItem,
    val bannerUrl: String?,
    val tabs: List<ChannelTab>,
    val joinedText: String?,
    val links: List<String>,
)

/** A playlist page (spec 5.6): the playlist, its description and the first page of its videos. */
data class PlaylistPage(val playlist: PlaylistItem, val description: String?, val first: Page<VideoItem>)

/** What a stream is (spec 5.13): a video, a live stream, a finished live stream, or audio only. */
enum class StreamKind { VIDEO, LIVE, POST_LIVE, AUDIO }

/**
 * A tappable span of a description (spec 5.4: "full text with tappable timestamps and links"): characters [start] until
 * [end] of the text, either an address ([url]) or a moment of the video ([timestampMs]).
 */
data class DescriptionLink(val start: Int, val end: Int, val url: String?, val timestampMs: Long?)

/** A video's description as plain text with its links and timestamps (spec 5.4). */
data class Description(val text: String, val links: List<DescriptionLink>)

/** A chapter of a video (spec 5.4, 6.5: the 2 dp gaps on the scrubber, the rows in the description sheet). */
data class Chapter(val title: String, val startMs: Long, val thumbnailUrl: String?)

/**
 * The scrubbing previews (spec 6.5: the 160 x 90 frame in the bubble): sheet images of [columns] x [rows] frames of
 * [frameWidth] x [frameHeight] pixels, one frame every [intervalMs], [totalFrames] in all.
 */
data class Storyboard(
    val urls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
    val intervalMs: Long,
    val totalFrames: Int,
)

/** A subtitle track (spec 5.4, 6.5, 5.16): its address and format, its language tag and name, and whether it was made by a machine. */
data class SubtitleTrack(val url: String, val mimeType: String, val language: String, val name: String, val autoGenerated: Boolean)

/**
 * Everything the watch page shows of one video (spec 4.2, 5.4): metadata, chapters, related, subtitles, storyboard and
 * the streams. [fetchedAtMs] is when the source answered (the controller caches details 10 minutes, spec 6.1).
 */
data class VideoDetails(
    val item: VideoItem,
    val description: Description,
    val likeCount: Long?,
    val category: String?,
    val tags: List<String>,
    val chapters: List<Chapter>,
    val related: List<ListItem>,
    val subtitles: List<SubtitleTrack>,
    val storyboard: Storyboard?,
    val kind: StreamKind,
    val streams: StreamSet,
    val subscriberCount: Long?,
    val fetchedAtMs: Long,
)
