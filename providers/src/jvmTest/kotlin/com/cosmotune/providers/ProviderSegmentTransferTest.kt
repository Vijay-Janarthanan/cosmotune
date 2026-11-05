package com.cosmotune.providers

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderSegmentTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val permissions = ProviderPermissions(listOf("localhost"), storage = true, file = true, allowHttp = true)
    private fun network() = ProviderNetwork(permissions, true)
    private fun segments(server: MockWebServer, vararg paths: String) = buildJsonArray { paths.forEach { add(server.url(it).toString()) } }

    @Test fun orderedChunkedSegmentsAndTheirHeadersReachOneAtomicOutput() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("init")); server.enqueue(MockResponse().setChunkedBody("first", 2)); server.enqueue(MockResponse().setBody("last"))
            val target = File(temporary.newFolder(), "song").apply { writeText("previous") }
            val input = buildJsonArray {
                add(server.url("/init").toString()); addJsonObject { put("url", server.url("/first").toString()); putJsonObject("headers") { put("X-Segment", "own") } }
                add(server.url("/last").toString())
            }
            val counts = mutableListOf<Pair<Int, Int>>()
            val result = ProviderSegmentTransfer(network(), 128).download(input, target, buildJsonObject { put("X-Provider", "fixture") }, 1) { bytes, total, done, count ->
                assertTrue(bytes in 0..13); if (total != null) assertEquals(13L, total); counts += done to count
            }
            assertEquals("initfirstlast", target.readText()); assertEquals(JsonPrimitive(13L), result["size"])
            assertEquals(3 to 3, counts.last())
            val requests = (1..3).map { server.takeRequest() }
            assertEquals(listOf("/init", "/first", "/last"), requests.map { it.path })
            assertTrue(requests.all { it.getHeader("X-Provider") == "fixture" })
            assertEquals("own", requests[1].getHeader("X-Segment"))
            assertEquals(listOf("song"), target.parentFile.list()!!.toList())
        }
    }

    @Test fun exactByteRangesAreValidatedAndIgnoredOrWrongRangesPreserveTheOldFile() {
        MockWebServer().use { server ->
            val target = File(temporary.newFolder(), "song").apply { writeText("previous") }
            val input = buildJsonArray { addJsonObject { put("url", server.url("/range").toString()); putJsonObject("headers") { put("Range", "bytes=3-5") } } }
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 3-5/10").setBody("def"))
            ProviderSegmentTransfer(network(), 32).download(input, target, JsonObject(emptyMap()), 1) { _, _, _, _ -> }
            assertEquals("def", target.readText()); assertEquals("bytes=3-5", server.takeRequest().getHeader("Range"))
            for (response in listOf(MockResponse().setBody("whole-file"),
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 2-4/10").setBody("bad"))) {
                server.enqueue(response)
                assertFailsWith<IllegalArgumentException> { ProviderSegmentTransfer(network(), 32).download(input, target, JsonObject(emptyMap()), 1) { _, _, _, _ -> } }
                assertEquals("def", target.readText())
            }
        }
    }

    @Test fun failedLaterSegmentsOversizeHtmlAndEncodedBodiesNeverReplaceAnExistingFile() {
        MockWebServer().use { server ->
            val target = File(temporary.newFolder(), "song").apply { writeText("previous") }
            server.enqueue(MockResponse().setBody("init")); server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
            assertFailsWith<IllegalArgumentException> { ProviderSegmentTransfer(network(), 32).download(segments(server, "/init", "/denied"), target, JsonObject(emptyMap()), 1) { _, _, _, _ -> } }
            for (response in listOf(MockResponse().setChunkedBody("012345678901", 2),
                MockResponse().addHeader("Content-Type", "text/html").setBody("login"),
                MockResponse().addHeader("Content-Encoding", "gzip").setBody("encoded"))) {
                server.enqueue(response)
                assertFailsWith<IllegalArgumentException> { ProviderSegmentTransfer(network(), 8).download(segments(server, "/bad"), target, JsonObject(emptyMap()), 1) { _, _, _, _ -> } }
                assertEquals("previous", target.readText())
                assertEquals(listOf("song"), target.parentFile.list()!!.toList())
            }
        }
    }

    @Test fun aRetriedPartialSegmentCannotDuplicateItsFirstAttempt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("init"))
            server.enqueue(MockResponse().setBody("truncated".repeat(1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody("final"))
            val target = File(temporary.newFolder(), "song")
            ProviderSegmentTransfer(network(), 32_768).download(segments(server, "/init", "/retry"), target, JsonObject(emptyMap()), 2) { _, _, _, _ -> }
            assertEquals("initfinal", target.readText()); assertEquals(3, server.requestCount)
        }
    }

    @Test fun cancellationDuringAReadKeepsThePreviousGeneration() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(32_768))).throttleBody(2048, 100, TimeUnit.MILLISECONDS))
            val target = File(temporary.newFolder(), "song").apply { writeText("previous") }
            val network = network()
            assertFailsWith<IllegalStateException> {
                ProviderSegmentTransfer(network, 65_536).download(segments(server, "/slow"), target, JsonObject(emptyMap()), 2) { bytes, _, _, _ ->
                    if (bytes > 0) network.cancel()
                }
            }
            assertEquals("previous", target.readText()); assertEquals(listOf("song"), target.parentFile.list()!!.toList())
        }
    }

    @Test fun theSdkReceivesSegmentCountsAndFinalBytesOnItsOwnVmThread() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("one")); server.enqueue(MockResponse().setBody("two"))
            val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("download_provider"), permissions = permissions), "0".repeat(64), allowLocalNetwork = true)
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            store.update { it.copy(installed = listOf(install)) }
            val host = ProviderMetadataHost(install, store, network())
            val config = buildJsonObject { putJsonObject("permissions") { putJsonArray("network") { add("localhost") }; put("file", true); put("storage", true) } }
            val code = """registerExtension({probe:function(){var final=false;var r=file.downloadSegments(${segments(server, "/one", "/two")},'song',{
                concurrency:4,onProgress:function(n,total,done,count){if(total===n&&done===2&&count===2)final=true;}
            },null);return {success:r.success,final:final};}});"""
            createProviderScriptSession(code, config, host::bridge, host::cancel).use {
                val result = Json.parseToJsonElement(it.invoke("probe", JsonArray(emptyList()))).jsonObject
                assertEquals(JsonPrimitive(true), result["success"]); assertEquals(JsonPrimitive(true), result["final"])
            }
            assertEquals("onetwo", File(store.root, "workspaces/fixture/song").readText())
        }
    }
}
