package com.cosmotune.youtube.account

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YouTubeCookieJarTest {
    private val now = 1_800_000_000_000L

    @Test fun aHeaderParsesByNameAndRendersInFirstSeenOrder() {
        val jar = YouTubeCookieJar.parse(" SID=1; SAPISID=abc ; empty=; =nameless; token=a=b==")
        assertEquals(listOf("SID", "SAPISID", "token"), jar.names.toList())
        assertEquals("a=b==", jar["token"], "a value keeps its own '='")
        assertEquals("SID=1; SAPISID=abc; token=a=b==", jar.header())
        assertTrue(YouTubeCookieJar.parse(null).isEmpty)
        assertTrue(YouTubeCookieJar.parse("  ").isEmpty)
    }

    @Test fun originsMergeWithTheLaterOneWinning() {
        val www = "SID=1; SAPISID=s; PREF=www; YSC=w"
        val music = "SID=1; SAPISID=s; PREF=music"
        val merged = YouTubeCookieJar.merge(www, null, music)
        assertEquals("SID=1; SAPISID=s; PREF=music; YSC=w", merged.header())
    }

    @Test fun setCookieReplacesAddsAndDeletes() {
        val jar = YouTubeCookieJar.parse("SID=1; SAPISID=s; __Secure-1PSIDTS=old; SIDCC=c1; gone=x")
        val next = jar.withSetCookies(
            listOf(
                "__Secure-1PSIDTS=new; Domain=.youtube.com; Path=/; Expires=Fri, 01-Jan-2100 00:00:00 GMT; Secure; HttpOnly",
                "SIDCC=c2; expires=Fri, 01-Jan-2100 00:00:00 GMT; path=/; domain=.youtube.com",
                "gone=; Domain=.youtube.com; Expires=Thu, 01-Jan-1970 00:00:10 GMT",
                "VISITOR_INFO1_LIVE=v; Max-Age=15552000; Domain=.youtube.com",
            ),
            now,
        )
        assertEquals("new", next["__Secure-1PSIDTS"])
        assertEquals("c2", next["SIDCC"])
        assertNull(next["gone"])
        assertEquals("v", next["VISITOR_INFO1_LIVE"])
        assertEquals("1", next["SID"], "what was not set stays")
    }

    @Test fun maxAgeOutranksExpiresAndZeroDeletes() {
        val jar = YouTubeCookieJar.parse("SAPISID=s; a=1; b=2")
        val next = jar.withSetCookies(
            listOf("a=3; Expires=Thu, 01-Jan-1970 00:00:10 GMT; Max-Age=600", "b=4; Max-Age=0"),
            now,
        )
        assertEquals("3", next["a"])
        assertNull(next["b"])
    }

    @Test fun cookiesOfAnotherDomainAreNotTaken() {
        val jar = YouTubeCookieJar.parse("SAPISID=s")
        val next = jar.withSetCookies(listOf("NID=n; Domain=.google.com", "x=1; Domain=music.youtube.com", "evil=1; Domain=notyoutube.com"), now)
        assertNull(next["NID"])
        assertNull(next["evil"])
        assertEquals("1", next["x"])
        val google = YouTubeCookieJar.parse("SAPISID=g").withSetCookies(listOf("NID=n; Domain=.google.com", "y=1; Domain=.youtube.com"), now, YouTubeCookieJar.GOOGLE_DOMAIN)
        assertEquals("n", google["NID"])
        assertNull(google["y"])
    }

    @Test fun nothingChangedIsTheSameJar() {
        val jar = YouTubeCookieJar.parse("SAPISID=s; a=1")
        assertSame(jar, jar.withSetCookies(listOf("a=1; Domain=.youtube.com"), now))
        assertSame(jar, jar.withSetCookies(emptyList(), now))
    }

    @Test fun theLoginIsTheSigningSecret() {
        assertTrue(YouTubeCookieJar.sameLogin("SAPISID=s; SIDCC=1", "SIDCC=2; SAPISID=s"))
        assertFalse(YouTubeCookieJar.sameLogin("SAPISID=s", "SAPISID=t"))
        assertFalse(YouTubeCookieJar.sameLogin("SID=1", "SID=1"), "a jar that cannot sign is nobody's")
        assertTrue(YouTubeCookieJar.sameLogin("__Secure-3PAPISID=t", "__Secure-3PAPISID=t; __Secure-1PAPISID=f"))
    }

    // ---- The Authorization header --------------------------------------------------------------------------------------

    @Test fun allThreeHashesAreSentWhenTheJarHasAllThreeSecrets() {
        val header = SapisidAuth.authorization("SAPISID=plain; __Secure-1PAPISID=first; __Secure-3PAPISID=third", "https://www.youtube.com", 1_700_000_000)
        assertEquals(
            "SAPISIDHASH 1700000000_${sha1("1700000000 plain https://www.youtube.com")} " +
                "SAPISID1PHASH 1700000000_${sha1("1700000000 first https://www.youtube.com")} " +
                "SAPISID3PHASH 1700000000_${sha1("1700000000 third https://www.youtube.com")}",
            header,
        )
    }

    @Test fun eachOriginHasItsOwnHashAndNoSecretMeansNoHeader() {
        val music = SapisidAuth.authorization("SAPISID=s", "https://music.youtube.com", 5)
        assertEquals("SAPISIDHASH 5_" + sha1("5 s https://music.youtube.com"), music)
        assertNull(SapisidAuth.authorization("SID=1; HSID=2", "https://music.youtube.com", 5))
        assertNull(SapisidAuth.authorization(null as String?, "https://music.youtube.com", 5))
    }

    // ---- The page config -----------------------------------------------------------------------------------------------

    @Test fun thePageConfigIsReadByKey() {
        val html = """<script>ytcfg.set({"LOGGED_IN":true,"INNERTUBE_CLIENT_VERSION":"2.20261101.07.00","VISITOR_DATA":"CgtabcDEF","DATASYNC_ID":"abc||def","SESSION_INDEX":"1"});</script>"""
        val config = YouTubePageConfig.parse(html)
        assertEquals(true, config.loggedIn)
        assertEquals("2.20261101.07.00", config.clientVersion)
        assertEquals("CgtabcDEF", config.visitorData)
        assertEquals("abc||def", config.dataSyncId)
        assertEquals("1", config.sessionIndex)
        assertNull(config.delegatedSessionId)
        assertEquals(false, YouTubePageConfig.parse("""{"LOGGED_IN": false}""").loggedIn)
        assertNull(YouTubePageConfig.parse("<html>consent</html>").loggedIn, "a page that does not say is not signed out")
        assertTrue(YouTubePageConfig.isWebVersion("2.20261101.07.00"))
        assertFalse(YouTubePageConfig.isWebVersion("1.20261101.01.00"), "a Music version is not a WEB one")
        assertFalse(YouTubePageConfig.isWebVersion(null))
    }

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
