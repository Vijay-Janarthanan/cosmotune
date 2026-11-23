package com.cosmotune.video.source

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoProxyTest {
    @AfterTest fun direct() = VideoProxy.set(null)

    @Test fun theUsualFormsAreRead() {
        val tor = VideoProxy.parse("socks5://127.0.0.1:9050")!!
        assertEquals(Proxy.Type.SOCKS, tor.type())
        assertEquals(9050, (tor.address() as InetSocketAddress).port)
        assertEquals(Proxy.Type.HTTP, VideoProxy.parse("proxy.lan:3128")!!.type())
        assertEquals(Proxy.NO_PROXY, VideoProxy.parse("  "))
        assertNull(VideoProxy.parse("ftp://x:1"))
        assertNull(VideoProxy.parse("http://:80"))
    }

    @Test fun theSelectorFollowsTheSetting() {
        VideoProxy.set("http://10.0.0.2:8080")
        assertEquals(Proxy.Type.HTTP, VideoProxy.selector.select(URI("https://www.youtube.com/")).single().type())
        assertEquals("http://10.0.0.2:8080", VideoProxy.mpvHttpProxy())
        VideoProxy.set("")
        assertEquals(Proxy.NO_PROXY, VideoProxy.selector.select(URI("https://www.youtube.com/")).single())
        assertEquals("", VideoProxy.mpvHttpProxy())
    }
}
