package com.cosmotune.video.downloads

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.DecoderCaps
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planner of plan task Y-24, step 1 (spec 5.16): rungs and codec order, Auto per caps, the nearest lower rung, sizes, labels, names. */
class DownloadPlannerTest {
    private val source = FakeVideoSource()
    private fun details(id: String): VideoDetails = runBlocking { source.details(id) }

    private val everything = DeviceCaps(maxHeight = 2160, maxFps = 60, codecs = setOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1))
    private val phone1080 = DeviceCaps(maxHeight = 1080, maxFps = 60, codecs = setOf(VideoCodec.H264, VideoCodec.VP9))
    private val h264Only = DeviceCaps(maxHeight = 1080, maxFps = 30, codecs = setOf(VideoCodec.H264))
    private val desktop = DeviceCaps(maxHeight = 2160, maxFps = 60, codecs = setOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1), autoCeilingHeight = 1440, joinsToWebm = true)

    private val auto = DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null))
    private fun rung(h: Int, fps: Int) = DownloadRequest(DownloadQuality.Rung(h, fps), SubtitlePick(false, null))
    private val audioOnly = DownloadRequest(DownloadQuality.AudioOnly, SubtitlePick(false, null))

    // fakevideo01 lasts 754 s; the fake's sizes are kbit/s x 125 x seconds
    private fun bytes(kbps: Int, seconds: Long = 754) = kbps * 125L * seconds

    @Test fun everyRungUpToTheCapsWithH264FirstThenVp9ThenAv1() {
        val options = DownloadPlanner.options(details("fakevideo01"), everything)
        assertEquals(
            listOf("2160p60", "1440p60", "1080p60", "1080p", "720p60", "720p", "480p", "360p", "240p", "144p"),
            options.rungs.map { it.label },
        )
        // 1080p60 exists as AV1 and VP9: VP9 comes before AV1; 1080p30 is H.264
        assertEquals(listOf("VP9", "VP9", "VP9", "H.264", "VP9", "H.264", "H.264", "H.264", "H.264", "H.264"), options.rungs.map { it.codec })
        assertEquals(DownloadQuality.Rung(1080, 60), options.rungs[2].quality)
        assertTrue(options.rungs.none { it.isEstimate }, "the fake gives every content length")
    }

    @Test fun theCapsLimitHeightFrameRateAndCodecs() {
        assertEquals(listOf("1080p", "720p", "480p", "360p", "240p", "144p"), DownloadPlanner.options(details("fakevideo01"), h264Only).rungs.map { it.label })
        assertEquals(
            listOf("1080p60", "1080p", "720p60", "720p", "480p", "360p", "240p", "144p"),
            DownloadPlanner.options(details("fakevideo01"), phone1080).rungs.map { it.label },
        )
        val av1Only = DeviceCaps(2160, 60, setOf(VideoCodec.AV1))
        assertEquals(listOf("1080p60"), DownloadPlanner.options(details("fakevideo01"), av1Only).rungs.map { it.label })
        assertEquals("AV1", DownloadPlanner.options(details("fakevideo01"), av1Only).rungs.single().codec)
    }

    @Test fun autoMeansTheHighestRungTheDeviceDecodesBelowItsCeiling() {
        assertEquals("2160p60", DownloadPlanner.options(details("fakevideo01"), everything).autoMeans)
        assertEquals("1080p60", DownloadPlanner.options(details("fakevideo01"), phone1080).autoMeans)
        assertEquals("1080p", DownloadPlanner.options(details("fakevideo01"), h264Only).autoMeans)
        // the desktop offers 2160p but Auto stops at the engine's ceiling, 1440
        assertEquals("1440p60", DownloadPlanner.options(details("fakevideo01"), desktop).autoMeans)
        assertEquals("2160p60", DownloadPlanner.options(details("fakevideo01"), desktop).rungs.first().label)

        val choice = DownloadPlanner.choiceFor(auto, details("fakevideo01"), phone1080)
        assertEquals(303, choice.video?.itag)
        assertEquals("1080p60", choice.label)
        assertEquals(1440, DownloadPlanner.choiceFor(auto, details("fakevideo01"), desktop).video?.height)
    }

    @Test fun aRungTheVideoLacksTakesTheNearestLowerOne() {
        val d = details("fakevideo01")
        assertEquals(136, DownloadPlanner.choiceFor(rung(720, 30), d, everything).video?.itag, "an exact rung")
        assertEquals(302, DownloadPlanner.choiceFor(rung(720, 60), d, everything).video?.itag)
        // 1440p60 on a device that stops at 1080p30 H.264: 1080p30
        assertEquals(137, DownloadPlanner.choiceFor(rung(1440, 60), d, h264Only).video?.itag)
        // the same height at a lower frame rate is nearer than a lower height
        assertEquals(137, DownloadPlanner.choiceFor(rung(1080, 60), d, h264Only).video?.itag)
        // a rung between two: the lower one
        assertEquals(135, DownloadPlanner.choiceFor(rung(600, 30), d, everything).video?.itag)
        // below everything: the lowest the video has
        assertEquals(160, DownloadPlanner.choiceFor(rung(100, 30), d, everything).video?.itag)
    }

    @Test fun theAudioFollowsTheContainer() {
        val d = details("fakevideo01")
        // the phone joins into MP4 only: VP9 goes with AAC in MP4
        val phone = DownloadPlanner.choiceFor(rung(1080, 60), d, phone1080)
        assertEquals(DownloadContainer.MP4, phone.container)
        assertEquals(AudioCodec.AAC, phone.audio.codec)
        // the desktop writes WebM for VP9: Opus, the original track, not the dubbed one
        val pc = DownloadPlanner.choiceFor(rung(1080, 60), d, desktop)
        assertEquals(DownloadContainer.WEBM, pc.container)
        assertEquals(AudioCodec.OPUS, pc.audio.codec)
        assertNull(pc.audio.trackId, "the original track")
        assertEquals(160_000, pc.audio.bitrate)
        // H.264 is MP4 with AAC on both
        val h264 = DownloadPlanner.choiceFor(rung(1080, 30), d, desktop)
        assertEquals(DownloadContainer.MP4, h264.container)
        assertEquals(AudioCodec.AAC, h264.audio.codec)
    }

    @Test fun audioOnlyIsTheBestAacAsM4a() {
        val choice = DownloadPlanner.choiceFor(audioOnly, details("fakevideo01"), everything)
        assertNull(choice.video)
        assertEquals(140, choice.audio.itag)
        assertEquals(DownloadContainer.M4A, choice.container)
        assertEquals(bytes(128), choice.estimatedBytes)
        assertEquals("Audio only", choice.label)
        val option = DownloadPlanner.options(details("fakevideo01"), everything).audioOnly
        assertEquals(DownloadQuality.AudioOnly, option.quality)
        assertEquals("AAC", option.codec)
        assertEquals(bytes(128), option.estimatedBytes)

        // without AAC: Opus, written as it is
        val d = details("fakevideo01")
        val noAac = d.copy(streams = d.streams.copy(audio = d.streams.audio.filter { it.codec != AudioCodec.AAC }))
        val opus = DownloadPlanner.choiceFor(audioOnly, noAac, everything)
        assertEquals(DownloadContainer.OPUS, opus.container)
        assertEquals(AudioCodec.OPUS, opus.audio.codec)
    }

    @Test fun sizesComeFromTheContentLengthsElseFromBitrateAndDuration() {
        val d = details("fakevideo01")
        val r1080 = DownloadPlanner.options(d, everything).rungs.first { it.label == "1080p" }
        assertEquals(bytes(3_800) + bytes(128), r1080.estimatedBytes)
        assertFalse(r1080.isEstimate)

        val noLengths = d.copy(streams = d.streams.copy(
            videoOnly = d.streams.videoOnly.map { it.copy(contentLength = null) },
            audio = d.streams.audio.map { it.copy(contentLength = null) },
        ))
        val estimated = DownloadPlanner.options(noLengths, everything).rungs.first { it.label == "1080p" }
        assertTrue(estimated.isEstimate)
        assertEquals(3_800_000L / 8 * 754 + 128_000L / 8 * 754, estimated.estimatedBytes)
    }

    @Test fun labelsReadHeightCodecAndSize() {
        val r1080 = DownloadPlanner.options(details("fakevideo01"), everything).rungs.first { it.label == "1080p" }
        assertEquals("1080p · H.264 · 370 MB", DownloadPlanner.rowLabel(r1080))
        assertEquals("about 339 MB", DownloadPlanner.rowLabel(RungOption(DownloadQuality.Rung(720, 30), "720p", null, 338_500_000, isEstimate = true)).substringAfter("720p · "))
        assertEquals("370 MB", DownloadPlanner.sizeText(370_214_000))
        assertEquals("1.4 GB", DownloadPlanner.sizeText(1_354_000_000))
        assertEquals("21 GB", DownloadPlanner.sizeText(21_000_000_000))
        assertEquals("820 KB", DownloadPlanner.sizeText(820_000))
        assertEquals("0 KB", DownloadPlanner.sizeText(0))
        assertEquals("1.0 GB", DownloadPlanner.sizeText(999_600_000))
    }

    @Test fun subtitleLanguagesListManualTracksFirstAndMarkTheAutoGeneratedOnes() {
        val options = DownloadPlanner.options(details("fakevideo01"), everything)
        assertEquals(
            listOf(SubtitleLanguage("en", "English", false), SubtitleLanguage("hi", "Hindi", false), SubtitleLanguage("en", "English", true)),
            options.subtitleLanguages,
        )
        assertEquals("en", options.originalLanguage)
        assertEquals(SubtitleLanguage("hi", "Hindi", false), DownloadPlanner.defaultSubtitle(options, "hi"))
        assertEquals(SubtitleLanguage("en", "English", false), DownloadPlanner.defaultSubtitle(options, "fr"), "the original language when the app's is missing")
    }

    @Test fun batchSizesAreAboutFromDurationsAndTheTypicalBitrates() {
        val items = listOf(FakeVideoSource.sampleItem("fakevideo01")!!, FakeVideoSource.sampleItem("fakevideo02")!!, FakeVideoSource.sampleItem("fakevideo11")!!)
        val options = DownloadPlanner.batchOptions(items, phone1080, appLanguage = "en", appLanguageName = "English")
        assertEquals(listOf("1080p60", "720p60", "480p", "360p", "240p", "144p"), options.rungs.map { it.label })
        assertTrue(options.rungs.all { it.isEstimate && it.codec == null })
        // 754 s + 600 s; the live video is left out
        assertEquals(2_000 * 125L * 1_354, options.rungs.first { it.label == "720p60" }.estimatedBytes)
        assertEquals(3_800 * 125L * 1_354, options.rungs.first().estimatedBytes)
        assertEquals(128 * 125L * 1_354, options.audioOnly.estimatedBytes)
        assertTrue(options.audioOnly.isEstimate)
        assertEquals("1080p60", options.autoMeans)
        assertEquals(listOf(SubtitleLanguage("en", "English", false), SubtitleLanguage("", DownloadPlanner.EACH_ORIGINAL_NAME, false)), options.subtitleLanguages)
        assertNull(options.originalLanguage)
        // the table of the plan
        assertEquals(listOf(120, 250, 500, 900, 2_000, 3_800, 9_000, 18_000), listOf(144, 240, 360, 480, 720, 1080, 1440, 2160).map(DownloadPlanner::typicalKbps))
        // an unknown duration counts as ten minutes
        val noDuration = FakeVideoSource.sampleItem("fakevideo01")!!.copy(durationSec = null)
        assertEquals(500 * 125L * 600, DownloadPlanner.batchOptions(listOf(noDuration), phone1080, "en", "English").rungs.first { it.label == "360p" }.estimatedBytes)
    }

    @Test fun fileNamesAreSafeShortAndCarryTheId() {
        val item = FakeVideoSource.sampleItem("fakevideo01")!!.copy(title = "AC/DC: \"Live\" <at> the *club*? | yes\\no")
        val choice = DownloadPlanner.choiceFor(rung(1080, 30), details("fakevideo01"), everything)
        assertEquals("AC DC Live at the club yes no [fakevideo01].mp4", DownloadPlanner.fileName(item, rung(1080, 30), choice))

        val long = item.copy(title = "a".repeat(80) + " " + "b".repeat(200))
        val name = DownloadPlanner.fileName(long, rung(1080, 30), choice)
        assertEquals("a".repeat(80) + " " + "b".repeat(39) + " [fakevideo01].mp4", name)
        assertEquals(120, DownloadPlanner.safeTitle(long.title).length)

        val short = FakeVideoSource.sampleItem("fakeshort01")!!
        val shortChoice = DownloadPlanner.choiceFor(auto, details("fakeshort01"), phone1080)
        assertEquals("Short One-take riff [fakeshort01].mp4", DownloadPlanner.fileName(short, auto, shortChoice))
        assertEquals("1080p60", shortChoice.label, "a vertical video is named by its short side")

        val audio = DownloadPlanner.choiceFor(audioOnly, details("fakevideo01"), everything)
        assertEquals("Building a tiny synth from scratch [fakevideo01].m4a", DownloadPlanner.fileName(FakeVideoSource.sampleItem("fakevideo01")!!, audioOnly, audio))
        assertEquals("Video [x1]", DownloadPlanner.baseName(item.copy(id = "x1", title = " ... ")))
        assertEquals(DownloadKind.SHORT, DownloadPlanner.kindOf(short, auto))
        assertEquals(DownloadKind.AUDIO, DownloadPlanner.kindOf(short, audioOnly))
    }

    @Test fun aJoinThatFailedKeepsAPairTheEntryCanPlay() {
        val item = FakeVideoSource.sampleItem("fakevideo01")!!
        val choice = DownloadPlanner.choiceFor(rung(1080, 30), details("fakevideo01"), everything)
        val (video, audio) = DownloadPlanner.pairNames(item, choice)
        assertEquals("Building a tiny synth from scratch [fakevideo01].video.mp4", video)
        assertEquals("Building a tiny synth from scratch [fakevideo01].audio.m4a", audio)
        val entry = DownloadEntry("download-1", item, DownloadKind.VIDEO, choice, rung(1080, 30), DownloadState.Done("/m/$video", 1), null, emptyList(), null, 0)
        assertEquals(MediaInput.LocalFiles(null, "/m/$video", "/m/$audio"), entry.localFiles())
        val joined = entry.copy(state = DownloadState.Done("/m/Building a tiny synth from scratch [fakevideo01].mp4", 1))
        assertEquals(MediaInput.LocalFiles("/m/Building a tiny synth from scratch [fakevideo01].mp4", null, null), joined.localFiles())
        assertNull(entry.copy(state = DownloadState.Waiting).localFiles())
    }

    @Test fun aPlannedChoiceStandsInUntilTheStreamsAreKnown() {
        val item = FakeVideoSource.sampleItem("fakevideo02")!!
        val planned = DownloadPlanner.plannedChoice(item, rung(720, 30), phone1080)
        assertTrue(DownloadPlanner.isPlanned(planned))
        assertEquals("720p", planned.label)
        assertEquals(2_000 * 125L * 600, planned.estimatedBytes)
        assertEquals("1080p60", DownloadPlanner.plannedChoice(item, auto, phone1080).label)
        assertEquals("Audio only", DownloadPlanner.plannedChoice(item, audioOnly, phone1080).label)
        assertFalse(DownloadPlanner.isPlanned(DownloadPlanner.choiceFor(auto, details("fakevideo02"), phone1080)))
    }

    @Test fun aFreshStreamSetIsMatchedByItagAndTrack() {
        val d = details("fakevideo01")
        val dubbed = d.streams.audio.first { it.trackId == "hi.4" }
        val fresh = d.streams.copy(audio = d.streams.audio.map { it.copy(url = it.url + "&fresh=1") }, videoOnly = d.streams.videoOnly.map { it.copy(url = it.url + "&fresh=1") })
        assertEquals("hi.4", DownloadPlanner.matching(fresh, dubbed)?.trackId)
        assertTrue(DownloadPlanner.matching(fresh, dubbed)!!.url.endsWith("&fresh=1"))
        assertEquals(137, DownloadPlanner.matching(fresh, d.streams.videoOnly.first { it.itag == 137 })?.itag)
        assertNull(DownloadPlanner.matching(fresh.copy(videoOnly = emptyList()), d.streams.videoOnly.first()))
    }

    @Test fun theOriginalLanguageComesFromTheOriginalAudioTrackElseTheAutoGeneratedSubtitles() {
        val d = details("fakevideo01")
        assertEquals("en", DownloadPlanner.originalLanguage(d))
        val noTrack = d.copy(streams = d.streams.copy(audio = d.streams.audio.map { it.copy(language = null) }), subtitles = d.subtitles.map { if (it.autoGenerated) it.copy(language = "de") else it })
        assertEquals("de", DownloadPlanner.originalLanguage(noTrack))
        assertNull(DownloadPlanner.originalLanguage(noTrack.copy(subtitles = emptyList())))
    }

    @Test fun capsFollowTheEngine() {
        val engine = EngineCapabilities(
            video = listOf(DecoderCaps(VideoCodec.H264, 3840, 2160, 60, false), DecoderCaps(VideoCodec.VP9, 3840, 2160, 60, false), DecoderCaps(VideoCodec.AV1, 1920, 1080, 30, false)),
            opus = true, hdrDisplay = false, displayHeightPx = 1440, sideloadedDash = false, autoCeilingHeight = 1440,
        )
        assertEquals(DeviceCaps(2160, 60, setOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1), 1440, joinsToWebm = true), DeviceCaps.from(engine, joinsToWebm = true))
    }
}
