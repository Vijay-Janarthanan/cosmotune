package com.cosmotune.watch

import com.cosmotune.watch.plugins.iptv.M3uParser
import kotlin.test.Test
import kotlin.test.assertEquals

class M3uHeadersTest {
    @Test fun kodiPropsAndExtHttpSetAnEntrysHeaders() {
        val list = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="One.in",One
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F21&Referer=https://ref.example/
            https://one.example/live.m3u8
            #EXTINF:-1 tvg-id="Two.in",Two
            #EXTHTTP:{"User-Agent":"TiviMate/5","Origin":"https://two.example"}
            https://two.example/live.m3u8
        """.trimIndent())
        assertEquals(mapOf("User-Agent" to "Kodi/21", "Referer" to "https://ref.example/"), list.entries[0].headers)
        assertEquals(mapOf("User-Agent" to "TiviMate/5", "Origin" to "https://two.example"), list.entries[1].headers)
    }
}
