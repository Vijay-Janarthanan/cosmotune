package com.cosmotune.video.select

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.VideoCodec
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The side-loaded DASH manifest (plan task Y-5, step 3; spec 6.2, 6.4). The addresses are made up. */
class DashManifestTest {

    private val ns = "urn:mpeg:dash:schema:mpd:2011"

    private val videoUrl = "https://rr1---sn-fake0000.googlevideo.com/videoplayback?expire=1792170000&id=o-FAKE&itag=303&c=WEB"
    private val audioUrl = "https://rr1---sn-fake0000.googlevideo.com/videoplayback?expire=1792170000&id=o-FAKE&itag=251&c=WEB"

    private val video = videoStream(
        itag = 303, codec = VideoCodec.VP9, width = 1920, height = 1080, fps = 60, kbps = 3400, url = videoUrl,
    ).copy(init = ByteRange(0, 219), index = ByteRange(220, 1_771))

    private val audio = audioStream(
        itag = 251, codec = AudioCodec.OPUS, kbps = 138, language = "en", url = audioUrl, sampleRate = 48_000, channels = 2,
    ).copy(init = ByteRange(0, 266), index = ByteRange(267, 1_514))

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun Document.all(name: String): List<Element> {
        val list = getElementsByTagNameNS(ns, name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    // ------------------------------------------------------------------ golden

    @Test
    fun theManifestOfOneSampleIsExactlyThis() {
        val expected = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" minBufferTime="PT1.500S" profiles="urn:mpeg:dash:profile:full:2011" type="static" mediaPresentationDuration="PT212.345S">
              <Period>
                <AdaptationSet id="0" mimeType="video/webm" subsegmentAlignment="true">
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
                  <Representation id="303" codecs="vp9" bandwidth="3400000" width="1920" height="1080" frameRate="60">
                    <BaseURL>https://rr1---sn-fake0000.googlevideo.com/videoplayback?expire=1792170000&amp;id=o-FAKE&amp;itag=303&amp;c=WEB</BaseURL>
                    <SegmentBase indexRange="220-1771">
                      <Initialization range="0-219"/>
                    </SegmentBase>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet id="1" lang="en" mimeType="audio/webm" subsegmentAlignment="true">
                  <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
                  <Representation id="251" codecs="opus" bandwidth="138000" audioSamplingRate="48000">
                    <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
                    <BaseURL>https://rr1---sn-fake0000.googlevideo.com/videoplayback?expire=1792170000&amp;id=o-FAKE&amp;itag=251&amp;c=WEB</BaseURL>
                    <SegmentBase indexRange="267-1514">
                      <Initialization range="0-266"/>
                    </SegmentBase>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent().replace("\r\n", "\n") + "\n"   // a checkout with CRLF must not change the golden text
        assertEquals(expected, DashManifest.build(video, audio, 212_345))
    }

    // ------------------------------------------------------------------ the parsed document

    @Test
    fun theManifestParsesNamespaceAwareWithTheExpectedElementsAndAttributes() {
        val doc = parse(DashManifest.build(video, audio, 212_345))

        val mpd = doc.documentElement
        assertEquals("MPD", mpd.localName)
        assertEquals(ns, mpd.namespaceURI)
        assertEquals("static", mpd.attr("type"))
        assertEquals("PT1.500S", mpd.attr("minBufferTime"))
        assertEquals("PT212.345S", mpd.attr("mediaPresentationDuration"))
        assertEquals("urn:mpeg:dash:profile:full:2011", mpd.attr("profiles"))

        assertEquals(1, doc.all("Period").size)
        val sets = doc.all("AdaptationSet")
        assertEquals(2, sets.size)
        assertEquals("video/webm", sets[0].attr("mimeType"))
        assertEquals("audio/webm", sets[1].attr("mimeType"))
        assertEquals("en", sets[1].attr("lang"))
        assertNull(sets[0].attr("lang"))

        val reps = doc.all("Representation")
        assertEquals(2, reps.size)
        val v = reps[0]
        assertEquals("303", v.attr("id"))
        assertEquals("vp9", v.attr("codecs"))
        assertEquals("3400000", v.attr("bandwidth"))
        assertEquals("1920", v.attr("width"))
        assertEquals("1080", v.attr("height"))
        assertEquals("60", v.attr("frameRate"))
        val a = reps[1]
        assertEquals("251", a.attr("id"))
        assertEquals("opus", a.attr("codecs"))
        assertEquals("138000", a.attr("bandwidth"))
        assertEquals("48000", a.attr("audioSamplingRate"))

        val channels = doc.all("AudioChannelConfiguration").single()
        assertEquals("urn:mpeg:dash:23003:3:audio_channel_configuration:2011", channels.attr("schemeIdUri"))
        assertEquals("2", channels.attr("value"))
        assertEquals(a, channels.parentNode)

        val bases = doc.all("SegmentBase")
        assertEquals(listOf("220-1771", "267-1514"), bases.map { it.attr("indexRange") })
        val inits = doc.all("Initialization")
        assertEquals(listOf("0-219", "0-266"), inits.map { it.attr("range") })
        assertEquals(bases[0], inits[0].parentNode)
        assertEquals(v, bases[0].parentNode)

        // the parser unescapes: the address comes back as it went in
        assertEquals(listOf(videoUrl, audioUrl), doc.all("BaseURL").map { it.textContent })
    }

    @Test
    fun anMp4SampleKeepsItsMediaTypesCodecsAndStereo() {
        val sample = SampleStreams.uhd
        val v = sample.videoOnly.first { it.itag == 137 }
        val a = sample.audio.first { it.itag == 140 }
        val doc = parse(DashManifest.build(v, a, sample.durationMs!!))
        val sets = doc.all("AdaptationSet")
        assertEquals(listOf("video/mp4", "audio/mp4"), sets.map { it.attr("mimeType") })
        val reps = doc.all("Representation")
        assertEquals("avc1.640028", reps[0].attr("codecs"))
        assertEquals("30", reps[0].attr("frameRate"))
        assertEquals("mp4a.40.2", reps[1].attr("codecs"))
        assertEquals("44100", reps[1].attr("audioSamplingRate"))
        assertEquals("PT212.000S", doc.documentElement.attr("mediaPresentationDuration"))
    }

    // ------------------------------------------------------------------ escaping

    @Test
    fun addressesAreXmlEscapedAndComeBackThroughTheParser() {
        val nasty = "https://h.googlevideo.com/videoplayback?a=1&b=<2>&c=\"3\"&d='4'"
        val manifest = DashManifest.build(video.copy(url = nasty), audio.copy(url = nasty + "&x=5"), 1_000)
        assertTrue(manifest.contains("<BaseURL>https://h.googlevideo.com/videoplayback?a=1&amp;b=&lt;2&gt;&amp;c=&quot;3&quot;&amp;d=&apos;4&apos;</BaseURL>"))
        assertFalse(manifest.contains("a=1&b"), "a bare ampersand must not survive")
        assertEquals(listOf(nasty, nasty + "&x=5"), parse(manifest).all("BaseURL").map { it.textContent })
    }

    @Test
    fun valuesInAttributesAreEscapedToo() {
        val v = video.copy(codecs = "vp9\"&<x>")
        val a = audio.copy(language = "en&US")
        val doc = parse(DashManifest.build(v, a, 1_000))
        assertEquals("vp9\"&<x>", doc.all("Representation")[0].attr("codecs"))
        assertEquals("en&US", doc.all("AdaptationSet")[1].attr("lang"))
    }

    // ------------------------------------------------------------------ the details

    @Test
    fun theDurationIsSecondsWithThreeDecimals() {
        fun duration(ms: Long) = parse(DashManifest.build(video, audio, ms)).documentElement.attr("mediaPresentationDuration")
        assertEquals("PT0.001S", duration(1))
        assertEquals("PT0.999S", duration(999))
        assertEquals("PT1.000S", duration(1_000))
        assertEquals("PT61.050S", duration(61_050))
        assertEquals("PT3723.456S", duration(3_723_456))
        assertEquals("PT36000.000S", duration(36_000_000))
    }

    @Test
    fun anUnknownDurationLeavesTheAttributeOutRatherThanClaimingAVideoOfNoLength() {
        for (ms in listOf(0L, -5L)) {
            val root = parse(DashManifest.build(video, audio, ms)).documentElement
            assertNull(root.attr("mediaPresentationDuration"), "duration $ms")
            assertEquals("static", root.attr("type"))
            assertEquals("PT1.500S", root.attr("minBufferTime"))
        }
    }

    @Test
    fun theDeliveryMethodOfAStreamDoesNotMatterOnlyItsRanges() {
        // the NewPipe adapter marks YouTube's adaptive streams PROGRESSIVE, with their init and index ranges
        val v = video.copy(delivery = Delivery.PROGRESSIVE)
        val a = audio.copy(delivery = Delivery.PROGRESSIVE)
        assertEquals(DashManifest.build(video.copy(delivery = Delivery.DASH), audio.copy(delivery = Delivery.DASH), 212_345), DashManifest.build(v, a, 212_345))
        assertEquals(2, parse(DashManifest.build(v, a, 212_345)).all("SegmentBase").size)
    }

    @Test
    fun aMediaTypeLoosesItsParameters() {
        val v = video.copy(mimeType = "video/webm; codecs=\"vp9\"")
        val a = audio.copy(mimeType = " audio/webm ;codecs=opus")
        val sets = parse(DashManifest.build(v, a, 1_000)).all("AdaptationSet")
        assertEquals("video/webm", sets[0].attr("mimeType"))
        assertEquals("audio/webm", sets[1].attr("mimeType"))
    }

    @Test
    fun whatTheSourceDidNotGiveIsLeftOut() {
        val v = video.copy(width = 0, height = 0, fps = 0, bitrate = 0)
        val a = audio.copy(language = null, sampleRate = null, channels = null, bitrate = 0)
        val doc = parse(DashManifest.build(v, a, 1_000))
        val reps = doc.all("Representation")
        listOf("width", "height", "frameRate", "bandwidth").forEach { assertNull(reps[0].attr(it), it) }
        listOf("audioSamplingRate", "bandwidth").forEach { assertNull(reps[1].attr(it), it) }
        assertNull(doc.all("AdaptationSet")[1].attr("lang"))
        assertTrue(doc.all("AudioChannelConfiguration").isEmpty())
        // the rest is still there
        assertEquals("303", reps[0].attr("id"))
        assertEquals("vp9", reps[0].attr("codecs"))
        assertEquals(2, doc.all("SegmentBase").size)
    }

    @Test
    fun anAudioTracksKindIsItsDashRole() {
        fun role(kind: AudioTrackKind): String? =
            parse(DashManifest.build(video, audio.copy(trackKind = kind), 1_000)).all("Role")[1].attr("value")
        assertEquals("main", role(AudioTrackKind.ORIGINAL))
        assertEquals("main", role(AudioTrackKind.UNKNOWN))
        assertEquals("dub", role(AudioTrackKind.DUBBED))
        assertEquals("description", role(AudioTrackKind.DESCRIPTIVE))
        assertEquals("alternate", role(AudioTrackKind.SECONDARY))
        val roles = parse(DashManifest.build(video, audio, 1_000)).all("Role")
        assertEquals(listOf("urn:mpeg:dash:role:2011", "urn:mpeg:dash:role:2011"), roles.map { it.attr("schemeIdUri") })
        assertEquals("main", roles[0].attr("value"))
    }

    @Test
    fun aStreamWithoutRangesCannotBePutInAManifest() {
        assertFailsWith<IllegalArgumentException> { DashManifest.build(video.copy(init = null), audio, 1_000) }
        assertFailsWith<IllegalArgumentException> { DashManifest.build(video.copy(index = null), audio, 1_000) }
        assertFailsWith<IllegalArgumentException> { DashManifest.build(video, audio.copy(init = null), 1_000) }
        assertFailsWith<IllegalArgumentException> { DashManifest.build(video, audio.copy(index = null), 1_000) }
    }
}
