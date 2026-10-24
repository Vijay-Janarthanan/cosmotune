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

class ProviderHomeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(name: String, fail: Boolean = false): ByteArray = ByteArrayOutputStream().use { out ->
        val manifest = """{"name":"$name","displayName":"$name music","version":"1.0.0","type":["metadata_provider"],"permissions":{"network":[],"storage":true},"capabilities":{"homeFeed":true,"homeFeedRegionSetting":"country"},"settings":[{"key":"country","default":"IN"},{"key":"lang","default":"ta"}]}"""
        val script = """
          registerExtension({getHomeFeed:function(){
            if($fail)throw Error('Unavailable');
            storage.set('calls',storage.get('calls',0)+1);
            return {sections:[{id:'new',title:settings.country+' '+settings.lang,items:[
              {id:'recording',name:'A song',item_type:'track',artists:[{id:'artist',name:'AC/DC'}],album:{id:'album',name:'A release'},duration_ms:125000,quality:'FLAC'},
              {id:'album',name:'A release',item_type:'album'}]}]};
          }});
        """.trimIndent()
        ZipOutputStream(out).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }; out.toByteArray()
    }

    @Test fun configuredRegionalShelvesPreserveRecordingMetadataAndCacheUntilForced() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        ProviderManager(store).use { manager ->
            manager.trust(manager.inspect { archive("fixture").inputStream() })
            manager.saveSettings("fixture", buildJsonObject { put("country", "US"); put("lang", "te") })
            val published = mutableListOf<com.cosmotune.music.data.model.HomeShelf>()
            ProviderHome.load("GB", "en", manager = manager) { published += it }
            val shelf = published.single()
            assertEquals("fixture music · US te", shelf.title)
            assertEquals("sflx-fixture", shelf.sourceId)
            val song = requireNotNull(shelf.items.first().song)
            assertEquals("src:sflx-fixture::recording", song.videoId)
            assertEquals("AC/DC", song.artist)
            assertEquals("2:05", song.durationText)
            assertEquals("LOSSLESS", song.sourceQuality)
            assertEquals("artist", ProviderBrowseKey.parse(requireNotNull(song.artistId))?.id)
            assertEquals("album", ProviderBrowseKey.parse(requireNotNull(song.albumId))?.id)
            assertEquals("album", ProviderBrowseKey.parse(requireNotNull(shelf.items.last().browseId))?.id)
            ProviderHome.load("GB", "en", manager = manager) {}
            assertEquals(JsonPrimitive(1), store.state.value.storage["fixture"]?.get("calls"))
            ProviderHome.load("GB", "en", force = true, manager = manager) {}
            assertEquals(JsonPrimitive(2), store.state.value.storage["fixture"]?.get("calls"))
        }
    }

    @Test fun unavailableAndDisabledProvidersCannotHideAnotherProvidersHome() = runBlocking<Unit> {
        ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
            listOf("broken", "enabled", "disabled").forEach { name ->
                manager.trust(manager.inspect { archive(name, fail = name == "broken").inputStream() })
            }
            manager.setEnabled("disabled", false)
            val shown = mutableListOf<String>()
            ProviderHome.load("IN", "en", manager = manager) { shelves -> shown += shelves.mapNotNull { it.sourceId } }
            assertEquals(listOf("sflx-enabled"), shown)
        }
    }
}
