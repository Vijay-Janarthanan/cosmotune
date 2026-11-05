package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.providers.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopSourceCatalogueTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun nativeArtistAndAlbumLinksKeepTheProviderInsteadOfQueryingYoutube() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), object : ProviderFileProtection {
            override fun seal(bytes: ByteArray) = bytes
            override fun open(bytes: ByteArray) = bytes
        })
        val manifest = """{"name":"fixture","version":"1.0.0","type":["metadata_provider"]}"""
        val code = """
            registerExtension({
              getArtist:function(id){return {id:id,name:'Singer',topTracks:[{id:'top',name:'A top song',artists:'Singer'}],albums:[{id:'album/one',name:'An album'}]};},
              getAlbum:function(id){return {id:id,name:'An album',tracks:[{id:'recording',name:'An album song',artists:'Singer'}]};}
            });
        """.trimIndent()
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to code).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            } }; output.toByteArray()
        }
        ProviderManager(store).use { manager ->
            manager.trust(manager.inspect { bytes.inputStream() })
            val artistKey = ProviderBrowseKey("fixture", "artist", "singer")
            assertEquals(BrowseType.ARTIST, browseTypeOf(artistKey.encode()))
            val artist = DesktopSourceCatalogue.load(artistKey, manager).artistPage()
            assertEquals("Singer", artist.name)
            assertEquals("src:sflx-fixture::top", artist.songs.single().videoId)
            val albumKey = requireNotNull(ProviderBrowseKey.parse(requireNotNull(artist.sections.single().items.single().browseId)))
            assertEquals("provider", albumKey.namespace)
            assertEquals("album/one", albumKey.id)
            assertEquals(BrowseType.ALBUM, browseTypeOf(albumKey.encode()))
            val album = DesktopSourceCatalogue.load(albumKey, manager)
            assertEquals("src:sflx-fixture::recording", album.songs.single().videoId)
            assertEquals(albumKey, ProviderBrowseKey.parse(requireNotNull(album.songs.single().albumId)))
        }
    }
}
