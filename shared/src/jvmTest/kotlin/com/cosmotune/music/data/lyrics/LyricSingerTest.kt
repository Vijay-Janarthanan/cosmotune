package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricSingerTest {
    @Test fun aNameIsKeptAndANumberIsNot() {
        assertEquals("Mira", LyricSinger.name("  Mira "))
        assertNull(LyricSinger.name("v1"))
        assertNull(LyricSinger.name("V2"))
        assertNull(LyricSinger.name("3"))
        assertNull(LyricSinger.name(" "))
        assertNull(LyricSinger.name(null))
    }

    @Test fun ttmlAgentsWithNamesNameTheirLines() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata>
                <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Mira</ttm:name></ttm:agent>
                <ttm:agent type="person" xml:id="v2"><ttm:name type="full">Juno</ttm:name></ttm:agent>
              </metadata></head>
              <body><div>
                <p begin="1.000" end="2.000" ttm:agent="v1">We drew a map</p>
                <p begin="3.000" end="4.000" ttm:agent="v2">every light</p>
              </div></body>
            </tt>
        """.trimIndent()
        val lines = TtmlLyrics.parse(ttml).filterNot { it.isGap }
        assertEquals(listOf("Mira", "Juno"), lines.map { it.singer })
        assertEquals(LyricAlignment.End, lines[1].alignment)
    }

    @Test fun ttmlAgentsWithoutNamesNameNobody() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/></metadata></head>
              <body><div>
                <p begin="1.000" end="2.000" ttm:agent="v1">We drew a map</p>
                <p begin="3.000" end="4.000" ttm:agent="v2">every light</p>
              </div></body>
            </tt>
        """.trimIndent()
        assertEquals(listOf(null, null), TtmlLyrics.parse(ttml).filterNot { it.isGap }.map { it.singer })
    }
}
