package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderFetchEnvelopeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aLargeValidJsonFetchFitsTheBridgeWithoutIncreasingTheRawMetadataLimit() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val payload = "{\"value\":\"" + "x".repeat(1_550_000) + "\"}"
            server.enqueue(MockResponse().setBody(payload).setHeader("Content-Type", "application/json"))
            val manifest = ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider"),
                permissions = ProviderPermissions(network = listOf("localhost"), allowHttp = true))
            val install = ProviderInstall(manifest, "0".repeat(64), allowLocalNetwork = true)
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            val host = ProviderMetadataHost(install, store)
            val config = buildJsonObject { putJsonObject("permissions") { putJsonArray("network") { add("localhost") } } }
            val script = """registerExtension({probe:function(url){var r=fetch(url);var value=r.json();return {ok:r.ok,length:value.value.length,textLength:r.text().length};}});"""
            try {
                createProviderScriptSession(script, config, host::bridge, host::cancel).use { runtime ->
                    val result = Json.parseToJsonElement(runtime.invoke("probe", buildJsonArray { add(server.url("/large").toString()) })).jsonObject
                    assertEquals(JsonPrimitive(true), result["ok"])
                    assertEquals(JsonPrimitive(1_550_000), result["length"])
                    assertEquals(JsonPrimitive(payload.length), result["textLength"])
                }
                assertEquals(2 * 1024 * 1024, ProviderNetwork.MAX_METADATA_BYTES)
            } finally { host.cancel() }
        }
    }

    @Test fun requiredSegmentFeatureAndProviderSongFilterAreAdmittedTogether() = runBlocking<Unit> {
        val manifest = """{"name":"fixture","version":"1.0.0","type":["metadata_provider"],"permissions":{"network":[]},
            "requiredRuntimeFeatures":["downloadSegments@1"],"searchBehavior":{"filters":[{"id":"songs"}]}}"""
        val script = """registerExtension({customSearch:function(query,options){if(options.filter!=='songs')throw Error('Wrong filter');return [{id:'one',name:query,artist:'Singer'}];},
            getTrack:function(id){return {id:'one',name:'Song',artist:'Singer',duration_ms:200000};}});"""
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            } }; out.toByteArray()
        }
        ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
            manager.trust(manager.inspect { bytes.inputStream() })
            assertEquals("Song", manager.search("fixture", "Song").single().title)
            assertEquals(200_000L, manager.track("fixture", "one")?.durationMs)
            assertNull(manager.track("fixture", "another-recording"))
        }
    }
}
