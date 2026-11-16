package com.cosmotune.manga.data

import org.koitharu.kotatsu.parsers.model.MangaParserSource
import kotlin.test.*

@OptIn(org.koitharu.kotatsu.parsers.DirectParsersAccess::class)
class ParserCatalogueUpdateTest {
    @Test fun thePinnedCatalogueIncludesTheReviewedNewSources() {
        val names = MangaParserSource.entries.map { it.name.replace("_", "") }.toSet()
        assertTrue("MANHWAREAD" in names)
        assertTrue("KAPPABEAST" in names)
        assertTrue(names.any { it.startsWith("MANGAHERE") })
        assertTrue("COMIZYIO" in names, "the reviewed a906 catalogue includes Comizy")
    }
}
