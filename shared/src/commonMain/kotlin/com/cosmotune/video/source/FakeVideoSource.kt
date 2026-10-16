package com.cosmotune.video.source

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.DescriptionLink
import com.cosmotune.video.model.Kiosk
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.PlaylistPage
import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.model.Storyboard
import com.cosmotune.video.model.StreamKind
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.model.VideoStream
import kotlin.jvm.Synchronized
import kotlin.math.min

/**
 * A deterministic source in memory (plan task Y-2, step 3), for the screens, the controller and the engines' tests: 12
 * videos `fakevideo01` to `fakevideo12` (11 is live, 12 upcoming), 12 Shorts `fakeshort01` to `fakeshort12`, two channels
 * [CHANNEL_1] and [CHANNEL_2], a playlist of 30 ([PLAYLIST_ID]) paged [PAGE_SIZE] at a time, comments with replies
 * (turned off on [COMMENTS_OFF_VIDEO]), the five kiosks of spec 5.2, and [failNext] to throw once. Every address is on an
 * `.invalid` host: nothing here can reach the network. [calls] records each call as `name(argument)`.
 */
class FakeVideoSource(private val clock: () -> Long = { NOW_MS }) : VideoSource {
    private val log = mutableListOf<String>()
    private var pendingFailure: VideoSourceError? = null

    /** Every call so far, as `name(argument)` (for example `details(fakevideo01)`), oldest first. */
    val calls: List<String>
        @Synchronized get() = log.toList()

    /** The last region set with [setRegion], as country and language, or null. */
    var region: Pair<String, String>? = null
        private set

    /** The next call (any of them) throws [error], once. */
    @Synchronized
    fun failNext(error: VideoSourceError) {
        pendingFailure = error
    }

    @Synchronized
    private fun call(name: String, argument: String = "") {
        log += "$name($argument)"
        val failure = pendingFailure ?: return
        pendingFailure = null
        throw failure
    }

    override suspend fun kiosks(): List<Kiosk> {
        call("kiosks")
        return KIOSKS
    }

    override suspend fun kiosk(id: String, page: PageToken?): Page<VideoItem> {
        call("kiosk", id)
        val index = KIOSKS.indexOfFirst { it.id == id }
        if (index < 0) throw VideoSourceError.NotAvailable("No kiosk $id")
        val rotated = videos.drop(index * 2) + videos.take(index * 2)
        return paged(rotated, page, KIOSK_PAGE_SIZE, "kiosk:$id")
    }

    override suspend fun suggestions(query: String): List<String> {
        call("suggestions", query)
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return listOf(q, "$q live", "$q full album", "$q tutorial")
    }

    override suspend fun search(query: String, filter: SearchFilter, page: PageToken?): Page<ListItem> {
        call("search", "$query,$filter")
        if (query.isBlank() || query.trim() == NO_RESULTS_QUERY) return Page(emptyList(), null)
        val all: List<ListItem> = when (filter) {
            SearchFilter.ALL -> listOf<ListItem>(ListItem.Channel(channels[0])) +
                videos.take(6).map { ListItem.Video(it) } +
                shorts.take(3).map { ListItem.Video(it) } +
                ListItem.Playlist(playlist) +
                videos.drop(6).map { ListItem.Video(it) } +
                ListItem.Channel(channels[1])
            SearchFilter.VIDEOS -> videos.map { ListItem.Video(it) }
            SearchFilter.SHORTS -> shorts.map { ListItem.Video(it) }
            SearchFilter.CHANNELS -> channels.map { ListItem.Channel(it) }
            SearchFilter.PLAYLISTS -> listOf(ListItem.Playlist(playlist))
        }
        return paged(all, page, PAGE_SIZE, "search:$filter")
    }

    override suspend fun details(videoId: String): VideoDetails {
        call("details", videoId)
        val item = sampleItem(videoId) ?: throw VideoSourceError.NotAvailable("Video $videoId is not available")
        return detailsOf(item, clock())
    }

    override suspend fun streams(videoId: String): StreamSet {
        call("streams", videoId)
        val item = sampleItem(videoId) ?: throw VideoSourceError.NotAvailable("Video $videoId is not available")
        return streamSetFor(item, clock())
    }

    override suspend fun channel(channelIdOrUrl: String): ChannelPage {
        call("channel", channelIdOrUrl)
        val channel = channels.firstOrNull { channelIdOrUrl.contains(it.id) || (it.handle != null && channelIdOrUrl.endsWith(it.handle)) }
            ?: throw VideoSourceError.NotAvailable("No channel $channelIdOrUrl")
        return ChannelPage(
            channel = channel,
            bannerUrl = "$MEDIA/banner/${channel.id}.jpg",
            tabs = listOf(ChannelTab.VIDEOS, ChannelTab.SHORTS, ChannelTab.LIVE, ChannelTab.PLAYLISTS),
            joinedText = if (channel.id == CHANNEL_1) "Joined 3 Mar 2015" else "Joined 21 Nov 2019",
            links = listOf("https://example.com/${channel.handle?.removePrefix("@")}"),
        )
    }

    override suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken?): Page<ListItem> {
        call("channelTab", "$channelId,$tab")
        if (channels.none { it.id == channelId }) throw VideoSourceError.NotAvailable("No channel $channelId")
        val all: List<ListItem> = when (tab) {
            ChannelTab.VIDEOS -> videos.filter { it.channelId == channelId && !it.isLive && !it.isUpcoming }.map { ListItem.Video(it) }
            ChannelTab.SHORTS -> shorts.filter { it.channelId == channelId }.map { ListItem.Video(it) }
            ChannelTab.LIVE -> videos.filter { it.channelId == channelId && (it.isLive || it.isUpcoming) }.map { ListItem.Video(it) }
            ChannelTab.PLAYLISTS -> if (playlist.ownerId == channelId) listOf(ListItem.Playlist(playlist)) else emptyList()
        }
        return paged(all, page, PAGE_SIZE, "tab:$channelId:$tab")
    }

    override suspend fun playlist(playlistId: String, page: PageToken?): PlaylistPage {
        call("playlist", playlistId)
        if (playlistId != PLAYLIST_ID) throw VideoSourceError.NotAvailable("No playlist $playlistId")
        return PlaylistPage(playlist, "Thirty sample videos, for paging ten at a time.", paged(playlistItems, page, PAGE_SIZE, "playlist:$playlistId"))
    }

    override suspend fun comments(videoId: String, sort: CommentSort, page: PageToken?): CommentPage {
        call("comments", "$videoId,$sort")
        if (sampleItem(videoId) == null) throw VideoSourceError.NotAvailable("Video $videoId is not available")
        if (videoId == COMMENTS_OFF_VIDEO) return CommentPage(emptyList(), null, null, disabled = true, sortable = false)
        val all = commentsOf(videoId)
        val ordered = if (sort == CommentSort.NEWEST) all.filter { it.pinned } + all.filterNot { it.pinned }.reversed() else all
        val p = paged(ordered, page, PAGE_SIZE, "comments:$videoId:$sort")
        return CommentPage(p.items, p.next, totalCount = all.size.toLong(), disabled = false, sortable = true)
    }

    override suspend fun replies(token: PageToken): CommentPage {
        call("replies", token.value)
        val parts = token.value.split(':')
        if (parts.size != 3 || parts[0] != "replies") throw VideoSourceError.Parsing("Not a replies token: ${token.value}", null)
        val commentId = parts[1]
        val videoId = commentId.substringBeforeLast("-c")
        val parent = commentsOf(videoId).firstOrNull { it.id == commentId } ?: throw VideoSourceError.NotAvailable("No comment $commentId")
        val all = (1..parent.replyCount).map { n ->
            Comment(
                id = "$commentId-r${two(n)}", authorName = "Replier $n", authorChannelId = null, authorAvatarUrl = null,
                text = "Reply $n to the comment above.", likeCount = (n * 3).toLong(), publishedText = "$n days ago", pinned = false,
                heartedByCreator = false, byCreator = n == 2, edited = false, replyCount = 0, replies = null,
            )
        }
        val p = paged(all, PageToken("replies:$commentId:${parts[2]}"), PAGE_SIZE, "replies:$commentId")
        return CommentPage(p.items, p.next, totalCount = all.size.toLong(), disabled = false, sortable = false)
    }

    @Synchronized
    override fun setRegion(country: String, language: String) {
        log += "setRegion($country,$language)"
        region = country to language
    }

    companion object {
        /** The fakes' clock: 2026-10-16 12:00 UTC. */
        const val NOW_MS = 1_792_152_000_000L

        /** The first channel (`fakevideo01` to `06`, `fakeshort01` to `06`, the playlist). */
        const val CHANNEL_1 = "UCfakechannel0000000001"

        /** The second channel (`fakevideo07` to `12`, `fakeshort07` to `12`). */
        const val CHANNEL_2 = "UCfakechannel0000000002"

        /** The playlist of 30. */
        const val PLAYLIST_ID = "PLfakeplaylist0000000001"

        /** A query that finds nothing (the "No results" state). */
        const val NO_RESULTS_QUERY = "noresults"

        /** The video whose comments are turned off. */
        const val COMMENTS_OFF_VIDEO = "fakevideo10"

        /** Lists page this many at a time (the playlist, search, channel tabs, comments). */
        const val PAGE_SIZE = 10

        /** Kiosks page this many at a time (12 videos: two pages). */
        const val KIOSK_PAGE_SIZE = 6

        private const val MEDIA = "https://media.cosmotune.invalid"
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        private fun two(n: Int): String = n.toString().padStart(2, '0')

        /** The five kiosks of spec 5.2. */
        val KIOSKS: List<Kiosk> = listOf(
            Kiosk("music", "Music"), Kiosk("gaming", "Gaming"), Kiosk("movies", "Movies and shows"),
            Kiosk("podcasts", "Podcasts"), Kiosk("live", "Live"),
        )

        /** The two channels. */
        val channels: List<ChannelItem> = listOf(
            ChannelItem(CHANNEL_1, "Northern Signal", "$MEDIA/avatar/$CHANNEL_1.jpg", 1_240_000, 342,
                "Synths, tape machines and long drives.", verified = true, handle = "@northernsignal"),
            ChannelItem(CHANNEL_2, "Paper Lantern", "$MEDIA/avatar/$CHANNEL_2.jpg", 86_500, 57,
                "Quiet music for late evenings.", verified = false, handle = "@paperlantern"),
        )

        private val videoTitles = listOf(
            "Building a tiny synth from scratch",
            "Ten minutes of rain on a tin roof",
            "How tape echo works, explained with a cassette deck and a lot of patience on a quiet Sunday afternoon",
            "Night drive through the city, no talking",
            "The history of the drum machine",
            "Live at the harbour: the full set",
            "Lantern-making in one evening",
            "Three chords, five songs",
            "A walk through the old market at dawn",
            "Field recording: a forest after the storm",
            "Live now: late-night radio",
            "Premiere: the making of the album",
        )
        private val durations = listOf<Long?>(754, 600, 1_325, 3_605, 482, 5_400, 245, 61, 2_710, 905, null, null)
        private val views = listOf<Long?>(1_234_567, 98_000, 4_500_000, 12_345, 760, 230_000_000, 5_600, 87, 999_999, 42_000, 1_200, null)
        private val ages = listOf(2 * HOUR, DAY, 3 * DAY, 7 * DAY, 14 * DAY, 30 * DAY, 90 * DAY, 180 * DAY, 365 * DAY, 730 * DAY, 0L, 0L)

        /** The 12 videos; 11 is live, 12 upcoming. */
        val videos: List<VideoItem> = (1..12).map { n ->
            val channel = if (n <= 6) channels[0] else channels[1]
            val id = "fakevideo${two(n)}"
            VideoItem(
                id = id, title = videoTitles[n - 1], channelId = channel.id, channelName = channel.name,
                channelAvatarUrl = channel.avatarUrl, thumbnailUrl = "$MEDIA/thumb/$id.jpg", durationSec = durations[n - 1],
                viewCount = views[n - 1],
                uploadedAtMs = when (n) { 11 -> NOW_MS - 40 * 60_000L; 12 -> NOW_MS + 2 * DAY; else -> NOW_MS - ages[n - 1] },
                uploadedText = if (n == 12) "Premieres in 2 days" else null,
                isLive = n == 11, isUpcoming = n == 12,
            )
        }

        private val shortTitles = listOf(
            "One-take riff", "The tape loop trick", "Sixty seconds of synth", "Desk tour, fast", "A chord you have never heard",
            "Cat meets theremin", "Folding a paper lantern", "Rain sound hack", "Humming in a stairwell", "The smallest piano",
            "Sunrise timelapse", "Last note of the night",
        )

        /** The 12 Shorts: 9:16 thumbnails, under a minute each. */
        val shorts: List<VideoItem> = (1..12).map { n ->
            val channel = if (n <= 6) channels[0] else channels[1]
            val id = "fakeshort${two(n)}"
            VideoItem(
                id = id, title = shortTitles[n - 1], channelId = channel.id, channelName = channel.name,
                channelAvatarUrl = channel.avatarUrl, thumbnailUrl = "$MEDIA/shorts/$id/vertical-720x1280.jpg",
                durationSec = 14L + n * 3, viewCount = 2_000L * n * n, uploadedAtMs = NOW_MS - n * 5 * HOUR, uploadedText = null,
                isShort = true,
            )
        }

        /** The playlist. */
        val playlist: PlaylistItem = PlaylistItem(PLAYLIST_ID, "Thirty for the road", "$MEDIA/thumb/fakeplvideo01.jpg",
            channels[0].name, CHANNEL_1, 30)

        /** The playlist's 30 videos, `fakeplvideo01` to `fakeplvideo30`. */
        val playlistItems: List<VideoItem> = (1..30).map { n ->
            val id = "fakeplvideo${two(n)}"
            VideoItem(
                id = id, title = "Road song number $n", channelId = CHANNEL_1, channelName = channels[0].name,
                channelAvatarUrl = channels[0].avatarUrl, thumbnailUrl = "$MEDIA/thumb/$id.jpg", durationSec = 180L + n * 17,
                viewCount = 10_000L + n * 731, uploadedAtMs = NOW_MS - n * 2 * DAY, uploadedText = null,
            )
        }

        private val byId: Map<String, VideoItem> = (videos + shorts + playlistItems).associateBy { it.id }

        /** Any sample item by id (videos, Shorts, playlist videos), or null. */
        fun sampleItem(id: String): VideoItem? = byId[id]

        private fun <T> paged(all: List<T>, page: PageToken?, size: Int, prefix: String): Page<T> {
            val from = page?.value?.substringAfterLast(':')?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (from >= all.size) return Page(emptyList(), null)
            val to = min(all.size, from + size)
            return Page(all.subList(from, to).toList(), if (to < all.size) PageToken("$prefix:$to") else null)
        }

        /** The stream set of a sample item at [nowMs]: a ladder from 2160p60 to 144p, a muxed 360p, AAC and Opus (and a dubbed track); HLS when live. */
        fun streamSetFor(item: VideoItem, nowMs: Long): StreamSet {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (fake)")
            if (item.isLive || item.isUpcoming) {
                return StreamSet(emptyList(), emptyList(), emptyList(), hlsUrl = "$MEDIA/live/${item.id}/index.m3u8", dashMpdUrl = null,
                    expiresAtMs = nowMs + 6 * HOUR, requestHeaders = headers, durationMs = null)
            }
            val seconds = item.durationSec ?: 600
            fun url(itag: Int) = "$MEDIA/videoplayback?id=${item.id}&itag=$itag&c=WEB"
            fun video(itag: Int, codec: VideoCodec, codecs: String, height: Int, fps: Int, kbps: Int, mime: String): VideoStream {
                val width = height * 16 / 9
                return VideoStream(
                    url = url(itag), itag = itag, mimeType = mime, codec = codec, codecs = codecs,
                    width = if (item.isShort) height else width, height = if (item.isShort) width else height, fps = fps,
                    bitrate = kbps * 1000, hdr = false, contentLength = kbps * 125L * seconds, init = ByteRange(0, 739),
                    index = ByteRange(740, 2_811), delivery = Delivery.DASH, hasAudio = false,
                )
            }
            fun audio(itag: Int, codec: AudioCodec, codecs: String, kbps: Int, mime: String, trackId: String?, language: String, kind: AudioTrackKind) =
                AudioStream(
                    url = url(itag) + (trackId?.let { "&xtags=$it" } ?: ""), itag = itag, mimeType = mime, codec = codec, codecs = codecs,
                    bitrate = kbps * 1000, sampleRate = 48_000, channels = 2, contentLength = kbps * 125L * seconds,
                    init = ByteRange(0, 640), index = ByteRange(641, 1_904), delivery = Delivery.DASH, trackId = trackId,
                    trackName = if (kind == AudioTrackKind.DUBBED) "Hindi" else "English original", language = language, trackKind = kind,
                )
            val webm = "video/webm"
            val mp4 = "video/mp4"
            return StreamSet(
                videoOnly = listOf(
                    video(315, VideoCodec.VP9, "vp9", 2160, 60, 18_000, webm),
                    video(308, VideoCodec.VP9, "vp9", 1440, 60, 9_000, webm),
                    video(399, VideoCodec.AV1, "av01.0.09M.08", 1080, 60, 3_000, mp4),
                    video(303, VideoCodec.VP9, "vp9", 1080, 60, 3_400, webm),
                    video(137, VideoCodec.H264, "avc1.640028", 1080, 30, 3_800, mp4),
                    video(302, VideoCodec.VP9, "vp9", 720, 60, 2_200, webm),
                    video(136, VideoCodec.H264, "avc1.4d401f", 720, 30, 2_000, mp4),
                    video(135, VideoCodec.H264, "avc1.4d401e", 480, 30, 900, mp4),
                    video(134, VideoCodec.H264, "avc1.4d401e", 360, 30, 500, mp4),
                    video(133, VideoCodec.H264, "avc1.4d4015", 240, 30, 250, mp4),
                    video(160, VideoCodec.H264, "avc1.4d400c", 144, 30, 120, mp4),
                ),
                muxed = listOf(
                    VideoStream(url(18), 18, mp4, VideoCodec.H264, "avc1.42001E, mp4a.40.2", 640, 360, 30, 600_000, false,
                        contentLength = 75L * 1000 * seconds, init = null, index = null, delivery = Delivery.PROGRESSIVE, hasAudio = true),
                ),
                audio = listOf(
                    audio(140, AudioCodec.AAC, "mp4a.40.2", 128, "audio/mp4", null, "en", AudioTrackKind.ORIGINAL),
                    audio(251, AudioCodec.OPUS, "opus", 160, "audio/webm", null, "en", AudioTrackKind.ORIGINAL),
                    audio(251, AudioCodec.OPUS, "opus", 150, "audio/webm", "hi.4", "hi", AudioTrackKind.DUBBED),
                ),
                hlsUrl = null, dashMpdUrl = null, expiresAtMs = nowMs + 6 * HOUR, requestHeaders = headers, durationMs = seconds * 1000,
            )
        }

        private fun detailsOf(item: VideoItem, nowMs: Long): VideoDetails {
            val long = !item.isShort && !item.isLive && !item.isUpcoming && (item.durationSec ?: 0) >= 200
            val text = buildString {
                append(item.title).append(".\n\n")
                if (long) append("0:00 Start\n1:30 The main part\n3:05 The end\n")
                append("More at https://example.com/").append(item.id)
            }
            val links = buildList {
                if (long) {
                    for ((stamp, ms) in listOf("0:00" to 0L, "1:30" to 90_000L, "3:05" to 185_000L)) {
                        val at = text.indexOf(stamp)
                        add(DescriptionLink(at, at + stamp.length, null, ms))
                    }
                }
                val address = "https://example.com/${item.id}"
                val at = text.indexOf(address)
                add(DescriptionLink(at, at + address.length, address, null))
            }
            val channel = channels.first { it.id == item.channelId }
            val seconds = item.durationSec ?: 0
            return VideoDetails(
                item = item,
                description = Description(text, links),
                likeCount = item.viewCount?.let { it / 40 },
                category = if (item.channelId == CHANNEL_1) "Music" else "Entertainment",
                tags = listOf("sample", "fake"),
                chapters = if (long) listOf(Chapter("Start", 0, null), Chapter("The main part", 90_000, null), Chapter("The end", 185_000, null)) else emptyList(),
                related = videos.filter { it.id != item.id }.take(6).map { ListItem.Video(it) } +
                    shorts.filter { it.id != item.id }.take(2).map { ListItem.Video(it) },
                subtitles = listOf(
                    SubtitleTrack("$MEDIA/subs/${item.id}.en.vtt", "text/vtt", "en", "English", autoGenerated = false),
                    SubtitleTrack("$MEDIA/subs/${item.id}.hi.vtt", "text/vtt", "hi", "Hindi", autoGenerated = false),
                    SubtitleTrack("$MEDIA/subs/${item.id}.en-auto.vtt", "text/vtt", "en", "English (auto-generated)", autoGenerated = true),
                ),
                storyboard = if (seconds > 0) Storyboard(
                    urls = listOf("$MEDIA/sb/${item.id}/M0.jpg", "$MEDIA/sb/${item.id}/M1.jpg"), frameWidth = 160, frameHeight = 90,
                    columns = 10, rows = 10, intervalMs = 5_000, totalFrames = (seconds / 5).toInt().coerceAtLeast(1),
                ) else null,
                kind = if (item.isLive) StreamKind.LIVE else StreamKind.VIDEO,
                streams = streamSetFor(item, nowMs),
                subscriberCount = channel.subscriberCount,
                fetchedAtMs = nowMs,
            )
        }

        private fun commentsOf(videoId: String): List<Comment> = (1..25).map { n ->
            val id = "$videoId-c${two(n)}"
            val replyCount = when (n) { 3 -> 3; 5 -> 12; 9 -> 1; else -> 0 }
            Comment(
                id = id,
                authorName = if (n == 1) "Northern Signal" else "Viewer $n",
                authorChannelId = if (n == 1) CHANNEL_1 else null,
                authorAvatarUrl = "$MEDIA/avatar/viewer$n.jpg",
                text = when (n) {
                    1 -> "Thanks for watching. The patch notes are in the description."
                    4 -> "This is a long comment that goes on for a while. ".repeat(12).trim()
                    else -> "Comment number $n on this video."
                },
                likeCount = (300L - n * 11).coerceAtLeast(0),
                publishedText = "$n days ago",
                pinned = n == 1,
                heartedByCreator = n == 2,
                byCreator = n == 1,
                edited = n == 6,
                replyCount = replyCount,
                replies = if (replyCount > 0) PageToken("replies:$id:0") else null,
            )
        }
    }
}
