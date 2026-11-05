package com.cosmotune.providers

import com.cosmotune.music.data.search.AddonSearch
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.toList
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

    @Test fun audioPackagesAreSearchedTooAndEachReportsItsOwnState() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            // Before, only `metadata_provider` packages were asked: an audio package with its own search was skipped.
            manager.trust(manager.inspect { archive("audio", echo, type = "download_provider").inputStream() })
            manager.trust(manager.inspect { archive("locked", """registerExtension({searchTracks:function(q,l){return {success:false,error_type:'verification_required'};}});""",
                type = "download_provider").inputStream() })
            manager.trust(manager.inspect { archive("nosearch", """registerExtension({getHome:function(){return [];}});""", type = "download_provider").inputStream() })
            val sources = manager.addonSearchSources()
            assertEquals(listOf("sflx-audio", "sflx-locked", "sflx-nosearch"), sources.map { it.id }.sorted())
            val last = com.cosmotune.music.data.search.AddonSearch.stream("aurora", sources, limit = 2).toList().last()
            assertEquals(com.cosmotune.music.data.search.AddonSourceStatus.DONE, last.source("sflx-audio")?.status)
            assertEquals(2, last.source("sflx-audio")?.songs?.size)
            assertEquals(com.cosmotune.music.data.search.AddonSourceStatus.NEEDS_VERIFICATION, last.source("sflx-locked")?.status,
                "shown as Verify to search, not as a broken provider")
            assertEquals(com.cosmotune.music.data.search.AddonSourceStatus.UNSUPPORTED, last.source("sflx-nosearch")?.status)
        }
    }

    @Test fun fivePackagesSharingTwoRuntimesAreAllAnswered() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            (1..5).forEach { manager.trust(manager.inspect { archive("p$it", echo).inputStream() }) }
            // Only two runtimes run at once; the other three wait for one, and their budget starts when they get it.
            val last = com.cosmotune.music.data.search.AddonSearch.stream("aurora", manager.addonSearchSources(), timeoutMs = 5_000).toList().last()
            assertEquals(5, last.sources.size)
            assertTrue(last.sources.all { it.status == com.cosmotune.music.data.search.AddonSourceStatus.DONE }, "${last.sources}")
        }
    }
}
