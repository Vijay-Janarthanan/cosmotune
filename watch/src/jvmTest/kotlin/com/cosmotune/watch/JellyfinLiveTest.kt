package com.cosmotune.watch

import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.plugins.servers.JellyfinPlugin
import com.cosmotune.watch.plugins.servers.ServerSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinLiveTest {
    @Test fun aServersLiveTvBecomesChannelsWithItsGuideAndStreams() = runBlocking<Unit> {
        TestServer().use { s ->
            s.json("/LiveTv/Channels", """{"Items":[{"Id":"ch1","Name":"BBC One","ChannelNumber":"101","ImageTags":{"Primary":"t1"},"ChannelType":"TV"},
                {"Id":"ch2","Name":"Radio 4","ChannelType":"Radio"}]}""")
            s.json("/LiveTv/Programs", """{"Items":[{"ChannelId":"ch1","Name":"News at Six","StartDate":"2026-11-23T18:00:00.0000000Z","EndDate":"2026-11-23T18:30:00.0000000Z","IsNews":true,"Genres":["News"]},
                {"ChannelId":"ch1","Name":"A Film","EpisodeTitle":null,"StartDate":"2026-11-23T18:30:00Z","EndDate":"2026-11-23T20:30:00Z","IsMovie":true,"Overview":"Plot"}]}""")
            s.json("/Items/ch1/PlaybackInfo", """{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","LiveStreamId":"ls1","SupportsDirectStream":true,"TranscodingUrl":"/videos/ch1/master.m3u8?x=1"}]}""")
            val plugin = JellyfinPlugin("jf-1", "Home", s.http(), s.base, ServerSession("tok", "u1"))
            val channels = plugin.channels()
            assertEquals(listOf("BBC One", "Radio 4"), channels.map { it.name })
            assertEquals(101, channels[0].number)
            assertEquals("${s.base}/Items/ch1/Images/Primary?tag=t1&maxHeight=120", channels[0].logoUrl)
            assertTrue(channels[1].isRadio)
            val guide = plugin.guide(channels.take(1), 0, Long.MAX_VALUE / 2)
            assertEquals(listOf("News at Six", "A Film"), guide.map { it.title })
            assertEquals(30 * 60_000L, guide[0].stopMs - guide[0].startMs)
            assertEquals(listOf("Movie"), guide[1].categories)
            assertEquals(listOf("News"), guide[0].categories)
            val streams = plugin.streams(PlayTarget.Live(channels[0]))
            assertEquals(listOf(StreamProtocol.PROGRESSIVE, StreamProtocol.HLS), streams.map { it.protocol })
            assertTrue(streams.all { it.isLive && it.headers["X-Emby-Token"] == "tok" && "tok" !in it.url })
            assertEquals("${s.base}/Videos/ch1/stream?static=true&MediaSourceId=ms1&LiveStreamId=ls1", streams[0].url)
            assertEquals("${s.base}/videos/ch1/master.m3u8?x=1", streams[1].url)
            assertTrue(s.requests.first { it.path!!.startsWith("/Items/ch1/PlaybackInfo") }.body.readUtf8().contains("\"AutoOpenLiveStream\":true"))
        }
    }

    @Test fun aServerWithoutLiveTvHasNoChannels() = runBlocking<Unit> {
        TestServer().use { s ->
            s.on("/LiveTv/Channels") { okhttp3.mockwebserver.MockResponse().setResponseCode(404) }
            assertTrue(JellyfinPlugin("jf-1", "Home", s.http(), s.base, ServerSession("tok", "u1")).channels().isEmpty())
        }
    }
}
