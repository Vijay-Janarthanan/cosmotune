package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.C0
import com.cosmotune.video.importers.ImportFixtures.V0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportSupportTest {
    @Test
    fun timesAreReadLikeJavaTimeReadsThem() {
        val cases = mapOf(
            "2026-10-15T08:30:00.123Z" to "2026-10-15T08:30:00.123Z",
            "2026-10-15T08:30:00Z" to "2026-10-15T08:30:00Z",
            "2026-10-15T08:30:00+05:30" to "2026-10-15T03:00:00Z",
            "2026-10-15T08:30:00-0800" to "2026-10-15T16:30:00Z",
            "2026-10-15T08:30:00.1Z" to "2026-10-15T08:30:00.100Z",
            "2026-10-15T08:30:00.123456789Z" to "2026-10-15T08:30:00.123Z",
            "2018-07-06 20:16:24 UTC" to "2018-07-06T20:16:24Z",
            "2018-07-06 20:16:24" to "2018-07-06T20:16:24Z",
            "2024-02-29T23:59:59Z" to "2024-02-29T23:59:59Z",
            "2000-03-01" to "2000-03-01T00:00:00Z",
            "1970-01-01T00:00:00Z" to "1970-01-01T00:00:00Z",
            "1969-12-31T23:59:59Z" to "1969-12-31T23:59:59Z",
            "2100-12-31T00:00:00Z" to "2100-12-31T00:00:00Z",
            " 2026-10-15T08:30Z " to "2026-10-15T08:30:00Z",
        )
        for ((text, iso) in cases) assertEquals(ImportFixtures.ms(iso), ImportSupport.parseTimeMs(text), text)
    }

    @Test
    fun textThatIsNotATimeIsNull() {
        for (text in listOf("", "yesterday", "2026-13-01T00:00:00Z", "2026-10-32", "2026/10/15", "2026-10-15T25:00:00Z", "2026-10-15T08:30:00 PST", "2026-10-15T0830Z", "20261015")) {
            assertNull(ImportSupport.parseTimeMs(text), text)
        }
    }

    @Test
    fun idsHaveTheRealLengths() {
        assertTrue(ImportSupport.isChannelId(C0))
        assertFalse(ImportSupport.isChannelId("UCfakechannel0000000001"), "the 23-character ids of the fake source are not real ids")
        assertFalse(ImportSupport.isChannelId("XX" + C0.drop(2)))
        assertFalse(ImportSupport.isChannelId(C0 + "a"))
        assertTrue(ImportSupport.isVideoId(V0))
        assertFalse(ImportSupport.isVideoId("short"))
        assertFalse(ImportSupport.isVideoId("has space!!"))
    }

    @Test
    fun aChannelIdIsFoundInsideAnAddressButNotInsideALongerRun() {
        assertEquals(C0, ImportSupport.channelIdIn("https://x/feed?channel_id=$C0"))
        assertEquals(C0, ImportSupport.channelIdIn(C0))
        assertEquals(C0, ImportSupport.channelIdIn("UCshort and then $C0"))
        assertNull(ImportSupport.channelIdIn("x${C0}"))
        assertNull(ImportSupport.channelIdIn("${C0}x"))
        assertNull(ImportSupport.channelIdIn("no ids here"))
    }

    @Test
    fun channelAddressesAreSortedIntoIdsAndLookups() {
        fun ref(url: String) = ImportSupport.channelRefOf(url)
        assertEquals(ChannelRef.Id(C0), ref("https://www.youtube.com/channel/$C0"))
        assertEquals(ChannelRef.Id(C0), ref("http://youtube.com/channel/$C0/videos?x=1#top"))
        assertEquals(ChannelRef.Id(C0), ref("/channel/$C0"))
        assertEquals(ChannelRef.Unresolved("https://www.youtube.com/user/Name"), ref("https://www.youtube.com/user/Name"))
        assertEquals(ChannelRef.Unresolved("https://m.youtube.com/c/Name"), ref("http://m.youtube.com/c/Name/videos"))
        assertEquals(ChannelRef.Unresolved("https://www.youtube.com/@handle"), ref("https://www.youtube.com/@handle"))
        assertEquals(ChannelRef.Unresolved("https://www.youtube.com/@handle"), ref("/@handle"), "an address with no host gets the default one")
        assertNull(ref("https://www.youtube.com/channel/short"))
        assertNull(ref("https://www.youtube.com/@"))
        assertNull(ref("https://www.youtube.com/playlist?list=PLx"))
        assertNull(ref(""))
    }

    @Test
    fun videoAddressesGiveTheirIdButMusicAddressesDoNot() {
        fun id(url: String) = ImportSupport.videoIdOf(url)
        assertEquals(V0, id("https://www.youtube.com/watch?v=$V0"))
        assertEquals(V0, id("https://www.youtube.com/watch?feature=share&v=$V0&t=3"))
        assertEquals(V0, id("https://youtu.be/$V0?t=3"))
        assertEquals(V0, id("https://www.youtube.com/shorts/$V0"))
        assertEquals(V0, id("https://www.youtube.com/embed/$V0"))
        assertNull(id("https://music.youtube.com/watch?v=$V0"))
        assertNull(id("https://www.youtube.com/watch?v=short"))
        assertNull(id("https://www.youtube.com/results?search_query=wood"))
        assertNull(id("https://www.youtube.com/channel/$C0"))
    }

    @Test
    fun aPlaylistIdComesFromTheListParameter() {
        assertEquals("PLabc", ImportSupport.playlistIdOf("https://www.youtube.com/playlist?list=PLabc"))
        assertEquals("PLabc", ImportSupport.playlistIdOf("https://www.youtube.com/watch?v=$V0&list=PLabc&index=2"))
        assertNull(ImportSupport.playlistIdOf("https://www.youtube.com/playlist"))
        assertNull(ImportSupport.playlistIdOf("https://www.youtube.com/playlist?list="))
    }
}
