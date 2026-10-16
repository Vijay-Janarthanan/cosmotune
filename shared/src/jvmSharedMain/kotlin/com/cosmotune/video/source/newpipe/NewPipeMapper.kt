package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ChannelPage
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.Description
import com.cosmotune.video.model.DescriptionLink
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.Storyboard
import com.cosmotune.video.model.StreamKind
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.SubtitleTrack
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.net.GooglevideoRequests
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.AudioStream as NpAudioStream
import org.schabi.newpipe.extractor.stream.Description as NpDescription
import org.schabi.newpipe.extractor.stream.VideoStream as NpVideoStream

/**
 * NewPipe's objects as our models (plan task Y-4, step 4). The only place that knows both; nothing here touches the network.
 *
 * Conventions: an unknown number (NewPipe uses -1, and -2 or -3 for "infinite" and "more than 100") is null; a blank string
 * is null; pictures are chosen by [bestImageUrl]; a list item that cannot be opened (no video id in its address) is dropped.
 */
internal object NewPipeMapper {
    /** A feed card is 328 dp wide: the smallest picture at least this wide is sharp without being heavy (spec 5.0). */
    const val THUMBNAIL_MIN_WIDTH = 480

    /** Avatars show at 36 to 88 dp; the 176 px variant YouTube serves is sharp at every size and still small. */
    const val AVATAR_MIN_WIDTH = 176

    /** A channel banner crosses the page: the smallest one at least this wide. */
    const val BANNER_MIN_WIDTH = 1060

    // ---------------------------------------------------------------- ids

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val BARE_CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val LIST_ID = Regex("^[A-Za-z0-9_-]+$")
    private val VIDEO_PATH_PREFIXES = setOf("shorts", "embed", "live", "v", "e")

    /** The 11-character video id of a watch, Shorts, live, embed or youtu.be address, or of a bare id; null when there is none. */
    fun videoIdOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (VIDEO_ID.matches(text)) return text
        val http = text.toHttpUrlOrNull() ?: return null
        http.queryParameter("v")?.takeIf { VIDEO_ID.matches(it) }?.let { return it }
        val segments = http.pathSegments.filter { it.isNotEmpty() }
        val host = http.host
        if (host == "youtu.be" || host.endsWith(".youtu.be")) return segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }
        if (segments.size >= 2 && segments[0] in VIDEO_PATH_PREFIXES) return segments[1].takeIf { VIDEO_ID.matches(it) }
        return null
    }

    /** The channel id of a `/channel/<id>` address, or of a bare `UC...` id; null for a handle, a user name or anything else. */
    fun channelIdOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (BARE_CHANNEL_ID.matches(text)) return text
        val http = text.toHttpUrlOrNull() ?: return null
        val segments = http.pathSegments.filter { it.isNotEmpty() }
        return if (segments.size >= 2 && segments[0] == "channel") segments[1] else null
    }

    /** The `@handle` of a handle address, or null. */
    fun handleOf(url: String?): String? {
        val http = url?.trim()?.toHttpUrlOrNull() ?: return null
        return http.pathSegments.firstOrNull { it.isNotEmpty() }?.takeIf { it.startsWith("@") && it.length > 1 }
    }

    /** What `VideoSource.channel` accepts for a channel address: its id, else its `@handle`, else `user/<name>` or `c/<name>`. */
    fun channelKeyOf(url: String?): String? {
        channelIdOf(url)?.let { return it }
        handleOf(url)?.let { return it }
        val http = url?.trim()?.toHttpUrlOrNull() ?: return null
        val segments = http.pathSegments.filter { it.isNotEmpty() }
        return if (segments.size >= 2 && (segments[0] == "user" || segments[0] == "c")) "${segments[0]}/${segments[1]}" else null
    }

    /** The playlist id of an address with `list=`, or null. */
    fun playlistIdOf(url: String?): String? {
        val http = url?.trim()?.toHttpUrlOrNull() ?: return null
        return http.queryParameter("list")?.takeIf { LIST_ID.matches(it) }
    }

    // ---------------------------------------------------------------- pictures

    /**
     * The picture to show out of [images]: the smallest one at least [minWidth] wide, else the largest; pictures of unknown
     * width only count when none has a width, and then the one of the highest resolution level wins. Null for no pictures.
     */
    fun bestImageUrl(images: List<Image>?, minWidth: Int): String? {
        val usable = images.orEmpty().filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return null
        val known = usable.filter { it.width > 0 }
        if (known.isNotEmpty()) {
            val enough = known.filter { it.width >= minWidth }
            return (if (enough.isNotEmpty()) enough.minByOrNull { it.width } else known.maxByOrNull { it.width })?.url
        }
        return usable.maxByOrNull { levelRank(it.estimatedResolutionLevel) }?.url
    }

    private fun levelRank(level: Image.ResolutionLevel?): Int = when (level) {
        Image.ResolutionLevel.HIGH -> 3
        Image.ResolutionLevel.MEDIUM -> 2
        Image.ResolutionLevel.LOW -> 1
        else -> 0
    }

    private fun thumbnailOf(images: List<Image>?) = bestImageUrl(images, THUMBNAIL_MIN_WIDTH)

    private fun avatarOf(images: List<Image>?) = bestImageUrl(images, AVATAR_MIN_WIDTH)

    private fun blankToNull(text: String?): String? = text?.takeIf { it.isNotBlank() }

    private fun known(count: Long): Long? = count.takeIf { it >= 0 }

    // ---------------------------------------------------------------- list items

    /** A video of a list (search, a channel tab, a kiosk, a playlist, the related videos). [nowMs] decides whether a premiere is still to come. */
    fun videoItem(item: StreamInfoItem, nowMs: Long): VideoItem {
        val live = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
        val durationSec = if (live) null else item.duration.takeIf { it > 0 }
        val uploaded = uploadTime(item.uploadDate, item.textualUploadDate)
        val upcoming = !live && durationSec == null && uploaded.atMs != null && uploaded.atMs > nowMs
        return VideoItem(
            id = videoIdOf(item.url) ?: item.url,
            title = item.name.orEmpty(),
            channelId = channelIdOf(item.uploaderUrl),
            channelName = blankToNull(item.uploaderName),
            channelAvatarUrl = avatarOf(item.uploaderAvatars),
            thumbnailUrl = thumbnailOf(item.thumbnails),
            durationSec = durationSec,
            viewCount = known(item.viewCount),
            uploadedAtMs = uploaded.atMs,
            uploadedText = if (uploaded.exact && !upcoming) null else uploaded.text,
            isLive = live,
            isUpcoming = upcoming,
            isShort = item.isShortFormContent,
        )
    }

    private class UploadTime(val atMs: Long?, val text: String?, val exact: Boolean)

    private fun uploadTime(date: org.schabi.newpipe.extractor.localization.DateWrapper?, text: String?): UploadTime =
        UploadTime(date?.instant?.toEpochMilli(), blankToNull(text), exact = date != null && !date.isApproximation)

    fun channelItem(item: ChannelInfoItem): ChannelItem = ChannelItem(
        id = channelKeyOf(item.url) ?: item.url,
        name = item.name.orEmpty(),
        avatarUrl = avatarOf(item.thumbnails),
        subscriberCount = known(item.subscriberCount),
        videoCount = known(item.streamCount),
        description = blankToNull(item.description),
        verified = item.isVerified,
        handle = handleOf(item.url),
    )

    fun playlistItem(item: PlaylistInfoItem): PlaylistItem = PlaylistItem(
        id = playlistIdOf(item.url) ?: item.url,
        title = item.name.orEmpty(),
        thumbnailUrl = thumbnailOf(item.thumbnails),
        ownerName = blankToNull(item.uploaderName),
        ownerId = channelIdOf(item.uploaderUrl),
        videoCount = known(item.streamCount),
    )

    /** One entry of a mixed list, or null for a kind of item Video does not show or a video without an id. */
    fun listItem(item: InfoItem, nowMs: Long): ListItem? = when (item) {
        is StreamInfoItem -> if (videoIdOf(item.url) != null) ListItem.Video(videoItem(item, nowMs)) else null
        is ChannelInfoItem -> ListItem.Channel(channelItem(item))
        is PlaylistInfoItem -> ListItem.Playlist(playlistItem(item))
        else -> null
    }

    // ---------------------------------------------------------------- the watch page

    fun streamKind(type: StreamType?): StreamKind = when (type) {
        StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> StreamKind.LIVE
        StreamType.POST_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM -> StreamKind.POST_LIVE
        StreamType.AUDIO_STREAM -> StreamKind.AUDIO
        else -> StreamKind.VIDEO
    }

    /** Everything the watch page shows of [info]; [nowMs] is also when the answer was fetched. */
    fun details(info: StreamInfo, nowMs: Long, localization: Localization): VideoDetails {
        val kind = streamKind(info.streamType)
        val live = kind == StreamKind.LIVE
        val durationSec = if (live) null else info.duration.takeIf { it > 0 }
        val uploaded = uploadTime(info.uploadDate, info.textualUploadDate)
        val upcoming = !live && durationSec == null && uploaded.atMs != null && uploaded.atMs > nowMs
        val item = VideoItem(
            id = blankToNull(info.id) ?: videoIdOf(info.url) ?: info.url,
            title = info.name.orEmpty(),
            channelId = channelIdOf(info.uploaderUrl),
            channelName = blankToNull(info.uploaderName),
            channelAvatarUrl = avatarOf(info.uploaderAvatars),
            thumbnailUrl = thumbnailOf(info.thumbnails),
            durationSec = durationSec,
            viewCount = known(info.viewCount),
            uploadedAtMs = uploaded.atMs,
            uploadedText = if (uploaded.exact && !upcoming) null else uploaded.text,
            isLive = live,
            isUpcoming = upcoming,
            isShort = info.isShortFormContent,
        )
        return VideoDetails(
            item = item,
            description = description(info.description, videoId = item.id),
            likeCount = known(info.likeCount),
            category = blankToNull(info.category),
            tags = info.tags.orEmpty().filter { it.isNotBlank() },
            chapters = chapters(info.streamSegments),
            related = info.relatedItems.orEmpty().mapNotNull { listItem(it, nowMs) },
            subtitles = info.subtitles.orEmpty().map { subtitle(it) },
            storyboard = storyboard(info.previewFrames),
            kind = kind,
            streams = streamSet(info, localization),
            subscriberCount = known(info.uploaderSubscriberCount),
            fetchedAtMs = nowMs,
        )
    }

    fun chapters(segments: List<StreamSegment>?): List<Chapter> = segments.orEmpty()
        .map { Chapter(title = it.title.orEmpty(), startMs = it.startTimeSeconds * 1000L, thumbnailUrl = blankToNull(it.previewUrl)) }
        .sortedBy { it.startMs }

    private val TIMEDTEXT = Regex("^https?://[^/]*youtube\\.com/api/timedtext", RegexOption.IGNORE_CASE)
    private val FMT_PARAMETER = Regex("([?&])fmt=[^&#]*")

    /**
     * A subtitle track. Addresses of the source's own timed-text service are asked for WebVTT (it can give it: `fmt=vtt`);
     * any other keeps the format NewPipe found, TTML with its MIME type.
     */
    fun subtitle(s: SubtitlesStream): SubtitleTrack {
        val url = s.content.orEmpty()
        val vtt = TIMEDTEXT.containsMatchIn(url)
        val address = when {
            !vtt -> url
            FMT_PARAMETER.containsMatchIn(url) -> url.replace(FMT_PARAMETER, "$1fmt=vtt")
            else -> url + (if ('?' in url) "&" else "?") + "fmt=vtt"
        }
        return SubtitleTrack(
            url = address,
            mimeType = if (vtt) MediaFormat.VTT.mimeType else s.format?.mimeType ?: MediaFormat.TTML.mimeType,
            language = s.languageTag.orEmpty(),
            name = s.displayLanguageName.orEmpty(),
            autoGenerated = s.isAutoGenerated,
        )
    }

    /** The scrubbing previews: the frameset with the largest frames (the bubble of the scrubber shows one at 160 x 90, spec 6.5). */
    fun storyboard(frames: List<Frameset>?): Storyboard? {
        val best = frames.orEmpty()
            .filter { it.urls.isNotEmpty() && it.totalCount > 0 && it.durationPerFrame > 0 && it.framesPerPageX > 0 && it.framesPerPageY > 0 }
            .maxByOrNull { it.frameWidth.toLong() * it.frameHeight } ?: return null
        return Storyboard(
            urls = best.urls.toList(),
            frameWidth = best.frameWidth,
            frameHeight = best.frameHeight,
            columns = best.framesPerPageX,
            rows = best.framesPerPageY,
            intervalMs = best.durationPerFrame.toLong(),
            totalFrames = best.totalCount,
        )
    }

    // ---------------------------------------------------------------- streams

    /** The streams of [info] with when they stop working and the User-Agent they were minted for. */
    fun streamSet(info: StreamInfo, localization: Localization): StreamSet {
        val videoOnly = info.videoOnlyStreams.orEmpty().mapNotNull { videoStream(it) }
        val muxed = info.videoStreams.orEmpty().mapNotNull { videoStream(it) }
        val audio = info.audioStreams.orEmpty().mapNotNull { audioStream(it) }
        val hls = blankToNull(info.hlsUrl)
        val dash = blankToNull(info.dashMpdUrl)
        val addresses = videoOnly.map { it.url } + muxed.map { it.url } + audio.map { it.url }
        val manifests = listOfNotNull(hls, dash)
        val client = (addresses + manifests).firstNotNullOfOrNull { GooglevideoRequests.clientOf(it) }
        return StreamSet(
            videoOnly = videoOnly,
            muxed = muxed,
            audio = audio,
            hlsUrl = hls,
            dashMpdUrl = dash,
            expiresAtMs = expiresAtMsOf(addresses) ?: expiresAtMsOf(manifests),
            requestHeaders = mapOf("User-Agent" to userAgentFor(client, localization)),
            durationMs = info.duration.takeIf { it > 0 }?.times(1000),
        )
    }

    /** The User-Agent the extractor expects for addresses minted for [client] (the `c=` parameter), else Video's default. */
    fun userAgentFor(client: String?, localization: Localization): String = when (client) {
        "VISIONOS" -> YoutubeParsingHelper.getVisionOsUserAgent(localization)
        "ANDROID" -> YoutubeParsingHelper.getAndroidUserAgent(localization)
        "IOS" -> YoutubeParsingHelper.getIosUserAgent(localization)
        else -> VideoDownloader.DEFAULT_USER_AGENT
    }

    private val EXPIRE_PARAMETER = Regex("[?&]expire=(\\d+)(?=[&#]|$)")
    private val EXPIRE_PATH = Regex("/expire/(\\d+)(?=/|$)")

    /** When the earliest of [urls] stops working, from its `expire` parameter (or `/expire/` path segment of a manifest), in milliseconds; null if none says. */
    fun expiresAtMsOf(urls: List<String>): Long? = urls.mapNotNull { url ->
        (EXPIRE_PARAMETER.find(url) ?: EXPIRE_PATH.find(url))?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
    }.minOrNull()

    private val RESOLUTION_HEIGHT = Regex("^(\\d{3,4})p")

    /**
     * A video stream, or null when it cannot be given as an address: its content is a manifest NewPipe generated for it
     * (`isUrl` false), or it is delivered by a method Video does not play.
     */
    fun videoStream(s: NpVideoStream): VideoStream? {
        if (!s.isUrl) return null
        val delivery = deliveryOf(s.deliveryMethod) ?: return null
        val url = s.content.takeIf { it.isNotBlank() } ?: return null
        val codecs = s.codec.orEmpty()
        var height = s.height
        var width = s.width
        if (height <= 0) height = RESOLUTION_HEIGHT.find(s.getResolution())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (width <= 0) width = height * 16 / 9
        return VideoStream(
            url = url,
            itag = s.itag,
            mimeType = s.format?.mimeType.orEmpty(),
            codec = videoCodecOf(codecs),
            codecs = codecs,
            width = width,
            height = height,
            fps = s.fps.coerceAtLeast(0),
            bitrate = s.bitrate.coerceAtLeast(0),
            hdr = isHdr(s.quality, codecs) || s.getResolution().contains("HDR", ignoreCase = true),
            contentLength = s.itagItem?.contentLength?.takeIf { it > 0 },
            init = range(s.initStart, s.initEnd),
            index = range(s.indexStart, s.indexEnd),
            delivery = delivery,
            hasAudio = !s.isVideoOnly(),
        )
    }

    fun audioStream(s: NpAudioStream): AudioStream? {
        if (!s.isUrl) return null
        val delivery = deliveryOf(s.deliveryMethod) ?: return null
        val url = s.content.takeIf { it.isNotBlank() } ?: return null
        val codecs = s.codec.orEmpty()
        val itag = s.itagItem
        val bitrate = when {
            s.bitrate > 0 -> s.bitrate
            s.averageBitrate > 0 -> s.averageBitrate * 1000
            else -> 0
        }
        return AudioStream(
            url = url,
            itag = s.itag,
            mimeType = s.format?.mimeType.orEmpty(),
            codec = audioCodecOf(codecs).takeIf { it != AudioCodec.OTHER } ?: audioCodecOfFormat(s.format),
            codecs = codecs,
            bitrate = bitrate,
            sampleRate = itag?.sampleRate?.takeIf { it > 0 },
            channels = itag?.audioChannels?.takeIf { it > 0 },
            contentLength = itag?.contentLength?.takeIf { it > 0 },
            init = range(s.initStart, s.initEnd),
            index = range(s.indexStart, s.indexEnd),
            delivery = delivery,
            trackId = blankToNull(s.audioTrackId),
            trackName = blankToNull(s.audioTrackName),
            language = s.audioLocale?.toLanguageTag()?.takeIf { it != "und" },
            trackKind = when (s.audioTrackType) {
                AudioTrackType.ORIGINAL -> AudioTrackKind.ORIGINAL
                AudioTrackType.DUBBED -> AudioTrackKind.DUBBED
                AudioTrackType.DESCRIPTIVE -> AudioTrackKind.DESCRIPTIVE
                AudioTrackType.SECONDARY -> AudioTrackKind.SECONDARY
                null -> AudioTrackKind.UNKNOWN
            },
        )
    }

    /** NewPipe's delivery as ours, one to one; null for the methods Video cannot play (smooth streaming, torrents). */
    private fun deliveryOf(method: DeliveryMethod?): Delivery? = when (method) {
        DeliveryMethod.PROGRESSIVE_HTTP -> Delivery.PROGRESSIVE
        DeliveryMethod.DASH -> Delivery.DASH
        DeliveryMethod.HLS -> Delivery.HLS
        else -> null
    }

    /**
     * A byte range from NewPipe's start and end; null when either is negative or they are not a range. The item's 0..0 is
     * what an unset range reads as: no container header is one byte long.
     */
    private fun range(start: Int, end: Int): ByteRange? =
        if (start < 0 || end < 0 || end < start || (start == 0 && end == 0)) null else ByteRange(start.toLong(), end.toLong())

    fun videoCodecOf(codecs: String?): VideoCodec {
        val c = codecs.orEmpty().lowercase()
        return when {
            c.startsWith("avc1") || c.startsWith("avc3") -> VideoCodec.H264
            c.startsWith("vp9") || c.startsWith("vp09") -> VideoCodec.VP9
            c.startsWith("av01") -> VideoCodec.AV1
            else -> VideoCodec.OTHER
        }
    }

    fun audioCodecOf(codecs: String?): AudioCodec {
        val c = codecs.orEmpty().lowercase()
        return when {
            c.startsWith("mp4a") -> AudioCodec.AAC
            c.startsWith("opus") -> AudioCodec.OPUS
            else -> AudioCodec.OTHER
        }
    }

    private fun audioCodecOfFormat(format: MediaFormat?): AudioCodec = when (format) {
        MediaFormat.M4A -> AudioCodec.AAC
        MediaFormat.WEBMA_OPUS, MediaFormat.OPUS -> AudioCodec.OPUS
        else -> AudioCodec.OTHER
    }

    /**
     * Whether a stream is HDR: NewPipe's `ItagItem` keeps no colour information, so the quality label ("2160p60 HDR") is
     * read, and the codec string's transfer characteristics: 16 (PQ) or 18 (HLG) in `vp09.PP.LL.DD.CC.cp.tc.mc.F` (field 7)
     * and in `av01.P.LLT.DD.M.CCC.cp.tc.mc.F` (field 8). A 10-bit stream with an SDR transfer function is not HDR.
     */
    fun isHdr(qualityLabel: String?, codecs: String?): Boolean {
        if (qualityLabel?.contains("HDR", ignoreCase = true) == true) return true
        val fields = codecs.orEmpty().lowercase().split('.')
        val transfer = when (fields.firstOrNull()) {
            "vp09" -> fields.getOrNull(6)
            "av01" -> fields.getOrNull(7)
            else -> null
        }?.toIntOrNull()
        return transfer == 16 || transfer == 18
    }

    // ---------------------------------------------------------------- the description

    /** A description as text with its links and timestamps; [videoId] is the video it belongs to (a timestamp link to another video is a plain link). */
    fun description(d: NpDescription?, videoId: String? = null): Description =
        if (d == null) Description("", emptyList()) else DescriptionParser.parse(d.content, d.type, videoId)

    // ---------------------------------------------------------------- comments

    private val EDITED = Regex("\\s*\\(edited\\)", RegexOption.IGNORE_CASE)

    /** A comment; its replies token carries [videoId], which NewPipe's page for replies does not. */
    fun comment(c: CommentsInfoItem, videoId: String): Comment {
        val published = c.textualUploadDate
        val edited = published != null && EDITED.containsMatchIn(published)
        val replies = c.replies?.takeIf { Page.isValid(it) }
        val body = c.commentText
        return Comment(
            id = blankToNull(c.commentId) ?: c.url,
            authorName = c.uploaderName.orEmpty(),
            authorChannelId = channelIdOf(c.uploaderUrl),
            authorAvatarUrl = avatarOf(c.uploaderAvatars),
            text = DescriptionParser.parse(body.content, body.type).text,
            likeCount = c.likeCount.takeIf { it >= 0 }?.toLong(),
            publishedText = blankToNull(published?.replace(EDITED, "")?.trim()),
            pinned = c.isPinned,
            heartedByCreator = c.isHeartedByUploader,
            byCreator = c.isChannelOwner,
            edited = edited,
            replyCount = when {
                c.replyCount > 0 -> c.replyCount
                replies != null -> 1
                else -> 0
            },
            replies = replies?.let { PageCodec.encode(it, videoId) },
        )
    }

    // ---------------------------------------------------------------- channels

    /** The channel of a header. */
    fun channelOf(info: ChannelInfo): ChannelItem = ChannelItem(
        id = info.id.orEmpty().removePrefix("channel/").takeIf { it.startsWith("UC") } ?: channelKeyOf(info.url) ?: info.id.orEmpty(),
        name = info.name.orEmpty(),
        avatarUrl = avatarOf(info.avatars),
        subscriberCount = known(info.subscriberCount),
        videoCount = null,
        description = blankToNull(info.description),
        verified = info.isVerified,
        handle = handleOf(info.originalUrl) ?: handleOf(info.url),
    )

    fun channelPage(info: ChannelInfo): ChannelPage = ChannelPage(
        channel = channelOf(info),
        bannerUrl = bestImageUrl(info.banners, BANNER_MIN_WIDTH),
        tabs = tabsOf(info.tabs),
        joinedText = null,
        links = info.donationLinks.orEmpty().filter { it.isNotBlank() },
    )

    /** The tabs a channel offers that Video shows, in the order of [ChannelTab]. */
    fun tabsOf(handlers: List<ListLinkHandler>?): List<ChannelTab> = handlers.orEmpty()
        .mapNotNull { it.contentFilters?.firstOrNull() }
        .mapNotNull { tabOfFilter(it) }
        .distinct()
        .sortedBy { it.ordinal }

    private fun tabOfFilter(filter: String): ChannelTab? = when (filter) {
        "videos" -> ChannelTab.VIDEOS
        "shorts" -> ChannelTab.SHORTS
        "livestreams" -> ChannelTab.LIVE
        "playlists" -> ChannelTab.PLAYLISTS
        else -> null
    }
}

/**
 * A description as text with its links (spec 5.4: "full text with tappable timestamps and links"). YouTube's descriptions
 * arrive as HTML (anchors, `<br>`, entities); the text, the anchors' spans and the times and bare addresses found in the
 * text become [DescriptionLink]s. A time is `m:ss` or `h:mm:ss`; a link whose address is a moment of the same video
 * (`t=90s`, `t=1h2m3s`, `#t=90`) is a timestamp, not an address.
 */
internal object DescriptionParser {
    private val TIME = Regex("(?<![\\w:.])(?:(\\d{1,2}):)?(\\d{1,3}):([0-5]\\d)(?![\\w:])")
    private val ADDRESS = Regex("https?://[^\\s<>\"]+")
    private val TIME_PARAMETER = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$")
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}'\""
    private const val SITE = "https://www.youtube.com"

    fun parse(content: String?, type: Int, videoId: String? = null): Description {
        if (content.isNullOrEmpty()) return Description("", emptyList())
        val text = StringBuilder()
        val links = mutableListOf<DescriptionLink>()
        if (type == NpDescription.HTML) {
            walk(Jsoup.parseBodyFragment(content).body(), text, links, videoId)
        } else {
            text.append(content)
        }
        val plain = text.toString()
        addAddresses(plain, links)
        addTimes(plain, links)
        return Description(plain, links.sortedBy { it.start })
    }

    private fun walk(node: Node, text: StringBuilder, links: MutableList<DescriptionLink>, videoId: String?) {
        when (node) {
            is TextNode -> text.append(node.wholeText)
            is Element -> when (node.normalName()) {
                "br" -> text.append('\n')
                "a" -> {
                    val start = text.length
                    node.childNodes().forEach { walk(it, text, links, videoId) }
                    addAnchor(node.attr("href"), start, text.length, links, videoId)
                }
                "p", "div", "li" -> {
                    if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                    node.childNodes().forEach { walk(it, text, links, videoId) }
                    if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                }
                else -> node.childNodes().forEach { walk(it, text, links, videoId) }
            }
            else -> Unit
        }
    }

    private fun addAnchor(href: String, start: Int, end: Int, links: MutableList<DescriptionLink>, videoId: String?) {
        if (end <= start || href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", ignoreCase = true)) return
        val address = if (href.startsWith("/")) SITE + href else href
        val seconds = momentOf(address, videoId)
        links += if (seconds != null) DescriptionLink(start, end, null, seconds * 1000) else DescriptionLink(start, end, address, null)
    }

    /** The second of the video that [address] points at, if it is a watch address of the video site (and of [videoId], when known) with a time. */
    private fun momentOf(address: String, videoId: String?): Long? {
        val http = address.toHttpUrlOrNull() ?: return null
        val host = http.host
        val ours = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (!ours) return null
        val target = NewPipeMapper.videoIdOf(address)
        if (videoId != null && target != null && target != videoId) return null
        val value = http.queryParameter("t") ?: http.queryParameter("start")
            ?: http.fragment?.split('&')?.firstOrNull { it.startsWith("t=") }?.removePrefix("t=")
        return value?.let { secondsOf(it) }
    }

    /** `90`, `90s`, `2m5s`, `1h2m3s` as seconds; null for anything else. */
    private fun secondsOf(value: String): Long? {
        value.toLongOrNull()?.let { return it }
        val m = TIME_PARAMETER.matchEntire(value) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        fun part(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        return part(1) * 3600 + part(2) * 60 + part(3)
    }

    private fun addAddresses(text: String, links: MutableList<DescriptionLink>) {
        for (match in ADDRESS.findAll(text)) {
            val start = match.range.first
            var end = match.range.last + 1
            while (end > start && text[end - 1] in TRAILING_PUNCTUATION) end--
            if (end - start < "http://x".length || overlaps(links, start, end)) continue
            links += DescriptionLink(start, end, text.substring(start, end), null)
        }
    }

    private fun addTimes(text: String, links: MutableList<DescriptionLink>) {
        for (match in TIME.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (overlaps(links, start, end)) continue
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLong()
            val seconds = match.groupValues[3].toLong()
            links += DescriptionLink(start, end, null, (hours * 3600 + minutes * 60 + seconds) * 1000)
        }
    }

    private fun overlaps(links: List<DescriptionLink>, start: Int, end: Int): Boolean = links.any { start < it.end && end > it.start }
}

/**
 * The addresses the adapter hands to NewPipe (plan task Y-4): watch pages, playlists and channels by id, handle, legacy name or
 * address. Pure string work.
 */
internal object NewPipeAddresses {
    const val SITE = "https://www.youtube.com"
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

    /** The watch page of [videoId], which may already be an address. */
    fun watchUrl(videoId: String): String = if (videoId.startsWith("http", ignoreCase = true)) videoId else "$SITE/watch?v=$videoId"

    fun playlistUrl(playlistId: String): String = "$SITE/playlist?list=$playlistId"

    /** A channel's page by id (`UC...`), `@handle`, `user/name`, `c/name`, a path, a bare name (taken as a handle) or an address. */
    fun channelUrl(idOrUrl: String): String {
        val text = idOrUrl.trim()
        return if (text.startsWith("http", ignoreCase = true)) text else "$SITE/${channelPath(text)}"
    }

    /** The path NewPipe's channel handlers take for a channel: `channel/UC...`, `@handle`, `user/name` or `c/name`. */
    fun channelPath(idOrUrl: String): String {
        val text = idOrUrl.trim()
        val key = if (text.startsWith("http", ignoreCase = true)) NewPipeMapper.channelKeyOf(text) ?: text else text.removePrefix("/")
        return when {
            key.startsWith("http", ignoreCase = true) -> key
            CHANNEL_ID.matches(key) -> "channel/$key"
            key.startsWith("@") || key.startsWith("channel/") || key.startsWith("user/") || key.startsWith("c/") -> key
            else -> "@$key"
        }
    }
}
