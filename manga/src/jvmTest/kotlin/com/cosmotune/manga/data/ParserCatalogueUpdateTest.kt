package com.cosmotune.manga.data

import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.test.*

class ParserCatalogueUpdateTest {
    @Test fun thePinnedCatalogueIncludesTheReviewedNewSources() {
        val names = MangaParserSource.entries.map { it.name.replace("_", "") }.toSet()
        assertTrue("MANHWAREAD" in names)
        assertTrue("KAPPABEAST" in names)
        assertTrue(names.any { it.startsWith("MANGAHERE") })
    }
}
