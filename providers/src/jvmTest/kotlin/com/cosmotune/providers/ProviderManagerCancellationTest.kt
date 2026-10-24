package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderManagerCancellationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(script: String, version: String = "1.0.0"): ByteArray {
        val manifest = """{"name":"fixture","version":"$version","type":["metadata_provider"],"permissions":{"storage":true,"file":true,"network":["localhost"],"allowHttp":true}}"""
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
            output.toByteArray()
        }
    }

    @Test fun disablingInterruptsInitialScriptHostWorkAndAReenabledUpdateCanRun() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            val script = """storage.set('starting',true);utils.sleep(20000);registerExtension({searchTracks:function(){return [];}});"""
            manager.trust(manager.inspect { archive(script).inputStream() })
            supervisorScope {
                val starting = async { manager.search("fixture", "query") }
                withTimeout(5_000) { store.state.first { it.storage["fixture"]?.get("starting") == JsonPrimitive(true) } }
                withTimeout(2_000) { manager.setEnabled("fixture", false) }
                withTimeout(2_000) { assertFailsWith<Exception> { starting.await() } }
            }
            assertFalse(store.state.value.installed.single().enabled)
            assertFailsWith<IllegalArgumentException> { manager.search("fixture", "disabled") }
            manager.setEnabled("fixture", true)
            val fixed = """registerExtension({searchTracks:function(q){return [{id:'song',name:q,artists:'fixture'}];}});"""
            manager.trust(manager.inspect { archive(fixed, "1.1.0").inputStream() })
            assertEquals("updated", manager.search("fixture", "updated").single().title)
        }
    }

    @Test fun disablingCancelsABlockedFileRequestWithoutWaitingForTheProviderMutex() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
            ProviderManager(store).use { manager ->
                val script = """registerExtension({searchTracks:function(){file.download(${JsonPrimitive(server.url("/blocked").toString())},'song');return [];}});"""
                manager.trust(manager.inspect { archive(script).inputStream() })
                manager.saveSettings("fixture", JsonObject(emptyMap()), allowLocal = true)
                supervisorScope {
                    val loading = async { manager.search("fixture", "query") }
                    withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                    withTimeout(2_000) { manager.setEnabled("fixture", false) }
                    withTimeout(2_000) { assertFailsWith<Exception> { loading.await() } }
                }
                assertFalse(store.state.value.installed.single().enabled)
                assertFalse(java.io.File(store.root, "workspaces/fixture/song").exists())
            }
        }
    }
}
