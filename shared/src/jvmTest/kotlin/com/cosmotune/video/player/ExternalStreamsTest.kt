package com.cosmotune.video.player

import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalStreamsTest {
    private fun stream(height: Int, url: String = "https://v/$height") = VideoStream(
        url = url, itag = height, mimeType = "video/mp4; codecs=\"avc1\"", codec = VideoCodec.entries.first(), codecs = "avc1",
        width = height * 16 / 9, height = height, fps = 30, bitrate = height * 1000, hdr = false, contentLength = null, init = null,
        index = null, delivery = Delivery.entries.first(), hasAudio = true,
    )

    private fun set(muxed: List<VideoStream>, hls: String? = null) =
        StreamSet(videoOnly = emptyList(), muxed = muxed, audio = emptyList(), hlsUrl = hls, dashMpdUrl = null, expiresAtMs = null, requestHeaders = emptyMap(), durationMs = null)

    @Test fun theSharpestStreamWithSoundIsHandedOver() {
        assertEquals(ExternalStream("https://v/720", "video/mp4"), ExternalStreams.pick(set(listOf(stream(360), stream(720))), live = false))
    }

    @Test fun aLiveStreamIsItsHlsAddress() {
        assertEquals("https://hls", ExternalStreams.pick(set(listOf(stream(360)), hls = "https://hls"), live = true)?.url)
        assertEquals("https://hls", ExternalStreams.pick(set(emptyList(), hls = "https://hls"), live = false)?.url)
    }

    @Test fun nothingWhenNoStreamPlaysOnItsOwn() {
        assertNull(ExternalStreams.pick(set(emptyList()), live = false))
    }
}
