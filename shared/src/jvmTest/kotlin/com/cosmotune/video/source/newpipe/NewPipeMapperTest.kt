package com.cosmotune.video.source.newpipe

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.StreamKind
import com.cosmotune.video.model.VideoCodec
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.schabi.newpipe.extractor.stream.AudioStream as NpAudioStream
import org.schabi.newpipe.extractor.stream.Description as NpDescription
import org.schabi.newpipe.extractor.stream.VideoStream as NpVideoStream

/**
 * NewPipe's own objects, built in memory, as our models (plan task Y-4, step 4). The adapter gives nothing of NewPipe's to
 * the rest of Video, so every field of every model has its check here.
 */
class NewPipeMapperTest {

    private val now = 1_792_152_000_000L // 2026-10-16 12:00 UTC
    private val localization = Localization("en", "GB")

    private fun image(width: Int, height: Int = width * 9 / 16, url: String = "https://i.example/$width.jpg"): Image =
        Image(url, height, width, Image.ResolutionLevel.fromHeight(height))

    // ---------------------------------------------------------------- ids from addresses

    @Test
    fun videoIdsComeFromEveryShapeOfWatchAddress() {
        val id = "jNQXAC9IVRw"
        assertEquals(id, NewPipeMapper.videoIdOf("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://www.youtube.com/watch?feature=share&v=$id&t=30s"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://m.youtube.com/watch?v=$id#t=10"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://music.youtube.com/watch?v=$id&list=RDAMVM$id"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://youtu.be/$id?t=5"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://www.youtube.com/shorts/$id"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://www.youtube.com/embed/$id"))
        assertEquals(id, NewPipeMapper.videoIdOf("https://www.youtube.com/live/$id?feature=share"))
        assertEquals(id, NewPipeMapper.videoIdOf(id))
        assertNull(NewPipeMapper.videoIdOf("https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q"))
        assertNull(NewPipeMapper.videoIdOf("https://example.com/"))
        assertNull(NewPipeMapper.videoIdOf(""))
        assertNull(NewPipeMapper.videoIdOf(null))
    }

    @Test
    fun channelIdsComeFromChannelAddressesOnly() {
        val id = "UCsXVk37bltHxD1rDPwtNM8Q"
        assertEquals(id, NewPipeMapper.channelIdOf("https://www.youtube.com/channel/$id"))
        assertEquals(id, NewPipeMapper.channelIdOf("https://www.youtube.com/channel/$id/videos"))
        assertEquals(id, NewPipeMapper.channelIdOf("https://www.youtube.com/channel/$id?sub_confirmation=1"))
        assertEquals(id, NewPipeMapper.channelIdOf(id))
        assertNull(NewPipeMapper.channelIdOf("https://www.youtube.com/@kurzgesagt"))
        assertNull(NewPipeMapper.channelIdOf("https://www.youtube.com/user/kurzgesagt"))
        assertNull(NewPipeMapper.channelIdOf("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
        assertNull(NewPipeMapper.channelIdOf(null))
    }

    @Test
    fun playlistIdsComeFromTheListParameter() {
        assertEquals("PLabc123_-", NewPipeMapper.playlistIdOf("https://www.youtube.com/playlist?list=PLabc123_-"))
        assertEquals("RDjNQXAC9IVRw", NewPipeMapper.playlistIdOf("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=RDjNQXAC9IVRw&index=2"))
        assertNull(NewPipeMapper.playlistIdOf("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
        assertNull(NewPipeMapper.playlistIdOf(null))
    }

    // ---------------------------------------------------------------- pictures

    @Test
    fun theThumbnailIsTheSmallestAtLeast480WideElseTheLargest() {
        val images = listOf(image(168), image(336), image(720), image(1280))
        assertEquals("https://i.example/720.jpg", NewPipeMapper.bestImageUrl(images, 480))
        assertEquals("https://i.example/336.jpg", NewPipeMapper.bestImageUrl(listOf(image(168), image(336)), 480))
        assertEquals("https://i.example/480.jpg", NewPipeMapper.bestImageUrl(listOf(image(480), image(960)), 480))
        assertEquals("https://i.example/720.jpg", NewPipeMapper.bestImageUrl(listOf(image(1280), image(720), image(168)), 480))
        assertNull(NewPipeMapper.bestImageUrl(emptyList(), 480))
        assertNull(NewPipeMapper.bestImageUrl(null, 480))
    }

    @Test
    fun picturesOfUnknownWidthFallBackToTheirResolutionLevel() {
        val unknown = Image.WIDTH_UNKNOWN
        val low = Image("https://i.example/low.jpg", Image.HEIGHT_UNKNOWN, unknown, Image.ResolutionLevel.LOW)
        val high = Image("https://i.example/high.jpg", Image.HEIGHT_UNKNOWN, unknown, Image.ResolutionLevel.HIGH)
        val medium = Image("https://i.example/medium.jpg", Image.HEIGHT_UNKNOWN, unknown, Image.ResolutionLevel.MEDIUM)
        assertEquals("https://i.example/high.jpg", NewPipeMapper.bestImageUrl(listOf(low, high, medium), 480))
        // A picture of known width beats those of unknown width.
        assertEquals("https://i.example/336.jpg", NewPipeMapper.bestImageUrl(listOf(low, image(336), high), 480))
    }

    // ---------------------------------------------------------------- list items

    private fun streamItem(type: StreamType = StreamType.VIDEO_STREAM): StreamInfoItem =
        StreamInfoItem(0, "https://www.youtube.com/watch?v=jNQXAC9IVRw", "Me at the zoo", type).also {
            it.uploaderName = "jawed"
            it.uploaderUrl = "https://www.youtube.com/channel/UC4QobU6STFB0P71PMvOGN5A"
            it.uploaderAvatars = listOf(image(88), image(176))
            it.thumbnails = listOf(image(168), image(336), image(720))
            it.duration = 19
            it.viewCount = 358_000_000
            it.uploadDate = DateWrapper(Instant.ofEpochMilli(1_113_400_000_000L))
            it.textualUploadDate = "21 years ago"
        }

    @Test
    fun aVideoItemCarriesEveryField() {
        val v = NewPipeMapper.videoItem(streamItem(), now)
        assertEquals("jNQXAC9IVRw", v.id)
        assertEquals("Me at the zoo", v.title)
        assertEquals("UC4QobU6STFB0P71PMvOGN5A", v.channelId)
        assertEquals("jawed", v.channelName)
        assertEquals("https://i.example/176.jpg", v.channelAvatarUrl)
        assertEquals("https://i.example/720.jpg", v.thumbnailUrl)
        assertEquals(19L, v.durationSec)
        assertEquals(358_000_000L, v.viewCount)
        assertEquals(1_113_400_000_000L, v.uploadedAtMs)
        assertNull(v.uploadedText, "an exact date needs no text")
        assertFalse(v.isLive)
        assertFalse(v.isUpcoming)
        assertFalse(v.isShort)
    }

    @Test
    fun unknownDurationsAndCountsAreNull() {
        val item = StreamInfoItem(0, "https://www.youtube.com/watch?v=abcdefghijk", "x", StreamType.VIDEO_STREAM)
        val v = NewPipeMapper.videoItem(item, now)
        assertNull(v.durationSec)
        assertNull(v.viewCount)
        assertNull(v.uploadedAtMs)
        assertNull(v.uploadedText)
        assertNull(v.channelId)
        assertNull(v.channelName)
        assertNull(v.thumbnailUrl)
        assertNull(v.channelAvatarUrl)
    }

    @Test
    fun anApproximateDateKeepsTheSourcesOwnText() {
        val item = streamItem().also {
            it.uploadDate = DateWrapper(Instant.ofEpochMilli(now - 3 * 86_400_000L), true)
            it.textualUploadDate = "3 days ago"
        }
        val v = NewPipeMapper.videoItem(item, now)
        assertEquals(now - 3 * 86_400_000L, v.uploadedAtMs)
        assertEquals("3 days ago", v.uploadedText)
    }

    @Test
    fun aTextualDateWithoutATimeKeepsTheText() {
        val item = streamItem().also { it.uploadDate = null; it.textualUploadDate = "Streamed 2 weeks ago" }
        val v = NewPipeMapper.videoItem(item, now)
        assertNull(v.uploadedAtMs)
        assertEquals("Streamed 2 weeks ago", v.uploadedText)
    }

    @Test
    fun shortFormContentIsAShort() {
        val short = streamItem().also { it.setShortFormContent(true) }
        assertTrue(NewPipeMapper.videoItem(short, now).isShort)
    }

    @Test
    fun aLiveStreamIsLiveAndHasNoDuration() {
        val live = streamItem(StreamType.LIVE_STREAM).also { it.duration = -1 }
        val v = NewPipeMapper.videoItem(live, now)
        assertTrue(v.isLive)
        assertFalse(v.isUpcoming)
        assertNull(v.durationSec)
        val audioLive = streamItem(StreamType.AUDIO_LIVE_STREAM)
        assertTrue(NewPipeMapper.videoItem(audioLive, now).isLive)
    }

    @Test
    fun aPremiereInTheFutureIsUpcoming() {
        val premiere = streamItem().also {
            it.duration = -1
            it.uploadDate = DateWrapper(Instant.ofEpochMilli(now + 2 * 86_400_000L))
            it.textualUploadDate = "Premieres in 2 days"
        }
        val v = NewPipeMapper.videoItem(premiere, now)
        assertTrue(v.isUpcoming)
        assertFalse(v.isLive)
        assertNull(v.durationSec)
    }

    @Test
    fun aVideoWithADurationIsNeverUpcomingEvenWithAFutureDate() {
        val odd = streamItem().also { it.uploadDate = DateWrapper(Instant.ofEpochMilli(now + 86_400_000L)) }
        assertFalse(NewPipeMapper.videoItem(odd, now).isUpcoming)
    }

    @Test
    fun aChannelItemCarriesEveryField() {
        val item = ChannelInfoItem(0, "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt").also {
            it.thumbnails = listOf(image(88), image(176))
            it.subscriberCount = 22_900_000
            it.streamCount = 240
            it.description = "Videos explaining things"
            it.isVerified = true
        }
        val c = NewPipeMapper.channelItem(item)
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", c.id)
        assertEquals("Kurzgesagt", c.name)
        assertEquals("https://i.example/176.jpg", c.avatarUrl)
        assertEquals(22_900_000L, c.subscriberCount)
        assertEquals(240L, c.videoCount)
        assertEquals("Videos explaining things", c.description)
        assertTrue(c.verified)
        assertNull(c.handle)
    }

    @Test
    fun aChannelWithUnknownCountsHasNullCounts() {
        val c = NewPipeMapper.channelItem(ChannelInfoItem(0, "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "x"))
        assertNull(c.subscriberCount)
        assertNull(c.videoCount)
        assertNull(c.description)
        assertFalse(c.verified)
    }

    @Test
    fun aChannelAddressedByHandleKeepsTheHandle() {
        val item = ChannelInfoItem(0, "https://www.youtube.com/@kurzgesagt", "Kurzgesagt")
        val c = NewPipeMapper.channelItem(item)
        assertEquals("@kurzgesagt", c.handle)
    }

    @Test
    fun aPlaylistItemCarriesEveryField() {
        val item = PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PLabc123", "Best of").also {
            it.thumbnails = listOf(image(336), image(720))
            it.uploaderName = "Kurzgesagt"
            it.uploaderUrl = "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q"
            it.streamCount = 40
        }
        val p = NewPipeMapper.playlistItem(item)
        assertEquals("PLabc123", p.id)
        assertEquals("Best of", p.title)
        assertEquals("https://i.example/720.jpg", p.thumbnailUrl)
        assertEquals("Kurzgesagt", p.ownerName)
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", p.ownerId)
        assertEquals(40L, p.videoCount)
    }

    @Test
    fun aMixHasNoCount() {
        val mix = PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=RDjNQXAC9IVRw", "Mix").also { it.streamCount = -2 }
        assertNull(NewPipeMapper.playlistItem(mix).videoCount)
    }

    @Test
    fun aListItemIsAVideoAChannelOrAPlaylist() {
        assertIs<ListItem.Video>(NewPipeMapper.listItem(streamItem(), now))
        assertIs<ListItem.Channel>(NewPipeMapper.listItem(ChannelInfoItem(0, "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "x"), now))
        assertIs<ListItem.Playlist>(NewPipeMapper.listItem(PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PL1", "x"), now))
        // A video without an id in its address cannot be played, so it is dropped.
        assertNull(NewPipeMapper.listItem(StreamInfoItem(0, "https://example.com/nothing", "x", StreamType.VIDEO_STREAM), now))
    }

    // ---------------------------------------------------------------- streams

    private fun videoStream(
        itag: Int,
        url: String,
        videoOnly: Boolean = true,
        format: MediaFormat = MediaFormat.MPEG_4,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        bitrate: Int = 4_000_000,
        codec: String? = "avc1.640028",
        quality: String? = null,
        contentLength: Long = 52_000_000L,
        init: Pair<Int, Int> = 0 to 739,
        index: Pair<Int, Int> = 740 to 2_811,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        resolution: String = "${height}p",
        isUrl: Boolean = true,
    ): NpVideoStream {
        val item = ItagItem.getItag(itag)
        item.width = width
        item.height = height
        item.setFps(fps)
        item.bitrate = bitrate
        item.codec = codec
        item.quality = quality
        item.contentLength = contentLength
        item.initStart = init.first
        item.initEnd = init.second
        item.indexStart = index.first
        item.indexEnd = index.second
        return NpVideoStream.Builder()
            .setId(itag.toString())
            .setContent(url, isUrl)
            .setMediaFormat(format)
            .setDeliveryMethod(delivery)
            .setIsVideoOnly(videoOnly)
            .setResolution(resolution)
            .setItagItem(item)
            .build()
    }

    private fun audioStream(
        itag: Int,
        url: String,
        format: MediaFormat = MediaFormat.M4A,
        bitrate: Int = 129_000,
        average: Int = 128,
        codec: String? = "mp4a.40.2",
        trackId: String? = null,
        trackName: String? = null,
        locale: Locale? = null,
        type: AudioTrackType? = null,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        contentLength: Long = 9_000_000L,
    ): NpAudioStream {
        val item = ItagItem.getItag(itag)
        item.bitrate = bitrate
        item.codec = codec
        item.sampleRate = sampleRate
        item.audioChannels = channels
        item.contentLength = contentLength
        item.initStart = 0
        item.initEnd = 640
        item.indexStart = 641
        item.indexEnd = 1_904
        val builder = NpAudioStream.Builder()
            .setId(itag.toString())
            .setContent(url, true)
            .setMediaFormat(format)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(average)
            .setItagItem(item)
        if (trackId != null) builder.setAudioTrackId(trackId)
        if (trackName != null) builder.setAudioTrackName(trackName)
        if (locale != null) builder.setAudioLocale(locale)
        if (type != null) builder.setAudioTrackType(type)
        return builder.build()
    }

    private fun googlevideo(itag: Int, client: String? = "VISIONOS", expire: Long = 1_792_160_000L, extra: String = ""): String =
        "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=$expire&ei=EEE&ip=1.2.3.4&id=o-ABC&itag=$itag" +
            (client?.let { "&c=$it" } ?: "") + extra

    private fun streamInfo(type: StreamType = StreamType.VIDEO_STREAM): StreamInfo =
        StreamInfo(0, "https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://www.youtube.com/watch?v=jNQXAC9IVRw", type, "jNQXAC9IVRw", "Me at the zoo", 0)

    @Test
    fun aVideoOnlyStreamCarriesEveryField() {
        val np = videoStream(137, googlevideo(137))
        val s = assertNotNull(NewPipeMapper.videoStream(np))
        assertEquals(googlevideo(137), s.url)
        assertEquals(137, s.itag)
        assertEquals("video/mp4", s.mimeType)
        assertEquals(VideoCodec.H264, s.codec)
        assertEquals("avc1.640028", s.codecs)
        assertEquals(1920, s.width)
        assertEquals(1080, s.height)
        assertEquals(30, s.fps)
        assertEquals(4_000_000, s.bitrate)
        assertFalse(s.hdr)
        assertEquals(52_000_000L, s.contentLength)
        assertEquals(ByteRange(0, 739), s.init)
        assertEquals(ByteRange(740, 2_811), s.index)
        assertEquals(Delivery.PROGRESSIVE, s.delivery)
        assertFalse(s.hasAudio)
    }

    @Test
    fun theCodecComesFromTheCodecString() {
        fun codecOf(codecs: String?) = NewPipeMapper.videoCodecOf(codecs)
        assertEquals(VideoCodec.H264, codecOf("avc1.640028"))
        assertEquals(VideoCodec.H264, codecOf("avc1.4d401f"))
        assertEquals(VideoCodec.VP9, codecOf("vp9"))
        assertEquals(VideoCodec.VP9, codecOf("vp09.00.40.08"))
        assertEquals(VideoCodec.VP9, codecOf("vp09.02.51.10.01.09.16.09.00"))
        assertEquals(VideoCodec.AV1, codecOf("av01.0.12M.08"))
        assertEquals(VideoCodec.OTHER, codecOf("hev1.1.6.L93.B0"))
        assertEquals(VideoCodec.OTHER, codecOf(""))
        assertEquals(VideoCodec.OTHER, codecOf(null))
        assertEquals(AudioCodec.AAC, NewPipeMapper.audioCodecOf("mp4a.40.2"))
        assertEquals(AudioCodec.OPUS, NewPipeMapper.audioCodecOf("opus"))
        assertEquals(AudioCodec.OTHER, NewPipeMapper.audioCodecOf("ec-3"))
        assertEquals(AudioCodec.OTHER, NewPipeMapper.audioCodecOf(null))
    }

    @Test
    fun streamsOfEachCodecMap() {
        val vp9 = NewPipeMapper.videoStream(videoStream(248, googlevideo(248), format = MediaFormat.WEBM, codec = "vp9"))
        assertEquals(VideoCodec.VP9, vp9?.codec)
        assertEquals("video/webm", vp9?.mimeType)
        val av1 = NewPipeMapper.videoStream(videoStream(399, "https://rr1.googlevideo.com/videoplayback?itag=399", codec = "av01.0.08M.08"))
        assertEquals(VideoCodec.AV1, av1?.codec)
    }

    @Test
    fun hdrComesFromTheQualityLabelOrTheTransferCharacteristics() {
        assertTrue(NewPipeMapper.isHdr("2160p60 HDR", null))
        assertTrue(NewPipeMapper.isHdr("1080p HDR", "vp09.02.40.10"))
        assertTrue(NewPipeMapper.isHdr(null, "vp09.02.51.10.01.09.16.09.00"), "PQ transfer function")
        assertTrue(NewPipeMapper.isHdr(null, "vp09.02.51.10.01.09.18.09.00"), "HLG transfer function")
        assertTrue(NewPipeMapper.isHdr(null, "av01.0.13M.10.0.110.09.16.09.0"))
        assertFalse(NewPipeMapper.isHdr("1080p60", "avc1.640028"))
        assertFalse(NewPipeMapper.isHdr(null, "vp09.00.40.08"))
        assertFalse(NewPipeMapper.isHdr(null, "vp09.02.51.10.01.09.01.09.00"), "10 bits with an SDR transfer function")
        assertFalse(NewPipeMapper.isHdr(null, "av01.0.12M.08"))
        assertFalse(NewPipeMapper.isHdr(null, null))
        val s = NewPipeMapper.videoStream(videoStream(315, googlevideo(315), format = MediaFormat.WEBM, codec = "vp09.02.51.10.01.09.16.09.00", quality = "2160p60 HDR", height = 2160, width = 3840, fps = 60))
        assertTrue(s?.hdr == true)
    }

    @Test
    fun aMuxedStreamHasAudio() {
        val s = assertNotNull(NewPipeMapper.videoStream(videoStream(18, googlevideo(18), videoOnly = false, height = 360, width = 640, codec = "avc1.42001E")))
        assertTrue(s.hasAudio)
        assertEquals(360, s.height)
    }

    @Test
    fun unknownRangesAndLengthsAreNull() {
        val np = videoStream(137, googlevideo(137), init = -1 to -1, index = -1 to -1, contentLength = ItagItem.CONTENT_LENGTH_UNKNOWN)
        val s = assertNotNull(NewPipeMapper.videoStream(np))
        assertNull(s.init)
        assertNull(s.index)
        assertNull(s.contentLength)
        // The item's default is 0..0, which no real container header can be.
        val unset = assertNotNull(NewPipeMapper.videoStream(videoStream(137, googlevideo(137), init = 0 to 0, index = 0 to 0)))
        assertNull(unset.init)
        assertNull(unset.index)
        // Half of a pair is not a range.
        val half = assertNotNull(NewPipeMapper.videoStream(videoStream(137, googlevideo(137), init = 0 to 739, index = -1 to -1)))
        assertEquals(ByteRange(0, 739), half.init)
        assertNull(half.index)
    }

    @Test
    fun theHeightComesFromTheResolutionLabelWhenTheItemHasNone() {
        val np = videoStream(137, googlevideo(137), width = 0, height = 0, fps = 60, resolution = "1080p60")
        val s = assertNotNull(NewPipeMapper.videoStream(np))
        assertEquals(1080, s.height)
        assertEquals(1920, s.width)
        assertEquals(60, s.fps)
    }

    @Test
    fun anUnknownFrameRateIsZero() {
        val s = assertNotNull(NewPipeMapper.videoStream(videoStream(137, googlevideo(137), fps = ItagItem.FPS_NOT_APPLICABLE_OR_UNKNOWN)))
        assertEquals(0, s.fps)
    }

    @Test
    fun aStreamWhoseContentIsAManifestIsLeftOut() {
        val manifest = videoStream(137, "<MPD>...</MPD>", delivery = DeliveryMethod.DASH, isUrl = false)
        assertNull(NewPipeMapper.videoStream(manifest))
    }

    @Test
    fun deliveryFollowsTheSources() {
        assertEquals(Delivery.DASH, NewPipeMapper.videoStream(videoStream(137, googlevideo(137), delivery = DeliveryMethod.DASH))?.delivery)
        assertEquals(Delivery.HLS, NewPipeMapper.videoStream(videoStream(137, "https://manifest.googlevideo.com/api/manifest/hls_playlist", delivery = DeliveryMethod.HLS))?.delivery)
        assertNull(NewPipeMapper.videoStream(videoStream(137, googlevideo(137), delivery = DeliveryMethod.TORRENT)))
    }

    @Test
    fun anAudioStreamCarriesEveryField() {
        val np = audioStream(
            251, googlevideo(251), format = MediaFormat.WEBMA_OPUS, bitrate = 140_000, average = 160, codec = "opus",
            trackId = "hi.4", trackName = "Hindi", locale = Locale.forLanguageTag("hi"), type = AudioTrackType.DUBBED,
        )
        val a = assertNotNull(NewPipeMapper.audioStream(np))
        assertEquals(googlevideo(251), a.url)
        assertEquals(251, a.itag)
        assertEquals("audio/webm", a.mimeType)
        assertEquals(AudioCodec.OPUS, a.codec)
        assertEquals("opus", a.codecs)
        assertEquals(140_000, a.bitrate)
        assertEquals(44_100, a.sampleRate)
        assertEquals(2, a.channels)
        assertEquals(9_000_000L, a.contentLength)
        assertEquals(ByteRange(0, 640), a.init)
        assertEquals(ByteRange(641, 1_904), a.index)
        assertEquals(Delivery.PROGRESSIVE, a.delivery)
        assertEquals("hi.4", a.trackId)
        assertEquals("Hindi", a.trackName)
        assertEquals("hi", a.language)
        assertEquals(AudioTrackKind.DUBBED, a.trackKind)
    }

    @Test
    fun anAudioTrackWithoutDetailsIsUnknown() {
        val a = assertNotNull(NewPipeMapper.audioStream(audioStream(140, googlevideo(140))))
        assertNull(a.trackId)
        assertNull(a.trackName)
        assertNull(a.language)
        assertEquals(AudioTrackKind.UNKNOWN, a.trackKind)
        assertEquals(AudioCodec.AAC, a.codec)
    }

    @Test
    fun theAudioTrackKindsMap() {
        fun kindOf(type: AudioTrackType) = NewPipeMapper.audioStream(audioStream(140, googlevideo(140), type = type))?.trackKind
        assertEquals(AudioTrackKind.ORIGINAL, kindOf(AudioTrackType.ORIGINAL))
        assertEquals(AudioTrackKind.DUBBED, kindOf(AudioTrackType.DUBBED))
        assertEquals(AudioTrackKind.DESCRIPTIVE, kindOf(AudioTrackType.DESCRIPTIVE))
        assertEquals(AudioTrackKind.SECONDARY, kindOf(AudioTrackType.SECONDARY))
    }

    @Test
    fun theLocaleIsBcp47() {
        val a = NewPipeMapper.audioStream(audioStream(140, googlevideo(140), locale = Locale.forLanguageTag("pt-BR")))
        assertEquals("pt-BR", a?.language)
    }

    @Test
    fun aMissingAudioBitrateFallsBackToTheAverage() {
        val a = NewPipeMapper.audioStream(audioStream(140, googlevideo(140), bitrate = 0, average = 128))
        assertEquals(128_000, a?.bitrate)
    }

    @Test
    fun theStreamSetSplitsVideoOnlyMuxedAndAudioAndSetsTheExpiry() {
        val info = streamInfo().also {
            it.videoOnlyStreams = listOf(videoStream(137, googlevideo(137, expire = 1_792_160_000L)), videoStream(248, googlevideo(248, expire = 1_792_150_000L), format = MediaFormat.WEBM, codec = "vp9"))
            it.videoStreams = listOf(videoStream(18, googlevideo(18, expire = 1_792_170_000L), videoOnly = false, height = 360, width = 640))
            it.audioStreams = listOf(audioStream(140, googlevideo(140, expire = 1_792_165_000L)))
            it.duration = 19
        }
        val set = NewPipeMapper.streamSet(info, localization)
        assertEquals(listOf(137, 248), set.videoOnly.map { it.itag })
        assertEquals(listOf(18), set.muxed.map { it.itag })
        assertEquals(listOf(140), set.audio.map { it.itag })
        assertEquals(1_792_150_000_000L, set.expiresAtMs, "the earliest address decides")
        assertEquals(19_000L, set.durationMs)
        assertNull(set.hlsUrl)
        assertNull(set.dashMpdUrl)
    }

    @Test
    fun theExpiryIsReadFromTheExpireParameterOnly() {
        assertEquals(1_792_160_000_000L, NewPipeMapper.expiresAtMsOf(listOf("https://a.googlevideo.com/videoplayback?expire=1792160000&itag=1")))
        assertEquals(1_792_160_000_000L, NewPipeMapper.expiresAtMsOf(listOf("https://a.googlevideo.com/videoplayback?itag=1&expire=1792160000")))
        assertNull(NewPipeMapper.expiresAtMsOf(listOf("https://a.googlevideo.com/videoplayback?xexpire=1792160000&itag=1")))
        assertNull(NewPipeMapper.expiresAtMsOf(listOf("https://a.googlevideo.com/videoplayback?expire=soon")))
        assertNull(NewPipeMapper.expiresAtMsOf(emptyList()))
        assertEquals(1_792_160_000_000L, NewPipeMapper.expiresAtMsOf(listOf("https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1792160000/ei/EEE/index.m3u8")))
    }

    @Test
    fun theRequestHeadersCarryTheUserAgentOfTheClientThatMintedTheAddresses() {
        fun headersFor(client: String?): Map<String, String> {
            val info = streamInfo().also {
                it.videoOnlyStreams = listOf(videoStream(137, googlevideo(137, client = client)))
                it.audioStreams = listOf(audioStream(140, googlevideo(140, client = client)))
            }
            return NewPipeMapper.streamSet(info, localization).requestHeaders
        }
        assertEquals(mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(localization)), headersFor("VISIONOS"))
        assertEquals(mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(localization)), headersFor("ANDROID"))
        assertEquals(mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(localization)), headersFor("IOS"))
        assertEquals(mapOf("User-Agent" to VideoDownloader.DEFAULT_USER_AGENT), headersFor("WEB"))
        assertEquals(mapOf("User-Agent" to VideoDownloader.DEFAULT_USER_AGENT), headersFor(null))
    }

    @Test
    fun theUserAgentUsesTheCountryOfTheLocalization() {
        val india = Localization("hi", "IN")
        assertTrue(NewPipeMapper.userAgentFor("VISIONOS", india).contains("IN"))
        assertEquals(YoutubeParsingHelper.getAndroidUserAgent(india), NewPipeMapper.userAgentFor("ANDROID", india))
    }

    @Test
    fun aLiveStreamGivesTheHlsAddress() {
        val hls = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1792160000/ei/EEE/index.m3u8"
        val info = streamInfo(StreamType.LIVE_STREAM).also { it.hlsUrl = hls; it.dashMpdUrl = "https://manifest.googlevideo.com/api/manifest/dash/expire/1792161000/x" }
        val set = NewPipeMapper.streamSet(info, localization)
        assertEquals(hls, set.hlsUrl)
        assertEquals("https://manifest.googlevideo.com/api/manifest/dash/expire/1792161000/x", set.dashMpdUrl)
        assertEquals(1_792_160_000_000L, set.expiresAtMs)
        assertNull(set.durationMs)
        assertTrue(set.videoOnly.isEmpty() && set.muxed.isEmpty() && set.audio.isEmpty())
    }

    @Test
    fun theStreamKindFollowsTheStreamType() {
        assertEquals(StreamKind.VIDEO, NewPipeMapper.streamKind(StreamType.VIDEO_STREAM))
        assertEquals(StreamKind.LIVE, NewPipeMapper.streamKind(StreamType.LIVE_STREAM))
        assertEquals(StreamKind.LIVE, NewPipeMapper.streamKind(StreamType.AUDIO_LIVE_STREAM))
        assertEquals(StreamKind.POST_LIVE, NewPipeMapper.streamKind(StreamType.POST_LIVE_STREAM))
        assertEquals(StreamKind.POST_LIVE, NewPipeMapper.streamKind(StreamType.POST_LIVE_AUDIO_STREAM))
        assertEquals(StreamKind.AUDIO, NewPipeMapper.streamKind(StreamType.AUDIO_STREAM))
        assertEquals(StreamKind.VIDEO, NewPipeMapper.streamKind(StreamType.NONE))
    }

    // ---------------------------------------------------------------- the rest of the watch page

    @Test
    fun chaptersCarryTheirStartInMillisecondsAndPreview() {
        val first = StreamSegment("Intro", 0)
        val second = StreamSegment("The zoo", 75).also { it.previewUrl = "https://i.ytimg.com/vi/x/hq2.jpg" }
        val chapters = NewPipeMapper.chapters(listOf(second, first))
        assertEquals(listOf("Intro", "The zoo"), chapters.map { it.title })
        assertEquals(listOf(0L, 75_000L), chapters.map { it.startMs })
        assertNull(chapters[0].thumbnailUrl)
        assertEquals("https://i.ytimg.com/vi/x/hq2.jpg", chapters[1].thumbnailUrl)
        assertTrue(NewPipeMapper.chapters(null).isEmpty())
    }

    private fun subtitle(url: String, format: MediaFormat = MediaFormat.TTML, language: String = "en", auto: Boolean = false): SubtitlesStream =
        SubtitlesStream.Builder().setContent(url, true).setMediaFormat(format).setLanguageCode(language).setAutoGenerated(auto).build()

    @Test
    fun aTimedTextSubtitleIsAskedForInVtt() {
        val t = NewPipeMapper.subtitle(subtitle("https://www.youtube.com/api/timedtext?v=abc&lang=en&fmt=ttml"))
        assertEquals("https://www.youtube.com/api/timedtext?v=abc&lang=en&fmt=vtt", t.url)
        assertEquals("text/vtt", t.mimeType)
        assertEquals("en", t.language)
        assertEquals("English", t.name)
        assertFalse(t.autoGenerated)

        val middle = NewPipeMapper.subtitle(subtitle("https://www.youtube.com/api/timedtext?fmt=ttml&v=abc&lang=de", language = "de", auto = true))
        assertEquals("https://www.youtube.com/api/timedtext?fmt=vtt&v=abc&lang=de", middle.url)
        assertTrue(middle.autoGenerated)

        val none = NewPipeMapper.subtitle(subtitle("https://www.youtube.com/api/timedtext?v=abc&lang=en"))
        assertEquals("https://www.youtube.com/api/timedtext?v=abc&lang=en&fmt=vtt", none.url)
    }

    @Test
    fun aSubtitleFromAnotherPlaceKeepsItsFormat() {
        val t = NewPipeMapper.subtitle(subtitle("https://subs.example.com/en.ttml"))
        assertEquals("https://subs.example.com/en.ttml", t.url)
        assertEquals("application/ttml+xml", t.mimeType)
        val vtt = NewPipeMapper.subtitle(subtitle("https://subs.example.com/en.vtt", format = MediaFormat.VTT))
        assertEquals("text/vtt", vtt.mimeType)
        assertEquals("https://subs.example.com/en.vtt", vtt.url)
    }

    @Test
    fun theStoryboardIsTheFramesetWithTheLargestFrames() {
        val small = Frameset(listOf("s0", "s1"), 80, 45, 120, 10_000, 10, 10)
        val large = Frameset(listOf("l0", "l1", "l2"), 160, 90, 150, 8_000, 5, 5)
        val medium = Frameset(listOf("m0"), 106, 60, 200, 5_000, 10, 10)
        val sb = assertNotNull(NewPipeMapper.storyboard(listOf(small, large, medium)))
        assertEquals(listOf("l0", "l1", "l2"), sb.urls)
        assertEquals(160, sb.frameWidth)
        assertEquals(90, sb.frameHeight)
        assertEquals(5, sb.columns)
        assertEquals(5, sb.rows)
        assertEquals(8_000L, sb.intervalMs)
        assertEquals(150, sb.totalFrames)
        assertNull(NewPipeMapper.storyboard(emptyList()))
        assertNull(NewPipeMapper.storyboard(null))
    }

    @Test
    fun theDetailsCarryEverythingTheWatchPageShows() {
        val related = listOf<org.schabi.newpipe.extractor.InfoItem>(
            streamItem(),
            ChannelInfoItem(0, "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt"),
            PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PL1", "List"),
        )
        val info = streamInfo().also {
            it.thumbnails = listOf(image(336), image(720))
            it.uploaderName = "jawed"
            it.uploaderUrl = "https://www.youtube.com/channel/UC4QobU6STFB0P71PMvOGN5A"
            it.uploaderAvatars = listOf(image(48), image(176))
            it.uploaderSubscriberCount = 1_200_000
            it.duration = 19
            it.viewCount = 358_000_000
            it.likeCount = 13_000_000
            it.category = "Entertainment"
            it.tags = listOf("zoo", "elephants")
            it.uploadDate = DateWrapper(Instant.ofEpochMilli(1_113_400_000_000L))
            it.textualUploadDate = "Apr 23, 2005"
            it.description = NpDescription("All right, so here we are<br>in front of the elephants", NpDescription.HTML)
            it.streamSegments = listOf(StreamSegment("Intro", 0), StreamSegment("Elephants", 8))
            it.subtitles = listOf(subtitle("https://www.youtube.com/api/timedtext?v=jNQXAC9IVRw&lang=en&fmt=ttml"))
            it.previewFrames = listOf(Frameset(listOf("u0"), 160, 90, 100, 1_000, 10, 10))
            it.relatedItems = related
            it.videoOnlyStreams = listOf(videoStream(137, googlevideo(137)))
            it.audioStreams = listOf(audioStream(140, googlevideo(140)))
        }
        val d = NewPipeMapper.details(info, now, localization)
        assertEquals("jNQXAC9IVRw", d.item.id)
        assertEquals("Me at the zoo", d.item.title)
        assertEquals("UC4QobU6STFB0P71PMvOGN5A", d.item.channelId)
        assertEquals("jawed", d.item.channelName)
        assertEquals("https://i.example/176.jpg", d.item.channelAvatarUrl)
        assertEquals("https://i.example/720.jpg", d.item.thumbnailUrl)
        assertEquals(19L, d.item.durationSec)
        assertEquals(358_000_000L, d.item.viewCount)
        assertEquals(1_113_400_000_000L, d.item.uploadedAtMs)
        assertFalse(d.item.isLive)
        assertEquals("All right, so here we are\nin front of the elephants", d.description.text)
        assertEquals(13_000_000L, d.likeCount)
        assertEquals("Entertainment", d.category)
        assertEquals(listOf("zoo", "elephants"), d.tags)
        assertEquals(listOf("Intro", "Elephants"), d.chapters.map { it.title })
        assertEquals(listOf(8_000L), d.chapters.drop(1).map { it.startMs })
        assertEquals(3, d.related.size)
        assertIs<ListItem.Video>(d.related[0])
        assertIs<ListItem.Channel>(d.related[1])
        assertIs<ListItem.Playlist>(d.related[2])
        assertEquals(1, d.subtitles.size)
        assertEquals("text/vtt", d.subtitles[0].mimeType)
        assertEquals(listOf("u0"), d.storyboard?.urls)
        assertEquals(StreamKind.VIDEO, d.kind)
        assertEquals(listOf(137), d.streams.videoOnly.map { it.itag })
        assertEquals(1_200_000L, d.subscriberCount)
        assertEquals(now, d.fetchedAtMs)
    }

    @Test
    fun theDetailsOfASparseVideoHaveNullsAndEmptyLists() {
        val d = NewPipeMapper.details(streamInfo(), now, localization)
        assertNull(d.likeCount)
        assertNull(d.category)
        assertNull(d.subscriberCount)
        assertNull(d.item.viewCount)
        assertNull(d.item.durationSec)
        assertNull(d.storyboard)
        assertEquals("", d.description.text)
        assertTrue(d.description.links.isEmpty())
        assertTrue(d.tags.isEmpty() && d.chapters.isEmpty() && d.related.isEmpty() && d.subtitles.isEmpty())
    }

    @Test
    fun aLiveStreamInfoIsLive() {
        val info = streamInfo(StreamType.LIVE_STREAM).also { it.hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1792160000/x/index.m3u8" }
        val d = NewPipeMapper.details(info, now, localization)
        assertEquals(StreamKind.LIVE, d.kind)
        assertTrue(d.item.isLive)
        assertNotNull(d.streams.hlsUrl)
    }

    // ---------------------------------------------------------------- the description

    @Test
    fun anHtmlDescriptionBecomesTextWithLinksAndTimestamps() {
        val html = "Chapters:<br>0:00 Intro<br>1:30 <a href=\"https://www.youtube.com/watch?v=jNQXAC9IVRw&t=90s\">The zoo</a><br>" +
            "Visit <a href=\"https://example.com/x?a=1&amp;b=2\">https://example.com/x?a=1&amp;b=2</a> &amp; subscribe"
        val d = DescriptionParser.parse(html, NpDescription.HTML)
        assertEquals("Chapters:\n0:00 Intro\n1:30 The zoo\nVisit https://example.com/x?a=1&b=2 & subscribe", d.text)

        fun textOf(l: com.cosmotune.video.model.DescriptionLink) = d.text.substring(l.start, l.end)
        val byText = d.links.associateBy { textOf(it) }
        // "0:00" and "1:30" are m:ss patterns; "The zoo" is a watch link with t=90s.
        assertEquals(0L, byText["0:00"]?.timestampMs)
        assertNull(byText["0:00"]?.url)
        assertEquals(90_000L, byText["1:30"]?.timestampMs)
        assertEquals(90_000L, byText["The zoo"]?.timestampMs)
        assertNull(byText["The zoo"]?.url)
        val web = byText["https://example.com/x?a=1&b=2"]
        assertEquals("https://example.com/x?a=1&b=2", web?.url)
        assertNull(web?.timestampMs)
        assertEquals(d.links.sortedBy { it.start }, d.links, "links are in text order")
        assertEquals(4, d.links.size)
    }

    @Test
    fun timestampLinksReadTheTParameterInAllItsForms() {
        fun tsOf(href: String): Long? {
            val d = DescriptionParser.parse("<a href=\"$href\">jump</a>", NpDescription.HTML)
            return d.links.singleOrNull()?.timestampMs
        }
        assertEquals(90_000L, tsOf("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=90s"))
        assertEquals(90_000L, tsOf("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=90"))
        assertEquals(90_000L, tsOf("https://www.youtube.com/watch?v=jNQXAC9IVRw#t=90"))
        assertEquals(3_723_000L, tsOf("https://youtu.be/jNQXAC9IVRw?t=1h2m3s"))
        assertEquals(125_000L, tsOf("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=2m5s"))
        assertNull(tsOf("https://example.com/page?t=90"), "only the video site's links are moments of the video")
    }

    @Test
    fun aPlainTextDescriptionGetsLinksAndTimestampsFromItsText() {
        val text = "Intro 0:00\nPart two 12:34\nAt 1:02:03 it ends. Go to https://example.com/a, then https://example.org/b."
        val d = DescriptionParser.parse(text, NpDescription.PLAIN_TEXT)
        assertEquals(text, d.text)
        fun textOf(l: com.cosmotune.video.model.DescriptionLink) = d.text.substring(l.start, l.end)
        val times = d.links.filter { it.timestampMs != null }
        assertEquals(listOf("0:00", "12:34", "1:02:03"), times.map { textOf(it) })
        assertEquals(listOf(0L, 754_000L, 3_723_000L), times.map { it.timestampMs })
        val urls = d.links.filter { it.url != null }
        assertEquals(listOf("https://example.com/a", "https://example.org/b"), urls.map { textOf(it) })
        assertEquals(listOf("https://example.com/a", "https://example.org/b"), urls.map { it.url })
    }

    @Test
    fun thingsThatLookLikeTimesButAreNotAreLeftAlone() {
        val d = DescriptionParser.parse("Call 12:345 or 1:2 or v1.2:30x, ratio 16:9 and 99:99", NpDescription.PLAIN_TEXT)
        assertTrue(d.links.isEmpty(), d.links.toString())
    }

    @Test
    fun aMarkdownDescriptionIsTreatedAsPlainText() {
        val d = DescriptionParser.parse("Chapter 0:30 here", NpDescription.MARKDOWN)
        assertEquals("Chapter 0:30 here", d.text)
        assertEquals(1, d.links.size)
    }

    @Test
    fun aMissingDescriptionIsEmpty() {
        val d = NewPipeMapper.description(null)
        assertEquals("", d.text)
        assertTrue(d.links.isEmpty())
        assertEquals("", NewPipeMapper.description(NpDescription.EMPTY_DESCRIPTION).text)
    }

    @Test
    fun htmlEntitiesAndTagsAreDecoded() {
        val d = DescriptionParser.parse("Tom &amp; Jerry &lt;3 &quot;cats&quot;<br><b>bold</b> <i>italic</i> &#39;x&#39; &nbsp;end", NpDescription.HTML)
        assertEquals("Tom & Jerry <3 \"cats\"\nbold italic 'x'  end", d.text)
    }

    @Test
    fun aRelativeLinkPointsAtTheSite() {
        val d = DescriptionParser.parse("<a href=\"/hashtag/zoo\">#zoo</a>", NpDescription.HTML)
        assertEquals("https://www.youtube.com/hashtag/zoo", d.links.single().url)
    }

    // ---------------------------------------------------------------- comments

    private fun comment(): CommentsInfoItem = CommentsInfoItem(0, "https://www.youtube.com/watch?v=jNQXAC9IVRw&lc=Ugx1", "Ada").also {
        it.commentId = "Ugx1"
        it.uploaderName = "Ada Lovelace"
        it.uploaderUrl = "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q"
        it.uploaderAvatars = listOf(image(48), image(88))
        it.commentText = NpDescription("Great video<br>see <a href=\"https://www.youtube.com/watch?v=jNQXAC9IVRw&t=83s\">1:23</a> &amp; more", NpDescription.HTML)
        it.likeCount = 1_200
        it.textualUploadDate = "3 days ago"
        it.setPinned(true)
        it.setHeartedByUploader(true)
        it.setChannelOwner(true)
        it.replyCount = 5
        it.setReplies(Page("https://www.youtube.com/youtubei/v1/next", "reply-token", null, null, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun aCommentCarriesEveryField() {
        val c = NewPipeMapper.comment(comment(), "jNQXAC9IVRw")
        assertEquals("Ugx1", c.id)
        assertEquals("Ada Lovelace", c.authorName)
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", c.authorChannelId)
        assertEquals("https://i.example/88.jpg", c.authorAvatarUrl)
        assertEquals("Great video\nsee 1:23 & more", c.text)
        assertEquals(1_200L, c.likeCount)
        assertEquals("3 days ago", c.publishedText)
        assertTrue(c.pinned)
        assertTrue(c.heartedByCreator)
        assertTrue(c.byCreator)
        assertFalse(c.edited)
        assertEquals(5, c.replyCount)
        val token = assertNotNull(c.replies)
        val decoded = PageCodec.decode(token)
        assertEquals("reply-token", decoded.page.id)
        assertEquals("jNQXAC9IVRw", decoded.context, "the replies token knows its video")
    }

    @Test
    fun aCommentWithoutRepliesHasNoToken() {
        val plain = CommentsInfoItem(0, "https://www.youtube.com/watch?v=jNQXAC9IVRw&lc=Ugx2", "Bob").also { it.commentId = "Ugx2"; it.likeCount = CommentsInfoItem.NO_LIKE_COUNT }
        val c = NewPipeMapper.comment(plain, "jNQXAC9IVRw")
        assertNull(c.replies)
        assertEquals(0, c.replyCount)
        assertNull(c.likeCount)
        assertFalse(c.pinned || c.heartedByCreator || c.byCreator || c.edited)
        assertNull(c.authorChannelId)
    }

    @Test
    fun anUnknownReplyCountWithARepliesPageIsAtLeastOne() {
        val item = comment().also { it.replyCount = CommentsInfoItem.UNKNOWN_REPLY_COUNT }
        assertEquals(1, NewPipeMapper.comment(item, "jNQXAC9IVRw").replyCount)
    }

    @Test
    fun theEditedMarkIsReadFromTheTimeText() {
        val item = comment().also { it.textualUploadDate = "3 days ago (edited)" }
        val c = NewPipeMapper.comment(item, "jNQXAC9IVRw")
        assertTrue(c.edited)
        assertEquals("3 days ago", c.publishedText)
    }

    // ---------------------------------------------------------------- channel

    private fun channelInfo(): ChannelInfo =
        ChannelInfo(0, "UCsXVk37bltHxD1rDPwtNM8Q", "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "https://www.youtube.com/@kurzgesagt", "Kurzgesagt").also {
            it.avatars = listOf(image(88), image(176), image(900))
            it.banners = listOf(image(1060, 175), image(1707, 283), image(2120, 351))
            it.subscriberCount = 22_900_000
            it.description = "Videos explaining things"
            it.isVerified = true
            it.donationLinks = arrayOf("https://example.com/shop", "https://example.org/")
            it.tabs = listOf(
                ListLinkHandler("u/videos", "u/videos", "channel/UCsXVk37bltHxD1rDPwtNM8Q", listOf("videos"), ""),
                ListLinkHandler("u/shorts", "u/shorts", "channel/UCsXVk37bltHxD1rDPwtNM8Q", listOf("shorts"), ""),
                ListLinkHandler("u/live", "u/live", "channel/UCsXVk37bltHxD1rDPwtNM8Q", listOf("livestreams"), ""),
                ListLinkHandler("u/pl", "u/pl", "channel/UCsXVk37bltHxD1rDPwtNM8Q", listOf("playlists"), ""),
                ListLinkHandler("u/albums", "u/albums", "channel/UCsXVk37bltHxD1rDPwtNM8Q", listOf("albums"), ""),
            )
        }

    @Test
    fun aChannelPageCarriesTheHeaderAndTheTabsTheChannelHas() {
        val page = NewPipeMapper.channelPage(channelInfo())
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", page.channel.id)
        assertEquals("Kurzgesagt", page.channel.name)
        assertEquals("https://i.example/176.jpg", page.channel.avatarUrl)
        assertEquals(22_900_000L, page.channel.subscriberCount)
        assertEquals("Videos explaining things", page.channel.description)
        assertTrue(page.channel.verified)
        assertEquals("@kurzgesagt", page.channel.handle)
        assertEquals("https://i.example/1060.jpg", page.bannerUrl)
        assertEquals(listOf(ChannelTab.VIDEOS, ChannelTab.SHORTS, ChannelTab.LIVE, ChannelTab.PLAYLISTS), page.tabs)
        assertEquals(listOf("https://example.com/shop", "https://example.org/"), page.links)
        assertNull(page.joinedText)
    }

    @Test
    fun aChannelThatHasOnlyVideosHasOnlyThatTab() {
        val info = channelInfo().also {
            it.tabs = listOf(ListLinkHandler("u/videos", "u/videos", "channel/UCsXVk37bltHxD1rDPwtNM8Q", listOf("videos"), ""))
            it.donationLinks = null
            it.banners = emptyList()
            it.setSubscriberCount(-1)
        }
        val page = NewPipeMapper.channelPage(info)
        assertEquals(listOf(ChannelTab.VIDEOS), page.tabs)
        assertTrue(page.links.isEmpty())
        assertNull(page.bannerUrl)
        assertNull(page.channel.subscriberCount)
    }

    @Test
    fun theChannelIdIsNormalised() {
        val info = ChannelInfo(0, "channel/UCsXVk37bltHxD1rDPwtNM8Q", "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", "x")
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", NewPipeMapper.channelPage(info).channel.id)
    }
}
