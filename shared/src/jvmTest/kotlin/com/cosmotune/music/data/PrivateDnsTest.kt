package com.cosmotune.music.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivateDnsTest {
    private var now = 1_000_000L
    private var asked = 0

    @AfterTest fun tearDown() { PrivateDnsResolver.choice = PrivateDns.OFF; PrivateDnsResolver.clearForTests(); PrivateDnsResolver.clock = System::currentTimeMillis }

    @Test fun namesAreAskedOverHttpsAndKeptForTheirTimeToLive() {
        PrivateDnsResolver.clock = { now }
        PrivateDnsResolver.ask = { resolver, name, type ->
            asked++
            assertEquals(PrivateDns.CLOUDFLARE, resolver)
            if (type == "A") """{"Status":0,"Answer":[{"name":"$name","type":5,"TTL":30,"data":"alias.example."},{"name":"$name","type":1,"TTL":120,"data":"142.250.1.1"}]}"""
            else """{"Status":0,"Answer":[{"name":"$name","type":28,"TTL":300,"data":"2a00:1450::1"}]}"""
        }
        PrivateDnsResolver.choice = PrivateDns.CLOUDFLARE
        val first = PrivateDnsResolver.lookup("music.youtube.com")
        assertEquals(listOf("142.250.1.1", "2a00:1450:0:0:0:0:0:1"), first.map { it.hostAddress })
        assertEquals("music.youtube.com", first.first().hostName)
        PrivateDnsResolver.lookup("music.youtube.com")
        assertEquals(2, asked, "kept")
        now += 121_000
        PrivateDnsResolver.lookup("music.youtube.com")
        assertEquals(4, asked, "asked again once the time to live ran out")
    }

    @Test fun localNamesAndAddressesAreNotSentOut() {
        assertFalse(PrivateDnsResolver.worthResolving("192.168.1.10"))
        assertFalse(PrivateDnsResolver.worthResolving("nas.local"))
        assertFalse(PrivateDnsResolver.worthResolving("localhost"))
        assertFalse(PrivateDnsResolver.worthResolving("::1"))
        assertTrue(PrivateDnsResolver.worthResolving("lrclib.net"))
    }

    @Test fun aFailedAnswerGivesNothing() {
        assertEquals(null, PrivateDnsResolver.parse("""{"Status":3}""", "x.example", 1))
        assertTrue(PrivateDnsResolver.parse("""{"Status":0,"Answer":[{"type":1,"data":"not an ip"}]}""", "x.example", 1)!!.first.isEmpty())
        assertEquals(PrivateDns.OFF, PrivateDns.named("nope"))
    }
}
