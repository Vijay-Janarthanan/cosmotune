package com.cosmotune.manga.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaVerificationSessionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactOriginCookiesAndUserAgentDoNotFollowRedirectsOrReachDisk() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { source -> MockWebServer().use { foreign ->
            source.useHttps(serverTls.sslSocketFactory(), false); foreign.useHttps(serverTls.sslSocketFactory(), false)
            val address = source.url("/series").toString()
            val sessions = MangaVerificationSessions()
            val base = MangaCookies(temporary.newFile("cookies"))
            val request = sessions.begin("source", address)
            sessions.accept(request, MangaBrowserProof(address, "clearance=private", "ActualBrowser/1"))
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .cookieJar(sessions.cookieJar(base)).addNetworkInterceptor(sessions.userAgentInterceptor()).build()
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/other")).addHeader("Set-Cookie", "clearance=renewed; Secure; Path=/"))
            foreign.enqueue(MockResponse().setBody("ok"))
            client.newCall(Request.Builder().url(address).header("User-Agent", "App/1").build()).execute().use { assertEquals("ok", it.body.string()) }
            source.takeRequest(2, TimeUnit.SECONDS)!!.let { assertEquals("clearance=private", it.getHeader("Cookie")); assertEquals("ActualBrowser/1", it.getHeader("User-Agent")) }
            foreign.takeRequest(2, TimeUnit.SECONDS)!!.let { assertNull(it.getHeader("Cookie")); assertEquals("App/1", it.getHeader("User-Agent")) }
            assertTrue(base.loadForRequest(source.url("/")).isEmpty(), "verification cookies must not be persisted")
            assertEquals("renewed", sessions.cookieJar(base).loadForRequest(source.url("/")).single().value)
            sessions.reset("source")
            sessions.cookieJar(base).saveFromResponse(source.url("/"), listOf(Cookie.Builder().name("clearance").value("late").hostOnlyDomain("localhost").build()))
            assertTrue(MangaCookies(java.io.File(temporary.root, "cookies")).loadForRequest(source.url("/")).isEmpty())
        } }
    }

    @Test fun expiredChangedAndMalformedProofCannotInstallASession() {
        var now = 1_000L
        val sessions = MangaVerificationSessions { now }
        val request = sessions.begin("source", "https://source.example/")
        assertFailsWith<IllegalArgumentException> { sessions.accept(request.copy(nonce = "different"), MangaBrowserProof(request.address, "clearance=yes", "Browser")) }
        assertFailsWith<IllegalArgumentException> { sessions.accept(request, MangaBrowserProof("https://other.example/", "clearance=yes", "Browser")) }
        assertFailsWith<IllegalArgumentException> { sessions.accept(request, MangaBrowserProof(request.address, "clearance=yes", "Browser\nInjected")) }
        assertFalse(sessions.active("source"))
        now += MangaVerificationSessions.REQUEST_TTL_MS
        assertFailsWith<IllegalArgumentException> { sessions.accept(request, MangaBrowserProof(request.address, "clearance=yes", "Browser")) }
        val next = sessions.begin("source", request.address)
        sessions.accept(next, MangaBrowserProof(next.address, "clearance=yes", "Browser"))
        assertTrue(sessions.active("source"))
        now += MangaVerificationSessions.SESSION_TTL_MS
        assertFalse(sessions.active("source"))
        assertFailsWith<IllegalArgumentException> { sessions.begin("source", "http://source.example/") }
        assertFailsWith<IllegalArgumentException> { sessions.begin("source", "https://user:secret@source.example/") }
    }

    @Test fun changedSourceRejectsBrowserCompletionAndSuccessfulCompletionRefreshesCatalogue() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        val config = MangaSourceConfig("fixture", "mihon", "Fixture", baseUrl = "https://source.example/")
        store.source(config)
        var calls = 0
        val repository = MangaRepository(store) { item, _ -> object : MangaSource {
            override val config = item
            override suspend fun browse(query: String, offset: Int): MangaBrowsePage { calls++; return MangaBrowsePage(emptyList(), null) }
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        repository.browse(config.id, "", 0)
        val request = repository.beginVerification(config.id)
        val proof = MangaBrowserProof(request.address, "clearance=yes", "Browser")
        store.source(config.copy(settings = mapOf("domain" to "changed")))
        assertFailsWith<IllegalArgumentException> { repository.completeVerification(request, proof) }
        repository.browse(config.id, "", 0)
        val next = repository.beginVerification(config.id)
        repository.completeVerification(next, proof)
        repository.browse(config.id, "", 0)
        assertEquals(3, calls)
        assertTrue(repository.context.verification.active(config.id))
        repository.invalidateSource(config.id)
        assertFalse(repository.context.verification.active(config.id))
    }
}
