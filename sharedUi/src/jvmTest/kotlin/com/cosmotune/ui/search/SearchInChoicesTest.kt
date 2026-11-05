package com.cosmotune.ui.search

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.search.AddonSearch
import com.cosmotune.music.data.search.AddonSearchProgress
import com.cosmotune.music.data.search.AddonSearchSource
import com.cosmotune.music.data.search.AddonSourceResult
import com.cosmotune.music.data.search.AddonSourceStatus
import com.cosmotune.providers.ProviderAccessState
import com.cosmotune.providers.ProviderVerificationStatus
import com.cosmotune.providers.ProviderVerificationTarget
import com.cosmotune.ui.providers.acceptsVerificationPaste
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "Search in" chips: All, YouTube Music, then every installed source by name, each with a status dot. */
class SearchInChoicesTest {
    private fun source(id: String, name: String) = AddonSearchSource(name, id = id) { _, _ -> emptyList() }
    private val sources = listOf(
        com.cosmotune.music.data.search.AddonSearchSource("YouTube Music", id = AddonSearch.YOUTUBE) { _, _ -> emptyList() },
        source("addon-1", "Lossless add-on"), source("sflx-qobuz", "Qobuz"), source("sflx-tidal", "Tidal"), source("sflx-amazon", "Amazon"),
    )

    @Test fun allAndYouTubeLeadThenEveryInstalledSourceOnce() {
        val choices = searchInChoices(sources + source("addon-1", "Duplicate"), progress = null)
        assertEquals(listOf(AddonSearch.ALL, AddonSearch.YOUTUBE, "addon-1", "sflx-qobuz", "sflx-tidal", "sflx-amazon"), choices.map { it.key })
        assertEquals("Qobuz", choices[3].label)
    }

    @Test fun eachChipShowsItsSourcesStateInTheCurrentSearch() {
        val progress = AddonSearchProgress("q", listOf(
            AddonSourceResult("addon-1", "Lossless add-on", AddonSourceStatus.DONE, listOf(Song("src:addon-1::1", "t", "a", null))),
            AddonSourceResult("sflx-qobuz", "Qobuz", AddonSourceStatus.NEEDS_VERIFICATION),
            AddonSourceResult("sflx-tidal", "Tidal", AddonSourceStatus.SEARCHING),
            AddonSourceResult("sflx-amazon", "Amazon", AddonSourceStatus.FAILED, reason = "HTTP 500"),
        ))
        val status = searchInChoices(sources, progress).associate { it.key to it.status }
        assertEquals(SearchInStatus.SEARCHING, status[AddonSearch.ALL])
        assertEquals(SearchInStatus.READY, status["addon-1"])
        assertEquals(SearchInStatus.NEEDS_VERIFICATION, status["sflx-qobuz"])
        assertEquals(SearchInStatus.SEARCHING, status["sflx-tidal"])
        assertEquals(SearchInStatus.ERROR, status["sflx-amazon"])
        // While its window is open the chip says so.
        val verifying = searchInChoices(sources, progress, mapOf("qobuz" to ProviderVerificationStatus.OPEN)).first { it.key == "sflx-qobuz" }
        assertEquals(SearchInStatus.VERIFYING, verifying.status)
    }

    @Test fun beforeAnySearchAProvidersChipShowsItsSavedConnection() {
        val status = searchInChoices(sources, null, access = { name ->
            if (name == "qobuz") ProviderAccessState.VERIFICATION_REQUIRED else ProviderAccessState.CONNECTED
        }).associate { it.key to it.status }
        assertEquals(SearchInStatus.NEEDS_VERIFICATION, status["sflx-qobuz"])
        assertEquals(SearchInStatus.READY, status["sflx-tidal"])
        assertEquals(SearchInStatus.IDLE, status[AddonSearch.ALL])
    }

    @Test fun sectionHeadersSayWhatEachSourceDid() {
        assertEquals("Searching…", sectionStatus(AddonSourceResult("a", "A", AddonSourceStatus.SEARCHING)))
        assertEquals("No results", sectionStatus(AddonSourceResult("a", "A", AddonSourceStatus.DONE)))
        assertEquals("Failed · HTTP 500", sectionStatus(AddonSourceResult("a", "A", AddonSourceStatus.FAILED, reason = "HTTP 500")))
        assertEquals("Needs verification", sectionStatus(AddonSourceResult("a", "A", AddonSourceStatus.NEEDS_VERIFICATION)))
        assertEquals("sflx-qobuz".let(::providerNameOf), "qobuz")
        assertEquals(null, providerNameOf("addon-1"))
    }

    @Test fun aPastedResultMustBelongToTheActiveChallenge() {
        val target = ProviderVerificationTarget("https://verify.example/challenge?id=1", "cosmotune://provider-auth/session-grant",
            "state-0123456789abcdef", "Fixture", listOf("verify.example"), System.currentTimeMillis() + 60_000, signed = true)
        assertTrue(acceptsVerificationPaste(target, " cosmotune://provider-auth/session-grant?state=state-0123456789abcdef&grant=abc "))
        assertFalse(acceptsVerificationPaste(target, "cosmotune://provider-auth/session-grant?state=another-state-0000000&grant=abc"), "another challenge's state")
        assertFalse(acceptsVerificationPaste(target, "https://elsewhere.example/?state=state-0123456789abcdef&grant=abc"))
        assertTrue(acceptsVerificationPaste(target, "grant=abc_DEF-123"), "a signed operator's bare grant, as the manual field accepts")
        assertFalse(acceptsVerificationPaste(target.copy(signed = false), "grant=abc"))
        assertFalse(acceptsVerificationPaste(target, ""))
    }
}
