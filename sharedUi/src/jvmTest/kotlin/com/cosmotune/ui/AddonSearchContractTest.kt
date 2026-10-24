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
        assertTrue("AddonSearch.search(q, sources)" in run)
        assertTrue("SourceRegistry.active().filterIsInstance<AddonSource>()" in run && "addonSearchSources()" in run)
        assertFalse("runSearch(" in run, "the add-on search never falls through to YouTube Music")
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
        assertTrue(search.indexOf("if (searchAddons)") < search.indexOf("DesktopMusicSources.search("), "the add-ons are decided before YouTube is asked")
        for (wire in listOf("searchingAddons = searchAddons", "addonResults = addonResults", "onAddonHistoryClick = ::runSearch", "\"addons:search\"")) {
            assertTrue(wire in desktop, "DesktopApp passes $wire")
        }
    }

    @Test fun theSwitchHandsEveryAddonParameterToBothPages() {
        val switch = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/SearchSwitch.kt")
        val names = listOf("searchingAddons", "addonResults", "addonHistory", "onAddonHistoryClick", "onAddonHistoryRemove", "onAddonHistoryClear", "onAddonSongClick")
        val params = SourceContract.names(SourceContract.parameters(switch, "SearchSwitch"))
        assertTrue(params.containsAll(names))
        val code = SourceContract.code(switch)
        for (name in names) assertEquals(2, Regex("""\b$name = $name\b""").findAll(code).count(), "$name reaches the new page and the old screen")
    }
}
