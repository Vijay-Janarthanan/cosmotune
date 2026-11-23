package com.cosmotune.remote

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PhoneSignInTest {

    @Test fun sealedTextOpensOnlyWithItsKey() {
        val key = PhoneSignIn.newKey()
        val sealed = PhoneSignIn.seal(key, "SID=abc; SAPISID=def")
        assertEquals("SID=abc; SAPISID=def", PhoneSignIn.open(key, sealed))
        assertNull(PhoneSignIn.open(PhoneSignIn.newKey(), sealed))
        assertNull(PhoneSignIn.open(key, sealed.dropLast(2) + "AA"))
        assertFalse("SAPISID" in sealed)
    }

    @Test fun theAppLinkCarriesTheComputerCodeAndKey() {
        val link = PhoneSignIn.Link("http://192.168.1.20:41234", "c0de", "a2V5")
        val parsed = assertNotNull(PhoneSignIn.Link.parse(link.appLink()))
        assertEquals(link, parsed)
        assertEquals("192.168.1.20", parsed.computer)
        assertNull(PhoneSignIn.Link.parse("cosmotune://signin?to=https%3A%2F%2Fevil.example&c=x&k=y"))
        assertNull(PhoneSignIn.Link.parse("cosmotune://party?to=x"))
    }

    @Test fun theComputerTakesOneSealedSessionAndNothingElse() = runBlocking {
        val receiver = PhoneSignIn.Receiver.start(InetAddress.getByName("127.0.0.1"))
        try {
            val link = receiver.link
            // The page the phone's camera opens names no key: it reads it from the fragment.
            val page = URI(receiver.pageUrl).toURL().openConnection() as HttpURLConnection
            val html = page.inputStream.readBytes().decodeToString()
            assertEquals(200, page.responseCode)
            assertFalse(link.key in html)
            assertTrue(receiver.pageUrl.endsWith("#" + link.key))
            // A wrong key is refused and the receiver keeps waiting.
            assertEquals(400, post(link, PhoneSignIn.seal(PhoneSignIn.newKey(), "SID=no")))
            assertEquals(200, post(link, PhoneSignIn.seal(PhoneSignIn.keyOf(link.key), "SID=yes")))
            assertEquals("SID=yes", withTimeout(2_000) { receiver.cookie.await() })
            assertEquals(410, post(link, PhoneSignIn.seal(PhoneSignIn.keyOf(link.key), "SID=again")))
        } finally {
            receiver.close()
        }
    }

    @Test fun thePhoneSendsTheCookieSealed() = runBlocking {
        val receiver = PhoneSignIn.Receiver.start(InetAddress.getByName("127.0.0.1"))
        try {
            PhoneSignIn.send(receiver.link, "SID=phone")
            assertEquals("SID=phone", withTimeout(2_000) { receiver.cookie.await() })
        } finally {
            receiver.close()
        }
    }

    @Test fun keysRoundTripThroughText() {
        val key = PhoneSignIn.newKey()
        assertContentEquals(key.encoded, PhoneSignIn.keyOf(PhoneSignIn.textOf(key)).encoded)
    }

    private fun post(link: PhoneSignIn.Link, body: String): Int {
        val connection = URI("${link.origin}/signin?c=${link.code}").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.responseCode
    }
}
