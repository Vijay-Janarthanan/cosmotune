package com.cosmotune.providers

import java.time.LocalDate
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ProviderDiscoveryMetadataTest {
    private fun track(json: String) = requireNotNull(ProviderCatalogue.track("fixture", Json.parseToJsonElement(json).jsonObject))
    @Test fun recordsOnlyStatedLanguageFullReleaseDateAndIsrc() {
        val known = ProviderDiscoveryMetadata.candidate(track("""{"id":"one","title":"Song","artist":"Singer","language":"Tamil","release_date":"2026-10-30T12:00:00Z","isrc":"INABC2600001"}"""), 2)
        assertEquals("ta", known.language)
        assertEquals(LocalDate.of(2026, 10, 30), known.releasedOn)
        assertEquals("INABC2600001", known.isrc)
        assertEquals("fixture", known.provider)
        assertTrue(known.song.videoId.startsWith("src:sflx-fixture::"))
    }
    @Test fun searchWordingSingerAndYearOnlyDateCannotInventFreshnessOrLanguage() {
        val unknown = ProviderDiscoveryMetadata.candidate(track("""{"id":"two","title":"Tamil new releases 2026","artist":"Tamil Singer","release_date":"2026"}"""), 0)
        assertNull(unknown.language); assertNull(unknown.releasedOn); assertNull(unknown.isrc)
        val album = ProviderDiscoveryMetadata.candidate(track("""{"id":"three","title":"Song","artist":"Singer","album":{"name":"Album","language_code":"te","releaseDate":"2026-02-30"}}"""), 0)
        assertEquals("te", album.language); assertNull(album.releasedOn)
    }
}
