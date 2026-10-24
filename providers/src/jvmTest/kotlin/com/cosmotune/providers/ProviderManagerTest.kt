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

class ProviderManagerTest {
    @Test fun versionsRespectReleasePrereleaseAndBuildMetadataOrder() {
        assertTrue(ProviderManager.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(ProviderManager.compareVersions("1.0.0", "1.0.0-rc.2") > 0)
        assertTrue(ProviderManager.compareVersions("1.0.0-rc.10", "1.0.0-rc.2") > 0)
        assertEquals(0, ProviderManager.compareVersions("1.0.0+new", "1.0.0+old"))
    }
    @get:Rule val temporary = TemporaryFolder()
    private fun packageBytes(version: String = "1.0.0", name: String = "fixture"): ByteArray = ByteArrayOutputStream().use { output ->
        val manifest = """{"name":"$name","version":"$version","type":["metadata_provider"],"permissions":{"network":[],"storage":true},"settings":[{"key":"country","default":"IN"}],"capabilities":{"homeFeed":true,"homeFeedRegionSetting":"country"}}"""
        val script = """
            registerExtension({
              initialize:function(){storage.set('initialized',true);return true;},
              searchTracks:function(query){return [{id:'song',name:query,artists:[{id:'artist',name:'Tyler, the Creator'}],album:{id:'album',name:'An album'},duration_ms:180000}];},
              getAlbum:function(id){return {id:id,name:'An album',tracks:[{id:'song',name:'A song',artists:'Artist'}]};},
              getHomeFeed:function(){
                if(storage.get('fail',false))throw Error('Feed unavailable');
                storage.set('calls',storage.get('calls',0)+1);
                return {sections:[{id:'regional',title:settings.country,items:[{id:'song',name:'A song',artists:'Artist',item_type:'track'}]}]};
              }
            });
        """.trimIndent()
        ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
        output.toByteArray()
    }

    @Test fun trustedPackagesCanSearchBrowseAndCacheRegionalFeedsWithoutMixingIdentities() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            val review = manager.inspect { packageBytes().inputStream() }
            assertTrue(store.state.value.storage.isEmpty())
            manager.trust(review)
            assertTrue(store.state.value.storage.isEmpty()) // Trust records the package; JavaScript starts on use.
            val track = manager.search("fixture", "A title").single()
            assertEquals("Tyler, the Creator", track.artist)
            assertEquals("An album", track.album)
            assertEquals("src:sflx-fixture::song", track.song().videoId)
            assertEquals("artist", ProviderBrowseKey.parse(requireNotNull(track.song().artistId))?.id)
            assertEquals("An album", manager.catalogue(ProviderBrowseKey("fixture", "album", "album")).title)
            assertEquals("IN", manager.home("fixture", "IN", "en").single().title)
            assertEquals("IN", manager.home("fixture", "IN", "en").single().title)
            assertEquals(1, store.state.value.storage.getValue("fixture").getValue("calls").jsonPrimitive.int)
            assertEquals("US", manager.home("fixture", "US", "en").single().title)
            assertEquals(2, store.state.value.storage.getValue("fixture").getValue("calls").jsonPrimitive.int)
            assertEquals("IN", manager.home("fixture", "IN", "en").single().title)
        }
    }

    @Test fun failedRefreshKeepsCachedSectionsAndDisabledStateSurvivesAnUpdate() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            manager.trust(manager.inspect { packageBytes().inputStream() })
            val original = manager.home("fixture", "IN", "en")
            store.update { old -> old.copy(storage = old.storage + ("fixture" to buildJsonObject { put("fail", true) })) }
            assertEquals(original, manager.home("fixture", "IN", "en", force = true))
            assertEquals(original, manager.home("fixture", "IN", "en", force = true))
            manager.setEnabled("fixture", false)
            val updated = manager.trust(manager.inspect { packageBytes("1.1.0").inputStream() })
            assertFalse(updated.enabled)
            assertFailsWith<IllegalArgumentException> { manager.search("fixture", "Song") }
            assertFailsWith<IllegalArgumentException> { manager.trust(manager.inspect { packageBytes("1.0.0").inputStream() }) }
            assertEquals("1.1.0", store.state.value.installed.single().manifest.version)
        }
    }

    @Test fun removalKeepsOtherProvidersAndAClosedManagerCannotStartMoreCode() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val manager = ProviderManager(store)
        manager.trust(manager.inspect { packageBytes().inputStream() })
        manager.trust(manager.inspect { packageBytes(name = "other").inputStream() })
        manager.search("fixture", "Song")
        manager.search("other", "Song")
        val removedFile = java.io.File(store.root, "workspaces/fixture/cache/file").apply { parentFile.mkdirs(); writeText("temporary media") }
        val retainedFile = java.io.File(store.root, "workspaces/other/cache/file").apply { parentFile.mkdirs(); writeText("other provider") }
        manager.remove("fixture")
        assertFalse(removedFile.exists())
        assertEquals("other provider", retainedFile.readText())
        assertEquals("other", store.state.value.installed.single().manifest.name)
        assertEquals(setOf("other"), store.state.value.storage.keys)
        assertEquals(1, java.io.File(store.root, "packages").listFiles().orEmpty().size)
        manager.trust(manager.inspect { packageBytes().inputStream() })
        manager.close()
        assertFailsWith<IllegalStateException> { manager.search("fixture", "Song") }
    }
}
