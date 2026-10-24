package com.cosmotune.music.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistSignaturesTest {
    @Test fun requiresAnUsableHttpBaseWithoutQueryOrCredentials() {
        assertTrue(ArtistSignatureRepository.isValidBaseUrl("https://example.org/signatures/"))
        for (value in listOf("https://", "https://?token=secret", "file:///tmp/signature", "https://user:password@example.org", "https://example.org?key=value", "https://example.org#fragment")) {
            assertFalse(ArtistSignatureRepository.isValidBaseUrl(value), value)
        }
        assertNull(ArtistSignatureRepository.effectiveBaseUrl(false, "https://example.org"))
    }

    @Test fun ambiguousNamesAndNonHttpAssetsDoNotProduceAnImage() {
        assertNull(ArtistSignatureRepository.parse("""{"matches":[{"artist":"Name"}]}"""))
        assertNull(ArtistSignatureRepository.parse("""{"signatures":[{"url":"http-not-a-url"},{"url":"file:///private"}]}"""))
    }

    @Test fun knownLicencePrecedesLargerUnknownRaster() {
        val parsed = ArtistSignatureRepository.parse("""{"signatures":[
            {"url":"https://example.org/large.png","width":2000,"height":1000},
            {"asset":{"url":"https://example.org/ink.svg","format":"svg"},"license":{"status":"known"},"width":600,"height":100}
        ]}""")
        assertEquals("https://example.org/ink.svg", parsed?.imageUrl)
        assertEquals(6f, parsed?.aspect)
    }
}
