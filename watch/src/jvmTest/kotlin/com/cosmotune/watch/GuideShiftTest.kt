package com.cosmotune.watch

import com.cosmotune.watch.plugins.iptv.M3uParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuideShiftTest {
    @Test fun readsKodiStyleShifts() {
        assertEquals(120, M3uParser.shiftMinutes("2"))
        assertEquals(-90, M3uParser.shiftMinutes("-1.5"))
        assertEquals(330, M3uParser.shiftMinutes("+5:30"))
        assertEquals(-90, M3uParser.shiftMinutes("-1:30"))
        assertNull(M3uParser.shiftMinutes(""))
        assertNull(M3uParser.shiftMinutes("soon"))
        assertNull(M3uParser.shiftMinutes("48"), "more than a day is a mistake")
    }

    @Test fun aChannelsShiftWinsOverThePlaylists() {
        val list = M3uParser.parse(sequenceOf(
            "#EXTM3U tvg-shift=\"1\"",
            "#EXTINF:-1 tvg-id=\"a\" tvg-shift=\"-2\",A", "http://x/a",
            "#EXTINF:-1 tvg-id=\"b\",B", "http://x/b",
        ))
        assertEquals(listOf(-120, 60), list.entries.map { it.epgShiftMinutes })
    }
}
