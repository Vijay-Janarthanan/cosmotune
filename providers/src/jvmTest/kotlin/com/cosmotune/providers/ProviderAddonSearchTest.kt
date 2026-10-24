package com.cosmotune.providers

import com.cosmotune.music.data.search.AddonSearch
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BC-31: installed `.sflx` metadata providers answer the Add-ons search through their own runtime, and only while enabled. */
class ProviderAddonSearchTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(name: String, script: String, type: String = "metadata_provider"): ByteArray {
        val manifest = """{"name":"$name","displayName":"Fixture $name","version":"1.0.0","type":["$type"],"permissions":{"network":[]}}"""
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (entry, body) ->
                zip.putNextEntry(ZipEntry(entry)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
            output.toByteArray()
        }
    }

    private val echo = """registerExtension({searchTracks:function(q,limit){var out=[];for(var i=0;i<limit+5;i++){out.push({id:'t'+i,name:q+' '+i,artists:'Singer'});}return out;}});"""

    @Test fun anEnabledProviderAnswersThroughItsRuntimeAndADisabledOneIsNotAsked() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            manager.trust(manager.inspect { archive("one", echo).inputStream() })
            manager.trust(manager.inspect { archive("two", echo).inputStream() })
            val sources = manager.addonSearchSources()
            assertEquals(listOf("Fixture one", "Fixture two"), sources.map { it.name }.sorted())

            val rows = AddonSearch.search("aurora", sources, limit = 3)
            assertEquals(6, rows.size, "three from each, the package asked for the limit and held to it")
            assertTrue(rows.all { it.title.startsWith("aurora ") && "sflx-" in it.videoId }, "provider track keys: ${rows.map { it.videoId }}")
            // Round-robin: the first row of each package before the second of either.
            assertEquals(setOf("aurora 0"), rows.take(2).map { it.title }.toSet())

            manager.setEnabled("two", false)
            assertEquals(listOf("Fixture one"), manager.addonSearchSources().map { it.name })
        }
    }

    @Test fun aPackageWithoutTheMetadataRoleIsNoSearchSource() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            manager.trust(manager.inspect { archive("lyrics", echo, type = "lyrics_provider").inputStream() })
            assertEquals(emptyList(), manager.addonSearchSources().map { it.name })
        }
    }
}
