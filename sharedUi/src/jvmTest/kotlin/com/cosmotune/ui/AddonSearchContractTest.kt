package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BC-31: how the two hosts wire the Add-ons source of Search, which cannot run here (the view model needs Android, the desktop
 * root a window). The search itself is `AddonSearchTest` (shared), the provider packages `ProviderAddonSearchTest`, the desktop's
 * choice of sources `DesktopAddonSearchTest`, the pages `AddonSearchInteractionTest`.
 */
class AddonSearchContractTest {
    private val viewModel = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/ui/MainViewModel.kt"))
    private val activity = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/MainActivity.kt"))
    private val desktop = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"))

    @Test fun thePhoneAsksTheAddonsOnlyOnSubmitAndThroughTheSharedSearch() {
        val typing = SourceContract.functionBody(viewModel, "onQueryChange")
        assertTrue("if (_searchSource.value != SearchSource.YOUTUBE) return" in typing, "no completion request while the add-ons are searched")
        assertTrue("if (AddonSearch.shouldSearch(q, submitted = true)) runAddonSearch(q)" in SourceContract.functionBody(viewModel, "submitSearch"))
        val run = SourceContract.functionBody(viewModel, "runAddonSearch")
        assertTrue("AddonSearch.select(addonSearchSources(), searchChoice.lastAddonChoice)" in run, "All, or the one source picked in Search in")
        assertTrue("addonSearch.start(q, sources)" in run, "every source at once, each reported as it answers")
        val sources = viewModel.substringAfter("fun addonSearchSources()").substringBefore("fun setSearchIn(")
        assertTrue(
            "SourceRegistry.active().mapNotNull" in sources && "is AddonSource ->" in sources &&
                "is com.cosmotune.music.data.sources.SubsonicSource ->" in sources && "else -> AddonSearchSource(" in sources &&
                "manager?.addonSearchSources()" in sources,
            "the add-on search covers every enabled source of the list (add-ons, modules, JioSaavn, Subsonic) and the provider packages",
        )
        assertTrue("youTubeMusicSearchSource(" in sources, "All includes YouTube Music's anonymous song search")
        assertFalse("runSearch(" in run, "the add-on search never falls through to the YouTube Music results page")
        assertTrue("ProviderAutoVerification.verified.collect { addonSearch.retry(\"sflx-\$it\") }" in viewModel,
            "a provider that just verified is asked again")
    }

    @Test fun bothRootsHostTheVerificationWindowAndTheSearchInPicker() {
        for ((name, root) in listOf("MainActivity" to activity, "DesktopApp" to desktop)) {
            assertTrue("com.cosmotune.ui.providers.ProviderVerificationHost()" in root, "$name opens verification windows")
            assertTrue("searchIn = com.cosmotune.ui.search.rememberSearchInState(" in root, "$name shows Search in")
        }
        assertTrue("ProviderAutoVerification.verified.collect { addonSearchSession.retry(\"sflx-\$it\") }" in desktop)
    }

    @Test fun thePhoneOffersTheAddonsBesideYouTubeAndTheLibrary() {
        assertTrue(Regex("""stringResource\(R\.string\.search_source_youtube\),\s*stringResource\(R\.string\.library\),\s*stringResource\(R\.string\.search_source_addons\),""")
            .containsMatchIn(activity), "the three options in SearchSource order")
        assertEquals("YOUTUBE, LIBRARY, ADDONS", Regex("""enum class SearchSource \{ ([A-Z, ]+) \}""").find(viewModel)?.groupValues?.get(1))
        for (wire in listOf("searchingAddons = searchSource == SearchSource.ADDONS", "addonResults = addonResults", "onAddonSongClick = ", "\"addons:search\"")) {
            assertTrue(wire in activity, "MainActivity passes $wire")
        }
    }

    @Test fun theDesktopAsksTheAddonsOnlyOnSubmitAndSendsNothingWhileTyping() {
        // Both typeahead passes (completions and media rows) stand down for the Add-ons source.
        assertEquals(2, Regex("""if \(!searchTyping \|\| query\.isBlank\(\) \|\| searchAddons\)""").findAll(desktop).count())
        val search = SourceContract.functionBody(desktop, "search")
        assertTrue("AddonSearch.shouldSearch(text, submitted = true)" in search && "DesktopSourceRegistry.addonSearchSources()" in search)
        assertTrue("addonSearchSession.start(text, sources)" in search)
        assertTrue(search.indexOf("if (searchAddons)") < search.indexOf("DesktopMusicSources.search("), "the add-ons are decided before YouTube is asked")
        for (wire in listOf("searchingAddons = searchAddons", "addonResults = addonResults", "onAddonHistoryClick = ::runSearch", "\"addons:search\"")) {
            assertTrue(wire in desktop, "DesktopApp passes $wire")
        }
    }

    @Test fun theSwitchHandsEveryAddonParameterToThePage() {
        val switch = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/SearchSwitch.kt")
        val names = listOf("searchingAddons", "addonResults", "addonHistory", "onAddonHistoryClick", "onAddonHistoryRemove", "onAddonHistoryClear", "onAddonSongClick")
        val params = SourceContract.names(SourceContract.parameters(switch, "SearchSwitch"))
        assertTrue(params.containsAll(names))
        val code = SourceContract.code(switch)
        for (name in names) assertEquals(1, Regex("""\b$name = $name\b""").findAll(code).count(), "$name reaches the page, once (the old screen is gone)")
    }
}
