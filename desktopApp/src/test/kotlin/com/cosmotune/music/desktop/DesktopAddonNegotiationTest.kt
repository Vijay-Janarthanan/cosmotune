package com.cosmotune.music.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the desktop asks an HTTP add-on for a quality tier and for the stereo or immersive mix, over a loopback add-on.
 * The Atmos preference is injected, so no stored setting is read.
 */
class DesktopAddonNegotiationTest {
    private class Fixture(manifest: String) : AutoCloseable {
        val queries = CopyOnWriteArrayList<Map<String, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/manifest.json") { exchange -> reply(exchange, manifest) }
            createContext("/search") { exchange -> record(exchange); reply(exchange, """{"tracks":[]}""") }
            createContext("/stream/") { exchange -> record(exchange); reply(exchange, """{"url":"https://media.example/a.flac","format":"flac"}""") }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"

        private fun record(exchange: com.sun.net.httpserver.HttpExchange) {
            queries += exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
        }

        private fun reply(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() = server.stop(0)
    }

    private val ladder = """{"id":"ladder","resources":["search","stream"],"settings":[
        {"key":"quality","type":"select","default":"normal","options":[{"value":"low"},{"value":"normal"},{"value":"high"},{"value":"max"}]}]}"""

    @Test fun eachTierIsAskedForInTheAddonsOwnWords() = runBlocking {
        Fixture(ladder).use { addon ->
            val client = DesktopAddonClient(addon.base, atmosWanted = { false })
            client.search("song", DesktopAddonClient.TIER_LOSSLESS).getOrThrow()
            client.search("song", DesktopAddonClient.TIER_HIGH).getOrThrow()
            client.search("song", DesktopAddonClient.TIER_LOW).getOrThrow()
            assertEquals(listOf("max", "high", "low"), addon.queries.map { it["quality"] })
        }
        Fixture("""{"id":"free","resources":["search"]}""").use { addon ->
            DesktopAddonClient(addon.base, atmosWanted = { false }).search("song", DesktopAddonClient.TIER_HIGH).getOrThrow()
            assertEquals("HIGH", addon.queries.single()["quality"], "an addon without options is sent the tier verbatim")
        }
    }

    @Test fun theAtmosHintTravelsOnlyWhenWantedAndAPickedStereoRowNeverCarriesIt() = runBlocking {
        Fixture(ladder).use { addon ->
            val wanting = DesktopAddonClient(addon.base, atmosWanted = { true })
            wanting.stream("t1", DesktopAddonClient.TIER_LOSSLESS).getOrThrow()
            wanting.stream("t2", DesktopAddonClient.TIER_LOSSLESS, stereoOnly = true).getOrThrow()
            DesktopAddonClient(addon.base, atmosWanted = { false }).stream("t3", DesktopAddonClient.TIER_LOSSLESS).getOrThrow()
            assertEquals("auto", addon.queries[0]["atmos"])
            assertNull(addon.queries[1]["atmos"])
            assertNull(addon.queries[2]["atmos"])
            assertTrue(addon.queries.all { it["quality"] == "max" })
        }
    }

    @Test fun aDeclaredAtmosSettingIsRespectedAndPinnedToItsOffValueForAStereoPick() = runBlocking {
        val declared = """{"id":"mixes","resources":["search","stream"],"settings":[
            {"key":"atmos","type":"select","default":"prefer","options":[{"value":"prefer"},{"value":"stereo"}]}]}"""
        Fixture(declared).use { addon ->
            val client = DesktopAddonClient(addon.base, atmosWanted = { true })
            client.stream("t1", DesktopAddonClient.TIER_LOSSLESS).getOrThrow()
            client.stream("t2", DesktopAddonClient.TIER_LOSSLESS, stereoOnly = true).getOrThrow()
            assertEquals("prefer", addon.queries[0]["atmos"], "the hint never overrides an addon's own default")
            assertEquals("stereo", addon.queries[1]["atmos"])
        }
        Fixture("""{"id":"opaque","resources":["search","stream"],"settings":[{"key":"atmos","default":"on"}]}""").use { addon ->
            DesktopAddonClient(addon.base, atmosWanted = { true }).stream("t", DesktopAddonClient.TIER_LOSSLESS, stereoOnly = true).getOrThrow()
            assertEquals("0", addon.queries.single()["atmos"], "no declared off value: the protocol's 0")
        }
    }

    @Test fun rowLabelsTellAtmosAndHiResApart() {
        val json = Json { ignoreUnknownKeys = true }
        fun row(text: String) = json.decodeFromString(DesktopAddonTrack.serializer(), text)
        assertTrue(row("""{"id":"a","audioQuality":"HI_RES_LOSSLESS","format":"flac"}""").isHiRes)
        assertTrue(row("""{"id":"b","audioQuality":"24-bit 96kHz"}""").isHiRes)
        assertFalse(row("""{"id":"c","audioQuality":"LOSSLESS","format":"flac"}""").isHiRes)
        assertTrue(row("""{"id":"d","audioQuality":"LOW","audioModes":["DOLBY_ATMOS"]}""").isDolbyAtmos)
        assertFalse(row("""{"id":"e","audioQuality":"LOSSLESS","audioModes":["STEREO"]}""").isDolbyAtmos)
    }
}
