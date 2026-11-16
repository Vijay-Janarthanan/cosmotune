package com.cosmotune.providers
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*
class ProviderStartupRecoveryTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun startupFailureReportsAndRecoversWithoutLosingCredentials() = runBlocking<Unit> {
        val bytes=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            val manifest="""{"name":"recoveryfixture","version":"1.0.0","type":["metadata_provider"],"permissions":{"network":[],"storage":true}}"""
            val code="""registerExtension({initialize:function(){if(!storage.get('ready',false))throw Error('offline');return true;},searchTracks:function(){return [{id:'song',name:'A Song',artists:'Artist'}];}});"""
            listOf("manifest.json" to manifest,"index.js" to code).forEach { (name,value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val store=ProviderStore(temporary.newFolder(),UnsealedProviderFiles)
        val saved=buildJsonObject { put("token","fixture-secret") }
        ProviderManager(store).use { manager ->
            manager.trust(manager.inspect { bytes.inputStream() })
            store.update { it.copy(credentials=mapOf("recoveryfixture" to saved)) }
            repeat(2) { assertFailsWith<Exception> { manager.search("recoveryfixture","Song") } }
            assertNotNull(manager.errors.value["recoveryfixture"])
            assertFalse(manager.errors.value.toString().contains("fixture-secret"))
            assertFailsWith<ProviderBackoffException> { manager.search("recoveryfixture","Song") }
            store.update { it.copy(storage=mapOf("recoveryfixture" to buildJsonObject { put("ready",true) })) }
            manager.retryProvider("recoveryfixture")
            assertEquals("A Song",manager.search("recoveryfixture","Song").single().title)
            assertNull(manager.errors.value["recoveryfixture"])
            assertEquals(saved,store.state.value.credentials["recoveryfixture"])
            assertTrue(store.state.value.installed.single().enabled)
            manager.setEnabled("recoveryfixture",false)
            assertFailsWith<IllegalArgumentException> { manager.retryProvider("recoveryfixture") }
            assertFalse(store.state.value.installed.single().enabled)
        }
    }
}
