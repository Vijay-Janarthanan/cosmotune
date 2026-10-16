package com.cosmotune.video.select

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.player.DecoderCaps
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.player.Selection
import com.cosmotune.video.settings.CodecPreference
import com.cosmotune.video.settings.VideoSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stream selector (plan task Y-5, step 2; spec 6.4): one test per rule, on the sample lists and on small made-up ones. */
class StreamSelectorTest {

    private val selector = StreamSelector()

    private fun choose(
        streams: StreamSet,
        caps: EngineCapabilities,
        settings: VideoSettings = VideoSettings(),
        metered: Boolean = false,
        manual: QualityOption? = null,
        audioTrackId: String? = null,
        bandwidthKbps: Int? = null,
        durationMs: Long = streams.durationMs ?: 0,
    ): Selection = selector.choose(streams, caps, settings, metered, manual, audioTrackId, bandwidthKbps, durationMs)

    private fun Selection.label() = option?.label
    private fun Selection.itag() = video?.itag

    private fun rungOf(streams: StreamSet, caps: EngineCapabilities, height: Int, codec: CodecPreference = CodecPreference.AUTO) =
        QualityLadder.of(streams, caps, codec).first { it.height == height }

    /** The phone with every decoder in hardware, shown on a display of [display] pixels. */
    private fun phone(display: Int) = Caps.phoneAv1.copy(displayHeightPx = display)

    // ------------------------------------------------------------------ (a) the rung: Auto, the defaults

    @Test
    fun autoIsTheDefaultOnWifiAndOnMobileDataAndTheChoiceIsTheSame() {
        val settings = VideoSettings()
        assertNull(settings.wifiQuality)
        assertNull(settings.meteredQuality)
        val lists = listOf(SampleStreams.uhd, SampleStreams.fhd, SampleStreams.sd)
        for (streams in lists) for ((name, caps) in Caps.all) {
            for (bandwidth in listOf(null, 800, 20_000)) {
                val unmetered = choose(streams, caps, settings, metered = false, bandwidthKbps = bandwidth)
                val metered = choose(streams, caps, settings, metered = true, bandwidthKbps = bandwidth)
                assertEquals(unmetered, metered, "$name, bandwidth $bandwidth")
            }
        }
    }

    @Test
    fun autoStopsAtTheDisplaysHeightRoundedDownToARung() {
        val uhd = SampleStreams.uhd
        assertEquals("1080p60", choose(uhd, phone(1080)).label())
        assertEquals("1440p60", choose(uhd, phone(1440)).label())
        assertEquals("2160p60", choose(uhd, phone(2160)).label())
        assertEquals("2160p60", choose(uhd, phone(2400)).label())    // taller than every rung
        assertEquals("720p60", choose(uhd, phone(1000)).label())     // between 720 and 1080: down to 720
        assertEquals("144p", choose(uhd, phone(100)).label())        // below every rung: the lowest
        assertEquals("2160p60", choose(uhd, phone(0)).label())       // unknown: no cap
    }

    @Test
    fun theTopRungOfTheLadderIsWhatAutoPlaysWithNothingToHoldItBack() {
        for ((name, caps) in Caps.all) {
            val roomy = caps.copy(displayHeightPx = 4320, autoCeilingHeight = null)
            for (streams in listOf(SampleStreams.uhd, SampleStreams.fhd, SampleStreams.sd)) {
                val selection = choose(streams, roomy)
                assertEquals(selection.ladder.first(), selection.option, name)
            }
        }
    }

    // ------------------------------------------------------------------ (a) the network setting

    @Test
    fun aCapOnWifiHoldsAutoBackOnWifiOnly() {
        val uhd = SampleStreams.uhd
        val settings = VideoSettings(wifiQuality = 720)
        assertEquals("720p60", choose(uhd, phone(2160), settings, metered = false).label())
        assertEquals("2160p60", choose(uhd, phone(2160), settings, metered = true).label())
    }

    @Test
    fun aCapOnMobileDataHoldsAutoBackOnMobileDataOnly() {
        val uhd = SampleStreams.uhd
        val settings = VideoSettings(meteredQuality = 480)
        assertEquals("480p", choose(uhd, phone(2160), settings, metered = true).label())
        assertEquals("2160p60", choose(uhd, phone(2160), settings, metered = false).label())
    }

    @Test
    fun theLowestOfTheCapAndTheDisplayWins() {
        val uhd = SampleStreams.uhd
        assertEquals("1080p60", choose(uhd, phone(1080), VideoSettings(wifiQuality = 1440)).label())   // the display is lower
        assertEquals("720p60", choose(uhd, phone(1080), VideoSettings(wifiQuality = 720)).label())     // the cap is lower
    }

    @Test
    fun everyRungSettingsOffersWorksAsACap() {
        val uhd = SampleStreams.uhd
        val caps = phone(2160)
        for (cap in VideoSettings.QUALITY_CHOICES.filterNotNull()) {
            val selection = choose(uhd, caps, VideoSettings(wifiQuality = cap, meteredQuality = cap), metered = false)
            assertEquals(cap, selection.option?.height, "cap $cap")
            assertEquals(cap, choose(uhd, caps, VideoSettings(wifiQuality = cap, meteredQuality = cap), metered = true).option?.height, "cap $cap, metered")
        }
    }

    @Test
    fun aCapBetweenTwoRungsRoundsDownAndOneBelowEveryRungTakesTheLowest() {
        val uhd = SampleStreams.uhd
        assertEquals("720p60", choose(uhd, phone(2160), VideoSettings(wifiQuality = 900)).label())
        assertEquals("144p", choose(uhd, phone(2160), VideoSettings(wifiQuality = 100)).label())
    }

    // ------------------------------------------------------------------ (a) the engine's ceiling

    @Test
    fun theDesktopsCeilingHoldsAutoAt1440ButNotAManualPick() {
        val uhd = SampleStreams.uhd
        val desktop = Caps.desktop
        val auto = choose(uhd, desktop)
        assertEquals("1440p60", auto.label())
        assertEquals(VideoCodec.VP9, auto.video?.codec)

        val top = rungOf(uhd, desktop, 2160)
        val manual = choose(uhd, desktop, manual = top)
        assertEquals("2160p60", manual.label())

        assertEquals("1080p60", choose(uhd, desktop, VideoSettings(wifiQuality = 1080)).label())    // a lower setting still wins
    }

    // ------------------------------------------------------------------ (a) the user's pick

    @Test
    fun aManualPickIsThePickWhateverTheDisplayTheNetworkAndTheBandwidthSay() {
        val uhd = SampleStreams.uhd
        val caps = phone(1080)
        val pick = rungOf(uhd, caps, 2160)
        val settings = VideoSettings(wifiQuality = 480, meteredQuality = 360)
        for (metered in listOf(false, true)) {
            val selection = choose(uhd, caps, settings, metered = metered, manual = pick, bandwidthKbps = 100)
            assertEquals("2160p60", selection.label(), "metered $metered")
        }
        assertEquals("360p", choose(uhd, caps, manual = rungOf(uhd, caps, 360), bandwidthKbps = 20_000).label())
    }

    @Test
    fun aManualPickThatIsNotOnTheLadderTakesTheHighestRungBelowIt() {
        val uhd = SampleStreams.uhd
        val caps = phone(2160)
        fun pick(height: Int) = QualityOption(height, 30, VideoCodec.H264, false, "${height}p")
        assertEquals("720p60", choose(uhd, caps, manual = pick(900)).label())
        assertEquals("2160p60", choose(uhd, caps, manual = pick(4320)).label())
        assertEquals("144p", choose(uhd, caps, manual = pick(100)).label())
    }

    @Test
    fun aManualPickTakesTheCodecOfTheSettingNotTheCodecOfThePick() {
        val uhd = SampleStreams.uhd
        val caps = phone(2160)
        val pick = QualityOption(1080, 60, VideoCodec.H264, false, "1080p60")
        assertEquals(699, choose(uhd, caps, manual = pick).itag())                                         // AV1 in hardware
        assertEquals(299, choose(uhd, caps, VideoSettings(codec = CodecPreference.H264), manual = pick).itag())
    }

    // ------------------------------------------------------------------ (a) the measured bandwidth

    @Test
    fun theBandwidthPicksTheHighestRungWhoseBitrateTimesOnePointFourFits() {
        val uhd = SampleStreams.uhd
        val caps = phone(2160)
        // AV1 1080p60 is 3,000 kb/s and the chosen Opus 138 kb/s: 3,138 x 1.4 = 4,393.2
        assertEquals("1080p60", choose(uhd, caps, bandwidthKbps = 4_394).label())
        assertEquals("720p60", choose(uhd, caps, bandwidthKbps = 4_393).label())     // AV1 720p60 is 1,800 + 138: 2,713.2
        assertEquals("720p60", choose(uhd, caps, bandwidthKbps = 2_714).label())
        assertEquals("480p", choose(uhd, caps, bandwidthKbps = 2_713).label())
        assertEquals("2160p60", choose(uhd, caps, bandwidthKbps = 19_794).label())
        assertEquals("1440p60", choose(uhd, caps, bandwidthKbps = 19_793).label())
    }

    @Test
    fun withoutRoomForAnyRungTheLowestIsPlayedAndAnUnknownBandwidthHoldsNothingBack() {
        val uhd = SampleStreams.uhd
        val caps = phone(2160)
        assertEquals("144p", choose(uhd, caps, bandwidthKbps = 50).label())
        assertEquals("2160p60", choose(uhd, caps, bandwidthKbps = null).label())
        assertEquals("2160p60", choose(uhd, caps, bandwidthKbps = 0).label())
        assertEquals("2160p60", choose(uhd, caps, bandwidthKbps = 1_000_000).label())
    }

    @Test
    fun theBandwidthAndTheCapsAreAllTheLowestWins() {
        val uhd = SampleStreams.uhd
        assertEquals("1080p60", choose(uhd, phone(1080), bandwidthKbps = 1_000_000).label())      // the display
        assertEquals("720p60", choose(uhd, phone(1080), bandwidthKbps = 3_000).label())            // the bandwidth
        assertEquals("480p", choose(uhd, phone(1080), VideoSettings(wifiQuality = 480), bandwidthKbps = 1_000_000).label())
    }

    @Test
    fun theBandwidthCountsTheAudioTooAndAMuxedStreamHasItInside() {
        // H.264 1080p30 is 3,800 kb/s and the AAC 128: 3,928 x 1.4 = 5,499.2
        val fhd = SampleStreams.fhd
        assertEquals("1080p", choose(fhd, phone(2160), bandwidthKbps = 5_500).label())
        assertEquals("720p", choose(fhd, phone(2160), bandwidthKbps = 5_499).label())
        // a muxed-only set has one rung, however little the bandwidth
        assertEquals("360p", choose(SampleStreams.sd, phone(2160), bandwidthKbps = 10).label())
    }

    // ------------------------------------------------------------------ (b) the codec at the rung

    private fun caps(vararg decoders: DecoderCaps) = Caps.phoneAv1.copy(video = decoders.toList(), displayHeightPx = 1080)
    private fun dec(codec: VideoCodec, hardware: Boolean) = DecoderCaps(codec, 3840, 2160, 60, hardware)

    @Test
    fun theCodecOrderIsAv1ThenVp9ThenH264InHardwareThenH264Vp9Av1InSoftware() {
        val uhd = SampleStreams.uhd
        // 1080p60 has AV1 (699), VP9 (303) and H.264 (299)
        assertEquals(699, choose(uhd, caps(dec(VideoCodec.AV1, true), dec(VideoCodec.VP9, true), dec(VideoCodec.H264, true))).itag())
        assertEquals(303, choose(uhd, caps(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, true), dec(VideoCodec.H264, true))).itag())
        assertEquals(299, choose(uhd, caps(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, false), dec(VideoCodec.H264, true))).itag())
        assertEquals(299, choose(uhd, caps(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, false), dec(VideoCodec.H264, false))).itag())
        assertEquals(303, choose(uhd, caps(dec(VideoCodec.AV1, false), dec(VideoCodec.VP9, false))).itag())
        assertEquals(699, choose(uhd, caps(dec(VideoCodec.AV1, false))).itag())
        // the three sets of the plan
        assertEquals(699, choose(uhd, Caps.phoneAv1).itag())
        assertEquals(303, choose(uhd, Caps.phoneH264Vp9).itag())
        assertEquals(299, choose(uhd, Caps.desktop.copy(autoCeilingHeight = 1080)).itag())
    }

    @Test
    fun thePreferredCodecIsUsedWhereTheDeviceDecodesItAtThatRung() {
        val uhd = SampleStreams.uhd
        val phone = phone(1080)
        assertEquals(299, choose(uhd, phone, VideoSettings(codec = CodecPreference.H264)).itag())
        assertEquals(303, choose(uhd, phone, VideoSettings(codec = CodecPreference.VP9)).itag())
        assertEquals(699, choose(uhd, phone, VideoSettings(codec = CodecPreference.AV1)).itag())
        // a software decoder is still the one the user asked for
        assertEquals(299, choose(uhd, caps(dec(VideoCodec.AV1, true), dec(VideoCodec.H264, false)), VideoSettings(codec = CodecPreference.H264)).itag())
    }

    @Test
    fun thePreferredCodecThatTheDeviceCannotDecodeOrTheRungLacksFallsBackToTheOrder() {
        val uhd = SampleStreams.uhd
        // no AV1 decoder on this phone
        assertEquals(303, choose(uhd, Caps.phoneH264Vp9, VideoSettings(codec = CodecPreference.AV1)).itag())
        // 2160p60 has no H.264 stream
        assertEquals(701, choose(uhd, phone(2160), VideoSettings(codec = CodecPreference.H264)).itag())
    }

    @Test
    fun theSelectionsOptionIsItsStreamAndItsRungOfTheLadder() {
        val lists = listOf(SampleStreams.uhd, SampleStreams.fhd, SampleStreams.sd)
        for (streams in lists) for ((name, caps) in Caps.all) for (codec in CodecPreference.entries) {
            for (bandwidth in listOf(null, 3_000)) {
                val selection = choose(streams, caps, VideoSettings(codec = codec), bandwidthKbps = bandwidth)
                assertEquals(QualityLadder.of(streams, caps, codec), selection.ladder, "$name $codec")
                val option = assertNotNull(selection.option, "$name $codec")
                val video = assertNotNull(selection.video, "$name $codec")
                assertTrue(option in selection.ladder, "$name $codec: the option is a rung of the ladder")
                assertEquals(option.height, QualityLadder.heightOf(video))
                assertEquals(option.fps, video.fps)
                assertEquals(option.codec, video.codec)
                assertEquals(option.hdr, video.hdr)
            }
        }
    }

    @Test
    fun anHdrDisplayPlaysTheHdrStreamAtItsRung() {
        val uhd = SampleStreams.uhd
        val hdrPhone = Caps.phoneAv1.copy(hdrDisplay = true, displayHeightPx = 2160)
        val selection = choose(uhd, hdrPhone)
        assertEquals("2160p60 HDR", selection.label())
        assertEquals(337, selection.itag())
        assertEquals("2160p60", choose(uhd, Caps.phoneAv1.copy(displayHeightPx = 2160)).label())
    }

    @Test
    fun aShortIsPlayedAtItsNominalHeightNotItsPixelHeight() {
        val short = streamSet(
            videoOnly = listOf(
                videoStream(1, VideoCodec.H264, 1080, 1920, kbps = 3_000),
                videoStream(2, VideoCodec.H264, 720, 1280, kbps = 1_800),
                videoStream(3, VideoCodec.H264, 360, 640, kbps = 600),
            ),
            audio = listOf(audioStream(140, AudioCodec.AAC, 128)),
        )
        assertEquals(listOf("1080p", "720p", "360p"), choose(short, phone(1080)).ladder.labels())
        assertEquals("1080p", choose(short, phone(1080)).label())
        assertEquals("720p", choose(short, phone(720)).label())
    }

    // ------------------------------------------------------------------ (c) the audio

    private val video1080 = videoStream(1, VideoCodec.H264, 1920, 1080)

    private fun withAudio(vararg audio: AudioStream) = streamSet(videoOnly = listOf(video1080), audio = audio.toList())

    @Test
    fun opusIsChosenWhenItsBitrateIsWithin20PercentOfTheBestAac() {
        // the sample: AAC 128 and Opus 138
        assertEquals(251, choose(SampleStreams.uhd, phone(1080)).audio?.itag)
        val aac = audioStream(140, AudioCodec.AAC, 128)
        fun with(opusKbps: Int) = withAudio(aac, audioStream(251, AudioCodec.OPUS, opusKbps))
        assertEquals(251, choose(with(160), phone(1080)).audio?.itag)    // more than the AAC: Opus
        assertEquals(251, choose(with(128), phone(1080)).audio?.itag)
        assertEquals(251, choose(with(103), phone(1080)).audio?.itag)    // 103 / 128 = 80.5 %
        assertEquals(140, choose(with(102), phone(1080)).audio?.itag)    // 102 / 128 = 79.7 %
        assertEquals(140, choose(with(50), phone(1080)).audio?.itag)
    }

    @Test
    fun theBestAacIsTheOneToCompareWithAndTheOneToPlay() {
        val streams = withAudio(
            audioStream(139, AudioCodec.AAC, 48),
            audioStream(140, AudioCodec.AAC, 128),
            audioStream(250, AudioCodec.OPUS, 70),
            audioStream(251, AudioCodec.OPUS, 100),
        )
        // the best Opus is 100: 78 % of the best AAC (128), not enough
        assertEquals(140, choose(streams, phone(1080)).audio?.itag)
        // against the 48 kb/s AAC alone the 70 kb/s Opus wins: it is the best AAC that counts
        assertEquals(250, choose(withAudio(audioStream(139, AudioCodec.AAC, 48), audioStream(250, AudioCodec.OPUS, 70)), phone(1080)).audio?.itag)
    }

    @Test
    fun anEngineWithoutOpusGetsAac() {
        val noOpus = Caps.phoneAv1.copy(opus = false, displayHeightPx = 1080)
        assertEquals(140, choose(SampleStreams.uhd, noOpus).audio?.itag)
        assertEquals(140, choose(SampleStreams.fhd, noOpus).audio?.itag)
    }

    @Test
    fun aSingleCodecIsTheOnlyChoiceAndOpusIsBetterThanNothing() {
        assertEquals(251, choose(withAudio(audioStream(251, AudioCodec.OPUS, 130)), phone(1080)).audio?.itag)
        assertEquals(251, choose(withAudio(audioStream(251, AudioCodec.OPUS, 130)), Caps.phoneAv1.copy(opus = false)).audio?.itag)
        assertEquals(140, choose(withAudio(audioStream(139, AudioCodec.AAC, 48), audioStream(140, AudioCodec.AAC, 128)), phone(1080)).audio?.itag)
    }

    @Test
    fun theAudioRuleIsTheSameOnEveryNetwork() {
        val streams = SampleStreams.uhd
        val unmetered = choose(streams, phone(1080), metered = false).audio
        val metered = choose(streams, phone(1080), metered = true).audio
        assertEquals(251, unmetered?.itag)
        assertEquals(unmetered, metered)
    }

    private val tracks = streamSet(
        videoOnly = listOf(video1080),
        audio = listOf(
            audioStream(140, AudioCodec.AAC, 128, trackId = "en.4", language = "en", kind = AudioTrackKind.ORIGINAL),
            audioStream(251, AudioCodec.OPUS, 135, trackId = "en.4", language = "en", kind = AudioTrackKind.ORIGINAL),
            audioStream(140, AudioCodec.AAC, 128, trackId = "hi.4", language = "hi", kind = AudioTrackKind.DUBBED),
            audioStream(251, AudioCodec.OPUS, 130, trackId = "hi.4", language = "hi", kind = AudioTrackKind.DUBBED),
            audioStream(140, AudioCodec.AAC, 128, trackId = "es-US.4", language = "es-US", kind = AudioTrackKind.DUBBED),
        ),
    )

    @Test
    fun theOriginalTrackIsTheDefault() {
        val audio = assertNotNull(choose(tracks, phone(1080)).audio)
        assertEquals("en.4", audio.trackId)
        assertEquals(251, audio.itag)
        // content language does not beat the original
        val spanish = choose(tracks, phone(1080), VideoSettings(contentLanguage = "es")).audio
        assertEquals("en.4", spanish?.trackId)
    }

    @Test
    fun theTrackAskedForWinsAndAStaleIdFallsBackToTheRules() {
        assertEquals("hi.4", choose(tracks, phone(1080), audioTrackId = "hi.4").audio?.trackId)
        assertEquals(251, choose(tracks, phone(1080), audioTrackId = "hi.4").audio?.itag)
        assertEquals("es-US.4", choose(tracks, phone(1080), audioTrackId = "es-US.4").audio?.trackId)
        assertEquals("en.4", choose(tracks, phone(1080), audioTrackId = "no-such-track").audio?.trackId)
    }

    @Test
    fun withoutAnOriginalTheTrackInTheContentLanguageThenTheFirstIsPlayed() {
        val dubbedOnly = tracks.copy(audio = tracks.audio.filter { it.trackKind != AudioTrackKind.ORIGINAL })
        assertEquals("es-US.4", choose(dubbedOnly, phone(1080), VideoSettings(contentLanguage = "es")).audio?.trackId)
        assertEquals("es-US.4", choose(dubbedOnly, phone(1080), VideoSettings(contentLanguage = "es-MX")).audio?.trackId)
        assertEquals("hi.4", choose(dubbedOnly, phone(1080), VideoSettings(contentLanguage = "HI")).audio?.trackId)
        assertEquals("hi.4", choose(dubbedOnly, phone(1080)).audio?.trackId)                                       // the first
        assertEquals("hi.4", choose(dubbedOnly, phone(1080), VideoSettings(contentLanguage = "fr")).audio?.trackId)  // no match: the first
    }

    // ------------------------------------------------------------------ (d) the input

    @Test
    fun aDashManifestIsBuiltWhenTheEngineTakesOneAndBothStreamsHaveRanges() {
        val uhd = SampleStreams.uhd
        val selection = choose(uhd, phone(1080), durationMs = 95_000)
        val input = assertIs<MediaInput.Dash>(selection.input)
        assertEquals(selection.video, input.video)
        assertEquals(selection.audio, input.audio)
        assertEquals(699, input.video.itag)
        assertEquals(251, input.audio.itag)
        assertEquals(DashManifest.build(input.video, input.audio, 95_000), input.manifest)
        assertTrue(input.manifest.contains("mediaPresentationDuration=\"PT95.000S\""))
    }

    @Test
    fun anEngineThatDoesNotTakeSideloadedDashGetsTwoProgressiveStreams() {
        val selection = choose(SampleStreams.uhd, Caps.desktop)
        val input = assertIs<MediaInput.Progressive>(selection.input)
        assertEquals(selection.video, input.video)
        assertEquals(selection.audio, input.audio)
        assertNotNull(input.video)
        assertNotNull(input.audio)
    }

    @Test
    fun streamsWithoutRangesAreSentProgressive() {
        val noVideoRanges = streamSet(
            videoOnly = listOf(videoStream(1, VideoCodec.H264, 1920, 1080, ranges = false)),
            audio = listOf(audioStream(140, AudioCodec.AAC, 128)),
        )
        val noAudioRanges = streamSet(
            videoOnly = listOf(videoStream(1, VideoCodec.H264, 1920, 1080)),
            audio = listOf(audioStream(140, AudioCodec.AAC, 128, ranges = false)),
        )
        for (streams in listOf(noVideoRanges, noAudioRanges)) {
            val selection = choose(streams, phone(1080))
            val input = assertIs<MediaInput.Progressive>(selection.input)
            assertEquals(selection.video, input.video)
            assertEquals(selection.audio, input.audio)
        }
    }

    @Test
    fun theRangesDecideNotTheDeliveryMethod() {
        // the NewPipe adapter marks YouTube's adaptive streams PROGRESSIVE, with their init and index ranges: still a manifest
        val adaptive = SampleStreams.fhd
        assertTrue(adaptive.videoOnly.all { it.delivery == Delivery.PROGRESSIVE && it.init != null && it.index != null })
        assertTrue(adaptive.audio.all { it.delivery == Delivery.PROGRESSIVE && it.init != null && it.index != null })
        val selection = choose(adaptive, phone(1080))
        val input = assertIs<MediaInput.Dash>(selection.input)
        assertEquals(selection.video, input.video)
        assertEquals(selection.audio, input.audio)

        // Y-2's fakes mark the same streams DASH: the same answer
        val marked = adaptive.copy(
            videoOnly = adaptive.videoOnly.map { it.copy(delivery = Delivery.DASH) },
            audio = adaptive.audio.map { it.copy(delivery = Delivery.DASH) },
        )
        val markedInput = assertIs<MediaInput.Dash>(choose(marked, phone(1080)).input)
        assertEquals(input.manifest, markedInput.manifest)

        // and the other way round: marked DASH but without ranges is sent as two progressive sources
        val noRanges = adaptive.copy(
            videoOnly = adaptive.videoOnly.map { it.copy(delivery = Delivery.DASH, init = null, index = null) },
            audio = adaptive.audio.map { it.copy(delivery = Delivery.DASH) },
        )
        assertIs<MediaInput.Progressive>(choose(noRanges, phone(1080)).input)

        // the desktop never takes a manifest
        assertIs<MediaInput.Progressive>(choose(adaptive, Caps.desktop).input)
    }

    @Test
    fun aVideoThatOffersOnly240pAnd144pIsPlayedAtTheBestOfThem() {
        val tiny = streamSet(
            videoOnly = listOf(
                videoStream(133, VideoCodec.H264, 426, 240, kbps = 300),
                videoStream(160, VideoCodec.H264, 256, 144, kbps = 110),
            ),
            audio = listOf(audioStream(140, AudioCodec.AAC, 128)),
        )
        for ((name, caps) in Caps.all) {
            val selection = choose(tiny, caps)
            assertEquals("240p", selection.label(), name)
            assertEquals(listOf("240p", "144p"), selection.ladder.labels(), name)
        }
        assertEquals("144p", choose(tiny, phone(1080), bandwidthKbps = 100).label())
        assertEquals("144p", choose(tiny, phone(1080), VideoSettings(wifiQuality = 144)).label())
        assertEquals("240p", choose(tiny, phone(1080), VideoSettings(wifiQuality = 2160)).label())
    }

    @Test
    fun aMuxedOnlySetIsOneProgressiveStreamWithNoAudioOfItsOwn() {
        for ((name, caps) in Caps.all) {
            val selection = choose(SampleStreams.sd, caps)
            val input = assertIs<MediaInput.Progressive>(selection.input, name)
            assertEquals(18, input.video?.itag, name)
            assertNull(input.audio, name)
            assertNull(selection.audio, name)
            assertEquals("360p", selection.label(), name)
        }
        // audio streams next to a muxed stream are not joined to it
        val withAudio = SampleStreams.sd.copy(audio = SampleStreams.uhd.audio)
        val selection = choose(withAudio, phone(1080))
        assertNull(selection.audio)
        assertEquals(MediaInput.Progressive(selection.video, null), selection.input)
    }

    @Test
    fun aMuxedStreamStandsInWhenNoVideoOnlyStreamPlays() {
        val streams = streamSet(
            videoOnly = listOf(videoStream(1, VideoCodec.VP9, 1920, 1080, fps = 60)),
            muxed = listOf(videoStream(18, VideoCodec.H264, 640, 360, hasAudio = true, ranges = false)),
            audio = listOf(audioStream(140, AudioCodec.AAC, 128)),
        )
        val h264Only = Caps.phoneAv1.copy(video = listOf(DecoderCaps(VideoCodec.H264, 1920, 1080, 60, true)))
        val selection = choose(streams, h264Only)
        assertEquals("360p", selection.label())
        assertEquals(MediaInput.Progressive(streams.muxed.single(), null), selection.input)
        assertNull(selection.audio)
    }

    @Test
    fun aLiveSetIsItsHlsAddress() {
        val live = streamSet(hlsUrl = "https://media.cosmotune.invalid/live/index.m3u8", durationMs = null)
        for ((name, caps) in Caps.all) {
            val selection = choose(live, caps, durationMs = 0)
            assertEquals(MediaInput.Hls("https://media.cosmotune.invalid/live/index.m3u8"), selection.input, name)
            assertNull(selection.option, name)
            assertNull(selection.video, name)
            assertNull(selection.audio, name)
            assertEquals(emptyList(), selection.ladder, name)
        }
        // video-only streams next to an HLS address are an ordinary video
        val vod = SampleStreams.fhd.copy(hlsUrl = "https://media.cosmotune.invalid/vod/index.m3u8")
        assertIs<MediaInput.Dash>(choose(vod, phone(1080)).input)
    }

    @Test
    fun aSetWithOnlyAudioPlaysTheAudioAndASetWithNothingPlaysNothing() {
        val audioOnly = streamSet(audio = listOf(audioStream(140, AudioCodec.AAC, 128)))
        val selection = choose(audioOnly, phone(1080))
        assertNull(selection.option)
        assertNull(selection.video)
        assertEquals(140, selection.audio?.itag)
        assertEquals(MediaInput.Progressive(null, selection.audio), selection.input)

        val nothing = choose(streamSet(), phone(1080))
        assertEquals(MediaInput.Progressive(null, null), nothing.input)
        assertNull(nothing.option)
        assertEquals(emptyList(), nothing.ladder)
    }

    @Test
    fun whenNoDecoderIsReportedTheSmallestPictureIsStillHandedOverSoTheEngineCanSayWhy() {
        val none = Caps.phoneAv1.copy(video = emptyList())
        val selection = choose(SampleStreams.uhd, none)
        assertEquals(emptyList(), selection.ladder)
        assertEquals(160, selection.video?.itag)             // 144p H.264, not the AV1 or VP9 of the same height, not an HDR one
        assertEquals("144p", selection.label())
        assertIs<MediaInput.Dash>(selection.input)

        val sd = choose(SampleStreams.sd, none)
        assertEquals(18, sd.video?.itag)
        assertNull(sd.audio)
    }
}
