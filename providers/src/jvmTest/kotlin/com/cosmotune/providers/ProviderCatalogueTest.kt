package com.cosmotune.providers

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ProviderCatalogueTest {
    @Test fun structuredArtistIdentityWinsOverAConflictingFlatCreditAndArtworkCannotEscapeToTheImageLoader() {
        val raw = Json.parseToJsonElement("""{"id":"song","name":"A recording","artist":"Wrong artist","artists":[{"id":"artist","name":"Tyler, the Creator"}],"cover_url":"file:///private/secret.png"}""").jsonObject
        val track = requireNotNull(ProviderCatalogue.track("fixture", raw))
        assertEquals("Tyler, the Creator", track.artist)
        assertEquals("artist", ProviderBrowseKey.parse(requireNotNull(track.artists.single().browseId))?.id)
        assertNull(track.song().thumbnailUrl)
    }

    @Test fun duplicateArtistAlbumsCannotCreateDuplicateKeysInTheCatalogueList() {
        val raw = Json.parseToJsonElement("""{"id":"artist","name":"Singer","albums":[{"id":"album","name":"One edition"},{"id":"album","name":"One edition"}]}""").jsonObject
        assertEquals(1, ProviderCatalogue.collection("fixture", "artist", raw)?.collections?.size)
    }

    @Test fun malformedAndOversizedIdentitiesDoNotBecomeNativeBrowseLinks() {
        assertNull(ProviderBrowseKey.parse("provider:fixture:unknown:YQ"))
        assertNull(ProviderBrowseKey.parse("provider:fixture:album:" + "A".repeat(50_000)))
        assertFailsWith<IllegalArgumentException> { ProviderBrowseKey("../outside", "album", "one") }
        assertFailsWith<IllegalArgumentException> { ProviderBrowseKey("fixture", "album", "") }
        assertNull(ProviderCatalogue.track("fixture", buildJsonObject { put("id", "a".repeat(10_000)); put("name", "Oversized identity") }))
    }
}
