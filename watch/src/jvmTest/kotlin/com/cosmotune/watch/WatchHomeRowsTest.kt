package com.cosmotune.watch

import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.CatalogDef
import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.plugin.WatchPlugin
import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchHomeRowsTest {
    /** An add-on with seven catalogues, each answering three titles of its own. */
    private class ManyCatalogues : WatchPlugin {
        override val id = "many"
        override val kind = PluginKind.STREMIO_ADDON
        override val displayName = "Many"
        override val badge = "MANY"
        override val capabilities = setOf(Capability.CATALOG)
        val asked = mutableListOf<String>()
        override suspend fun catalogs() = (1..7).map { CatalogDef(id, "movie/c$it", "Catalogue $it", TitleType.FILM) }
        override suspend fun catalogPage(catalog: CatalogDef, skip: Int): List<TitleSummary> {
            asked += catalog.id
            return (1..3).map { TitleSummary(WatchKey.film(id, "${catalog.id}-$skip-$it"), TitleType.FILM, "${catalog.name} $it") }
        }
    }

    // Nothing leaves the test: the metadata rows and probes find no network and answer nothing.
    private val offline = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("offline") }).build()

    @Test fun everyCatalogueOfAnAddOnIsARowAndThoseAfterTheFirstFewLoadWhenAsked() = runBlocking {
        val root = Files.createTempDirectory("watch-rows").toFile()
        try {
            val plugin = ManyCatalogues()
            val repository = DefaultWatchRepository(WatchConfig(offline, root, root.resolve("cache")),
                WatchStore(root.resolve("watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> listOf(plugin) })
            val rows = repository.home(HomeFilter.ALL).rows.filter { it.pluginId == "many" }
            assertEquals((1..7).map { "Catalogue $it" }, rows.map { it.title }, "all seven, in the add-on's order")
            assertTrue(rows.take(4).all { !it.deferred && it.items.size == 3 }, "the first four come with the home")
            assertTrue(rows.drop(4).all { it.deferred && it.items.isEmpty() }, "the rest wait to be scrolled to")
            assertEquals(4, plugin.asked.size, "the home did not wait for the others")
            assertFalse(repository.home(HomeFilter.ALL).empty)

            val later = repository.rowPage(rows[6].id, 0)
            assertEquals(listOf("Catalogue 7 1", "Catalogue 7 2", "Catalogue 7 3"), later.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }
}
