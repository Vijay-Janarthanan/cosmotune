package com.cosmotune.watch

import com.cosmotune.watch.live.SpotlightSources
import com.cosmotune.watch.model.LiveEventKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotlightSourcesTest {
    @Test fun headlinesKeepPublisherAndSkipStaleOrUnsafeEntries() {
        val now = Instant.parse("2026-11-09T09:00:00Z").toEpochMilli()
        val headlines = SpotlightSources.parseHeadlines(
            """<rss><channel>
              <item><title>சென்னையில் மழை - Example</title><source>Example</source><link>https://news.google.com/rss/articles/a</link>
                <pubDate>Mon, 9 Nov 2026 08:00:00 GMT</pubDate></item>
              <item><title>Old</title><link>https://news.google.com/rss/articles/b</link>
                <pubDate>Fri, 6 Nov 2026 08:00:00 GMT</pubDate></item>
              <item><title>Other host</title><link>https://example.com/c</link>
                <pubDate>Mon, 9 Nov 2026 08:00:00 GMT</pubDate></item>
            </channel></rss>""", now,
        )
        val item = headlines.single()
        assertEquals("சென்னையில் மழை", item.title)
        assertEquals("Example", item.source)
        assertEquals(LiveEventKind.NEWS, item.kind)
        assertFalse(item.isLive)
        assertTrue(SpotlightSources.parseHeadlines("""<!DOCTYPE rss [<!ENTITY a SYSTEM "file:///private">]><rss/>""", now).isEmpty())
    }
}
