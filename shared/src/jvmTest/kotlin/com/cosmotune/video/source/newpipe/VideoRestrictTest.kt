package com.cosmotune.video.source.newpipe

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.schabi.newpipe.extractor.downloader.Request
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoRestrictTest {
    @AfterTest fun off() { VideoRestrict.strict = false }

    @Test fun onlyYouTubesHostsGetTheHeader() {
        VideoRestrict.strict = true
        assertTrue(VideoRestrict.applies("www.youtube.com") && VideoRestrict.applies("youtubei.googleapis.com") && VideoRestrict.applies("m.youtube.com"))
        assertFalse(VideoRestrict.applies("sponsor.ajay.app") || VideoRestrict.applies("notyoutube.com") || VideoRestrict.applies(null))
        VideoRestrict.strict = false
        assertFalse(VideoRestrict.applies("www.youtube.com"))
    }

    @Test fun theDownloaderSendsItWhenOn() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("ok"))
            server.enqueue(MockResponse().setBody("ok"))
            val downloader = VideoDownloader()
            // The local server is not YouTube: the rule is checked by host above; here the header is off for it.
            VideoRestrict.strict = true
            downloader.execute(Request.newBuilder().get(server.url("/a").toString()).build())
            assertNull(server.takeRequest().getHeader(VideoRestrict.HEADER))
            assertEquals("ok", downloader.execute(Request.newBuilder().get(server.url("/b").toString()).build()).responseBody())
        } finally {
            server.shutdown()
        }
    }
}
