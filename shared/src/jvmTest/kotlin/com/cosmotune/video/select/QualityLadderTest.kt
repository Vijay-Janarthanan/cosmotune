package com.cosmotune.video.select

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.DecoderCaps
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.settings.CodecPreference
import com.cosmotune.video.settings.VideoSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------------------------------------------------
// Shared by the three tests of this package: the three sample stream lists, three capability sets and small builders.
// ---------------------------------------------------------------------------------------------------------------------

/** The sample stream lists in `src/jvmTest/resources/video/select/` (made up: no real address, no network). */
internal object SampleStreams {
    /** A 4K60 video with AV1, VP9 and H.264 ladders, two HDR streams, a muxed 360p and AAC and Opus audio. */
    val uhd: StreamSet get() = load("uhd60-av1-vp9-h264")

    /** A 1080p video that only has H.264 and AAC. */
    val fhd: StreamSet get() = load("fhd-h264-only")

    /** A video that only has one muxed 360p stream. */
    val sd: StreamSet get() = load("sd-muxed-only")

    fun load(name: String): StreamSet {
        val text = checkNotNull(SampleStreams::class.java.getResourceAsStream("/video/select/$name.json")) { "video/select/$name.json is missing" }
            .bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonObject
        val seconds = root.getValue("durationMs").jsonPrimitive.int / 1000
        return StreamSet(
            videoOnly = root.getValue("videoOnly").jsonArray.map { videoOf(it.jsonObject, seconds, hasAudio = false) },
            muxed = root.getValue("muxed").jsonArray.map { videoOf(it.jsonObject, seconds, hasAudio = true) },
            audio = root.getValue("audio").jsonArray.map { audioOf(it.jsonObject, seconds) },
            hlsUrl = null,
            dashMpdUrl = null,
            expiresAtMs = null,
            requestHeaders = emptyMap(),
            durationMs = seconds * 1000L,
        )
    }

    private fun videoOf(o: JsonObject, seconds: Int, hasAudio: Boolean): VideoStream {
        val kbps = o.getValue("kbps").jsonPrimitive.int
        return videoStream(
            itag = o.getValue("itag").jsonPrimitive.int,
            codec = VideoCodec.valueOf(o.getValue("codec").jsonPrimitive.content),
            width = o.getValue("w").jsonPrimitive.int,
            height = o.getValue("h").jsonPrimitive.int,
            fps = o.getValue("fps").jsonPrimitive.int,
            kbps = kbps,
            hdr = o["hdr"]?.jsonPrimitive?.booleanOrNull ?: false,
            ranges = o["ranges"]?.jsonPrimitive?.booleanOrNull ?: true,
            hasAudio = hasAudio,
            mimeType = o.getValue("mime").jsonPrimitive.content,
            codecs = o.getValue("codecs").jsonPrimitive.content,
            seconds = seconds,
        )
    }

    private fun audioOf(o: JsonObject, seconds: Int): AudioStream = audioStream(
        itag = o.getValue("itag").jsonPrimitive.int,
        codec = AudioCodec.valueOf(o.getValue("codec").jsonPrimitive.content),
        kbps = o.getValue("kbps").jsonPrimitive.int,
        trackId = o["track"]?.jsonPrimitive?.contentOrNull,
        language = o["lang"]?.jsonPrimitive?.contentOrNull,
        kind = o["kind"]?.jsonPrimitive?.contentOrNull?.let { AudioTrackKind.valueOf(it) } ?: AudioTrackKind.ORIGINAL,
        mimeType = o.getValue("mime").jsonPrimitive.content,
        codecs = o.getValue("codecs").jsonPrimitive.content,
        sampleRate = o["rate"]?.jsonPrimitive?.intOrNull,
        channels = o["ch"]?.jsonPrimitive?.intOrNull,
        seconds = seconds,
    )
}

/** The capability sets of the plan's step 1 and the HDR variants the tests need. */
internal object Caps {
    private fun all(maxWidth: Int, maxHeight: Int, fps: Int, hardware: Boolean, vararg codecs: VideoCodec) =
        codecs.map { DecoderCaps(it, maxWidth, maxHeight, fps, hardware) }

    /** A phone with hardware AV1 (and VP9 and H.264) to 4K60; a 1080p-high display; side-loaded DASH. */
    val phoneAv1 = EngineCapabilities(
        video = all(3840, 2160, 60, true, VideoCodec.AV1, VideoCodec.VP9, VideoCodec.H264),
        opus = true, hdrDisplay = false, displayHeightPx = 1080, sideloadedDash = true, autoCeilingHeight = null,
    )

    /** A phone with hardware H.264 and VP9 to 1080p60 and no AV1. */
    val phoneH264Vp9 = EngineCapabilities(
        video = all(1920, 1080, 60, true, VideoCodec.H264, VideoCodec.VP9),
        opus = true, hdrDisplay = false, displayHeightPx = 1080, sideloadedDash = true, autoCeilingHeight = null,
    )

    /** The desktop: software everything to 4K60, a 2160-high display, no side-loaded DASH, Auto stops at 1440. */
    val desktop = EngineCapabilities(
        video = all(3840, 2160, 60, false, VideoCodec.AV1, VideoCodec.VP9, VideoCodec.H264),
        opus = true, hdrDisplay = false, displayHeightPx = 2160, sideloadedDash = false, autoCeilingHeight = 1440,
    )

    /** The three, with their names for failure messages. */
    val all = listOf("phoneAv1" to phoneAv1, "phoneH264Vp9" to phoneH264Vp9, "desktop" to desktop)
}

internal fun videoStream(
    itag: Int,
    codec: VideoCodec,
    width: Int,
    height: Int,
    fps: Int = 30,
    kbps: Int = 1000,
    hdr: Boolean = false,
    ranges: Boolean = true,
    hasAudio: Boolean = false,
    mimeType: String = if (codec == VideoCodec.VP9) "video/webm" else "video/mp4",
    codecs: String = when (codec) { VideoCodec.VP9 -> "vp9"; VideoCodec.AV1 -> "av01.0.08M.08"; else -> "avc1.640028" },
    seconds: Int = 100,
    url: String = "https://media.cosmotune.invalid/videoplayback?id=sample&itag=$itag&c=WEB",
) = VideoStream(
    url = url, itag = itag, mimeType = mimeType, codec = codec, codecs = codecs, width = width, height = height, fps = fps,
    bitrate = kbps * 1000, hdr = hdr, contentLength = kbps * 125L * seconds,
    init = if (ranges) ByteRange(0, 739) else null, index = if (ranges) ByteRange(740, 2_811) else null,
    delivery = Delivery.PROGRESSIVE /* what the NewPipe adapter gives, with or without ranges */, hasAudio = hasAudio,
)

internal fun audioStream(
    itag: Int,
    codec: AudioCodec,
    kbps: Int,
    trackId: String? = null,
    language: String? = "en",
    kind: AudioTrackKind = AudioTrackKind.ORIGINAL,
    ranges: Boolean = true,
    mimeType: String = if (codec == AudioCodec.OPUS) "audio/webm" else "audio/mp4",
    codecs: String = if (codec == AudioCodec.OPUS) "opus" else "mp4a.40.2",
    sampleRate: Int? = 48_000,
    channels: Int? = 2,
    seconds: Int = 100,
    url: String = "https://media.cosmotune.invalid/videoplayback?id=sample&itag=$itag&c=WEB" + (trackId?.let { "&xtags=$it" } ?: ""),
) = AudioStream(
    url = url, itag = itag, mimeType = mimeType, codec = codec, codecs = codecs, bitrate = kbps * 1000, sampleRate = sampleRate,
    channels = channels, contentLength = kbps * 125L * seconds, init = if (ranges) ByteRange(0, 640) else null,
    index = if (ranges) ByteRange(641, 1_904) else null, delivery = Delivery.PROGRESSIVE,
    trackId = trackId, trackName = trackId?.let { "Track $it" }, language = language, trackKind = kind,
)

internal fun streamSet(
    videoOnly: List<VideoStream> = emptyList(),
    muxed: List<VideoStream> = emptyList(),
    audio: List<AudioStream> = emptyList(),
    hlsUrl: String? = null,
    durationMs: Long? = 100_000,
) = StreamSet(
    videoOnly = videoOnly, muxed = muxed, audio = audio, hlsUrl = hlsUrl, dashMpdUrl = null, expiresAtMs = null,
    requestHeaders = emptyMap(), durationMs = durationMs,
)

internal fun List<QualityOption>.labels() = map { it.label }

// ---------------------------------------------------------------------------------------------------------------------

/** The quality ladder (plan task Y-5, step 1; spec 6.4): three sample lists against three capability sets, and the rules behind them. */
class QualityLadderTest {

    private val allHeights = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

    // ------------------------------------------------------------------ the 3 x 3 of the plan

    @Test
    fun a4k60VideoOnAPhoneWithHardwareAv1OffersEveryRungInAv1() {
        val ladder = QualityLadder.of(SampleStreams.uhd, Caps.phoneAv1)
        assertEquals(listOf("2160p60", "1440p60", "1080p60", "720p60", "480p", "360p", "240p", "144p"), ladder.labels())
        assertEquals(allHeights, ladder.map { it.height })
        assertTrue(ladder.all { it.codec == VideoCodec.AV1 && !it.hdr })
    }

    @Test
    fun a4k60VideoOnAPhoneWithHardwareH264AndVp9To1080pStopsAt1080p60AndPrefersVp9() {
        val ladder = QualityLadder.of(SampleStreams.uhd, Caps.phoneH264Vp9)
        assertEquals(listOf("1080p60", "720p60", "480p", "360p", "240p", "144p"), ladder.labels())
        // VP9 in hardware comes before H.264 in hardware, and AV1 cannot be decoded
        assertTrue(ladder.all { it.codec == VideoCodec.VP9 })
    }

    @Test
    fun a4k60VideoOnTheDesktopWithSoftwareDecodersKeepsEveryRungAndUsesH264WhereItExists() {
        val ladder = QualityLadder.of(SampleStreams.uhd, Caps.desktop)
        // the desktop's ceiling is Auto's business (the selector), not the ladder's: 2160p stays selectable
        assertEquals(listOf("2160p60", "1440p60", "1080p60", "720p60", "480p", "360p", "240p", "144p"), ladder.labels())
        // no hardware: H.264 where there is any, else VP9
        assertEquals(VideoCodec.VP9, ladder[0].codec)
        assertEquals(VideoCodec.VP9, ladder[1].codec)
        assertTrue(ladder.drop(2).all { it.codec == VideoCodec.H264 })
    }

    @Test
    fun a1080pH264OnlyVideoHasSixRungsOnAnyDevice() {
        for ((name, caps) in Caps.all) {
            val ladder = QualityLadder.of(SampleStreams.fhd, caps)
            assertEquals(listOf("1080p", "720p", "480p", "360p", "240p", "144p"), ladder.labels(), name)
            assertTrue(ladder.all { it.codec == VideoCodec.H264 && it.fps == 30 }, name)
        }
    }

    @Test
    fun aMuxedOnly360pVideoHasOneRungOnAnyDevice() {
        for ((name, caps) in Caps.all) {
            val ladder = QualityLadder.of(SampleStreams.sd, caps)
            assertEquals(listOf("360p"), ladder.labels(), name)
            assertEquals(VideoCodec.H264, ladder.single().codec, name)
        }
    }

    // ------------------------------------------------------------------ the rules

    @Test
    fun theLadderCoversEveryRungTheOwnerCanPickInSettings() {
        val pickable = VideoSettings.QUALITY_CHOICES.filterNotNull()
        assertEquals(listOf(2160, 1440, 1080, 720, 480, 360, 240, 144), pickable)
        assertEquals(pickable, QualityLadder.of(SampleStreams.uhd, Caps.phoneAv1).map { it.height })
    }

    @Test
    fun theLadderIsSortedFromTheTopAndHasOneRungPerHeight() {
        val ladder = QualityLadder.of(SampleStreams.uhd, Caps.desktop)
        assertEquals(ladder.map { it.height }.sortedDescending(), ladder.map { it.height })
        assertEquals(ladder.size, ladder.map { it.height }.toSet().size)
    }

    @Test
    fun aHeightKeepsItsBestFrameRate() {
        val streams = streamSet(videoOnly = listOf(
            videoStream(1, VideoCodec.H264, 1920, 1080, fps = 30),
            videoStream(2, VideoCodec.H264, 1920, 1080, fps = 60),
            videoStream(3, VideoCodec.H264, 1280, 720, fps = 24),
            videoStream(4, VideoCodec.H264, 1280, 720, fps = 50),
        ))
        assertEquals(listOf("1080p60", "720p50"), QualityLadder.of(streams, Caps.phoneAv1).labels())
    }

    @Test
    fun muxedStreamsAreOnlyUsedWhenThereAreNoVideoOnlyOnes() {
        val videoOnly = videoStream(1, VideoCodec.H264, 854, 480)
        val muxed = videoStream(18, VideoCodec.H264, 1280, 720, hasAudio = true, ranges = false)
        assertEquals(listOf("480p"), QualityLadder.of(streamSet(videoOnly = listOf(videoOnly), muxed = listOf(muxed)), Caps.phoneAv1).labels())
        assertEquals(listOf("720p"), QualityLadder.of(streamSet(muxed = listOf(muxed)), Caps.phoneAv1).labels())
    }

    @Test
    fun theDeliveryMethodDoesNotTakeAStreamOffTheLadder() {
        // the NewPipe adapter marks YouTube's adaptive streams PROGRESSIVE (the samples are made that way) and Y-2's fakes DASH; the ladder never looks at it
        val dash = SampleStreams.uhd.let { set -> set.copy(videoOnly = set.videoOnly.map { it.copy(delivery = Delivery.DASH) }) }
        assertTrue(SampleStreams.uhd.videoOnly.all { it.delivery == Delivery.PROGRESSIVE })
        assertEquals(QualityLadder.of(SampleStreams.uhd, Caps.phoneAv1), QualityLadder.of(dash, Caps.phoneAv1))
    }

    @Test
    fun aVideoThatOffersOnly240pAnd144pHasTwoRungs() {
        val tiny = streamSet(videoOnly = listOf(
            videoStream(133, VideoCodec.H264, 426, 240),
            videoStream(160, VideoCodec.H264, 256, 144),
        ))
        for ((name, caps) in Caps.all) assertEquals(listOf("240p", "144p"), QualityLadder.of(tiny, caps).labels(), name)
    }

    @Test
    fun aSetWhoseVideoOnlyStreamsAreAllUndecodableFallsBackToItsMuxedStream() {
        val streams = streamSet(
            videoOnly = listOf(videoStream(1, VideoCodec.VP9, 1920, 1080, fps = 60), videoStream(2, VideoCodec.AV1, 1280, 720)),
            muxed = listOf(videoStream(18, VideoCodec.H264, 640, 360, hasAudio = true, ranges = false)),
        )
        val h264Only = Caps.phoneAv1.copy(video = listOf(DecoderCaps(VideoCodec.H264, 1920, 1080, 60, true)))
        assertEquals(listOf("360p"), QualityLadder.of(streams, h264Only).labels())
        // as soon as one video-only stream plays, the muxed one is left out
        assertEquals(listOf("1080p60", "720p"), QualityLadder.of(streams, Caps.phoneAv1).labels())
    }

    @Test
    fun aRungNeedsADecoderThatCoversItsCodecItsSizeAndItsFrameRate() {
        val streams = streamSet(videoOnly = listOf(
            videoStream(1, VideoCodec.VP9, 1920, 1080, fps = 60),
            videoStream(2, VideoCodec.H264, 1920, 1080, fps = 30),
            videoStream(3, VideoCodec.VP9, 2560, 1440, fps = 30),
            videoStream(4, VideoCodec.AV1, 1280, 720, fps = 30),
        ))
        fun caps(vararg d: DecoderCaps) = Caps.phoneAv1.copy(video = d.toList())

        // VP9 stops at 30 fps: the 60 fps stream is out, the H.264 stream of the same height stands in; 1440p VP9 is too big; AV1 is not decodable
        val slow = caps(DecoderCaps(VideoCodec.VP9, 1920, 1080, 30, true), DecoderCaps(VideoCodec.H264, 1920, 1080, 60, true))
        assertEquals(listOf("1080p"), QualityLadder.of(streams, slow).labels())

        // two entries for one codec: a larger size at 30 fps and a smaller one at 60 fps
        val two = caps(DecoderCaps(VideoCodec.VP9, 2560, 1440, 30, true), DecoderCaps(VideoCodec.VP9, 1920, 1080, 60, true))
        assertEquals(listOf("1440p", "1080p60"), QualityLadder.of(streams, two).labels())

        // AV1 in software counts too
        val av1 = caps(DecoderCaps(VideoCodec.AV1, 1920, 1080, 30, false))
        assertEquals(listOf("720p"), QualityLadder.of(streams, av1).labels())

        // no decoder at all: no rung
        assertEquals(emptyList(), QualityLadder.of(streams, caps()))
    }

    @Test
    fun theSizeIsComparedInEitherOrientation() {
        val short = streamSet(videoOnly = listOf(videoStream(1, VideoCodec.H264, 1080, 1920, fps = 30)))
        val landscapeDecoder = Caps.phoneAv1.copy(video = listOf(DecoderCaps(VideoCodec.H264, 1920, 1080, 60, true)))
        assertEquals(listOf("1080p"), QualityLadder.of(short, landscapeDecoder).labels())
        val small = Caps.phoneAv1.copy(video = listOf(DecoderCaps(VideoCodec.H264, 1280, 720, 60, true)))
        assertEquals(emptyList(), QualityLadder.of(short, small).labels())
    }

    @Test
    fun anUnknownFrameRateIsCoveredAndHasNoRateInItsLabel() {
        val streams = streamSet(videoOnly = listOf(videoStream(1, VideoCodec.H264, 1280, 720, fps = 0)))
        val caps = Caps.phoneAv1.copy(video = listOf(DecoderCaps(VideoCodec.H264, 1920, 1080, 30, true)))
        assertEquals(listOf("720p"), QualityLadder.of(streams, caps).labels())
    }

    @Test
    fun hdrRungsAreOnlyOfferedOnAnHdrDisplay() {
        val sdr = QualityLadder.of(SampleStreams.uhd, Caps.phoneAv1)
        assertTrue(sdr.none { it.hdr })
        val hdrCaps = Caps.phoneAv1.copy(hdrDisplay = true)
        val hdr = QualityLadder.of(SampleStreams.uhd, hdrCaps)
        // the HDR stream wins its height; heights without one stay as they were
        assertEquals(listOf("2160p60 HDR", "1440p60", "1080p60 HDR", "720p60", "480p", "360p", "240p", "144p"), hdr.labels())
        assertEquals(VideoCodec.VP9, hdr[0].codec)
        assertEquals(VideoCodec.AV1, hdr[1].codec)
        assertEquals(VideoCodec.VP9, hdr[2].codec)
    }

    @Test
    fun aHeightThatOnlyHasAnHdrStreamIsDroppedOnAStandardDisplay() {
        val streams = streamSet(videoOnly = listOf(
            videoStream(1, VideoCodec.VP9, 3840, 2160, fps = 60, hdr = true),
            videoStream(2, VideoCodec.VP9, 1920, 1080, fps = 60),
        ))
        assertEquals(listOf("1080p60"), QualityLadder.of(streams, Caps.phoneAv1).labels())
        assertEquals(listOf("2160p60 HDR", "1080p60"), QualityLadder.of(streams, Caps.phoneAv1.copy(hdrDisplay = true)).labels())
    }

    @Test
    fun theCodecOrderFollowsTheDevice() {
        val streams = streamSet(videoOnly = listOf(
            videoStream(1, VideoCodec.AV1, 1920, 1080, fps = 60),
            videoStream(2, VideoCodec.VP9, 1920, 1080, fps = 60),
            videoStream(3, VideoCodec.H264, 1920, 1080, fps = 60),
        ))
        fun codecOn(vararg d: DecoderCaps) = QualityLadder.of(streams, Caps.phoneAv1.copy(video = d.toList())).single().codec
        fun dec(codec: VideoCodec, hardware: Boolean) = DecoderCaps(codec, 3840, 2160, 60, hardware)

        assertEquals(VideoCodec.AV1, codecOn(dec(VideoCodec.AV1, true), dec(VideoCodec.VP9, true), dec(VideoCodec.H264, true)))
        assertEquals(VideoCodec.VP9, codecOn(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, true), dec(VideoCodec.H264, true)))
        assertEquals(VideoCodec.H264, codecOn(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, false), dec(VideoCodec.H264, true)))
        // no hardware at all: H.264, then VP9, then AV1
        assertEquals(VideoCodec.H264, codecOn(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, false), dec(VideoCodec.H264, false)))
        assertEquals(VideoCodec.VP9, codecOn(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, false)))
        assertEquals(VideoCodec.AV1, codecOn(dec(VideoCodec.AV1, false)))
    }

    @Test
    fun thePreferredCodecWinsWhereTheDeviceDecodesIt() {
        // 2160p only has VP9 and AV1; 1080p has all three
        val ladder = { pref: CodecPreference, caps: EngineCapabilities -> QualityLadder.of(SampleStreams.uhd, caps, pref) }

        val h264 = ladder(CodecPreference.H264, Caps.phoneAv1)
        assertEquals(VideoCodec.AV1, h264[0].codec)     // 2160p has no H.264: the order decides
        assertEquals(VideoCodec.AV1, h264[1].codec)     // neither has 1440p
        assertTrue(h264.drop(2).all { it.codec == VideoCodec.H264 })
        assertEquals("1080p60", h264[2].label)

        val vp9 = ladder(CodecPreference.VP9, Caps.phoneAv1)
        assertTrue(vp9.all { it.codec == VideoCodec.VP9 })

        // the device cannot decode AV1: the preference is ignored and the order stands
        assertTrue(ladder(CodecPreference.AV1, Caps.phoneH264Vp9).all { it.codec == VideoCodec.VP9 })
    }

    @Test
    fun thePreferredCodecKeepsItsOwnFrameRateEvenWhenAnotherCodecHasMore() {
        val streams = streamSet(videoOnly = listOf(
            videoStream(1, VideoCodec.VP9, 1920, 1080, fps = 60),
            videoStream(2, VideoCodec.H264, 1920, 1080, fps = 30),
        ))
        assertEquals(listOf("1080p60"), QualityLadder.of(streams, Caps.phoneAv1).labels())
        assertEquals(listOf("1080p"), QualityLadder.of(streams, Caps.phoneAv1, CodecPreference.H264).labels())
    }

    @Test
    fun nothingIsOfferedForALiveSetOrWhenNothingIsDecodable() {
        assertEquals(emptyList(), QualityLadder.of(streamSet(hlsUrl = "https://media.cosmotune.invalid/live/index.m3u8"), Caps.phoneAv1))
        assertEquals(emptyList(), QualityLadder.of(SampleStreams.uhd, Caps.phoneAv1.copy(video = emptyList())))
    }

    // ------------------------------------------------------------------ the height of a stream

    @Test
    fun theHeightIsTheStandardOneWhoseFrameHoldsTheStream() {
        fun h(width: Int, height: Int) = QualityLadder.heightOf(videoStream(1, VideoCodec.H264, width, height))
        // 16:9
        assertEquals(144, h(256, 144))
        assertEquals(240, h(426, 240))
        assertEquals(240, h(424, 240))
        assertEquals(360, h(640, 360))
        assertEquals(480, h(854, 480))
        assertEquals(480, h(852, 480))
        assertEquals(720, h(1280, 720))
        assertEquals(1080, h(1920, 1080))
        assertEquals(1440, h(2560, 1440))
        assertEquals(2160, h(3840, 2160))
        assertEquals(4320, h(7680, 4320))
        // 4:3 and square: by the height
        assertEquals(1080, h(1440, 1080))
        assertEquals(480, h(640, 480))
        assertEquals(1080, h(1080, 1080))
        // 2.39:1: by the width, as the source names them
        assertEquals(1080, h(1920, 804))
        assertEquals(720, h(1280, 536))
        // Shorts, 9:16: by the short side
        assertEquals(1080, h(1080, 1920))
        assertEquals(720, h(720, 1280))
        assertEquals(360, h(360, 640))
        // cinema 4K
        assertEquals(2160, h(4096, 2160))
        // the width is unknown: the height as given
        assertEquals(720, h(0, 720))
        assertEquals(0, h(1280, 0))
    }
}
