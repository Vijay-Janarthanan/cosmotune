package com.cosmotune.providers

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderHostWorkTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun finish(work: ProviderHostWork, id: String): JsonObject {
        val end = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < end) { val result = work.poll(id); if (result["pending"] == null) return result }
        fail("Provider file work did not finish")
    }

    @Test fun progressIsDeliveredBeforeCompletionAndJobsCannotOverlapOrCrossOwners() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ProviderHostWork {}.use { work -> ProviderHostWork {}.use { other ->
            val id = work.start(1) { work.report(10, 20); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); JsonPrimitive(true) }
                .getValue("pending").jsonPrimitive.content
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> { work.start(2) { JsonNull } }
                assertFailsWith<IllegalArgumentException> { other.poll(id) }
                val result = work.poll(id)
                assertEquals(JsonPrimitive(id), result["pending"])
                assertEquals(JsonPrimitive(10), result["events"]!!.jsonArray.single().jsonObject["args"]!!.jsonArray.first())
            } finally { release.countDown() }
            assertEquals(JsonPrimitive(true), finish(work, id)["value"])
            assertFailsWith<IllegalArgumentException> { work.poll(id) }
        } }
    }

    @Test fun cancellationReleasesTheJobAndClosesItsHostWork() {
        val cancelled = AtomicBoolean(false); val entered = CountDownLatch(1); val exited = CountDownLatch(1)
        ProviderHostWork { cancelled.set(true) }.use { work ->
            val id = work.start(1) {
                try { entered.countDown(); while (!cancelled.get()) Thread.sleep(10); JsonNull }
                finally { exited.countDown() }
            }.getValue("pending").jsonPrimitive.content
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            work.cancel(id)
            assertTrue(cancelled.get()); assertTrue(exited.await(3, TimeUnit.SECONDS))
            assertFailsWith<IllegalArgumentException> { work.poll(id) }
        }
    }

    @Test fun progressQueueStaysBoundedAndRetainsTheLatestCount() {
        val complete = CountDownLatch(1)
        ProviderHostWork {}.use { work ->
            val id = work.start(7) { repeat(100) { work.report(it.toLong() + 1, 100) }; complete.countDown(); JsonPrimitive(true) }
                .getValue("pending").jsonPrimitive.content
            assertTrue(complete.await(3, TimeUnit.SECONDS))
            val first = work.poll(id)
            val events = first.getValue("events").jsonArray
            assertTrue(events.size <= 16)
            assertEquals(JsonPrimitive(100), events.last().jsonObject["args"]!!.jsonArray.first())
            if (first["pending"] != null) finish(work, id)
        }
    }

    private fun fixture(block: (ProviderMetadataHost, ProviderStore, MockWebServer, ProviderInstall) -> Unit) {
        MockWebServer().use { server ->
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.start()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("download_provider"),
                permissions = ProviderPermissions(listOf("localhost"), storage = true, file = true)), "0".repeat(64), allowLocalNetwork = true)
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            runBlocking { store.update { it.copy(installed = listOf(install)) } }
            val network = ProviderNetwork(install.manifest.permissions, true, OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
            val host = ProviderMetadataHost(install, store, network)
            try { block(host, store, server, install) } finally { host.cancel() }
        }
    }
    private fun config() = buildJsonObject { putJsonObject("permissions") {
        putJsonArray("network") { add("localhost") }; put("storage", true); put("file", true)
    } }

    @Test fun actualGuestCallbacksObserveIntermediateTlsTransferProgress() {
        fixture { host, store, server, _ -> runBlocking<Unit> {
            val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)).throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS))
            val code = """registerExtension({probe:function(){
              var r=file.download(${JsonPrimitive(server.url("/audio").toString())},'song.bin',{onProgress:function(n,total){
                if(n>0&&n<total)storage.set('intermediate',true);
              }});return {success:r.success,intermediate:storage.get('intermediate',false)};
            }});"""
            createProviderScriptSession(code, config(), host::bridge, host::cancel).use { runtime ->
                val result = Json.parseToJsonElement(runtime.invoke("probe", JsonArray(emptyList()))).jsonObject
                assertEquals(JsonPrimitive(true), result["success"])
                assertEquals(JsonPrimitive(true), result["intermediate"])
            }
            assertContentEquals(payload, File(store.root, "workspaces/fixture/song.bin").readBytes())
        } }
    }

    @Test fun throwingFromAGuestProgressCallbackCancelsTheTransferAndPreservesAnExistingFile() {
        fixture { host, store, server, _ -> runBlocking<Unit> {
            val output = File(store.root, "workspaces/fixture/song.bin").apply { parentFile.mkdirs(); writeText("previous") }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(512 * 1024))).throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS))
            val code = """registerExtension({probe:function(){return file.download(${JsonPrimitive(server.url("/audio").toString())},'song.bin',{
                onProgress:function(n,total){if(n>0&&n<total)throw Error('fixture cancelled');}
            });}});"""
            createProviderScriptSession(code, config(), host::bridge, host::cancel).use { runtime ->
                assertFailsWith<Exception> { runtime.invoke("probe", JsonArray(emptyList())) }
            }
            assertTrue(host.network.isCancelled)
            assertEquals("previous", output.readText())
        } }
    }
}
