package com.cosmotune.music.data.recommendations

import java.nio.file.Files
import kotlin.test.*

class DiscoveryPreferencesTest {
    @Test fun regionAndMusicLanguagePersistIndependentlyAndRejectInvalidCountries() {
        val directory = Files.createTempDirectory("discovery-test").toFile()
        try {
            val file = directory.resolve("discovery.json")
            DiscoveryPreferences.configure(file)
            DiscoveryPreferences.save(DiscoveryRegion("ZZ", "ta"))
            assertEquals("Global", DiscoveryPreferences.state.value.countryLabel)
            assertEquals("Tamil", DiscoveryPreferences.state.value.languageLabel)
            assertTrue(file.readText().contains("\"musicLanguage\":\"ta\""))
            assertFailsWith<IllegalArgumentException> { DiscoveryPreferences.save(DiscoveryRegion("not-a-country", "en")) }
            assertEquals("ZZ", DiscoveryPreferences.state.value.country)
        } finally { directory.deleteRecursively() }
    }
}
