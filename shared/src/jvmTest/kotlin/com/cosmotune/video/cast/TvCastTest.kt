package com.cosmotune.video.cast

import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TvCastTest {
    private fun muxed(itag: Int, h: Int, mime: String = "video/mp4; codecs=\"avc1\"", codec: VideoCodec = VideoCodec.H264, url: String = "https://g/$itag") =
        VideoStream(url, itag, mime, codec, "avc1", h * 16 / 9, h, 30, h * 1000, false, null, null, null, Delivery.PROGRESSIVE, hasAudio = true)

    private fun set(muxed: List<VideoStream>, hls: String? = null, headers: Map<String, String> = mapOf("User-Agent" to "x")) =
        StreamSet(emptyList(), muxed, emptyList(), hls, null, null, headers, null)

    @Test fun theBestFileWithSoundInsideIsChosenAndItsQualityNamed() {
        val choice = TvCast.choose(set(listOf(muxed(18, 360), muxed(22, 720))))
        assertIs<TvCastChoice.Muxed>(choice)
        assertEquals(22, choice.stream.itag)
        assertEquals("720p", choice.label)
        assertEquals(mapOf("User-Agent" to "x"), choice.headers)
    }

    @Test fun anHlsAddressWinsWhenTheSourceOffersOne() {
        val choice = TvCast.choose(set(listOf(muxed(22, 720)), hls = "https://g/live.m3u8"))
        assertIs<TvCastChoice.Hls>(choice)
        assertEquals("application/vnd.apple.mpegurl", TvCast.mimeOf(choice))
    }

    @Test fun mp4WithH264BeatsWebmAtTheSameSize() {
        val choice = TvCast.choose(set(listOf(muxed(43, 720, "video/webm", VideoCodec.VP9), muxed(22, 720))))
        assertEquals(22, (choice as TvCastChoice.Muxed).stream.itag)
    }

    @Test fun splitPictureAndSoundOnlyCannotBeCast() {
        assertNull(TvCast.choose(set(emptyList())))
        assertNull(TvCast.choose(null))
    }
}
