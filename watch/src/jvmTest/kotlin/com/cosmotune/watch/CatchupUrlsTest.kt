package com.cosmotune.watch

import com.cosmotune.watch.model.Catchup
import com.cosmotune.watch.plugins.iptv.CatchupUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatchupUrlsTest {
    // 2026-11-23 10:00:00 UTC to 11:00, asked at 12:00.
    private val start = 1_795_428_000_000L
    private val end = start + 3_600_000
    private val now = start + 7_200_000

    @Test fun defaultFillsTheTemplate() {
        val c = Catchup("default", 3, "http://tv.example/arch/{Y}-{m}-{d}/{H}{M}.m3u8?start=\${start}&dur={duration}&o={offset}")
        assertEquals("http://tv.example/arch/2026-11-23/1000.m3u8?start=1795428000&dur=3600&o=7200", CatchupUrls.build("http://tv.example/live.m3u8", c, start, end, now))
    }

    @Test fun appendAndShiftAddToTheLiveAddress() {
        assertEquals("http://tv.example/live.m3u8?utc=1795428000&lutc=1795435200",
            CatchupUrls.build("http://tv.example/live.m3u8", Catchup("shift", 1), start, end, now))
        assertEquals("http://tv.example/live.m3u8?a=1&utc=1795428000&lutc=1795435200",
            CatchupUrls.build("http://tv.example/live.m3u8?a=1", Catchup("shift", 1), start, end, now))
        assertEquals("http://tv.example/live.m3u8?from={nothing}&t=1795428000",
            CatchupUrls.build("http://tv.example/live.m3u8", Catchup("append", 1, "?from={nothing}&t={utc}"), start, end, now))
    }

    @Test fun flussonicAndXtreamRewriteThePath() {
        assertEquals("http://fs.example/ch1/video-1795428000-3600.m3u8?token=x",
            CatchupUrls.build("http://fs.example/ch1/video.m3u8?token=x", Catchup("flussonic", 1), start, end, now))
        assertEquals("http://fs.example/ch1/timeshift_abs-1795428000.ts",
            CatchupUrls.build("http://fs.example/ch1/mpegts", Catchup("fs", 1), start, end, now))
        assertEquals("http://xc.example:8080/timeshift/me/pw/60/2026-11-23:10-00/417.ts",
            CatchupUrls.build("http://xc.example:8080/live/me/pw/417.ts", Catchup("xc", 2), start, end, now))
    }

    @Test fun nothingForTheFutureOrAMissingTemplate() {
        assertNull(CatchupUrls.build("http://tv.example/live.m3u8", Catchup("shift", 1), now + 1, now + 100, now))
        assertNull(CatchupUrls.build("http://tv.example/live.m3u8", Catchup("default", 1), start, end, now))
        assertNull(CatchupUrls.build("http://tv.example/live.m3u8", Catchup("vod", 1), start, end, now))
    }
}
