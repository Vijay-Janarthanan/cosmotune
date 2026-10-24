package com.cosmotune.manga.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cosmotune.manga.extensions.MangaExtensions
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaAndroidRuntimeTest {
    @Test fun signedApkLoadsOnlyAfterTrustAndBothSourceApisRoundTrip() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MangaStore(java.io.File(context.filesDir, "manga"), platformMangaFileProtection())
        val repository = MangaRepository(store) { config, parser -> MangaExtensions(context, store, parser).source(config) }
        val extensions = MangaExtensions(context, store, repository.context)
        store.update { it.copy(sources = it.sources.filterNot { source -> source.settings["package"] == "com.cosmotune.manga.fixture" }) }
        val sourcesBefore = store.state.value.sources
        val review = extensions.inspect { context.assets.open("source-api-fixture.apk") }
        assertEquals("com.cosmotune.manga.fixture", review.packageName)
        assertFalse(review.signers.isEmpty())
        assertEquals(sourcesBefore, store.state.value.sources)
        val configs = extensions.trust(review)
        assertEquals(2, configs.size)
        for (config in configs) {
            val book = repository.browse(config.id, "", 0).books.single()
            val detail = repository.chapters(book)
            assertEquals(book.id, detail.id)
            assertTrue(detail.sourceMemo.toString().contains("preserved"))
            assertEquals(1, detail.chapters.size)
            assertTrue(detail.chapters.single().sourceMemo.toString().contains("preserved"))
            val page = repository.pages(detail.chapters.single()).single()
            assertEquals("https://example.invalid/page.png", page.url)
            val request = (repository.source(config.id) as MangaImageTransport).imageRequest(page)
            if (config.name == "HTTP API fixture") {
                assertEquals("POST", page.requestMethod); assertEquals("POST", request.method)
                val body = okio.Buffer(); requireNotNull(request.body).writeTo(body)
                assertEquals("page=0&token=fixture-body", body.readUtf8())
                assertEquals("source-owned", request.header("X-Fixture-Request"))
            } else assertEquals("GET", request.method)
            store.save(detail)
        }
        val book = store.state.value.books.first { it.sourceId == configs.first().id }
        store.progress(MangaProgress(book.id, book.chapters.single().id, 1, 3))
        val restored = MangaStore(store.root, platformMangaFileProtection())
        assertNull(restored.recoveryMessage)
        assertEquals(1, restored.state.value.progress.getValue(book.id).page)
        store.source(configs.first().copy(enabled = false))
        val update = extensions.inspect { context.assets.open("source-api-fixture.apk") }
        assertFalse(extensions.trust(update).first { it.id == configs.first().id }.enabled)
    }

    @Test fun changingAReviewedApkIsRejectedBeforeLoading() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MangaStore(java.io.File(context.filesDir, "manga"), platformMangaFileProtection())
        val parser = ParserContext(store.root, store.protection) { store.state.value.sources }
        val extensions = MangaExtensions(context, store, parser)
        val review = extensions.inspect { context.assets.open("source-api-fixture.apk") }
        try {
            RandomAccessFile(review.stagedFile, "rw").use { it.seek(0); it.write(0) }
            try { extensions.trust(review); fail("A changed APK must not be loaded") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("changed")) }
        } finally { review.stagedFile.delete() }
    }
    @Test fun entryPointCannotResolveToAParentClassInsteadOfTheReviewedApk() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MangaStore(java.io.File(context.cacheDir, "entry-owner-${java.util.UUID.randomUUID()}"), platformMangaFileProtection())
        val extensions = MangaExtensions(context, store, ParserContext(store.root, store.protection) { store.state.value.sources })
        val review = extensions.inspect { context.assets.open("source-api-fixture.apk") }
        try { extensions.trust(review.copy(classes = listOf("java.lang.String"))); fail("Host entry was accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("reviewed APK")) }
        assertTrue(store.state.value.sources.isEmpty())
    }
    @Test fun bundledRankerMapsUnderFiveMegabytesAndRanksOnAndroid() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = java.io.File(context.cacheDir, "ranker-benchmark").apply { mkdirs() }
        val seed = com.cosmotune.music.data.model.Song("model-probe", "Billie Jean", "Michael Jackson", null, albumName = "Thriller")
        val candidates = (0 until 50).map { seed.copy(videoId = "model-candidate-$it", title = "Candidate $it") }
        com.cosmotune.music.data.recommendations.LocalRecommendations.configure(folder)
        repeat(30) { com.cosmotune.music.data.recommendations.LocalRecommendations.enhance(seed, candidates) { emptyList() } }
        val file = requireNotNull(folder.listFiles()?.firstOrNull { it.extension == "ctrec" })
        assertEquals(4_176_439L, file.length())
        assertTrue(file.length() < 5_000_000)
        val samples = (0 until 100).map {
            val start = System.nanoTime()
            val ranked = com.cosmotune.music.data.recommendations.LocalRecommendations.enhance(seed, candidates) { emptyList() }
            assertEquals(50, ranked.size)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        android.util.Log.i("CosmotuneModelProbe", "mapped=${file.length()}; candidates=50; median_ms=${samples[50]}; p95_ms=${samples[95]}")
        assertTrue("Ranking exceeded the emulator regression budget", samples[50] < 250)
    }

}
