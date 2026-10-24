package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class ParserRuntimeTest {
    @Test fun pageScriptHasNoHostAccessAndAnInfiniteLoopIsStopped() = runBlocking {
        assertEquals("decoded", evaluateMangaScript("['de','coded'].join('')", 5_000))
        assertFails { evaluateMangaScript("Java.type('java.lang.System').getProperty('user.home')", 1_000) }
        assertFails { evaluateMangaScript("while(true) {}", 100) }
        assertEquals("after timeout", evaluateMangaScript("'after timeout'", 1_000))
    }

    @Test fun catalogueContainsUniqueSourceQualifiedIdentities() {
        val sources = KotatsuSource.catalogue()
        assertTrue(sources.size > 100)
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        assertTrue(sources.all { it.id.startsWith("kotatsu:") && it.title.isNotBlank() })
    }
}
