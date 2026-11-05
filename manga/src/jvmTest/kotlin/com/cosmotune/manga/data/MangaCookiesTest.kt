package com.cosmotune.manga.data

import java.io.File
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaCookiesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun persistentCookiesStayOnTheirHostAndClearWithoutSealingEmptyBytes() {
        val file = File(temporary.newFolder(), "cookies")
        val protection = object : MangaFileProtection {
            override fun seal(bytes: ByteArray): ByteArray { require(bytes.isNotEmpty()); return bytes.reversedArray() }
            override fun open(bytes: ByteArray) = bytes.reversedArray()
        }
        val url = "https://example.org/reader?private=value".toHttpUrl()
        val cookie = Cookie.Builder().name("session").value("secret").hostOnlyDomain(url.host).path("/")
            .secure().httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build()
        val jar = MangaCookies(file, protection)
        jar.saveFromResponse(url, listOf(cookie))
        assertFalse(file.readText().contains("secret"))
        assertFalse(file.readBytes().reversedArray().toString(Charsets.UTF_8).contains("private=value"))
        val reopened = MangaCookies(file, protection)
        assertEquals("secret", reopened.loadForRequest(url).single().value)
        assertTrue(reopened.loadForRequest("https://other.example/".toHttpUrl()).isEmpty())
        reopened.clear()
        assertFalse(file.exists())
        assertTrue(MangaCookies(file, protection).loadForRequest(url).isEmpty())
    }
}
