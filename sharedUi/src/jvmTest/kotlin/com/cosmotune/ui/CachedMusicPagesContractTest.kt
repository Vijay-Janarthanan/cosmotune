package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Music's main pages show what they last loaded at once, on the phone (and the TV, which shares its view model) and on the
 * desktop. The view model needs Android and the desktop root a window, so the wiring is read here; the cache itself is
 * `PageCacheTest` and `MusicPagesTest` (shared), the Releases page `CachedPagesInteractionTest`.
 */
class CachedMusicPagesContractTest {
    private val viewModel = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/ui/MainViewModel.kt"))
    private val activity = SourceContract.code(SourceContract.read("app/src/main/java/com/cosmotune/music/MainActivity.kt"))
    private val desktop = SourceContract.code(SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"))

    @Test fun thePhoneShowsLastRunsHomeAndSwapsInTheFeedInOneStep() {
        val load = SourceContract.functionBody(viewModel, "loadHome")
        assertTrue("MusicPages.savedHome(identity)" in load, "Home reads this listener's saved copy")
        assertTrue("_home.value !is UiState.Loading" in load, "the copy never replaces content fetched in this run")
        assertTrue("homeStaged = mutableListOf()" in load, "shelves that land meanwhile are staged")
        assertTrue("if (staged != null) _home.value = UiState.Success(emptyList())" in load && "staged?.forEach { it() }" in load,
            "the copy goes and the new page comes in one step, when the core feed answers")
        assertTrue("_refreshFailed.value += Feed.HOME" in load, "offline, the copy stays with a note")
        assertTrue("homeStaged?.let { staged -> staged += { publishHomeShelves(shelves, prepend) }; return }" in viewModel)
        assertTrue("MusicPages.saveHome(listenerKey(), state.data)" in viewModel, "Home as it settles is kept for the next start")
        assertTrue("MusicPages.forget(listenerKey())" in SourceContract.functionBody(viewModel, "signOut"), "a listener who signs out leaves no copy")
    }

    @Test fun thePhonesExploreLibraryAndPagesKeepTheirContent() {
        assertTrue("MusicPages.savedExplore()" in SourceContract.functionBody(viewModel, "loadExplore"))
        val explore = SourceContract.functionBody(viewModel, "fetchExplore")
        assertTrue("if (state is UiState.Error && _explore.value is UiState.Success) { _refreshFailed.value += Feed.EXPLORE; return }" in explore,
            "a failed refresh keeps the moods")
        assertTrue("MusicPages.savedLibrary(identity)" in SourceContract.functionBody(viewModel, "loadLibrary"))
        assertTrue("if (next is UiState.Error && _library.value is UiState.Success) { _refreshFailed.value += Feed.LIBRARY; return }" in
            SourceContract.functionBody(viewModel, "fetchLibrary"), "a failed refresh keeps the library")
        val open = SourceContract.functionBody(viewModel, "openDetail")
        assertTrue("recentDetail.get(browseId)" in open && "_detailStack.value += remembered ?: DetailPage(" in open,
            "an artist, album or playlist opened lately comes back as it was")
        assertTrue("recentDetail.evictAll()" in SourceContract.functionBody(viewModel, "clearListenerState"), "another listener's pages are forgotten")
        for (feed in listOf("HOME", "EXPLORE", "LIBRARY")) assertTrue("-> MainViewModel.Feed.$feed" in activity, "$feed notes a failed refresh")
        assertTrue("failedFeed in refreshFailed" in activity && "com.cosmotune.ui.states.RefreshFailedNote(onRetry = { viewModel.refresh(failedFeed) }" in activity)
    }

    @Test fun theDesktopShowsLastRunsPagesTheSameWay() {
        val load = SourceContract.functionBody(desktop, "loadHome")
        assertTrue("com.cosmotune.cache.MusicPages.savedHome(savedFor)" in load && "homeStage.start()" in load)
        assertTrue("val staged = homeStage.stop()" in load && "staged?.forEach { it() }" in load)
        assertTrue("homeRefreshFailed = true" in load)
        assertTrue("com.cosmotune.cache.MusicPages.saveHome(identity, shelves)" in desktop)
        assertTrue("com.cosmotune.cache.MusicPages.savedExplore()" in SourceContract.functionBody(desktop, "loadExplore"))
        assertTrue("com.cosmotune.cache.MusicPages.savedLibrary(libraryFor)" in desktop)
        assertTrue("if (homeRefreshFailed && destination == DesktopDestination.LISTEN_NOW" in desktop)
    }
}
