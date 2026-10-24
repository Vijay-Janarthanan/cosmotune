package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

/** Explicit opt-in only. A pinned parser's inclusion never implies its public website is available. */
class MangaLiveSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun publicSourceCanBrowseLoadChaptersAndFetchOneImage() = runBlocking<Unit> {
        val names = System.getProperty("cosmotune.manga.live", "").split(',').filter { it.isNotBlank() }
        assumeTrue("Set -Pcosmotune.manga.live=SOURCE to check a real website", names.isNotEmpty())
        for (name in names) withTimeout(90_000) {
            val descriptor = KotatsuSource.catalogue().first { it.id == "kotatsu:$name" }
            val store = MangaStore(temporary.newFolder())
            val config = MangaSourceConfig(descriptor.id, name = descriptor.title, language = descriptor.language)
            store.source(config)
            val repository = MangaRepository(store)
            val source = repository.source(config.id)
            val books = source.browse().books
            assertTrue(books.isNotEmpty(), "$name catalogue is empty")
            val book = source.details(books.first())
            assertTrue(book.chapters.isNotEmpty(), "$name returned no chapters")
            val chapter = book.chapters.last()
            val pages = source.pages(chapter)
            assertTrue(pages.isNotEmpty(), "$name returned no pages")
            val image = repository.pageFile(pages.first(), config.id)
            assertTrue(image.length() > 0, "$name returned an empty image")
            decodeMangaBitmap(image.readBytes())
            println("Verified $name: ${books.size} catalogue items, ${book.chapters.size} chapters, ${pages.size} page links; one image decoded (${image.length()} bytes)")
        }
    }

    @Test fun currentKeiyoushiStoreDecodesItsRealProtobufIndex() = runBlocking<Unit> {
        assumeTrue("Set -Pcosmotune.manga.storeLive=true for a real store check", System.getProperty("cosmotune.manga.storeLive").toBoolean())
        val result = MangaExtensionStores().fetch("https://github.com/keiyoushi/extensions/raw/repo/index.pb",
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2")
        assertTrue(result.extensions.size > 100)
        assertTrue(result.extensions.all { it.apkUrl.startsWith("https://") })
        println("Verified ${result.store.name}: ${result.extensions.size} extension packages; APIs ${result.extensions.map { it.api }.distinct()}; publisher fingerprint matches official metadata")
    }
}
