package com.cosmotune.manga.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.manga.extensions.MangaExtensions
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, isolated emulator check. No external APK is bundled in any build. */
@RunWith(AndroidJUnit4::class)
class MangaExternalExtensionTest {
    @Test fun reviewedPublisherPackageCanLoadBrowseAndDecodeAPublicChapter() = runBlocking<Unit> {
        val requested = InstrumentationRegistry.getArguments().getString("liveMangaExtension").orEmpty()
        assumeTrue("Set the liveMangaExtension runner argument for an external package check", requested.isNotBlank())
        // The opt-in is deliberately restricted to the public publisher's Manga Plus package.
        require(requested == "mangaplus")
        withTimeout(120_000) {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val root = File(app.cacheDir, "external-source-probe-${java.util.UUID.randomUUID()}")
            val store = MangaStore(root, platformMangaFileProtection())
            val repository = MangaRepository(store) { config, parser -> MangaExtensions(app, store, parser).source(config) }
            val extensions = MangaExtensions(app, store, repository.context)
            val catalogue = MangaExtensionStores().fetch("https://github.com/keiyoushi/extensions/raw/repo/index.pb",
                "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2")
            val listing = catalogue.extensions.single { it.packageName.endsWith(".mangaplus") }
            val review = extensions.inspectDownload(listing, catalogue.store)
            println("Reviewed external source ${review.packageName} ${review.version}; SHA256 ${review.sha256}")
            println("External source entry points: ${review.classes.joinToString()}")
            val configs = extensions.trust(review)
            val config = configs.firstOrNull { it.language == "en" } ?: configs.first()
            val books = repository.browse(config.id, "", 0).books
            assertTrue("External catalogue is empty", books.isNotEmpty())
            val detail = repository.chapters(books.first())
            assertTrue("External chapter list is empty", detail.chapters.isNotEmpty())
            val pages = repository.pages(detail.chapters.first())
            assertTrue("External page list is empty", pages.isNotEmpty())
            val image = repository.pageFile(pages.first(), config.id)
            assertTrue(image.length() > 0)
            decodeMangaBitmap(image.readBytes())
            println("Verified external source: ${configs.size} language sources, ${books.size} catalogue items, ${detail.chapters.size} chapters, ${pages.size} pages; decoded ${image.length()} image bytes")
            extensions.remove(review.packageName)
        }
    }
}
